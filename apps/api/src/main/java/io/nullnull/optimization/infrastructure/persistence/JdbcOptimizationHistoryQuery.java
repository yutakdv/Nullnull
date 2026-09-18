package io.nullnull.optimization.infrastructure.persistence;

import io.nullnull.optimization.application.OptimizationHistoryQuery;
import io.nullnull.optimization.domain.OptimizationDecisionKind;
import io.nullnull.optimization.domain.OptimizationScope;
import io.nullnull.optimization.domain.OptimizationStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The optimization module's own tables, plus the trip title the history line names.
 *
 * <p>That title is a read across a module boundary: {@code trips} belongs to {@code trip}
 * (SYSTEM_ARCHITECTURE §4). It is allowed here on the same terms {@code JdbcFeedStore} already
 * reads it - a read-only join for a value the response renders, with every write still going
 * through the owning module's application service.
 *
 * <p>Worth being explicit about why the architecture test stays green: {@code
 * ArchitectureRulesTest.modulesNeverReachIntoAnotherModulesInfrastructure} compares CLASS
 * dependencies against another module's {@code infrastructure} package, and a table named inside a
 * SQL string is not a class. So this passes because the check cannot see it, not because the check
 * examined it and agreed - and the next reader should not take the green as a ruling. The ruling is
 * this comment, and the precedent above.
 */
@Repository
public class JdbcOptimizationHistoryQuery implements OptimizationHistoryQuery {

    /**
     * The join to trips is an inner join on purpose. A run's trip cannot be missing - the foreign key
     * cascades, so deleting a trip deletes its runs (V024's comment: a run that outlived its trip
     * would be a record of an itinerary nobody can read any more). An outer join would be defensive
     * code for a state the schema forbids, and it would answer with a titleless row rather than let
     * the impossible state be seen.
     */
    private static final String BASE = """
            SELECT r.id, r.trip_id, t.title, r.scope, r.status, r.queued_at, r.expires_at,
                   d.decision, d.decided_at
              FROM optimization_runs r
              JOIN trips t ON t.id = r.trip_id
              LEFT JOIN LATERAL (
                       SELECT decision, decided_at
                         FROM optimization_decisions
                        WHERE run_id = r.id
                        ORDER BY decided_at DESC, id DESC
                        LIMIT 1
                   ) d ON true
             WHERE r.requested_by_owner_id = ?
            """;

    private final JdbcClient jdbc;

    public JdbcOptimizationHistoryQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<HistoryRow> page(UUID ownerId, UUID tripId, PageKey after, int limit) {
        StringBuilder sql = new StringBuilder(BASE);
        List<Object> parameters = new ArrayList<>();
        parameters.add(ownerId);
        if (tripId != null) {
            // Not a second ownership check: the owner predicate above already decides that, and a
            // trip filter that also scoped ownership would make a foreign trip id look like an empty
            // history rather than one the caller cannot see. It is the same answer here only because
            // runs carry their own owner.
            sql.append(" AND r.trip_id = ?");
            parameters.add(tripId);
        }
        if (after != null) {
            // Resume after that run rather than skipping a count of rows: a run queued later inserts
            // at the head of queued_at DESC, which under an offset would re-serve the row the reader
            // just saw. id DESC in the tie branch, matching ORDER BY.
            sql.append(" AND (r.queued_at < ? OR (r.queued_at = ? AND r.id < ?))");
            Timestamp queuedAt = Timestamp.from(after.queuedAt());
            parameters.add(queuedAt);
            parameters.add(queuedAt);
            parameters.add(after.runId());
        }
        // The ordering optimization_runs_owner_queued_idx serves. id breaks ties so the sort is
        // total: two runs queued in the same millisecond must not swap places between pages.
        sql.append(" ORDER BY r.queued_at DESC, r.id DESC LIMIT ?");
        parameters.add(limit);
        return jdbc.sql(sql.toString()).params(parameters)
                .query((ResultSet row, int index) -> map(row))
                .list();
    }

    private static HistoryRow map(ResultSet row) throws SQLException {
        String decision = row.getString("decision");
        return new HistoryRow(row.getObject("id", UUID.class), row.getObject("trip_id", UUID.class),
                row.getString("title"), OptimizationScope.of(row.getString("scope")),
                OptimizationStatus.of(row.getString("status")), instant(row, "queued_at"),
                instant(row, "expires_at"),
                decision == null ? null : OptimizationDecisionKind.valueOf(decision),
                decision == null ? null : instant(row, "decided_at"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
