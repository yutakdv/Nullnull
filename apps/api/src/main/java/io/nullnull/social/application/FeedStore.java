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

    /** One page of PUBLISHED posts in the fixed order, offset rows in. */
    List<Post> publishedPage(long offset, int limit);

    /** A PUBLISHED post. DRAFT and HIDDEN are absent, not forbidden: readers have no claim on them. */
    Optional<Post> publishedPost(UUID postId);

    /** Which of these posts this owner has saved. */
    Set<UUID> savedPostIds(UUID ownerId, List<UUID> postIds);

    /** Inserts the relation if it is absent; returns the state either way, never moving savedAt. */
    SavedPostState save(UUID ownerId, UUID postId, Instant now);

    /** Removes the relation. Absent is success: unsave is idempotent by the contract's own 204. */
    void unsave(UUID ownerId, UUID postId);

    /**
     * Which of these places the trip already holds, split by whether they are scheduled.
     * An empty map is the honest answer when no trip was selected.
     */
    Map<UUID, Boolean> tripPlaceStates(UUID ownerId, UUID tripId, List<UUID> placeIds);
}
