package io.nullnull.optimization.domain.route;

import io.nullnull.trip.domain.ItemLock;
import io.nullnull.trip.domain.LockType;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One stop in a candidate day, as {@link RouteFeasibility} needs to see it.
 *
 * <p>{@code key} is the stop's identity inside one verification and nothing else - it is what the
 * route matrix keys its pairs on. It never goes on the wire: a 1-&gt;1 directions request has no
 * field for a caller-chosen identifier, so which pair a response belongs to is fixed by which call
 * was made. (The multi-destination operation does echo a caller-chosen key, and if this ever moves
 * to that shape the same string can serve both ends without a second mapping to keep in step.)
 *
 * <p><b>The locks are a list because an item really can carry four.</b> The four types are
 * independent and are never auto-released (invariant 7); {@code trip_constraints_one_per_type}
 * stores one row per {@code (trip_item_id, type)}, so a stop can be pinned to a date <em>and</em> to
 * a time <em>and</em> hold a booking at once. A single-lock field could hold only one of them and
 * whichever the caller left out would go unchecked - the card's own {@code BA-083-T1} asked for
 * "모든 잠금 조합" and one field cannot express a combination. Two locks of the same type are
 * rejected here rather than resolved, the way the unique index rejects them: a second DATE is a
 * request that cannot be satisfied, not a later one that wins.
 *
 * <p>The locks are the trip module's {@link ItemLock}, not a copy: a second vocabulary here would be
 * a second opinion about which fields belong to which type. {@code LockIn} in the recommendation
 * module reuses it for the same reason.
 */
public record PlannedStop(String key, UUID placeId, Duration dwell, List<ItemLock> locks) {

    public PlannedStop {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(placeId, "placeId");
        Objects.requireNonNull(dwell, "dwell");
        locks = List.copyOf(Objects.requireNonNull(locks, "locks"));
        if (key.isBlank()) {
            throw new IllegalArgumentException("a stop key must not be blank");
        }
        if (dwell.isNegative()) {
            throw new IllegalArgumentException("dwell must not be negative");
        }
        Set<LockType> seen = EnumSet.noneOf(LockType.class);
        for (ItemLock lock : locks) {
            if (!seen.add(Objects.requireNonNull(lock, "lock").type())) {
                throw new IllegalArgumentException("a stop carries at most one lock of each type");
            }
        }
    }

    /** A stop with no lock on it. */
    public static PlannedStop of(String key, UUID placeId, Duration dwell) {
        return new PlannedStop(key, placeId, dwell, List.of());
    }

    /** A stop with the locks it carries, in the order the caller listed them. */
    public static PlannedStop locked(String key, UUID placeId, Duration dwell, ItemLock... locks) {
        return new PlannedStop(key, placeId, dwell, List.of(locks));
    }
}
