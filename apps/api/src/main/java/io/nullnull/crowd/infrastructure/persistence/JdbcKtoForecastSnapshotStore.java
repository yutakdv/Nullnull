package io.nullnull.crowd.infrastructure.persistence;

import io.nullnull.crowd.application.KtoForecastRequest;
import io.nullnull.crowd.application.KtoForecastSnapshotSet;
import io.nullnull.crowd.application.KtoForecastSnapshotStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC persistence for C4's normalized immutable forecast batches; no raw KTO response is stored. */
@Repository
public class JdbcKtoForecastSnapshotStore implements KtoForecastSnapshotStore {

    private static final String KTO_PLACE_SOURCE = "KTO_KOR_SERVICE_2";
    private static final String SCOPE_LABEL = "KTO 관광지 일별 집중률 예측";

    private final JdbcTemplate jdbc;

    public JdbcKtoForecastSnapshotStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<KtoForecastRequest> findFreshRequest(UUID placeId, Instant at) {
        return jdbc.query("""
                SELECT detail.area_code, detail.sigungu_code, detail.title
                  FROM place_external_refs reference
                  JOIN kto_place_snapshots detail
                    ON detail.source_code = reference.source_code
                   AND detail.content_id = reference.external_id
                   AND reference.external_type = 'KTO_CONTENT_TYPE:' || detail.content_type_id
                 WHERE reference.place_id = ?
                   AND reference.source_code = ?
                   AND detail.stale_at > ?
                   AND detail.area_code IS NOT NULL
                   AND detail.sigungu_code IS NOT NULL
                 ORDER BY detail.fetched_at DESC, detail.id DESC
                 LIMIT 1
                """, (result, row) -> request(placeId, result), placeId, KTO_PLACE_SOURCE, Timestamp.from(at))
                .stream().findFirst();
    }

    @Override
    public void save(KtoForecastRequest request, KtoForecastSnapshotSet snapshotSet) {
        jdbc.update("""
                INSERT INTO snapshot_sets
                    (id, source_code, source_registry_version, collector_run_id, source_state, forecast_issue_id,
                     comparison_group_id, observed_at, fetched_at, stale_at, normalization_version, created_at)
                VALUES (?, ?, ?, ?, 'FORECAST', ?, ?, NULL, ?, ?, ?, ?)
                """, snapshotSet.id(), KtoForecastSnapshotSet.SOURCE_CODE, snapshotSet.sourceRegistryVersion(),
                snapshotSet.collectorRunId(), snapshotSet.forecastIssueId(), snapshotSet.comparisonGroupId(),
                Timestamp.from(snapshotSet.fetchedAt()), Timestamp.from(snapshotSet.staleAt()),
                snapshotSet.normalizationVersion(), Timestamp.from(snapshotSet.fetchedAt()));
        for (KtoForecastSnapshotSet.ForecastPoint point : snapshotSet.points()) {
            jdbc.update("""
                    INSERT INTO crowd_snapshots
                        (id, snapshot_set_id, source_code, source_registry_version, place_id, source_state,
                         observed_at, target_at, fetched_at, stale_at, metric_code, value, unit, ordinal_level,
                         confidence, quality_flags, forecast_issue_id, comparison_group_id, normalization_version,
                         observed_at_skew_seconds, scope, scope_label, mapping_type, fallback_used, created_at)
                    VALUES (?, ?, ?, ?, ?, 'FORECAST', NULL, ?, ?, ?, ?, ?, ?, NULL, NULL, '[]'::jsonb, ?, ?, ?,
                            NULL, 'PLACE', ?, 'DIRECT', false, ?)
                    """, point.id(), snapshotSet.id(), KtoForecastSnapshotSet.SOURCE_CODE,
                    snapshotSet.sourceRegistryVersion(), request.placeId(), Timestamp.from(point.targetAt()),
                    Timestamp.from(snapshotSet.fetchedAt()), Timestamp.from(snapshotSet.staleAt()),
                    KtoForecastSnapshotSet.METRIC_CODE, point.value(), KtoForecastSnapshotSet.UNIT,
                    snapshotSet.forecastIssueId(), snapshotSet.comparisonGroupId(), snapshotSet.normalizationVersion(),
                    SCOPE_LABEL, Timestamp.from(snapshotSet.fetchedAt()));
        }
    }

    private static KtoForecastRequest request(UUID placeId, ResultSet result) throws SQLException {
        return new KtoForecastRequest(placeId, result.getString("area_code"), result.getString("sigungu_code"),
                result.getString("title"));
    }
}
