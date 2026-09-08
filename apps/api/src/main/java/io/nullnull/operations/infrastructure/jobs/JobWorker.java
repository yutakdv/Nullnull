package io.nullnull.operations.infrastructure.jobs;

import com.zaxxer.hikari.HikariDataSource;
import io.nullnull.operations.application.JobConnectionBudget;
import io.nullnull.operations.application.JobContext;
import io.nullnull.operations.application.JobExecutionException;
import io.nullnull.operations.application.JobHandler;
import io.nullnull.operations.application.JobHandlerRegistry;
import io.nullnull.operations.application.JobLockTimeoutException;
import io.nullnull.operations.application.JobProperties;
import io.nullnull.operations.application.JobQueue;
import io.nullnull.operations.application.JobUnitOfWorkGuard;
import io.nullnull.operations.application.StaleLeaseException;
import io.nullnull.operations.application.TtlSweep;
import io.nullnull.operations.domain.AbandonedJob;
import io.nullnull.operations.domain.ClaimedJob;
import io.nullnull.operations.domain.JobLease;
import io.nullnull.shared.ids.UuidV7;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs jobs: one poll loop and one thread pool per type, heartbeats while a handler works, bounded
 * retries and a dead letter at the ceiling.
 *
 * <p>Isolation between types is structural rather than advisory
 * (docs/architecture/SYSTEM_ARCHITECTURE.md §10, "one queue isolates collector, optimization and
 * deletion by type with per-type concurrency"): each type has its own executor and its own scheduled
 * poll, and a poll claims nothing while its own executor is full. A saturated deletion queue therefore
 * cannot delay another type's claim.
 *
 * <p>That is <em>thread</em> isolation, and it says nothing about the connection pool, which the
 * request threads share with every handler that opens a unit of work. Measured with ten handlers each
 * holding one: {@code /health/ready} answered 503 after ten seconds, because both its probes waited out
 * the Hikari connection timeout. So {@link #start()} refuses to start a worker whose worst case could
 * do that - see {@link JobConnectionBudget} for the count and the derivation - and what remains true is
 * the weaker, checked statement: the worker can never hold enough connections to make the API fail its
 * own readiness check.
 *
 * <p>Handler execution happens outside any transaction. The handler opens short, lease-checked ones
 * through {@link JobContext#transactional}, which is what keeps a long external call out of a database
 * lock (§19.2) and what lets a stale worker's write roll back with its job row.
 *
 * <p>{@code nullnull.jobs.enabled=false} starts neither the polls nor the retention sweep. Test
 * suites run with it off so a context does not race a test that seeds a job; the worker's own tests
 * turn it back on explicitly. Production runs with it on - with it off, retention would silently stop
 * as well.
 */
@Component
public class JobWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    /**
     * How long stop() waits for in-flight handlers. Whatever does not finish is not cancelled mid-write:
     * its lease simply lapses and another worker re-takes the job, which is the same path as a crash.
     */
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);

    private final JobHandlerRegistry handlers;
    private final JobQueue queue;
    private final JobProperties properties;
    private final JobUnitOfWorkGuard guard;
    private final TtlSweep retention;
    private final TransactionTemplate transactions;
    private final DataSource dataSource;
    private final Clock clock;

    /** Identifies this process in {@code locked_by}; the per-claim token follows it. */
    private final String workerId;

    // Concurrent: filled by start(), read by every poll thread, cleared by stop() while they run.
    private final Map<String, ExecutorService> executors = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();
    private final List<ScheduledFuture<?>> scheduled = new ArrayList<>();

    private volatile ThreadPoolTaskScheduler scheduler;
    private volatile boolean running;

    public JobWorker(JobHandlerRegistry handlers, JobQueue queue, JobProperties properties,
            JobUnitOfWorkGuard guard, TtlSweep retention, PlatformTransactionManager transactionManager,
            DataSource dataSource, Clock clock) {
        this.handlers = handlers;
        this.queue = queue;
        this.properties = properties;
        this.guard = guard;
        this.retention = retention;
        this.transactions = new TransactionTemplate(transactionManager);
        this.dataSource = dataSource;
        this.clock = clock;
        this.workerId = "w" + UuidV7.create(clock).toString().substring(0, 8);
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (!properties.enabled()) {
            log.info("job worker disabled by nullnull.jobs.enabled; no polling and no retention sweep");
            return;
        }
        Set<String> types = handlers.types();
        int slots = types.stream().mapToInt(properties::concurrencyFor).sum();
        // Before any pool exists: a worker that could starve readiness must not reach the point where
        // it has claimed something.
        JobConnectionBudget.requireHeadroom(types.size(), slots, maximumPoolSize());
        scheduler = new ThreadPoolTaskScheduler();
        // One thread per poll loop, one per in-flight heartbeat and one for the retention sweep, so a
        // blocked heartbeat can never delay another type's poll.
        scheduler.setPoolSize(types.size() + slots + 1);
        scheduler.setThreadNamePrefix("nullnull-job-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.initialize();
        running = true;
        for (String type : types) {
            int concurrency = properties.concurrencyFor(type);
            executors.put(type, Executors.newFixedThreadPool(concurrency, threadFactory(type)));
            inFlight.put(type, new AtomicInteger());
            scheduled.add(scheduler.scheduleWithFixedDelay(() -> pollSafely(type), properties.pollInterval()));
            log.info("job worker polling type={} concurrency={} workerId={}", type, concurrency, workerId);
        }
        scheduled.add(scheduler.scheduleWithFixedDelay(this::sweepSafely, properties.retentionSweepInterval()));
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        scheduled.forEach(future -> future.cancel(false));
        scheduled.clear();
        executors.values().forEach(ExecutorService::shutdown);
        for (Map.Entry<String, ExecutorService> entry : executors.entrySet()) {
            try {
                if (!entry.getValue().awaitTermination(SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                    // Not an error: the lease expires and the job is re-taken. Logged so a slow
                    // handler is visible instead of looking like a clean shutdown.
                    log.warn("job executor did not finish within the shutdown grace type={}", entry.getKey());
                    entry.getValue().shutdownNow();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                entry.getValue().shutdownNow();
            }
        }
        executors.clear();
        inFlight.clear();
        // Last, and only after every handler has stopped: an in-flight attempt still schedules its
        // heartbeats here. The reference is kept so a late task fails its own guard instead of a
        // NullPointerException; start() replaces it if the worker is started again.
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void pollSafely(String type) {
        try {
            poll(type);
        } catch (RuntimeException failure) {
            // A fixed-delay task that throws is never rescheduled, which would stop this type's queue
            // for the life of the process. One bad tick is logged and the next one runs.
            log.error("job poll failed type={}", type, failure);
        }
    }

    private void poll(String type) {
        AtomicInteger active = inFlight.get(type);
        ExecutorService executor = executors.get(type);
        if (!running || active == null || executor == null) {
            // A cancelled fixed-delay task is not interrupted, so a tick can still be running while
            // stop() takes the executors away. Nothing has been claimed yet; there is nothing to undo.
            return;
        }
        JobHandler handler = handlers.require(type);
        int concurrency = properties.concurrencyFor(type);
        endAbandoned(type);
        while (running && active.get() < concurrency) {
            Instant now = clock.instant();
            Optional<ClaimedJob> claimed =
                    queue.claim(type, leaseToken(), now, now.plus(properties.lease()));
            if (claimed.isEmpty()) {
                return;
            }
            ClaimedJob job = claimed.get();
            active.incrementAndGet();
            try {
                executor.execute(() -> run(job, handler, active));
            } catch (RejectedExecutionException rejected) {
                active.decrementAndGet();
                // Shutdown between the claim and the submit: the lease lapses and the job is re-taken.
                log.warn("job could not be started, the worker is stopping type={} jobId={}",
                        type, job.lease().jobId());
                return;
            }
        }
    }

    private void run(ClaimedJob job, JobHandler handler, AtomicInteger active) {
        JobLease lease = job.lease();
        ThreadPoolTaskScheduler beats = scheduler;
        if (!running || beats == null) {
            // Claimed just as the worker stopped. Nothing has been touched, so the honest move is to
            // let the lease lapse and be re-taken rather than to start work that cannot heartbeat.
            active.decrementAndGet();
            log.warn("job attempt not started, the worker is stopping type={} jobId={}",
                    lease.type(), lease.jobId());
            return;
        }
        ScheduledFuture<?> heartbeat =
                beats.scheduleWithFixedDelay(() -> heartbeat(lease), properties.heartbeatInterval());
        try {
            JobContext context =
                    new JobContext(job, queue, transactions, properties.lease(), clock, guard);
            guard.enterHandler();
            try {
                handler.handle(context);
            } finally {
                guard.exitHandler();
            }
            heartbeat.cancel(false);
            queue.complete(lease, clock.instant());
            log.info("job completed type={} jobId={} attempt={}",
                    lease.type(), lease.jobId(), lease.attempt());
        } catch (StaleLeaseException stale) {
            heartbeat.cancel(false);
            log.warn("job attempt abandoned, another worker holds the lease type={} jobId={} attempt={}",
                    lease.type(), lease.jobId(), lease.attempt());
        } catch (JobLockTimeoutException contention) {
            heartbeat.cancel(false);
            // Contention, not a handler failure: the unit of work that lost the race committed
            // nothing, so charging one of the five attempts here would let lock contention alone
            // dead-letter a healthy job. Earlier units of work in the same attempt may already have
            // committed - several short ones per attempt are the intended shape (JobContext) - and the
            // job is re-taken from the beginning once the lease lapses, which is the same path as a
            // crash and the reason a handler must be idempotent for its deduplication key.
            log.warn("job attempt gave up waiting for its own row type={} jobId={} attempt={}",
                    lease.type(), lease.jobId(), lease.attempt());
        } catch (RuntimeException failure) {
            heartbeat.cancel(false);
            recordFailure(job, failure);
        } finally {
            heartbeat.cancel(false);
            active.decrementAndGet();
        }
    }

    private void recordFailure(ClaimedJob job, RuntimeException failure) {
        JobLease lease = job.lease();
        String errorCode = JobExecutionException.codeOf(failure);
        // The throwable is logged because a poison job is otherwise undiagnosable. Handler exceptions
        // carry operator-safe messages by contract (JobHandler): no payload value and no user text.
        log.warn("job attempt failed type={} jobId={} attempt={} errorCode={}",
                lease.type(), lease.jobId(), lease.attempt(), errorCode, failure);
        try {
            Instant now = clock.instant();
            if (job.lastAttempt()) {
                queue.deadLetter(lease, errorCode, now);
                // The dead-letter line an operator alerts on: identifiers and a code, nothing else.
                log.error("job dead-letter type={} jobId={} attempts={} errorCode={}",
                        lease.type(), lease.jobId(), lease.attempt(), errorCode);
            } else {
                queue.retry(lease, errorCode, now, properties.nextAttemptAt(now, lease.attempt()));
            }
        } catch (StaleLeaseException stale) {
            log.warn("job failure not recorded, another worker holds the lease type={} jobId={}",
                    lease.type(), lease.jobId());
        } catch (JobLockTimeoutException contention) {
            // The row is held by someone else right now. The attempt was already spent by the claim,
            // so the job is re-taken after the lease lapses and ends at the ceiling either way.
            log.warn("job failure not recorded, the job row is held elsewhere type={} jobId={}",
                    lease.type(), lease.jobId());
        }
    }

    private void heartbeat(JobLease lease) {
        Instant now = clock.instant();
        try {
            queue.heartbeat(lease, now, now.plus(properties.lease()));
        } catch (StaleLeaseException stale) {
            // The job was re-taken while this attempt was still working. Its writes are already
            // refused by the same condition; nothing to stop here, and the next beat is pointless.
            log.warn("job lease lost while running type={} jobId={} attempt={}",
                    lease.type(), lease.jobId(), lease.attempt());
        } catch (JobLockTimeoutException contention) {
            // The expected case: this job's own unit of work holds the row, so the beat cannot extend
            // the lease while the work runs. That is also why JobContext bounds a unit of work by one
            // lease, and it is not worth a stack trace.
            log.debug("job heartbeat waited out its own unit of work type={} jobId={}",
                    lease.type(), lease.jobId());
        } catch (RuntimeException failure) {
            // The next beat retries; if beats keep failing the lease lapses and the job is re-taken.
            log.warn("job heartbeat failed type={} jobId={}", lease.type(), lease.jobId(), failure);
        }
    }

    /**
     * Ends the jobs of this type that no worker will ever finish: RUNNING, lease expired, attempts
     * spent. Run on every poll tick, before the claim, so the row leaves RUNNING on the same pass that
     * the claim refuses to re-take it, and the operator gets the same dead-letter line a thrown failure
     * produces.
     */
    private void endAbandoned(String type) {
        List<AbandonedJob> abandoned = queue.failAbandoned(type, clock.instant());
        for (AbandonedJob job : abandoned) {
            log.error("job dead-letter type={} jobId={} attempts={} errorCode={}",
                    job.type(), job.jobId(), job.attempts(), JobQueue.LEASE_EXPIRED_ERROR_CODE);
        }
    }

    /**
     * The real pool, not the property that usually configures it: this number is what a unit of work
     * actually competes for, and reading it from the pool means a value set any other way is still
     * checked. Nothing to fall back to if it cannot be read - an unchecked worker is the defect.
     */
    private int maximumPoolSize() {
        if (dataSource instanceof HikariDataSource hikari) {
            return hikari.getMaximumPoolSize();
        }
        try {
            if (dataSource.isWrapperFor(HikariDataSource.class)) {
                return dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("the job worker could not read the connection pool size, so "
                    + "its connection budget cannot be checked", failure);
        }
        throw new IllegalStateException("the job worker expects a HikariCP pool to size its connection "
                + "budget against, but the DataSource is " + dataSource.getClass().getName());
    }

    private void sweepSafely() {
        try {
            retention.sweep();
        } catch (RuntimeException failure) {
            log.error("retention sweep tick failed", failure);
        }
    }

    /** Fresh per claim, so a re-take invalidates the previous holder even inside the same process. */
    private String leaseToken() {
        return workerId + ":" + UuidV7.create(clock);
    }

    private static ThreadFactory threadFactory(String type) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "nullnull-job-" + type + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
