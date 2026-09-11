package io.nullnull.crowd.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable normalized output of one validated KTO concentration forecast batch. */
public record KtoForecastSnapshotSet(UUID id, UUID collectorRunId, long sourceRegistryVersion,
        String forecastIssueId, String comparisonGroupId, String normalizationVersion, String payloadHash,
        Instant fetchedAt, Instant staleAt, List<ForecastPoint> points) {

    public static final String SOURCE_CODE = "KTO_CONCENTRATION_FORECAST";
    public static final String METRIC_CODE = "KTO_RELATIVE_CONCENTRATION_INDEX";
    public static final String UNIT = "relative-index";

    public KtoForecastSnapshotSet {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(collectorRunId, "collectorRunId");
        forecastIssueId = safeIdentifier(forecastIssueId, "forecastIssueId");
        comparisonGroupId = safeIdentifier(comparisonGroupId, "comparisonGroupId");
        normalizationVersion = safeIdentifier(normalizationVersion, "normalizationVersion");
        payloadHash = safeHash(payloadHash);
        Objects.requireNonNull(fetchedAt, "fetchedAt");
        Objects.requireNonNull(staleAt, "staleAt");
        if (sourceRegistryVersion < 1 || !staleAt.isAfter(fetchedAt)) {
            throw new IllegalArgumentException("KTO forecast provenance is invalid");
        }
        points = List.copyOf(points == null ? List.of() : points);
        if (points.isEmpty()) {
            throw new IllegalArgumentException("KTO forecast set requires at least one point");
        }
        if (!points.stream().sorted(Comparator.comparing(ForecastPoint::targetAt)).toList().equals(points)) {
            throw new IllegalArgumentException("KTO forecast points must be target ordered");
        }
        Set<Instant> targets = new HashSet<>();
        for (ForecastPoint point : points) {
            if (!targets.add(point.targetAt())) {
                throw new IllegalArgumentException("KTO forecast targets must be unique");
            }
        }
    }

    private static String safeIdentifier(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.matches("[A-Za-z0-9:_.-]{1,100}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static String safeHash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("KTO forecast payloadHash is invalid");
        }
        return value;
    }

    public record ForecastPoint(UUID id, Instant targetAt, BigDecimal value) {
        public ForecastPoint {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(targetAt, "targetAt");
            Objects.requireNonNull(value, "value");
            if (value.scale() > 4 || value.compareTo(BigDecimal.ZERO) < 0
                    || value.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new IllegalArgumentException("KTO concentration rate is outside the reviewed range");
            }
        }
    }
}
