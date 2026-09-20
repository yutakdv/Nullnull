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
 * nothing behind it reports {@code UNAVAILABLE}, never {@code READY}. That was every capability until
 * BA-050, which builds the optimization run pipeline - so {@code optimization} is now a capability
 * whose flag may legitimately be turned on, and it reports {@code READY} when it is. {@code live}
 * joined it on 2026-09-20 (see {@code WITHOUT_A_SOURCE} below for what arrived). {@code replay} still
 * has no source: its flag remains refused at startup, because turning it on would advertise something
 * nothing can answer.
 *
 * <p>A flag that may be turned on is not a flag that should be, and where it is turned on is a
 * deployment decision, not this class's. BA-050 queues runs, freezes their evidence and refuses the ones
 * whose input moved, and BA-051 writes the preview, so the flag now has something behind it. The default
 * stays OFF; the submission build turns it ON in its deployment settings (owner decision, 2026-09-19).
 * Nothing here enforces either, and the contest profile does not touch it.
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

    /**
     * Capabilities whose flag cannot be turned on yet, because nothing would answer it.
     *
     * <p>A list rather than "all of them": each entry leaves when its own source arrives, and the set
     * shrinking is the visible record of which ones have one. B03 removes {@code replay}.
     *
     * <p><strong>{@code live} left on 2026-09-20 (B10).</strong> What it waited for was not a route
     * but something to answer WITH, and all three arrived together: SEOUL_CITYDATA promoted in V046,
     * a collector that stores a reading per area, and {@code queryLiveAreas} reading them back. The
     * flag still defaults OFF and turning it on is a deployment decision, the same as optimization's
     * - what changed is that an ON flag no longer advertises something nothing can answer.
     */
    private static final List<String> WITHOUT_A_SOURCE = List.of(DemoCapabilities.REPLAY);

    private final Map<String, Boolean> flags;
    private final Clock clock;

    public DemoCapabilityQuery(@Value("${nullnull.capabilities.live}") boolean live,
            @Value("${nullnull.capabilities.replay}") boolean replay,
            @Value("${nullnull.capabilities.optimization}") boolean optimization, Clock clock) {
        this.flags = Map.of(DemoCapabilities.LIVE, live, DemoCapabilities.REPLAY, replay,
                DemoCapabilities.OPTIMIZATION, optimization);
        this.clock = Objects.requireNonNull(clock, "clock");
        // A capability with no server-side source is a misconfiguration when its flag is ON, in every
        // environment. The slice that adds the source is the one that takes its capability out of
        // this check and teaches report() to answer for it; BA-050 did that for optimization, so the
        // two left are the two that still have nothing behind them.
        for (String name : WITHOUT_A_SOURCE) {
            if (Boolean.TRUE.equals(this.flags.get(name))) {
                throw new IllegalStateException(DemoCapabilities.FLAG_VARIABLES.get(name)
                        + " is ON but no server-side source answers the '" + name + "' capability yet;"
                        + " the slice that adds the source is the one that may turn this flag on");
            }
        }
    }

    public DemoReadinessReport readiness() {
        List<CapabilityReport> capabilities = DemoCapabilities.NAMES.stream()
                .map(this::report)
                .toList();
        return new DemoReadinessReport(overall(capabilities), capabilities, clock.instant());
    }

    /**
     * One capability's answer, which says which of the two reasons it is unavailable for.
     *
     * <p>"The flag is off" and "nothing answers this yet" are different states for an operator: the
     * first is a decision they can change, the second is not. Reporting both as one sentence would
     * have them looking for a flag to turn on for a feature that has no implementation behind it.
     */
    private CapabilityReport report(String name) {
        String flagVariable = DemoCapabilities.FLAG_VARIABLES.get(name);
        if (WITHOUT_A_SOURCE.contains(name)) {
            return new CapabilityReport(name, ProbeStatus.UNAVAILABLE,
                    flagVariable + " is OFF and no server-side source answers this capability yet");
        }
        return Boolean.TRUE.equals(flags.get(name))
                ? new CapabilityReport(name, ProbeStatus.READY, flagVariable + " is ON")
                : new CapabilityReport(name, ProbeStatus.UNAVAILABLE, flagVariable + " is OFF");
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
