package io.nullnull.identity.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeletionStore {
    void create(DeletionRecord request, byte[] statusTokenHash, UUID tombstoneId,
            Instant deleteBefore, Instant retainUntil, String scopeHash);
    Optional<DeletionRecord> find(UUID requestId);
    boolean hasStatusTokenHash(UUID requestId, byte[] hash);
    void markRunning(UUID requestId, int attempt, Instant now);
    void markCompleted(UUID requestId, Instant now);
    void markFailed(UUID requestId, int attempt, String status, String failureCode, Instant now);
    List<UUID> tombstonedOwners();
    int expireStatusTokens(Instant now);
    int hardDeleteEligibleOwners(Instant now);
}
