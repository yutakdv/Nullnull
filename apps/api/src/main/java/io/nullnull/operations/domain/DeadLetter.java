package io.nullnull.operations.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * A job the worker is dead-lettering, as its handler's hook sees it (#261): the job, the attempt it ended
 * on and the {@code last_error_code} it ends with.
 */
public record DeadLetter(UUID jobId, String type, int attempt, JobPayload payload, String errorCode) {

    public DeadLetter {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(errorCode, "errorCode");
    }
}
