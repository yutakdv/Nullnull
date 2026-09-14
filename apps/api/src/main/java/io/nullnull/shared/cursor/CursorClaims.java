package io.nullnull.shared.cursor;

import java.time.Instant;
import java.util.Objects;

/**
 * Claims of an opaque cursor (docs/architecture/RECOMMENDATION_ALGORITHM.md §5.1, docs/api/README.md).
 * {@code ownerBinding} is an HMAC/hash of the owner id computed by the caller, never the raw id.
 * {@code context} binds endpoint and filters (for example {@code "feed:<selectedTripId|none>"}).
 *
 * <p>{@code sortKey} is a {@link CursorSortKey} in its wire form: the row the previous page ended
 * on. There is deliberately no ordinal beside it (BA-027). §5.1 designed one for a frozen snapshot,
 * but no snapshot table was ever built, so the ordinal became an OFFSET into a live table and every
 * page after the first was wrong whenever the set changed underneath it. A surface that could
 * accept either form would leave no way to tell which one it had used, so the ordinal is gone from
 * the record rather than merely unused.
 */
public record CursorClaims(String snapshotId, String sortKey, String ownerBinding, String context, int sortVersion,
        Instant expiresAt, String keyId) {

    public CursorClaims {
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(sortKey, "sortKey");
        Objects.requireNonNull(ownerBinding, "ownerBinding");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(keyId, "keyId");
        if (sortKey.isBlank()) {
            throw new IllegalArgumentException("sortKey must not be blank");
        }
        for (String part : new String[] {snapshotId, sortKey, ownerBinding, context, keyId}) {
            if (part.indexOf('|') >= 0) {
                throw new IllegalArgumentException("claims must not contain '|'");
            }
        }
    }
}
