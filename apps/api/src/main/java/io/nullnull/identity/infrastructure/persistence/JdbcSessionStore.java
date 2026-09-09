package io.nullnull.identity.infrastructure.persistence;

import io.nullnull.identity.application.SessionStore;
import io.nullnull.identity.domain.DemoSession;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcSessionStore implements SessionStore {
    private final JdbcTemplate jdbc;
    public JdbcSessionStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<DemoSession> find(byte[] hash) {
        return jdbc.query("SELECT * FROM demo_sessions WHERE token_hash = ?", this::map, hash)
                .stream().findFirst();
    }
    @Override
    public Optional<DemoSession> lock(UUID id) {
        return jdbc.query("SELECT * FROM demo_sessions WHERE id = ? FOR UPDATE", this::map, id)
                .stream().findFirst();
    }
    @Override
    public void insert(DemoSession s, byte[] hash) {
        jdbc.update("INSERT INTO demo_sessions(id, owner_id, token_hash, created_at, expires_at) VALUES (?,?,?,?,?)",
                s.id(), s.ownerId(), hash, Timestamp.from(s.createdAt()), Timestamp.from(s.expiresAt()));
    }
    @Override
    public void touch(UUID id, Instant seen, Instant expiry) {
        jdbc.update("UPDATE demo_sessions SET last_seen_at = ?, expires_at = ? WHERE id = ?",
                Timestamp.from(seen), Timestamp.from(expiry), id);
    }
    @Override
    public void issue(UUID id, UUID sessionId, byte[] hash, Instant now, Instant expiry) {
        // Caller holds the session lock: concurrent tabs cannot exceed the five-token limit.
        jdbc.update("DELETE FROM demo_session_csrf_tokens WHERE demo_session_id = ? AND expires_at <= ?",
                sessionId, Timestamp.from(now));
        jdbc.update("""
                DELETE FROM demo_session_csrf_tokens WHERE id IN (
                    SELECT id FROM demo_session_csrf_tokens WHERE demo_session_id = ?
                    ORDER BY COALESCE(last_used_at, created_at) DESC, created_at DESC, id DESC OFFSET 4)
                """, sessionId);
        jdbc.update("""
                INSERT INTO demo_session_csrf_tokens(id, demo_session_id, token_hash, created_at, expires_at)
                VALUES (?,?,?,?,?)
                """, id, sessionId, hash, Timestamp.from(now), Timestamp.from(expiry));
    }
    @Override
    public boolean useCsrf(UUID sessionId, byte[] hash, Instant now) {
        return jdbc.update("""
                UPDATE demo_session_csrf_tokens SET last_used_at = ?
                WHERE demo_session_id = ? AND token_hash = ? AND expires_at > ?
                """, Timestamp.from(now), sessionId, hash, Timestamp.from(now)) == 1;
    }
    private DemoSession map(ResultSet row, int index) throws SQLException {
        return new DemoSession(row.getObject("id", UUID.class), row.getObject("owner_id", UUID.class),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("expires_at").toInstant(),
                instant(row, "last_seen_at"), instant(row, "revoked_at"));
    }
    private static Instant instant(ResultSet row, String field) throws SQLException {
        Timestamp value = row.getTimestamp(field);
        return value == null ? null : value.toInstant();
    }
}
