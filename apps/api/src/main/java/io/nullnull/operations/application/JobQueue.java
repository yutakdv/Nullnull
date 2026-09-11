package io.nullnull.operations.application;

import io.nullnull.operations.domain.AbandonedJob;
import io.nullnull.operations.domain.ClaimedJob;
import io.nullnull.operations.domain.EnqueuedJob;
import io.nullnull.operations.domain.JobLease;
import io.nullnull.operations.domain.JobRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Storage port for the leased job queue on {@code background_jobs}.
 *
 * <p>Like {@link io.nullnull.identity.application.IdempotencyRecordStore} this port is shaped by SQL
 * semantics, because here the SQL <em>is</em> the safety property: the claim is one atomic statement
 * and every later write is conditioned on the lease it names. Splitting a claim into a read and a
 * write, or a completion into a check and an update, would reintroduce exactly the race these
 * methods exist to remove.
 *
 * <p>All {@link Instant} arguments come from the injected {@link java.time.Clock}, never from the
 * database, so a test can move time without touching rows and two workers always compare the same
 * clock.
 */
public interface JobQueue {

    /**
     * {@code last_error_code} of a job ended by {@link #failAbandoned}. A distinct code because the
     * cause is distinct: no handler reported this failure, the worker holding the job disappeared.
     */
    String LEASE_EXPIRED_ERROR_CODE = "LEASE_EXPIRED";

    /**
     * Registers a job, or returns the <em>outstanding</em> one that already holds the deduplication
     * key.
     *
     * <p>"Outstanding" is the whole meaning of the key: at most one READY, RETRY or RUNNING job may
     * hold it at a time, and a COMPLETED or FAILED job holds nothing. So a caller with a natural key -
     * {@code collector:kto:area-1} for a scheduled poll - enqueues it again after the previous run
     * finished and gets a new job, while a second enqueue of work that is still outstanding returns
     * that work with {@code created=false}. A key that also covered finished rows would make the
     * second poll a silent no-op for the whole {@code nullnull.jobs.finished-retention} window.
     *
     * <p>Requires the caller's transaction: docs/architecture/SYSTEM_ARCHITECTURE.md §19.2 requires
     * run creation and job registration (and deletion request and deletion job) to commit together,
     * so a job that outlives a rolled-back command is impossible.
     *
     * @throws JobEnqueueException when no handler is registered for the type, which would leave the
     *         row unclaimable forever, when the request exceeds the configured attempt ceiling, or
     *         when the key is already held by an outstanding job of another type
     */
    EnqueuedJob enqueue(JobRequest request);

    /**
     * Takes the next runnable job of one type and marks it RUNNING with a fresh lease and the next
     * attempt, in a single statement.
     *
     * <p>Eligible is either a READY/RETRY row whose {@code next_attempt_at} has passed, or a RUNNING
     * row whose lease has expired <em>and</em> whose attempts are not spent - that second case is how
     * a crashed worker's job is re-taken (docs/architecture/SYSTEM_ARCHITECTURE.md §10). The attempt
     * condition is what stops a handler that never throws (an OOM, a hang, a killed process) from
     * being re-taken forever; {@link #failAbandoned} ends those rows instead.
     *
     * @param leaseToken fresh per claim; the token a re-take invalidates
     * @param leaseUntil when this claim stops being authoritative unless a heartbeat extends it
     */
    Optional<ClaimedJob> claim(String type, String leaseToken, Instant now, Instant leaseUntil);

    /**
     * Ends every job of one type that {@link #claim} may no longer re-take: RUNNING, lease expired,
     * attempts spent. Each becomes FAILED with {@code completed_at} and
     * {@link #LEASE_EXPIRED_ERROR_CODE}, which is what makes it a dead letter for the readiness probe
     * and for the operator's alert.
     *
     * <p>Runs on the same poll tick as the claim: the two conditions are complements, so a row that
     * the claim skipped for its attempt count is ended by this statement on the same pass rather than
     * sitting RUNNING forever.
     *
     * @return one entry per job ended, so the caller can log the same alertable line a thrown failure
     *         produces
     */
    List<AbandonedJob> failAbandoned(String type, Instant now);

    /**
     * Re-asserts the lease inside the caller's transaction and locks the job row for its duration.
     *
     * <p>This is what binds a handler's domain write to its lease: the write and this assertion commit
     * or roll back together, and while the transaction is open no other worker can re-take the job,
     * so two workers' units of work for one job can never overlap.
     *
     * @throws StaleLeaseException when the lease is no longer the current one
     */
    void assertLeaseHeld(JobLease lease, Instant now);

    /**
     * Extends the lease while the handler is still working.
     *
     * @throws StaleLeaseException when the lease is no longer the current one
     */
    void heartbeat(JobLease lease, Instant now, Instant leaseUntil);

    /** @throws StaleLeaseException when the lease is no longer the current one */
    void complete(JobLease lease, Instant now);

    /**
     * Schedules another attempt after a retryable failure.
     *
     * @throws StaleLeaseException when the lease is no longer the current one
     */
    void retry(JobLease lease, String errorCode, Instant now, Instant nextAttemptAt);

    /**
     * Moves the job to the terminal FAILED state after the last attempt failed.
     *
     * @throws StaleLeaseException when the lease is no longer the current one
     */
    void deadLetter(JobLease lease, String errorCode, Instant now);

    /** Retention sweep: removes COMPLETED and FAILED rows finished before the cutoff. */
    int deleteFinishedBefore(Instant cutoff);

    /** How many jobs reached the dead-letter state since an instant; the input to the jobs probe. */
    int countDeadLettersSince(Instant since);
}
