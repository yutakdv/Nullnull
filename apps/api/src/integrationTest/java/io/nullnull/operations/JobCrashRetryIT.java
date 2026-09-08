package io.nullnull.operations;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.operations.application.JobContext;
import io.nullnull.operations.application.JobExecutionException;
import io.nullnull.operations.application.JobHandler;
import io.nullnull.operations.application.JobProperties;
import io.nullnull.operations.application.JobQueue;
import io.nullnull.operations.domain.JobPayload;
import io.nullnull.operations.domain.JobRequest;
import io.nullnull.shared.ids.UuidV7;
import io.nullnull.testsupport.MutableClock;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The branch the abandoned sweep must <em>not</em> take, which is the complement of
 * {@link JobAbandonedLeaseIT}. {@code JobWorker.poll} runs the sweep before the claim on every tick, so
 * a job whose worker died passes {@code JdbcJobQueue.FAIL_ABANDONED} first and
 * {@code CLAIM_EXPIRED_LEASE} second, and only the attempt condition the two share decides which of
 * them takes it: the sweep ends the row when its attempts are spent, the claim re-takes it while any
 * are left.
 *
 * <p>Measured with {@code AND attempt_count >= max_attempts} deleted from the sweep: the whole
 * integration suite stayed green while a crashed first attempt was dead-lettered on the very next poll
 * tick instead of being retried - retry after a crash gone, and nothing anywhere to say so.
 *
 * <p>Attempt 1 hangs for the rest of the test the way an OOM-killed process does: it reports nothing,
 * writes nothing and holds its concurrency slot, so the expired lease is all the queue has to go on.
 * The second slot is where the re-takes run, and they fail loudly, so a job that really does run out
 * of attempts ends with the handler's own error code and not with the sweep's.
 */
@SpringBootTest(properties = {
        "nullnull.jobs.enabled=true",
        "nullnull.jobs.poll-interval=PT0.05S",
        // Two slots for the one type: the hung attempt keeps one for ever and the re-takes need the
        // other. 2 x 2 slots + 1 type + 1 sweep = 6 connections in the worst case, inside the shipped
        // pool of 10 (io.nullnull.operations.application.JobConnectionBudget).
        "nullnull.jobs.default-concurrency=2",
        // Out of reach on purpose, so the optional recommendation probe cannot add latency or noise.
        "nullnull.ai.base-url=http://127.0.0.1:1"})
@Import({TestcontainersConfiguration.class, JobCrashRetryIT.TestJobs.class})
@DisplayName("BA-005 a crashed attempt is retried, not swept")
class JobCrashRetryIT {

    static final Instant START = Instant.parse("2026-03-04T05:06:07Z");
    static final String TYPE = "crash-retry";
    static final String AFTER_CRASH_CODE = "AFTER_CRASH_FAILURE";

    /** The row's own ceiling, well under {@code nullnull.jobs.max-attempts}. */
    static final int CEILING = 3;

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(30);

    /** Re-created per test: a latch left at zero would let the next attempt 1 run straight through. */
    static volatile CountDownLatch firstAttemptEntered = new CountDownLatch(1);
    static volatile CountDownLatch releaseTheHungAttempt = new CountDownLatch(1);
    static volatile AtomicInteger invocations = new AtomicInteger();

    @TestConfiguration(proxyBeanMethods = false)
    static class TestJobs {

        @Bean
        @Primary
        MutableClock testClock() {
            return MutableClock.at(START);
        }

        @Bean
        JobHandler crashRetryHandler() {
            return new JobHandler() {
                @Override
                public String type() {
                    return TYPE;
                }

                @Override
                public void handle(JobContext context) {
                    if (invocations.incrementAndGet() == 1) {
                        // Attempt 1 never comes back: an OOM, a hang, a process that was killed.
                        firstAttemptEntered.countDown();
                        awaitRelease();
                        return;
                    }
                    // Every re-take fails with a code of its own, so the row that finally ends at the
                    // ceiling cannot be confused with one the sweep ended.
                    throw new JobExecutionException(AFTER_CRASH_CODE,
                            "this handler fails on every attempt after the first, by design");
                }
            };
        }

        private static void awaitRelease() {
            try {
                if (!releaseTheHungAttempt.await(AWAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the test never released the hung attempt");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
    }

    @Autowired
    JobQueue queue;

    @Autowired
    JobProperties properties;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @BeforeEach
    void startFromAQuietQueue() {
        firstAttemptEntered = new CountDownLatch(1);
        releaseTheHungAttempt = new CountDownLatch(1);
        invocations = new AtomicInteger();
        jdbc.update("DELETE FROM idempotency_records");
        jdbc.update("DELETE FROM background_jobs");
    }

    @AfterEach
    void releaseTheHandler() {
        releaseTheHungAttempt.countDown();
    }

    @Test
    @DisplayName("a lease that lapsed with attempts left is re-taken, and only the ceiling ends the job")
    void aCrashedAttemptIsRetakenAndOnlyTheCeilingEndsTheJob() {
        UUID jobId = enqueue();
        await(() -> firstAttemptEntered.getCount() == 0, "attempt 1 never started");
        assertThat(status(jobId)).isEqualTo("RUNNING");
        assertThat(attempts(jobId)).isOne();

        String terminal = awaitTerminal(jobId);

        assertThat(terminal).as("a job with attempts left is retried, never swept").isEqualTo("FAILED");
        assertThat(attempts(jobId)).as("every attempt the row allows was spent").isEqualTo(CEILING);
        assertThat(errorCode(jobId))
                .as("the handler's own failure ended it at the ceiling, not the abandoned sweep")
                .isEqualTo(AFTER_CRASH_CODE)
                .isNotEqualTo(JobQueue.LEASE_EXPIRED_ERROR_CODE);
        assertThat(invocations.get()).as("the crash was re-taken and really ran again").isEqualTo(CEILING);
    }

    /**
     * Waits for a terminal status, expiring the crashed first attempt's lease by hand and otherwise
     * leaving the clock alone: a jump between a later claim and the write that follows it would expire
     * that attempt's lease and let the sweep end the job for a reason this test is not about. The
     * back-off between the failing attempts is crossed the way {@code JobWorkerIT} crosses it, by
     * moving to the {@code next_attempt_at} the worker just wrote while nothing is running.
     */
    private String awaitTerminal(UUID jobId) {
        long deadline = System.nanoTime() + AWAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            String status = status(jobId);
            if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                return status;
            }
            if ("RUNNING".equals(status) && attempts(jobId) == 1) {
                clock.advance(properties.lease().plusSeconds(1));
            } else if ("RETRY".equals(status)) {
                Instant next = nextAttemptAt(jobId);
                if (next.isAfter(clock.instant())) {
                    clock.set(next);
                }
            }
            sleep();
        }
        throw new AssertionError("job " + jobId + " never reached a terminal status");
    }

    private UUID enqueue() {
        JobRequest request = JobRequest.ready(UuidV7.create(clock), TYPE, TYPE + ":" + UUID.randomUUID(),
                JobPayload.empty(), CEILING, clock.instant());
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

    private Instant nextAttemptAt(UUID jobId) {
        return jdbc.queryForObject("SELECT next_attempt_at FROM background_jobs WHERE id = ?",
                OffsetDateTime.class, jobId).toInstant();
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
