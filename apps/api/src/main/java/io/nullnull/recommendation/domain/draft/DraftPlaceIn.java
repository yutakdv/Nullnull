package io.nullnull.recommendation.domain.draft;

import io.nullnull.recommendation.domain.item.OpeningWindowIn;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Mirrors {@code DraftPlaceIn}: one published place of the pool and the windows somebody verified for
 * it. A trip date with no entry is unverified, which the service labels UNKNOWN - never OPEN and never
 * CLOSED - so the map carries only what catalog established.
 */
public record DraftPlaceIn(UUID placeId, Map<LocalDate, OpeningWindowIn> openingHours) {

    /** One window per trip date, and a trip spans at most 30 (§4.1); the service refuses more with a 422. */
    public static final int MAX_OPENING_HOURS = 30;

    public DraftPlaceIn {
        Objects.requireNonNull(placeId, "placeId");
        openingHours = Map.copyOf(Objects.requireNonNull(openingHours, "openingHours"));
        if (openingHours.size() > MAX_OPENING_HOURS) {
            throw new IllegalArgumentException("at most " + MAX_OPENING_HOURS + " openingHours per place");
        }
    }
}
