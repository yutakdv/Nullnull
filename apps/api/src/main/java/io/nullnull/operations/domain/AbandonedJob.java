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
 *
 * <p>{@code payload} is carried so the job's handler can end what the job owned (#261,
 * {@code JobHandler#onDeadLetter}): an abandoned job is a dead letter like any other - when its payload
 * can be read. When it cannot, {@code payloadUnreadable} says so and the hook is not called.
 */
public record AbandonedJob(UUID jobId, String type, int attempts, JobPayload payload, boolean payloadUnreadable) {

    public AbandonedJob {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        if (attempts < 1) {
            throw new IllegalArgumentException("an abandoned job has at least one attempt: " + attempts);
        }
        if (payloadUnreadable && !payload.values().isEmpty()) {
            throw new IllegalArgumentException("an unreadable payload carries no values");
        }
    }

    /** An abandoned job whose payload was read. */
    public AbandonedJob(UUID jobId, String type, int attempts, JobPayload payload) {
        this(jobId, type, attempts, payload, false);
    }

    /**
     * An abandoned job whose stored payload could not be read (BA-005-T9). It ends like any other - the
     * sweep is one statement and must not roll back for one row - but nothing names what it owned.
     */
    public static AbandonedJob withUnreadablePayload(UUID jobId, String type, int attempts) {
        return new AbandonedJob(jobId, type, attempts, JobPayload.empty(), true);
    }
}
