package io.nullnull.trip.application;

import io.nullnull.trip.domain.LockType;
import io.nullnull.trip.domain.TripValidationException;
import java.util.Set;
import java.util.UUID;

/**
 * replaceTripItem's body, validated.
 *
 * <p>Two of the schema's four fields are refused rather than honoured, and both refusals are
 * deliberate. They are published with no meaning behind them - a request field reached the contract
 * before any code read it - and the two wrong answers are worse than a refusal: accepting and
 * ignoring makes what the caller sent disappear silently, and implementing a guess settles a
 * question nobody has asked. A refusal can be opened later; an invented behaviour is hard to take
 * back once clients depend on it.
 */
public record ReplaceTripItemCommand(UUID replacementPlaceId, UUID relationId,
        Boolean preserveDateTime, Set<LockType> releaseConstraints) {

    public ReplaceTripItemCommand {
        if (replacementPlaceId == null) {
            throw new TripValidationException("replacementPlaceId", "NotNull",
                    "replacementPlaceId is required");
        }
        if (relationId != null) {
            // No response in the contract carries a relation id and no table stores one, so a caller
            // cannot hold a value this could validate - only null or something it invented (#204).
            throw new TripValidationException("relationId", "Unsupported",
                    "relationId is not accepted: no operation issues one yet");
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
