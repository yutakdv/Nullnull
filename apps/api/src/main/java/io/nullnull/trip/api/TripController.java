package io.nullnull.trip.api;

import io.nullnull.identity.application.OwnerContext;
import io.nullnull.identity.application.OwnerPreferencesService;
import io.nullnull.shared.http.NullnullOperation;
import io.nullnull.shared.http.NullnullOperation.Security;
import io.nullnull.trip.application.CreateTripCommand;
import io.nullnull.trip.application.TripPageView;
import io.nullnull.trip.application.TripService;
import io.nullnull.trip.application.TripView;
import io.nullnull.trip.domain.Trip;
import io.nullnull.trip.domain.TripInterest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * listTrips, createTrip and getTrip.
 *
 * <p>The owner comes from {@link OwnerContext}, which the session filter derives from the cookie; no
 * route here takes an owner identifier, because one supplied by the caller must never be trusted
 * (invariant 11).
 *
 * <p>{@code days} is built here from the trip's date range rather than read from storage. That is
 * what makes a created trip's shape deterministic - one empty day per date, in order - and what
 * stops a stored day list from drifting out of step with the range after an edit.
 */
@RestController
public class TripController {

    private final TripService trips;
    private final OwnerPreferencesService owners;

    public TripController(TripService trips, OwnerPreferencesService owners) {
        this.trips = trips;
        this.owners = owners;
    }

    @GetMapping(value = "/trips", produces = MediaType.APPLICATION_JSON_VALUE)
    @NullnullOperation(id = "listTrips", security = Security.SESSION)
    public ResponseEntity<TripPageResponse> list(OwnerContext owner,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        TripPageView page = trips.list(owner, status, cursor, limit);
        return ResponseEntity.ok()
                // Owner-scoped, so never cacheable by anything between here and the browser.
                .header("Cache-Control", "private, no-store")
                .body(new TripPageResponse(page.items().stream().map(TripSummaryResponse::from).toList(),
                        new CursorPageResponse(page.nextCursor(), page.hasMore())));
    }

    @PostMapping(value = "/trips", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @NullnullOperation(id = "createTrip", security = {Security.SESSION, Security.CSRF})
    public ResponseEntity<TripDetailResponse> create(OwnerContext owner,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CreateTripBody body) {
        String locale = owners.get(owner).locale();
        CreateTripCommand command = CreateTripCommand.of(body.title(), body.startDate(), body.endDate(),
                body.timezone(), body.planningLevel(), interests(body.interests()), locale);
        TripView created = trips.create(owner, idempotencyKey, command);
        TripDetailResponse response = TripDetailResponse.from(created);
        return ResponseEntity.created(java.net.URI.create("/api/v1/trips/" + response.id()))
                .eTag(created.trip().entityTag())
                .header("Cache-Control", "private, no-store")
                .body(response);
    }

    @GetMapping(value = "/trips/{tripId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @NullnullOperation(id = "getTrip", security = Security.SESSION)
    public ResponseEntity<TripDetailResponse> get(OwnerContext owner, @PathVariable UUID tripId) {
        TripView view = trips.get(owner, tripId);
        return ResponseEntity.ok()
                // The quoted version. Every mutation sends it back as If-Match (invariant 6).
                .eTag(view.trip().entityTag())
                .header("Cache-Control", "private, no-store")
                .body(TripDetailResponse.from(view));
    }

    private static List<TripInterest> interests(List<TripInterestBody> submitted) {
        if (submitted == null) {
            return List.of();
        }
        return submitted.stream()
                .map(entry -> new TripInterest(entry == null ? null : entry.code(),
                        entry == null || entry.weight() == null ? 0 : entry.weight()))
                .toList();
    }

    /**
     * seedItems is absent from this body on purpose. Accepting it would mean deciding what
     * SeedTripItem.startTime means, and the three canon documents disagree: the contract's
     * format: time requires an offset, the ERD's column cannot store one, and the API README says
     * offset-less local time (#145). Unknown fields are rejected, so a caller that sends seedItems
     * gets a 400 rather than a trip that silently dropped it.
     */
    public record CreateTripBody(String title, LocalDate startDate, LocalDate endDate, String timezone,
            String planningLevel, List<TripInterestBody> interests) { }

    public record TripInterestBody(String code, Integer weight) { }

    public record TripPageResponse(List<TripSummaryResponse> items, CursorPageResponse page) { }

    public record CursorPageResponse(String nextCursor, boolean hasMore) { }

    public record TripSummaryResponse(UUID id, String title, LocalDate startDate, LocalDate endDate,
            String timezone, String status, long version, int candidateCount) {

        static TripSummaryResponse from(TripView view) {
            Trip trip = view.trip();
            return new TripSummaryResponse(trip.id(), trip.title(), trip.range().startDate(),
                    trip.range().endDate(), trip.range().timezone().getId(), trip.status().name(),
                    trip.version(), view.candidateCount());
        }
    }

    public record TripDetailResponse(UUID id, String title, LocalDate startDate, LocalDate endDate,
            String timezone, String status, long version, int candidateCount, String planningLevel,
            List<TripInterestResponse> interests, List<TripDayResponse> days,
            List<Object> candidates) {

        static TripDetailResponse from(TripView view) {
            Trip trip = view.trip();
            return new TripDetailResponse(trip.id(), trip.title(), trip.range().startDate(),
                    trip.range().endDate(), trip.range().timezone().getId(), trip.status().name(),
                    trip.version(), view.candidateCount(), trip.planningLevel().name(),
                    trip.interests().stream()
                            .map(i -> new TripInterestResponse(i.code(), i.weight())).toList(),
                    trip.days().stream().map(date -> new TripDayResponse(date, List.of())).toList(),
                    // Empty, and the contract says an empty array here does NOT mean the trip has no
                    // candidates: candidateCount is the number to display and listTripCandidates is
                    // the paginated source.
                    List.of());
        }
    }

    public record TripInterestResponse(String code, int weight) { }

    /** Items are always empty until BA-040 - and until PM-008 settles what a start time looks like. */
    public record TripDayResponse(LocalDate date, List<Object> items) { }
}
