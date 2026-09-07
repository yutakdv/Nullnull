package io.nullnull.recommendation.domain.item;

import java.time.LocalTime;
import java.util.Objects;

/** Mirrors {@code OpeningWindowIn}: a verified window, a verified closure, or an explicit unknown. */
public record OpeningWindowIn(OpeningState state, LocalTime opensAt, LocalTime closesAt) {

    public enum OpeningState { OPEN, CLOSED, UNKNOWN }

    public OpeningWindowIn {
        Objects.requireNonNull(state, "state");
    }

    /**
     * A window that opens and closes on the same date. P0 has no representation for an overnight window
     * (D-REC-18): {@code closesAt <= opensAt} is refused here exactly as the service refuses it with a
     * 422, so a 22:00-02:00 day is never silently read as an empty or inverted one.
     */
    public static OpeningWindowIn open(LocalTime opensAt, LocalTime closesAt) {
        Objects.requireNonNull(opensAt, "opensAt");
        Objects.requireNonNull(closesAt, "closesAt");
        if (!closesAt.isAfter(opensAt)) {
            throw new IllegalArgumentException("closesAt must be after opensAt");
        }
        return new OpeningWindowIn(OpeningState.OPEN, opensAt, closesAt);
    }

    public static OpeningWindowIn closed() {
        return new OpeningWindowIn(OpeningState.CLOSED, null, null);
    }

    public static OpeningWindowIn unknown() {
        return new OpeningWindowIn(OpeningState.UNKNOWN, null, null);
    }
}
