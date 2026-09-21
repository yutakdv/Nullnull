package io.nullnull.social.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An upload this service signed a URL for, and the record that lets the second call trust the first.
 *
 * <p>WHY THIS EXISTS AT ALL. createPost is handed an id, never a path. A caller who cannot name a
 * storage key cannot walk one - not into another visitor's prefix, not into the published location,
 * not into the app bundle the same bucket serves. BA-082-T1 (another owner's ticket cannot be
 * consumed) and BA-082-T14 (the key is built only from the owner and an id the caller never chose)
 * rest on the same fact: the key is ours and the id only resolves for its owner.
 *
 * <p>There is no EXPIRED state. Nothing would write it - the card ships no sweeping job (the
 * connection budget is held at two job types) - and a state no code can produce is a guard that can
 * never fire. {@link #usableAt(Instant)} asks the question of the row's own time instead.
 */
public record UploadIntent(UUID id, UUID ownerId, UploadIntentStatus status, String contentType,
        long contentLength, String checksumSha256, String quarantineKey, Instant createdAt,
        Instant expiresAt, Instant consumedAt) {

    public UploadIntent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(checksumSha256, "checksumSha256");
        Objects.requireNonNull(quarantineKey, "quarantineKey");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (contentLength < 1) {
            throw new IllegalArgumentException("contentLength must be positive");
        }
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must follow createdAt");
        }
        if ((status == UploadIntentStatus.PENDING) != (consumedAt == null)) {
            throw new IllegalStateException(
                    "consumedAt exists exactly while the intent is no longer PENDING");
        }
    }

    /**
     * Whether this intent may still be turned into a post.
     *
     * <p>Both halves matter and they fail differently: a CONSUMED intent is a replay, an expired one
     * is a caller who took too long. Callers that collapse them into one refusal lose the ability to
     * tell a visitor which happened.
     */
    public boolean usableAt(Instant now) {
        return status == UploadIntentStatus.PENDING && now.isBefore(expiresAt);
    }

    /** Whether this intent belongs to the caller asking about it. */
    public boolean belongsTo(UUID candidateOwnerId) {
        return ownerId.equals(candidateOwnerId);
    }
}
