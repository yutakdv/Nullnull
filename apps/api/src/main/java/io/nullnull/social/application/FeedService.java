package io.nullnull.social.application;

import io.nullnull.catalog.application.CatalogPlaceProjectionService;
import io.nullnull.catalog.application.CatalogPlaceQuery;
import io.nullnull.catalog.application.CatalogPlaceQuery.CatalogMediaAsset;
import io.nullnull.catalog.application.CatalogPlaceQuery.CatalogPlaceSummary;
import io.nullnull.identity.application.OwnerContext;
import io.nullnull.shared.cursor.CursorClaims;
import io.nullnull.shared.cursor.CursorException;
import io.nullnull.shared.cursor.CursorSortKey;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.ids.UuidV7;
import io.nullnull.shared.problem.ProblemCode;
import io.nullnull.social.domain.CandidateState;
import io.nullnull.social.domain.FeedFeedbackAction;
import io.nullnull.social.domain.FeedOrdering;
import io.nullnull.social.domain.Post;
import io.nullnull.trip.domain.TripValidationException;
import java.time.Clock;
import java.time.Instant;
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
    private final CatalogPlaceQuery catalog;
    private final io.nullnull.trip.application.CandidateService candidates;
    private final Clock clock;

    public FeedService(FeedStore feed, FeedCursorProperties cursors,
            CatalogPlaceProjectionService places, CatalogPlaceQuery catalog,
            io.nullnull.trip.application.CandidateService candidates, Clock clock) {
        this.feed = feed;
        this.cursors = cursors;
        this.places = places;
        this.catalog = catalog;
        this.candidates = candidates;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public FeedPageView list(OwnerContext context, String cursor, Integer limit, UUID tripId) {
        int size = pageSize(limit);
        String binding = cursors.ownerBinding(context.ownerId());
        FeedStore.PageKey after = null;
        if (cursor != null && !cursor.isBlank()) {
            CursorClaims claims = cursors.cursorCodec().decode(cursor, clock.instant(), binding, CONTEXT);
            if (claims.sortVersion() != FeedOrdering.SORT_VERSION) {
                // A key minted under another order names a row this order would resume elsewhere.
                throw new CursorException(ProblemCode.CURSOR_INVALID);
            }
            CursorSortKey key = CursorSortKey.decode(claims.sortKey());
            after = new FeedStore.PageKey(key.instantValue(), key.id());
        }
        List<Post> found = feed.publishedPage(after, size + 1);
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
        // The last post of THIS page, not a count of what came before it: that row is the reader's
        // place and stays their place whatever is published or hidden ahead of it. Read inside the
        // branch because an empty page has no last row, and hasMore cannot be true when it is empty.
        String next = hasMore ? nextCursor(page.get(page.size() - 1), binding) : null;
        return new FeedPageView(cards, next, hasMore);
    }

    private String nextCursor(Post last, String binding) {
        return cursors.cursorCodec().encode(new CursorClaims(CONTEXT,
                CursorSortKey.of(last.publishedAt(), last.id()).encode(), binding, CONTEXT,
                FeedOrdering.SORT_VERSION, clock.instant().plus(cursors.cursorTtl()), cursors.keyId()));
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
        return new PostDetailView(post, places.embeddedSummaries(context, post.placeIds()), saved,
                coverAsset(post));
    }

    /**
     * The licence behind the cover, read through catalog because media assets are its rows.
     *
     * <p>Not behind {@link CatalogPlaceProjectionService}'s publication gate, and that is the
     * distinction rather than an omission: the gate governs the C3 projection of provider-derived
     * place data, while a cover is a 1st-party asset the team made (A-024). Gating ours on their
     * approval would say something untrue about where it came from.
     *
     * <p>A post that names an asset the catalog cannot serve fails the read instead of projecting
     * null. Null is the honest answer for a post published before V021, which names no asset at all;
     * using it for a named-but-unservable one would hide exactly the defect
     * {@code posts_published_cover_asset_check} was added to make impossible, and the reader would
     * still be shown the image through {@code coverUrl}.
     */
    private CatalogMediaAsset coverAsset(Post post) {
        if (post.coverAssetId() == null) {
            return null;
        }
        return catalog.mediaAsset(post.coverAssetId())
                .orElseThrow(() -> new ApiException(ProblemCode.SOURCE_UNAVAILABLE,
                        "A published post references a cover asset that is not available."));
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

    /**
     * recordFeedFeedback. Two of the five actions are recorded and three are refused.
     *
     * <p>The refusal is the contract's own vocabulary meeting a release decision (#163): HIDE, LIKE
     * and DISLIKE have no projection to read them back from, no undo and, for HIDE, no recovery
     * entry point, all of which PM-011 is still open on. Accepting and dropping them would be worse
     * than refusing - the client would believe a state exists that nothing can show.
     *
     * <p>There is no idempotency record behind this operation, deliberately. The natural key is
     * stronger for this shape: a retry of the same event carries the same occurredAt, so it lands in
     * the same minute bucket and converges whatever key it was sent with, while a genuinely later
     * impression is a different bucket and must be its own row. An idempotency record would add a
     * row and a lock per impression to answer a question the unique index already answers.
     *
     * <p>The post is checked first, so feedback cannot be recorded about something the reader could
     * not have seen - including a DRAFT, which publishedPost does not return.
     */
    @Transactional
    public void recordFeedback(OwnerContext context, UUID postId, FeedFeedbackAction action,
            Instant occurredAt) {
        if (!action.recordedInP0()) {
            throw new TripValidationException("action", "Unsupported",
                    action + " feedback is not recorded in this release");
        }
        feed.publishedPost(postId).orElseThrow(FeedService::notFound);
        Instant now = clock.instant();
        feed.recordFeedback(UuidV7.create(clock), context.ownerId(), postId, action, occurredAt,
                minuteBucket(occurredAt), now);
    }

    /**
     * Whole minutes since the epoch, floored towards the past for instants before 1970 as well.
     *
     * <p>{@code Math.floorDiv} rather than {@code /}: integer division truncates towards zero, so a
     * negative epoch second would round the bucket the wrong way and put two events either side of
     * the epoch in one. No device sends such a timestamp today, which is exactly why the version
     * that only works for positive values would never be noticed.
     */
    static long minuteBucket(Instant occurredAt) {
        return Math.floorDiv(occurredAt.getEpochSecond(), 60L);
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

    public record PostDetailView(Post post, List<CatalogPlaceSummary> places, boolean saved,
            CatalogMediaAsset coverAsset) { }
}
