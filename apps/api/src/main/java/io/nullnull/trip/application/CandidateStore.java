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

    List<TripCandidate> page(UUID tripId, CandidateStatus status, long offset, int limit);

    int count(UUID tripId);

    /** Whether this owner holds this trip; every candidate read and write is gated on it. */
    boolean ownsTrip(UUID ownerId, UUID tripId);

    /** Non-dismissed candidates for these places in this trip, by place id. */
    Map<UUID, CandidateStatus> statesByPlace(UUID tripId, List<UUID> placeIds);

    /** {@code duplicate} is true when the candidate already existed with a non-dismissed status. */
    record Saved(TripCandidate candidate, boolean duplicate) { }
}
