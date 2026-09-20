package io.nullnull.optimization.domain.route;

import io.nullnull.trip.domain.ItemLock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * One stop in a candidate day, as {@link RouteFeasibility} needs to see it.
 *
 * <p>{@code key} is the stop's identity inside one verification and nothing else - it is what the
 * route matrix keys its pairs on. Kakao's multi-destination request carries a caller-chosen
 * {@code key} per destination and echoes it back on each result, so the same string can address a
 * leg on the wire and a stop here without a second mapping to keep in step.
 *
 * <p>The lock is the trip module's {@link ItemLock}, not a copy: the four types are independent and
 * never auto-released (invariant 7), and a second vocabulary here would be a second opinion about
 * which fields belong to which type. {@code LockIn} in the recommendation module reuses it for the
 * same reason. A stop with no lock carries null.
 */
public record PlannedStop(String key, UUID placeId, Duration dwell, ItemLock lock) {

    public PlannedStop {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(placeId, "placeId");
        Objects.requireNonNull(dwell, "dwell");
        if (key.isBlank()) {
            throw new IllegalArgumentException("a stop key must not be blank");
        }
        if (dwell.isNegative()) {
            throw new IllegalArgumentException("dwell must not be negative");
        }
    }

    /** A stop with no lock on it. */
    public static PlannedStop of(String key, UUID placeId, Duration dwell) {
        return new PlannedStop(key, placeId, dwell, null);
    }
}
