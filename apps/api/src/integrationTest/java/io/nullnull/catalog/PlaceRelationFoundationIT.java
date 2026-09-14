package io.nullnull.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.testsupport.TestcontainersConfiguration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import io.nullnull.testsupport.OwnedRows;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;

/**
 * BA-024 storage only, the half the catalog publication gate does not reach.
 *
 * <p>No controller is exercised here and none can be: {@code RelatedPlace.place} is a required
 * {@code PlaceSummary}, every summary goes through {@code embeddedSummaries}, and that method's
 * first line is {@code requirePublicProjection()}. So a response carrying candidates waits on
 * BA-021-T3's staging evidence. What does not wait is what a relation is allowed to be, which is
 * what SOURCE_CATALOG §4 already fixes: an internal rule produces SIMILAR with a reason, EXACT needs
 * an official direct relation plus a confirmed canonical mapping, and the aggregation window is
 * preserved rather than assumed.
 *
 * <p>Only T1 and T5 are claimed. T4's clause is "expired evidence is not a candidate" - a read rule,
 * which a schema cannot prove; the window check below is its storage half, and the clause belongs to
 * the slice that adds the query. T2, T3, T6 and T7 need the response path for the same reason.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-024 verified place relations")
class PlaceRelationFoundationIT {

    private static final Instant NOW = Instant.parse("2026-09-13T00:00:00Z");
    private static final Instant LATER = Instant.parse("2026-12-13T00:00:00Z");
    private static final String RULE = "NULLNULL_CATALOG_RULE";
    private static final String PROVIDER = "KTO_RELATED_PLACES";

    @Autowired
    JdbcTemplate jdbc;


    /**
     * The places that were already there when this test started. Everything that appears after it
     * is this test's, and only that is removed - a blanket DELETE takes other classes' rows or, more
     * often, fails on one of the foreign keys that deliberately do not cascade (AGENTS.md rule 6).
     */
    private List<UUID> placesBefore = List.of();

    @BeforeEach
    void notePlacesAlreadyPresent() {
        placesBefore = jdbc.queryForList("SELECT id FROM places", UUID.class);
    }

    @AfterEach
    void removeOnlyOwnFixtures() {
        List<UUID> mine = OwnedRows.appeared(jdbc, "places", placesBefore);
        OwnedRows.remove(jdbc, "places", mine);
    }

    @Test
    @DisplayName("BA-024-T1 duplicate candidates for one canonical pair converge on a single row")
    void duplicateCandidatesConvergeOnOneRow() {
        UUID source = activePlace();
        UUID target = activePlace();
        UUID other = activePlace();
        UUID deprecated = activePlace();
        deprecate(deprecated, target);

        similar(source, target);

        // The same pair offered twice - the second candidate is the same relation, not a new one.
        assertThatThrownBy(() -> similar(source, target)).isInstanceOf(DataAccessException.class);
        // The same pair reached through a retired alias of the target. Refusing it is what forces the
        // caller to resolve to the canonical ID first, which is where the convergence happens: both
        // candidates end up naming the row that already exists.
        assertThatThrownBy(() -> similar(source, deprecated)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> similar(deprecated, target)).isInstanceOf(DataAccessException.class);
        // A place is not related to itself, however a candidate list arrives at the idea.
        assertThatThrownBy(() -> similar(source, source)).isInstanceOf(DataAccessException.class);

        // The negative control: a genuinely different target is still accepted, so the rejections
        // above are the duplicate pair rather than the table refusing a second row at all.
        assertThatCode(() -> similar(source, other)).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM place_relations WHERE source_place_id = ?", Long.class, source))
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("BA-024-T5 a mapping that is not a confirmed official relation cannot be EXACT")
    void onlyAConfirmedProviderRelationCanBeExact() {
        UUID source = activePlace();
        UUID target = activePlace();

        assertThatThrownBy(() -> relation(source, target, "EXACT", "PROVIDER_DIRECT", "UNCERTAIN", PROVIDER))
                .isInstanceOf(DataAccessException.class);
        // An internally derived candidate is SIMILAR by definition (SOURCE_CATALOG §4), no matter how
        // certain the rule that produced it is.
        assertThatThrownBy(() -> relation(source, target, "EXACT", "INTERNAL_RULE", "CONFIRMED", RULE))
                .isInstanceOf(DataAccessException.class);

        // Both negative controls: the same uncertainty is fine as SIMILAR, and a confirmed official
        // relation is fine as EXACT. The rejection is the combination, not either field alone.
        assertThatCode(() -> relation(source, target, "SIMILAR", "INTERNAL_RULE", "UNCERTAIN", RULE))
                .doesNotThrowAnyException();
        assertThatCode(() -> relation(activePlace(), target, "EXACT", "PROVIDER_DIRECT", "CONFIRMED", PROVIDER))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a relation states why it is offered, and an internal rule cannot borrow a provider's name")
    void everyRelationCarriesItsReasonAndItsDerivation() {
        UUID source = activePlace();
        UUID target = activePlace();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO place_relations
                    (id, source_place_id, target_place_id, relation_type, derivation, mapping_certainty,
                     relation_reason, source_code, source_registry_version, effective_at, expires_at, created_at)
                VALUES (?, ?, ?, 'SIMILAR', 'INTERNAL_RULE', 'UNCERTAIN', '   ', ?, 1, ?, ?, ?)
                """, UUID.randomUUID(), source, target, RULE, timestamp(), later(), timestamp()))
                .isInstanceOf(DataAccessException.class);
        // A row derived by our own rule that credits a provider would put that provider's name behind
        // a claim it never made.
        assertThatThrownBy(() -> relation(source, target, "SIMILAR", "INTERNAL_RULE", "UNCERTAIN", PROVIDER))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> relation(source, target, "SIMILAR", "PROVIDER_DIRECT", "UNCERTAIN", RULE))
                .isInstanceOf(DataAccessException.class);

        assertThatCode(() -> similar(source, target)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("relation evidence keeps an aggregation window that is not already empty")
    void evidenceKeepsANonEmptyWindow() {
        UUID source = activePlace();
        UUID target = activePlace();

        assertThatThrownBy(() -> window(source, target, NOW, NOW)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> window(source, target, LATER, NOW)).isInstanceOf(DataAccessException.class);

        // An open-ended relation is legal - a rule-derived similarity does not expire on its own -
        // and that is different from a window that was empty the moment it was written.
        assertThatCode(() -> window(source, target, NOW, null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("relations attach to active canonical places, and block deprecation until they are moved")
    void relationsFollowTheCanonicalMergeRule() {
        UUID canonical = activePlace();
        UUID duplicate = activePlace();
        UUID target = activePlace();
        similar(duplicate, target);

        assertThatThrownBy(() -> deprecate(duplicate, canonical)).isInstanceOf(DataAccessException.class);

        jdbc.update("DELETE FROM place_relations WHERE source_place_id = ?", duplicate);
        assertThatCode(() -> deprecate(duplicate, canonical)).doesNotThrowAnyException();
    }

    private UUID activePlace() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO places
                    (id, canonical_name, category_code, latitude, longitude, region_code, status,
                     created_at, updated_at)
                VALUES (?, '관련 장소 테스트', 'A0201', 37.579617, 126.977041, '11', 'ACTIVE', ?, ?)
                """, id, timestamp(), timestamp());
        return id;
    }

    private void deprecate(UUID id, UUID canonicalPlaceId) {
        jdbc.update("UPDATE places SET status = 'DEPRECATED', canonical_place_id = ? WHERE id = ?",
                canonicalPlaceId, id);
    }

    private void similar(UUID source, UUID target) {
        relation(source, target, "SIMILAR", "INTERNAL_RULE", "UNCERTAIN", RULE);
    }

    private void relation(UUID source, UUID target, String type, String derivation, String certainty,
            String sourceCode) {
        jdbc.update("""
                INSERT INTO place_relations
                    (id, source_place_id, target_place_id, relation_type, derivation, mapping_certainty,
                     relation_reason, source_code, source_registry_version, effective_at, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, '같은 분류·지역', ?, 1, ?, ?, ?)
                """, UUID.randomUUID(), source, target, type, derivation, certainty, sourceCode,
                timestamp(), later(), timestamp());
    }

    private void window(UUID source, UUID target, Instant effectiveAt, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO place_relations
                    (id, source_place_id, target_place_id, relation_type, derivation, mapping_certainty,
                     relation_reason, source_code, source_registry_version, effective_at, expires_at, created_at)
                VALUES (?, ?, ?, 'SIMILAR', 'INTERNAL_RULE', 'UNCERTAIN', '같은 분류·지역', ?, 1, ?, ?, ?)
                """, UUID.randomUUID(), source, target, RULE, java.sql.Timestamp.from(effectiveAt),
                expiresAt == null ? null : java.sql.Timestamp.from(expiresAt), timestamp());
    }

    private static java.sql.Timestamp timestamp() {
        return java.sql.Timestamp.from(NOW);
    }

    private static java.sql.Timestamp later() {
        return java.sql.Timestamp.from(LATER);
    }
}
