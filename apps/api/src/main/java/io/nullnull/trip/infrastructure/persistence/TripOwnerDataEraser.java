package io.nullnull.trip.infrastructure.persistence;

import io.nullnull.identity.application.OwnerDataEraser;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Erases the trips an owner holds. The trip module owns its own tables, which is what the deletion
 * SPI is for: identity never writes trip rows.
 *
 * <p>Only {@code trips} is named. {@code trip_interests} and {@code trip_revisions} have no
 * {@code owner_id} of their own and are removed by the foreign key cascade, so naming them here
 * would claim coverage this class does not itself provide.
 *
 * <p>Deleting the trips also clears {@code owners.active_trip_id}, because that foreign key is
 * ON DELETE SET NULL - the trip pointer cannot outlive the trip it points at.
 *
 * <p>{@code deleteBefore} is ignored on purpose. A trip is the user's own content, not an audit or
 * replay record, so there is no window during which part of it is kept: erasure is immediate and
 * total (docs/security/PRIVACY_REQUIREMENTS.md). The parameter exists for erasers that DO retain.
 */
@Component
public class TripOwnerDataEraser implements OwnerDataEraser {

    private final JdbcClient jdbc;

    public TripOwnerDataEraser(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "trip-owned-aggregates";
    }

    @Override
    public Set<String> ownerIdTables() {
        return Set.of("trips");
    }

    @Override
    @Transactional
    public void erase(UUID ownerId, Instant deleteBefore) {
        jdbc.sql("DELETE FROM trips WHERE owner_id = ?").param(ownerId).update();
    }
}
