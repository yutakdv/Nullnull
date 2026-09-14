package io.nullnull.catalog.infrastructure.persistence;

import io.nullnull.catalog.application.CatalogRelationStore;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** V027, written by the internal rule. */
@Repository
public class JdbcCatalogRelationStore implements CatalogRelationStore {

    /**
     * The rule itself, in the column that holds it: {@code source_registry.metric_definition} for
     * NULLNULL_CATALOG_RULE reads "동일 taxonomy·region 기반 SIMILAR 규칙", and this is that sentence
     * as SQL. The codes are opaque provider values and that does not matter - the claim is that two
     * places carry the same classification and the same region, not what those strings mean.
     */
    private static final String MATCHING_PAIRS = """
            SELECT source.id AS source_id, target.id AS target_id
              FROM places source
              JOIN places target
                ON target.category_code = source.category_code
               AND target.region_code = source.region_code
               AND target.id <> source.id
             WHERE source.status = 'ACTIVE'
               AND target.status = 'ACTIVE'
             ORDER BY source.id, target.id
            """;

    private final JdbcTemplate jdbc;

    public JdbcCatalogRelationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<RulePair> ruleCandidates() {
        return jdbc.query(MATCHING_PAIRS, (row, number) -> new RulePair(
                row.getObject("source_id", UUID.class), row.getObject("target_id", UUID.class)));
    }

    @Override
    public long currentRevision(String sourceCode) {
        Long version = jdbc.queryForObject(
                "SELECT current_revision FROM source_registry WHERE code = ?", Long.class, sourceCode);
        if (version == null || version < 1) {
            throw new IllegalStateException("the relation rule source has no current revision");
        }
        return version;
    }

    @Override
    public int upsert(List<DerivedRelation> rows, String reason) {
        if (rows.isEmpty()) {
            return 0;
        }
        int[][] applied = jdbc.batchUpdate("""
                INSERT INTO place_relations
                    (id, source_place_id, target_place_id, relation_type, derivation, mapping_certainty,
                     relation_reason, source_code, source_registry_version, effective_at, expires_at,
                     created_at)
                VALUES (?, ?, ?, 'SIMILAR', 'INTERNAL_RULE', 'CONFIRMED', ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source_place_id, target_place_id, source_code)
                DO UPDATE SET effective_at = EXCLUDED.effective_at,
                              expires_at = EXCLUDED.expires_at,
                              source_registry_version = EXCLUDED.source_registry_version
                """, rows, rows.size(), (statement, row) -> {
            statement.setObject(1, row.id());
            statement.setObject(2, row.sourcePlaceId());
            statement.setObject(3, row.targetPlaceId());
            statement.setString(4, reason);
            statement.setString(5, row.sourceCode());
            statement.setLong(6, row.registryVersion());
            statement.setTimestamp(7, Timestamp.from(row.effectiveAt()));
            statement.setTimestamp(8, Timestamp.from(row.expiresAt()));
            statement.setTimestamp(9, Timestamp.from(row.createdAt()));
        });
        int total = 0;
        for (int[] batch : applied) {
            for (int count : batch) {
                total += Math.max(count, 0);
            }
        }
        return total;
    }

    @Override
    public int expireUnmatched(Instant now, String sourceCode) {
        // Only rows whose window is still open: one already closed stays closed at the instant it was
        // closed, so a later run cannot quietly move an expiry that has already happened.
        return jdbc.update("""
                UPDATE place_relations relation
                   SET expires_at = ?
                 WHERE relation.source_code = ?
                   AND (relation.expires_at IS NULL OR relation.expires_at > ?)
                   AND NOT EXISTS (
                         SELECT 1
                           FROM places source
                           JOIN places target
                             ON target.category_code = source.category_code
                            AND target.region_code = source.region_code
                          WHERE source.id = relation.source_place_id
                            AND target.id = relation.target_place_id
                            AND source.status = 'ACTIVE'
                            AND target.status = 'ACTIVE')
                """, Timestamp.from(now), sourceCode, Timestamp.from(now));
    }
}
