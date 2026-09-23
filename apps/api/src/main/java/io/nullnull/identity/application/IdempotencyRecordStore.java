package io.nullnull.identity.application;

import io.nullnull.identity.domain.IdempotencyRecord;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage port for replay records. The two reservation methods exist separately because their SQL
 * semantics are the behaviour: a conditional insert claims the slot, and a locking read serialises
 * concurrent callers on the claimed row. Both are only meaningful inside the caller's transaction.
 */
public interface IdempotencyRecordStore {

    /**
     * {@code INSERT ... ON CONFLICT DO NOTHING} on {@code (owner_id, route_key, idempotency_key)}.
     * Returns true when this caller created the reservation, false when a row already existed.
     */
    boolean insertIfAbsent(IdempotencyRecord reservation);

    /**
     * {@code SELECT ... FOR UPDATE} on the reserved row. A second caller with the same key blocks here
     * until the first one commits, and then sees its stored response. Empty only when the row is gone.
     */
    Optional<IdempotencyRecord> lockExisting(UUID ownerId, String routeKey, String idempotencyKey);

    /** Stores the response projection that later retries replay. */
    void complete(UUID recordId, int responseStatus, String responseBodyJson);

    /**
     * Stores the response and moves the record's expiry to {@code expiresAt}. For a reservation that was
     * committed before its command ran (#340): its expiry until now was a lease on the key, and from the
     * moment a response exists it is the response's retention instead.
     */
    void complete(UUID recordId, int responseStatus, String responseBodyJson, Instant expiresAt);

    /**
     * The record as committed, read without a lock. For a caller waiting on someone else's reservation:
     * it asks whether that one has finished, and a locking read would queue behind the very command that
     * is finishing it.
     */
    Optional<IdempotencyRecord> find(UUID ownerId, String routeKey, String idempotencyKey);

    /**
     * Removes a reservation whose command did not complete, and only such a one: a failed command frees
     * its key, but a record that already holds a response is never taken back by this.
     */
    void release(UUID recordId);

    /**
     * Removes one record by identifier. The guard uses it for a row whose retention has expired: the
     * slot counts as absent, so it is deleted under the lock already held and reserved again in the
     * same transaction.
     */
    void delete(UUID recordId);

    /**
     * The scheduled hard delete of docs/architecture/ERD.md §6: removes every record whose 24 hour
     * retention has passed, and returns how many went. The guard already refuses to replay an expired
     * row, so this is what actually stops the table from growing, not what makes retention correct.
     */
    int deleteExpired(Instant now);
}
