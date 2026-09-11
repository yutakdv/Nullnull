package io.nullnull.operations.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * A job that ran out of attempts without any worker being alive to say so: its lease expired while it
 * was RUNNING and {@code attempt_count} had already reached {@code max_attempts}.
 *
 * <p>It exists because the attempt ceiling used to be applied in one place only - the worker, when a
 * handler threw. A handler that hangs, gets killed, or dies with its process never throws, so the row
 * was re-taken on every poll for as long as it existed: measured attempts 1..6 against a ceiling of 5,
 * final status RUNNING, and zero dead letters, so readiness stayed READY and no alert line was ever
 * written. The queue now ends such a job itself, and this is what it reports so the worker can log the
 * same alertable dead-letter line a thrown failure produces.
 */
public record AbandonedJob(UUID jobId, String type, int attempts) {

    public AbandonedJob {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(type, "type");
        if (attempts < 1) {
            throw new IllegalArgumentException("an abandoned job has at least one attempt: " + attempts);
        }
    }
}
