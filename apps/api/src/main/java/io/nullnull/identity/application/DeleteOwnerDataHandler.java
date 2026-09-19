package io.nullnull.identity.application;

import io.nullnull.operations.application.JobContext;
import io.nullnull.operations.application.JobExecutionException;
import io.nullnull.operations.application.JobHandler;
import io.nullnull.operations.application.JobLockTimeoutException;
import io.nullnull.operations.application.OpsAlarm;
import io.nullnull.operations.application.StaleLeaseException;
import io.nullnull.operations.domain.DeadLetter;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public final class DeleteOwnerDataHandler implements JobHandler {
    private static final String FAILURE = "OWNER_DATA_ERASE_FAILED";
    private final DeletionStore deletions;
    private final List<OwnerDataEraser> erasers;
    private final Clock clock;

    public DeleteOwnerDataHandler(DeletionStore deletions, List<OwnerDataEraser> erasers, Clock clock) {
        this.deletions=deletions;
        this.erasers=erasers.stream().sorted(Comparator.comparing(OwnerDataEraser::name)).toList();
        this.clock=clock;
    }
    @Override public String type() { return DeletionService.JOB_TYPE; }
    @Override
    public void handle(JobContext context) {
        UUID owner = requiredUuid(context, "ownerId");
        UUID request = requiredUuid(context, "requestId");
        Instant now = clock.instant();
        try {
            // Inside the try: an attempt whose start could not be written failed like any other.
            context.transactional(() -> deletions.markRunning(request, context.attempt(), now));
            for (OwnerDataEraser eraser : erasers) {
                context.transactional(() -> eraser.erase(owner, now));
            }
            context.transactional(() -> deletions.markCompleted(request, clock.instant()));
        } catch (JobLockTimeoutException | StaleLeaseException notThisAttemptsFailure) {
            // Contention for the job row, or a lease another worker now holds: this attempt did not fail,
            // so no partial failure is recorded and no DELETION_PARTIAL_FAILED fires. The worker lets the
            // lease lapse (JobWorker.run). With attempts left the job is re-taken; on the last one the
            // abandoned sweep dead-letters it and onDeadLetter ends the request FAILED. Until then the
            // request keeps what its last committed unit of work wrote.
            throw notThisAttemptsFailure;
        } catch (RuntimeException failure) {
            // The row's ceiling, which is the worker's dead-letter test too: a setting read here could
            // have changed since enqueue and write FAILED on an attempt that is retried, or never.
            boolean last = context.lastAttempt();
            context.transactional(() -> deletions.markFailed(request, context.attempt(),
                    last ? "FAILED" : "PARTIAL_FAILED", FAILURE, clock.instant()));
            // Only once that write committed: a worker whose lease lapsed is refused at the commit and
            // throws above, so every recorded failure has one line and a refused one has none.
            OpsAlarm.emit(last ? OpsAlarm.deletionFailed(context.jobId(), context.attempt(), FAILURE)
                    : OpsAlarm.deletionPartialFailed(context.jobId(), context.attempt(), FAILURE));
            throw new JobExecutionException(FAILURE, "Owner data erasure did not complete.");
        }
    }
    /**
     * #261: a request whose job is dead-lettered - its last attempt's lease ran out, or a failure escaped
     * before it could be recorded - ends FAILED instead of saying RUNNING for a job that will never run
     * again. It writes in the dead letter's own transaction (JobHandler), so the two commit together, and
     * its DELETION_FAILED line waits for that commit. A request already ended is left alone and logs
     * nothing, which is also what makes the at-least-once hook safe to run twice.
     */
    @Override
    public void onDeadLetter(DeadLetter deadLetter) {
        UUID request;
        try {
            request = UUID.fromString(deadLetter.payload().get("requestId"));
        } catch (RuntimeException invalid) {
            // INVALID_JOB_PAYLOAD: nothing names a request, so there is no request to end.
            return;
        }
        if (deletions.failUnfinished(request, deadLetter.attempt(), FAILURE, clock.instant())) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    OpsAlarm.emit(OpsAlarm.deletionFailed(deadLetter.jobId(), deadLetter.attempt(),
                            deadLetter.errorCode()));
                }
            });
        }
    }
    /** Not retryable: the next attempt would read the same payload (JobWorker then ends the job at once). */
    private static UUID requiredUuid(JobContext context, String key) {
        try { return UUID.fromString(context.payload().get(key)); }
        catch (RuntimeException invalid) {
            // No cause: its message would carry the payload value into the worker's log line.
            throw new JobExecutionException("INVALID_JOB_PAYLOAD", "Deletion job payload is invalid.", null, false);
        }
    }
}
