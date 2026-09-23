package io.nullnull.trip.application;

import io.nullnull.trip.domain.Trip;
import io.nullnull.trip.domain.LockType;
import io.nullnull.trip.domain.TripConstraint;
import io.nullnull.trip.domain.TripItem;
import java.time.Instant;
import java.time.LocalDate;
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

    /**
     * The trip's version with its row locked FOR SHARE until the caller's transaction ends, or empty
     * when this owner holds no such trip.
     *
     * <p>SHARE rather than UPDATE because the caller only needs the trip to hold still while it acts on
     * what it read: every mutation takes the row FOR UPDATE and so waits, while a foreign key check
     * (FOR KEY SHARE, a candidate or item being inserted) and another holder of SHARE do not. It is a
     * lock, so it needs a read-write transaction: PostgreSQL refuses row locks in a read-only one.
     */
    java.util.OptionalLong versionForShare(UUID ownerId, UUID tripId);

    /** The trip's scheduled items, with their constraints. */
    List<TripItem> items(UUID tripId);

    /**
     * The revision recorded at one version of a trip, if the trip has reached that version.
     *
     * <p>{@code (trip_id, version)} is unique, so a version names at most one revision. Empty means
     * the version was never written - which is a real answer rather than an error, because a caller
     * asking about a version it read a moment ago can be racing a mutation that has not committed.
     */
    Optional<UUID> revisionAt(UUID tripId, long version);

    /**
     * Adds one item and its constraints. Every rule about whether it MAY be added - the range, the
     * caps, the positions - is the caller's, checked against the items it read under the same lock.
     */
    void insertItem(UUID tripId, TripItem item, Instant at);

    /**
     * Removes one item. False when this trip did not hold it.
     *
     * <p>Whatever pointed at the item must have stopped pointing at it first. The database says so
     * rather than a convention: {@code trip_candidates.scheduled_trip_item_id} is ON DELETE SET NULL
     * while {@code trip_candidates_scheduled_shape_check} requires a SCHEDULED candidate to name an
     * item, so deleting the item out from under one turns the referential action into a constraint
     * violation instead of a candidate that quietly forgot where it was scheduled.
     */
    boolean deleteItem(UUID tripId, UUID itemId);

    /**
     * Moves one item to a date and a position.
     *
     * <p>Callers moving several items must first {@link #deferSlotUniqueness()}: the obvious moves
     * pass through a state where two rows share a slot, and the constraint refuses that per statement
     * until it is deferred.
     */
    void moveItem(UUID tripId, UUID itemId, LocalDate date, int position, Instant at);

    /**
     * Defers {@code trip_items_slot_unique} to COMMIT for the current transaction.
     *
     * <p>Scoped to the transaction by PostgreSQL itself, so nothing outside it changes: a writer that
     * never calls this still fails on its own statement.
     */
    void deferSlotUniqueness();

    /** Writes an item's patchable fields. Its id, trip and place are not among them. */
    void updateItem(UUID tripId, TripItem item, Instant at);

    /** Points one item at a different place, keeping every schedule field it already holds. */
    void replaceItemPlace(UUID tripId, UUID itemId, UUID placeId, Instant at);

    /**
     * Writes one lock, replacing only its own type.
     *
     * <p>The unique index on {@code (trip_item_id, type)} is what makes "only its own" true in
     * storage rather than in this method's care: setting DATE cannot disturb TIME because they are
     * different rows, and re-setting DATE cannot produce two because the index refuses it.
     */
    void putConstraint(UUID tripId, UUID itemId, TripConstraint constraint, Instant at);

    /** Removes one lock from an item. False when the item did not carry that type. */
    boolean deleteConstraint(UUID tripItemId, LockType type);

    /** Applies new metadata at {@code trip.version()}, which the caller has already incremented. */
    void updateMetadata(Trip trip, String snapshotSchemaVersion, String snapshotHash, String snapshot);

    /** Removes the trip; every owned row follows through the foreign keys. */
    void delete(UUID ownerId, UUID tripId);

    /**
     * One page of the owner's trips, resuming after {@code after}. {@code limit} is the caller's page
     * size plus one extra row, so the caller can tell "there is more" from "this page happened to be
     * full".
     *
     * @param after the last trip of the previous page, or null for the first page
     */
    List<Trip> page(UUID ownerId, String status, PageKey after, int limit);

    /** The row a trip page ended on, in the terms {@code start_date DESC, id DESC} sorts by. */
    record PageKey(LocalDate startDate, UUID tripId) { }

    /** How many candidates each of these trips holds, for TripSummary.candidateCount. */
    Map<UUID, Integer> candidateCounts(List<UUID> tripIds);
}
