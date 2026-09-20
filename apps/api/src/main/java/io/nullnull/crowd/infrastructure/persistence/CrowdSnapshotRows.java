package io.nullnull.crowd.infrastructure.persistence;

import io.nullnull.crowd.application.CrowdForecastQuery.Snapshot;
import io.nullnull.crowd.application.CrowdForecastQuery.SourceDescriptor;
import io.nullnull.crowd.domain.ComparisonScope;
import io.nullnull.crowd.domain.CrowdStage;
import io.nullnull.crowd.domain.QualityFlag;
import io.nullnull.crowd.domain.SourceState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * How a crowd_snapshots row becomes a {@code Snapshot}, in one place.
 *
 * <p>Extracted when the Seoul live-area read needed the same row. **The part that had to stay single
 * is the CrowdStage guard below**: "a stored stage from a source with no reviewed mapping is not
 * served as one" is a rule, and a second copy of a rule is a copy that goes stale on its own
 * schedule. The SQL travels with it because the guard reads {@code source_code} and
 * {@code ordinal_level}, which only this SELECT guarantees are there.
 */
final class CrowdSnapshotRows {

    /**
     * Extracted from {@link #latest} so a caller that already knows the set id can ask for its points
     * without re-running the "which set is newest" question. Same query, same mapping - the only
     * change is who chooses {@code setId}.
     */
    /**
     * One point with everything a provenance line needs: its set, its source revision and whether a
     * quarantine covers it now. Shared by every read here so a point reads the same whichever way it
     * was asked for.
     */
    static final String POINT = """
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
                """;

    private final JsonMapper json = JsonMapper.builder().build();

    Snapshot map(ResultSet result, int row) throws SQLException {
        String ordinalLevel = result.getString("ordinal_level");
        Set<QualityFlag> flags = qualityFlags(result.getString("quality_flags"));
        if (ordinalLevel != null && !CrowdStage.publishable(result.getString("source_code"), ordinalLevel)) {
            // A stored stage from a source with no reviewed mapping onto the scale - today every source -
            // is an unreviewed meaning, the same as an unknown flag below: it is not served as a stage,
            // and the point says why (BA-023-T23). Being on the scale is not enough: a digit stored under
            // a source that publishes no stages would otherwise become an authoritative one.
            ordinalLevel = null;
            EnumSet<QualityFlag> drifted = flags.isEmpty() ? EnumSet.noneOf(QualityFlag.class) : EnumSet.copyOf(flags);
            drifted.add(QualityFlag.SCHEMA_DRIFT);
            flags = Set.copyOf(drifted);
        }
        SourceDescriptor source = new SourceDescriptor(result.getString("source_code"),
                result.getString("source_display_name"), result.getLong("source_registry_version"),
                result.getString("license_name"), result.getString("official_url"), result.getString("license_url"),
                result.getString("attribution"), result.getString("metric_definition"));
        return new Snapshot(result.getObject("id", UUID.class), result.getObject("snapshot_set_id", UUID.class),
                result.getObject("collector_run_id", UUID.class), result.getObject("place_id", UUID.class), source,
                SourceState.valueOf(result.getString("source_state")), instant(result, "observed_at"),
                instant(result, "target_at"), instant(result, "fetched_at"), instant(result, "stale_at"),
                result.getString("metric_code"), result.getBigDecimal("value"), result.getString("unit"),
                ordinalLevel, result.getBigDecimal("confidence"),
                flags, result.getString("forecast_issue_id"),
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
