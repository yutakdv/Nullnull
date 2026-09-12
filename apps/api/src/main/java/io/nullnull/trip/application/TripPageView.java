package io.nullnull.trip.application;

import java.util.List;
import java.util.Objects;

/** One page of listTrips, with the opaque cursor for the next one (null when there is no next). */
public record TripPageView(List<TripView> items, String nextCursor, boolean hasMore) {
    public TripPageView {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (!hasMore && nextCursor != null) {
            throw new IllegalArgumentException("a last page must not carry a next cursor");
        }
    }
}
