package io.nullnull.social.infrastructure.persistence;

import io.nullnull.identity.application.OwnerDataEraser;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Erases what an owner saved.
 *
 * <p>Written with V015 rather than after {@code DeletionIT} BA-012-T2 caught it. That check sweeps
 * {@code information_schema} for every table carrying an {@code owner_id} and demands each one be
 * erased by its module or retained for a named reason; {@code trips} reached it unclaimed once, and
 * the lesson recorded in HANDOFF-PROMPT is that a new owner-owned table gets its eraser in the same
 * change as its migration.
 *
 * <p>{@code notifications} joined this eraser with V037. The coverage check finds owner-owned
 * tables by their FOREIGN KEY to {@code owners} rather than by the column's name, so the table was
 * enrolled the moment the migration declared that key - which is why the two land together.
 *
 * <p>BA-082 ANSWERED THE QUESTION THIS JAVADOC LEFT OPEN. Until now {@code posts.author_owner_id}
 * was only cleared, because a curated post is not the deleted owner's content and must not vanish
 * with them. A post the owner WROTE is their content - the reason for keeping the others does not
 * reach it - so it is deleted, along with the cover asset that exists only for it. Other people's
 * saved candidates survive: {@code candidate_sources.post_id} is ON DELETE SET NULL, so a candidate
 * someone kept loses its provenance link rather than the candidate.
 *
 * <p>WHAT THIS PATH CANNOT DO, NAMED. The published image itself is an object in a bucket, and an
 * eraser must not make an external call - least of all inside its transaction. Deleting the post
 * makes the object unreachable (no row carries the URL and the key is a random id), which is not
 * the same as deleted. Closing that gap needs a job or a lifecycle rule over the published prefix
 * and belongs to whoever adds one; BA-082 ships neither, and A-058 records the same shape.
 */
@Component
public class SocialOwnerDataEraser implements OwnerDataEraser {

    private final JdbcClient jdbc;

    public SocialOwnerDataEraser(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "social-saved-posts";
    }

    @Override
    public Set<String> ownerIdTables() {
        return Set.of("saved_posts", "posts", "feed_feedback", "notifications", "upload_intents");
    }

    @Override
    @Transactional
    public void erase(UUID ownerId, Instant deleteBefore) {
        // What the owner saved is their own record of interest, not an audit trail, so it goes
        // immediately and entirely; deleteBefore is for erasers that retain.
        jdbc.sql("DELETE FROM saved_posts WHERE owner_id = ?").param(ownerId).update();
        // Feed feedback is the reader's own behaviour, not an audit record, so erasure is immediate
        // and total rather than waiting for the 90-day retention sweep (ERD §6).
        jdbc.sql("DELETE FROM feed_feedback WHERE owner_id = ?").param(ownerId).update();
        // Notifications are the traveller's own record of what happened to their trips, not an
        // audit trail, so they go immediately rather than waiting for the retention sweep. The
        // owners foreign key cascades too; this covers the soft-delete stage, where the owner row
        // is still there - which is the stage DeletionIT BA-012-T2 measures.
        jdbc.sql("DELETE FROM notifications WHERE owner_id = ?").param(ownerId).update();
        // Tickets are spent or abandoned scraps of an upload attempt; nothing replays from them.
        jdbc.sql("DELETE FROM upload_intents WHERE owner_id = ?").param(ownerId).update();
        // The owner's own posts go, and their covers with them. The asset ids are read BEFORE the
        // posts are deleted because the only thing linking the two is the column that is about to
        // disappear. post_places, saved_posts and feed_feedback cascade from the post.
        //
        // WHAT SURVIVES, AND WHY IT IS NOT AN OVERSIGHT: candidate_sources.post_id is ON DELETE SET
        // NULL (V016), so a candidate another traveller saved from this post keeps existing and
        // loses only its provenance link. That asymmetry is deliberate - the post is the deleted
        // owner's content, the candidate is somebody else's plan - and without this note the next
        // reader makes that column cascade for consistency.
        var coverAssets = jdbc.sql("SELECT cover_asset_id FROM posts"
                        + " WHERE author_owner_id = ? AND cover_asset_id IS NOT NULL")
                .param(ownerId)
                .query(UUID.class)
                .list();
        jdbc.sql("DELETE FROM posts WHERE author_owner_id = ?").param(ownerId).update();
        for (UUID assetId : coverAssets) {
            // By id, not by source: a statement naming USER_UPLOAD would be about every upload in
            // the table rather than this owner's, which on a shared database is a different claim.
            jdbc.sql("DELETE FROM media_assets WHERE id = ?").param(assetId).update();
        }
    }
}
