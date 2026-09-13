package io.nullnull.trip.application;

import io.nullnull.trip.domain.LockType;
import io.nullnull.trip.domain.TripValidationException;
import java.util.Set;
import java.util.UUID;

/**
 * replaceTripItem's body, validated.
 *
 * <p>Two of the schema's three remaining fields are refused rather than honoured, and both refusals
 * are deliberate. They were published with no meaning behind them - a request field reached the
 * contract before any code read it - and the two wrong answers are worse than a refusal: accepting
 * and ignoring makes what the caller sent disappear silently, and implementing a guess settles a
 * question nobody has asked. A refusal can be opened later; an invented behaviour is hard to take
 * back once clients depend on it.
 *
 * <p>A third, {@code relationId}, was removed from the contract instead (#204, owner-approved): no
 * operation issues a relation id, so unlike the other two there was no future in which a caller
 * could send a meaningful one.
 */
public record ReplaceTripItemCommand(UUID replacementPlaceId, Boolean preserveDateTime,
        Set<LockType> releaseConstraints) {

    public ReplaceTripItemCommand {
        if (replacementPlaceId == null) {
            throw new TripValidationException("replacementPlaceId", "NotNull",
                    "replacementPlaceId is required");
        }
        if (preserveDateTime != null && !preserveDateTime) {
            // What false should do has never been decided; the operation's own summary says the
            // schedule is kept unconditionally (#203).
            throw new TripValidationException("preserveDateTime", "Unsupported",
                    "preserveDateTime=false is not supported");
        }
        releaseConstraints = releaseConstraints == null ? Set.of() : Set.copyOf(releaseConstraints);
        for (LockType type : releaseConstraints) {
            if (type == LockType.DATE || type == LockType.TIME) {
                // Published because dropping a value from a request enum is breaking, not because a
                // replacement can be refused by one: it keeps the date and the start time, so these
                // are never in the way and releasing them here would delete a lock nothing asked
                // about.
                throw new TripValidationException("releaseConstraints", "Unsupported",
                        "a replacement keeps the schedule, so " + type + " cannot be released here");
            }
        }
    }
}
