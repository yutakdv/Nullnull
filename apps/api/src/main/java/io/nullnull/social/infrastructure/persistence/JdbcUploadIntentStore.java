package io.nullnull.social.infrastructure.persistence;

import io.nullnull.social.application.UploadIntentStore;
import io.nullnull.social.domain.UploadIntent;
import io.nullnull.social.domain.UploadIntentStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link UploadIntentStore} over {@code upload_intents} (V045). */
@Repository
public class JdbcUploadIntentStore implements UploadIntentStore {

    private static final String COLUMNS = "id, owner_id, status, content_type, content_length,"
            + " checksum_sha256, quarantine_key, created_at, expires_at, consumed_at";

    private final JdbcClient jdbc;

    public JdbcUploadIntentStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(UploadIntent intent) {
        jdbc.sql("INSERT INTO upload_intents (" + COLUMNS + ")"
                        + " VALUES (:id, :owner, :status, :contentType, :contentLength,"
                        + " :checksum, :key, :createdAt, :expiresAt, NULL)")
                .param("id", intent.id())
                .param("owner", intent.ownerId())
                .param("status", intent.status().name())
                .param("contentType", intent.contentType())
                .param("contentLength", intent.contentLength())
                .param("checksum", intent.checksumSha256())
                .param("key", intent.quarantineKey())
                .param("createdAt", Timestamp.from(intent.createdAt()))
                .param("expiresAt", Timestamp.from(intent.expiresAt()))
                .update();
    }

    @Override
    public Optional<UploadIntent> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM upload_intents WHERE id = :id")
                .param("id", id)
                .query(JdbcUploadIntentStore::map)
                .optional();
    }

    @Override
    public boolean claim(UUID id, Instant at) {
        // The WHERE names this row and requires it to still be PENDING, so the race is settled by
        // the database rather than by the order two application threads happened to read in. The
        // loser of two concurrent createPost calls never reaches the object store, which matters
        // because the winner deletes the original as part of finishing.
        return update(id, UploadIntentStatus.CONSUMED, at, "PENDING");
    }

    @Override
    public boolean reject(UUID id, Instant at) {
        // From CONSUMED, not from PENDING: rejection is what happens to an intent this caller has
        // already claimed, so a row that never got claimed cannot be rejected out from under one.
        return update(id, UploadIntentStatus.REJECTED, at, "CONSUMED");
    }

    private boolean update(UUID id, UploadIntentStatus to, Instant at, String from) {
        int updated = jdbc.sql("UPDATE upload_intents SET status = :status, consumed_at = :at"
                        + " WHERE id = :id AND status = :from")
                .param("status", to.name())
                .param("at", Timestamp.from(at))
                .param("id", id)
                .param("from", from)
                .update();
        return updated == 1;
    }

    private static UploadIntent map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp consumedAt = rs.getTimestamp("consumed_at");
        return new UploadIntent(
                rs.getObject("id", UUID.class),
                rs.getObject("owner_id", UUID.class),
                UploadIntentStatus.of(rs.getString("status")),
                rs.getString("content_type"),
                rs.getLong("content_length"),
                rs.getString("checksum_sha256"),
                rs.getString("quarantine_key"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                consumedAt == null ? null : consumedAt.toInstant());
    }
}
