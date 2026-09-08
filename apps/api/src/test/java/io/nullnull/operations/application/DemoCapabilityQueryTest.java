package io.nullnull.operations.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.operations.application.DemoCapabilityQuery.CapabilityReport;
import io.nullnull.operations.application.DemoCapabilityQuery.DemoReadinessReport;
import io.nullnull.operations.application.ReadinessProbe.ProbeStatus;
import io.nullnull.operations.application.ReadinessQuery.ReadinessState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
    @DisplayName("every P0 capability is UNAVAILABLE, because no source answers it yet")
    void everyCapabilityIsUnavailableWithAnOperatorSafeDetail() {
        DemoReadinessReport report = new DemoCapabilityQuery(false, false, false, CLOCK).readiness();

        assertThat(report.checkedAt()).isEqualTo(NOW);
        assertThat(report.overall()).isEqualTo(ReadinessState.NOT_READY);
        assertThat(report.capabilities()).extracting(CapabilityReport::name)
                .containsExactlyElementsOf(DemoCapabilities.NAMES);
        assertThat(report.capabilities()).allSatisfy(capability -> {
            assertThat(capability.status()).isEqualTo(ProbeStatus.UNAVAILABLE);
            assertThat(capability.detail())
                    .contains(DemoCapabilities.FLAG_VARIABLES.get(capability.name()))
                    .contains("no server-side source");
        });
    }

    @Test
    @DisplayName("BA-003-T2 a FEATURE flag turned on with no source behind it fails startup")
    void aFlagTurnedOnWithoutASourceIsRefused() {
        // The combination is real: docs/operations/ENVIRONMENT.md §9 requires a LIVE feature that is ON
        // to have its source registry, key and readiness present, and §6 makes this response the
        // authority on what is enabled. Nothing backs any of the three in P0, so ON is a lie in every
        // environment - and a flag that could turn a capability ON without a source would be exactly
        // the safety-invariant OFF switch the BA-003 card forbids.
        assertThatThrownBy(() -> new DemoCapabilityQuery(true, false, false, CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FEATURE_LIVE_DATA")
                .hasMessageContaining("live");
        assertThatThrownBy(() -> new DemoCapabilityQuery(false, true, false, CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FEATURE_REPLAY_MODE");
        assertThatThrownBy(() -> new DemoCapabilityQuery(false, false, true, CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FEATURE_OPTIMIZATION_ITEM");
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
