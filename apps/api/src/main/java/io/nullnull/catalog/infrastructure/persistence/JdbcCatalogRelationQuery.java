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
 * Selection only: the window filter and a total order, with no place content joined in.
 *
 * <p>Nothing here re-checks that either end is an active canonical place. V027's insert trigger
 * refuses a relation that names a deprecated place and its deprecation guard refuses to retire a
 * place that still has one, so a row in this table already has two active ends. A status join would
 * be a second guard over the same fact, and then neither would be provable on its own - the shape
 * BA-022's foreign key and trigger ended up in.
 */
@Repository
public class JdbcCatalogRelationQuery implements CatalogRelationQuery {

    /**
     * EXACT before SIMILAR because the stronger evidence is the better candidate, then target id as
     * the tiebreaker. The tiebreaker is what makes the order total: without it two relations written
     * in either order could come back in either order, and the ranker downstream would be scoring a
     * different input each time for the same stored evidence.
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
            SELECT target_place_id, relation_type, relation_reason, source_code,
                   source_registry_version, effective_at, expires_at
              FROM place_relations
             WHERE source_place_id = ?
               AND effective_at <= ?
               AND (expires_at IS NULL OR expires_at > ?)
             ORDER BY CASE relation_type WHEN 'EXACT' THEN 0 ELSE 1 END, target_place_id
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
                row.getObject("target_place_id", UUID.class),
                row.getString("relation_type"),
                row.getString("relation_reason"),
                row.getString("source_code"),
                row.getLong("source_registry_version"),
                row.getTimestamp("effective_at").toInstant(),
                expiresAt == null ? null : expiresAt.toInstant());
    }
}
