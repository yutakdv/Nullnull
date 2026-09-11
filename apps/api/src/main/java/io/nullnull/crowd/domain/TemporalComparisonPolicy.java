package io.nullnull.crowd.domain;

import java.util.Objects;

/**
 * TEMPORAL axis rule (docs/data/SOURCE_CATALOG.md §10): same canonical place, source, metric
 * definition and forecast issue; only the target differs. Evaluation order is fixed so the same
 * pair always yields the same reason code: MISSING_PROVENANCE, PROVIDER_INCIDENT, REPLAY_INPUT,
 * STALE_INPUT, QUALITATIVE_ONLY, DIFFERENT_SCOPE, DIFFERENT_SOURCE, MAPPING_UNCERTAIN,
 * DIFFERENT_FORECAST_ISSUE, then SAME_METRIC_AND_ISSUE.
 */
public final class TemporalComparisonPolicy {

    /**
     * Whether one normalized point may participate in a temporal pair once another target from the
     * same forecast issue is selected. This is intentionally weaker than {@link #evaluate}: it does
     * not invent a peer or a delta, but gives the UI a stable per-point eligibility explanation.
     */
    public ComparisonVerdict eligibility(CrowdPoint point) {
        Objects.requireNonNull(point, "point");
        if (missingEligibilityProvenance(point)) {
            return ComparisonVerdict.ineligible(ComparisonReasonCode.MISSING_PROVENANCE);
        }
        if (has(point, QualityFlag.PROVIDER_INCIDENT)) {
            return ComparisonVerdict.ineligible(ComparisonReasonCode.PROVIDER_INCIDENT);
        }
        if (point.sourceState() == SourceState.REPLAY) {
            return ComparisonVerdict.ineligible(ComparisonReasonCode.REPLAY_INPUT);
        }
        if (point.sourceState() == SourceState.STALE) {
            return ComparisonVerdict.ineligible(ComparisonReasonCode.STALE_INPUT);
        }
        if (point.sourceState() == SourceState.QUALITATIVE || point.value() == null) {
            return ComparisonVerdict.ineligible(ComparisonReasonCode.QUALITATIVE_ONLY);
        }
        if (point.scope() != ComparisonScope.PLACE || point.placeId() == null) {
            return ComparisonVerdict.ineligible(ComparisonReasonCode.DIFFERENT_SCOPE);
        }
        if (has(point, QualityFlag.MAPPING_UNCERTAIN)) {
            return ComparisonVerdict.ineligible(ComparisonReasonCode.MAPPING_UNCERTAIN);
        }
        if (point.sourceState() != SourceState.FORECAST || point.forecastIssueId() == null) {
            return ComparisonVerdict.ineligible(ComparisonReasonCode.DIFFERENT_FORECAST_ISSUE);
        }
        return ComparisonVerdict.eligible(ComparisonReasonCode.SAME_METRIC_AND_ISSUE);
    }

    /**
     * Precondition: the two points must describe different targets. Two equal non-null targetAt values are
     * a caller/hydration bug with one shape, so they throw before any eligibility check runs; a null
     * targetAt is missing metadata and is answered with MISSING_PROVENANCE like any other absent field.
     */
    public ComparisonVerdict evaluate(CrowdPoint before, CrowdPoint after) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        if (before.targetAt() != null && before.targetAt().equals(after.targetAt())) {
            throw new IllegalArgumentException("temporal pair must compare two different targetAt values");
        }
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
        return ComparisonVerdict.eligible(ComparisonReasonCode.SAME_METRIC_AND_ISSUE);
    }

    /**
     * Incomplete provenance, no snapshot id, no target, UNAVAILABLE, or any drift/skew/partial flag: the
     * value is quarantined (§5.6 "schema drift → 차단", REC-DATA-04). §9 has no dedicated code for these
     * flags, so they map to MISSING_PROVENANCE (D-REC-1).
     */
    private static boolean missingProvenance(CrowdPoint point) {
        return !point.provenanceComplete() || point.snapshotId() == null || point.targetAt() == null
                || point.sourceState() == SourceState.UNAVAILABLE
                || has(point, QualityFlag.SCHEMA_DRIFT) || has(point, QualityFlag.OBSERVED_AT_SKEW)
                || has(point, QualityFlag.PARTIAL_PAYLOAD);
    }

    /** A non-forecast point legitimately has no target; temporal pair evaluation still requires one. */
    private static boolean missingEligibilityProvenance(CrowdPoint point) {
        return !point.provenanceComplete() || point.snapshotId() == null
                || (point.sourceState() == SourceState.FORECAST || point.sourceState() == SourceState.STALE)
                        && point.targetAt() == null
                || point.sourceState() == SourceState.UNAVAILABLE || has(point, QualityFlag.SCHEMA_DRIFT)
                || has(point, QualityFlag.OBSERVED_AT_SKEW) || has(point, QualityFlag.PARTIAL_PAYLOAD);
    }

    private static boolean has(CrowdPoint point, QualityFlag flag) {
        return point.qualityFlags().contains(flag);
    }
}
