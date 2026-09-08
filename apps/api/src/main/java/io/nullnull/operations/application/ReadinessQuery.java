package io.nullnull.operations.application;

import io.nullnull.operations.application.ReadinessProbe.ProbeResult;
import io.nullnull.operations.application.ReadinessProbe.ProbeStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Aggregates probes into READY / DEGRADED / NOT_READY. A failed optional probe never turns
 * the application NOT_READY, and liveness is not affected by any probe (BA-003).
 */
@Service
public class ReadinessQuery {

    public enum ReadinessState { READY, DEGRADED, NOT_READY }

    public record CheckReport(String name, boolean required, ProbeResult result) {
    }

    public record ReadinessReport(ReadinessState state, List<CheckReport> checks) {
    }

    private final List<ReadinessProbe> probes;
    private final Clock clock;

    public ReadinessQuery(List<ReadinessProbe> probes, Clock clock) {
        this.probes = List.copyOf(probes);
        this.clock = clock;
    }

    public ReadinessReport readiness() {
        Instant now = clock.instant();
        List<CheckReport> checks = probes.stream()
                .map(probe -> new CheckReport(probe.name(), probe.required(), probe.probe(now)))
                .toList();
        ReadinessState state = ReadinessState.READY;
        for (CheckReport check : checks) {
            ProbeStatus status = check.result().status();
            if (check.required() && status == ProbeStatus.UNAVAILABLE) {
                state = ReadinessState.NOT_READY;
                break;
            }
            if (status != ProbeStatus.READY) {
                state = ReadinessState.DEGRADED;
            }
        }
        return new ReadinessReport(state, checks);
    }
}
