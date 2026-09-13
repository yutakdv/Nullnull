package io.nullnull.optimization.infrastructure.persistence;

import io.nullnull.identity.application.OwnerDataEraser;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Erases the optimization runs an owner asked for.
 *
 * <p>The trip eraser would already take most of them: {@code optimization_runs.trip_id} cascades, so
 * deleting a trip deletes its runs. This exists because the coverage check in {@code DeletionIT} asks
 * a sharper question than "will the row go away eventually" - it finds every table with a foreign key
 * to {@code owners} and requires a module to claim it or a reason to be written down. A run carries
 * {@code requested_by_owner_id}, so it is this module's to claim, and claiming it by actually
 * deleting is better than relying on another module's cascade running first.
 *
 * <p>{@code deleteBefore} is ignored: a run is the traveller's own activity, not an audit record, so
 * there is no window in which part of it is kept.
 */
@Component
public class OptimizationOwnerDataEraser implements OwnerDataEraser {

    private final JdbcClient jdbc;

    public OptimizationOwnerDataEraser(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "optimization-runs";
    }

    @Override
    public Set<String> ownerIdTables() {
        // Only the table this class deletes from. optimization_run_snapshot_sets has no owner column
        // and goes with the run through its foreign key, so naming it would claim coverage this class
        // does not itself provide.
        return Set.of("optimization_runs");
    }

    @Override
    @Transactional
    public void erase(UUID ownerId, Instant deleteBefore) {
        jdbc.sql("DELETE FROM optimization_runs WHERE requested_by_owner_id = ?")
                .param(ownerId)
                .update();
    }
}
