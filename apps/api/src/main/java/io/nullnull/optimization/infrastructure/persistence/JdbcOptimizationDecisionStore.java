package io.nullnull.optimization.infrastructure.persistence;

import io.nullnull.optimization.application.OptimizationDecisionStore;
import io.nullnull.optimization.domain.OptimizationDecision;
import io.nullnull.optimization.domain.OptimizationDecisionKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The optimization module's own tables. No other module reads or writes them. */
@Repository
public class JdbcOptimizationDecisionStore implements OptimizationDecisionStore {

    private final JdbcClient jdbc;

    public JdbcOptimizationDecisionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertIfFirst(OptimizationDecision decision) {
        try {
            jdbc.sql("""
                    INSERT INTO optimization_decisions (id, run_id, proposal_id, owner_id, decision,
                            expected_trip_version, resulting_trip_version, before_revision_id,
                            after_revision_id, reverted_decision_id, revert_until, decided_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)
                    .params(decision.id(), decision.runId(), decision.proposalId(), decision.ownerId(),
                            decision.decision().name(), decision.expectedTripVersion(),
                            decision.resultingTripVersion(), decision.beforeRevisionId(),
                            decision.afterRevisionId(), decision.revertedDecisionId(),
                            timestamp(decision.revertUntil()), Timestamp.from(decision.decidedAt()))
                    .update();
            return true;
        } catch (DuplicateKeyException alreadyDecided) {
            // The partial unique index refused a second initial decision for this run. Caught rather
            // than checked for beforehand, because a read-then-write would leave a window in which
            // both callers saw no decision - which is exactly the race BA-052-T1 is about.
            //
            // Two indexes can raise this now, and both mean "someone got here first". V030's partial
            // unique index refuses a second INITIAL decision for a run; V033's refuses a second
            // REVERT of the same APPLY. The sentence above was written when only the first existed
            // and said a REVERT could not collide - V033 made that false, and a caller racing to
            // undo the same decision twice is exactly what it now catches.
            //
            // The primary key still cannot collide: these ids are minted here, not supplied.
            return false;
        }
    }

    @Override
    public List<OptimizationDecision> findByRun(UUID runId) {
        return jdbc.sql("""
                SELECT id, run_id, proposal_id, owner_id, decision, expected_trip_version,
                       resulting_trip_version, before_revision_id, after_revision_id,
                       reverted_decision_id, revert_until, decided_at
                  FROM optimization_decisions
                 WHERE run_id = ?
                 ORDER BY decided_at, id
                """)
                .param(runId)
                .query((ResultSet row, int index) -> decision(row))
                .list();
    }

    @Override
    public Optional<OptimizationDecision> findForOwner(UUID ownerId, UUID decisionId) {
        return jdbc.sql("""
                SELECT id, run_id, proposal_id, owner_id, decision, expected_trip_version,
                       resulting_trip_version, before_revision_id, after_revision_id,
                       reverted_decision_id, revert_until, decided_at
                  FROM optimization_decisions
                 WHERE id = ? AND owner_id = ?
                """)
                .params(decisionId, ownerId)
                .query((ResultSet row, int index) -> decision(row))
                .optional();
    }

    private static Timestamp timestamp(Instant at) {
        return at == null ? null : Timestamp.from(at);
    }

    private static OptimizationDecision decision(ResultSet row) throws SQLException {
        Timestamp revertUntil = row.getTimestamp("revert_until");
        return new OptimizationDecision(row.getObject("id", UUID.class),
                row.getObject("run_id", UUID.class), row.getObject("proposal_id", UUID.class),
                row.getObject("owner_id", UUID.class),
                OptimizationDecisionKind.valueOf(row.getString("decision")),
                row.getLong("expected_trip_version"),
                row.getObject("resulting_trip_version", Long.class),
                row.getObject("before_revision_id", UUID.class),
                row.getObject("after_revision_id", UUID.class),
                row.getObject("reverted_decision_id", UUID.class),
                revertUntil == null ? null : revertUntil.toInstant(),
                row.getTimestamp("decided_at").toInstant());
    }
}
