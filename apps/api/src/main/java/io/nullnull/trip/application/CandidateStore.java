package io.nullnull.trip.application;

import io.nullnull.trip.domain.CandidateStatus;
import io.nullnull.trip.domain.TripCandidate;
import io.nullnull.trip.domain.TripCandidate.CandidateSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for trip candidates. Owned by the trip module. */
public interface CandidateStore {

    /**
     * Inserts an ACTIVE candidate, or returns the existing non-dismissed one for the same place.
     *
     * <p>The convergence is the partial unique index's, not this method's: two concurrent saves
     * cannot both insert, so the loser reads the winner's row. A service-level "check then insert"
     * would let both through under concurrency, which is exactly BA-034-T1.
     */
    Saved saveActive(UUID tripId, UUID placeId, String note, CandidateSource source, Instant now);

    Optional<TripCandidate> find(UUID ownerId, UUID tripId, UUID candidateId);

    /** Marks an ACTIVE candidate DISMISSED. Returns false when the row moved under the caller. */
    boolean dismiss(UUID candidateId, Instant now);

    /**
     * ACTIVE to SCHEDULED, pointing at the item that scheduled it. False when the row moved.
     *
     * <p>The status is in the WHERE clause for the same reason {@link #dismiss} puts it there: a
     * candidate dismissed between the read and this write must not be silently scheduled, and the
     * row count is the only thing that can tell the caller which of the two happened.
     */
    boolean schedule(UUID candidateId, UUID tripItemId, Instant now);

    /**
     * SCHEDULED back to ACTIVE for the candidate that points at this item, clearing the pointer.
     *
     * <p>Empty when no candidate points at it, which is not a failure: an item can reach the schedule
     * without ever having been a candidate (createTrip's seedItems, or addTripItem with no
     * candidateId). The caller decides what that means; the store only reports it.
     */
    Optional<UUID> restoreScheduledFor(UUID tripItemId, Instant now);

    /**
     * SCHEDULED to DISMISSED for the candidate that points at this item, clearing the pointer.
     *
     * <p>Reachable only from removeTripItem with disposition REMOVE (ERD): the user removed the place
     * from the schedule and said not to keep it as a candidate either. Dismissing a SCHEDULED
     * candidate through removeTripCandidate is still refused - that path would leave the item on the
     * schedule with nothing recording where it came from.
     */
    Optional<UUID> dismissScheduledFor(UUID tripItemId, Instant now);

    List<TripCandidate> page(UUID tripId, CandidateStatus status, long offset, int limit);

    int count(UUID tripId);

    /** Whether this owner holds this trip; every candidate read and write is gated on it. */
    boolean ownsTrip(UUID ownerId, UUID tripId);

    /** Non-dismissed candidates for these places in this trip, by place id. */
    Map<UUID, CandidateStatus> statesByPlace(UUID tripId, List<UUID> placeIds);

    /** {@code duplicate} is true when the candidate already existed with a non-dismissed status. */
    record Saved(TripCandidate candidate, boolean duplicate) { }
}
