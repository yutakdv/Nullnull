package io.nullnull.operations.domain;

import java.util.Objects;

/** One claimed attempt: the lease that authorises every later write, plus what the handler needs. */
public record ClaimedJob(JobLease lease, String deduplicationKey, JobPayload payload, int maxAttempts) {

    public ClaimedJob {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(deduplicationKey, "deduplicationKey");
        Objects.requireNonNull(payload, "payload");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("job maxAttempts must be at least 1 but was " + maxAttempts);
        }
    }

    /** True when this attempt is the last one the row allows, so a failure is a dead letter. */
    public boolean lastAttempt() {
        return lease.attempt() >= maxAttempts;
    }
}
