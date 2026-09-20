package io.nullnull.crowd.infrastructure.persistence;

import io.nullnull.crowd.application.SeoulLiveSnapshotStore;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JDBC persistence for one normalized Seoul live-area reading; no raw provider body is stored. */
@Repository
public class JdbcSeoulLiveSnapshotStore implements SeoulLiveSnapshotStore {

    private static final String SOURCE_CODE = "SEOUL_CITYDATA";
    /**
     * The reading is directly about its subject, which here is the area itself - so DIRECT, the same
     * value the KTO writer uses for a place reading its own series. {@code AREA} is the OTHER
     * direction: a PLACE carrying an area's number, which is what seoul_live_area_maps records and
     * what LiveCoverage governs. Putting AREA here would say this row is a place measurement taken
     * from a neighbour, and it is not a place measurement at all.
     */
    private static final String MAPPING_TYPE = "DIRECT";

    private final JdbcTemplate jdbc;

    public JdbcSeoulLiveSnapshotStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void save(Reading reading) {
        jdbc.update("""
                INSERT INTO snapshot_sets
                    (id, source_code, source_registry_version, collector_run_id, source_state,
                     forecast_issue_id, comparison_group_id, observed_at, fetched_at, stale_at,
                     normalization_version, created_at)
                VALUES (?, ?, ?, ?, ?, NULL, NULL, ?, ?, ?, ?, ?)
                """, reading.snapshotSetId(), SOURCE_CODE, reading.sourceRegistryVersion(),
                reading.collectorRunId(), reading.sourceState().name(),
                Timestamp.from(reading.observedAt()), Timestamp.from(reading.fetchedAt()),
                timestamp(reading.staleAt()), NORMALIZATION_VERSION, Timestamp.from(reading.fetchedAt()));
        // place_id NULL and scope LIVE_AREA together: crowd_snapshots_scope_check makes the subject
        // and the scope one decision, so an area reading cannot be written as if it were a place's.
        jdbc.update("""
                INSERT INTO crowd_snapshots
                    (id, snapshot_set_id, source_code, source_registry_version, place_id, live_area_id,
                     source_state, observed_at, target_at, fetched_at, stale_at, metric_code, value, unit,
                     ordinal_level, confidence, quality_flags, forecast_issue_id, comparison_group_id,
                     normalization_version, observed_at_skew_seconds, scope, scope_label, mapping_type,
                     fallback_used, created_at)
                VALUES (?, ?, ?, ?, NULL, ?, ?, ?, NULL, ?, ?, ?, NULL, NULL, NULL, NULL, '[]'::jsonb,
                        NULL, NULL, ?, NULL, 'LIVE_AREA', ?, ?, false, ?)
                """, reading.snapshotId(), reading.snapshotSetId(), SOURCE_CODE,
                reading.sourceRegistryVersion(), reading.liveAreaId(), reading.sourceState().name(),
                Timestamp.from(reading.observedAt()), Timestamp.from(reading.fetchedAt()),
                timestamp(reading.staleAt()), METRIC_CODE, NORMALIZATION_VERSION, SCOPE_LABEL,
                MAPPING_TYPE, Timestamp.from(reading.fetchedAt()));
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
