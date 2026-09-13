package io.nullnull.social.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A curated post and the canonical places it mentions.
 *
 * <p>{@code publishedAt} is nullable in storage but never null on a PUBLISHED post - the feed sorts
 * by it, so a published row without one would have no position in that order at all. The database
 * enforces the pairing; this records why.
 *
 * <p>{@code coverAssetId} is the licensed asset behind {@code coverUrl} (A-024, V021). It is the id
 * only: the asset itself is a catalog row, so this module carries the reference and asks catalog for
 * the record rather than reading another module's table. Nullable here because a DRAFT is written
 * before its cover exists, and because {@code posts_published_cover_asset_check} is NOT VALID - a
 * post published before V021 keeps its free-text cover and names no asset.
 */
public record Post(UUID id, PostStatus status, String title, String excerpt, String body,
        String coverUrl, UUID coverAssetId, Instant publishedAt, List<UUID> placeIds) {

    public Post {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(coverUrl, "coverUrl");
        if (status == PostStatus.PUBLISHED && publishedAt == null) {
            throw new IllegalStateException("a published post must carry publishedAt");
        }
        placeIds = placeIds == null ? List.of() : List.copyOf(placeIds);
    }

    /** The card's headline place: the PRIMARY mention, which is position 0 by construction. */
    public UUID primaryPlaceId() {
        return placeIds.isEmpty() ? null : placeIds.get(0);
    }
}
