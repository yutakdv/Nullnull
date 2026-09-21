package io.nullnull.crowd.infrastructure.persistence;

import io.nullnull.OperationsContext;
import io.nullnull.OperationsPlan;
import io.nullnull.crowd.application.SeoulLiveSnapshotStore;
import java.sql.Timestamp;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/** Lists normalized Seoul observations an owner can name in a replay capture plan. */
public final class ReplayCandidateListMain {

    private ReplayCandidateListMain() {
    }

    public static void main(String[] args) {
        try (ConfigurableApplicationContext context = OperationsContext.start(OperationsContext.Access.READ)) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            jdbc.query("""
                    SELECT point.id, point.live_area_id, point.observed_at
                      FROM crowd_snapshots point
                      JOIN live_areas area ON area.id = point.live_area_id
                      JOIN source_registry source ON source.code = point.source_code
                     WHERE point.source_code = 'SEOUL_CITYDATA'
                       AND point.source_registry_version = source.current_revision
                       AND point.scope = 'LIVE_AREA'
                       AND point.source_state IN ('LIVE', 'STALE')
                       AND point.metric_code = ?
                       AND point.normalization_version = ?
                       AND area.status = 'ACTIVE'
                     ORDER BY point.observed_at DESC, point.id DESC
                     LIMIT 20
                    """, (RowCallbackHandler) row -> System.out.println("replay_candidate snapshot=" + row.getObject(1)
                    + " area=" + row.getObject(2)
                    + " observed=" + row.getObject(3, Timestamp.class).toInstant()),
                    SeoulLiveSnapshotStore.METRIC_CODE, SeoulLiveSnapshotStore.NORMALIZATION_VERSION);
        } catch (RuntimeException failure) {
            System.out.println("replay_candidates_failed reason=" + OperationsPlan.failureReason(failure));
            throw failure;
        }
    }
}
