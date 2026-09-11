package io.nullnull.identity.application;

import java.util.UUID;

/** BA-030 supplies an owner-scoped non-deleted-trip check while the caller holds the owner lock. */
public interface TripLookup {
    boolean isActiveOwnedTrip(UUID ownerId, UUID tripId);
}
