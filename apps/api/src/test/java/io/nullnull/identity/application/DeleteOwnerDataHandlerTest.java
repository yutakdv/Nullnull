package io.nullnull.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.nullnull.operations.application.JobContext;
import io.nullnull.operations.application.JobExecutionException;
import io.nullnull.operations.application.JobQueue;
import io.nullnull.operations.application.JobUnitOfWorkGuard;
import io.nullnull.operations.application.OpsAlarm;
import io.nullnull.operations.application.StaleLeaseException;
import io.nullnull.operations.domain.ClaimedJob;
import io.nullnull.operations.domain.JobLease;
import io.nullnull.operations.domain.JobPayload;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The two decisions the deletion handler's alarm rests on, without a database: the line comes only after
 * the failure it reports committed, and FAILED follows the row's attempt ceiling, which is the worker's own
 * dead-letter test. DeletionIncidentSignalIT shows the lines a real failure produces; neither of these
 * orderings can be produced there on purpose.
 */
@DisplayName("BA-072 deletion handler alarm ordering")
class DeleteOwnerDataHandlerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-04T05:06:07Z"), ZoneOffset.UTC);
    private static final String FAILURE = "OWNER_DATA_ERASE_FAILED";

    private final UUID jobId = UUID.fromString("01890000-0000-7000-8000-00000000000a");
    private final UUID owner = UUID.randomUUID();
    private final UUID request = UUID.randomUUID();

    private ch.qos.logback.classic.Logger alarmLogger;
    private ListAppender<ILoggingEvent> alarms;

    @BeforeEach
    void captureAlarms() {
        alarmLogger = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(OpsAlarm.class.getName());
        alarms = new ListAppender<>();
        alarms.start();
        alarmLogger.addAppender(alarms);
    }

    @AfterEach
    void release() {
        alarmLogger.detachAppender(alarms);
        alarms.stop();
    }

    @Test
    @DisplayName("a failure write refused because the lease lapsed logs no alarm")
    void aRefusedFailureWriteLogsNothing() {
        DeletionStore store = mock(DeletionStore.class);
        JobQueue queue = mock(JobQueue.class);
        AtomicBoolean failureWritten = new AtomicBoolean();
        doAnswer(invocation -> {
            failureWritten.set(true);
            return null;
        }).when(store).markFailed(any(), anyInt(), anyString(), anyString(), any());
        // The lease holds until the failure is written; the assertion before that commit finds it gone,
        // which is what a worker whose lease lapsed mid-attempt meets (JobContext).
        doAnswer(invocation -> {
            if (failureWritten.get()) {
                throw new StaleLeaseException(invocation.getArgument(0), "unit of work");
            }
            return null;
        }).when(queue).assertLeaseHeld(any(), any());

        assertThatThrownBy(() -> handler(store).handle(context(queue, 2, 5)))
                .isInstanceOf(StaleLeaseException.class);
        verify(store).markFailed(eq(request), eq(2), eq("PARTIAL_FAILED"), eq(FAILURE), any());
        assertThat(alarms.list).as("the write rolled back, so there is nothing to report").isEmpty();
    }

    @Test
    @DisplayName("the row's attempt ceiling decides FAILED, and its line follows the commit")
    void theRowsCeilingDecidesTheFinalFailure() {
        DeletionStore store = mock(DeletionStore.class);
        JobQueue queue = mock(JobQueue.class);

        // Last attempt by the row (2 of 2), whatever APP_DELETION_RETRY_LIMIT says today.
        assertThatThrownBy(() -> handler(store).handle(context(queue, 2, 2)))
                .isInstanceOf(JobExecutionException.class);
        verify(store).markFailed(eq(request), eq(2), eq("FAILED"), eq(FAILURE), any());

        // Not the last by the row (2 of 5): the worker retries it, so it is a partial failure.
        DeletionStore retried = mock(DeletionStore.class);
        assertThatThrownBy(() -> handler(retried).handle(context(queue, 2, 5)))
                .isInstanceOf(JobExecutionException.class);
        verify(retried).markFailed(eq(request), eq(2), eq("PARTIAL_FAILED"), eq(FAILURE), any());

        assertThat(alarms.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                OpsAlarm.deletionFailed(jobId, 2, FAILURE).line(),
                OpsAlarm.deletionPartialFailed(jobId, 2, FAILURE).line());
    }

    private DeleteOwnerDataHandler handler(DeletionStore store) {
        OwnerDataEraser failing = new OwnerDataEraser() {
            @Override public String name() { return "failing"; }
            @Override public Set<String> ownerIdTables() { return Set.of(); }
            @Override public void erase(UUID ownerId, Instant deleteBefore) {
                throw new IllegalStateException("synthetic erase failure");
            }
        };
        return new DeleteOwnerDataHandler(store, List.of(failing), CLOCK);
    }

    private JobContext context(JobQueue queue, int attempt, int maxAttempts) {
        ClaimedJob job = new ClaimedJob(new JobLease(jobId, DeletionService.JOB_TYPE, "w1:token", attempt),
                "owner:" + owner, JobPayload.of(Map.of("ownerId", owner.toString(), "requestId", request.toString())),
                maxAttempts);
        return new JobContext(job, queue, new TransactionTemplate(new CommittingTransactionManager()),
                Duration.ofSeconds(30), CLOCK, new JobUnitOfWorkGuard());
    }

    /** Commits and rolls back nothing; the lease assertions the handler relies on are the queue's. */
    private static final class CommittingTransactionManager implements PlatformTransactionManager {
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus(true);
        }
        @Override public void commit(TransactionStatus status) {
        }
        @Override public void rollback(TransactionStatus status) {
        }
    }
}
