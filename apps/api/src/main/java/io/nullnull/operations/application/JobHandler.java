package io.nullnull.operations.application;

import io.nullnull.operations.domain.DeadLetter;
import io.nullnull.operations.domain.JobPayload;

/**
 * What one job type actually does. Implement it as a bean; {@link JobHandlerRegistry} indexes every
 * implementation by {@link #type()} at startup.
 *
 * <p>Contract for an implementation:
 * <ul>
 * <li>Be idempotent for one {@code deduplicationKey}. At-least-once is the guarantee the queue can
 *     give: a worker can die after committing its work and before recording completion, and the job
 *     is then re-taken (docs/architecture/ERD.md §4).</li>
 * <li>Do every write through {@link JobContext#transactional}, so the write is bound to the lease.
 *     {@link JobUnitOfWorkGuard} enforces this for the begins it can see: on the handler's own thread
 *     the helper arms exactly one permitted begin, so a {@code @Transactional} service called from the
 *     handler is refused, and so is one called with {@code REQUIRES_NEW} from inside the unit of work.
 *     Two things the guard cannot see are therefore part of this contract instead. Keep the work on
 *     the handler's thread: a transaction begun on an {@code @Async} thread or in a parallel stream is
 *     lease-free and commits unbound. And never write through {@code JdbcClient}, {@code JdbcTemplate},
 *     an {@code EntityManager}, a {@code DataSource} or a {@code Connection}: a statement in
 *     autocommit begins no transaction at all, so nothing would stop it. That last rule is only
 *     partly checked: {@code ArchitectureRulesTest.jobHandlersNeverTouchTheDatabaseDirectly} fails a
 *     handler that names one of those types itself, and it does not follow the call into a
 *     collaborator that holds one - a plain class with no transaction of its own, writing in
 *     autocommit, is invisible to both that rule and the guard. Use an application service of the
 *     owning module, called inside the unit of work.</li>
 * <li>Keep long external calls outside those transactions
 *     (docs/architecture/SYSTEM_ARCHITECTURE.md §19.2: no long external call inside a DB lock). The
 *     handler runs with no transaction of its own, so the natural shape is: read in a short unit of
 *     work, call out, write in another short unit of work.</li>
 * <li>Throw to fail the attempt; {@link JobExecutionException} names a stable error code. Never
 *     swallow a failure and return normally, which would record a completion that never happened.</li>
 * </ul>
 */
public interface JobHandler {

    /** Matches {@link io.nullnull.operations.domain.JobRequest#TYPE}; unique across all handlers. */
    String type();

    void handle(JobContext context);

    /**
     * The job has been dead-lettered: its attempts are spent, a failure was marked as final, or its
     * lease ran out with nothing left to re-take (#261). Whatever the job was working on is left where
     * the last attempt stopped unless this ends it - a record that says "in progress" for a job that
     * will never run again.
     *
     * <p>Runs in the SAME transaction as the dead-letter write, so the two commit together: a job is
     * never FAILED while what it owned still says it is running, and if this throws, the dead letter
     * rolls back with it and is written again on the next pass. That makes it at-least-once like
     * {@link #handle}, so it must be idempotent. It runs outside {@link JobContext}: write through the
     * owning module's application port and let the caller's transaction carry it, never open another.
     *
     * @param errorCode the {@code last_error_code} the job ends with - a handler's
     *                  {@link JobExecutionException} code, or {@link JobQueue#LEASE_EXPIRED_ERROR_CODE}
     */
    default void onDeadLetter(JobPayload payload, String errorCode) {
    }

    /**
     * The same hook with the job's identity and the attempt it ended on, which is what a handler needs to
     * name the job in an operator line (OpsAlarm) - emitted after the dead letter commits, never inside it.
     * The worker calls this one; by default it hands the payload and code to the two-argument form.
     */
    default void onDeadLetter(DeadLetter deadLetter) {
        onDeadLetter(deadLetter.payload(), deadLetter.errorCode());
    }
}
