package io.nullnull.crowd.infrastructure.persistence;

import io.nullnull.crowd.application.CrowdForecastQuery;
import io.nullnull.crowd.domain.ComparisonScope;
import io.nullnull.crowd.domain.QualityFlag;
import io.nullnull.crowd.domain.SourceState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads one immutable set at a time. The query joins the revision captured by the snapshot instead
 * of the mutable registry row, so old previews retain their original license and metric meaning.
 */
@Repository
public class JdbcCrowdForecastQuery implements CrowdForecastQuery {

    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    public JdbcCrowdForecastQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<SnapshotSet> latestFresh(UUID placeId, Instant from, Instant to, Instant now) {
        return latest(placeId, from, to, now, true);
    }

    @Override
    public Optional<SnapshotSet> latestStale(UUID placeId, Instant from, Instant to, Instant now) {
        return latest(placeId, from, to, now, false);
    }

    private Optional<SnapshotSet> latest(UUID placeId, Instant from, Instant to, Instant now, boolean fresh) {
        // The operator is selected internally, never from a request value. Keeping the placeholder
        // solely for the timestamp avoids the malformed `?AND` SQL which text-block concatenation
        // can produce when this query is reformatted.
        String freshnessOperator = fresh ? ">" : "<=";
        String latestSetSql = """
                SELECT ss.id
                  FROM snapshot_sets ss
                 WHERE ss.source_state = 'FORECAST'
                   AND ss.stale_at %s ?
                   AND EXISTS (
                       SELECT 1
                         FROM crowd_snapshots point
                        WHERE point.snapshot_set_id = ss.id
                          AND point.place_id = ?
                          AND point.target_at >= ?
                          AND point.target_at <= ?
                   )
                 ORDER BY ss.fetched_at DESC, ss.id DESC
                 LIMIT 1
                """.formatted(freshnessOperator);
        List<UUID> ids = jdbc.query(latestSetSql,
                new Object[] {Timestamp.from(now), placeId, Timestamp.from(from), Timestamp.from(to)},
                (result, row) -> result.getObject("id", UUID.class));
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        UUID setId = ids.get(0);
        List<Snapshot> points = jdbc.query("""
                SELECT point.id, point.snapshot_set_id, set_row.collector_run_id, point.place_id,
                       point.source_code, point.source_registry_version, point.source_state,
                       point.observed_at, point.target_at, point.fetched_at, point.stale_at,
                       point.metric_code, point.value, point.unit, point.ordinal_level, point.confidence,
                       point.quality_flags::text AS quality_flags, point.forecast_issue_id,
                       point.comparison_group_id, point.normalization_version,
                       point.observed_at_skew_seconds, point.scope, point.scope_label, point.mapping_type,
                       point.fallback_used,
                       revision.canonical_contract->>'displayName' AS source_display_name,
                       revision.canonical_contract->'license'->>'name' AS license_name,
                       revision.canonical_contract->>'officialUrl' AS official_url,
                       revision.canonical_contract->'license'->>'url' AS license_url,
                       revision.canonical_contract->>'attributionTemplate' AS attribution,
                       revision.canonical_contract->>'metricDefinition' AS metric_definition,
                       EXISTS (
                           SELECT 1
                             FROM source_quality_incidents incident
                            WHERE incident.source_code = point.source_code
                              AND incident.disposition = 'QUARANTINE'
                              AND incident.affected_from <= point.fetched_at
                              AND (incident.affected_to IS NULL OR point.fetched_at < incident.affected_to)
                       ) AS incident_active
                  FROM crowd_snapshots point
                  JOIN snapshot_sets set_row ON set_row.id = point.snapshot_set_id
                  JOIN source_registry_revisions revision
                    ON revision.source_code = point.source_code
                   AND revision.version = point.source_registry_version
                 WHERE point.snapshot_set_id = ?
                   AND point.place_id = ?
                   AND point.target_at >= ?
                   AND point.target_at <= ?
                 ORDER BY point.target_at ASC, point.id ASC
                """, new Object[] {setId, placeId, Timestamp.from(from), Timestamp.from(to)}, this::snapshot);
        return points.isEmpty() ? Optional.empty() : Optional.of(new SnapshotSet(setId, points));
    }

    private Snapshot snapshot(ResultSet result, int row) throws SQLException {
        SourceDescriptor source = new SourceDescriptor(result.getString("source_code"),
                result.getString("source_display_name"), result.getLong("source_registry_version"),
                result.getString("license_name"), result.getString("official_url"), result.getString("license_url"),
                result.getString("attribution"), result.getString("metric_definition"));
        return new Snapshot(result.getObject("id", UUID.class), result.getObject("snapshot_set_id", UUID.class),
                result.getObject("collector_run_id", UUID.class), result.getObject("place_id", UUID.class), source,
                SourceState.valueOf(result.getString("source_state")), instant(result, "observed_at"),
                instant(result, "target_at"), instant(result, "fetched_at"), instant(result, "stale_at"),
                result.getString("metric_code"), result.getBigDecimal("value"), result.getString("unit"),
                result.getString("ordinal_level"), result.getBigDecimal("confidence"),
                qualityFlags(result.getString("quality_flags")), result.getString("forecast_issue_id"),
                result.getString("comparison_group_id"), result.getString("normalization_version"),
                (Integer) result.getObject("observed_at_skew_seconds"),
                ComparisonScope.valueOf(result.getString("scope")), result.getString("scope_label"),
                result.getString("mapping_type"), result.getBoolean("fallback_used"), result.getBoolean("incident_active"));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /** Unknown persisted flags are schema drift: do not turn an unreviewed meaning into a comparison. */
    private Set<QualityFlag> qualityFlags(String raw) {
        try {
            JsonNode values = json.readTree(raw);
            if (!values.isArray()) {
                return Set.of(QualityFlag.SCHEMA_DRIFT);
            }
            EnumSet<QualityFlag> flags = EnumSet.noneOf(QualityFlag.class);
            for (JsonNode value : values) {
                if (!value.isTextual()) {
                    return Set.of(QualityFlag.SCHEMA_DRIFT);
                }
                flags.add(QualityFlag.valueOf(value.asText()));
            }
            return Set.copyOf(flags);
        } catch (RuntimeException exception) {
            return Set.of(QualityFlag.SCHEMA_DRIFT);
        }
    }
}
