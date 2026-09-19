package io.nullnull.optimization.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.nullnull.operations.application.JobContext;
import io.nullnull.operations.application.JobExecutionException;
import io.nullnull.operations.application.JobQueue;
import io.nullnull.operations.application.JobUnitOfWorkGuard;
import io.nullnull.operations.domain.ClaimedJob;
import io.nullnull.operations.domain.JobLease;
import io.nullnull.operations.domain.JobPayload;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The handler's half of BA-005-T5 for optimize-item: its failure is not retryable. The worker's half - a
 * non-retryable optimize-item failure is dead-lettered on the attempt it happened - is measured by
 * OptimizeGatewayFailureIT (BA-051-T18), and the clause as a whole is proven end to end for
 * delete-owner-data by DeletionIncidentSignalIT. The payload is read before
 * anything else, so nothing else the handler holds is needed here.
 */
@DisplayName("BA-005 optimize-item payload")
class OptimizeItemPayloadTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-04T05:06:07Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("an optimize-item job whose payload names no run fails without a retry")
    void anUnreadablePayloadIsNotRetried() {
        OptimizeItemHandler handler = new OptimizeItemHandler(null, null, null, null, null, null, null, null, null,
                null, null, null, CLOCK);
        ClaimedJob job = new ClaimedJob(new JobLease(UUID.randomUUID(), OptimizationService.JOB_TYPE, "w1:token", 1),
                "optimization:unreadable", JobPayload.of(Map.of("runId", "not-a-run")), 5);
        JobContext context = new JobContext(job, mock(JobQueue.class),
                new TransactionTemplate(new NoTransactions()), Duration.ofSeconds(30), CLOCK, new JobUnitOfWorkGuard());

        assertThatThrownBy(() -> handler.handle(context)).satisfies(failure -> {
            assertThat(JobExecutionException.codeOf(failure)).isEqualTo("INVALID_JOB_PAYLOAD");
            assertThat(JobExecutionException.retryableOf(failure))
                    .as("the next attempt would read the same payload").isFalse();
        });
    }

    private static final class NoTransactions implements PlatformTransactionManager {
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus(true);
        }
        @Override public void commit(TransactionStatus status) {
        }
        @Override public void rollback(TransactionStatus status) {
        }
    }
}
