package io.nullnull.crowd.infrastructure.persistence;

import io.nullnull.crowd.application.QuotaExhaustedException;
import io.nullnull.crowd.application.CollectorRunSourceMismatchException;
import io.nullnull.crowd.application.SourceDisabledException;
import io.nullnull.crowd.application.SourceQuotaStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL row locking makes count-and-reserve atomic across every application instance. */
@Repository
public class JdbcSourceQuotaStore implements SourceQuotaStore {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcSourceQuotaStore(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public Reservation reserve(ReservationRequest request, java.time.Instant dayStart,
            java.time.Instant nextDayStart) {
        return transactions.execute(status -> reserveInTransaction(request, dayStart, nextDayStart));
    }

    private Reservation reserveInTransaction(ReservationRequest request, java.time.Instant dayStart,
            java.time.Instant nextDayStart) {
        SourceLimit source = jdbc.query("""
                SELECT (quota_policy->>'perDay')::integer AS per_day, enabled,
                       stale_after_seconds IS NOT NULL AS has_stale_policy,
                       approval_state IN ('DEV_APPROVED', 'PROD_APPROVED') AS approved
                  FROM source_registry WHERE code = ? FOR UPDATE
                """, (rs, row) -> new SourceLimit(rs.getInt("per_day"), rs.getBoolean("enabled"),
                        rs.getBoolean("has_stale_policy"), rs.getBoolean("approved")), request.sourceCode())
                .stream().findFirst().orElse(null);
        if (source == null || !source.enabled() || !source.hasStalePolicy() || !source.approved()) {
            throw new SourceDisabledException();
        }
        Integer used = jdbc.queryForObject("""
                SELECT count(*) FROM api_ingest_logs l
                  JOIN collector_runs r ON r.id = l.collector_run_id
                 WHERE r.source_code = ? AND l.created_at >= ? AND l.created_at < ?
                """, Integer.class, request.sourceCode(), java.sql.Timestamp.from(dayStart),
                java.sql.Timestamp.from(nextDayStart));
        int current = used == null ? 0 : used;
        if (current >= source.perDay()) {
            throw new QuotaExhaustedException();
        }
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO api_ingest_logs
                    (id, collector_run_id, endpoint_key, outcome, release_version, request_id,
                     validation_result, created_at)
                SELECT ?, r.id, ?, 'STARTED', ?, ?, 'PENDING', ?
                  FROM collector_runs r
                 WHERE r.id = ? AND r.source_code = ? AND r.status = 'STARTED'
                """, id, request.endpointKey(), request.releaseVersion(), request.requestId(),
                java.sql.Timestamp.from(request.createdAt()), request.collectorRunId(), request.sourceCode());
        if (inserted != 1) {
            throw new CollectorRunSourceMismatchException();
        }
        int after = current + 1;
        List<Integer> crossed = new ArrayList<>();
        for (int threshold : List.of(60, 80, 90)) {
            int firstCount = (source.perDay() * threshold + 99) / 100;
            if (after == firstCount) {
                crossed.add(threshold);
            }
        }
        return new Reservation(id, after, source.perDay(), crossed);
    }

    private record SourceLimit(int perDay, boolean enabled, boolean hasStalePolicy, boolean approved) {
    }
}
