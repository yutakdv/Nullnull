package io.nullnull.identity.application;

import io.nullnull.identity.domain.DemoSession;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SessionStore {
    Optional<DemoSession> find(byte[] hash);
    Optional<DemoSession> lock(UUID id);
    void insert(DemoSession session, byte[] hash);
    void touch(UUID id, Instant seen, Instant expiry);
    void issue(UUID id, UUID sessionId, byte[] hash, Instant now, Instant expiry);
    boolean useCsrf(UUID sessionId, byte[] hash, Instant now);

    /** Revokes every bearer for an owner and removes all of their CSRF credentials. */
    void revokeOwner(UUID ownerId, Instant at);
}
