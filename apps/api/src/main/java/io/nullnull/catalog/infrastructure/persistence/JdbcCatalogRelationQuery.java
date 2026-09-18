package io.nullnull.catalog.infrastructure.persistence;

import io.nullnull.catalog.application.CatalogRelationQuery;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Selection and its provenance, with no place content joined in.
 *
 * <p>Nothing here re-checks that either end is an active canonical place. V027's insert trigger
 * refuses a relation that names a deprecated place and its deprecation guard refuses to retire a
 * place that still has one, so a row in this table already has two active ends. A status join would
 * be a second guard over the same fact, and then neither would be provable on its own - the shape
 * BA-022's foreign key and trigger ended up in.
 *
 * <p>The registry values come from the pinned revision rather than the live {@code source_registry}
 * row, the same lateral join {@code JdbcCatalogPlaceQuery} uses for a place's credit. A source whose
 * licence or credit changes must not silently restate the terms an older relation was recorded
 * under.
 */
@Repository
public class JdbcCatalogRelationQuery implements CatalogRelationQuery {

    /**
     * EXACT before SIMILAR because the stronger evidence is the better candidate, then target id as
     * the tiebreaker. The tiebreaker is what makes the order total: without it two relations written
     * in either order could come back in either order, and whatever orders them next would be handed a
     * different input each time for the same stored evidence. (That is the projection's converge, not an
     * apps/ai ranker: ADR-0006 · 예외, docs/decisions/ARCHITECTURE_DECISIONS.md.)
     *
     * <p>Measured, and only half of it is proven. Removing the EXACT ranking turns
     * exactEvidenceIsOfferedBeforeSimilar red; removing {@code target_place_id} turns nothing red,
     * because the planner reaches this table through the unique index on
     * {@code (source_place_id, target_place_id, source_code)} and that index already hands rows back
     * in target order. The tiebreaker is therefore holding a guarantee no test here can demonstrate -
     * it earns its place against a future plan that does not use that index, not against this one.
     * Do not read a passing order test as evidence that it is doing the work.
     */
    private static final String CANDIDATES = """
            SELECT relation.id, relation.target_place_id, relation.relation_type, relation.derivation,
                   relation.mapping_certainty, relation.relation_reason, relation.source_code,
                   relation.source_registry_version, relation.effective_at, relation.expires_at, relation.created_at,
                   revision.canonical_contract->>'displayName' AS source_display_name,
                   revision.canonical_contract->>'sourceState' AS source_state,
                   revision.canonical_contract->'license'->>'name' AS license_name,
                   revision.canonical_contract->'license'->>'url' AS license_url,
                   revision.canonical_contract->>'officialUrl' AS official_url,
                   revision.canonical_contract->>'attributionTemplate' AS attribution,
                   revision.canonical_contract->>'metricDefinition' AS metric_definition,
                   revision.canonical_contract->>'providerSchemaVersion' AS normalization_version,
                   revision.canonical_contract->>'defaultScope' AS scope
              FROM place_relations relation
              JOIN source_registry_revisions revision
                ON revision.source_code = relation.source_code
               AND revision.version = relation.source_registry_version
             WHERE relation.source_place_id = ?
               AND relation.effective_at <= ?
               AND (relation.expires_at IS NULL OR relation.expires_at > ?)
             ORDER BY CASE relation.relation_type WHEN 'EXACT' THEN 0 ELSE 1 END,
                      relation.target_place_id
            """;

    private final JdbcTemplate jdbc;

    public JdbcCatalogRelationQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<CatalogRelationCandidate> candidatesFor(UUID sourcePlaceId, Instant at) {
        Timestamp instant = Timestamp.from(at);
        return jdbc.query(CANDIDATES, JdbcCatalogRelationQuery::candidate, sourcePlaceId, instant, instant);
    }

    private static CatalogRelationCandidate candidate(ResultSet row, int rowNumber) throws SQLException {
        Timestamp expiresAt = row.getTimestamp("expires_at");
        return new CatalogRelationCandidate(
                row.getObject("id", UUID.class),
                row.getObject("target_place_id", UUID.class),
                row.getString("relation_type"),
                row.getString("relation_reason"),
                row.getString("derivation"),
                row.getString("mapping_certainty"),
                row.getTimestamp("effective_at").toInstant(),
                expiresAt == null ? null : expiresAt.toInstant(),
                row.getTimestamp("created_at").toInstant(),
                new CatalogRelationSource(
                        row.getString("source_code"),
                        row.getLong("source_registry_version"),
                        row.getString("source_display_name"),
                        row.getString("source_state"),
                        row.getString("license_name"),
                        row.getString("license_url"),
                        row.getString("official_url"),
                        row.getString("attribution"),
                        row.getString("metric_definition"),
                        row.getString("normalization_version"),
                        row.getString("scope")));
    }
}
