package io.nullnull.social.application;

import io.nullnull.catalog.application.CatalogPlaceProjectionService;
import io.nullnull.catalog.application.CatalogPlaceQuery.CatalogPlaceSummary;
import io.nullnull.identity.application.OwnerContext;
import io.nullnull.shared.cursor.CursorClaims;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import io.nullnull.social.domain.CandidateState;
import io.nullnull.social.domain.FeedOrdering;
import io.nullnull.social.domain.Post;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * listFeed, getPost, savePost and unsavePost.
 *
 * <p>The feed's ORDER is the same for everyone - {@link FeedOrdering}, publishedAt DESC then id ASC.
 * Owner state (saved, candidate) is hydrated onto a page that has already been chosen, never used
 * to choose it. The BA-032 boundary says so outright: tripId changes what a card displays and not
 * where it sits.
 */
@Service
public class FeedService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;
    private static final String CONTEXT = "listFeed";

    private final FeedStore feed;
    private final FeedCursorProperties cursors;
    private final CatalogPlaceProjectionService places;
    private final io.nullnull.trip.application.CandidateService candidates;
    private final Clock clock;

    public FeedService(FeedStore feed, FeedCursorProperties cursors,
            CatalogPlaceProjectionService places,
            io.nullnull.trip.application.CandidateService candidates, Clock clock) {
        this.feed = feed;
        this.cursors = cursors;
        this.places = places;
        this.candidates = candidates;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public FeedPageView list(OwnerContext context, String cursor, Integer limit, UUID tripId) {
        int size = pageSize(limit);
        String binding = cursors.ownerBinding(context.ownerId());
        long offset = 0;
        if (cursor != null && !cursor.isBlank()) {
            CursorClaims claims = cursors.cursorCodec().decode(cursor, clock.instant(), binding, CONTEXT);
            offset = claims.nextOrdinal();
        }
        List<Post> found = feed.publishedPage(offset, size + 1);
        boolean hasMore = found.size() > size;
        List<Post> page = hasMore ? found.subList(0, size) : found;

        // Both hydrations are one statement for the whole page. A per-card lookup is where a shared
        // cache becomes tempting, and a shared cache is exactly how one owner's saved state leaks
        // into another's feed (BA-032-T2).
        List<UUID> postIds = page.stream().map(Post::id).toList();
        Set<UUID> savedPosts = feed.savedPostIds(context.ownerId(), postIds);
        List<UUID> placeIds = page.stream().map(Post::primaryPlaceId).filter(java.util.Objects::nonNull)
                .distinct().toList();
        Map<UUID, Boolean> inTrip = feed.tripPlaceStates(context.ownerId(), tripId, placeIds);
        // BA-034 landed, so SAVED_TO_SELECTED_TRIP is now observable rather than a value nothing
        // could produce.
        Map<UUID, io.nullnull.trip.domain.CandidateStatus> saved =
                candidateStates(context, tripId, placeIds);

        // Through the catalog's GATED projection, not its query port. The canonical catalog is
        // KTO-derived and stays fail-closed until BA-021-T3 records staging call evidence; reading
        // around that here would serve the same data under a different operation and make the
        // decision meaningless. While it is closed, listFeed answers 503 SOURCE_UNAVAILABLE - which
        // is correct, because FeedCard.primaryPlace is required and there is no place to put in it.
        Map<UUID, CatalogPlaceSummary> byId = new java.util.HashMap<>();
        for (CatalogPlaceSummary summary : places.embeddedSummaries(context, placeIds)) {
            byId.put(summary.id(), summary);
        }
        List<FeedCardView> cards = new ArrayList<>(page.size());
        for (Post post : page) {
            CatalogPlaceSummary place = byId.get(post.primaryPlaceId());
            if (place == null) {
                // A published post whose primary place is gone cannot produce a contract-valid card.
                // Dropping it silently would make the page size lie, so it is a data defect the
                // curator must fix rather than something to paper over here.
                throw new ApiException(ProblemCode.SOURCE_UNAVAILABLE,
                        "A published post references a place that is not available.");
            }
            cards.add(new FeedCardView(post, place, savedPosts.contains(post.id()),
                    candidateState(tripId, post.primaryPlaceId(), inTrip, saved)));
        }
        String next = hasMore
                ? cursors.cursorCodec().encode(new CursorClaims(CONTEXT, offset + size, binding, CONTEXT,
                        FeedOrdering.SORT_VERSION, clock.instant().plus(cursors.cursorTtl()),
                        cursors.keyId()))
                : null;
        return new FeedPageView(cards, next, hasMore);
    }

    /**
     * NO_TRIP_SELECTED when no trip was named, which is not the same as NOT_SAVED: the question has
     * no answer rather than the answer being no. A foreign tripId yields no rows and therefore
     * NOT_SAVED, which tells the caller nothing about a trip they do not own.
     */
    private static CandidateState candidateState(UUID tripId, UUID placeId, Map<UUID, Boolean> inTrip,
            Map<UUID, io.nullnull.trip.domain.CandidateStatus> saved) {
        if (tripId == null) {
            return CandidateState.NO_TRIP_SELECTED;
        }
        if (placeId == null) {
            return CandidateState.NOT_SAVED;
        }
        // Scheduled wins: a place on the itinerary is on it however it got there, and a candidate
        // row that still says ACTIVE beside a scheduled item would be the less current of the two.
        if (inTrip.containsKey(placeId)) {
            return CandidateState.SCHEDULED_IN_SELECTED_TRIP;
        }
        return switch (saved.get(placeId)) {
            case null -> CandidateState.NOT_SAVED;
            case DISMISSED -> CandidateState.NOT_SAVED;
            case SCHEDULED -> CandidateState.SCHEDULED_IN_SELECTED_TRIP;
            case ACTIVE -> CandidateState.SAVED_TO_SELECTED_TRIP;
        };
    }

    private Map<UUID, io.nullnull.trip.domain.CandidateStatus> candidateStates(OwnerContext context,
            UUID tripId, List<UUID> placeIds) {
        if (tripId == null || placeIds.isEmpty()) {
            return Map.of();
        }
        // The trip is confirmed to be this owner's before anything is read, so a foreign tripId
        // reveals nothing about it - the same rule the scheduled-place lookup follows.
        if (!candidates.ownsTrip(context.ownerId(), tripId)) {
            return Map.of();
        }
        return candidates.statesByPlace(tripId, placeIds);
    }

    @Transactional(readOnly = true)
    public PostDetailView post(OwnerContext context, UUID postId) {
        Post post = feed.publishedPost(postId).orElseThrow(FeedService::notFound);
        boolean saved = feed.savedPostIds(context.ownerId(), List.of(postId)).contains(postId);
        // Same gate as the feed: a post's linked places are the same KTO-derived catalog rows.
        return new PostDetailView(post, places.embeddedSummaries(context, post.placeIds()), saved);
    }

    /**
     * savePost. 201 the first time, 200 on a repeat, and the SAME savedAt either way - saving again
     * must not move the timestamp, or a no-op would reorder the owner's saved list.
     *
     * <p>Saving a post touches no trip: not its candidates, not its items, not its version
     * (invariant 2 and BA-032-T3). Nothing in this method can reach a trip table.
     */
    @Transactional
    public SavedPostState save(OwnerContext context, UUID postId) {
        feed.publishedPost(postId).orElseThrow(FeedService::notFound);
        return feed.save(context.ownerId(), postId, clock.instant());
    }

    /** unsavePost. Absent is success: the contract answers 204 for "removed or already absent". */
    @Transactional
    public void unsave(OwnerContext context, UUID postId) {
        feed.unsave(context.ownerId(), postId);
    }

    private static int pageSize(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ApiException(ProblemCode.INVALID_REQUEST,
                    "limit must be between 1 and " + MAX_LIMIT + ".");
        }
        return limit;
    }

    private static ApiException notFound() {
        return new ApiException(ProblemCode.NOT_FOUND, "The requested post does not exist.");
    }

    /** One feed card: the post, whether this owner saved it, and what their trip knows about it. */
    public record FeedCardView(Post post, CatalogPlaceSummary primaryPlace, boolean saved,
            CandidateState candidateState) { }

    public record FeedPageView(List<FeedCardView> items, String nextCursor, boolean hasMore) {
        public FeedPageView {
            items = List.copyOf(items);
            if (!hasMore && nextCursor != null) {
                throw new IllegalArgumentException("a last page must not carry a next cursor");
            }
        }
    }

    public record PostDetailView(Post post, List<CatalogPlaceSummary> places, boolean saved) { }
}
