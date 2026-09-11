package io.nullnull.operations;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.nullnull.identity.application.OwnerRepository;
import io.nullnull.operations.application.JobContext;
import io.nullnull.operations.application.JobHandler;
import io.nullnull.operations.application.JobProperties;
import io.nullnull.operations.application.JobQueue;
import io.nullnull.operations.application.ReadinessProbe.ProbeStatus;
import io.nullnull.operations.application.ReadinessQuery;
import io.nullnull.operations.domain.JobPayload;
import io.nullnull.operations.domain.JobRequest;
import io.nullnull.shared.ids.UuidV7;
import io.nullnull.testsupport.MutableClock;
import io.nullnull.testsupport.OwnerFixtures;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The two failures where no handler ever reports anything, so nothing above the queue can charge them
 * to an attempt: the worker holding a job disappears, and the job row is held by someone else.
 *
 * <p><strong>Abandoned lease.</strong> The attempt ceiling used to live only in the worker, and only on
 * the path where a handler throws. A handler that hangs, is OOM-killed or dies with its process throws
 * nothing, so the row was re-taken on every poll for ever: measured attempts 1..6 against a ceiling of
 * 5, final status RUNNING, {@code countDeadLettersSince(24h) = 0}, so readiness stayed READY and no
 * alert line was ever written. Here the handler hangs, the lease lapses, and the queue itself has to
 * refuse the re-take and end the job.
 *
 * <p><strong>Contention.</strong> A unit of work whose row is held elsewhere used to raise a raw
 * {@code CannotAcquireLockException}, which the worker recorded as {@code HANDLER_ERROR} and charged to
 * one of the attempts - lock contention alone could dead-letter a healthy job. It must be treated as
 * what it is instead: nothing was written, no attempt spent by the handler.
 *
 * <p>One slot per type on purpose. The two synthetic types run beside the production deletion type,
 * so three units of work, three heartbeats, three claims and the retention sweep require ten
 * connections plus the two-connection readiness reserve.
 */
@SpringBootTest(properties = {
        "nullnull.jobs.enabled=true",
        "nullnull.jobs.poll-interval=PT0.05S",
        "nullnull.jobs.default-concurrency=1",
        "spring.datasource.hikari.maximum-pool-size=12",
        "nullnull.ai.base-url=http://127.0.0.1:1"})
@Import({TestcontainersConfiguration.class, JobAbandonedLeaseIT.TestJobs.class})
@DisplayName("BA-005 abandoned lease and contention")
class JobAbandonedLeaseIT {

    static final Instant START = Instant.parse("2026-03-04T05:06:07Z");
    static final String HANGING = "abandon-hanging";
    static final String CONTENDED = "abandon-contended";

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(30);

    /** Re-created per test: a latch that stayed at zero would let the next handler run straight through. */
    static volatile CountDownLatch entered = new CountDownLatch(1);
    static volatile CountDownLatch release = new CountDownLatch(1);
    /** Opened when the contended handler's unit of work has finished raising its failure. */
    static volatile CountDownLatch failureReported = new CountDownLatch(1);
    static volatile List<UUID> attemptedWrites = new CopyOnWriteArrayList<>();

    @TestConfiguration(proxyBeanMethods = false)
    static class TestJobs {

        @Bean
        @Primary
        MutableClock testClock() {
            return MutableClock.at(START);
        }

        /** A worker that is gone as far as the queue can tell: it holds the job and never finishes. */
        @Bean
        JobHandler hangingHandler() {
            return handler(HANGING, context -> {
                entered.countDown();
                await(release);
            });
        }

        /** Writes the normal way; the test makes its unit of work lose a race for the job row. */
        @Bean
        JobHandler contendedHandler(OwnerRepository owners, MutableClock clock) {
            return handler(CONTENDED, context -> {
                entered.countDown();
                await(release);
                // Built before the unit of work opens: the lease assertion that opens it is itself the
                // statement that loses the race, so nothing inside the lambda would ever run.
                var owner = OwnerFixtures.anonymous(clock);
                attemptedWrites.add(owner.id());
                try {
                    context.transactional(() -> owners.create(owner));
                } finally {
                    // Nothing is swallowed - the failure keeps propagating. This only tells the test
                    // that the row can be released now, so what the worker does next is measured
                    // against a row nobody holds.
                    failureReported.countDown();
                }
            });
        }

        private static void await(CountDownLatch latch) {
            try {
                if (!latch.await(AWAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the test never released the handler");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }

        private static JobHandler handler(String type, java.util.function.Consumer<JobContext> body) {
            return new JobHandler() {
                @Override
                public String type() {
                    return type;
                }

                @Override
                public void handle(JobContext context) {
                    body.accept(context);
                }
            };
        }
    }

    @Autowired
    JobQueue queue;

    @Autowired
    JobProperties properties;

    @Autowired
    ReadinessQuery readiness;

    @Autowired
    OwnerRepository owners;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    MutableClock clock;

    private ListAppender<ILoggingEvent> workerLog;

    @BeforeEach
    void startFromAQuietQueue() {
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        failureReported = new CountDownLatch(1);
        attemptedWrites = new CopyOnWriteArrayList<>();
        jdbc.update("DELETE FROM deletion_tombstones");
        jdbc.update("DELETE FROM deletion_requests");
        jdbc.update("DELETE FROM idempotency_records");
        jdbc.update("DELETE FROM background_jobs");
        // Shared Compose DB retains earlier identity fixtures; clear children before owners.
        jdbc.update("DELETE FROM demo_sessions");
        jdbc.update("DELETE FROM owners");
        workerLog = new ListAppender<>();
        workerLog.start();
        workerLogger().addAppender(workerLog);
    }

    @AfterEach
    void releaseTheHandler() {
        release.countDown();
        workerLogger().detachAppender(workerLog);
        workerLog.stop();
    }

    @Test
    @DisplayName("a job whose worker disappeared is not re-taken past the ceiling; it dead-letters")
    void anAbandonedJobReachesTheCeilingAndBecomesADeadLetter() {
        UUID jobId = enqueue(HANGING, 1);
        await(() -> entered.getCount() == 0, "the hanging handler never started");
        assertThat(status(jobId)).isEqualTo("RUNNING");
        assertThat(attempts(jobId)).isOne();

        // The worker is now, from the queue's side, indistinguishable from a process that died mid-job.
        String terminal = awaitTerminalWhileTheLeaseLapses(jobId);

        assertThat(terminal).as("re-taken for ever is the defect; FAILED is the fix").isEqualTo("FAILED");
        assertThat(attempts(jobId)).as("the ceiling holds on the lease-expiry path too").isOne();
        assertThat(errorCode(jobId)).isEqualTo(JobQueue.LEASE_EXPIRED_ERROR_CODE);
        assertThat(completedAt(jobId)).as("a dead letter without completed_at is invisible to the probe")
                .isNotNull();

        List<ILoggingEvent> deadLetters = workerLog.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .filter(event -> event.getFormattedMessage().contains("job dead-letter"))
                .toList();
        assertThat(deadLetters).as("the same alertable line a thrown failure produces").hasSize(1);
        assertThat(deadLetters.getFirst().getFormattedMessage())
                .contains(HANGING, jobId.toString(), JobQueue.LEASE_EXPIRED_ERROR_CODE);

        assertThat(queue.countDeadLettersSince(clock.instant().minus(properties.deadLetterWindow())))
                .isPositive();
        assertThat(readiness.readiness().checks())
                .filteredOn(check -> check.name().equals("jobs"))
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.result().status()).isEqualTo(ProbeStatus.DEGRADED);
                    assertThat(check.result().detail()).contains("dead-letter");
                });
    }

    @Test
    @DisplayName("a unit of work that loses the race for its own row is contention, not a failed attempt")
    void aContendedUnitOfWorkDoesNotSpendAnAttempt() throws Exception {
        UUID jobId = enqueue(CONTENDED, 3);
        await(() -> entered.getCount() == 0, "the contended handler never started");

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT id FROM background_jobs WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, jobId);
                try (ResultSet locked = lock.executeQuery()) {
                    assertThat(locked.next()).isTrue();
                }
            }
            // Only now does the handler try to open its unit of work, which needs the row above.
            release.countDown();
            await(() -> failureReported.getCount() == 0, "the handler never reported its failure");
            // Released before anything is asserted, so the state below is what the worker chose and
            // not what a held row prevented it from writing. Measured with the worker's contention
            // branch removed and this test still holding the row: recordFailure's retry blocked on it,
            // the row stayed RUNNING with no error code, and every assertion below passed.
            holder.rollback();
        }
        await(() -> loggedContention() == 1, "the worker never reported contention");

        assertThat(errorCode(jobId)).as("contention is not the handler's failure").isNull();
        assertThat(status(jobId)).isEqualTo("RUNNING");
        assertThat(attempts(jobId)).as("only the claim spent an attempt").isOne();
        assertThat(attemptedWrites).hasSize(1);
        assertThat(owners.findById(attemptedWrites.getFirst()))
                .as("nothing was written, so nothing has to be undone")
                .isEmpty();
    }

    private long loggedContention() {
        return workerLog.list.stream()
                .filter(event -> event.getFormattedMessage().contains("gave up waiting for its own row"))
                .count();
    }

    /**
     * Moves the injected clock past the lease until the job reaches a terminal status. The loop keeps
     * advancing because the in-flight attempt's own heartbeat extends the lease from the same clock; the
     * queue's decision is a comparison against it, which is the production path to an expired lease.
     */
    private String awaitTerminalWhileTheLeaseLapses(UUID jobId) {
        long deadline = System.nanoTime() + AWAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            String status = status(jobId);
            if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                return status;
            }
            clock.advance(properties.lease().plusSeconds(1));
            sleep();
        }
        throw new AssertionError("job " + jobId + " never reached a terminal status");
    }

    private UUID enqueue(String type, int maxAttempts) {
        JobRequest request = JobRequest.ready(UuidV7.create(clock), type, type + ":" + UUID.randomUUID(),
                JobPayload.empty(), maxAttempts, clock.instant());
        return transactions.execute(status -> queue.enqueue(request)).id();
    }

    private String status(UUID jobId) {
        return jdbc.queryForObject("SELECT status FROM background_jobs WHERE id = ?", String.class, jobId);
    }

    private int attempts(UUID jobId) {
        return jdbc.queryForObject("SELECT attempt_count FROM background_jobs WHERE id = ?",
                Integer.class, jobId);
    }

    private String errorCode(UUID jobId) {
        return jdbc.queryForObject("SELECT last_error_code FROM background_jobs WHERE id = ?",
                String.class, jobId);
    }

    private OffsetDateTime completedAt(UUID jobId) {
        return jdbc.queryForObject("SELECT completed_at FROM background_jobs WHERE id = ?",
                OffsetDateTime.class, jobId);
    }

    private static ch.qos.logback.classic.Logger workerLogger() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        return context.getLogger("io.nullnull.operations.infrastructure.jobs.JobWorker");
    }

    private static void await(BooleanSupplier condition, String failure) {
        long deadline = System.nanoTime() + AWAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep();
        }
        throw new AssertionError(failure);
    }

    private static void sleep() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
