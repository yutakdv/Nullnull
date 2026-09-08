package io.nullnull.shared.cursor;

import java.time.Instant;
import java.util.Objects;

/**
 * Claims of an opaque cursor (docs/architecture/RECOMMENDATION_ALGORITHM.md §5.1, docs/api/README.md).
 * {@code ownerBinding} is an HMAC/hash of the owner id computed by the caller, never the raw id.
 * {@code context} binds endpoint and filters (for example {@code "feed:<selectedTripId|none>"}).
 */
public record CursorClaims(String snapshotId, long nextOrdinal, String ownerBinding, String context, int sortVersion,
        Instant expiresAt, String keyId) {

    public CursorClaims {
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(ownerBinding, "ownerBinding");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(keyId, "keyId");
        if (nextOrdinal < 0) {
            throw new IllegalArgumentException("nextOrdinal must be >= 0");
        }
        for (String part : new String[] {snapshotId, ownerBinding, context, keyId}) {
            if (part.indexOf('|') >= 0) {
                throw new IllegalArgumentException("claims must not contain '|'");
            }
        }
    }
}
