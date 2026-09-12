package io.nullnull.trip.application;

import io.nullnull.trip.domain.Trip;
import java.util.Objects;

/**
 * A trip plus the one number that is not part of the aggregate: how many candidates it holds.
 *
 * <p>The contract is explicit that {@code candidateCount} is NOT {@code candidates.length} - the
 * embedded array is a bounded view and the paginated source is listTripCandidates - so the count is
 * carried separately rather than derived from a list the response may have truncated.
 */
public record TripView(Trip trip, int candidateCount) {
    public TripView {
        Objects.requireNonNull(trip, "trip");
        if (candidateCount < 0) {
            throw new IllegalArgumentException("candidateCount must not be negative");
        }
    }
}
