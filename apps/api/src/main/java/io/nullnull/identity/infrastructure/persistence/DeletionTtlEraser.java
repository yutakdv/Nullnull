package io.nullnull.identity.infrastructure.persistence;

import io.nullnull.identity.application.DeletionStore;
import io.nullnull.operations.application.TtlEraser;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DeletionTtlEraser implements TtlEraser {
    private final DeletionStore deletions;
    public DeletionTtlEraser(DeletionStore deletions) { this.deletions=deletions; }
    @Override public String name() { return "deletion-receipts"; }
    @Override @Transactional
    public int erase(Instant now) {
        return deletions.expireStatusTokens(now) + deletions.hardDeleteEligibleOwners(now);
    }
}
