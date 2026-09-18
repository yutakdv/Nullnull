package io.nullnull.operations.domain;

import java.util.Objects;

/**
 * One claimed attempt: the lease that authorises every later write, plus what the handler needs.
 * {@code leaseRetaken} is true when the row was taken over from a RUNNING lease that had lapsed, rather
 * than from READY or RETRY.
 */
public record ClaimedJob(JobLease lease, String deduplicationKey, JobPayload payload, int maxAttempts,
        boolean leaseRetaken) {

    public ClaimedJob {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(deduplicationKey, "deduplicationKey");
        Objects.requireNonNull(payload, "payload");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("job maxAttempts must be at least 1 but was " + maxAttempts);
        }
    }

    /** A claim from READY or RETRY. */
    public ClaimedJob(JobLease lease, String deduplicationKey, JobPayload payload, int maxAttempts) {
        this(lease, deduplicationKey, payload, maxAttempts, false);
    }

    /** True when this attempt is the last one the row allows, so a failure is a dead letter. */
    public boolean lastAttempt() {
        return lease.attempt() >= maxAttempts;
    }
}
