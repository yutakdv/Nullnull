package io.nullnull.social.api;

import io.nullnull.catalog.api.PlaceController.MediaAssetResponse;
import io.nullnull.catalog.api.PlaceController.PlaceSummaryResponse;
import io.nullnull.identity.application.OwnerContext;
import io.nullnull.shared.http.NullnullOperation;
import io.nullnull.shared.http.NullnullOperation.Security;
import io.nullnull.social.application.FeedService;
import io.nullnull.social.application.FeedService.FeedCardView;
import io.nullnull.social.application.FeedService.FeedPageView;
import io.nullnull.social.application.FeedService.PostDetailView;
import io.nullnull.social.application.SavedPostState;
import io.nullnull.social.domain.FeedFeedbackAction;
import io.nullnull.trip.domain.TripValidationException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * listFeed, getPost, savePost and unsavePost.
 *
 * <p>The owner comes from {@link OwnerContext}; {@code tripId} is the only identifier a caller
 * supplies, and it changes what a card DISPLAYS rather than which cards there are or in what order.
 */
@RestController
public class FeedController {

    private final FeedService feed;

    public FeedController(FeedService feed) {
        this.feed = feed;
    }

    @GetMapping(value = "/feed", produces = MediaType.APPLICATION_JSON_VALUE)
    @NullnullOperation(id = "listFeed", security = Security.SESSION)
    public ResponseEntity<FeedPageResponse> list(OwnerContext owner,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) UUID tripId) {
        FeedPageView page = feed.list(owner, cursor, limit, tripId);
        return ResponseEntity.ok()
                // The ORDER is shared but savedPost and candidateState are this owner's, so the
                // response as a whole is owner-scoped and must never be cached by anything shared.
                .header("Cache-Control", "private, no-store")
                .body(new FeedPageResponse(page.items().stream().map(FeedCardResponse::from).toList(),
                        new CursorPageResponse(page.nextCursor(), page.hasMore())));
    }

    @GetMapping(value = "/posts/{postId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @NullnullOperation(id = "getPost", security = Security.SESSION)
    public ResponseEntity<PostDetailResponse> post(OwnerContext owner, @PathVariable UUID postId) {
        return ResponseEntity.ok()
                .header("Cache-Control", "private, no-store")
                .body(PostDetailResponse.from(feed.post(owner, postId)));
    }

    @org.springframework.web.bind.annotation.PutMapping(value = "/posts/{postId}/saved", produces = MediaType.APPLICATION_JSON_VALUE)
    @NullnullOperation(id = "savePost", security = {Security.SESSION, Security.CSRF})
    public ResponseEntity<SavedPostStateResponse> save(OwnerContext owner, @PathVariable UUID postId) {
        SavedPostState state = feed.save(owner, postId);
        // 201 the first time, 200 on a repeat - and the same savedAt in both, so saving again does
        // not move the post in the owner's saved list.
        return ResponseEntity.status(state.duplicate() ? 200 : 201)
                .header("Cache-Control", "private, no-store")
                .body(SavedPostStateResponse.from(state));
    }

    @DeleteMapping("/posts/{postId}/saved")
    @NullnullOperation(id = "unsavePost", security = {Security.SESSION, Security.CSRF})
    public ResponseEntity<Void> unsave(OwnerContext owner, @PathVariable UUID postId) {
        feed.unsave(owner, postId);
        // 204 for "removed or already absent": unsaving something that is not saved is not an error,
        // and reporting one would make a double tap look like a failure.
        return ResponseEntity.noContent().header("Cache-Control", "private, no-store").build();
    }

    public record FeedPageResponse(List<FeedCardResponse> items, CursorPageResponse page) { }

    public record CursorPageResponse(String nextCursor, boolean hasMore) { }

    public record FeedCardResponse(PostSummaryResponse post, PlaceSummaryResponse primaryPlace,
            Object crowd, boolean savedPost, String candidateState) {

        static FeedCardResponse from(FeedCardView view) {
            // crowd stays null: a CrowdMetric needs a full DataProvenance and inventing one is the
            // fabricated evidence invariant 8 exists to prevent. BA-023 fills it from real snapshots.
            return new FeedCardResponse(PostSummaryResponse.from(view),
                    PlaceSummaryResponse.from(view.primaryPlace()), null, view.saved(),
                    view.candidateState().name());
        }
    }

    public record PostSummaryResponse(UUID id, String title, String excerpt, String coverUrl,
            Instant publishedAt) {

        static PostSummaryResponse from(FeedCardView view) {
            return new PostSummaryResponse(view.post().id(), view.post().title(), view.post().excerpt(),
                    view.post().coverUrl(), view.post().publishedAt());
        }
    }

    public record PostDetailResponse(UUID id, String title, String excerpt, String coverUrl,
            MediaAssetResponse coverAsset, Instant publishedAt, String body,
            List<PlaceSummaryResponse> places, boolean saved) {

        static PostDetailResponse from(PostDetailView view) {
            // coverUrl is the rendered image; coverAsset is the right to render it. A-024 made the
            // cover a 1st-party asset and V021 made naming one a condition of publishing, so a post
            // published since then always has a licence behind it - this projects it. Null here now
            // means one thing only: a post published before V021, whose CHECK is NOT VALID and whose
            // free-text cover was never given an asset. An asset that exists but cannot be served
            // fails the read in FeedService instead of arriving here as a null.
            return new PostDetailResponse(view.post().id(), view.post().title(), view.post().excerpt(),
                    view.post().coverUrl(), MediaAssetResponse.from(view.coverAsset()),
                    view.post().publishedAt(), view.post().body(),
                    view.places().stream().map(PlaceSummaryResponse::from).toList(), view.saved());
        }
    }

    @PostMapping(value = "/feed/feedback", consumes = MediaType.APPLICATION_JSON_VALUE)
    @NullnullOperation(id = "recordFeedFeedback", security = {Security.SESSION, Security.CSRF})
    public ResponseEntity<Void> recordFeedback(OwnerContext owner,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody FeedFeedbackBody body) {
        // The header is required by the contract and required here, so a client that omits it is
        // told rather than silently treated as sending a one-off. It is not looked up: convergence
        // comes from the minute key, which FeedService.recordFeedback explains.
        if (body == null) {
            throw new TripValidationException("action", "NotNull", "a request body is required");
        }
        feed.recordFeedback(owner, body.postId(), action(body.action()), occurredAt(body.occurredAt()));
        return ResponseEntity.noContent().header("Cache-Control", "private, no-store").build();
    }

    private static FeedFeedbackAction action(String value) {
        try {
            return FeedFeedbackAction.of(value);
        } catch (IllegalArgumentException unknown) {
            throw new TripValidationException("action", "Unsupported",
                    "action must be one of the published feed interactions");
        }
    }

    private static Instant occurredAt(String value) {
        if (value == null) {
            throw new TripValidationException("occurredAt", "NotNull", "occurredAt is required");
        }
        try {
            return java.time.OffsetDateTime.parse(value).toInstant();
        } catch (java.time.format.DateTimeParseException malformed) {
            throw new TripValidationException("occurredAt", "Format",
                    "occurredAt must be an RFC 3339 date-time");
        }
    }

    /** The three fields FeedFeedbackRequest declares. The owner is never one of them. */
    public record FeedFeedbackBody(UUID postId, String action, String occurredAt) {
    }

    public record SavedPostStateResponse(UUID postId, boolean saved, boolean duplicate, Instant savedAt) {
        static SavedPostStateResponse from(SavedPostState state) {
            return new SavedPostStateResponse(state.postId(), state.saved(), state.duplicate(),
                    state.savedAt());
        }
    }
}
