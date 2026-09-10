package io.nullnull.identity.application;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Module-owned deletion SPI. Implementations may touch only tables owned by their module. */
public interface OwnerDataEraser {
    String name();
    Set<String> ownerIdTables();
    void erase(UUID ownerId, Instant deleteBefore);
}
