package io.nullnull.trip.application;

import io.nullnull.trip.domain.Trip;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for the trip aggregate's metadata. Owned by the trip module. */
public interface TripStore {

    /** Writes the trip, its interests and its first revision in the caller's transaction. */
    void create(Trip trip, String snapshotSchemaVersion, String snapshotHash, String snapshot);

    /** The trip if this owner holds it. A trip owned by someone else is absent, never forbidden. */
    Optional<Trip> find(UUID ownerId, UUID tripId);

    /**
     * One page of the owner's trips. {@code offset} comes from the decoded cursor and {@code limit}
     * is the caller's page size; the implementation fetches one extra row so the caller can tell
     * "there is more" from "this page happened to be full".
     */
    List<Trip> page(UUID ownerId, String status, long offset, int limit);

    /** How many candidates each of these trips holds, for TripSummary.candidateCount. */
    java.util.Map<UUID, Integer> candidateCounts(List<UUID> tripIds);
}
