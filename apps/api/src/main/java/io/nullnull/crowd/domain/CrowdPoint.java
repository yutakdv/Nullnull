package io.nullnull.crowd.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One normalized snapshot value as the recommendation sees it. Nullable fields keep provider
 * semantics: FORECAST has targetAt + forecastIssueId; LIVE has no issue; value is null for
 * QUALITATIVE/UNAVAILABLE. provenanceComplete is computed by the crowd application layer from
 * the DataProvenance required-field rule (docs/data/SOURCE_CATALOG.md §8).
 */
public record CrowdPoint(UUID snapshotId, UUID placeId, ComparisonScope scope, String sourceCode,
        SourceState sourceState, String metricCode, String forecastIssueId, Instant targetAt,
        BigDecimal value, Set<QualityFlag> qualityFlags, boolean provenanceComplete,
        int sourceRegistryVersion, String normalizationVersion) {

    public CrowdPoint {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(sourceCode, "sourceCode");
        Objects.requireNonNull(sourceState, "sourceState");
        Objects.requireNonNull(metricCode, "metricCode");
        Objects.requireNonNull(normalizationVersion, "normalizationVersion");
        qualityFlags = Set.copyOf(Objects.requireNonNull(qualityFlags, "qualityFlags"));
        if (sourceRegistryVersion < 1) {
            throw new IllegalArgumentException("sourceRegistryVersion starts at 1");
        }
    }
}
