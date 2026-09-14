package io.nullnull.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.testsupport.TestcontainersConfiguration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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
 * BA-022 step 3, the "영업 확인 근거" half: schema safety only.
 *
 * <p>What is preserved is the check, not the value - "when, and against which page, did a reviewer
 * establish this POI's operating status". A window therefore cannot exist without the evidence row
 * it came from, and an evidence row with no window at all is a legal, meaningful state: someone
 * looked and could not settle it. That distinction is invariant 6 applied to opening hours, and a
 * schema that stored only windows would flatten it into the same absence as "nobody looked".
 *
 * <p>The source is V025's own {@code NULLNULL_CURATED_HOURS} registry row, seeded there because
 * A-023 refuses a source with no staleness threshold and A-032 settled that threshold at P30D. What
 * V025 does not seed is readings: which places get curated is A-031's operations script. So these
 * tests write their own observations, which is also the only way to exercise the guards.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-022 curated opening-hours evidence")
class PlaceHoursEvidenceIT {

    private static final Instant NOW = Instant.parse("2026-09-13T00:00:00Z");
    private static final Instant STALE_AT = Instant.parse("2026-10-13T00:00:00Z");
    private static final String SOURCE = "NULLNULL_CURATED_HOURS";
    private static final long SOURCE_VERSION = 1L;
    private static final String EVIDENCE_URL = "https://royal.cha.go.kr/example/hours";
    private static final LocalDate DATE = LocalDate.of(2026, 10, 5);

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void removeOnlyOwnFixtures() {
        jdbc.update("DELETE FROM place_hours_windows");
        jdbc.update("DELETE FROM place_hours_observations");
        jdbc.update("DELETE FROM places");
    }

    @Test
    @DisplayName("BA-022-T4 an opening-hours window cannot be stored without the evidence row it came from")
    void aWindowRequiresItsEvidenceRow() {
        UUID place = activePlace();
        UUID observation = observation(place, "OBSERVED");

        assertThatThrownBy(() -> window(UUID.randomUUID(), DATE, "OPEN", LocalTime.of(9, 0), LocalTime.of(17, 0)))
                .isInstanceOf(DataAccessException.class);

        // The negative control: the identical window is accepted once it names a real observation,
        // so the rejection above is the missing evidence and not the window itself.
        assertThatCode(() -> window(observation, DATE, "OPEN", LocalTime.of(9, 0), LocalTime.of(17, 0)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("BA-022-T5 an evidence row with no window at all is stored and kept")
    void evidenceWithoutAValueIsStorable() {
        UUID place = activePlace();

        UUID observation = observation(place, "AMBIGUOUS");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM place_hours_observations WHERE id = ?", Long.class, observation))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM place_hours_windows WHERE observation_id = ?", Long.class, observation))
                .isZero();
    }

    @Test
    @DisplayName("BA-022-T6 a window whose closesAt is not after its opensAt is rejected")
    void aWindowMustCloseAfterItOpens() {
        UUID observation = observation(activePlace(), "OBSERVED");

        assertThatThrownBy(() -> window(observation, DATE, "OPEN", LocalTime.of(9, 0), LocalTime.of(9, 0)))
                .isInstanceOf(DataAccessException.class);
        // An overnight window: P0 has no representation for one (D-REC-18), and OpeningWindowIn's
        // canonical constructor refuses exactly this, so the table refuses what the record refuses
        // rather than letting a hydrator ship a window no filter can evaluate.
        assertThatThrownBy(() -> window(observation, DATE, "OPEN", LocalTime.of(22, 0), LocalTime.of(2, 0)))
                .isInstanceOf(DataAccessException.class);

        assertThatCode(() -> window(observation, DATE, "OPEN", LocalTime.of(9, 0), LocalTime.of(17, 0)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("BA-022-T7 a place that carries opening-hours evidence cannot be deprecated")
    void evidenceBlocksDeprecationUntilItIsMoved() {
        UUID canonical = activePlace();
        UUID duplicate = activePlace();
        UUID observation = observation(duplicate, "OBSERVED");

        assertThatThrownBy(() -> deprecate(duplicate, canonical)).isInstanceOf(DataAccessException.class);

        // The negative control, and the merge rule: move the evidence first, then deprecate.
        jdbc.update("DELETE FROM place_hours_observations WHERE id = ?", observation);
        assertThatCode(() -> deprecate(duplicate, canonical)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a place has at most one current evidence row, and superseding one frees the slot")
    void onlyOneCurrentObservationPerPlace() {
        UUID place = activePlace();
        UUID first = observation(place, "OBSERVED");

        assertThatThrownBy(() -> observation(place, "OBSERVED")).isInstanceOf(DataAccessException.class);

        jdbc.update("UPDATE place_hours_observations SET superseded_at = ? WHERE id = ?", timestamp(), first);
        assertThatCode(() -> observation(place, "OBSERVED")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("one evidence row holds at most one window per date")
    void oneWindowPerDate() {
        UUID observation = observation(activePlace(), "OBSERVED");
        window(observation, DATE, "OPEN", LocalTime.of(9, 0), LocalTime.of(17, 0));

        assertThatThrownBy(() -> window(observation, DATE, "CLOSED", null, null))
                .isInstanceOf(DataAccessException.class);

        assertThatCode(() -> window(observation, DATE.plusDays(1), "CLOSED", null, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a window can only hang off an OBSERVED evidence row, in either direction")
    void windowsOnlyExistUnderAnObservedOutcome() {
        UUID undecided = observation(activePlace(), "NO_INFORMATION");
        assertThatThrownBy(() -> window(undecided, DATE, "OPEN", LocalTime.of(9, 0), LocalTime.of(17, 0)))
                .isInstanceOf(DataAccessException.class);

        UUID observed = observation(activePlace(), "OBSERVED");
        assertThatCode(() -> window(observed, DATE, "OPEN", LocalTime.of(9, 0), LocalTime.of(17, 0)))
                .doesNotThrowAnyException();

        // The back door the insert guard alone leaves open: store windows under OBSERVED, then
        // downgrade the outcome underneath them. Same two-step shape V010 closes for a revoked
        // media licence - remove the values first, then weaken the record.
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE place_hours_observations SET outcome = 'AMBIGUOUS' WHERE id = ?", observed))
                .isInstanceOf(DataAccessException.class);
        jdbc.update("DELETE FROM place_hours_windows WHERE observation_id = ?", observed);
        assertThatCode(() -> jdbc.update(
                "UPDATE place_hours_observations SET outcome = 'AMBIGUOUS' WHERE id = ?", observed))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("evidence cannot be deleted while its windows still stand")
    void evidenceCannotBeDeletedUnderItsWindows() {
        UUID observation = observation(activePlace(), "OBSERVED");
        window(observation, DATE, "OPEN", LocalTime.of(9, 0), LocalTime.of(17, 0));

        // This is the half of "a value cannot exist without its evidence" that the trigger does not
        // cover: the trigger watches windows arriving, the foreign key watches evidence leaving.
        // Without this case the foreign key is unprovable - removing it turns no test red, and then
        // the fact every comment about this pair rests on is guarded by nothing anyone can check.
        //
        // V025's comment on observation_id was written before this case existed, and exactly one of
        // its three claims stopped being true: "neither guard is proven on its own". The other two
        // still hold - removing either guard alone does still leave BA-022-T4 green, and a passing
        // T4 does still say nothing about the foreign key, because what proves the key is this test
        // and not that one. The migration has run, so the correction lives next to the case that
        // caused it rather than in the file it corrects.
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM place_hours_observations WHERE id = ?", observation))
                .isInstanceOf(DataAccessException.class);

        jdbc.update("DELETE FROM place_hours_windows WHERE observation_id = ?", observation);
        assertThatCode(() -> jdbc.update(
                "DELETE FROM place_hours_observations WHERE id = ?", observation))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("evidence attaches to an active canonical place, never to a deprecated one")
    void evidenceAttachesToActivePlacesOnly() {
        UUID canonical = activePlace();
        UUID deprecated = activePlace();
        deprecate(deprecated, canonical);

        assertThatThrownBy(() -> observation(deprecated, "OBSERVED")).isInstanceOf(DataAccessException.class);
        assertThatCode(() -> observation(canonical, "OBSERVED")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a CLOSED window carries no times and an OPEN one cannot be half a window")
    void windowShapeMatchesItsState() {
        UUID observation = observation(activePlace(), "OBSERVED");

        assertThatThrownBy(() -> window(observation, DATE, "CLOSED", LocalTime.of(9, 0), LocalTime.of(17, 0)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> window(observation, DATE, "OPEN", LocalTime.of(9, 0), null))
                .isInstanceOf(DataAccessException.class);
        // UNKNOWN is not a storable state: an absent row is the unverified one, which is what
        // ProposalRevalidatorTest.anAbsentOpeningWindowIsUnverifiedRatherThanOpen fixes in the
        // evaluator. A stored UNKNOWN would let a full map read as authoritative.
        assertThatThrownBy(() -> window(observation, DATE, "UNKNOWN", null, null))
                .isInstanceOf(DataAccessException.class);

        assertThatCode(() -> window(observation, DATE, "CLOSED", null, null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("evidence names a page to re-read and a moment the reading goes stale")
    void evidenceIsReCheckable() {
        UUID place = activePlace();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO place_hours_observations
                    (id, place_id, source_code, source_registry_version, outcome, observed_at,
                     evidence_url, stale_at, created_at)
                VALUES (?, ?, ?, ?, 'OBSERVED', ?, 'royal.cha.go.kr/example/hours', ?, ?)
                """, UUID.randomUUID(), place, SOURCE, SOURCE_VERSION, timestamp(), staleTimestamp(), timestamp()))
                .isInstanceOf(DataAccessException.class);
        // A-023: a source with no staleness threshold is not collected, so a reading that never
        // goes stale cannot be recorded either.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO place_hours_observations
                    (id, place_id, source_code, source_registry_version, outcome, observed_at,
                     evidence_url, stale_at, created_at)
                VALUES (?, ?, ?, ?, 'OBSERVED', ?, ?, ?, ?)
                """, UUID.randomUUID(), place, SOURCE, SOURCE_VERSION, timestamp(), EVIDENCE_URL,
                timestamp(), timestamp()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO place_hours_observations
                    (id, place_id, source_code, source_registry_version, outcome, observed_at,
                     evidence_url, stale_at, created_at)
                VALUES (?, ?, ?, 999, 'OBSERVED', ?, ?, ?, ?)
                """, UUID.randomUUID(), place, SOURCE, timestamp(), EVIDENCE_URL, staleTimestamp(), timestamp()))
                .isInstanceOf(DataAccessException.class);

        assertThatCode(() -> observation(place, "OBSERVED")).doesNotThrowAnyException();
    }

    private UUID activePlace() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO places
                    (id, canonical_name, category_code, latitude, longitude, region_code, status,
                     created_at, updated_at)
                VALUES (?, '경복궁 테스트', 'A0201', 37.579617, 126.977041, '11', 'ACTIVE', ?, ?)
                """, id, timestamp(), timestamp());
        return id;
    }

    private void deprecate(UUID id, UUID canonicalPlaceId) {
        jdbc.update("UPDATE places SET status = 'DEPRECATED', canonical_place_id = ? WHERE id = ?",
                canonicalPlaceId, id);
    }

    private UUID observation(UUID placeId, String outcome) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO place_hours_observations
                    (id, place_id, source_code, source_registry_version, outcome, observed_at,
                     evidence_url, stale_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, placeId, SOURCE, SOURCE_VERSION, outcome, timestamp(), EVIDENCE_URL,
                staleTimestamp(), timestamp());
        return id;
    }

    private void window(UUID observationId, LocalDate date, String state, LocalTime opensAt, LocalTime closesAt) {
        jdbc.update("""
                INSERT INTO place_hours_windows (id, observation_id, effective_on, state, opens_at, closes_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), observationId, java.sql.Date.valueOf(date), state,
                opensAt == null ? null : java.sql.Time.valueOf(opensAt),
                closesAt == null ? null : java.sql.Time.valueOf(closesAt));
    }

    private static java.sql.Timestamp timestamp() {
        return java.sql.Timestamp.from(NOW);
    }

    private static java.sql.Timestamp staleTimestamp() {
        return java.sql.Timestamp.from(STALE_AT);
    }
}
