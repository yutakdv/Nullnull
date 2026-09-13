package io.nullnull.importer.infrastructure.persistence;

import io.nullnull.identity.application.OwnerDataEraser;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Erases an owner's import drafts.
 *
 * <p>Written with V028 rather than after a check caught it, though the check would have: {@code
 * DeletionIT.ownerIdTableCoverageIsExplicit} finds every table with a FOREIGN KEY to {@code owners}
 * and requires each one to be either swept by its module or listed with a reason for keeping it.
 * {@code itinerary_import_drafts} has that key, so creating the table without this class turns that
 * test red - which is the point of it.
 *
 * <p>Everything goes, immediately, and nothing is retained. A draft is the traveller's own itinerary
 * in structured form; it carries no receipt anyone replays and no obligation that outlives the
 * deletion request. {@code deleteBefore} is for erasers that keep rows a while longer, and this is
 * not one. Drafts expire in 24 hours anyway - deletion simply does not wait for that.
 *
 * <p>Order against the other erasers is not load-bearing, which is worth saying because {@code
 * DeleteOwnerDataHandler} runs them sorted by {@link #name()} and that would otherwise make a
 * rename a silent behaviour change. A confirmed draft points at its trip with {@code ON DELETE
 * CASCADE}, so if the trip aggregate is erased first its drafts go with it; an unconfirmed draft
 * points at no trip at all. Either sequence ends with no rows, and neither leaves a foreign key
 * pointing at something that has gone.
 */
@Component
public class ImporterOwnerDataEraser implements OwnerDataEraser {

    private final JdbcClient jdbc;

    public ImporterOwnerDataEraser(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "import-drafts";
    }

    @Override
    public Set<String> ownerIdTables() {
        return Set.of("itinerary_import_drafts");
    }

    @Override
    @Transactional
    public void erase(UUID ownerId, Instant deleteBefore) {
        jdbc.sql("DELETE FROM itinerary_import_drafts WHERE owner_id = ?").param(ownerId).update();
    }
}
