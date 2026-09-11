package io.nullnull.identity.application;

import java.time.Instant;
import java.util.UUID;

public record DeletionRecord(UUID id, UUID ownerId, String status, int attemptCount,
        String failureCode, Instant tokenExpiresAt, Instant requestedAt, Instant startedAt,
        Instant completedAt, Instant updatedAt) {
}
