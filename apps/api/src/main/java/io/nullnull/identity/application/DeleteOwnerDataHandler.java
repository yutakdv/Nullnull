package io.nullnull.identity.application;

import io.nullnull.operations.application.JobContext;
import io.nullnull.operations.application.JobExecutionException;
import io.nullnull.operations.application.JobHandler;
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
    private final DeletionProperties properties;
    private final Clock clock;

    public DeleteOwnerDataHandler(DeletionStore deletions, List<OwnerDataEraser> erasers,
            DeletionProperties properties, Clock clock) {
        this.deletions=deletions;
        this.erasers=erasers.stream().sorted(Comparator.comparing(OwnerDataEraser::name)).toList();
        this.properties=properties; this.clock=clock;
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
            String status = context.attempt() >= properties.retryLimit ? "FAILED" : "PARTIAL_FAILED";
            context.transactional(() -> deletions.markFailed(request, context.attempt(), status,
                    FAILURE, clock.instant()));
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
