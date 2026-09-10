package io.nullnull.identity.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TombstoneReapplierTest {
    @Test void startupFailsWhenAnyRestoredOwnerCannotBeErased() {
        DeletionStore store = mock(DeletionStore.class);
        UUID owner = UUID.randomUUID();
        when(store.tombstonedOwners()).thenReturn(List.of(owner));
        OwnerDataEraser failing = new OwnerDataEraser() {
            public String name() { return "failing"; }
            public Set<String> ownerIdTables() { return Set.of(); }
            public void erase(UUID ignored, Instant cutoff) { throw new IllegalStateException("failed"); }
        };
        var lifecycle = new TombstoneReapplier(store, List.of(failing),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        assertThatThrownBy(lifecycle::start).isInstanceOf(IllegalStateException.class);
        assertThat(lifecycle.isRunning()).isFalse();
        assertThat(lifecycle.getPhase()).isLessThan(Integer.MAX_VALUE - 1);
    }
}
