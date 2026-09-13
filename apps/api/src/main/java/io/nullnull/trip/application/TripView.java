package io.nullnull.trip.application;

import io.nullnull.trip.domain.Trip;
import java.util.List;
import java.util.Objects;

/**
 * A trip, the one number that is not part of the aggregate, and the items a detail response shows.
 *
 * <p>The contract is explicit that {@code candidateCount} is NOT {@code candidates.length} - the
 * embedded array is a bounded view and the paginated source is listTripCandidates - so the count is
 * carried separately rather than derived from a list the response may have truncated.
 *
 * <p>{@code items} is empty for a list projection, which shows summaries and has no day structure to
 * fill. It is also empty for a trip that genuinely has none, and those two are not distinguishable
 * here on purpose: the only caller that renders days is the detail response, and a trip with no
 * items renders empty days either way.
 */
public record TripView(Trip trip, int candidateCount, List<TripItemView> items) {
    public TripView {
        Objects.requireNonNull(trip, "trip");
        if (candidateCount < 0) {
            throw new IllegalArgumentException("candidateCount must not be negative");
        }
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** A projection with no day structure to fill: listTrips. */
    public TripView(Trip trip, int candidateCount) {
        this(trip, candidateCount, List.of());
    }
}
