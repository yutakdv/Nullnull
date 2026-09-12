package io.nullnull.analytics.infrastructure.persistence;

import io.nullnull.identity.application.OwnerDataEraser;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Erases an owner's analytics events.
 *
 * <p>Written with V017, not after a check caught it. At the soft-delete stage the owner row still
 * exists, so the {@code ON DELETE CASCADE} on {@code owner_id} does not fire and this eraser is the
 * only thing that removes these rows - the same gap {@code OwnerDataErasureIT} found for the trip
 * aggregate. The cascade covers only the later scrub stage.
 *
 * <p>Everything goes, immediately. There is no analytics interest that outlives a deletion request:
 * the rows measure a product, and a user who asked to be erased did not agree to keep measuring it.
 * {@code deleteBefore} is for erasers that retain, and this one does not.
 */
@Component
public class AnalyticsOwnerDataEraser implements OwnerDataEraser {

    private final JdbcClient jdbc;

    public AnalyticsOwnerDataEraser(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "analytics-events";
    }

    @Override
    public Set<String> ownerIdTables() {
        return Set.of("analytics_events");
    }

    @Override
    @Transactional
    public void erase(UUID ownerId, Instant deleteBefore) {
        jdbc.sql("DELETE FROM analytics_events WHERE owner_id = ?").param(ownerId).update();
    }
}
