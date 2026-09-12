package io.nullnull.trip.infrastructure.persistence;

import io.nullnull.identity.application.TripLookup;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * BA-030's production answer to identity's trip port, replacing the placeholder that could only ever
 * say no.
 *
 * <p>"Active" here means the trip exists and this owner holds it, not that its status is ACTIVE: a
 * DRAFT trip is a perfectly good active trip to point the profile at, and archiving is the state
 * that should stop it. ARCHIVED is therefore the one status excluded.
 *
 * <p>Deletion needs no clause. A deleted trip is a removed row, not a flag, and the foreign key on
 * owners.active_trip_id clears the pointer when that happens.
 */
@Component
public class JdbcTripLookup implements TripLookup {

    private final JdbcClient jdbc;

    public JdbcTripLookup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isActiveOwnedTrip(UUID ownerId, UUID tripId) {
        if (ownerId == null || tripId == null) {
            return false;
        }
        return jdbc.sql("SELECT count(*) FROM trips WHERE id = ? AND owner_id = ? AND status <> 'ARCHIVED'")
                .params(tripId, ownerId)
                .query(Integer.class)
                .single() > 0;
    }
}
