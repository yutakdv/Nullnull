package io.nullnull.operations.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * The outcome of an enqueue. {@code created} false means the deduplication key already had a job:
 * the caller gets that job's identity and current status instead of an error, because "this work is
 * already requested" is the intended answer to a duplicate request, not a failure.
 */
public record EnqueuedJob(UUID id, JobStatus status, boolean created) {

    public EnqueuedJob {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
    }
}
