package io.nullnull.live.api;

import io.nullnull.catalog.api.PlaceController.PlaceDetailResponse;
import io.nullnull.catalog.api.PlaceController.PlaceSummaryResponse;
import io.nullnull.catalog.api.PlaceController.RelatedPlaceResultResponse;
import io.nullnull.crowd.application.CrowdProvenanceProjection.CrowdMetric;
import io.nullnull.identity.application.OwnerContext;
import io.nullnull.live.application.LiveAreaProjection.LiveAreaResultResponse;
import io.nullnull.live.application.LiveAreaQueryService;
import io.nullnull.live.application.LivePlaceQueryService;
import io.nullnull.live.application.LivePlaceQueryService.LivePlaceDetailRow;
import io.nullnull.live.application.LivePlaceQueryService.LivePlaceRow;
import io.nullnull.shared.http.NullnullOperation;
import io.nullnull.shared.http.NullnullOperation.Security;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The Live tab's three operations: queryLiveAreas, listLiveAreaPlaces and getLivePlace. */
@RestController
public class LiveController {

    private final LiveAreaQueryService areas;
    private final LivePlaceQueryService places;

    public LiveController(LiveAreaQueryService areas, LivePlaceQueryService places) {
        this.areas = areas;
        this.places = places;
    }

    /**
     * A read-only POST for the same reason {@code searchPlaces} and {@code queryPlaceCrowdForecasts}
     * are: it changes nothing, so it carries neither CSRF token nor Idempotency-Key, and the coarse
     * viewport stays out of the access log's URL. {@code Cache-Control: private, no-store} comes from
     * {@code SessionHttpConfiguration} like every session operation's.
     *
     * <p>The body is taken as BigDecimal and never as double: the contract's coordinates are decimal
     * to three places and {@code CoarseViewport} refuses a fourth, which is a question binary
     * floating point cannot be asked.
     */
    @PostMapping(value = "/live/areas", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @NullnullOperation(id = "queryLiveAreas", security = Security.SESSION)
    public LiveAreaResultResponse query(@RequestBody(required = false) LiveAreaQueryBody body) {
        ViewportBody viewport = body == null ? null : body.viewport();
        return areas.query(body == null ? null : body.mode(), body == null ? null : body.regionCode(),
                viewport == null ? null : viewport.west(), viewport == null ? null : viewport.south(),
                viewport == null ? null : viewport.east(), viewport == null ? null : viewport.north());
    }

    /**
     * The places one area covers.
     *
     * <p><strong>A bare array, and that is the clause BA-091-T5 turns into a trap.</strong> The
     * contract gives this operation no {@code cursor} or {@code limit} parameter and no page
     * envelope, so no Live route issues a cursor - the owner-binding rules that
     * {@code BA-022-T2} and {@code BA-070-T1} own have nothing to bind here. That is a fact about
     * today, not a principle: an area holds as many places as review has mapped into it, which is a
     * handful, and if that ever stops being true the assertion on this shape goes red and whoever
     * adds paging is told, at that moment, that a cursor needs an owner.
     */
    @GetMapping(value = "/live/areas/{areaId}/places", produces = MediaType.APPLICATION_JSON_VALUE)
    @NullnullOperation(id = "listLiveAreaPlaces", security = Security.SESSION)
    public List<LivePlaceResponse> placesInArea(OwnerContext owner, @PathVariable UUID areaId) {
        return places.placesInArea(owner, areaId).stream().map(LivePlaceResponse::from).toList();
    }

    @GetMapping(value = "/live/places/{placeId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @NullnullOperation(id = "getLivePlace", security = Security.SESSION)
    public LivePlaceDetailResponse place(OwnerContext owner, @PathVariable UUID placeId) {
        return LivePlaceDetailResponse.from(places.place(owner, placeId));
    }

    public record LiveAreaQueryBody(String mode, String regionCode, ViewportBody viewport) { }

    public record ViewportBody(BigDecimal west, BigDecimal south, BigDecimal east, BigDecimal north) { }

    /**
     * {@code crowd} is null for an uncovered place and the field is sent rather than omitted, for
     * the reason {@code RelatedPlaceResponse.crowd} is: a present null says "no reading" out loud
     * where an absent field leaves a reader guessing whether it was forgotten. Zero would be a
     * reading nobody took.
     */
    public record LivePlaceResponse(PlaceSummaryResponse place, String mappingType, boolean fallbackUsed,
            CrowdMetric crowd) {
        static LivePlaceResponse from(LivePlaceRow row) {
            return new LivePlaceResponse(PlaceSummaryResponse.from(row.place()), row.mappingType(),
                    row.fallbackUsed(), row.crowd());
        }
    }

    public record LivePlaceDetailResponse(PlaceDetailResponse place, String dataState, CrowdMetric crowd,
            RelatedPlaceResultResponse related) {
        static LivePlaceDetailResponse from(LivePlaceDetailRow row) {
            return new LivePlaceDetailResponse(PlaceDetailResponse.from(row.place()), row.dataState().name(),
                    row.crowd(), RelatedPlaceResultResponse.from(row.related()));
        }
    }
}
