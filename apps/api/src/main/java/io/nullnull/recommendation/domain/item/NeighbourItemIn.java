package io.nullnull.recommendation.domain.item;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;

/** Mirrors {@code NeighbourItemIn}: another item on a day the move touches. */
public record NeighbourItemIn(UUID itemId, LocalDate date, int position, LocalTime startTime,
        Integer durationMinutes) {

    public NeighbourItemIn {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(date, "date");
    }
}
