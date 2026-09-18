package io.nullnull.recommendation.domain.draft;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Mirrors {@code DraftComposeRequest} (REC-CON-04): the trip dates and the pool to place on them.
 *
 * <p>{@code tripEnd} is inclusive. {@code maxStopsPerDay} is the API's own per-day cap for a draft,
 * sent rather than read from policy-v1 so the pinned policy hash does not move. The body carries no
 * owner or session id, no interest, no must-visit place, no crowd value and no time of day.
 *
 * <p>Everything the service would answer with a 422 is refused here first, because a 4xx from
 * {@code apps/ai} is a hydration bug on this side, not an outage.
 */
public record DraftComposeRequest(Instant evaluatedAt, LocalDate tripStart, LocalDate tripEnd, String tripZone,
        int maxStopsPerDay, List<DraftPlaceIn> pool) {

    /** The service ranks at most 100 places; the API sends the first 100 by id and says it truncated. */
    public static final int MAX_POOL = 100;
    /** An IANA zone id; the service refuses a longer one with a 422. */
    public static final int MAX_TRIP_ZONE = 64;
    /** A trip spans at most 30 dates (§4.1), the same bound createTrip enforces. */
    public static final int MAX_TRIP_DATES = 30;

    public DraftComposeRequest {
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        Objects.requireNonNull(tripStart, "tripStart");
        Objects.requireNonNull(tripEnd, "tripEnd");
        Objects.requireNonNull(tripZone, "tripZone");
        if (tripEnd.isBefore(tripStart)) {
            throw new IllegalArgumentException("tripEnd must not precede tripStart");
        }
        if (tripEnd.toEpochDay() - tripStart.toEpochDay() + 1 > MAX_TRIP_DATES) {
            throw new IllegalArgumentException("a trip spans at most " + MAX_TRIP_DATES + " dates");
        }
        if (tripZone.isBlank()) {
            throw new IllegalArgumentException("tripZone must not be blank");
        }
        if (tripZone.length() > MAX_TRIP_ZONE) {
            throw new IllegalArgumentException("tripZone must be at most " + MAX_TRIP_ZONE + " characters");
        }
        if (maxStopsPerDay < 1) {
            throw new IllegalArgumentException("maxStopsPerDay must be >= 1");
        }
        pool = List.copyOf(Objects.requireNonNull(pool, "pool"));
        if (pool.size() > MAX_POOL) {
            throw new IllegalArgumentException("at most " + MAX_POOL + " places per pool");
        }
        Set<UUID> seen = new HashSet<>();
        for (DraftPlaceIn place : pool) {
            // Two entries for one place would let it be placed twice; the service refuses that too.
            if (!seen.add(place.placeId())) {
                throw new IllegalArgumentException("pool carries a placeId more than once");
            }
            for (LocalDate date : place.openingHours().keySet()) {
                // A window outside the trip decides nothing and the service refuses it with a 422.
                if (date.isBefore(tripStart) || date.isAfter(tripEnd)) {
                    throw new IllegalArgumentException("openingHours must lie inside the trip range");
                }
            }
        }
    }
}
