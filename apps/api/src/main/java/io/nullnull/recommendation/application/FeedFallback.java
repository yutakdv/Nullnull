package io.nullnull.recommendation.application;

import io.nullnull.recommendation.domain.feed.FeedCandidateIn;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** §5.1 fixed order computed in Spring when apps/ai is unavailable. */
public final class FeedFallback {

    private FeedFallback() {
    }

    /**
     * Same filters and tie-break as the service (PublishedFilter, CanonicalPlaceFilter, FixedOrderScorer at
     * microsecond precision, postId compared as its canonical string — {@code UUID.compareTo} is signed and
     * would diverge from the service for non-v7 ids).
     */
    public static List<UUID> order(List<FeedCandidateIn> candidates, Instant evaluatedAt, int cap) {
        return candidates.stream()
                .filter(c -> c.status() == FeedCandidateIn.PostStatus.PUBLISHED && c.publishedAt() != null
                        && !c.publishedAt().isAfter(evaluatedAt) && c.primaryPlaceId() != null)
                .sorted(Comparator.comparing(FeedCandidateIn::publishedAt).reversed()
                        .thenComparing(c -> c.postId().toString()))
                .limit(cap)
                .map(FeedCandidateIn::postId)
                .toList();
    }
}
