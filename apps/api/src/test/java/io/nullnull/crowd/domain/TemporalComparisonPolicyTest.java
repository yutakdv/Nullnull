package io.nullnull.crowd.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("REC-DATA-02/03/04 temporal pair eligibility")
class TemporalComparisonPolicyTest {

    static final UUID PLACE = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93a01");
    static final UUID OTHER_PLACE = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93a02");
    final TemporalComparisonPolicy policy = new TemporalComparisonPolicy();

    static CrowdPoint forecast(UUID place, String issue, String target, int value, Set<QualityFlag> flags) {
        return new CrowdPoint(UUID.randomUUID(), place, ComparisonScope.PLACE, "KTO_CONCENTRATION_FORECAST",
                SourceState.FORECAST, "KTO_RELATIVE_CONCENTRATION_INDEX", issue, Instant.parse(target),
                BigDecimal.valueOf(value), flags, true, 1, "kto-forecast-v1");
    }

    static CrowdPoint withState(CrowdPoint p, SourceState state) {
        return new CrowdPoint(p.snapshotId(), p.placeId(), p.scope(), p.sourceCode(), state, p.metricCode(),
                state == SourceState.FORECAST ? p.forecastIssueId() : null, p.targetAt(),
                state == SourceState.QUALITATIVE ? null : p.value(), p.qualityFlags(), p.provenanceComplete(),
                p.sourceRegistryVersion(), p.normalizationVersion());
    }

    static CrowdPoint withVersions(CrowdPoint p, int registryVersion, String normalization) {
        return new CrowdPoint(p.snapshotId(), p.placeId(), p.scope(), p.sourceCode(), p.sourceState(), p.metricCode(),
                p.forecastIssueId(), p.targetAt(), p.value(), p.qualityFlags(), p.provenanceComplete(), registryVersion, normalization);
    }

    @Test
    void samePlaceSourceMetricIssueIsEligible() {
        ComparisonVerdict verdict = policy.evaluate(
                forecast(PLACE, "issue-1", "2026-09-12T01:00:00Z", 80, Set.of()),
                forecast(PLACE, "issue-1", "2026-09-12T05:00:00Z", 60, Set.of()));
        assertThat(verdict.eligible()).isTrue();
        assertThat(verdict.reasonCode()).isEqualTo(ComparisonReasonCode.SAME_METRIC_AND_ISSUE);
    }

    @Test
    void differentIssueIsIneligible() {
        ComparisonVerdict verdict = policy.evaluate(
                forecast(PLACE, "issue-1", "2026-09-12T01:00:00Z", 80, Set.of()),
                forecast(PLACE, "issue-2", "2026-09-12T05:00:00Z", 60, Set.of()));
        assertThat(verdict).isEqualTo(ComparisonVerdict.ineligible(ComparisonReasonCode.DIFFERENT_FORECAST_ISSUE));
    }

    @Test
    void differentPlaceIsDifferentScope() {
        ComparisonVerdict verdict = policy.evaluate(
                forecast(PLACE, "issue-1", "2026-09-12T01:00:00Z", 80, Set.of()),
                forecast(OTHER_PLACE, "issue-1", "2026-09-12T05:00:00Z", 60, Set.of()));
        assertThat(verdict.reasonCode()).isEqualTo(ComparisonReasonCode.DIFFERENT_SCOPE);
    }

    @Test
    void staleReplayIncidentQualitativeAndMissingProvenanceBlockInPrecedenceOrder() {
        CrowdPoint before = forecast(PLACE, "issue-1", "2026-09-12T01:00:00Z", 80, Set.of());
        CrowdPoint after = forecast(PLACE, "issue-1", "2026-09-12T05:00:00Z", 60, Set.of());
        assertThat(policy.evaluate(before, withState(after, SourceState.STALE)).reasonCode())
                .isEqualTo(ComparisonReasonCode.STALE_INPUT);
        assertThat(policy.evaluate(withState(before, SourceState.REPLAY), after).reasonCode())
                .isEqualTo(ComparisonReasonCode.REPLAY_INPUT);
        assertThat(policy.evaluate(before, withState(after, SourceState.QUALITATIVE)).reasonCode())
                .isEqualTo(ComparisonReasonCode.QUALITATIVE_ONLY);
        assertThat(policy.evaluate(before, forecast(PLACE, "issue-1", "2026-09-12T05:00:00Z", 60,
                Set.of(QualityFlag.PROVIDER_INCIDENT))).reasonCode())
                .isEqualTo(ComparisonReasonCode.PROVIDER_INCIDENT);
        CrowdPoint noProvenance = new CrowdPoint(null, PLACE, ComparisonScope.PLACE, "KTO_CONCENTRATION_FORECAST",
                SourceState.FORECAST, "KTO_RELATIVE_CONCENTRATION_INDEX", "issue-1",
                Instant.parse("2026-09-12T05:00:00Z"), BigDecimal.valueOf(60), Set.of(QualityFlag.PROVIDER_INCIDENT), false, 1, "kto-forecast-v1");
        assertThat(policy.evaluate(before, noProvenance).reasonCode())
                .as("missing provenance wins over incident")
                .isEqualTo(ComparisonReasonCode.MISSING_PROVENANCE);
    }

    @Test
    void liveVersusForecastHasNoSharedIssue() {
        CrowdPoint before = withState(forecast(PLACE, "issue-1", "2026-09-12T01:00:00Z", 80, Set.of()), SourceState.LIVE);
        CrowdPoint after = forecast(PLACE, "issue-1", "2026-09-12T05:00:00Z", 60, Set.of());
        assertThat(policy.evaluate(before, after).reasonCode()).isEqualTo(ComparisonReasonCode.DIFFERENT_FORECAST_ISSUE);
    }

    @Test
    void mappingUncertainFlagBlocks() {
        ComparisonVerdict verdict = policy.evaluate(
                forecast(PLACE, "issue-1", "2026-09-12T01:00:00Z", 80, Set.of()),
                forecast(PLACE, "issue-1", "2026-09-12T05:00:00Z", 60, Set.of(QualityFlag.MAPPING_UNCERTAIN)));
        assertThat(verdict.reasonCode()).isEqualTo(ComparisonReasonCode.MAPPING_UNCERTAIN);
    }

    @Test
    void driftSkewAndPartialPayloadAreQuarantined() {
        CrowdPoint before = forecast(PLACE, "issue-1", "2026-09-12T01:00:00Z", 80, Set.of());
        for (QualityFlag flag : List.of(QualityFlag.SCHEMA_DRIFT, QualityFlag.OBSERVED_AT_SKEW, QualityFlag.PARTIAL_PAYLOAD)) {
            ComparisonVerdict verdict = policy.evaluate(before, forecast(PLACE, "issue-1", "2026-09-12T05:00:00Z", 60, Set.of(flag)));
            assertThat(verdict.eligible()).as(flag.name()).isFalse();
            assertThat(verdict.reasonCode()).as(flag.name()).isEqualTo(ComparisonReasonCode.MISSING_PROVENANCE);
        }
    }

    @Test
    void differentRegistryOrNormalizationVersionIsADifferentSeries() {
        CrowdPoint before = forecast(PLACE, "issue-1", "2026-09-12T01:00:00Z", 80, Set.of());
        CrowdPoint after = forecast(PLACE, "issue-1", "2026-09-12T05:00:00Z", 60, Set.of());
        assertThat(policy.evaluate(before, withVersions(after, 2, "kto-forecast-v1")).reasonCode()).isEqualTo(ComparisonReasonCode.DIFFERENT_SOURCE);
        assertThat(policy.evaluate(before, withVersions(after, 1, "kto-forecast-v2")).reasonCode()).isEqualTo(ComparisonReasonCode.DIFFERENT_SOURCE);
    }

    @Test
    void sameTargetIsACallerBug() {
        CrowdPoint point = forecast(PLACE, "issue-1", "2026-09-12T01:00:00Z", 80, Set.of());
        assertThatThrownBy(() -> policy.evaluate(point, point)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void randomPairsAreEligibleOnlyWhenAllFiveConditionsHold() {
        var rng = java.util.random.RandomGeneratorFactory.of("L64X128MixRandom").create(20260906L);
        SourceState[] states = SourceState.values();
        for (int i = 0; i < 1_000; i++) {
            UUID place = rng.nextBoolean() ? PLACE : OTHER_PLACE;
            String issue = rng.nextBoolean() ? "issue-1" : "issue-2";
            SourceState state = states[rng.nextInt(states.length)];
            Set<QualityFlag> flags = rng.nextInt(4) == 0 ? Set.of(QualityFlag.values()[rng.nextInt(5)]) : Set.of();
            CrowdPoint before = forecast(PLACE, "issue-1", "2026-09-12T01:00:00Z", 80, Set.of());
            CrowdPoint after = withState(forecast(place, issue, "2026-09-12T05:00:00Z", 60, flags), state);
            ComparisonVerdict verdict = policy.evaluate(before, after);
            boolean expected = place.equals(PLACE) && issue.equals("issue-1") && state == SourceState.FORECAST && flags.isEmpty();
            assertThat(verdict.eligible()).as("seed 20260906 iteration %d", i).isEqualTo(expected);
        }
    }
}
