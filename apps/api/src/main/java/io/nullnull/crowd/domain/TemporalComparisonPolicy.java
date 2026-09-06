package io.nullnull.crowd.domain;

import java.util.Objects;

/**
 * TEMPORAL axis rule (docs/data/SOURCE_CATALOG.md §10): same canonical place, source, metric
 * definition and forecast issue; only the target differs. Evaluation order is fixed so the same
 * pair always yields the same reason code.
 */
public final class TemporalComparisonPolicy {

    public ComparisonVerdict evaluate(CrowdPoint before, CrowdPoint after) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        if (missingProvenance(before) || missingProvenance(after)) {
            return ComparisonVerdict.ineligible(ComparisonReasonCode.MISSING_PROVENANCE);
        }
        if (has(before, QualityFlag.PROVIDER_INCIDENT) || has(after, QualityFlag.PROVIDER_INCIDENT)) {
            return ComparisonVerdict.ineligible(ComparisonReasonCode.PROVIDER_INCIDENT);
        }
        if (before.sourceState() == SourceState.REPLAY || after.sourceState() == SourceState.REPLAY) {
            return ComparisonVerdict.ineligible(ComparisonReasonCode.REPLAY_INPUT);
        }
        if (before.sourceState() == SourceState.STALE || after.sourceState() == SourceState.STALE) {
            return ComparisonVerdict.ineligible(ComparisonReasonCode.STALE_INPUT);
        }
        if (before.sourceState() == SourceState.QUALITATIVE || after.sourceState() == SourceState.QUALITATIVE
                || before.value() == null || after.value() == null) {
            return ComparisonVerdict.ineligible(ComparisonReasonCode.QUALITATIVE_ONLY);
        }
        if (before.scope() != ComparisonScope.PLACE || after.scope() != ComparisonScope.PLACE
                || !Objects.equals(before.placeId(), after.placeId()) || before.placeId() == null) {
            return ComparisonVerdict.ineligible(ComparisonReasonCode.DIFFERENT_SCOPE);
        }
        if (!before.sourceCode().equals(after.sourceCode()) || !before.metricCode().equals(after.metricCode())
                || before.sourceRegistryVersion() != after.sourceRegistryVersion()
                || !before.normalizationVersion().equals(after.normalizationVersion())) {
            // A different metric, registry revision or normalization within one provider is a different
            // series (§5.4 "동일 POI·issue·metric·정규화"); the public code list has no DIFFERENT_METRIC value (D-REC-1).
            return ComparisonVerdict.ineligible(ComparisonReasonCode.DIFFERENT_SOURCE);
        }
        if (has(before, QualityFlag.MAPPING_UNCERTAIN) || has(after, QualityFlag.MAPPING_UNCERTAIN)) {
            return ComparisonVerdict.ineligible(ComparisonReasonCode.MAPPING_UNCERTAIN);
        }
        if (before.sourceState() != SourceState.FORECAST || after.sourceState() != SourceState.FORECAST
                || before.forecastIssueId() == null || !before.forecastIssueId().equals(after.forecastIssueId())) {
            return ComparisonVerdict.ineligible(ComparisonReasonCode.DIFFERENT_FORECAST_ISSUE);
        }
        if (before.targetAt() == null || after.targetAt() == null || before.targetAt().equals(after.targetAt())) {
            throw new IllegalArgumentException("temporal pair must have two different targets");
        }
        return ComparisonVerdict.eligible(ComparisonReasonCode.SAME_METRIC_AND_ISSUE);
    }

    /**
     * Incomplete provenance, no snapshot id, UNAVAILABLE, or any drift/skew/partial flag: the value is
     * quarantined (§5.6 "schema drift → 차단", REC-DATA-04). §9 has no dedicated code for these flags, so
     * they map to MISSING_PROVENANCE (D-REC-1).
     */
    private static boolean missingProvenance(CrowdPoint point) {
        return !point.provenanceComplete() || point.snapshotId() == null
                || point.sourceState() == SourceState.UNAVAILABLE
                || has(point, QualityFlag.SCHEMA_DRIFT) || has(point, QualityFlag.OBSERVED_AT_SKEW)
                || has(point, QualityFlag.PARTIAL_PAYLOAD);
    }

    private static boolean has(CrowdPoint point, QualityFlag flag) {
        return point.qualityFlags().contains(flag);
    }
}
