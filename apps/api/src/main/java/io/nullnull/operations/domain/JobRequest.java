package io.nullnull.operations.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What a caller asks the queue to run.
 *
 * <p>{@code deduplicationKey} is the unit of "at most one outstanding piece of work"
 * (docs/architecture/ERD.md §4 "Deletion/background jobs"), and outstanding is meant literally: the
 * key is unique across READY, RETRY and RUNNING rows only. So a second enqueue while the work is still
 * outstanding returns that job instead of creating a second one, and the handler only has to be
 * idempotent per key - while an enqueue after the previous job finished creates a new job, which is
 * what lets a recurring key like {@code collector:kto:area-1} run again. A key that also covered
 * finished rows would turn the second run of every recurring job into a silent no-op until the
 * finished row was swept.
 *
 * <p>Both string bounds mirror the column widths so an over-long value is a rejected request rather
 * than a driver failure after the caller's transaction has already done work, and both shapes
 * exclude free text for the reason given on {@link JobPayload}.
 */
public record JobRequest(UUID id, String type, String deduplicationKey, JobPayload payload,
        int maxAttempts, Instant availableAt, Instant createdAt) {

    public static final int TYPE_MAX_LENGTH = 64;
    public static final int DEDUPLICATION_KEY_MAX_LENGTH = 200;

    /** Lower case with hyphens, so a type is also usable as a property key and a metric label. */
    public static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9-]{0,63}");

    static final Pattern DEDUPLICATION_KEY = Pattern.compile("[A-Za-z0-9_:.-]{1,200}");

    /**
     * Ceiling on {@code max_attempts}. Retries are for transient faults; a job that needs more than
     * twenty tries is broken, and the row would keep a poison payload alive for days of back-off.
     */
    public static final int MAX_ATTEMPTS_LIMIT = 20;

    public JobRequest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(availableAt, "availableAt");
        Objects.requireNonNull(createdAt, "createdAt");
        if (type == null || !TYPE.matcher(type).matches()) {
            throw new IllegalArgumentException("job type must match " + TYPE.pattern() + " but was: " + type);
        }
        if (deduplicationKey == null || !DEDUPLICATION_KEY.matcher(deduplicationKey).matches()) {
            // The key is built from domain IDs, but it is not echoed here: it can name a resource.
            throw new IllegalArgumentException("job deduplication key must match " + DEDUPLICATION_KEY.pattern());
        }
        if (maxAttempts < 1 || maxAttempts > MAX_ATTEMPTS_LIMIT) {
            throw new IllegalArgumentException(
                    "job maxAttempts must be between 1 and " + MAX_ATTEMPTS_LIMIT + " but was " + maxAttempts);
        }
        if (availableAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("job availableAt must not precede createdAt");
        }
    }

    /** A job that may run as soon as a worker of its type is free. */
    public static JobRequest ready(UUID id, String type, String deduplicationKey, JobPayload payload,
            int maxAttempts, Instant now) {
        return new JobRequest(id, type, deduplicationKey, payload, maxAttempts, now, now);
    }
}
