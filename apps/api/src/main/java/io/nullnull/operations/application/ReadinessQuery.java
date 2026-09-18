package io.nullnull.operations.application;

import io.nullnull.operations.application.ReadinessProbe.ProbeResult;
import io.nullnull.operations.application.ReadinessProbe.ProbeStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Aggregates probes into READY / DEGRADED / NOT_READY. A failed optional probe never turns
 * the application NOT_READY, and liveness is not affected by any probe (BA-003).
 *
 * <p>A probe that throws is a probe that failed, and is recorded as UNAVAILABLE here rather than
 * propagated (#258). Without that, one probe decided the whole answer by breaking it: with the
 * database unreachable the database probe reported UNAVAILABLE as designed, and then a source probe
 * reading the registry threw from the pool and the readiness answer became a 500 instead of the
 * contract's 503. The rule sits here, once, so a probe added later cannot bring the 500 back.
 *
 * <p>Required probes run first, and one that is UNAVAILABLE decides NOT_READY without running the
 * optional ones. Most optional probes read the database too, and during an outage each would wait
 * out the pool's connection timeout in turn - the answer would take several times that long while
 * already being known. The NOT_READY answer carries no checks, so nothing is lost.
 */
@Service
public class ReadinessQuery {

    private static final Logger log = LoggerFactory.getLogger(ReadinessQuery.class);

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
        Map<ReadinessProbe, CheckReport> results = new LinkedHashMap<>();
        for (ReadinessProbe probe : probes) {
            if (probe.required()) {
                CheckReport check = run(probe, now);
                results.put(probe, check);
                if (check.result().status() == ProbeStatus.UNAVAILABLE) {
                    return new ReadinessReport(ReadinessState.NOT_READY, List.copyOf(results.values()));
                }
            }
        }
        for (ReadinessProbe probe : probes) {
            if (!probe.required()) {
                results.put(probe, run(probe, now));
            }
        }
        // In the order the probes are registered, whichever ran first.
        List<CheckReport> checks = new ArrayList<>(probes.size());
        probes.forEach(probe -> checks.add(results.get(probe)));
        // Every required probe is past UNAVAILABLE here, so anything short of READY only degrades.
        ReadinessState state = checks.stream().allMatch(check -> check.result().status() == ProbeStatus.READY)
                ? ReadinessState.READY : ReadinessState.DEGRADED;
        return new ReadinessReport(state, List.copyOf(checks));
    }

    private static CheckReport run(ReadinessProbe probe, Instant now) {
        try {
            return new CheckReport(probe.name(), probe.required(), probe.probe(now));
        } catch (RuntimeException failure) {
            // The type and not the message: a driver's message names the host it could not reach, and
            // ProbeResult's detail is operator-safe by contract.
            log.warn("readiness probe failed probe={} type={}", probe.name(), failure.getClass().getName());
            return new CheckReport(probe.name(), probe.required(),
                    new ProbeResult(ProbeStatus.UNAVAILABLE, now, "probe failed"));
        }
    }
}
