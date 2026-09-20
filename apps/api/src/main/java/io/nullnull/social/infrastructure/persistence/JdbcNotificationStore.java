package io.nullnull.social.infrastructure.persistence;

import io.nullnull.social.application.NotificationStore;
import io.nullnull.social.domain.NotificationType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link NotificationStore} over {@code notifications} (V037). */
@Repository
public class JdbcNotificationStore implements NotificationStore {

    private static final String COLUMNS =
            "id, type, title, body, deep_link, created_at, read_at";

    private final JdbcClient jdbc;

    public JdbcNotificationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UUID insert(UUID id, UUID ownerId, NotificationType type, String title, String body,
            String deepLink, Instant createdAt, Instant expiresAt) {
        jdbc.sql("INSERT INTO notifications (id, owner_id, type, title, body, deep_link,"
                        + " created_at, read_at, expires_at)"
                        + " VALUES (:id, :owner, :type, :title, :body, :deepLink, :createdAt, NULL,"
                        + " :expiresAt)")
                .param("id", id)
                .param("owner", ownerId)
                .param("type", type.name())
                .param("title", title)
                .param("body", body)
                .param("deepLink", deepLink)
                .param("createdAt", Timestamp.from(createdAt))
                .param("expiresAt", Timestamp.from(expiresAt))
                .update();
        return id;
    }

    @Override
    public List<NotificationRow> page(UUID ownerId, PageKey after, int limit, Instant now) {
        // Row-value comparison rather than "created_at < ? OR (created_at = ? AND id < ?)". The two
        // are equivalent and the tuple form is the one the (owner_id, created_at DESC, id DESC)
        // index can walk, so the page does not degrade into a sort as the table grows.
        String keyset = after == null ? "" : " AND (created_at, id) < (:afterCreated, :afterId)";
        var query = jdbc.sql("SELECT " + COLUMNS + " FROM notifications"
                        + " WHERE owner_id = :owner AND expires_at > :now" + keyset
                        + " ORDER BY created_at DESC, id DESC LIMIT :limit")
                .param("owner", ownerId)
                .param("now", Timestamp.from(now))
                .param("limit", limit);
        if (after != null) {
            query = query.param("afterCreated", Timestamp.from(after.createdAt()))
                    .param("afterId", after.id());
        }
        return query.query(JdbcNotificationStore::readRow).list();
    }

    @Override
    public long unreadCount(UUID ownerId, Instant now) {
        Long count = jdbc.sql("SELECT count(*) FROM notifications"
                        + " WHERE owner_id = :owner AND read_at IS NULL AND expires_at > :now")
                .param("owner", ownerId)
                .param("now", Timestamp.from(now))
                .query(Long.class)
                .single();
        return count == null ? 0L : count;
    }

    @Override
    public int markRead(UUID ownerId, UUID notificationId, Instant now, Instant readExpiry) {
        // read_at and expires_at on the right-hand side are the row's OLD values, so a second open
        // keeps the first readAt and the expiry it produced. Re-reading must not extend a life.
        //
        // The owner is part of the predicate rather than checked after a lookup: a row that is not
        // this owner's updates nothing, and so does a row that does not exist. The caller gets the
        // same count for both, which is the whole of BA-085-T5.
        return jdbc.sql("UPDATE notifications SET read_at = COALESCE(read_at, :now),"
                        + " expires_at = CASE WHEN read_at IS NULL"
                        + " THEN LEAST(expires_at, :readExpiry) ELSE expires_at END"
                        + " WHERE id = :id AND owner_id = :owner AND expires_at > :now")
                .param("id", notificationId)
                .param("owner", ownerId)
                .param("now", Timestamp.from(now))
                .param("readExpiry", Timestamp.from(readExpiry))
                .update();
    }

    @Override
    public int markAllRead(UUID ownerId, Instant cutoff, Instant readExpiry) {
        // LEAST over two instants, not created_at + an interval: the caller resolved the 30 days to
        // readExpiry before this statement was built. No interval arithmetic reaches SQL, which is
        // what keeps the stored expiry independent of the session's TimeZone.
        return jdbc.sql("UPDATE notifications SET read_at = :cutoff,"
                        + " expires_at = LEAST(expires_at, :readExpiry)"
                        + " WHERE owner_id = :owner AND read_at IS NULL"
                        + " AND created_at <= :cutoff AND expires_at > :cutoff")
                .param("owner", ownerId)
                .param("cutoff", Timestamp.from(cutoff))
                .param("readExpiry", Timestamp.from(readExpiry))
                .update();
    }

    @Override
    public int deleteExpired(Instant now) {
        return jdbc.sql("DELETE FROM notifications WHERE expires_at <= :now")
                .param("now", Timestamp.from(now))
                .update();
    }

    private static NotificationRow readRow(ResultSet rs, int rowNumber) throws SQLException {
        Timestamp readAt = rs.getTimestamp("read_at");
        return new NotificationRow(
                rs.getObject("id", UUID.class),
                NotificationType.of(rs.getString("type")),
                rs.getString("title"),
                rs.getString("body"),
                rs.getString("deep_link"),
                rs.getTimestamp("created_at").toInstant(),
                readAt == null ? null : readAt.toInstant());
    }
}
