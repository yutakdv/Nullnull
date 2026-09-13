package io.nullnull.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.catalog.application.CatalogHoursQuery;
import io.nullnull.catalog.application.CatalogHoursQuery.CatalogOpeningWindow;
import io.nullnull.catalog.application.CuratedHoursImporter;
import io.nullnull.catalog.application.CuratedHoursPlan;
import io.nullnull.catalog.application.CuratedHoursPlan.CuratedPlaceHours;
import io.nullnull.catalog.application.CuratedHoursPlan.CuratedWindow;
import io.nullnull.catalog.application.CuratedHoursPlan.CurationException;
import io.nullnull.catalog.application.CuratedHoursPlan.Outcome;
import io.nullnull.catalog.application.CuratedHoursPlan.State;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * BA-025: the operations script that gives curated opening hours a producer.
 *
 * <p>Everything downstream of this was already built and already green - {@code getCandidateTripMatches}
 * is registered, it reads {@code CatalogHoursQuery}, and that reads {@code place_hours_windows}. What
 * was missing was anything that writes a row, so the evaluator saw an empty map and answered UNKNOWN
 * for every date. This is the missing writer, and the tests below are about the two ways it could
 * quietly say more than the curator did.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-025 curated opening hours import")
class CuratedHoursImportIT {

    private static final Instant READ_AT = Instant.parse("2026-09-14T02:00:00Z");
    private static final LocalDate DAY_ONE = LocalDate.of(2026, 10, 5);
    private static final LocalDate DAY_TWO = DAY_ONE.plusDays(1);
    private static final String PAGE = "https://royal.cha.go.kr/example/hours";

    @Autowired
    CuratedHoursImporter importer;

    @Autowired
    CatalogHoursQuery hours;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void removeOnlyOwnFixtures() {
        jdbc.update("DELETE FROM place_hours_windows");
        jdbc.update("DELETE FROM place_hours_observations");
        jdbc.update("DELETE FROM places");
    }

    @Test
    @DisplayName("BA-025-T1 a plan that is refused writes nothing at all")
    void aRefusedPlanLeavesNothingBehind() {
        UUID place = place();

        // Every one of these is rejected while the file is being read, before a transaction opens.
        // The property asserted is single - no partial write - and the inputs are the ways an operator
        // can get the file wrong.
        assertThatThrownBy(() -> entry(place, Outcome.OBSERVED,
                open(DAY_ONE, LocalTime.of(9, 0), LocalTime.of(17, 0)),
                open(DAY_ONE, LocalTime.of(10, 0), LocalTime.of(18, 0))))
                .isInstanceOf(CurationException.class).hasMessageContaining("two windows for one date");
        assertThatThrownBy(() -> new CuratedPlaceHours(place, "http://insecure.example", READ_AT,
                Outcome.OBSERVED, List.of(open(DAY_ONE, LocalTime.of(9, 0), LocalTime.of(17, 0)))))
                .isInstanceOf(CurationException.class).hasMessageContaining("https");
        assertThatThrownBy(() -> open(DAY_ONE, LocalTime.of(22, 0), LocalTime.of(2, 0)))
                .isInstanceOf(CurationException.class).hasMessageContaining("closesAt must be after");
        assertThatThrownBy(() -> entry(place, Outcome.OBSERVED))
                .isInstanceOf(CurationException.class).hasMessageContaining("states no window");

        // A place the catalog does not have is refused by the table rather than by the file, and the
        // transaction is what makes that the same outcome: the run ends with nothing written.
        assertThatThrownBy(() -> importer.importPlan(new CuratedHoursPlan(List.of(
                entry(UUID.randomUUID(), Outcome.OBSERVED,
                        open(DAY_ONE, LocalTime.of(9, 0), LocalTime.of(17, 0)))))))
                .isInstanceOf(DataAccessException.class);

        assertThat(rows("place_hours_observations")).isZero();
        assertThat(rows("place_hours_windows")).isZero();
    }

    @Test
    @DisplayName("BA-025-T2 applying the same plan again leaves one current reading")
    void reapplyingThePlanConvergesOnOneCurrentReading() {
        UUID place = place();
        CuratedHoursPlan plan = new CuratedHoursPlan(List.of(entry(place, Outcome.OBSERVED,
                open(DAY_ONE, LocalTime.of(9, 0), LocalTime.of(17, 0)))));

        importer.importPlan(plan);
        assertThatCode(() -> importer.importPlan(plan)).doesNotThrowAnyException();

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM place_hours_observations
                 WHERE place_id = ? AND superseded_at IS NULL
                """, Long.class, place)).isEqualTo(1L);
        // And the reading that survived is usable: a second run must not leave the place readable as
        // nothing, which is what a delete-then-insert that lost its windows would do.
        assertThat(hours.windowsFor(place, DAY_ONE, DAY_ONE, READ_AT)).containsOnlyKeys(DAY_ONE);
    }

    @Test
    @DisplayName("BA-025-T3 a new reading supersedes the previous one without deleting it")
    void thePreviousReadingIsKept() {
        UUID place = place();
        importer.importPlan(new CuratedHoursPlan(List.of(entry(place, Outcome.OBSERVED,
                open(DAY_ONE, LocalTime.of(9, 0), LocalTime.of(17, 0))))));
        importer.importPlan(new CuratedHoursPlan(List.of(entry(place, Outcome.OBSERVED,
                open(DAY_ONE, LocalTime.of(10, 0), LocalTime.of(18, 0))))));

        // Two rows, one of them retired. Converging by deleting the old row would satisfy T2 and lose
        // the evidence that was current yesterday - this is the assertion that forbids it.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM place_hours_observations WHERE place_id = ?", Long.class, place))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM place_hours_observations
                 WHERE place_id = ? AND superseded_at IS NOT NULL
                """, Long.class, place)).isEqualTo(1L);
        assertThat(hours.windowsFor(place, DAY_ONE, DAY_ONE, READ_AT).get(DAY_ONE).opensAt())
                .isEqualTo(LocalTime.of(10, 0));
    }

    @Test
    @DisplayName("BA-025-T4 a date the plan does not mention gets no window")
    void anUnmentionedDateStaysUnverified() {
        UUID place = place();
        importer.importPlan(new CuratedHoursPlan(List.of(entry(place, Outcome.OBSERVED,
                open(DAY_ONE, LocalTime.of(9, 0), LocalTime.of(17, 0))))));

        Map<LocalDate, CatalogOpeningWindow> windows = hours.windowsFor(place, DAY_ONE, DAY_TWO, READ_AT);

        // The blank the curator left has to stay blank. A script that filled day two with anything -
        // a closure, a copy of day one - would be asserting hours nobody read, which is the invariant
        // 9 case V025 was shaped to keep impossible.
        assertThat(windows).containsOnlyKeys(DAY_ONE);
        assertThat(windows.get(DAY_ONE).state()).isEqualTo(CatalogOpeningWindow.State.OPEN);
    }

    @Test
    @DisplayName("BA-025-T5 a reading that settled nothing is recorded as evidence with no window")
    void anUnsettledReadingIsStillEvidence() {
        UUID place = place();

        importer.importPlan(new CuratedHoursPlan(List.of(entry(place, Outcome.AMBIGUOUS))));

        // "Someone looked and could not tell" is a different fact from "nobody looked", and the file
        // has to be able to say the first. Without this the curator's only options are inventing a
        // day or dropping the place, and both lose the distinction the two tables exist for.
        assertThat(jdbc.queryForObject("""
                SELECT outcome FROM place_hours_observations WHERE place_id = ? AND superseded_at IS NULL
                """, String.class, place)).isEqualTo("AMBIGUOUS");
        assertThat(rows("place_hours_windows")).isZero();
        assertThat(hours.windowsFor(place, DAY_ONE, DAY_TWO, READ_AT)).isEmpty();
    }

    private static CuratedPlaceHours entry(UUID placeId, Outcome outcome, CuratedWindow... windows) {
        return new CuratedPlaceHours(placeId, PAGE, READ_AT, outcome, List.of(windows));
    }

    private static CuratedWindow open(LocalDate date, LocalTime opensAt, LocalTime closesAt) {
        return new CuratedWindow(date, State.OPEN, opensAt, closesAt);
    }

    private long rows(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private UUID place() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO places
                    (id, canonical_name, category_code, latitude, longitude, region_code, status,
                     created_at, updated_at)
                VALUES (?, '경복궁 테스트', 'A0201', 37.579617, 126.977041, '11', 'ACTIVE', ?, ?)
                """, id, Timestamp.from(READ_AT), Timestamp.from(READ_AT));
        return id;
    }
}
