package io.nullnull.recommendation.domain.item;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Mirrors {@code ItemProposeRequest}. Every fact the service needs is hydrated here: it never reads a
 * database or a provider, and the body carries no owner, session or raw itinerary text.
 */
public record ItemProposeRequest(Instant evaluatedAt, UUID tripId, int tripVersion, LocalDate tripStart,
        LocalDate tripEnd, String tripZone, TargetItemIn target, List<LockIn> locks, List<NeighbourItemIn> neighbours,
        Map<LocalDate, OpeningWindowIn> openingHours, RouteEvidence routeEvidence, List<TemporalCandidateIn> candidates) {

    public enum RouteEvidence { NONE, VERIFIED }

    public static final int MAX_LOCKS = 4;
    public static final int MAX_NEIGHBOURS = 100;
    /** One opening window per trip date; a trip spans at most 30 dates (§4.1). */
    public static final int MAX_OPENING_HOURS = 30;
    public static final int MAX_CANDIDATES = 2000;
    /** An IANA zone id; the service refuses a longer one with a 422. */
    public static final int MAX_TRIP_ZONE = 64;

    public ItemProposeRequest {
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        Objects.requireNonNull(tripId, "tripId");
        Objects.requireNonNull(tripStart, "tripStart");
        Objects.requireNonNull(tripEnd, "tripEnd");
        Objects.requireNonNull(tripZone, "tripZone");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(routeEvidence, "routeEvidence");
        if (tripVersion < 1) {
            throw new IllegalArgumentException("tripVersion must be >= 1");
        }
        if (tripEnd.isBefore(tripStart)) {
            throw new IllegalArgumentException("tripEnd must not precede tripStart");
        }
        if (tripZone.isBlank()) {
            throw new IllegalArgumentException("tripZone must not be blank");
        }
        if (tripZone.length() > MAX_TRIP_ZONE) {
            throw new IllegalArgumentException("tripZone must be at most " + MAX_TRIP_ZONE + " characters");
        }
        locks = List.copyOf(Objects.requireNonNull(locks, "locks"));
        neighbours = List.copyOf(Objects.requireNonNull(neighbours, "neighbours"));
        openingHours = Map.copyOf(Objects.requireNonNull(openingHours, "openingHours"));
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        requireAtMost(locks.size(), MAX_LOCKS, "locks");
        requireAtMost(neighbours.size(), MAX_NEIGHBOURS, "neighbours");
        requireAtMost(openingHours.size(), MAX_OPENING_HOURS, "openingHours");
        requireAtMost(candidates.size(), MAX_CANDIDATES, "candidates");
    }

    private static void requireAtMost(int size, int cap, String name) {
        if (size > cap) {
            throw new IllegalArgumentException("at most " + cap + " " + name + " per request");
        }
    }
}
