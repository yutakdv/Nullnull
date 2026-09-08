package io.nullnull.recommendation.domain.feed;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Mirrors {@code FeedCandidateIn}: curated post facts only, never owner-specific state. */
public record FeedCandidateIn(UUID postId, Instant publishedAt, PostStatus status, UUID primaryPlaceId) {

    public enum PostStatus { PUBLISHED, DRAFT, WITHDRAWN, DELETED }

    public FeedCandidateIn {
        Objects.requireNonNull(postId, "postId");
        Objects.requireNonNull(status, "status");
    }
}
