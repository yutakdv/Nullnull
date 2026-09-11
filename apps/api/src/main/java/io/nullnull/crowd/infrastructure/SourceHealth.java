package io.nullnull.crowd.infrastructure;

import io.nullnull.crowd.application.SourceRegistryStore;
import io.nullnull.operations.application.ReadinessProbe;
import java.time.Instant;

/** DB-only optional probe. It never calls a provider from the readiness request thread. */
public final class SourceHealth implements ReadinessProbe {

    private final String sourceCode;
    private final SourceRegistryStore store;

    public SourceHealth(String sourceCode, SourceRegistryStore store) {
        this.sourceCode = sourceCode;
        this.store = store;
    }

    @Override
    public String name() {
        return "source:" + sourceCode;
    }

    @Override
    public boolean required() {
        return false;
    }

    @Override
    public ProbeResult probe(Instant checkedAt) {
        var source = store.findByCode(sourceCode);
        if (source.isEmpty()) {
            return new ProbeResult(ProbeStatus.DEGRADED, checkedAt, "registry entry missing");
        }
        if (!source.orElseThrow().collectionEnabled()) {
            return new ProbeResult(ProbeStatus.READY, checkedAt, "disabled");
        }
        SourceRegistryStore.SourceCondition condition = store.conditionAt(sourceCode, checkedAt);
        if (condition.incidentActive()) {
            return new ProbeResult(ProbeStatus.DEGRADED, checkedAt, "provider incident active");
        }
        if (condition.latestRunQuarantined()) {
            return new ProbeResult(ProbeStatus.DEGRADED, checkedAt, "latest run quarantined");
        }
        return new ProbeResult(ProbeStatus.READY, checkedAt, null);
    }
}
