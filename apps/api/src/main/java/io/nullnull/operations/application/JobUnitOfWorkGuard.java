package io.nullnull.operations.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

/**
 * Refuses a transaction begun on a handler thread outside {@link JobContext#transactional}.
 *
 * <p>A handler runs with no transaction of its own. Without this guard, calling any
 * {@code @Transactional} service from a handler would quietly start a transaction that is not bound to
 * the lease: a worker whose lease had already been re-taken would still commit its domain write, which
 * is precisely the failure REC-JOB-01 forbids, and nothing would report it.
 *
 * <p>Spring Boot registers every {@link TransactionExecutionListener} bean with the transaction
 * manager, so this listener sees every transaction begin in the application, and because
 * {@code AbstractPlatformTransactionManager} calls listeners before {@code doBegin}, throwing here
 * means the transaction never starts.
 *
 * <p><strong>Exactly what is enforced.</strong> {@link JobContext#transactional} arms a
 * <em>single</em> permitted begin, which the next begin on that thread consumes. So:
 * <ul>
 * <li>the helper's own lease-checked transaction passes;</li>
 * <li>every later begin on the same thread is refused - a {@code @Transactional} service called
 *     directly by the handler, and equally one called with {@code REQUIRES_NEW} from <em>inside</em>
 *     the unit of work, which would otherwise commit on its own and survive the rollback that a lost
 *     lease forces on everything around it.</li>
 * </ul>
 *
 * <p><strong>What is not enforced, and why.</strong>
 * <ul>
 * <li><em>Work handed to another thread.</em> The markers are thread-local, so an {@code @Async} call
 *     or a parallel stream started by a handler would begin its transaction on a thread this guard
 *     knows nothing about. No handler does that today; an {@code InheritableThreadLocal} or a
 *     {@code TaskDecorator} for a caller that does not exist would be plumbing written against a guess,
 *     so the rule is stated here instead: a handler's work stays on the handler's thread.</li>
 * <li><em>A write that opens no transaction.</em> This listener only sees begins, so a handler writing
 *     through {@code JdbcClient}, {@code JdbcTemplate} or an {@code EntityManager} in autocommit would
 *     never reach it. The ArchUnit rule
 *     {@code ArchitectureRulesTest.jobHandlersNeverTouchTheDatabaseDirectly} narrows that gap rather
 *     than closing it: it fails a {@link JobHandler} implementation that <em>names</em> one of those
 *     types directly, which is all a dependency check can see. A collaborator one hop away is not
 *     covered - measured: a handler whose only collaborator is a plain class with no
 *     {@code @Transactional} anywhere, writing through {@code jdbc.sql("INSERT ...").update()}, passes
 *     the rule, never reaches this listener and commits unbound. So what this guard catches is the
 *     same-thread transactional mistake, not every possible unbound write; the rest is the contract in
 *     {@link JobHandler}, and the residual is recorded in the BA-005 card
 *     (docs/roles/BACKEND_AI_PLAYBOOK.md#ba-005) for the slice that adds the first handler.</li>
 * <li><em>A savepoint.</em> {@code PROPAGATION_NESTED} begins no transaction: it either takes a
 *     savepoint inside the one already open, or is refused outright by the transaction manager, which
 *     is what the JPA one does because it does not allow savepoints by default. Neither reaches this
 *     listener, and neither can escape the rollback of the unit of work it sits inside, which is the
 *     property this guard exists for.</li>
 * </ul>
 */
@Component
public class JobUnitOfWorkGuard implements TransactionExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(JobUnitOfWorkGuard.class);

    private final ThreadLocal<Boolean> insideHandler = new ThreadLocal<>();
    private final ThreadLocal<Boolean> permittedBegin = new ThreadLocal<>();

    /** Called by the worker around {@code handler.handle(context)} only, never around its own writes. */
    public void enterHandler() {
        insideHandler.set(Boolean.TRUE);
    }

    public void exitHandler() {
        insideHandler.remove();
        // A handler that leaked the flag would silently disarm the guard for the next job on this
        // pooled thread, so the exit is unconditional rather than balanced against the enter.
        permittedBegin.remove();
    }

    /**
     * Permits the next transaction begin on this thread, and only that one.
     *
     * <p>Called by {@link JobContext#transactional} immediately before it opens its lease-checked
     * transaction. One-shot rather than a scope: a scope would also wave through the begins made
     * inside it, which is how a {@code REQUIRES_NEW} service escaped the unit of work it was called
     * from and outlived its rollback.
     */
    public void armUnitOfWork() {
        permittedBegin.set(Boolean.TRUE);
    }

    public void disarmUnitOfWork() {
        permittedBegin.remove();
    }

    @Override
    public void beforeBegin(TransactionExecution transaction) {
        if (!Boolean.TRUE.equals(insideHandler.get())) {
            return;
        }
        if (Boolean.TRUE.equals(permittedBegin.get())) {
            // Consumed here, so the helper's own begin is the only one this arming covers.
            permittedBegin.remove();
            return;
        }
        // No job id here: the listener does not know which job this thread runs, and the stack trace
        // of the thrown exception is what identifies the offending code.
        log.error("job handler attempted a transaction outside JobContext.transactional");
        throw new IllegalStateException(
                "A job handler must open every transaction through JobContext.transactional, which "
                        + "re-asserts the lease, so that a stale worker's write rolls back with its job row.");
    }
}
