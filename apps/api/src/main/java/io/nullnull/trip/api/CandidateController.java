package io.nullnull.trip.api;

import io.nullnull.catalog.api.PlaceController.PlaceSummaryResponse;
import io.nullnull.catalog.application.CatalogPlaceProjectionService;
import io.nullnull.catalog.application.CatalogPlaceQuery.CatalogPlaceSummary;
import io.nullnull.identity.application.OwnerContext;
import io.nullnull.shared.http.NullnullOperation;
import io.nullnull.shared.http.NullnullOperation.Security;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import io.nullnull.trip.application.CandidateService;
import io.nullnull.trip.application.CandidateService.CandidatePageView;
import io.nullnull.trip.application.CandidateService.SaveResult;
import io.nullnull.trip.domain.CandidateSourceType;
import io.nullnull.trip.domain.TripCandidate;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

/** listTripCandidates, addTripCandidate and removeTripCandidate. */
@RestController
public class CandidateController {

    private final CandidateService candidates;
    private final CatalogPlaceProjectionService places;

    public CandidateController(CandidateService candidates, CatalogPlaceProjectionService places) {
        this.candidates = candidates;
        this.places = places;
    }

    @GetMapping(value = "/trips/{tripId}/candidates", produces = MediaType.APPLICATION_JSON_VALUE)
    @NullnullOperation(id = "listTripCandidates", security = Security.SESSION)
    public ResponseEntity<CandidatePageResponse> list(OwnerContext owner, @PathVariable UUID tripId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        CandidatePageView page = candidates.list(owner, tripId, status, cursor, limit);
        return ResponseEntity.ok()
                .header("Cache-Control", "private, no-store")
                .body(new CandidatePageResponse(project(owner, page.items()),
                        new CursorPageResponse(page.nextCursor(), page.hasMore())));
    }

    @PostMapping(value = "/trips/{tripId}/candidates", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @NullnullOperation(id = "addTripCandidate", security = {Security.SESSION, Security.CSRF})
    public ResponseEntity<CandidateSaveResultResponse> add(OwnerContext owner,
            @PathVariable UUID tripId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody AddCandidateBody body) {
        if (body == null || body.source() == null || body.source().type() == null) {
            throw new io.nullnull.trip.domain.TripValidationException("source.type", "NotNull",
                    "source type is required");
        }
        SaveResult result = candidates.add(owner, tripId, idempotencyKey, body.placeId(),
                CandidateSourceType.of(body.source().type()), body.source().postId(), body.note());
        // 200 when the trip already held a non-dismissed candidate for that place, 201 when it did
        // not. tripScheduleChanged is const false in the schema: this operation cannot change a
        // schedule, and saying so in the body is what makes invariant 2 checkable by the client.
        return ResponseEntity.status(result.duplicate() ? 200 : 201)
                .header("Cache-Control", "private, no-store")
                .body(new CandidateSaveResultResponse(
                        project(owner, List.of(result.candidate())).get(0), result.duplicate(), false));
    }

    @DeleteMapping("/trips/{tripId}/candidates/{candidateId}")
    @NullnullOperation(id = "removeTripCandidate", security = {Security.SESSION, Security.CSRF})
    public ResponseEntity<Void> remove(OwnerContext owner, @PathVariable UUID tripId,
            @PathVariable UUID candidateId) {
        candidates.dismiss(owner, tripId, candidateId);
        return ResponseEntity.noContent().header("Cache-Control", "private, no-store").build();
    }

    /** Places come through the catalog's gated projection, the same as every other embedding. */
    private List<TripCandidateResponse> project(OwnerContext owner, List<TripCandidate> items) {
        if (items.isEmpty()) {
            return List.of();
        }
        Map<UUID, CatalogPlaceSummary> byId = new HashMap<>();
        for (CatalogPlaceSummary summary : places.embeddedSummaries(owner,
                items.stream().map(TripCandidate::placeId).distinct().toList())) {
            byId.put(summary.id(), summary);
        }
        return items.stream().map(candidate -> TripCandidateResponse.from(candidate,
                byId.get(candidate.placeId()))).toList();
    }

    public record AddCandidateBody(UUID placeId, AddCandidateSourceBody source, String note) { }

    public record AddCandidateSourceBody(String type, UUID postId) { }

    public record CandidatePageResponse(List<TripCandidateResponse> items, CursorPageResponse page) { }

    public record CursorPageResponse(String nextCursor, boolean hasMore) { }

    public record CandidateSaveResultResponse(TripCandidateResponse candidate, boolean duplicate,
            boolean tripScheduleChanged) { }

    public record TripCandidateResponse(UUID id, UUID tripId, PlaceSummaryResponse place, String status,
            UUID scheduledTripItemId, String note, List<CandidateSourceResponse> sources,
            Instant createdAt) {

        static TripCandidateResponse from(TripCandidate candidate, CatalogPlaceSummary place) {
            if (place == null) {
                // TripCandidate.required includes `place`, so emitting null here would answer a
                // schema-invalid body - and the client would see a candidate with no place rather
                // than an error it can act on. FeedService already refuses the same situation for
                // the same reason. The case is unreachable today (V010's trigger keeps a DEPRECATED
                // row pointing at an ACTIVE canonical one, and the summary query resolves through
                // COALESCE before filtering), which is exactly why the branch must fail loudly
                // instead of quietly: nothing else would tell us the invariant broke.
                throw new ApiException(ProblemCode.SOURCE_UNAVAILABLE,
                        "A saved candidate references a place that is not available.");
            }
            return new TripCandidateResponse(candidate.id(), candidate.tripId(),
                    PlaceSummaryResponse.from(place), candidate.status().name(),
                    candidate.scheduledTripItemId(), candidate.note(),
                    candidate.sources().stream().map(CandidateSourceResponse::from).toList(),
                    candidate.createdAt());
        }
    }

    public record CandidateSourceResponse(String type, UUID postId, Instant createdAt) {
        static CandidateSourceResponse from(TripCandidate.CandidateSource source) {
            return new CandidateSourceResponse(source.type().name(), source.postId(),
                    source.createdAt());
        }
    }
}
