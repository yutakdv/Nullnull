package io.nullnull.identity.infrastructure.persistence;

import io.nullnull.identity.application.DeletionRecord;
import io.nullnull.identity.application.DeletionStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcDeletionStore implements DeletionStore {
    private final JdbcTemplate jdbc;
    public JdbcDeletionStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void create(DeletionRecord r, byte[] tokenHash, UUID tombstoneId,
            Instant deleteBefore, Instant retainUntil, String scopeHash) {
        jdbc.update("""
                INSERT INTO deletion_requests(id,owner_id,status_token_hash,status,attempt_count,
                    status_token_expires_at,requested_at,updated_at) VALUES (?,?,?,?,?,?,?,?)
                """, r.id(), r.ownerId(), tokenHash, r.status(), r.attemptCount(),
                Timestamp.from(r.tokenExpiresAt()), Timestamp.from(r.requestedAt()), Timestamp.from(r.updatedAt()));
        jdbc.update("""
                INSERT INTO deletion_tombstones(id,deletion_request_id,owner_id,delete_before,
                    retain_until,scope_hash,created_at) VALUES (?,?,?,?,?,?,?)
                """, tombstoneId, r.id(), r.ownerId(), Timestamp.from(deleteBefore),
                Timestamp.from(retainUntil), scopeHash, Timestamp.from(r.requestedAt()));
    }

    @Override
    public Optional<DeletionRecord> find(UUID id) {
        return jdbc.query("SELECT * FROM deletion_requests WHERE id = ?", this::map, id).stream().findFirst();
    }

    @Override
    public boolean hasStatusTokenHash(UUID id, byte[] hash) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM deletion_requests"
                + " WHERE id=? AND status_token_hash=?)", Boolean.class, id, hash));
    }

    @Override
    public void markRunning(UUID id, int attempt, Instant now) {
        jdbc.update("UPDATE deletion_requests SET status='RUNNING',attempt_count=?,"
                + "started_at=COALESCE(started_at,?),updated_at=? WHERE id=?",
                attempt, Timestamp.from(now), Timestamp.from(now), id);
    }

    @Override
    public void markCompleted(UUID id, Instant now) {
        jdbc.update("UPDATE deletion_requests SET status='COMPLETED',failure_code=NULL,"
                + "completed_at=?,updated_at=? WHERE id=?", Timestamp.from(now), Timestamp.from(now), id);
    }

    @Override
    public void markFailed(UUID id, int attempt, String status, String code, Instant now) {
        jdbc.update("UPDATE deletion_requests SET status=?,attempt_count=?,failure_code=?,"
                + "completed_at=CASE WHEN ?='FAILED' THEN ? ELSE NULL END,updated_at=? WHERE id=?",
                status, attempt, code, status, Timestamp.from(now), Timestamp.from(now), id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> tombstonedOwners() {
        return jdbc.queryForList("SELECT owner_id FROM deletion_tombstones ORDER BY owner_id", UUID.class);
    }

    @Override
    public int expireStatusTokens(Instant now) {
        return jdbc.update("UPDATE deletion_requests SET status_token_hash=NULL"
                + " WHERE status_token_hash IS NOT NULL AND status_token_expires_at <= ?", Timestamp.from(now));
    }

    @Override
    public int hardDeleteEligibleOwners(Instant now) {
        List<UUID> owners = jdbc.queryForList("""
                SELECT owner_id FROM deletion_tombstones t
                WHERE retain_until <= ?
                  AND NOT EXISTS (SELECT 1 FROM demo_sessions s WHERE s.owner_id=t.owner_id)
                  AND NOT EXISTS (SELECT 1 FROM idempotency_records i WHERE i.owner_id=t.owner_id)
                ORDER BY owner_id LIMIT 1000
                """, UUID.class, Timestamp.from(now));
        int removed = 0;
        for (UUID owner : owners) {
            jdbc.update("DELETE FROM deletion_tombstones WHERE owner_id=?", owner);
            jdbc.update("DELETE FROM deletion_requests WHERE owner_id=?", owner);
            removed += jdbc.update("DELETE FROM owners WHERE id=? AND deleted_at IS NOT NULL", owner);
        }
        return removed;
    }

    private DeletionRecord map(ResultSet row, int ignored) throws SQLException {
        return new DeletionRecord(row.getObject("id", UUID.class), row.getObject("owner_id", UUID.class),
                row.getString("status"), row.getInt("attempt_count"), row.getString("failure_code"),
                row.getTimestamp("status_token_expires_at").toInstant(),
                row.getTimestamp("requested_at").toInstant(), instant(row,"started_at"),
                instant(row,"completed_at"), row.getTimestamp("updated_at").toInstant());
    }
    private static Instant instant(ResultSet row, String name) throws SQLException {
        Timestamp value = row.getTimestamp(name); return value == null ? null : value.toInstant();
    }
}
