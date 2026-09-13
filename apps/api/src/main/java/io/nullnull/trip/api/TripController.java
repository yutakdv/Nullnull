package io.nullnull.trip.api;

import io.nullnull.identity.application.OwnerContext;
import io.nullnull.identity.application.OwnerPreferencesService;
import io.nullnull.shared.http.NullnullOperation;
import io.nullnull.shared.http.NullnullOperation.Security;
import io.nullnull.trip.application.CreateTripCommand;
import io.nullnull.trip.application.TripPageView;
import io.nullnull.catalog.api.PlaceController.PlaceSummaryResponse;
import java.util.Map;
import io.nullnull.trip.application.TripItemView;
import io.nullnull.trip.domain.ItemLock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import io.nullnull.trip.application.TripService;
import io.nullnull.trip.application.TripView;
import io.nullnull.trip.domain.Trip;
import io.nullnull.trip.domain.TripConstraint;
import io.nullnull.trip.domain.TripInterest;
import io.nullnull.trip.domain.TripItem;
import java.time.LocalDate;
import java.time.LocalTime;
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
                body.timezone(), body.planningLevel(), interests(body.interests()),
                seedItems(body.seedItems()), locale);
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

    @org.springframework.web.bind.annotation.PatchMapping(value = "/trips/{tripId}",
            consumes = "application/merge-patch+json", produces = MediaType.APPLICATION_JSON_VALUE)
    @NullnullOperation(id = "updateTrip", security = {Security.SESSION, Security.CSRF})
    public ResponseEntity<TripDetailResponse> update(OwnerContext owner, @PathVariable UUID tripId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody java.util.Map<String, Object> patch) {
        TripView updated = trips.update(owner, tripId, ifMatch, UpdateTripBodies.from(patch));
        return ResponseEntity.ok()
                .eTag(updated.trip().entityTag())
                .header("Cache-Control", "private, no-store")
                .body(TripDetailResponse.from(updated));
    }

    @org.springframework.web.bind.annotation.PutMapping(value = "/trips/{tripId}/interests",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @NullnullOperation(id = "replaceTripInterests", security = {Security.SESSION, Security.CSRF})
    public ResponseEntity<TripDetailResponse> replaceInterests(OwnerContext owner,
            @PathVariable UUID tripId, @RequestHeader("If-Match") String ifMatch,
            @RequestBody ReplaceInterestsBody body) {
        TripView updated = trips.replaceInterests(owner, tripId, ifMatch,
                interests(body == null ? null : body.interests()));
        return ResponseEntity.ok()
                .eTag(updated.trip().entityTag())
                .header("Cache-Control", "private, no-store")
                .body(TripDetailResponse.from(updated));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/trips/{tripId}")
    @NullnullOperation(id = "deleteTrip", security = {Security.SESSION, Security.CSRF})
    public ResponseEntity<Void> delete(OwnerContext owner, @PathVariable UUID tripId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        trips.delete(owner, tripId, ifMatch, idempotencyKey);
        // 204 with no body and no ETag: there is no representation left to tag.
        return ResponseEntity.noContent().header("Cache-Control", "private, no-store").build();
    }

    private static List<TripItem> seedItems(List<SeedTripItemBody> submitted) {
        if (submitted == null) {
            return List.of();
        }
        List<TripItem> items = new java.util.ArrayList<>(submitted.size());
        for (SeedTripItemBody body : submitted) {
            if (body == null || body.placeId() == null || body.date() == null || body.position() == null) {
                throw new io.nullnull.trip.domain.TripValidationException("seedItems[]", "NotNull",
                        "placeId, date and position are required on a seeded item");
            }
            items.add(new TripItem(UUID.randomUUID(), body.placeId(), body.date(), body.position(),
                    body.startTime(), null, null, constraints(body.constraints())));
        }
        return List.copyOf(items);
    }

    private static List<TripConstraint> constraints(List<SetConstraintBody> submitted) {
        if (submitted == null) {
            return List.of();
        }
        List<TripConstraint> constraints = new java.util.ArrayList<>(submitted.size());
        for (SetConstraintBody body : submitted) {
            constraints.add(UpdateTripBodies.constraint(body));
        }
        return List.copyOf(constraints);
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

    public record CreateTripBody(String title, LocalDate startDate, LocalDate endDate, String timezone,
            String planningLevel, List<TripInterestBody> interests, List<SeedTripItemBody> seedItems) { }

    /** {@code SeedTripItem}. startTime is offset-less local time in the trip's timezone (#145). */
    public record SeedTripItemBody(UUID placeId, LocalDate date, Integer position, LocalTime startTime,
            List<SetConstraintBody> constraints) { }

    /**
     * {@code SetConstraintInput}, flattened: the four variants differ only by which fields they
     * carry, and the discriminator decides which. Jackson polymorphism is not used because the
     * contract's oneOf is closed and a wrong `type` must be a field error, not a parse failure.
     */
    public record SetConstraintBody(String type, Boolean locked, String source, LocalDate date,
            LocalTime startTime, LocalTime endTime, Integer toleranceMinutes) { }

    public record TripInterestBody(String code, Integer weight) { }

    public record ReplaceInterestsBody(List<TripInterestBody> interests) { }

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
                    renderDays(trip, view.items()),
                    // Empty, and the contract says an empty array here does NOT mean the trip has no
                    // candidates: candidateCount is the number to display and listTripCandidates is
                    // the paginated source.
                    List.of());
        }
    }

    public record TripInterestResponse(String code, int weight) { }

    public record TripDayResponse(LocalDate date, List<Object> items) { }

    /**
     * Every trip date, in order, each carrying the items stored for it.
     *
     * <p>A date with nothing on it still appears: the contract's day array is the trip's calendar,
     * not a list of the days that happen to be busy, and a client rendering a week needs the gaps.
     */
    static List<TripDayResponse> renderDays(Trip trip, List<TripItemView> items) {
        Map<LocalDate, List<Object>> byDate = new LinkedHashMap<>();
        for (TripItemView view : items) {
            byDate.computeIfAbsent(view.item().date(), date -> new ArrayList<>())
                    .add(TripItemResponse.from(view));
        }
        return trip.days().stream()
                .map(date -> new TripDayResponse(date, byDate.getOrDefault(date, List.of())))
                .toList();
    }

    public record TripItemResponse(UUID id, PlaceSummaryResponse place, LocalDate date, int position,
            String startTime, Integer durationMinutes, String note, List<Object> constraints) {

        static TripItemResponse from(TripItemView view) {
            TripItem item = view.item();
            return new TripItemResponse(item.id(), PlaceSummaryResponse.from(view.place()),
                    item.date(), item.position(), wallClock(item.startTime()), item.durationMinutes(),
                    item.note(), item.constraints().stream().map(TripItemResponse::constraint).toList());
        }

        /**
         * The four lock shapes are a tagged union with {@code additionalProperties: false}, so each
         * one is rendered as exactly its own fields. A single flat record with nulls would be a
         * different document: {@code date: null} on a MUST_VISIT is a property that variant does not
         * declare, and the contract rejects it rather than ignoring it.
         */
        static Object constraint(TripConstraint stored) {
            String source = stored.source().name();
            return switch (stored.lock()) {
                case ItemLock.MustVisit ignored -> new MustVisitConstraintResponse("MUST_VISIT", true, source);
                case ItemLock.Date date -> new DateConstraintResponse("DATE", true, source, date.date());
                case ItemLock.Time time -> new TimeConstraintResponse("TIME", true, source,
                        wallClock(time.startTime()), time.toleranceMinutes());
                case ItemLock.Reservation reservation -> new ReservationConstraintResponse("RESERVATION",
                        true, source, reservation.date(), wallClock(reservation.startTime()),
                        wallClock(reservation.endTime()));
            };
        }

        /**
         * Seconds are always present. The contract's pattern requires them (#145) and Jackson's
         * default rendering drops them on the minute, so {@code 09:00} would leave the schema.
         */
        static String wallClock(java.time.LocalTime value) {
            return value == null ? null : WALL_CLOCK.format(value);
        }
    }

    private static final java.time.format.DateTimeFormatter WALL_CLOCK =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");

    public record MustVisitConstraintResponse(String type, boolean locked, String source) { }

    public record DateConstraintResponse(String type, boolean locked, String source, LocalDate date) { }

    public record TimeConstraintResponse(String type, boolean locked, String source, String startTime,
            int toleranceMinutes) { }

    public record ReservationConstraintResponse(String type, boolean locked, String source,
            LocalDate date, String startTime, String endTime) { }
}
