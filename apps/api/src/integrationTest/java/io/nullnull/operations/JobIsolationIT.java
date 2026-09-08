package io.nullnull.operations;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.operations.application.JobContext;
import io.nullnull.operations.application.JobHandler;
import io.nullnull.operations.application.JobQueue;
import io.nullnull.operations.domain.JobPayload;
import io.nullnull.operations.domain.JobRequest;
import io.nullnull.shared.ids.UuidV7;
import io.nullnull.testsupport.MutableClock;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * BA-005-T3, second half: a saturated executor must not delay another type's claim or the readiness
 * endpoint. Measured, not asserted in prose.
 *
 * <p>One type is given a concurrency of one and three jobs whose handler blocks on a latch held for
 * the whole measurement. While it is stuck, a job of another type is enqueued and {@code
 * /health/ready} is called, and both are timed. If the poll loop, the executors or the request path
 * shared anything, the blocked handler would hold it and both measurements would run into the test
 * timeout instead of finishing in milliseconds.
 *
 * <p>The blocked handler blocks <em>inside</em> {@link JobContext#transactional}, so it holds a pooled
 * connection and not merely a thread. That is the resource that actually starves the API: a handler
 * that only held a thread would leave this measurement green no matter what the pool did, which is what
 * it used to do. What keeps the two apart at scale is not measured here but refused at startup, by
 * {@link io.nullnull.operations.application.JobConnectionBudget}.
 */
@SpringBootTest(properties = {
        "nullnull.jobs.enabled=true",
        "nullnull.jobs.poll-interval=PT0.05S",
        "nullnull.jobs.concurrency.isolation-slow=1",
        // One slot per type: two units of work, two heartbeats, two claims and the sweep are seven
        // connections in the worst case, which fits the shipped pool of 10 with the readiness reserve
        // left over (io.nullnull.operations.application.JobConnectionBudget).
        "nullnull.jobs.default-concurrency=1",
        "nullnull.ai.base-url=http://127.0.0.1:1"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class,
        JobIsolationIT.TestJobs.class})
@DisplayName("BA-005 per-type isolation")
class JobIsolationIT {

    static final Instant START = Instant.parse("2026-03-04T05:06:07Z");
    static final String SLOW = "isolation-slow";
    static final String QUICK = "isolation-quick";

    /** Opened only by the test, so the slow executor stays saturated for as long as it measures. */
    static final CountDownLatch RELEASE = new CountDownLatch(1);
    static final CountDownLatch SLOW_ENTERED = new CountDownLatch(1);

    /**
     * A claim latency this high can only come from the blocked type, not from the 50ms poll interval:
     * it is two orders of magnitude above the interval and well below any real coupling, which would
     * hold until the latch opens and time the test out instead.
     */
    private static final Duration ISOLATION_BUDGET = Duration.ofSeconds(5);
    private static final Duration READINESS_BUDGET = Duration.ofSeconds(2);
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(30);

    @TestConfiguration(proxyBeanMethods = false)
    static class TestJobs {

        @Bean
        @Primary
        MutableClock testClock() {
            return MutableClock.at(START);
        }

        /** Holds a lease-checked unit of work - and therefore a pooled connection - while it blocks. */
        @Bean
        JobHandler slowHandler() {
            return handler(SLOW, context -> context.transactional(() -> {
                SLOW_ENTERED.countDown();
                try {
                    if (!RELEASE.await(AWAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the test never released the slow handler");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }));
        }

        @Bean
        JobHandler quickHandler() {
            return handler(QUICK, context -> {
            });
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
    TransactionTemplate transactions;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @AfterEach
    void releaseTheSlowHandler() {
        RELEASE.countDown();
        await(() -> count(SLOW, "RUNNING") == 0, "the slow jobs never finished");
    }

    @Test
    @DisplayName("BA-005-T3 a saturated executor delays neither another type's claim nor readiness")
    void aSaturatedExecutorDoesNotDelayAnotherTypeOrTheApi() {
        List<UUID> slowJobs = new ArrayList<>();
        for (int job = 0; job < 3; job++) {
            slowJobs.add(enqueue(SLOW));
        }
        await(() -> SLOW_ENTERED.getCount() == 0, "the slow type never started a job");
        // Concurrency one: exactly one slow job runs and the other two wait in the queue, not in a
        // thread. That is the saturated state the measurement below happens in.
        assertThat(count(SLOW, "RUNNING")).isOne();
        assertThat(count(SLOW, "READY")).isEqualTo(2);

        // The measurement below is only about connections if the blocked handler is holding one.
        assertThat(backendsHoldingTheJobTableInAnOpenTransaction())
                .as("the blocked handler holds an open unit of work on background_jobs")
                .isPositive();

        long claimStart = System.nanoTime();
        UUID quick = enqueue(QUICK);
        await(() -> "COMPLETED".equals(status(quick)), "the quick job never completed");
        Duration quickLatency = Duration.ofNanos(System.nanoTime() - claimStart);

        long readinessStart = System.nanoTime();
        MvcTestResult ready = mvc.get().uri("/api/v1/health/ready").exchange();
        Duration readinessLatency = Duration.ofNanos(System.nanoTime() - readinessStart);

        assertThat(ready).hasStatus(HttpStatus.OK);
        assertThat(quickLatency)
                .as("a quick job waited %s while the other type was saturated", quickLatency)
                .isLessThan(ISOLATION_BUDGET);
        assertThat(readinessLatency)
                .as("/health/ready answered in %s while the other type was saturated", readinessLatency)
                .isLessThan(READINESS_BUDGET);
        // The blocked handler was still blocked the whole time, so the two measurements above were
        // taken against a genuinely saturated executor and not after it drained.
        assertThat(count(SLOW, "RUNNING")).isOne();
        assertThat(slowJobs).hasSize(3);
    }

    private UUID enqueue(String type) {
        JobRequest request = JobRequest.ready(UuidV7.create(clock), type, type + ":" + UUID.randomUUID(),
                JobPayload.empty(), 3, clock.instant());
        return transactions.execute(status -> queue.enqueue(request)).id();
    }

    private String status(UUID jobId) {
        return jdbc.queryForObject("SELECT status FROM background_jobs WHERE id = ?", String.class, jobId);
    }

    /**
     * Backends that are inside an open transaction and hold a lock on {@code background_jobs}. The
     * blocked unit of work is exactly one of those, so a handler that stopped opening one - the shape
     * this test had while it could not fail for a starved pool - reads zero here.
     */
    private int backendsHoldingTheJobTableInAnOpenTransaction() {
        return jdbc.queryForObject("SELECT count(DISTINCT a.pid) FROM pg_stat_activity a"
                + " JOIN pg_locks l ON l.pid = a.pid JOIN pg_class c ON c.oid = l.relation"
                + " WHERE a.state = 'idle in transaction' AND a.pid <> pg_backend_pid()"
                + " AND c.relname = 'background_jobs'", Integer.class);
    }

    private int count(String type, String status) {
        return jdbc.queryForObject("SELECT count(*) FROM background_jobs WHERE type = ? AND status = ?",
                Integer.class, type, status);
    }

    private static void await(BooleanSupplier condition, String failure) {
        long deadline = System.nanoTime() + AWAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
        throw new AssertionError(failure);
    }
}
