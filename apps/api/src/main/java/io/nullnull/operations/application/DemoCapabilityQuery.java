package io.nullnull.operations.application;

import io.nullnull.crowd.application.ReplayManifestReader;
import io.nullnull.crowd.domain.SeoulLiveAreaObservation;
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
 * nothing behind it reports {@code UNAVAILABLE}, never {@code READY}. That was every capability until
 * BA-050, which builds the optimization run pipeline - so {@code optimization} is now a capability
 * whose flag may legitimately be turned on, and it reports {@code READY} when it is. {@code live}
 * joined it on 2026-09-20. Replay now has an approved-manifest reader, but only a valid captured
 * manifest makes its capability READY.
 *
 * <p>A flag that may be turned on is not a flag that should be, and where it is turned on is a
 * deployment decision, not this class's. BA-050 queues runs, freezes their evidence and refuses the ones
 * whose input moved, and BA-051 writes the preview, so the flag now has something behind it. The default
 * stays OFF; the submission build turns it ON in its deployment settings (owner decision, 2026-09-19).
 * Nothing here enforces either, and the contest profile does not touch it.
 *
 * <p>The replay flag is an upper bound, not evidence of captured data. The approved manifest is
 * checked on every readiness request, so the first real capture can make replay READY without a
 * second deploy or an empty store being advertised as usable.
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
    private final ReplayManifestReader replayReader;
    private final Clock clock;

    public DemoCapabilityQuery(@Value("${nullnull.capabilities.live}") boolean live,
            @Value("${nullnull.capabilities.replay}") boolean replay,
            @Value("${nullnull.capabilities.optimization}") boolean optimization,
            ReplayManifestReader replayReader, Clock clock) {
        this.flags = Map.of(DemoCapabilities.LIVE, live, DemoCapabilities.REPLAY, replay,
                DemoCapabilities.OPTIMIZATION, optimization);
        this.replayReader = Objects.requireNonNull(replayReader, "replayReader");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DemoReadinessReport readiness() {
        List<CapabilityReport> capabilities = DemoCapabilities.NAMES.stream()
                .map(this::report)
                .toList();
        return new DemoReadinessReport(overall(capabilities), capabilities, clock.instant());
    }

    /** One capability's answer; replay also checks that approved data exists right now. */
    private CapabilityReport report(String name) {
        String flagVariable = DemoCapabilities.FLAG_VARIABLES.get(name);
        if (!Boolean.TRUE.equals(flags.get(name))) {
            return new CapabilityReport(name, ProbeStatus.UNAVAILABLE, flagVariable + " is OFF");
        }
        if (DemoCapabilities.REPLAY.equals(name)) {
            try {
                if (replayReader.latestFor(SeoulLiveAreaObservation.SOURCE_CODE, clock.instant()).isEmpty()) {
                    return new CapabilityReport(name, ProbeStatus.UNAVAILABLE,
                            flagVariable + " is ON but no approved manifest is available");
                }
            } catch (RuntimeException unavailable) {
                return new CapabilityReport(name, ProbeStatus.UNAVAILABLE,
                        flagVariable + " is ON but approved manifest is unavailable");
            }
        }
        return new CapabilityReport(name, ProbeStatus.READY, flagVariable + " is ON");
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
