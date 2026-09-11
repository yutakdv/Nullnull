package io.nullnull.operations.application;

import io.nullnull.operations.application.ReadinessProbe.ProbeStatus;
import io.nullnull.operations.application.ReadinessQuery.ReadinessState;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Answers {@code getDemoReadiness}: can a visitor actually use each P0 demo capability
 * ({@code FR-OPS-02}).
 *
 * <p>The BA-003 safety line is "readiness가 없는 기능은 준비 완료로 광고하지 않는다": a capability with
 * nothing behind it reports {@code UNAVAILABLE}, never {@code READY}. In P0 that is every capability -
 * there is no live provider, no replay dataset and no optimizer yet - so this endpoint currently
 * answers {@code NOT_READY} with three {@code UNAVAILABLE} entries. That is the correct answer and not
 * a failure; the slice that adds a source is the slice that changes it.
 *
 * <p>The flags are wired in the only direction that is safe. A {@code FEATURE_*} flag may turn a
 * feature OFF; it may never turn ON something the server cannot do, because that would advertise a
 * capability no source answers and "flag는 backend capability response가 정본이다"
 * (docs/operations/ENVIRONMENT.md §6) makes this response the thing the Frontend believes. So an ON
 * flag with no source behind it fails startup, which is also the rule
 * docs/operations/ENVIRONMENT.md §9 states for a LIVE feature turned on without its source.
 */
@Service
public class DemoCapabilityQuery {

    /** One capability's answer. {@code detail} is operator-safe: a flag name, never a host or a key. */
    public record CapabilityReport(String name, ProbeStatus status, String detail) {
    }

    public record DemoReadinessReport(ReadinessState overall, List<CapabilityReport> capabilities,
            Instant checkedAt) {
    }

    private final Map<String, Boolean> flags;
    private final Clock clock;

    public DemoCapabilityQuery(@Value("${nullnull.capabilities.live}") boolean live,
            @Value("${nullnull.capabilities.replay}") boolean replay,
            @Value("${nullnull.capabilities.optimization}") boolean optimization, Clock clock) {
        this.flags = Map.of(DemoCapabilities.LIVE, live, DemoCapabilities.REPLAY, replay,
                DemoCapabilities.OPTIMIZATION, optimization);
        this.clock = Objects.requireNonNull(clock, "clock");
        // No capability has a server-side source in P0, so any ON flag is a misconfiguration in every
        // environment. The slice that adds a source removes its capability from this check and from
        // report() in the same change; until then an ON flag would publish a lie.
        for (String name : DemoCapabilities.NAMES) {
            if (Boolean.TRUE.equals(this.flags.get(name))) {
                throw new IllegalStateException(DemoCapabilities.FLAG_VARIABLES.get(name)
                        + " is ON but no server-side source answers the '" + name + "' capability yet;"
                        + " the slice that adds the source is the one that may turn this flag on");
            }
        }
    }

    public DemoReadinessReport readiness() {
        List<CapabilityReport> capabilities = DemoCapabilities.NAMES.stream()
                .map(name -> new CapabilityReport(name, ProbeStatus.UNAVAILABLE,
                        DemoCapabilities.FLAG_VARIABLES.get(name)
                                + " is OFF and no server-side source answers this capability yet"))
                .toList();
        return new DemoReadinessReport(overall(capabilities), capabilities, clock.instant());
    }

    /**
     * READY only when every capability is; NOT_READY when none is, because then there is nothing to
     * demonstrate at all; DEGRADED for the partial state in between.
     */
    static ReadinessState overall(List<CapabilityReport> capabilities) {
        long ready = capabilities.stream()
                .filter(capability -> capability.status() == ProbeStatus.READY)
                .count();
        // An empty list is NOT_READY too: "nothing to check" is not the same as "everything passed".
        if (capabilities.isEmpty() || ready == 0) {
            return ReadinessState.NOT_READY;
        }
        return ready == capabilities.size() ? ReadinessState.READY : ReadinessState.DEGRADED;
    }
}
