package io.nullnull.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.catalog.application.CatalogRelationDeriver;
import io.nullnull.catalog.application.CatalogRelationDeriver.DerivationReport;
import io.nullnull.testsupport.MutableClock;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import io.nullnull.testsupport.OwnedRows;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;

/**
 * BA-026: the internal rule that gives place_relations a producer.
 *
 * <p>Everything is asserted against the table rather than through {@code listRelatedPlaces}, because
 * what this card promises is about stored evidence - which pairs exist, which windows are open, what
 * a second run does to the first run's rows. The route's own behaviour is BA-024's seven clauses.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, CatalogRelationDeriveIT.Time.class})
@DisplayName("BA-026 internal rule relation derivation")
class CatalogRelationDeriveIT {

    private static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");
    private static final String RULE = "NULLNULL_CATALOG_RULE";

    @TestConfiguration
    static class Time {
        @Bean
        @Primary
        MutableClock deriveClock() {
            return MutableClock.at(NOW);
        }
    }

    @Autowired CatalogRelationDeriver deriver;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;


    /**
     * The places that were already there when this test started. Everything that appears after it
     * is this test's, and only that is removed - a blanket DELETE takes other classes' rows or, more
     * often, fails on one of the foreign keys that deliberately do not cascade (AGENTS.md rule 6).
     */
    /** Unique per run: the rule groups by region, and this test must own its group. */
    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);

    private List<UUID> placesBefore = List.of();

    @BeforeEach
    void notePlacesAlreadyPresent() {
        placesBefore = jdbc.queryForList("SELECT id FROM places", UUID.class);
    }

    @AfterEach
    void removeOnlyOwnFixtures() {
        List<UUID> mine = OwnedRows.appeared(jdbc, "places", placesBefore);
        // Only this test's rows: unscoped, this UPDATE rewrote the status and canonical pointer of
        // every place in a database the gate shares with every other class.
        for (UUID placeId : mine) {
            jdbc.update("UPDATE places SET status = 'ACTIVE', canonical_place_id = NULL WHERE id = ?",
                    placeId);
        }
        OwnedRows.remove(jdbc, "places", mine);
    }

    @Test
    @DisplayName("BA-026-T1 only places sharing a category and a region become related")
    void onlyMatchingPairsAreDerived() {
        UUID palaceA = place("A0201", "11");
        UUID palaceB = place("A0201", "11");
        UUID otherCategory = place("A0202", "11");
        UUID otherRegion = place("A0201", "26");

        deriver.derive();

        // Both directions for the matching pair, and nothing at all for the other two. Asserting only
        // the absence would pass for a rule that derives nothing.
        assertThat(targetsOf(palaceA)).containsExactly(palaceB);
        assertThat(targetsOf(palaceB)).containsExactly(palaceA);
        assertThat(targetsOf(otherCategory)).isEmpty();
        assertThat(targetsOf(otherRegion)).isEmpty();
    }

    @Test
    @DisplayName("BA-026-T2 the rule never writes EXACT")
    void theRuleNeverClaimsAnExactRelation() {
        place("A0201", "11");
        place("A0201", "11");

        deriver.derive();

        // V027 would refuse an EXACT row from this source anyway - EXACT needs PROVIDER_DIRECT - so
        // this asserts the script does not try, which is the half the table cannot show. Every row is
        // SIMILAR, from the rule, filed as the rule's own derivation.
        assertThat(jdbc.queryForList("""
                SELECT DISTINCT relation_type || '/' || derivation || '/' || source_code
                  FROM place_relations
                """, String.class)).containsExactly("SIMILAR/INTERNAL_RULE/" + RULE);
    }

    @Test
    @DisplayName("BA-026-T3 a second run converges on one row per pair and keeps the first one")
    void rerunningConvergesAndKeepsTheOriginalRow() {
        // Read the clock, never NOW: MutableClock is one bean for this whole class and only moves
        // forward, so any test that advanced it leaves the ones after it at a later instant. This
        // cost a wrong diagnosis once already, in TripImportIT.
        Instant started = clock.instant();
        UUID one = place("A0201", "11");
        UUID two = place("A0201", "11");
        deriver.derive();
        List<UUID> firstIds = jdbc.queryForList("SELECT id FROM place_relations ORDER BY id", UUID.class);
        Instant firstCreated = created(one, two);

        clock.advance(Duration.ofHours(1));
        DerivationReport second = deriver.derive();

        assertThat(rows()).isEqualTo(2L);
        assertThat(jdbc.queryForList("SELECT id FROM place_relations ORDER BY id", UUID.class))
                .isEqualTo(firstIds);
        // The window moved and the row did not: a re-derivation that deleted and re-inserted would
        // satisfy "one row per pair" while losing when the evidence was first recorded.
        assertThat(created(one, two)).isEqualTo(firstCreated);
        assertThat(expiry(one, two))
                .isEqualTo(started.plus(Duration.ofHours(1)).plus(Duration.ofDays(7)));
        // Scoped like rows(): recorded() counts every group in the catalog, and the gate shares
        // one database. What this test can claim is about the pair it created.
        assertThat(rows()).isEqualTo(2L);
    }

    @Test
    @DisplayName("BA-026-T4 a place that is not active appears at neither end")
    void aRetiredPlaceIsNeitherSourceNorTarget() {
        UUID living = place("A0201", "11");
        UUID alsoLiving = place("A0201", "11");
        UUID retired = deprecated("A0201", "11", living);

        deriver.derive();

        assertThat(targetsOf(living)).containsExactly(alsoLiving);
        assertThat(targetsOf(retired)).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM place_relations WHERE target_place_id = ?", Long.class, retired))
                .isZero();
    }

    @Test
    @DisplayName("BA-026-T5 a pair that stops matching is expired rather than deleted, and stays expired")
    void aPairThatStopsMatchingIsClosedAndNotReopened() {
        Instant started = clock.instant();
        UUID one = place("A0201", "11");
        UUID two = place("A0201", "11");
        deriver.derive();
        UUID row = jdbc.queryForObject("""
                SELECT id FROM place_relations WHERE source_place_id = ? AND target_place_id = ?
                """, UUID.class, one, two);

        // The catalog moves: this place is reclassified, so the pair no longer satisfies the rule.
        // Changing the category rather than retiring the place is deliberate - V027's trigger refuses
        // to deprecate a place that still has relations, so that route would fail before the clause
        // could be checked.
        clock.advance(Duration.ofHours(1));
        jdbc.update("UPDATE places SET category_code = 'A0203' WHERE id = ?", two);
        deriver.derive();
        Instant closedAt = expiry(one, two);

        // Closed, not gone. The row still records what the catalog said before the change.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM place_relations WHERE id = ?", Long.class, row)).isEqualTo(1L);
        assertThat(closedAt).isEqualTo(started.plus(Duration.ofHours(1)));

        // And a later run does not move an expiry that has already happened.
        clock.advance(Duration.ofHours(1));
        deriver.derive();
        assertThat(expiry(one, two)).isEqualTo(closedAt);
    }

    @Test
    @DisplayName("BA-026-T6 a place with more peers than the cap is reported, not trimmed")
    void anOverfullSourceIsNamedRatherThanTruncated() {
        for (int at = 0; at < CatalogRelationDeriver.MAX_PER_SOURCE + 2; at++) {
            place("A0301", "11");
        }

        DerivationReport report = deriver.derive();

        // Every one of them has more peers than the cap, so every one is skipped - and none gets an
        // arbitrary hundred. Nothing here can prefer one peer over another: the rule says they are
        // all equally similar, which is why picking a hundred would be a ranking nobody computed.
        assertThat(report.skipped()).containsAll(OwnedRows.appeared(jdbc, "places", placesBefore));
        assertThat(rows()).isZero();
        assertThat(rows()).isZero();
    }

    private List<UUID> targetsOf(UUID source) {
        return jdbc.queryForList("""
                SELECT target_place_id FROM place_relations WHERE source_place_id = ?
                 ORDER BY target_place_id
                """, UUID.class, source);
    }

    private Instant created(UUID source, UUID target) {
        return jdbc.queryForObject("""
                SELECT created_at FROM place_relations WHERE source_place_id = ? AND target_place_id = ?
                """, Timestamp.class, source, target).toInstant();
    }

    private Instant expiry(UUID source, UUID target) {
        return jdbc.queryForObject("""
                SELECT expires_at FROM place_relations WHERE source_place_id = ? AND target_place_id = ?
                """, Timestamp.class, source, target).toInstant();
    }

    /**
     * Relations whose source is a place THIS test made. The unscoped count answered for every class
     * that ran before it once the gate gave them one database to share.
     */
    private long rows() {
        List<UUID> mine = OwnedRows.appeared(jdbc, "places", placesBefore);
        if (mine.isEmpty()) {
            return 0L;
        }
        String placeholders = mine.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(", "));
        Long found = jdbc.queryForObject("SELECT count(*) FROM place_relations WHERE source_place_id IN ("
                + placeholders + ")", Long.class, mine.toArray());
        return found == null ? 0L : found;
    }

    /**
     * The rule pairs places by {@code (category_code, region_code)} and
     * {@link CatalogRelationDeriver} runs over the WHOLE catalog, so this fixture's region
     * carries a suffix unique to the run. Without it any place another class left behind in the
     * gate's shared database joins these groups, and the derivation under test is no longer the
     * one this test set up - it found 182 relations where the test expected none. The suffix
     * isolates the group; it does not narrow what {@code derive()} does.
     */
    private UUID place(String category, String region) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO places
                    (id, canonical_name, category_code, latitude, longitude, region_code, status,
                     created_at, updated_at)
                VALUES (?, '장소', ?, 37.579617, 126.977041, ?, 'ACTIVE', ?, ?)
                """, id, category, region + RUN, Timestamp.from(NOW), Timestamp.from(NOW));
        return id;
    }

    private UUID deprecated(String category, String region, UUID canonical) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO places
                    (id, canonical_place_id, canonical_name, category_code, latitude, longitude,
                     region_code, status, created_at, updated_at)
                VALUES (?, ?, '폐기된 장소', ?, 37.579617, 126.977041, ?, 'DEPRECATED', ?, ?)
                """, id, canonical, category, region + RUN, Timestamp.from(NOW), Timestamp.from(NOW));
        return id;
    }
}
