package io.nullnull.recommendation.domain.item;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;

/** Mirrors {@code TargetItemIn}: the item being moved. A missing start time or duration stays null. */
public record TargetItemIn(UUID itemId, UUID placeId, LocalDate date, LocalTime startTime, Integer durationMinutes,
        int position) {

    public TargetItemIn {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(placeId, "placeId");
        Objects.requireNonNull(date, "date");
        if (durationMinutes != null && durationMinutes <= 0) {
            throw new IllegalArgumentException("durationMinutes must be positive when present");
        }
    }
}
