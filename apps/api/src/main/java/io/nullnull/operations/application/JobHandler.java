package io.nullnull.operations.application;

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
}
