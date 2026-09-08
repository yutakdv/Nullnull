package io.nullnull.operations.infrastructure.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import io.nullnull.operations.application.JobContext;
import io.nullnull.operations.application.JobHandler;
import io.nullnull.operations.application.JobHandlerRegistry;
import io.nullnull.operations.application.JobProperties;
import io.nullnull.operations.application.JobQueue;
import io.nullnull.operations.application.JobUnitOfWorkGuard;
import io.nullnull.operations.application.TtlSweep;
import io.nullnull.operations.domain.AbandonedJob;
import io.nullnull.operations.domain.ClaimedJob;
import io.nullnull.operations.domain.EnqueuedJob;
import io.nullnull.operations.domain.JobLease;
import io.nullnull.operations.domain.JobRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * The connection budget is checked where it has to be checked: on the way into a running worker.
 *
 * <p>{@link io.nullnull.operations.application.JobConnectionBudget} owns the arithmetic; this class
 * owns the wiring, so removing the call from {@code start()} turns the suite red rather than leaving a
 * correct formula nobody consults. No database is touched: the refusal happens before the first pool,
 * the first thread and the first claim.
 */
@DisplayName("BA-005 job worker startup budget")
class JobWorkerStartupTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-04T05:06:07Z"), ZoneOffset.UTC);
    private static final String TYPE = "budget-test";

    @Test
    void aWorkerWhoseWorstCaseCouldStarveReadinessNeverStarts() {
        // One type at concurrency 10 against the shipped pool of 10: the measured 503.
        JobWorker worker = worker(properties(10), 10);

        assertThatThrownBy(worker::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("22")
                .hasMessageContaining("spring.datasource.hikari.maximum-pool-size 10");
        assertThat(worker.isRunning()).as("nothing was scheduled and nothing was claimed").isFalse();
    }

    @Test
    void aWorkerInsideItsBudgetStarts() {
        JobWorker worker = worker(properties(2), 10);
        try {
            worker.start();
            assertThat(worker.isRunning()).isTrue();
        } finally {
            worker.stop();
        }
    }

    @Test
    void aDisabledWorkerIsNotChecked() {
        // Nothing polls and nothing sweeps, so the demand is zero whatever the concurrency says.
        JobWorker worker = worker(properties(64, false), 10);
        worker.start();
        assertThat(worker.isRunning()).isFalse();
    }

    private static JobWorker worker(JobProperties properties, int maximumPoolSize) {
        return new JobWorker(new JobHandlerRegistry(List.of(handler())), new QuietQueue(), properties,
                new JobUnitOfWorkGuard(), new TtlSweep(List.of(), CLOCK), new NoTransactionManager(),
                pool(maximumPoolSize), CLOCK);
    }

    /** Lazy: HikariCP only opens connections on the first {@code getConnection()}, which never comes. */
    private static DataSource pool(int maximumPoolSize) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setMaximumPoolSize(maximumPoolSize);
        return dataSource;
    }

    private static JobProperties properties(int concurrency) {
        return properties(concurrency, true);
    }

    private static JobProperties properties(int concurrency, boolean enabled) {
        return new JobProperties(enabled, Duration.ofSeconds(60), Duration.ofSeconds(1),
                Duration.ofSeconds(3), 5, Duration.ofSeconds(10), Duration.ofMinutes(5),
                Duration.ofMinutes(15), Duration.ofDays(7), Duration.ofHours(1), 1,
                Map.of(TYPE, concurrency));
    }

    private static JobHandler handler() {
        return new JobHandler() {
            @Override
            public String type() {
                return TYPE;
            }

            @Override
            public void handle(JobContext context) {
                throw new UnsupportedOperationException("no job is ever claimed in this test");
            }
        };
    }

    /** Answers "nothing to do" so a poll that slips through before {@code stop()} is a no-op. */
    private static final class QuietQueue implements JobQueue {

        @Override
        public EnqueuedJob enqueue(JobRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ClaimedJob> claim(String type, String leaseToken, Instant now, Instant leaseUntil) {
            return Optional.empty();
        }

        @Override
        public List<AbandonedJob> failAbandoned(String type, Instant now) {
            return List.of();
        }

        @Override
        public void assertLeaseHeld(JobLease lease, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void heartbeat(JobLease lease, Instant now, Instant leaseUntil) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void complete(JobLease lease, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void retry(JobLease lease, String errorCode, Instant now, Instant nextAttemptAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deadLetter(JobLease lease, String errorCode, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int deleteFinishedBefore(Instant cutoff) {
            return 0;
        }

        @Override
        public int countDeadLettersSince(Instant since) {
            return 0;
        }
    }

    /** The worker only needs a manager to build its template with; nothing here opens a transaction. */
    private static final class NoTransactionManager implements PlatformTransactionManager {

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus(false);
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
