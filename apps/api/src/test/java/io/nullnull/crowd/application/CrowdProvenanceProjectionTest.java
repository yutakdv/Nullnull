package io.nullnull.crowd.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.crowd.application.CrowdForecastQuery.Snapshot;
import io.nullnull.crowd.application.CrowdForecastQuery.SourceDescriptor;
import io.nullnull.crowd.application.CrowdProvenanceProjection.CrowdMetric;
import io.nullnull.crowd.domain.ComparisonReasonCode;
import io.nullnull.crowd.domain.ComparisonScope;
import io.nullnull.crowd.domain.SourceState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CrowdProvenanceProjectionTest {

    private static final Instant NOW = Instant.parse("2032-01-01T00:00:00Z");
    private final CrowdProvenanceProjection projection = new CrowdProvenanceProjection();

    @ParameterizedTest(name = "BA-023-T1 {0} preserves its distinct public state")
    @MethodSource("states")
    void sixStatesAndNullTimeSemanticsStayExplicit(SourceState sourceState, SourceState expectedState,
            String expectedFreshness, String expectedReason) {
        boolean forecastDerived = sourceState == SourceState.FORECAST || sourceState == SourceState.STALE;
        CrowdMetric metric = projection.project(snapshot(sourceState, forecastDerived ? null : NOW,
                forecastDerived ? NOW.plusSeconds(86_400) : null), NOW, false);

        assertThat(metric.state()).isEqualTo(expectedState);
        assertThat(metric.provenance().sourceState()).isEqualTo(expectedState);
        assertThat(metric.provenance().freshness()).isEqualTo(expectedFreshness);
        assertThat(metric.provenance().comparisonReasonCode()).isEqualTo(expectedReason);
        assertThat(metric.provenance().source()).isEqualTo("KTO_CONCENTRATION_FORECAST");
        assertThat(metric.provenance().sourceDisplayName()).isNotBlank();
        assertThat(metric.provenance().fetchedAt()).isEqualTo(NOW.minusSeconds(60));
        if (sourceState == SourceState.FORECAST) {
            assertThat(metric.provenance().observedAt()).isNull();
            assertThat(metric.provenance().targetAt()).isEqualTo(NOW.plusSeconds(86_400));
            assertThat(metric.provenance().comparisonEligible()).isTrue();
        }
        if (sourceState == SourceState.UNAVAILABLE) {
            assertThat(metric.provenance().provenanceId()).isNull();
        }
    }

    @ParameterizedTest(name = "BA-023-T1 incomplete forecast provenance stays incomparable [{index}]")
    @MethodSource("missingForecastProvenance")
    void missingForecastMetadataNeverGetsAComparableValue(Instant observedAt, Instant targetAt, String issue) {
        CrowdMetric metric = projection.project(snapshot(SourceState.FORECAST, observedAt, targetAt, issue), NOW, false);

        assertThat(metric.provenance().comparisonEligible()).isFalse();
        assertThat(metric.provenance().comparisonReasonCode()).isEqualTo(ComparisonReasonCode.MISSING_PROVENANCE);
    }

    /**
     * A quarantine incident is declared after collection and resolved at read time, so this is the one
     * flag the stored row cannot carry. Removing the guard must not stay green.
     */
    @Test
    @DisplayName("BA-023-T2 a quarantined source keeps its stored value but loses comparability")
    void aQuarantinedSourceKeepsItsValueButLosesComparability() {
        Instant target = NOW.plusSeconds(86_400);
        CrowdMetric comparable = projection.project(snapshot(SourceState.FORECAST, null, target, "issue-1", false),
                NOW, false);
        assertThat(comparable.provenance().comparisonEligible()).isTrue();
        assertThat(comparable.provenance().qualityFlags()).isEmpty();

        CrowdMetric quarantined = projection.project(snapshot(SourceState.FORECAST, null, target, "issue-1", true),
                NOW, false);

        assertThat(quarantined.value()).isEqualByComparingTo("42.5");
        assertThat(quarantined.provenance().qualityFlags()).containsExactly("PROVIDER_INCIDENT");
        assertThat(quarantined.provenance().comparisonEligible()).isFalse();
        assertThat(quarantined.provenance().comparisonReasonCode()).isEqualTo(ComparisonReasonCode.PROVIDER_INCIDENT);
    }

    private static Stream<Arguments> states() {
        return Stream.of(
                Arguments.of(SourceState.LIVE, SourceState.LIVE, "FRESH",
                        ComparisonReasonCode.DIFFERENT_FORECAST_ISSUE),
                Arguments.of(SourceState.FORECAST, SourceState.FORECAST, "FRESH",
                        ComparisonReasonCode.SAME_METRIC_AND_ISSUE),
                Arguments.of(SourceState.REPLAY, SourceState.REPLAY, "UNKNOWN", ComparisonReasonCode.REPLAY_INPUT),
                Arguments.of(SourceState.QUALITATIVE, SourceState.QUALITATIVE, "FRESH",
                        ComparisonReasonCode.QUALITATIVE_ONLY),
                Arguments.of(SourceState.STALE, SourceState.STALE, "STALE", ComparisonReasonCode.STALE_INPUT),
                Arguments.of(SourceState.UNAVAILABLE, SourceState.UNAVAILABLE, "UNKNOWN",
                        ComparisonReasonCode.MISSING_PROVENANCE));
    }

    private static Stream<Arguments> missingForecastProvenance() {
        return Stream.of(
                Arguments.of(null, null, "issue-1"),
                Arguments.of(null, NOW.plusSeconds(86_400), null));
    }

    private static Snapshot snapshot(SourceState state, Instant observedAt, Instant targetAt) {
        return snapshot(state, observedAt, targetAt, "issue-1");
    }

    private static Snapshot snapshot(SourceState state, Instant observedAt, Instant targetAt, String issue) {
        return snapshot(state, observedAt, targetAt, issue, false);
    }

    private static Snapshot snapshot(SourceState state, Instant observedAt, Instant targetAt, String issue,
            boolean incidentActive) {
        Instant staleAt = state == SourceState.REPLAY || state == SourceState.UNAVAILABLE ? null : NOW.plusSeconds(3600);
        BigDecimal value = state == SourceState.QUALITATIVE || state == SourceState.UNAVAILABLE ? null
                : BigDecimal.valueOf(42.5);
        return new Snapshot(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new SourceDescriptor("KTO_CONCENTRATION_FORECAST", "한국관광공사 관광지 집중률 예측", 2,
                        "이용허락범위 제한 없음", "https://www.data.go.kr/data/15128555/openapi.do",
                        "https://data.go.kr/ugs/selectPortalPolicyView.do", "출처: ⓒ한국관광공사",
                        "날짜 단위 상대 집중률 예측"),
                state, observedAt, targetAt, NOW.minusSeconds(60), staleAt,
                "KTO_RELATIVE_CONCENTRATION_INDEX", value, "relative-index", null, null, Set.of(), issue, issue,
                "kto-tats-cnctr-rate-v4.1", null, ComparisonScope.PLACE, "fixture place", "DIRECT", false,
                incidentActive);
    }
}
