package io.nullnull.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.operations.application.JobContext;
import io.nullnull.operations.application.JobHandler;
import io.nullnull.operations.application.JobLockTimeoutException;
import io.nullnull.operations.application.JobProperties;
import io.nullnull.operations.application.JobQueue;
import io.nullnull.operations.application.JobUnitOfWorkGuard;
import io.nullnull.operations.domain.ClaimedJob;
import io.nullnull.operations.domain.JobPayload;
import io.nullnull.operations.domain.JobRequest;
import io.nullnull.shared.ids.UuidV7;
import io.nullnull.testsupport.MutableClock;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * BA-005-T3: the two job runtime durations reach the database, the way BA-002-T3 does it for the
 * idempotency guard.
 *
 * <p>Both are set to non-default values here, because a test that asserts the shipped default cannot
 * tell a wired property from a hardcoded constant. What this class exists to catch was measured:
 * deleting every {@code lockWaitLimit.applyToCurrentTransaction} call from {@code JdbcJobQueue} -
 * the runtime's entire bounded-wait mechanism - left every BA-005 integration test that existed
 * then green. The count is not the point; that the whole mechanism could go without one red test is.
 *
 * <p>The worker stays off (the suite default) so nothing claims a job behind an assertion.
 */
@SpringBootTest(properties = {
        "nullnull.jobs.lock-timeout=PT1S",
        "nullnull.jobs.lease=PT2S"})
@Import({TestcontainersConfiguration.class, JobConfigurationIT.TestJobs.class})
@DisplayName("BA-005 job runtime configuration on real PostgreSQL")
class JobConfigurationIT {

    static final Instant START = Instant.parse("2026-03-04T05:06:07Z");
    static final String TYPE = "configuration-test";

    /** The shipped defaults, which this context must not be running with. */
    private static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration DEFAULT_LEASE = Duration.ofSeconds(60);

    @TestConfiguration(proxyBeanMethods = false)
    static class TestJobs {

        @Bean
        @Primary
        MutableClock testClock() {
            return MutableClock.at(START);
        }

        @Bean
        JobHandler configurationTestHandler() {
            return new JobHandler() {
                @Override
                public String type() {
                    return TYPE;
                }

                @Override
                public void handle(JobContext context) {
                    throw new UnsupportedOperationException("the worker is disabled in this context");
                }
            };
        }
    }

    @Autowired
    JobQueue queue;

    @Autowired
    JobProperties properties;

    @Autowired
    JobUnitOfWorkGuard guard;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    MutableClock clock;

    @Value("${nullnull.jobs.lock-timeout}")
    Duration configuredLockTimeout;

    @BeforeEach
    void clearTheQueue() {
        jdbc.update("DELETE FROM idempotency_records");
        jdbc.update("DELETE FROM background_jobs");
        jdbc.update("DELETE FROM owners");
    }

    @Test
    @DisplayName("BA-005-T3 every queue statement that can wait runs under the configured lock_timeout")
    void theConfiguredLockTimeoutIsSetOnEveryStatementThatCanWait() {
        assertThat(configuredLockTimeout).isEqualTo(Duration.ofSeconds(1)).isNotEqualTo(DEFAULT_LOCK_TIMEOUT);
        assertThat(properties.lockTimeout()).isEqualTo(configuredLockTimeout);
        // One job per terminal statement below, plus the one the unit of work uses at the end.
        enqueue();
        enqueue();
        enqueue();
        enqueue();

        Map<String, String> bounds = new LinkedHashMap<>();
        AtomicReference<ClaimedJob> claimed = new AtomicReference<>();
        bounds.put("claim", lockTimeoutDuring(() -> claimed.set(claim())));
        bounds.put("failAbandoned", lockTimeoutDuring(() -> queue.failAbandoned(TYPE, clock.instant())));
        bounds.put("assertLeaseHeld",
                lockTimeoutDuring(() -> queue.assertLeaseHeld(claimed.get().lease(), clock.instant())));
        bounds.put("heartbeat", lockTimeoutDuring(() -> queue.heartbeat(claimed.get().lease(),
                clock.instant(), clock.instant().plus(properties.lease()))));
        bounds.put("complete",
                lockTimeoutDuring(() -> queue.complete(claimed.get().lease(), clock.instant())));

        ClaimedJob second = claim();
        bounds.put("retry", lockTimeoutDuring(() -> queue.retry(second.lease(), "X", clock.instant(),
                clock.instant().plusSeconds(60))));
        ClaimedJob third = claim();
        bounds.put("deadLetter",
                lockTimeoutDuring(() -> queue.deadLetter(third.lease(), "X", clock.instant())));
        bounds.put("deleteFinishedBefore",
                lockTimeoutDuring(() -> queue.deleteFinishedBefore(clock.instant())));

        // "0" is what an unset or ignored property leaves behind, and it means "wait for ever".
        assertThat(bounds).hasSize(8).allSatisfy(
                (statement, bound) -> assertThat(bound).as(statement).isEqualTo("1s"));
        // The same value inside the handler's own helper, which is where a domain write happens.
        ClaimedJob fourth = claim();
        assertThat(new JobContext(fourth, queue, transactions, properties.lease(), clock, guard)
                .transactional(() -> jdbc.queryForObject("SHOW lock_timeout", String.class)))
                .isEqualTo("1s");
    }

    /**
     * The timeout is the point of this test as much as the assertion is. Measured with every
     * {@code lockWaitLimit.applyToCurrentTransaction} call deleted: the blocked statement never
     * returned and the run sat here until it was killed at 600s, so the defect this test exists to
     * catch made it hang instead of fail. {@code SEPARATE_THREAD} because the default mode only
     * interrupts the test thread, which a driver blocked in a socket read ignores; on a separate
     * thread the unbounded wait is reported as this test failing.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    @DisplayName("BA-005-T3 a queue write blocked on the job row fails fast with the module's exception")
    void aBlockedQueueWriteFailsFastInsteadOfHanging() throws Exception {
        enqueue();
        ClaimedJob claimed = claim();

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT id FROM background_jobs WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, claimed.lease().jobId());
                try (ResultSet locked = lock.executeQuery()) {
                    assertThat(locked.next()).isTrue();
                }
            }

            long startedAt = System.nanoTime();
            // Named, not a driver exception: the worker has to tell contention from a handler failure,
            // because charging this to an attempt lets lock contention alone dead-letter a healthy job.
            assertThatThrownBy(() -> queue.complete(claimed.lease(), clock.instant()))
                    .isInstanceOf(JobLockTimeoutException.class);
            Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);

            // Without SET LOCAL lock_timeout this call never returns.
            assertThat(waited).isLessThan(Duration.ofSeconds(15));
            holder.rollback();
        }

        assertThat(status(claimed.lease().jobId())).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("a unit of work cannot sit open past the lease it is bound to")
    void aUnitOfWorkIsBoundedByOneLease() {
        assertThat(properties.lease()).isEqualTo(Duration.ofSeconds(2)).isNotEqualTo(DEFAULT_LEASE);
        enqueue();
        ClaimedJob claimed = claim();
        JobContext context =
                new JobContext(claimed, queue, transactions, properties.lease(), clock, guard);

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> context.transactional(() -> jdbc.execute("SELECT pg_sleep(20)")))
                .isInstanceOf(DataAccessException.class);
        Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);

        // The lease is two seconds, so the statement is cancelled long before the sleep would end.
        assertThat(waited).isLessThan(Duration.ofSeconds(15));
        assertThat(status(claimed.lease().jobId())).isEqualTo("RUNNING");
    }

    private String lockTimeoutDuring(Runnable statement) {
        return transactions.execute(status -> {
            statement.run();
            // Same connection as the transaction above, so this is the bound that statement ran under.
            return jdbc.queryForObject("SHOW lock_timeout", String.class);
        });
    }

    private UUID enqueue() {
        JobRequest request = JobRequest.ready(UuidV7.create(clock), TYPE,
                TYPE + ":" + UUID.randomUUID(), JobPayload.empty(), 3, clock.instant());
        return transactions.execute(status -> queue.enqueue(request)).id();
    }

    private ClaimedJob claim() {
        Instant now = clock.instant();
        return queue.claim(TYPE, "configuration:" + UUID.randomUUID(), now, now.plus(properties.lease()))
                .orElseThrow();
    }

    private String status(UUID jobId) {
        return jdbc.queryForObject("SELECT status FROM background_jobs WHERE id = ?", String.class, jobId);
    }
}
