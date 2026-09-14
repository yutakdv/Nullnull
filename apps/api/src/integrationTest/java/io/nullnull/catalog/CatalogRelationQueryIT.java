package io.nullnull.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.catalog.application.CatalogRelationQuery;
import io.nullnull.catalog.application.CatalogRelationQuery.CatalogRelationCandidate;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import io.nullnull.testsupport.OwnedRows;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;

/**
 * BA-024 candidate selection, which happens before hydration and therefore before the catalog
 * publication gate. Selecting candidates needs canonical ids and relation metadata, not a
 * PlaceSummary, so the gate that stops {@code listRelatedPlaces} from carrying items does not stop
 * this: what it returns is exactly what the response stage will hand to the ranker.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-024 relation candidate selection")
class CatalogRelationQueryIT {

    private static final Instant NOW = Instant.parse("2026-09-13T00:00:00Z");
    private static final Instant BEFORE = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant AFTER = Instant.parse("2026-12-01T00:00:00Z");
    private static final String RULE = "NULLNULL_CATALOG_RULE";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CatalogRelationQuery relations;


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
    @DisplayName("BA-024-T4 relation evidence whose window has closed is not offered as a candidate")
    void expiredEvidenceIsNotACandidate() {
        UUID source = activePlace();
        UUID expired = activePlace();
        UUID live = activePlace();
        UUID openEnded = activePlace();

        relation(source, expired, BEFORE, NOW);
        relation(source, live, BEFORE, AFTER);
        relation(source, openEnded, BEFORE, null);

        List<UUID> offered = relations.candidatesFor(source, NOW).stream()
                .map(CatalogRelationCandidate::targetPlaceId).toList();

        // expires_at is the moment the evidence stops standing, so an instant equal to it is already
        // past it - the same boundary the table uses when it refuses expires_at = effective_at.
        assertThat(offered).doesNotContain(expired);
        // The negative controls: a window still open and a window with no end are both offered, so
        // the exclusion above is the closed window rather than the query returning nothing.
        assertThat(offered).contains(live, openEnded);
    }

    @Test
    @DisplayName("evidence that has not started yet is not offered either")
    void evidenceBeforeItsWindowIsNotACandidate() {
        UUID source = activePlace();
        UUID future = activePlace();
        UUID live = activePlace();

        relation(source, future, AFTER, null);
        relation(source, live, BEFORE, null);

        assertThat(relations.candidatesFor(source, NOW).stream()
                .map(CatalogRelationCandidate::targetPlaceId).toList())
                .containsExactly(live);
    }

    @Test
    @DisplayName("candidates come back in one order however they were written")
    void selectionOrderDoesNotDependOnInsertionOrder() {
        UUID source = activePlace();
        List<UUID> targets = new java.util.ArrayList<>(
                List.of(activePlace(), activePlace(), activePlace(), activePlace()));
        targets.forEach(target -> relation(source, target, BEFORE, null));
        List<UUID> first = order(source);

        // Rewrite the same set in the opposite order: same evidence, different arrival.
        jdbc.update("DELETE FROM place_relations WHERE source_place_id = ?", source);
        java.util.Collections.reverse(targets);
        targets.forEach(target -> relation(source, target, BEFORE, null));

        assertThat(order(source)).isEqualTo(first);
        assertThat(first).hasSize(4);
    }

    @Test
    @DisplayName("stronger evidence is offered first, whichever was written first")
    void exactEvidenceIsOfferedBeforeSimilar() {
        UUID source = activePlace();
        UUID similar = activePlace();
        UUID exact = activePlace();

        relation(source, similar, BEFORE, null);
        exactRelation(source, exact);

        assertThat(order(source)).containsExactly(exact, similar);
    }

    @Test
    @DisplayName("a candidate carries the evidence the ranker and the response both need")
    void candidatesCarryTheirEvidence() {
        UUID source = activePlace();
        UUID target = activePlace();
        relation(source, target, BEFORE, AFTER);

        CatalogRelationCandidate candidate = relations.candidatesFor(source, NOW).getFirst();

        assertThat(candidate.targetPlaceId()).isEqualTo(target);
        assertThat(candidate.relationType()).isEqualTo("SIMILAR");
        assertThat(candidate.relationReason()).isEqualTo("같은 분류·지역");
        assertThat(candidate.source().code()).isEqualTo(RULE);
        assertThat(candidate.source().registryVersion()).isEqualTo(1L);
        assertThat(candidate.effectiveAt()).isEqualTo(BEFORE);
        assertThat(candidate.expiresAt()).isEqualTo(AFTER);
        // The registry values come from the pinned revision, not the live row: V007 seeds this
        // source as our own taxonomy rule, and a relation recorded under it keeps being described
        // by the terms of the revision it named.
        assertThat(candidate.source().displayName()).isEqualTo("널널 카탈로그 규칙");
        assertThat(candidate.source().sourceState()).isEqualTo("QUALITATIVE");
        assertThat(candidate.source().normalizationVersion()).isEqualTo("rule-v1");
        assertThat(candidate.recordedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("a place with no relation evidence at all yields no candidates")
    void aPlaceWithoutEvidenceHasNoCandidates() {
        assertThat(relations.candidatesFor(activePlace(), NOW)).isEmpty();
    }

    private List<UUID> order(UUID source) {
        return relations.candidatesFor(source, NOW).stream()
                .map(CatalogRelationCandidate::targetPlaceId).toList();
    }

    private UUID activePlace() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO places
                    (id, canonical_name, category_code, latitude, longitude, region_code, status,
                     created_at, updated_at)
                VALUES (?, '관련 장소 후보', 'A0201', 37.579617, 126.977041, '11', 'ACTIVE', ?, ?)
                """, id, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        return id;
    }

    /** The only shape V027 lets EXACT take: an official direct relation with a confirmed mapping. */
    private void exactRelation(UUID source, UUID target) {
        jdbc.update("""
                INSERT INTO place_relations
                    (id, source_place_id, target_place_id, relation_type, derivation, mapping_certainty,
                     relation_reason, source_code, source_registry_version, effective_at, expires_at, created_at)
                VALUES (?, ?, ?, 'EXACT', 'PROVIDER_DIRECT', 'CONFIRMED', '공식 연관 관광지',
                        'KTO_RELATED_PLACES', 1, ?, NULL, ?)
                """, UUID.randomUUID(), source, target, java.sql.Timestamp.from(BEFORE),
                java.sql.Timestamp.from(NOW));
    }

    private void relation(UUID source, UUID target, Instant effectiveAt, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO place_relations
                    (id, source_place_id, target_place_id, relation_type, derivation, mapping_certainty,
                     relation_reason, source_code, source_registry_version, effective_at, expires_at, created_at)
                VALUES (?, ?, ?, 'SIMILAR', 'INTERNAL_RULE', 'UNCERTAIN', '같은 분류·지역', ?, 1, ?, ?, ?)
                """, UUID.randomUUID(), source, target, RULE, java.sql.Timestamp.from(effectiveAt),
                expiresAt == null ? null : java.sql.Timestamp.from(expiresAt),
                java.sql.Timestamp.from(NOW));
    }
}
