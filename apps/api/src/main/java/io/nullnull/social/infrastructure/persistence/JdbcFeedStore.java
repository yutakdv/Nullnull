package io.nullnull.social.infrastructure.persistence;

import io.nullnull.social.application.FeedStore;
import io.nullnull.social.application.SavedPostState;
import io.nullnull.social.domain.Post;
import io.nullnull.social.domain.PostStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The social module's own tables. No other module reads or writes them. */
@Repository
public class JdbcFeedStore implements FeedStore {

    private final JdbcClient jdbc;

    public JdbcFeedStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Post> publishedPage(long offset, int limit) {
        // published_at DESC, id ASC - the order FeedOrdering defines. Fixed for everyone: no owner
        // state appears in this query, so a saved post cannot move up someone's feed.
        List<Post> posts = jdbc.sql("""
                SELECT id, status, title, body, cover_url, published_at
                  FROM posts
                 WHERE status = 'PUBLISHED'
                 ORDER BY published_at DESC, id ASC
                 LIMIT ? OFFSET ?
                """)
                .params(limit, offset)
                .query((ResultSet row, int index) -> map(row, List.of()))
                .list();
        return hydratePlaces(posts);
    }

    @Override
    public Optional<Post> publishedPost(UUID postId) {
        Optional<Post> post = jdbc.sql("""
                SELECT id, status, title, body, cover_url, published_at
                  FROM posts
                 WHERE id = ? AND status = 'PUBLISHED'
                """)
                .param(postId)
                .query((ResultSet row, int index) -> map(row, List.of()))
                .optional();
        return post.map(found -> hydratePlaces(List.of(found)).get(0));
    }

    @Override
    public Set<UUID> savedPostIds(UUID ownerId, List<UUID> postIds) {
        if (postIds.isEmpty()) {
            return Set.of();
        }
        // One statement for the whole page, not one per card: BA-032-T2 is about an owner's saved
        // state, and a per-card lookup is where a shared cache would be tempting.
        Set<UUID> saved = new HashSet<>(jdbc.sql(
                "SELECT post_id FROM saved_posts WHERE owner_id = ? AND post_id = ANY (?)")
                .params(ownerId, postIds.toArray(UUID[]::new))
                .query(UUID.class)
                .list());
        return Set.copyOf(saved);
    }

    @Override
    public SavedPostState save(UUID ownerId, UUID postId, Instant now) {
        // ON CONFLICT DO NOTHING, then read back. The primary key makes the insert idempotent, and
        // reading afterwards is what returns the ORIGINAL savedAt on a repeat rather than moving it.
        int inserted = jdbc.sql("INSERT INTO saved_posts (owner_id, post_id, created_at)"
                        + " VALUES (?, ?, ?) ON CONFLICT (owner_id, post_id) DO NOTHING")
                .params(ownerId, postId, Timestamp.from(now))
                .update();
        Instant savedAt = jdbc.sql("SELECT created_at FROM saved_posts WHERE owner_id = ? AND post_id = ?")
                .params(ownerId, postId)
                .query(Timestamp.class)
                .single()
                .toInstant();
        return new SavedPostState(postId, true, savedAt, inserted == 0);
    }

    @Override
    public void unsave(UUID ownerId, UUID postId) {
        jdbc.sql("DELETE FROM saved_posts WHERE owner_id = ? AND post_id = ?")
                .params(ownerId, postId)
                .update();
    }

    @Override
    public Map<UUID, Boolean> tripPlaceStates(UUID ownerId, UUID tripId, List<UUID> placeIds) {
        if (tripId == null || placeIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Boolean> states = new HashMap<>();
        // owner_id is in the WHERE clause: a tripId the caller supplied must not reveal anything
        // about a trip they do not own (invariant 11). A foreign trip simply yields no rows.
        jdbc.sql("""
                SELECT item.place_id
                  FROM trip_items item
                  JOIN trips trip ON trip.id = item.trip_id
                 WHERE trip.id = ? AND trip.owner_id = ? AND item.place_id = ANY (?)
                """)
                .params(tripId, ownerId, placeIds.toArray(UUID[]::new))
                .query((ResultSet row, int index) -> {
                    states.put(row.getObject("place_id", UUID.class), true);
                    return null;
                })
                .list();
        return Map.copyOf(states);
    }

    private List<Post> hydratePlaces(List<Post> posts) {
        if (posts.isEmpty()) {
            return posts;
        }
        Map<UUID, List<UUID>> byPost = new HashMap<>();
        jdbc.sql("SELECT post_id, place_id FROM post_places WHERE post_id = ANY (?) ORDER BY position")
                .param(posts.stream().map(Post::id).toArray(UUID[]::new))
                .query((ResultSet row, int index) -> {
                    byPost.computeIfAbsent(row.getObject("post_id", UUID.class),
                            key -> new ArrayList<>()).add(row.getObject("place_id", UUID.class));
                    return null;
                })
                .list();
        List<Post> hydrated = new ArrayList<>(posts.size());
        for (Post post : posts) {
            hydrated.add(new Post(post.id(), post.status(), post.title(), post.excerpt(), post.body(),
                    post.coverUrl(), post.publishedAt(), byPost.getOrDefault(post.id(), List.of())));
        }
        return List.copyOf(hydrated);
    }

    private static Post map(ResultSet row, List<UUID> placeIds) throws SQLException {
        Timestamp published = row.getTimestamp("published_at");
        String body = row.getString("body");
        return new Post(row.getObject("id", UUID.class), PostStatus.of(row.getString("status")),
                row.getString("title"), excerpt(body), body, row.getString("cover_url"),
                published == null ? null : published.toInstant(), placeIds);
    }

    /**
     * The card's excerpt, derived from the body rather than stored.
     *
     * <p>A stored excerpt is a second copy of the same text that drifts when the body is edited, and
     * the contract types it as nullable precisely because a post need not have one. Deriving keeps
     * one source of truth; a body shorter than the cut has no excerpt at all rather than a copy of
     * itself, which is what lets the "post with no excerpt" card state exist.
     */
    private static String excerpt(String body) {
        if (body == null) {
            return null;
        }
        String flattened = body.replaceAll("\\s+", " ").trim();
        if (flattened.length() <= EXCERPT_LENGTH) {
            return null;
        }
        return flattened.substring(0, EXCERPT_LENGTH).trim() + "…";
    }

    private static final int EXCERPT_LENGTH = 120;
}
