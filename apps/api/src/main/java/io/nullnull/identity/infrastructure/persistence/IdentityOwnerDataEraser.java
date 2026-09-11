package io.nullnull.identity.infrastructure.persistence;

import io.nullnull.identity.application.OwnerDataEraser;
import io.nullnull.identity.application.OwnerRepository;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class IdentityOwnerDataEraser implements OwnerDataEraser {
    private final OwnerRepository owners;
    public IdentityOwnerDataEraser(OwnerRepository owners) { this.owners = owners; }
    @Override public String name() { return "identity-owner-profile"; }
    @Override public Set<String> ownerIdTables() { return Set.of(); }
    @Override @Transactional
    public void erase(UUID ownerId, Instant deleteBefore) {
        owners.lockAny(ownerId).ifPresent(ignored -> owners.scrubDeleted(ownerId));
    }
}
