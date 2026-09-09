package io.nullnull.identity.infrastructure.persistence;

import io.nullnull.identity.application.TripLookup;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** No trip table exists before BA-030. No supplied identifier can be selected yet. */
@Component
public class UnavailableTripLookup implements TripLookup {
    @Override public boolean isActiveOwnedTrip(UUID ownerId, UUID tripId) { return false; }
}
