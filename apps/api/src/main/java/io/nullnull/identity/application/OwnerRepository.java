package io.nullnull.identity.application;

import io.nullnull.identity.domain.Owner;
import java.util.Optional;
import java.util.UUID;

/**
 * The owner port every later slice depends on. It speaks domain types only; the JPA entity stays
 * inside identity.infrastructure.
 */
public interface OwnerRepository {

    /** Inserts a newly created owner. Joins the caller's transaction when there is one. */
    Owner create(Owner owner);

    Optional<Owner> findById(UUID id);

    /** Applies preference fields only; caller holds the owner lifecycle lock in this transaction. */
    Owner updatePreferences(Owner owner);

    /**
     * Takes the owner-lifecycle lock, the first lock in the order
     * {@code owner lifecycle -> idempotency reservation -> trip -> run/decision -> child rows}
     * (docs/architecture/SYSTEM_ARCHITECTURE.md §19.1), and returns the owner only while it is alive.
     * Empty means the owner does not exist or is already soft-deleted, so the command must not run.
     * Requires an existing transaction: a lock without one would be meaningless.
     */
    Optional<Owner> lockAlive(UUID id);
}
