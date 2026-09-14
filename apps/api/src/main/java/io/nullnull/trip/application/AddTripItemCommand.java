package io.nullnull.trip.application;

import io.nullnull.trip.domain.TripConstraint;
import io.nullnull.trip.domain.TripItem;
import io.nullnull.trip.domain.TripValidationException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * addTripItem's body, validated.
 *
 * <p>{@code candidateId} is what distinguishes scheduling a place the traveller had saved from
 * adding one straight to the plan. When it is present the named candidate becomes SCHEDULED and
 * points at the new item; when it is absent no candidate is touched, because a place can reach the
 * schedule without ever having been a candidate (invariant 1 keeps the two resources apart).
 *
 * <p>The bounds below are {@link TripItem}'s, referenced rather than repeated so the numbers cannot
 * drift. What is deliberately not shared is the field POINTER: here the request body is the item, so
 * a client is told {@code position}, while the same violation inside createTrip is at
 * {@code seedItems[].position}. Two documents, two pointers, one set of limits.
 */
public record AddTripItemCommand(UUID placeId, UUID candidateId, LocalDate date, int position,
        LocalTime startTime, Integer durationMinutes, String note, List<TripConstraint> constraints) {

    public AddTripItemCommand {
        if (placeId == null) {
            throw new TripValidationException("placeId", "NotNull", "placeId is required");
        }
        if (date == null) {
            throw new TripValidationException("date", "NotNull", "date is required");
        }
        // One rule, owned by TripItem; this caller names the fields as ITS request body spells them.
        TripItem.requireFieldBounds("", position, durationMinutes, note);
        constraints = TripConstraint.validated(constraints);
    }

    /** The item this command asks for, once the service has decided its identity. */
    TripItem toItem(UUID id) {
        return new TripItem(id, placeId, date, position, startTime, durationMinutes, note, constraints);
    }
}
