package io.nullnull.identity.application;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A deletion request whose status token was cleared by one sweep, as the row stood at that moment. The id
 * stays in the process: a client holds it, so it never reaches a log line (OpsAlarm).
 */
public record ExpiredReceipt(UUID requestId, String status, int attempt) {

    /** V006's pending statuses (deletion_requests_pending_idx): the deletion had not reached an end. */
    private static final Set<String> UNFINISHED = Set.of("ACCEPTED", "RUNNING", "PARTIAL_FAILED");

    public ExpiredReceipt {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(status, "status");
    }

    /** The person can no longer look this deletion up, and it is not done. */
    public boolean unfinished() {
        return UNFINISHED.contains(status);
    }
}
