package io.nullnull.social.infrastructure.persistence;

import io.nullnull.social.application.FeedStore;
import io.nullnull.social.application.PostLockTimeoutException;
import io.nullnull.social.application.SavedPostState;
import io.nullnull.social.domain.FeedFeedbackAction;
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
    public List<Post> publishedPage(PageKey after, int limit) {
        // published_at DESC, id ASC - the order FeedOrdering defines. Fixed for everyone: no owner
        // state appears in this query, so a saved post cannot move up someone's feed.
        StringBuilder sql = new StringBuilder("""
                SELECT id, status, title, body, cover_url, cover_asset_id, published_at
                  FROM posts
                 WHERE status = 'PUBLISHED'
                """);
        List<Object> parameters = new ArrayList<>();
        if (after != null) {
            // Resume after that post, rather than skipping a count of rows: a curator publishing
            // something ahead of the reader must not push what they have already read back at them.
            // The tie branch follows id ASC because published_at alone is not a total order.
            sql.append(" AND (published_at < ? OR (published_at = ? AND id > ?))");
            Timestamp at = Timestamp.from(after.publishedAt());
            parameters.add(at);
            parameters.add(at);
            parameters.add(after.postId());
        }
        sql.append(" ORDER BY published_at DESC, id ASC LIMIT ?");
        parameters.add(limit);
        List<Post> posts = jdbc.sql(sql.toString()).params(parameters)
                .query((ResultSet row, int index) -> map(row, List.of()))
                .list();
        return hydratePlaces(posts);
    }

    @Override
    public Optional<Post> publishedPost(UUID postId) {
        Optional<Post> post = jdbc.sql("""
                SELECT id, status, title, body, cover_url, cover_asset_id, published_at
                  FROM posts
                 WHERE id = ? AND status = 'PUBLISHED'
                """)
                .param(postId)
                .query((ResultSet row, int index) -> map(row, List.of()))
                .optional();
        return post.map(found -> hydratePlaces(List.of(found)).get(0));
    }

    @Override
    public boolean lockPublishedPost(UUID postId) {
        return jdbc.sql("SELECT id FROM posts WHERE id = ? AND status = 'PUBLISHED' FOR SHARE")
                .param(postId).query(UUID.class).optional().isPresent();
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
                    post.coverUrl(), post.coverAssetId(), post.publishedAt(),
                    byPost.getOrDefault(post.id(), List.of())));
        }
        return List.copyOf(hydrated);
    }

    @Override
    public boolean postExists(UUID postId) {
        return jdbc.sql("SELECT count(*) FROM posts WHERE id = ?").param(postId)
                .query(Integer.class).single() > 0;
    }

    @Override
    public UUID insertFirstPartyCover(UUID assetId, String url, String alt, String checksum,
            Instant now) {
        UUID licenceId = jdbc.sql(
                        "SELECT id FROM asset_licenses WHERE source_code = 'NULLNULL_FIRST_PARTY'")
                .query(UUID.class)
                .optional()
                // Named rather than left as an empty Optional: the cause is never visible from the
                // query, and a run that reached this line has a plan file that looked fine.
                .orElseThrow(() -> new IllegalStateException(
                        "the seeded NULLNULL_FIRST_PARTY licence (V021) is missing"));
        jdbc.sql("""
                INSERT INTO media_assets (id, asset_license_id, source_external_id, origin_url,
                                          served_url, checksum, media_type, alt_text, license_checked_at)
                VALUES (?, ?, ?, ?, ?, ?, 'IMAGE', ?, ?)
                """)
                .params(assetId, licenceId, "curated-" + assetId, url, url, checksum, alt,
                        Timestamp.from(now))
                .update();
        return assetId;
    }

    @Override
    public UUID insertUserUploadCover(UUID assetId, String url, String sourceExternalId, String alt,
            String checksum, Instant now) {
        UUID licenceId = jdbc.sql(
                        "SELECT id FROM asset_licenses WHERE source_code = 'USER_UPLOAD'")
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "the seeded USER_UPLOAD licence (V045) is missing"));
        // origin_url and served_url carry the same value, which is what V021's first-party covers
        // already do (PostCovers): an asset that originates with us has no external place it came
        // from, and the alternative - recording the quarantine key - would name a private path that
        // has been deleted by the time anybody could read this row.
        jdbc.sql("""
                INSERT INTO media_assets (id, asset_license_id, source_external_id, origin_url,
                                          served_url, checksum, media_type, alt_text, license_checked_at)
                VALUES (?, ?, ?, ?, ?, ?, 'IMAGE', ?, ?)
                """)
                .params(assetId, licenceId, sourceExternalId, url, url, checksum, alt,
                        Timestamp.from(now))
                .update();
        return assetId;
    }

    @Override
    public void insertAuthoredDraftPost(UUID postId, UUID authorOwnerId, String title, String body,
            String coverUrl, UUID coverAssetId, Instant now) {
        jdbc.sql("""
                INSERT INTO posts (id, author_owner_id, status, title, body, cover_url,
                                   cover_asset_id, created_at, updated_at)
                VALUES (?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?)
                """)
                .params(postId, authorOwnerId, title, body, coverUrl, coverAssetId,
                        Timestamp.from(now), Timestamp.from(now))
                .update();
    }

    @Override
    public void insertDraftPost(UUID postId, String title, String body, String coverUrl,
            UUID coverAssetId, Instant now) {
        jdbc.sql("""
                INSERT INTO posts (id, status, title, body, cover_url, cover_asset_id, created_at,
                                   updated_at)
                VALUES (?, 'DRAFT', ?, ?, ?, ?, ?, ?)
                """)
                .params(postId, title, body, coverUrl, coverAssetId, Timestamp.from(now),
                        Timestamp.from(now))
                .update();
    }

    @Override
    public void linkPostPlace(UUID postId, UUID placeId, int position, String mentionType) {
        jdbc.sql("""
                INSERT INTO post_places (post_id, place_id, position, mention_type)
                VALUES (?, ?, ?, ?)
                """)
                .params(postId, placeId, position, mentionType)
                .update();
    }

    @Override
    public void publishPost(UUID postId, Instant publishedAt, Instant now) {
        jdbc.sql("""
                UPDATE posts SET status = 'PUBLISHED', published_at = ?, updated_at = ?
                 WHERE id = ? AND status = 'DRAFT'
                """)
                .params(Timestamp.from(publishedAt), Timestamp.from(now), postId)
                .update();
    }

    @Override
    public int withdrawIfPublished(UUID postId, Instant now) {
        // The condition is the decision, not a repeat of one made elsewhere: PostgreSQL re-checks it
        // on the row it locks, so of two withdrawals that reach the same post at once, the second
        // finds it HIDDEN and changes nothing.
        try {
            return jdbc.sql("""
                    UPDATE posts SET status = 'HIDDEN', published_at = NULL, updated_at = ?
                     WHERE id = ? AND status = 'PUBLISHED'
                    """)
                    .params(Timestamp.from(now), postId)
                    .update();
        } catch (RuntimeException failure) {
            // The caller bounds the wait (LockWaitLimit); this names its expiry, which the driver
            // reports as an uncategorised SQL error - the identity and operations modules translate
            // the same SQLState for the same reason.
            if (isLockTimeout(failure)) {
                throw new PostLockTimeoutException("Timed out waiting for the post's row lock.", failure);
            }
            throw failure;
        }
    }

    /** PostgreSQL {@code lock_not_available}: raised when the {@code lock_timeout} bound expires. */
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private static boolean isLockTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && LOCK_NOT_AVAILABLE.equals(sql.getSQLState())) {
                return true;
            }
            if (cause.getCause() == cause) {
                return false;
            }
        }
        return false;
    }

    @Override
    public Optional<PostStatus> postStatus(UUID postId) {
        return jdbc.sql("SELECT status FROM posts WHERE id = ?")
                .param(postId)
                .query(String.class)
                .optional()
                .map(PostStatus::of);
    }

    @Override
    public boolean recordFeedback(UUID id, UUID ownerId, UUID postId, FeedFeedbackAction action,
            Instant occurredAt, long occurredMinute, Instant receivedAt) {
        // ON CONFLICT DO NOTHING on the minute key, so a repeat inside the same minute is one row
        // and not a caught exception. The distinction matters to the caller only as "was this new";
        // either way the contract answers 204, because the reader did what they did.
        return jdbc.sql("""
                INSERT INTO feed_feedback (id, owner_id, post_id, action, occurred_at, occurred_minute,
                                           received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (owner_id, post_id, action, occurred_minute) DO NOTHING
                """)
                .params(id, ownerId, postId, action.name(), Timestamp.from(occurredAt), occurredMinute,
                        Timestamp.from(receivedAt))
                .update() == 1;
    }

    private static Post map(ResultSet row, List<UUID> placeIds) throws SQLException {
        Timestamp published = row.getTimestamp("published_at");
        String body = row.getString("body");
        return new Post(row.getObject("id", UUID.class), PostStatus.of(row.getString("status")),
                row.getString("title"), excerpt(body), body, row.getString("cover_url"),
                row.getObject("cover_asset_id", UUID.class),
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
