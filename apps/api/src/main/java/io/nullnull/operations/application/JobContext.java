package io.nullnull.operations.application;

import io.nullnull.operations.domain.ClaimedJob;
import io.nullnull.operations.domain.JobPayload;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What a {@link JobHandler} is given for one attempt: its payload reference, its deduplication key,
 * its attempt number, and the only sanctioned way to write.
 *
 * <p>{@link #transactional} opens a short transaction and asserts the lease inside it <em>twice</em>:
 * once before the handler's work runs and once immediately before the commit. Each assertion is a
 * conditional update on the job row, so it both proves the lease is still current and holds the row for
 * the rest of the transaction. Three consequences matter:
 * <ul>
 * <li>A worker whose lease expired cannot commit. Measured before the second assertion existed: a
 *     {@code PT2S} lease with an eight second unit of work committed its domain write under a lease
 *     that had lapsed six seconds earlier, the job was re-claimed, and the handler ran a second time -
 *     the duplicate execution REC-JOB-01 exists to prevent. The pre-commit assertion matches zero rows
 *     in that case, {@link StaleLeaseException} is thrown, and the domain write rolls back with it
 *     (docs/architecture/SYSTEM_ARCHITECTURE.md §19.2).</li>
 * <li>The transaction also carries a timeout of one lease, so a unit of work cannot sit open past the
 *     point where its lease could still be valid. That is a backstop, not the guarantee: the lease
 *     cannot be extended while the unit of work is open (the heartbeat's update blocks on the row this
 *     transaction holds and gives up at {@code nullnull.jobs.lock-timeout}), so one lease is an upper
 *     bound on the remaining lease and the pre-commit assertion is what makes the bound exact.</li>
 * <li>While a unit of work is open no other worker can re-take the job, so two workers' units of work
 *     for one job never overlap.</li>
 * </ul>
 *
 * <p>The handler itself runs outside any transaction, which is what keeps a long external call out of
 * a database lock. Several short units of work per attempt are the intended shape.
 */
public final class JobContext {

    private final ClaimedJob job;
    private final JobQueue queue;
    private final PlatformTransactionManager transactionManager;
    private final TransactionTemplate transactions;
    private final Duration lease;
    private final Clock clock;
    private final JobUnitOfWorkGuard guard;

    public JobContext(ClaimedJob job, JobQueue queue, TransactionTemplate transactions, Duration lease,
            Clock clock, JobUnitOfWorkGuard guard) {
        this.job = Objects.requireNonNull(job, "job");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.transactionManager = Objects.requireNonNull(transactions.getTransactionManager(),
                "the transaction template must carry its transaction manager");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.guard = Objects.requireNonNull(guard, "guard");
        if (lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("the lease must be positive but was " + lease);
        }
    }

    /** Domain identifiers only, by contract and by construction ({@link JobPayload}). */
    public JobPayload payload() {
        return job.payload();
    }

    /** The key the handler must be idempotent for. */
    public String deduplicationKey() {
        return job.deduplicationKey();
    }

    /** 1 for the first attempt. Useful for a handler that logs or degrades on a late attempt. */
    public int attempt() {
        return job.lease().attempt();
    }

    public void transactional(Runnable unitOfWork) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        transactional(() -> {
            unitOfWork.run();
            return null;
        });
    }

    /**
     * Runs one unit of work in a transaction that only commits while this lease is still current.
     *
     * @throws StaleLeaseException when the lease was lost; the work has rolled back
     * @throws org.springframework.transaction.TransactionTimedOutException when the unit of work
     *         outlived a whole lease, which the pre-commit assertion would refuse anyway
     */
    public <T> T transactional(Supplier<T> unitOfWork) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // Joining someone else's transaction would put the lease assertion and the write in a unit
            // of work this context does not control, and a nested call would re-assert pointlessly.
            throw new IllegalStateException(
                    "JobContext.transactional must open its own transaction; one is already active");
        }
        guard.armUnitOfWork();
        try {
            return boundedByTheLease().execute(status -> {
                queue.assertLeaseHeld(job.lease(), clock.instant());
                T result = unitOfWork.get();
                // The one that matters: the lease can lapse while the work runs, and a commit after
                // that is a second execution of the same job.
                queue.assertLeaseHeld(job.lease(), clock.instant());
                return result;
            });
        } finally {
            guard.disarmUnitOfWork();
        }
    }

    /**
     * A fresh template per unit of work: the worker's own template is shared by every job on every
     * thread, so setting a timeout on it would leak into other attempts.
     */
    private TransactionTemplate boundedByTheLease() {
        TransactionTemplate bounded = new TransactionTemplate(transactionManager, transactions);
        bounded.setTimeout(leaseTimeoutSeconds());
        return bounded;
    }

    /** Whole seconds, rounded up, and never below one: the lease itself is floored at one second. */
    private int leaseTimeoutSeconds() {
        long seconds = Math.ceilDiv(lease.toMillis(), 1000L);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, seconds));
    }
}
