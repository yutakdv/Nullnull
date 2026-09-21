package io.nullnull.operations.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.operations.application.DemoCapabilityQuery.CapabilityReport;
import io.nullnull.operations.application.DemoCapabilityQuery.DemoReadinessReport;
import io.nullnull.operations.application.ReadinessProbe.ProbeStatus;
import io.nullnull.operations.application.ReadinessQuery.ReadinessState;
import io.nullnull.crowd.application.ReplayManifestReader;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BA-003: the capability vocabulary the Frontend maps to screens, and the two rules that keep
 * {@code getDemoReadiness} honest - nothing is READY without a source, and a flag can only turn a
 * feature off.
 */
@DisplayName("BA-003 demo capability readiness")
class DemoCapabilityQueryTest {

    private static final Instant NOW = Instant.parse("2026-03-04T05:06:07Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    @DisplayName("the published capability names are exactly the FR-OPS-02 three")
    void theVocabularyIsPinned() {
        // Adding or renaming a name here is an FE-facing contract change: docs/api/openapi.yaml puts no
        // enum on CapabilityStatus.name, so this test and the BA-003 card are where the set is fixed.
        assertThat(DemoCapabilities.NAMES).containsExactly("live", "replay", "optimization");
        assertThat(DemoCapabilities.FLAG_VARIABLES).containsOnlyKeys(DemoCapabilities.NAMES);
        assertThat(DemoCapabilities.FLAG_VARIABLES.values())
                .containsExactlyInAnyOrder("FEATURE_LIVE_DATA", "FEATURE_REPLAY_MODE",
                        "FEATURE_OPTIMIZATION_ITEM");
    }

    @Test
    @DisplayName("no infrastructure probe name leaks into the product capability list")
    void theTwoNamespacesShareNoName() {
        assertThat(DemoCapabilities.NAMES)
                .as("/health/ready checks are a separate namespace and must not be depended on here")
                .doesNotContain("database", "jobs", "recommendation");
    }

    @Test
    @DisplayName("every capability is UNAVAILABLE with its flags at the default")
    void everyCapabilityIsUnavailableWithAnOperatorSafeDetail() {
        DemoReadinessReport report = query(false, false, false, false).readiness();

        assertThat(report.checkedAt()).isEqualTo(NOW);
        assertThat(report.overall()).isEqualTo(ReadinessState.NOT_READY);
        assertThat(report.capabilities()).extracting(CapabilityReport::name)
                .containsExactlyElementsOf(DemoCapabilities.NAMES);
        assertThat(report.capabilities()).allSatisfy(capability -> {
            assertThat(capability.status()).isEqualTo(ProbeStatus.UNAVAILABLE);
            assertThat(capability.detail())
                    .contains(DemoCapabilities.FLAG_VARIABLES.get(capability.name()));
        });
    }

    @Test
    @DisplayName("an unavailable capability says WHICH of the two reasons it is unavailable for")
    void theDetailDistinguishesAnOffFlagFromAMissingSource() {
        List<CapabilityReport> capabilities =
                query(false, false, false, false).readiness().capabilities();

        // With flags off, each detail names the controlling flag. Replay has a second, data-backed
        // unavailable state when the flag is on but no manifest has been approved.
        assertThat(detail(capabilities, "replay")).isEqualTo("FEATURE_REPLAY_MODE is OFF");
        assertThat(detail(capabilities, "optimization"))
                .as("BA-050 built the run pipeline, so this one is off by decision, not by absence")
                .doesNotContain("no server-side source")
                .isEqualTo("FEATURE_OPTIMIZATION_ITEM is OFF");
        // B10 did the same for live on 2026-09-20 - V046's promoted source, a collector that stores
        // a reading per area, and queryLiveAreas reading them back. The list of capabilities with
        // nothing behind them is down to one, and that shrinking is the point of this assertion
        // being per-capability rather than "all of them say the same thing".
        assertThat(detail(capabilities, "live"))
                .doesNotContain("no server-side source")
                .isEqualTo("FEATURE_LIVE_DATA is OFF");
    }

    @Test
    @DisplayName("BA-050 optimization reports READY when its flag is on, and only that one may be")
    void theOptimizationFlagCanNowBeTurnedOn() {
        DemoReadinessReport report = query(false, false, true, false).readiness();

        assertThat(report.capabilities()).filteredOn(capability -> capability.name().equals("optimization"))
                .singleElement()
                .satisfies(capability -> assertThat(capability.status()).isEqualTo(ProbeStatus.READY));
        // Not READY overall: the other two still have nothing behind them, and DEGRADED is what a
        // partial demo is. A capability turning on must not make the whole report claim more.
        assertThat(report.overall()).isEqualTo(ReadinessState.DEGRADED);
    }

    private static String detail(List<CapabilityReport> capabilities, String name) {
        return capabilities.stream().filter(capability -> capability.name().equals(name))
                .map(CapabilityReport::detail).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("BA-092-T11 replay flag가 켜져도 승인 manifest 전에는 READY를 광고하지 않는다")
    void replayReadinessRequiresAnApprovedManifest() {
        CapabilityReport missing = query(false, true, false, false).readiness().capabilities().stream()
                .filter(item -> item.name().equals("replay")).findFirst().orElseThrow();
        CapabilityReport available = query(false, true, false, true).readiness().capabilities().stream()
                .filter(item -> item.name().equals("replay")).findFirst().orElseThrow();

        assertThat(missing.status()).isEqualTo(ProbeStatus.UNAVAILABLE);
        assertThat(missing.detail()).contains("approved manifest");
        assertThat(available.status()).isEqualTo(ProbeStatus.READY);
    }

    private static DemoCapabilityQuery query(boolean live, boolean replay, boolean optimization,
            boolean manifestAvailable) {
        ReplayManifestReader reader = new ReplayManifestReader() {
            @Override
            public Optional<ReplayBatch> read(UUID manifestId, Instant now) { return Optional.empty(); }
            @Override
            public Optional<ReplayBatch> latestFor(String sourceCode, Instant now) {
                return manifestAvailable ? Optional.of(new ReplayBatch(UUID.randomUUID(), NOW, NOW, List.of()))
                        : Optional.empty();
            }
        };
        return new DemoCapabilityQuery(live, replay, optimization, reader, CLOCK);
    }

    @Test
    @DisplayName("overall is READY only when every capability is, and never for an empty list")
    void overallIsTheConservativeAggregate() {
        assertThat(DemoCapabilityQuery.overall(List.of(ready("live"), ready("replay"))))
                .isEqualTo(ReadinessState.READY);
        assertThat(DemoCapabilityQuery.overall(List.of(ready("live"), unavailable("replay"))))
                .isEqualTo(ReadinessState.DEGRADED);
        assertThat(DemoCapabilityQuery.overall(List.of(unavailable("live"), unavailable("replay"))))
                .isEqualTo(ReadinessState.NOT_READY);
        // "Nothing to check" is not "everything passed".
        assertThat(DemoCapabilityQuery.overall(List.of())).isEqualTo(ReadinessState.NOT_READY);
    }

    private static CapabilityReport ready(String name) {
        return new CapabilityReport(name, ProbeStatus.READY, null);
    }

    private static CapabilityReport unavailable(String name) {
        return new CapabilityReport(name, ProbeStatus.UNAVAILABLE, "no source");
    }
}
