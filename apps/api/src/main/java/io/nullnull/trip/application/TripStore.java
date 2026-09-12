package io.nullnull.trip.application;

import io.nullnull.trip.domain.Trip;
import io.nullnull.trip.domain.TripItem;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for the trip aggregate. Owned by the trip module. */
public interface TripStore {

    /** Writes the trip, its interests, its seeded items and its first revision in one transaction. */
    void create(Trip trip, List<TripItem> items, String snapshotSchemaVersion, String snapshotHash,
            String snapshot);

    /** The trip if this owner holds it. A trip owned by someone else is absent, never forbidden. */
    Optional<Trip> find(UUID ownerId, UUID tripId);

    /**
     * The trip with its row locked for update, so a concurrent mutation of the same trip waits here
     * instead of both readers deciding on the same version. Two tabs racing is the case BA-031-T1
     * exists for: one must win and the other must see the version it lost to.
     */
    Optional<Trip> findForUpdate(UUID ownerId, UUID tripId);

    /** The trip's scheduled items, with their constraints. */
    List<TripItem> items(UUID tripId);

    /** Applies new metadata at {@code trip.version()}, which the caller has already incremented. */
    void updateMetadata(Trip trip, String snapshotSchemaVersion, String snapshotHash, String snapshot);

    /** Removes the trip; every owned row follows through the foreign keys. */
    void delete(UUID ownerId, UUID tripId);

    /**
     * One page of the owner's trips. {@code offset} comes from the decoded cursor and {@code limit}
     * is the caller's page size; the implementation fetches one extra row so the caller can tell
     * "there is more" from "this page happened to be full".
     */
    List<Trip> page(UUID ownerId, String status, long offset, int limit);

    /** How many candidates each of these trips holds, for TripSummary.candidateCount. */
    Map<UUID, Integer> candidateCounts(List<UUID> tripIds);
}
