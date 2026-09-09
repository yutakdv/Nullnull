package io.nullnull.identity.infrastructure.persistence;

import io.nullnull.identity.application.LockWaitLimit;
import io.nullnull.operations.application.TtlEraser;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Identity retention, serialized with session activity by owner then session locks. */
@Component
public class SessionTtlEraser implements TtlEraser {
    private final JdbcTemplate jdbc;
    private final LockWaitLimit locks;
    private final Duration lockTimeout;
    public SessionTtlEraser(JdbcTemplate jdbc, LockWaitLimit locks,
            @org.springframework.beans.factory.annotation.Value("${nullnull.idempotency.lock-timeout}") Duration lockTimeout) {
        this.jdbc = jdbc; this.locks = locks; this.lockTimeout = lockTimeout;
    }
    @Override public String name() { return "demo-sessions"; }
    @Override @Transactional
    public int erase(Instant now) {
        locks.applyToCurrentTransaction(lockTimeout);
        int removed = 0;
        var owners = jdbc.queryForList("""
                SELECT DISTINCT owner_id FROM demo_sessions
                WHERE (last_seen_at IS NULL AND revoked_at IS NULL AND created_at <= ?) OR revoked_at <= ?
                ORDER BY owner_id LIMIT 1000
                """, UUID.class, Timestamp.from(now.minus(Duration.ofMinutes(15))),
                Timestamp.from(now.minus(Duration.ofDays(30))));
        for (UUID owner : owners) {
            jdbc.queryForList("SELECT id FROM owners WHERE id = ? FOR UPDATE", UUID.class, owner);
            boolean orphanOwner = Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM demo_sessions WHERE owner_id = ?
                        AND last_seen_at IS NULL AND revoked_at IS NULL AND created_at <= ?)
                    """, Boolean.class, owner, Timestamp.from(now.minus(Duration.ofMinutes(15)))));
            removed += jdbc.update("""
                    DELETE FROM demo_session_csrf_tokens WHERE demo_session_id IN
                    (SELECT id FROM demo_sessions WHERE owner_id = ? AND
                    ((last_seen_at IS NULL AND revoked_at IS NULL AND created_at <= ?) OR revoked_at <= ?))
                    """, owner, Timestamp.from(now.minus(Duration.ofMinutes(15))),
                    Timestamp.from(now.minus(Duration.ofDays(30))));
            removed += jdbc.update("""
                    DELETE FROM demo_sessions WHERE owner_id = ? AND
                    ((last_seen_at IS NULL AND revoked_at IS NULL AND created_at <= ?) OR revoked_at <= ?)
                    """, owner, Timestamp.from(now.minus(Duration.ofMinutes(15))),
                    Timestamp.from(now.minus(Duration.ofDays(30))));
            if (orphanOwner) {
                removed += jdbc.update("""
                        DELETE FROM owners WHERE id = ? AND kind = 'ANONYMOUS' AND deleted_at IS NULL
                        AND NOT EXISTS (SELECT 1 FROM demo_sessions WHERE owner_id = ?)
                        """, owner, owner);
            }
        }
        removed += jdbc.update("DELETE FROM demo_session_csrf_tokens WHERE expires_at <= ?", Timestamp.from(now));
        return removed;
    }
}
