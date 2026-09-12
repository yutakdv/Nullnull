package io.nullnull.trip.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The trip aggregate's metadata, as the server holds it.
 *
 * <p>Days are not a field. {@link #days()} derives them from the range so a trip cannot hold a day
 * list that disagrees with its own dates, which is also what makes a created trip's shape the same
 * on every retry.
 *
 * <p>{@code version} is the ETag: getTrip returns it quoted and every mutation must send it back as
 * If-Match (invariant 6). It starts at 1 and only a mutation that actually changes the aggregate
 * raises it - saving a candidate does not (invariant 2).
 */
public record Trip(UUID id, UUID ownerId, String title, TripDateRange range, PlanningLevel planningLevel,
        TripStatus status, long version, List<TripInterest> interests, Instant createdAt,
        Instant updatedAt, Instant archivedAt) {

    public Trip {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(range, "range");
        Objects.requireNonNull(planningLevel, "planningLevel");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 1) {
            throw new IllegalArgumentException("trip version starts at 1");
        }
        if ((status == TripStatus.ARCHIVED) != (archivedAt != null)) {
            throw new IllegalArgumentException("archivedAt exists exactly while the trip is ARCHIVED");
        }
        interests = TripInterest.validated(interests);
    }

    /**
     * The deterministic seed createTrip produces: one day per calendar date in the range, in order.
     * Items are not part of this - seeding items needs SeedTripItem.startTime, whose wire format is
     * still contradictory across the contract, the ERD and the API README (PM-008).
     */
    public List<LocalDate> days() {
        return range.days();
    }

    /** The ETag value, quoted, exactly as getTrip and createTrip send it. */
    public String entityTag() {
        return "\"" + version + "\"";
    }
}
