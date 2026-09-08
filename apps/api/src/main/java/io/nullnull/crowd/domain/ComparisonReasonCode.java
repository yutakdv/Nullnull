package io.nullnull.crowd.domain;

/** The eleven public reason codes from docs/data/SOURCE_CATALOG.md §9. No other value is emitted. */
public final class ComparisonReasonCode {
    public static final String SAME_METRIC_AND_ISSUE = "SAME_METRIC_AND_ISSUE";
    public static final String SAME_SOURCE_SCOPE_SET = "SAME_SOURCE_SCOPE_SET";
    public static final String DIFFERENT_SOURCE = "DIFFERENT_SOURCE";
    public static final String DIFFERENT_SCOPE = "DIFFERENT_SCOPE";
    public static final String DIFFERENT_FORECAST_ISSUE = "DIFFERENT_FORECAST_ISSUE";
    public static final String STALE_INPUT = "STALE_INPUT";
    public static final String REPLAY_INPUT = "REPLAY_INPUT";
    public static final String QUALITATIVE_ONLY = "QUALITATIVE_ONLY";
    public static final String MAPPING_UNCERTAIN = "MAPPING_UNCERTAIN";
    public static final String PROVIDER_INCIDENT = "PROVIDER_INCIDENT";
    public static final String MISSING_PROVENANCE = "MISSING_PROVENANCE";

    private ComparisonReasonCode() {
    }
}
