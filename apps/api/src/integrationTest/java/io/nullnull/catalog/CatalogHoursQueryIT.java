package io.nullnull.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.catalog.application.CatalogHoursQuery;
import io.nullnull.catalog.application.CatalogHoursQuery.CatalogOpeningWindow;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reading curated opening hours for a trip's dates, which is what BA-042 hands the slot evaluator.
 *
 * <p>Every case here is about one distinction: a date with no window is unverified, never closed.
 * The map this returns has no entry for such a date and no way to express one - {@code State} has
 * OPEN and CLOSED only - so a caller cannot turn "nobody established this" into "open" or into
 * "closed" without inventing a value the type does not have.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-022 curated hours read for a trip range")
class CatalogHoursQueryIT {

    private static final Instant NOW = Instant.parse("2026-09-13T00:00:00Z");
    private static final Instant STALE_AT = Instant.parse("2026-10-13T00:00:00Z");
    private static final LocalDate DAY_ONE = LocalDate.of(2026, 10, 5);
    private static final LocalDate DAY_TWO = DAY_ONE.plusDays(1);
    private static final LocalDate DAY_THREE = DAY_ONE.plusDays(2);
    private static final String SOURCE = "NULLNULL_CURATED_HOURS";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CatalogHoursQuery hours;

    @AfterEach
    void removeOnlyOwnFixtures() {
        jdbc.update("DELETE FROM place_hours_windows");
        jdbc.update("DELETE FROM place_hours_observations");
        jdbc.update("DELETE FROM places");
    }

    @Test
    @DisplayName("a date nobody established is absent, and a verified closure is present as CLOSED")
    void anUnreadDateIsAbsentAndAVerifiedClosureIsNot() {
        UUID place = activePlace();
        UUID observation = observation(place, "OBSERVED", NOW, STALE_AT);
        open(observation, DAY_ONE, LocalTime.of(9, 0), LocalTime.of(17, 0));
        closed(observation, DAY_TWO);
        // DAY_THREE is deliberately not written: its status depends on a condition the curator could
        // not resolve, which is the case V025 exists to keep distinguishable.

        Map<LocalDate, CatalogOpeningWindow> windows = hours.windowsFor(place, DAY_ONE, DAY_THREE, NOW);

        assertThat(windows).containsOnlyKeys(DAY_ONE, DAY_TWO);
        assertThat(windows.get(DAY_TWO).state()).isEqualTo(CatalogOpeningWindow.State.CLOSED);
        assertThat(windows.get(DAY_TWO).opensAt()).isNull();
    }

    @Test
    @DisplayName("an OPEN window carries the times that were read")
    void anOpenWindowCarriesItsTimes() {
        UUID place = activePlace();
        open(observation(place, "OBSERVED", NOW, STALE_AT), DAY_ONE, LocalTime.of(9, 0), LocalTime.of(17, 0));

        CatalogOpeningWindow window = hours.windowsFor(place, DAY_ONE, DAY_ONE, NOW).get(DAY_ONE);

        assertThat(window.state()).isEqualTo(CatalogOpeningWindow.State.OPEN);
        assertThat(window.opensAt()).isEqualTo(LocalTime.of(9, 0));
        assertThat(window.closesAt()).isEqualTo(LocalTime.of(17, 0));
    }

    @Test
    @DisplayName("a superseded reading is not read")
    void supersededEvidenceIsNotRead() {
        UUID place = activePlace();
        UUID observation = observation(place, "OBSERVED", NOW, STALE_AT);
        open(observation, DAY_ONE, LocalTime.of(9, 0), LocalTime.of(17, 0));
        assertThat(hours.windowsFor(place, DAY_ONE, DAY_ONE, NOW)).isNotEmpty();

        jdbc.update("UPDATE place_hours_observations SET superseded_at = ? WHERE id = ?",
                java.sql.Timestamp.from(NOW), observation);

        assertThat(hours.windowsFor(place, DAY_ONE, DAY_ONE, NOW)).isEmpty();
    }

    @Test
    @DisplayName("a reading that has gone stale is not read")
    void staleEvidenceIsNotRead() {
        UUID place = activePlace();
        open(observation(place, "OBSERVED", NOW, STALE_AT), DAY_ONE, LocalTime.of(9, 0), LocalTime.of(17, 0));

        // A-023: the threshold is when the reading stops being trusted, so an instant at or past it
        // is past it - the same boundary place_relations uses for expires_at.
        assertThat(hours.windowsFor(place, DAY_ONE, DAY_ONE, STALE_AT)).isEmpty();
        assertThat(hours.windowsFor(place, DAY_ONE, DAY_ONE, STALE_AT.minusSeconds(1))).isNotEmpty();
    }

    @Test
    @DisplayName("dates outside the asked range are not returned")
    void datesOutsideTheRangeAreNotReturned() {
        UUID place = activePlace();
        UUID observation = observation(place, "OBSERVED", NOW, STALE_AT);
        open(observation, DAY_ONE, LocalTime.of(9, 0), LocalTime.of(17, 0));
        open(observation, DAY_THREE, LocalTime.of(9, 0), LocalTime.of(17, 0));

        assertThat(hours.windowsFor(place, DAY_TWO, DAY_THREE, NOW)).containsOnlyKeys(DAY_THREE);
    }

    @Test
    @DisplayName("a place nobody has curated yields an empty map, which is not a closed trip")
    void anUncuratedPlaceYieldsNothing() {
        assertThat(hours.windowsFor(activePlace(), DAY_ONE, DAY_THREE, NOW)).isEmpty();
    }

    private UUID activePlace() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO places
                    (id, canonical_name, category_code, latitude, longitude, region_code, status,
                     created_at, updated_at)
                VALUES (?, '경복궁 테스트', 'A0201', 37.579617, 126.977041, '11', 'ACTIVE', ?, ?)
                """, id, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        return id;
    }

    private UUID observation(UUID placeId, String outcome, Instant observedAt, Instant staleAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO place_hours_observations
                    (id, place_id, source_code, source_registry_version, outcome, observed_at,
                     evidence_url, stale_at, created_at)
                VALUES (?, ?, ?, 1, ?, ?, 'https://royal.cha.go.kr/example/hours', ?, ?)
                """, id, placeId, SOURCE, outcome, java.sql.Timestamp.from(observedAt),
                java.sql.Timestamp.from(staleAt), java.sql.Timestamp.from(observedAt));
        return id;
    }

    private void open(UUID observationId, LocalDate date, LocalTime opensAt, LocalTime closesAt) {
        jdbc.update("""
                INSERT INTO place_hours_windows (id, observation_id, effective_on, state, opens_at, closes_at)
                VALUES (?, ?, ?, 'OPEN', ?, ?)
                """, UUID.randomUUID(), observationId, java.sql.Date.valueOf(date),
                java.sql.Time.valueOf(opensAt), java.sql.Time.valueOf(closesAt));
    }

    private void closed(UUID observationId, LocalDate date) {
        jdbc.update("""
                INSERT INTO place_hours_windows (id, observation_id, effective_on, state, opens_at, closes_at)
                VALUES (?, ?, ?, 'CLOSED', NULL, NULL)
                """, UUID.randomUUID(), observationId, java.sql.Date.valueOf(date));
    }
}
