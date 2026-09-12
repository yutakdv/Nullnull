package io.nullnull.social.api;

import io.nullnull.catalog.api.PlaceController.PlaceSummaryResponse;
import io.nullnull.identity.application.OwnerContext;
import io.nullnull.shared.http.NullnullOperation;
import io.nullnull.shared.http.NullnullOperation.Security;
import io.nullnull.social.application.FeedService;
import io.nullnull.social.application.FeedService.FeedCardView;
import io.nullnull.social.application.FeedService.FeedPageView;
import io.nullnull.social.application.FeedService.PostDetailView;
import io.nullnull.social.application.SavedPostState;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
            Object coverAsset, Instant publishedAt, String body, List<PlaceSummaryResponse> places,
            boolean saved) {

        static PostDetailResponse from(PostDetailView view) {
            // coverAsset is null until a reviewed media licence exists for the cover; coverUrl alone
            // carries no redistribution right, which is the gap PM-010 tracks.
            return new PostDetailResponse(view.post().id(), view.post().title(), view.post().excerpt(),
                    view.post().coverUrl(), null, view.post().publishedAt(), view.post().body(),
                    view.places().stream().map(PlaceSummaryResponse::from).toList(), view.saved());
        }
    }

    public record SavedPostStateResponse(UUID postId, boolean saved, boolean duplicate, Instant savedAt) {
        static SavedPostStateResponse from(SavedPostState state) {
            return new SavedPostStateResponse(state.postId(), state.saved(), state.duplicate(),
                    state.savedAt());
        }
    }
}
