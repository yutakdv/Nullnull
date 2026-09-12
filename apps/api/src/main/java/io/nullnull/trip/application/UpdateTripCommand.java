package io.nullnull.trip.application;

import io.nullnull.trip.domain.PlanningLevel;
import io.nullnull.trip.domain.Trip;
import io.nullnull.trip.domain.TripDateRange;
import io.nullnull.trip.domain.TripStatus;
import io.nullnull.trip.domain.TripTitles;
import io.nullnull.trip.domain.TripValidationException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * A merge-patch for trip metadata.
 *
 * <p>Every field is an {@link Optional}: EMPTY means the caller did not send it and the current
 * value stands, PRESENT means they did. That distinction is the whole point of merge-patch and it
 * cannot be expressed by a null field, because for a nullable property null is itself a value the
 * caller may be sending.
 *
 * <p>No field here is nullable, so a present value is always a real one. {@code title} is the one
 * that could look nullable - the contract allows omitting it on create - but on update, sending
 * null would mean "remove the title", and a trip always has one. A blank title is refused rather
 * than being quietly replaced by the locale default, because on update the caller is naming the
 * trip, not declining to.
 */
public record UpdateTripCommand(Optional<String> title, Optional<LocalDate> startDate,
        Optional<LocalDate> endDate, Optional<String> timezone, Optional<PlanningLevel> planningLevel,
        Optional<TripStatus> status) {

    public UpdateTripCommand {
        title = title == null ? Optional.empty() : title;
        startDate = startDate == null ? Optional.empty() : startDate;
        endDate = endDate == null ? Optional.empty() : endDate;
        timezone = timezone == null ? Optional.empty() : timezone;
        planningLevel = planningLevel == null ? Optional.empty() : planningLevel;
        status = status == null ? Optional.empty() : status;
    }

    public boolean isEmpty() {
        return title.isEmpty() && startDate.isEmpty() && endDate.isEmpty() && timezone.isEmpty()
                && planningLevel.isEmpty() && status.isEmpty();
    }

    /**
     * The trip this patch produces, at the next version.
     *
     * <p>The range is rebuilt as a whole even when only one end moved, so the reversal and 30-day
     * rules are applied to the RESULT rather than to the part that changed - moving startDate past
     * an unchanged endDate is exactly as invalid as sending both.
     *
     * <p>Changing the timezone keeps the local dates and wall-clock times as they are. The contract
     * says so outright, and it is the only reading that does not silently move a booking: the user
     * corrected which zone their 09:30 was always in, they did not ask for a different 09:30.
     */
    public Trip applyTo(Trip current, Instant now) {
        if (isEmpty()) {
            throw new TripValidationException("body", "Empty", "the patch changed nothing");
        }
        String newTitle = title.map(value -> requireTitle(value, current))
                .orElse(current.title());
        TripDateRange range = TripDateRange.of(startDate.orElse(current.range().startDate()),
                endDate.orElse(current.range().endDate()),
                timezone.orElse(current.range().timezone().getId()));
        TripStatus newStatus = status.orElse(current.status());
        if (current.status() == TripStatus.ARCHIVED && newStatus == TripStatus.ARCHIVED) {
            // Re-archiving is not an edit; the timestamp would move for nothing.
            newStatus = TripStatus.ARCHIVED;
        }
        Instant archivedAt = switch (newStatus) {
            case ARCHIVED -> current.archivedAt() == null ? now : current.archivedAt();
            default -> null;
        };
        return new Trip(current.id(), current.ownerId(), newTitle, range,
                planningLevel.orElse(current.planningLevel()), newStatus, current.version() + 1,
                current.interests(), current.createdAt(), now, archivedAt);
    }

    private static String requireTitle(String value, Trip current) {
        if (value == null || value.isBlank()) {
            throw new TripValidationException("title", "NotBlank",
                    "title must not be blank; omit it to keep the current one");
        }
        return TripTitles.resolve(value, null);
    }
}
