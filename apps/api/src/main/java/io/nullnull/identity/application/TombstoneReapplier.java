package io.nullnull.identity.application;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Reapplies retained deletion manifests synchronously before the web server starts accepting traffic. */
@Component
public final class TombstoneReapplier implements SmartLifecycle {
    private final DeletionStore deletions;
    private final List<OwnerDataEraser> erasers;
    private final Clock clock;
    private volatile boolean running;
    public TombstoneReapplier(DeletionStore deletions, List<OwnerDataEraser> erasers, Clock clock) {
        this.deletions=deletions;
        this.erasers=erasers.stream().sorted(Comparator.comparing(OwnerDataEraser::name)).toList();
        this.clock=clock;
    }
    @Override public void start() {
        for (var owner : deletions.tombstonedOwners()) {
            for (OwnerDataEraser eraser : erasers) {
                eraser.erase(owner, clock.instant());
            }
        }
        running=true;
    }
    @Override public void stop() { running=false; }
    @Override public boolean isRunning() { return running; }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 2; }
}
