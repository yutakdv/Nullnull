package io.nullnull.recommendation.application;

import io.nullnull.recommendation.domain.feed.FeedCandidateIn;
import io.nullnull.social.domain.FeedOrdering;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** §5.1 fixed order computed in Spring when apps/ai is unavailable. */
public final class FeedFallback {

    private FeedFallback() {
    }

    /**
     * Same filters and tie-break as the service (PublishedFilter, CanonicalPlaceFilter, FixedOrderScorer at
     * microsecond precision). The order itself is {@link FeedOrdering#comparator()}, the one comparator the
     * feed endpoint and this fallback share, so a page served by either side is ordered identically.
     */
    public static List<UUID> order(List<FeedCandidateIn> candidates, Instant evaluatedAt, int cap) {
        return candidates.stream()
                .filter(c -> c.status() == FeedCandidateIn.PostStatus.PUBLISHED && c.publishedAt() != null
                        && !c.publishedAt().isAfter(evaluatedAt) && c.primaryPlaceId() != null)
                .map(c -> new FeedOrdering.FeedEntry(c.postId(), c.publishedAt()))
                .sorted(FeedOrdering.comparator())
                .limit(cap)
                .map(FeedOrdering.FeedEntry::postId)
                .toList();
    }
}
