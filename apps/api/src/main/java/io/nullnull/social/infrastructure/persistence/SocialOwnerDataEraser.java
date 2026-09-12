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
 * <p>{@code posts.author_owner_id} is NOT named here. It is nullable, no P0 row sets it, and its
 * foreign key is ON DELETE SET NULL - a curated post is not the deleted owner's content and must
 * not disappear with them. When BA-082 lets an owner author a post, that slice decides whether
 * their posts are erased or de-authored, and adds the row here.
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
        return Set.of("saved_posts", "posts");
    }

    @Override
    @Transactional
    public void erase(UUID ownerId, Instant deleteBefore) {
        // What the owner saved is their own record of interest, not an audit trail, so it goes
        // immediately and entirely; deleteBefore is for erasers that retain.
        jdbc.sql("DELETE FROM saved_posts WHERE owner_id = ?").param(ownerId).update();
        // Curated posts survive. This clears the authorship pointer for any row that ever gains one
        // so the table can never hold a deleted owner's identifier, which is what ownerIdTables
        // above is claiming coverage of.
        jdbc.sql("UPDATE posts SET author_owner_id = NULL WHERE author_owner_id = ?")
                .param(ownerId).update();
    }
}
