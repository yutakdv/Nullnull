package io.nullnull.operations.domain;

import java.util.Objects;

/**
 * One claimed attempt: the lease that authorises every later write, plus what the handler needs.
 * {@code leaseRetaken} is true when the row was taken over from a RUNNING lease that had lapsed, rather
 * than from READY or RETRY.
 *
 * <p>{@code payloadUnreadable} is true when the stored payload could not be read as a {@link JobPayload}
 * (BA-005-T7). The claim still succeeds - refusing it left the row first in line for ever - but there is
 * no payload to hand anyone, so {@code payload} is then empty and must not be given to a handler as if
 * the job had carried nothing.
 */
public record ClaimedJob(JobLease lease, String deduplicationKey, JobPayload payload, int maxAttempts,
        boolean leaseRetaken, boolean payloadUnreadable) {

    public ClaimedJob {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(deduplicationKey, "deduplicationKey");
        Objects.requireNonNull(payload, "payload");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("job maxAttempts must be at least 1 but was " + maxAttempts);
        }
        if (payloadUnreadable && !payload.values().isEmpty()) {
            throw new IllegalArgumentException("an unreadable payload carries no values");
        }
    }

    /** A claim whose payload was read. */
    public ClaimedJob(JobLease lease, String deduplicationKey, JobPayload payload, int maxAttempts,
            boolean leaseRetaken) {
        this(lease, deduplicationKey, payload, maxAttempts, leaseRetaken, false);
    }

    /** A claim from READY or RETRY. */
    public ClaimedJob(JobLease lease, String deduplicationKey, JobPayload payload, int maxAttempts) {
        this(lease, deduplicationKey, payload, maxAttempts, false);
    }

    /** A claim of a row whose stored payload could not be read. */
    public static ClaimedJob withUnreadablePayload(JobLease lease, String deduplicationKey, int maxAttempts,
            boolean leaseRetaken) {
        return new ClaimedJob(lease, deduplicationKey, JobPayload.empty(), maxAttempts, leaseRetaken, true);
    }

    /** True when this attempt is the last one the row allows, so a failure is a dead letter. */
    public boolean lastAttempt() {
        return lease.attempt() >= maxAttempts;
    }
}
