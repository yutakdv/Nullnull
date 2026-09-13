package io.nullnull.trip.application;

import io.nullnull.trip.domain.LockType;
import io.nullnull.trip.domain.TripItem;
import io.nullnull.trip.domain.TripValidationException;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * reorderTripItems' body, validated.
 *
 * <p>An entry names an item and where it should end up. Items the request does not name keep the
 * date and position they have: the command is what the caller wants CHANGED, and the rules that
 * follow - one item per slot, the per-day cap, the trip's range - are checked against the whole day
 * as it would end up, not against the entries alone.
 *
 * <p>{@code releaseConstraints} is per entry because the locks are per item. It is the only way a
 * lock is released (contract: {@code ReleasedTemporalLocks}), so a move that a lock refuses and the
 * request does not name is a conflict rather than a quiet unlock - invariant 7 says the server never
 * releases one on its own, and this field is what carries the user's answer instead.
 */
public record ReorderTripItemsCommand(List<Entry> entries) {

    public ReorderTripItemsCommand {
        entries = List.copyOf(entries == null ? List.of() : entries);
        if (entries.isEmpty()) {
            throw new TripValidationException("items", "Size", "at least one item is required");
        }
        if (entries.size() > TripItem.MAX_PER_TRIP) {
            throw new TripValidationException("items", "Size",
                    "at most " + TripItem.MAX_PER_TRIP + " items may be moved at once");
        }
        Set<UUID> seen = new LinkedHashSet<>();
        for (Entry entry : entries) {
            if (!seen.add(entry.itemId())) {
                // Two entries for one item is not "the later one wins" - it is a request with no
                // single answer, and picking one would be the server deciding what the caller meant.
                throw new TripValidationException("items[].itemId", "Duplicate",
                        "each item may appear at most once");
            }
        }
    }

    /** Where one item should end up, and which of its locks the user chose to release to get there. */
    public record Entry(UUID itemId, LocalDate date, int position, Set<LockType> releaseConstraints) {

        public Entry {
            if (itemId == null) {
                throw new TripValidationException("items[].itemId", "NotNull", "itemId is required");
            }
            if (date == null) {
                throw new TripValidationException("items[].date", "NotNull", "date is required");
            }
            if (position < 0) {
                throw new TripValidationException("items[].position", "Range",
                        "position must not be negative");
            }
            releaseConstraints = releaseConstraints == null ? Set.of() : Set.copyOf(releaseConstraints);
            if (releaseConstraints.contains(LockType.MUST_VISIT)) {
                // The contract's enum cannot express it, so this is unreachable from HTTP. It is here
                // because the type can: a MUST_VISIT lock keeps the PLACE, and a reorder keeps the
                // place, so it never refuses one - releasing it here would delete a lock that was not
                // in the way, through a command that had no reason to touch it.
                throw new TripValidationException("items[].releaseConstraints", "Enum",
                        "a reorder cannot release MUST_VISIT");
            }
        }
    }
}
