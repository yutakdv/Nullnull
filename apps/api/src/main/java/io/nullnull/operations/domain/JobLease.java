package io.nullnull.operations.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Proof that one worker holds one attempt of one job right now.
 *
 * <p>The token is written to {@code locked_by} by the claim and is fresh for every claim, so a worker
 * that lost its lease and had the job re-taken (even by itself) cannot match the row again. Every
 * write after the claim - heartbeat, the handler's unit of work, completion and failure - is
 * conditioned on this token AND this attempt, which is what
 * docs/architecture/SYSTEM_ARCHITECTURE.md §19.2 means by "the completion commit is conditioned on the
 * current lease owner and attempt so a stale worker's late write is rejected".
 */
public record JobLease(UUID jobId, String type, String token, int attempt) {

    public static final int TOKEN_MAX_LENGTH = 128;

    public JobLease {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(type, "type");
        if (token == null || token.isBlank() || token.length() > TOKEN_MAX_LENGTH) {
            throw new IllegalArgumentException("job lease token must be 1.." + TOKEN_MAX_LENGTH + " characters");
        }
        if (attempt < 1) {
            throw new IllegalArgumentException("job lease attempt must be at least 1 but was " + attempt);
        }
    }
}
