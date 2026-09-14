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

    /**
     * The contract's {@code maximum: 1440} on durationMinutes - one day. Only the lower bound was
     * enforced here, which no request could reach while SeedTripItem was the sole way in: it carries
     * no duration at all. addTripItem does, so the upper bound has a caller for the first time.
     */
    public static final int MAX_DURATION_MINUTES = 1440;

    public TripItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(placeId, "placeId");
        Objects.requireNonNull(date, "date");
        // seedItems[] is the right prefix here and only here: createTrip is the one caller that can
        // reach this constructor with a value a request chose. The two item commands validate in
        // their own compact constructors, where the field is plainly durationMinutes.
        requireFieldBounds("seedItems[].", position, durationMinutes, note);
        constraints = TripConstraint.validated(constraints);
    }

    /**
     * The bounds of one item's own fields, in ONE place, with the field path supplied by the caller.
     *
     * <p>These three rules used to be written out three times - here, in AddTripItemCommand and in
     * UpdateTripItemCommand - and that is the shape AGENTS.md rule 3 warns about: when a rule lives
     * in several places, one of them goes stale first. The defect was already visible before it did
     * any harm. A mutation that deleted the bound from AddTripItemCommand left BA-040-T8 GREEN,
     * because this constructor was still refusing the value - so no copy was individually necessary,
     * and the test could not see any one of them disappear. Worse, the copy that survived says
     * {@code seedItems[].durationMinutes}, a field path that does not exist in an addTripItem body,
     * so the day a command's own copy went the FE would be sent to a field its form does not have.
     *
     * <p>The value is owned by this type; the field path is not. The same rule rejects
     * {@code seedItems[].durationMinutes} on createTrip and {@code durationMinutes} on
     * updateTripItem, so the caller passes the prefix rather than this method guessing it.
     *
     * <p>Callers still each call it. Defence in depth is not duplication: BA-040-T8 asks that EVERY
     * command that can set a duration refuses a bad one, not that one layer does.
     *
     * @param position null when the caller is not setting one
     */
    public static void requireFieldBounds(String fieldPrefix, Integer position, Integer durationMinutes,
            String note) {
        if (position != null && position < 0) {
            throw new TripValidationException(fieldPrefix + "position", "Range",
                    "position must not be negative");
        }
        if (durationMinutes != null
                && (durationMinutes < 1 || durationMinutes > MAX_DURATION_MINUTES)) {
            throw new TripValidationException(fieldPrefix + "durationMinutes", "Range",
                    "durationMinutes must be between 1 and " + MAX_DURATION_MINUTES);
        }
        if (note != null && note.length() > MAX_NOTE_LENGTH) {
            throw new TripValidationException(fieldPrefix + "note", "Size",
                    "note must be at most " + MAX_NOTE_LENGTH + " characters");
        }
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
