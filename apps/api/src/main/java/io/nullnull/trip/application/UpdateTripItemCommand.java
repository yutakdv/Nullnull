package io.nullnull.trip.application;

import io.nullnull.trip.domain.LockType;
import io.nullnull.trip.domain.TripItem;
import io.nullnull.trip.domain.TripValidationException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.Set;

/**
 * A merge-patch for one scheduled item.
 *
 * <p>Three of these fields are nullable in the contract, which merge-patch turns into three states
 * rather than two: not sent, sent as a value, sent as null to clear. {@code Optional<Optional<T>>}
 * carries all three - the outer says whether the caller sent the field, the inner whether what they
 * sent was a value. A single Optional cannot: for a nullable property, null is itself something the
 * caller may be saying, so collapsing the two would make "remove the start time" indistinguishable
 * from "leave the start time alone".
 *
 * <p>{@code date} and {@code position} are not nullable, so they keep the single Optional
 * {@link UpdateTripCommand} uses, and an explicit null on either is a field error rather than a
 * clear.
 */
public record UpdateTripItemCommand(Optional<LocalDate> date, Optional<Integer> position,
        Optional<Optional<LocalTime>> startTime, Optional<Optional<Integer>> durationMinutes,
        Optional<Optional<String>> note, Set<LockType> releaseConstraints) {

    public UpdateTripItemCommand {
        date = date == null ? Optional.empty() : date;
        position = position == null ? Optional.empty() : position;
        startTime = startTime == null ? Optional.empty() : startTime;
        durationMinutes = durationMinutes == null ? Optional.empty() : durationMinutes;
        note = note == null ? Optional.empty() : note;
        releaseConstraints = releaseConstraints == null ? Set.of() : Set.copyOf(releaseConstraints);
        if (position.isPresent() && position.get() < 0) {
            throw new TripValidationException("position", "Range", "position must not be negative");
        }
        Integer duration = durationMinutes.flatMap(value -> value).orElse(null);
        if (duration != null && (duration < 1 || duration > TripItem.MAX_DURATION_MINUTES)) {
            throw new TripValidationException("durationMinutes", "Range",
                    "durationMinutes must be between 1 and " + TripItem.MAX_DURATION_MINUTES);
        }
        String text = note.flatMap(value -> value).orElse(null);
        if (text != null && text.length() > TripItem.MAX_NOTE_LENGTH) {
            throw new TripValidationException("note", "Size",
                    "note must be at most " + TripItem.MAX_NOTE_LENGTH + " characters");
        }
        if (releaseConstraints.contains(LockType.MUST_VISIT)) {
            // ReleasedTemporalLocks cannot express it, so this is unreachable over HTTP. It is
            // checked because the type allows it: MUST_VISIT pins the PLACE, which this command
            // never changes, so it can never be the lock that refused the edit.
            throw new TripValidationException("releaseConstraints", "Enum",
                    "an item update cannot release MUST_VISIT");
        }
    }

    /** True when nothing about the item itself would move. A lock release alone is still a change. */
    public boolean touchesNothing() {
        return date.isEmpty() && position.isEmpty() && startTime.isEmpty()
                && durationMinutes.isEmpty() && note.isEmpty() && releaseConstraints.isEmpty();
    }

    /**
     * The item this patch would produce from the one stored.
     *
     * <p>The nullable fields are unwrapped with an explicit isPresent rather than
     * {@code map(...).orElse(current)}. That shorter form is wrong in exactly the case this type
     * exists for: {@code Optional.map} answers EMPTY when its mapper returns null, so a field the
     * caller sent as null - the clear - becomes indistinguishable from one they never sent, and the
     * old value survives. A test that patched note to null caught it.
     */
    TripItem applyTo(TripItem item) {
        return new TripItem(item.id(), item.placeId(), date.orElse(item.date()),
                position.orElse(item.position()),
                startTime.isPresent() ? startTime.get().orElse(null) : item.startTime(),
                durationMinutes.isPresent() ? durationMinutes.get().orElse(null)
                        : item.durationMinutes(),
                note.isPresent() ? note.get().orElse(null) : item.note(),
                item.constraints().stream()
                        .filter(constraint -> !releaseConstraints.contains(constraint.type()))
                        .toList());
    }
}
