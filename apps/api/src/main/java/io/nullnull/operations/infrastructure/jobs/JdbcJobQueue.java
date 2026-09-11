package io.nullnull.operations.infrastructure.jobs;

import io.nullnull.identity.application.LockWaitLimit;
import io.nullnull.operations.application.JobEnqueueException;
import io.nullnull.operations.application.JobHandlerRegistry;
import io.nullnull.operations.application.JobProperties;
import io.nullnull.operations.application.JobQueue;
import io.nullnull.operations.application.StaleLeaseException;
import io.nullnull.operations.domain.AbandonedJob;
import io.nullnull.operations.domain.ClaimedJob;
import io.nullnull.operations.domain.EnqueuedJob;
import io.nullnull.operations.domain.JobLease;
import io.nullnull.operations.domain.JobPayload;
import io.nullnull.operations.domain.JobRequest;
import io.nullnull.operations.domain.JobStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * JdbcClient rather than JPA, for the same reason as
 * {@code JdbcIdempotencyRecordStore}: the SQL semantics are the behaviour. Taking a job is one
 * statement - {@code FOR UPDATE SKIP LOCKED} inside a CTE feeding the UPDATE - so two workers can
 * never both take it, and every write after it carries the lease in its WHERE clause, so a zero-row
 * result is a rejected stale write and never a silent success. Neither has a faithful JPA expression
 * and there is deliberately no entity for this table.
 *
 * <p>Every statement that can wait for the job row runs with {@code nullnull.jobs.lock-timeout} on its
 * transaction and inside {@link BoundedJobLockWait}, so a lost race fails fast and arrives at the
 * worker as contention rather than as a handler failure.
 */
@Repository
public class JdbcJobQueue implements JobQueue {

    /**
     * The non-terminal statuses. The deduplication key is unique across exactly these
     * (V004's partial index), so an outstanding job holds its key and a finished one holds nothing.
     */
    private static final String OUTSTANDING_STATUSES = "('READY', 'RETRY', 'RUNNING')";

    /**
     * The {@code WHERE} of the conflict target names the partial index, which is what lets PostgreSQL
     * infer it: without it the statement would look for a unique index over the whole column, which
     * V004 removed.
     */
    private static final String INSERT_IF_ABSENT = """
            INSERT INTO background_jobs
                (id, type, deduplication_key, status, payload_reference,
                 attempt_count, max_attempts, next_attempt_at, created_at)
            VALUES (:id, :type, :deduplicationKey, 'READY', CAST(:payload AS jsonb),
                    0, :maxAttempts, :availableAt, :createdAt)
            ON CONFLICT (deduplication_key) WHERE status IN """ + OUTSTANDING_STATUSES + """
             DO NOTHING
            """;

    /** The one job the key can collide with: a finished job of the same key is history, not a holder. */
    private static final String FIND_OUTSTANDING_BY_DEDUPLICATION_KEY = """
            SELECT id, type, status
              FROM background_jobs
             WHERE deduplication_key = :deduplicationKey
               AND status IN """ + OUTSTANDING_STATUSES + """
            """;

    /**
     * The claim is two statements tried in order, and not one statement with an OR, because the OR
     * cannot be served by {@code background_jobs_claim_idx}: that index is partial on
     * {@code status IN ('READY','RETRY')}, so a {@code status = 'RUNNING'} branch beside it has
     * nothing to match. Measured on PostgreSQL 17.6 with 200,000 retained finished rows and eight
     * outstanding ones (schema as shipped in V001): the OR-ed statement was a sequential scan,
     * 200,000 rows removed by filter, 10.2ms - on every poll of every type, once a second. The split
     * first statement is an index scan on the same data, 0.02ms.
     *
     * <p>V004's partial unique index over the outstanding statuses gives the OR-ed form something to
     * use as well, so it is no longer a scan there (BitmapOr over both indexes, 0.02ms); the split is
     * still what keeps the common branch a single index scan bounded by {@code next_attempt_at}
     * instead of a bitmap recheck over every outstanding row, and it is where the attempt ceiling
     * below can be expressed without weakening the first branch.
     *
     * <p><strong>What those figures assume: a small outstanding set.</strong> Only this statement is
     * served by {@code background_jobs_claim_idx}. {@link #CLAIM_EXPIRED_LEASE} and
     * {@link #FAIL_ABANDONED} select on {@code status = 'RUNNING'}, which that partial index does not
     * cover, so both fall back to {@code background_jobs_outstanding_key_idx} and read every
     * outstanding row. Measured on the same PostgreSQL with 100,000 READY rows added to the 200,000
     * finished ones: both became bitmap index scans over about 100,009 outstanding entries with about
     * 100,006 removed by filter, 9.6ms - per poll tick, not per claim. No covering index is added for
     * that here: ERD §8 keeps indexes tied to real plans and a six-figure backlog is not this
     * product's measured state, so the numbers and the index they would justify are recorded in the
     * BA-005 card (docs/roles/BACKEND_AI_PLAYBOOK.md#ba-005) as the trigger to revisit.
     */
    private static final String CLAIM_READY_OR_RETRY = """
            WITH eligible AS (
                SELECT id
                  FROM background_jobs
                 WHERE type = :type
                   AND status IN ('READY', 'RETRY')
                   AND next_attempt_at <= :now
                 ORDER BY next_attempt_at, id
                 LIMIT 1
                 FOR UPDATE SKIP LOCKED
            )
            """;

    /**
     * How a crashed worker's job is re-taken - but only while the row has attempts left. Without the
     * attempt condition a handler that never throws (an OOM, a hang, a killed process) is re-taken on
     * every poll forever: measured attempts 1..6 against a ceiling of 5, status still RUNNING, no dead
     * letter and therefore no alert. {@link #failAbandoned} ends the rows this branch now skips.
     */
    private static final String CLAIM_EXPIRED_LEASE = """
            WITH eligible AS (
                SELECT id
                  FROM background_jobs
                 WHERE type = :type
                   AND status = 'RUNNING'
                   AND lease_until <= :now
                   AND attempt_count < max_attempts
                 ORDER BY next_attempt_at, id
                 LIMIT 1
                 FOR UPDATE SKIP LOCKED
            )
            """;

    /**
     * The claim, the lease and the attempt increment are one statement, so there is no window in which
     * a row is selected but not yet marked.
     */
    private static final String CLAIM_TAIL = """
            UPDATE background_jobs AS job
               SET status = 'RUNNING',
                   locked_by = :leaseToken,
                   lease_until = :leaseUntil,
                   heartbeat_at = :now,
                   attempt_count = job.attempt_count + 1
              FROM eligible
             WHERE job.id = eligible.id
            RETURNING job.id, job.type, job.deduplication_key, job.payload_reference,
                      job.attempt_count, job.max_attempts
            """;

    /** The complement of {@link #CLAIM_EXPIRED_LEASE}: expired lease, attempts spent, no worker alive. */
    private static final String FAIL_ABANDONED = """
            WITH abandoned AS (
                SELECT id
                  FROM background_jobs
                 WHERE type = :type
                   AND status = 'RUNNING'
                   AND lease_until <= :now
                   AND attempt_count >= max_attempts
                 FOR UPDATE SKIP LOCKED
            )
            UPDATE background_jobs AS job
               SET status = 'FAILED',
                   completed_at = :now,
                   heartbeat_at = :now,
                   last_error_code = :errorCode,
                   locked_by = NULL,
                   lease_until = NULL
              FROM abandoned
             WHERE job.id = abandoned.id
            RETURNING job.id, job.type, job.attempt_count
            """;

    /** The condition every post-claim write shares: this owner, this attempt, still RUNNING, not expired. */
    private static final String LEASE_CONDITION = """
             WHERE id = :id
               AND locked_by = :token
               AND attempt_count = :attempt
               AND status = 'RUNNING'
               AND lease_until > :now
            """;

    private static final String ASSERT_LEASE = """
            UPDATE background_jobs
               SET heartbeat_at = :now
            """ + LEASE_CONDITION;

    private static final String HEARTBEAT = """
            UPDATE background_jobs
               SET heartbeat_at = :now,
                   lease_until = :leaseUntil
            """ + LEASE_CONDITION;

    private static final String COMPLETE = """
            UPDATE background_jobs
               SET status = 'COMPLETED',
                   completed_at = :now,
                   heartbeat_at = :now,
                   locked_by = NULL,
                   lease_until = NULL
            """ + LEASE_CONDITION;

    private static final String RETRY = """
            UPDATE background_jobs
               SET status = 'RETRY',
                   next_attempt_at = :nextAttemptAt,
                   last_error_code = :errorCode,
                   heartbeat_at = :now,
                   locked_by = NULL,
                   lease_until = NULL
            """ + LEASE_CONDITION;

    private static final String DEAD_LETTER = """
            UPDATE background_jobs
               SET status = 'FAILED',
                   completed_at = :now,
                   last_error_code = :errorCode,
                   heartbeat_at = :now,
                   locked_by = NULL,
                   lease_until = NULL
            """ + LEASE_CONDITION;

    private static final String DELETE_FINISHED = """
            DELETE FROM background_jobs
             WHERE status IN ('COMPLETED', 'FAILED')
               AND completed_at <= :cutoff
            """;

    private static final String COUNT_DEAD_LETTERS = """
            SELECT count(*)
              FROM background_jobs
             WHERE status = 'FAILED'
               AND completed_at > :since
            """;

    private static final TypeReference<Map<String, String>> PAYLOAD = new TypeReference<>() {
    };

    /**
     * Two attempts to resolve a deduplication collision: one for the insert that lost the race, one
     * for the case where the outstanding job finished between the insert and the read, which frees the
     * key and makes the next insert succeed. A third would mean something is wrong, not racy.
     */
    private static final int ENQUEUE_ATTEMPTS = 2;

    private final JdbcClient jdbc;
    private final JobHandlerRegistry handlers;
    private final LockWaitLimit lockWaitLimit;
    private final JobProperties properties;
    private final ObjectMapper json;

    JdbcJobQueue(JdbcClient jdbc, JobHandlerRegistry handlers, LockWaitLimit lockWaitLimit,
            JobProperties properties, ObjectMapper json) {
        this.jdbc = jdbc;
        this.handlers = handlers;
        this.lockWaitLimit = lockWaitLimit;
        this.properties = properties;
        this.json = json;
    }

    /**
     * MANDATORY: the job must commit with whatever created it. Deliberately does not bound the lock
     * wait - this runs inside the caller's transaction, and overwriting the caller's own
     * {@code lock_timeout} would silently change the bound of every lock it takes afterwards. The
     * expiry of <em>that</em> bound is still translated, so the caller sees contention for what it is.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public EnqueuedJob enqueue(JobRequest request) {
        if (!handlers.isRegistered(request.type())) {
            throw new JobEnqueueException(
                    "no job handler is registered for the type " + request.type()
                            + "; the row would never be claimed");
        }
        if (request.maxAttempts() > properties.maxAttempts()) {
            throw new JobEnqueueException("job maxAttempts " + request.maxAttempts()
                    + " exceeds nullnull.jobs.max-attempts " + properties.maxAttempts());
        }
        for (int attempt = 1; attempt <= ENQUEUE_ATTEMPTS; attempt++) {
            boolean inserted = BoundedJobLockWait.on("enqueue", () -> jdbc.sql(INSERT_IF_ABSENT)
                    .param("id", request.id())
                    .param("type", request.type())
                    .param("deduplicationKey", request.deduplicationKey())
                    .param("payload", json.writeValueAsString(request.payload().values()))
                    .param("maxAttempts", request.maxAttempts())
                    .param("availableAt", utc(request.availableAt()))
                    .param("createdAt", utc(request.createdAt()))
                    .update()) == 1;
            if (inserted) {
                return new EnqueuedJob(request.id(), JobStatus.READY, true);
            }
            Optional<EnqueuedJob> outstanding = jdbc.sql(FIND_OUTSTANDING_BY_DEDUPLICATION_KEY)
                    .param("deduplicationKey", request.deduplicationKey())
                    .query((ResultSet rs, int row) -> outstanding(rs, request))
                    .optional();
            if (outstanding.isPresent()) {
                return outstanding.get();
            }
        }
        // The key is not echoed: it names a resource. The type is enough to find the caller.
        throw new IllegalStateException(
                "job could not be enqueued; the deduplication key kept appearing and vanishing for type "
                        + request.type());
    }

    @Override
    @Transactional
    public Optional<ClaimedJob> claim(String type, String leaseToken, Instant now, Instant leaseUntil) {
        lockWaitLimit.applyToCurrentTransaction(properties.lockTimeout());
        Optional<ClaimedJob> ready = claimWith(CLAIM_READY_OR_RETRY, type, leaseToken, now, leaseUntil);
        return ready.isPresent() ? ready
                : claimWith(CLAIM_EXPIRED_LEASE, type, leaseToken, now, leaseUntil);
    }

    private Optional<ClaimedJob> claimWith(String eligible, String type, String leaseToken, Instant now,
            Instant leaseUntil) {
        return BoundedJobLockWait.on("claim", () -> jdbc.sql(eligible + CLAIM_TAIL)
                .param("type", type)
                .param("leaseToken", leaseToken)
                .param("now", utc(now))
                .param("leaseUntil", utc(leaseUntil))
                .query((ResultSet rs, int row) -> claimed(rs, leaseToken))
                .optional());
    }

    @Override
    @Transactional
    public List<AbandonedJob> failAbandoned(String type, Instant now) {
        lockWaitLimit.applyToCurrentTransaction(properties.lockTimeout());
        return BoundedJobLockWait.on("abandoned job sweep", () -> jdbc.sql(FAIL_ABANDONED)
                .param("type", type)
                .param("now", utc(now))
                .param("errorCode", LEASE_EXPIRED_ERROR_CODE)
                .query((ResultSet rs, int row) -> new AbandonedJob(rs.getObject("id", UUID.class),
                        rs.getString("type"), rs.getInt("attempt_count")))
                .list());
    }

    /** MANDATORY: an assertion outside the handler's transaction would prove nothing about its write. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void assertLeaseHeld(JobLease lease, Instant now) {
        lockWaitLimit.applyToCurrentTransaction(properties.lockTimeout());
        require(BoundedJobLockWait.on("unit of work", () -> jdbc.sql(ASSERT_LEASE)
                .params(leaseParameters(lease, now))
                .update()), lease, "unit of work");
    }

    @Override
    @Transactional
    public void heartbeat(JobLease lease, Instant now, Instant leaseUntil) {
        lockWaitLimit.applyToCurrentTransaction(properties.lockTimeout());
        require(BoundedJobLockWait.on("heartbeat", () -> jdbc.sql(HEARTBEAT)
                .params(leaseParameters(lease, now))
                .param("leaseUntil", utc(leaseUntil))
                .update()), lease, "heartbeat");
    }

    @Override
    @Transactional
    public void complete(JobLease lease, Instant now) {
        lockWaitLimit.applyToCurrentTransaction(properties.lockTimeout());
        require(BoundedJobLockWait.on("completion", () -> jdbc.sql(COMPLETE)
                .params(leaseParameters(lease, now))
                .update()), lease, "completion");
    }

    @Override
    @Transactional
    public void retry(JobLease lease, String errorCode, Instant now, Instant nextAttemptAt) {
        lockWaitLimit.applyToCurrentTransaction(properties.lockTimeout());
        require(BoundedJobLockWait.on("retry", () -> jdbc.sql(RETRY)
                .params(leaseParameters(lease, now))
                .param("errorCode", errorCode)
                .param("nextAttemptAt", utc(nextAttemptAt))
                .update()), lease, "retry");
    }

    @Override
    @Transactional
    public void deadLetter(JobLease lease, String errorCode, Instant now) {
        lockWaitLimit.applyToCurrentTransaction(properties.lockTimeout());
        require(BoundedJobLockWait.on("dead letter", () -> jdbc.sql(DEAD_LETTER)
                .params(leaseParameters(lease, now))
                .param("errorCode", errorCode)
                .update()), lease, "dead letter");
    }

    @Override
    @Transactional
    public int deleteFinishedBefore(Instant cutoff) {
        lockWaitLimit.applyToCurrentTransaction(properties.lockTimeout());
        return BoundedJobLockWait.on("retention sweep",
                () -> jdbc.sql(DELETE_FINISHED).param("cutoff", utc(cutoff)).update());
    }

    @Override
    @Transactional(readOnly = true)
    public int countDeadLettersSince(Instant since) {
        return jdbc.sql(COUNT_DEAD_LETTERS)
                .param("since", utc(since))
                .query(Integer.class)
                .single();
    }

    private static void require(int updated, JobLease lease, String operation) {
        if (updated != 1) {
            // Zero rows means the lease moved on. Never a silent success: this is the mechanism that
            // stops a stale worker from committing (REC-JOB-01).
            throw new StaleLeaseException(lease, operation);
        }
    }

    private static Map<String, Object> leaseParameters(JobLease lease, Instant now) {
        return Map.of("id", lease.jobId(), "token", lease.token(), "attempt", lease.attempt(),
                "now", utc(now));
    }

    private ClaimedJob claimed(ResultSet rs, String leaseToken) throws SQLException {
        // The token is the one this claim just wrote, so it is not read back from the row.
        JobLease lease = new JobLease(rs.getObject("id", UUID.class), rs.getString("type"),
                leaseToken, rs.getInt("attempt_count"));
        return new ClaimedJob(lease, rs.getString("deduplication_key"),
                JobPayload.of(json.readValue(rs.getString("payload_reference"), PAYLOAD)),
                rs.getInt("max_attempts"));
    }

    private EnqueuedJob outstanding(ResultSet rs, JobRequest request) throws SQLException {
        String type = rs.getString("type");
        if (!type.equals(request.type())) {
            // One key, one outstanding job: two types sharing it would silently drop one of them.
            throw new JobEnqueueException("the deduplication key of a " + request.type()
                    + " job is already held by an outstanding job of type " + type);
        }
        return new EnqueuedJob(rs.getObject("id", UUID.class), JobStatus.valueOf(rs.getString("status")), false);
    }

    /** The PostgreSQL driver binds OffsetDateTime to timestamptz; Instant has no inferable SQL type. */
    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
