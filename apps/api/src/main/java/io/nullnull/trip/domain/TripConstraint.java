package io.nullnull.trip.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One stored lock: the lock itself plus who placed it.
 *
 * <p>{@link ItemLock} already carries each type's own fields and nothing else, which is the tagged
 * shape docs/api/openapi.yaml declares through the {@code TripConstraint} discriminator. Only
 * {@code source} lives outside it, because it is common to all four.
 *
 * <p>What this deliberately does NOT do is decide what SETTING or RELEASING a lock means - the
 * transitions, whether one lock blocks another move, what a release does to an item. Those belong
 * to BA-041 (setTripItemConstraint / removeTripItemConstraint) and are not implied by being able to
 * store the four shapes the contract already defines.
 */
public record TripConstraint(ItemLock lock, ConstraintSource source) {

    /** The contract caps an item at four constraints because there are four independent types. */
    public static final int MAX_PER_ITEM = 4;

    public TripConstraint {
        Objects.requireNonNull(lock, "lock");
        Objects.requireNonNull(source, "source");
    }

    public LockType type() {
        return lock.type();
    }

    /**
     * One lock per type on one item. The four are independent (invariant 7), so two of the same type
     * is not "the later one wins" - it is a request that cannot be satisfied, and the database's
     * unique (trip_item_id, type) refuses it anyway.
     */
    public static List<TripConstraint> validated(List<TripConstraint> submitted) {
        List<TripConstraint> constraints = submitted == null ? List.of() : submitted;
        if (constraints.size() > MAX_PER_ITEM) {
            throw new TripValidationException("seedItems[].constraints", "Size",
                    "an item may carry at most " + MAX_PER_ITEM + " constraints");
        }
        Set<LockType> seen = new LinkedHashSet<>();
        List<TripConstraint> validated = new ArrayList<>(constraints.size());
        for (TripConstraint constraint : constraints) {
            Objects.requireNonNull(constraint, "constraint");
            if (!seen.add(constraint.type())) {
                throw new TripValidationException("seedItems[].constraints[].type", "Duplicate",
                        "each constraint type may appear at most once on an item");
            }
            validated.add(constraint);
        }
        return List.copyOf(validated);
    }
}
