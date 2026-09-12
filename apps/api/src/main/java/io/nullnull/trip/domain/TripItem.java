package io.nullnull.trip.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One scheduled place in a trip.
 *
 * <p>{@code startTime} is a wall-clock time in the trip's timezone and carries no offset (#145). It
 * is optional: an item can sit on a day without a time, which is what distinguishes it from a
 * TripCandidate only by being on the schedule at all (invariant 1).
 */
public record TripItem(UUID id, UUID placeId, LocalDate date, int position, LocalTime startTime,
        Integer durationMinutes, String note, List<TripConstraint> constraints) {

    /** The contract caps a day at 20 items and a trip at 100. */
    public static final int MAX_PER_DAY = 20;
    public static final int MAX_PER_TRIP = 100;
    public static final int MAX_NOTE_LENGTH = 500;

    public TripItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(placeId, "placeId");
        Objects.requireNonNull(date, "date");
        if (position < 0) {
            throw new TripValidationException("seedItems[].position", "Range",
                    "position must not be negative");
        }
        if (durationMinutes != null && durationMinutes < 1) {
            throw new TripValidationException("seedItems[].durationMinutes", "Range",
                    "durationMinutes must be at least 1");
        }
        if (note != null && note.length() > MAX_NOTE_LENGTH) {
            throw new TripValidationException("seedItems[].note", "Size",
                    "note must be at most " + MAX_NOTE_LENGTH + " characters");
        }
        constraints = TripConstraint.validated(constraints);
    }

    /**
     * Whether this item pins itself to a calendar date, which is what a date-range shrink has to
     * respect. A DATE or RESERVATION lock does; MUST_VISIT and TIME do not - MUST_VISIT says the
     * place stays in the trip, and TIME pins a clock time rather than a day.
     */
    public boolean pinsADate() {
        return constraints.stream()
                .anyMatch(constraint -> constraint.type() == LockType.DATE
                        || constraint.type() == LockType.RESERVATION);
    }

    /** The date a lock pins this item to, or the item's own date when nothing pins it. */
    public LocalDate pinnedDate() {
        for (TripConstraint constraint : constraints) {
            if (constraint.lock() instanceof ItemLock.Date locked) {
                return locked.date();
            }
            if (constraint.lock() instanceof ItemLock.Reservation locked) {
                return locked.date();
            }
        }
        return date;
    }
}
