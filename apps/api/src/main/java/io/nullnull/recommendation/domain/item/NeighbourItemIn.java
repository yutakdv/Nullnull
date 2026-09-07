package io.nullnull.recommendation.domain.item;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Mirrors {@code NeighbourItemIn}: another item on a day the move touches. A stay is either unknown or
 * a positive number of minutes — zero or a negative length would shrink a neighbouring interval to
 * nothing and let an overlapping preview through, and the service refuses the same values with a 422.
 */
public record NeighbourItemIn(UUID itemId, LocalDate date, int position, LocalTime startTime,
        Integer durationMinutes) {

    public NeighbourItemIn {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(date, "date");
        if (durationMinutes != null && durationMinutes <= 0) {
            throw new IllegalArgumentException("durationMinutes must be positive when present");
        }
    }
}
