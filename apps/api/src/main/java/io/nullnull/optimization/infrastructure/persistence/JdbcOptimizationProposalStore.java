package io.nullnull.optimization.infrastructure.persistence;

import io.nullnull.optimization.application.OptimizationProposalStore;
import io.nullnull.optimization.domain.OptimizationChange;
import io.nullnull.optimization.domain.OptimizationChangeOperation;
import io.nullnull.optimization.domain.OptimizationProposal;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The optimization module's own tables. No other module reads or writes them. */
@Repository
public class JdbcOptimizationProposalStore implements OptimizationProposalStore {

    private final JdbcClient jdbc;

    public JdbcOptimizationProposalStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertAll(List<OptimizationProposal> proposals) {
        for (OptimizationProposal proposal : proposals) {
            jdbc.sql("""
                    INSERT INTO optimization_proposals (id, run_id, rank, summary, comparison_eligible,
                            comparison_reason_code, crowd_delta, travel_minutes_delta,
                            validation_summary, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                    """)
                    .params(proposal.id(), proposal.runId(), proposal.rank(), proposal.summary(),
                            proposal.comparisonEligible(), proposal.comparisonReasonCode(),
                            proposal.crowdDelta(), proposal.travelMinutesDelta(),
                            proposal.validationSummary(), Timestamp.from(proposal.createdAt()))
                    .update();
            for (OptimizationChange change : proposal.changes()) {
                jdbc.sql("""
                        INSERT INTO optimization_changes (id, proposal_id, trip_item_id, operation,
                                before_value, after_value, sequence)
                        VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                        """)
                        .params(change.id(), proposal.id(), change.tripItemId(),
                                change.operation().name(), change.beforeValue(), change.afterValue(),
                                change.sequence())
                        .update();
            }
        }
    }

    @Override
    public List<OptimizationProposal> findByRun(UUID runId) {
        // Changes first, so each proposal is constructed complete. The record refuses an empty change
        // list, which means a proposal read back without its changes could not be built at all -
        // the read fails loudly rather than handing back a proposal that proposes nothing.
        Map<UUID, List<OptimizationChange>> changes = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT c.id, c.proposal_id, c.trip_item_id, c.operation, c.before_value, c.after_value,
                       c.sequence
                  FROM optimization_changes c
                  JOIN optimization_proposals p ON p.id = c.proposal_id
                 WHERE p.run_id = ?
                 ORDER BY c.sequence
                """)
                .param(runId)
                .query((ResultSet row, int index) -> {
                    changes.computeIfAbsent(row.getObject("proposal_id", UUID.class),
                            key -> new ArrayList<>()).add(change(row));
                    return null;
                })
                .list();
        return jdbc.sql("""
                SELECT id, run_id, rank, summary, comparison_eligible, comparison_reason_code,
                       crowd_delta, travel_minutes_delta, validation_summary, created_at
                  FROM optimization_proposals
                 WHERE run_id = ?
                 ORDER BY rank
                """)
                .param(runId)
                .query((ResultSet row, int index) -> proposal(row, changes))
                .list();
    }

    private static OptimizationChange change(ResultSet row) throws SQLException {
        return new OptimizationChange(row.getObject("id", UUID.class),
                row.getObject("trip_item_id", UUID.class),
                OptimizationChangeOperation.valueOf(row.getString("operation")),
                row.getString("before_value"), row.getString("after_value"), row.getInt("sequence"));
    }

    private static OptimizationProposal proposal(ResultSet row, Map<UUID, List<OptimizationChange>> changes)
            throws SQLException {
        UUID id = row.getObject("id", UUID.class);
        BigDecimal crowdDelta = row.getBigDecimal("crowd_delta");
        Integer travelMinutesDelta = row.getObject("travel_minutes_delta", Integer.class);
        return new OptimizationProposal(id, row.getObject("run_id", UUID.class), row.getInt("rank"),
                row.getString("summary"), row.getBoolean("comparison_eligible"),
                row.getString("comparison_reason_code"), crowdDelta, travelMinutesDelta,
                row.getString("validation_summary"), row.getTimestamp("created_at").toInstant(),
                changes.getOrDefault(id, List.of()));
    }
}
