package io.nullnull.optimization.infrastructure.persistence;

import io.nullnull.optimization.application.OptimizationRunStore;
import io.nullnull.optimization.domain.OptimizationFailureCode;
import io.nullnull.optimization.domain.OptimizationRun;
import io.nullnull.optimization.domain.OptimizationScope;
import io.nullnull.optimization.domain.OptimizationStatus;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The optimization module's own tables. No other module reads or writes them. */
@Repository
public class JdbcOptimizationRunStore implements OptimizationRunStore {

    private static final String COLUMNS = """
            id, trip_id, requested_by_owner_id, scope, target_item_id, target_date, include_candidates,
            status, input_trip_version, input_revision_id, data_fingerprint, algorithm_version,
            policy_version, policy_hash, catalog_version,
            failure_code, failure_message, queued_at, started_at, completed_at, expires_at
            """;

    private final JdbcClient jdbc;

    public JdbcOptimizationRunStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(OptimizationRun run) {
        jdbc.sql("""
                INSERT INTO optimization_runs (id, trip_id, requested_by_owner_id, scope, target_item_id,
                        target_date, include_candidates, status, input_trip_version, input_revision_id,
                        queued_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(run.id(), run.tripId(), run.ownerId(), run.scope().name(), run.targetItemId(),
                        run.targetDate() == null ? null : Date.valueOf(run.targetDate()),
                        run.includeCandidates(), run.status().name(), run.inputTripVersion(),
                        run.inputRevisionId(), Timestamp.from(run.queuedAt()))
                .update();
    }

    @Override
    public Optional<OptimizationRun> find(UUID runId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM optimization_runs WHERE id = ?")
                .param(runId)
                .query(JdbcOptimizationRunStore::map)
                .optional()
                .map(this::withSnapshotSets);
    }

    @Override
    public Optional<OptimizationRun> findForOwner(UUID ownerId, UUID runId) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM optimization_runs WHERE id = ? AND requested_by_owner_id = ?")
                .params(runId, ownerId)
                .query(JdbcOptimizationRunStore::map)
                .optional()
                .map(this::withSnapshotSets);
    }

    @Override
    public boolean transition(UUID runId, OptimizationStatus from, OptimizationStatus to, Instant at) {
        if (!from.canMoveTo(to)) {
            // Refused here rather than written and regretted: the graph is the type's, and a store
            // that accepted any pair would make the type's promise depend on every call site.
            throw new IllegalArgumentException("a run cannot move from " + from + " to " + to);
        }
        Timestamp now = Timestamp.from(at);
        return jdbc.sql("""
                UPDATE optimization_runs
                   SET status = ?,
                       started_at = CASE WHEN ? = 'RUNNING' THEN ? ELSE started_at END,
                       completed_at = CASE WHEN ? IN ('READY', 'APPLIED', 'KEPT', 'REVERTED', 'FAILED',
                                                      'EXPIRED') THEN ? ELSE completed_at END
                 WHERE id = ? AND status = ?
                """)
                .params(to.name(), to.name(), now, to.name(), now, runId, from.name())
                .update() == 1;
    }

    @Override
    public boolean markReady(UUID runId, String dataFingerprint, String algorithmVersion,
            String policyVersion, String policyHash, String catalogVersion, Instant at) {
        // expires_at IS NOT NULL is part of the condition, not an assumption: recordFrozenEvidence
        // sets it, and a run that skipped that step would otherwise reach the CHECK and throw. Asking
        // here turns "this run is not ready to be READY" into a false the handler can read.
        return jdbc.sql("""
                UPDATE optimization_runs
                   SET status = 'READY', completed_at = ?, data_fingerprint = ?, algorithm_version = ?,
                       policy_version = ?, policy_hash = ?, catalog_version = ?
                 WHERE id = ? AND status = 'RUNNING' AND expires_at IS NOT NULL
                """)
                .params(Timestamp.from(at), dataFingerprint, algorithmVersion, policyVersion,
                        policyHash, catalogVersion, runId)
                .update() == 1;
    }

    @Override
    public boolean recordFrozenEvidence(UUID runId, Instant expiresAt, List<UUID> snapshotSetIds) {
        boolean updated = jdbc.sql("""
                UPDATE optimization_runs
                   SET expires_at = ?
                 WHERE id = ? AND status = 'RUNNING'
                """)
                .params(Timestamp.from(expiresAt), runId)
                .update() == 1;
        if (!updated) {
            return false;
        }
        // Replaced rather than appended: an attempt that re-freezes must not leave the previous
        // attempt's sets claiming to be part of this run's evidence.
        jdbc.sql("DELETE FROM optimization_run_snapshot_sets WHERE run_id = ?").param(runId).update();
        for (int index = 0; index < snapshotSetIds.size(); index++) {
            jdbc.sql("""
                    INSERT INTO optimization_run_snapshot_sets (run_id, snapshot_set_id, purpose, sequence)
                    VALUES (?, ?, 'BEFORE', ?)
                    """)
                    .params(runId, snapshotSetIds.get(index), index)
                    .update();
        }
        return true;
    }

    @Override
    public boolean fail(UUID runId, OptimizationStatus from, OptimizationFailureCode code, String message,
            Instant at) {
        if (!from.canMoveTo(OptimizationStatus.FAILED)) {
            throw new IllegalArgumentException("a run cannot move from " + from + " to FAILED");
        }
        return jdbc.sql("""
                UPDATE optimization_runs
                   SET status = 'FAILED', failure_code = ?, failure_message = ?, completed_at = ?
                 WHERE id = ? AND status = ?
                """)
                .params(code.name(), message, Timestamp.from(at), runId, from.name())
                .update() == 1;
    }

    private OptimizationRun withSnapshotSets(OptimizationRun run) {
        List<UUID> sets = jdbc.sql("""
                SELECT snapshot_set_id FROM optimization_run_snapshot_sets
                 WHERE run_id = ? ORDER BY purpose, sequence
                """)
                .param(run.id())
                .query(UUID.class)
                .list();
        return new OptimizationRun(run.id(), run.tripId(), run.ownerId(), run.scope(), run.targetItemId(),
                run.targetDate(), run.includeCandidates(), run.status(), run.inputTripVersion(),
                run.inputRevisionId(), run.dataFingerprint(), run.algorithmVersion(),
                run.policyVersion(), run.policyHash(), run.catalogVersion(), run.failureCode(),
                run.failureMessage(), run.queuedAt(), run.startedAt(), run.completedAt(), run.expiresAt(),
                sets);
    }

    private static OptimizationRun map(ResultSet row, int index) throws SQLException {
        Date targetDate = row.getDate("target_date");
        String failureCode = row.getString("failure_code");
        return new OptimizationRun(row.getObject("id", UUID.class), row.getObject("trip_id", UUID.class),
                row.getObject("requested_by_owner_id", UUID.class),
                OptimizationScope.of(row.getString("scope")),
                row.getObject("target_item_id", UUID.class),
                targetDate == null ? null : targetDate.toLocalDate(),
                row.getBoolean("include_candidates"), OptimizationStatus.of(row.getString("status")),
                row.getLong("input_trip_version"), row.getObject("input_revision_id", UUID.class),
                row.getString("data_fingerprint"), row.getString("algorithm_version"),
                row.getString("policy_version"), row.getString("policy_hash"),
                row.getString("catalog_version"),
                failureCode == null ? null : OptimizationFailureCode.of(failureCode),
                row.getString("failure_message"), instant(row, "queued_at"), instant(row, "started_at"),
                instant(row, "completed_at"), instant(row, "expires_at"), java.util.List.of());
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
