package io.nullnull.identity.application;

import io.nullnull.operations.application.JobContext;
import io.nullnull.operations.application.JobExecutionException;
import io.nullnull.operations.application.JobHandler;
import io.nullnull.operations.application.OpsAlarm;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

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
        context.transactional(() -> deletions.markRunning(request, context.attempt(), now));
        try {
            for (OwnerDataEraser eraser : erasers) {
                context.transactional(() -> eraser.erase(owner, now));
            }
            context.transactional(() -> deletions.markCompleted(request, clock.instant()));
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
    private static UUID requiredUuid(JobContext context, String key) {
        try { return UUID.fromString(context.payload().get(key)); }
        catch (RuntimeException invalid) {
            throw new JobExecutionException("INVALID_JOB_PAYLOAD", "Deletion job payload is invalid.");
        }
    }
}
