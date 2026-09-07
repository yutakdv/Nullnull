package io.nullnull.recommendation.domain.item;

import java.time.LocalTime;
import java.util.Objects;

/** Mirrors {@code OpeningWindowIn}: a verified window, a verified closure, or an explicit unknown. */
public record OpeningWindowIn(OpeningState state, LocalTime opensAt, LocalTime closesAt) {

    public enum OpeningState { OPEN, CLOSED, UNKNOWN }

    public OpeningWindowIn {
        Objects.requireNonNull(state, "state");
    }

    public static OpeningWindowIn open(LocalTime opensAt, LocalTime closesAt) {
        return new OpeningWindowIn(OpeningState.OPEN, Objects.requireNonNull(opensAt, "opensAt"),
                Objects.requireNonNull(closesAt, "closesAt"));
    }

    public static OpeningWindowIn closed() {
        return new OpeningWindowIn(OpeningState.CLOSED, null, null);
    }

    public static OpeningWindowIn unknown() {
        return new OpeningWindowIn(OpeningState.UNKNOWN, null, null);
    }
}
