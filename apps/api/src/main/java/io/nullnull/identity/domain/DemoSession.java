package io.nullnull.identity.domain;

import java.time.Instant;
import java.util.UUID;

/** Persisted session metadata; bearer values never belong in a domain record. */
public record DemoSession(UUID id, UUID ownerId, Instant createdAt, Instant expiresAt,
        Instant lastSeenAt, Instant revokedAt) { }
