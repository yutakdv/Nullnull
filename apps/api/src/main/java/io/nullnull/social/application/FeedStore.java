package io.nullnull.social.application;

import io.nullnull.social.domain.Post;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Persistence port for curated posts and an owner's saved posts. Owned by the social module. */
public interface FeedStore {

    /**
     * One page of PUBLISHED posts in the fixed order, resuming after {@code after}.
     *
     * @param after the last post of the previous page, or null for the first page
     */
    List<Post> publishedPage(PageKey after, int limit);

    /** The row a feed page ended on, in the terms {@link io.nullnull.social.domain.FeedOrdering} sorts by. */
    record PageKey(Instant publishedAt, UUID postId) { }

    /** A PUBLISHED post. DRAFT and HIDDEN are absent, not forbidden: readers have no claim on them. */
    Optional<Post> publishedPost(UUID postId);

    /**
     * Locks a published source until its caller's transaction finishes. FOR SHARE, not KEY SHARE:
     * withdrawal changes status without changing the primary key and must conflict with this lock.
     */
    boolean lockPublishedPost(UUID postId);

    /** Which of these posts this owner has saved. */
    Set<UUID> savedPostIds(UUID ownerId, List<UUID> postIds);

    /** Inserts the relation if it is absent; returns the state either way, never moving savedAt. */
    SavedPostState save(UUID ownerId, UUID postId, Instant now);

    /** Removes the relation. Absent is success: unsave is idempotent by the contract's own 204. */
    void unsave(UUID ownerId, UUID postId);

    /** Whether a post id is already taken, so a curation run can leave it alone (A-031). */
    boolean postExists(UUID postId);

    /**
     * Stores a 1st-party cover image against the licence V021 seeded, and returns its id.
     *
     * <p>The licence is looked up, never created: a run that made its own would be publishing under
     * a grant nobody reviewed, which is the thing A-024 and the asset_licenses chain exist to stop.
     */
    UUID insertFirstPartyCover(UUID assetId, String url, String alt, String checksum, Instant now);

    /** Writes the post as a DRAFT. It is not visible until {@link #publishPost} runs. */
    /**
     * The media asset behind a cover a visitor uploaded (V045's USER_UPLOAD source).
     *
     * <p>Separate from {@link #insertFirstPartyCover} because the licence is a different row and the
     * difference is the point: A-024 was amended (2026-09-20) to allow uploaded covers only under a
     * source that does not claim the work is ours. Pointing an uploaded photograph at the
     * first-party licence would record that the team made it and that redistribution was ours to
     * allow.
     *
     * @param sourceExternalId the upload intent's id - where these bytes reached us, which is what
     *        makes media_assets_license_source_checksum_unique hold without anything else
     */
    UUID insertUserUploadCover(UUID assetId, String url, String sourceExternalId, String alt,
            String checksum, Instant now);

    /**
     * A draft post with an author, which a curated post does not have.
     *
     * <p>The author is an owner id derived from the session, never a value the request carried
     * (invariant 11). It may be an anonymous owner: A-058 allows an anonymous session to author.
     */
    void insertAuthoredDraftPost(UUID postId, UUID authorOwnerId, String title, String body,
            String coverUrl, UUID coverAssetId, Instant now);

    void insertDraftPost(UUID postId, String title, String body, String coverUrl, UUID coverAssetId,
            Instant now);

    /** Links one place to a post at a position; position 0 is the primary place. */
    void linkPostPlace(UUID postId, UUID placeId, int position, String mentionType);

    /**
     * Publishes a draft.
     *
     * <p>Separate from the insert because V022's trigger requires the primary place to exist first:
     * a post is written, given its place, and only then made visible.
     */
    void publishPost(UUID postId, Instant publishedAt, Instant now);

    /**
     * Takes the post back if it is PUBLISHED: HIDDEN, with {@code published_at} cleared, which V015's
     * shape CHECK requires of every row that is not PUBLISHED. Touches no other status.
     *
     * @return the rows changed - 1 when this call withdrew the post, 0 when it was not PUBLISHED
     *         by the time the statement held its row
     */
    int withdrawIfPublished(UUID postId, Instant now);

    /** The post's status, or empty when no post has that id. */
    Optional<io.nullnull.social.domain.PostStatus> postStatus(UUID postId);

    /**
     * Records one feed interaction, or converges on the one already recorded for that minute.
     *
     * @param occurredMinute whole minutes since the epoch for {@code occurredAt}; the dedup key
     * @return true when this call created the row, false when the minute already had one
     */
    boolean recordFeedback(UUID id, UUID ownerId, UUID postId, io.nullnull.social.domain.FeedFeedbackAction action,
            Instant occurredAt, long occurredMinute, Instant receivedAt);

    /**
     * Which of these places the trip already holds, split by whether they are scheduled.
     * An empty map is the honest answer when no trip was selected.
     */
    Map<UUID, Boolean> tripPlaceStates(UUID ownerId, UUID tripId, List<UUID> placeIds);
}
