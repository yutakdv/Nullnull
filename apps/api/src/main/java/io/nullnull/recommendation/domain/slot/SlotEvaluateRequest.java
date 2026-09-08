package io.nullnull.recommendation.domain.slot;

import io.nullnull.recommendation.domain.item.ItemProposeRequest;
import io.nullnull.recommendation.domain.item.NeighbourItemIn;
import io.nullnull.recommendation.domain.item.OpeningWindowIn;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Mirrors {@code SlotEvaluateRequest}: the facts for one ACTIVE candidate. {@code datesWithSamePlace}
 * are the trip dates that already schedule this canonical place, {@code maxItemsPerDay} is the API's
 * per-day item limit, and {@code checking} is true only while a real verification job runs for this
 * candidate. The body carries no owner, session or raw itinerary text.
 */
public record SlotEvaluateRequest(Instant evaluatedAt, UUID tripId, UUID candidateId, UUID placeId,
        LocalDate tripStart, LocalDate tripEnd, String tripZone, Integer durationMinutes, List<NeighbourItemIn> items,
        Map<LocalDate, OpeningWindowIn> openingHours, List<LocalDate> datesWithSamePlace,
        ItemProposeRequest.RouteEvidence routeEvidence, int maxItemsPerDay, boolean checking) {

    public static final int MAX_ITEMS = 100;
    /** One opening window per trip date, and at most one duplicate per trip date; a trip spans at most 30 (§4.1). */
    public static final int MAX_OPENING_HOURS = 30;
    public static final int MAX_DATES_WITH_SAME_PLACE = 30;

    public SlotEvaluateRequest {
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        Objects.requireNonNull(tripId, "tripId");
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(placeId, "placeId");
        Objects.requireNonNull(tripStart, "tripStart");
        Objects.requireNonNull(tripEnd, "tripEnd");
        Objects.requireNonNull(tripZone, "tripZone");
        Objects.requireNonNull(routeEvidence, "routeEvidence");
        if (tripEnd.isBefore(tripStart)) {
            throw new IllegalArgumentException("tripEnd must not precede tripStart");
        }
        if (maxItemsPerDay < 1) {
            throw new IllegalArgumentException("maxItemsPerDay must be >= 1");
        }
        if (durationMinutes != null && durationMinutes < 1) {
            throw new IllegalArgumentException("durationMinutes must be positive when present");
        }
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        openingHours = Map.copyOf(Objects.requireNonNull(openingHours, "openingHours"));
        datesWithSamePlace = List.copyOf(Objects.requireNonNull(datesWithSamePlace, "datesWithSamePlace"));
        requireAtMost(items.size(), MAX_ITEMS, "items");
        requireAtMost(openingHours.size(), MAX_OPENING_HOURS, "openingHours");
        requireAtMost(datesWithSamePlace.size(), MAX_DATES_WITH_SAME_PLACE, "datesWithSamePlace");
        for (LocalDate date : datesWithSamePlace) {
            // A duplicate outside the trip can only be a hydration bug: it would mask no trip date.
            if (date.isBefore(tripStart) || date.isAfter(tripEnd)) {
                throw new IllegalArgumentException("datesWithSamePlace must lie inside the trip range");
            }
        }
    }

    private static void requireAtMost(int size, int cap, String name) {
        if (size > cap) {
            throw new IllegalArgumentException("at most " + cap + " " + name + " per request");
        }
    }
}
