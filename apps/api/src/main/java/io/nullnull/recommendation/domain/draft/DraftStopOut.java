package io.nullnull.recommendation.domain.draft;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Mirrors {@code DraftStopOut}: one placed place. It carries no time field - P0 proposes a date and
 * never invents a time. {@code hoursState} is OPEN only when this API sent a verified OPEN window for
 * that date; the gateway checks that on the way in.
 */
public record DraftStopOut(UUID placeId, LocalDate date, int position, HoursState hoursState) {

    public enum HoursState { OPEN, UNKNOWN }

    public DraftStopOut {
        Objects.requireNonNull(placeId, "placeId");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(hoursState, "hoursState");
        if (position < 0) {
            throw new IllegalArgumentException("position must be >= 0");
        }
    }
}
