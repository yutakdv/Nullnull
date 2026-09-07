package io.nullnull.recommendation.domain.item;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Mirrors {@code TemporalCandidateIn}: one before/after snapshot pair for the same place, already
 * judged by {@code TemporalComparisonPolicy}. {@code verdictReasonCode} is one of the eleven codes in
 * docs/data/SOURCE_CATALOG.md §9; only {@code SAME_METRIC_AND_ISSUE} may accompany an eligible pair.
 */
public record TemporalCandidateIn(UUID placeId, LocalDate date, LocalTime time, ForecastResolution resolution,
        BigDecimal beforeValue, BigDecimal afterValue, String metricCode, boolean verdictEligible,
        String verdictReasonCode, UUID beforeSnapshotId, UUID afterSnapshotId) {

    public enum ForecastResolution { DAY, HOUR }

    /** The published code of the compared metric; the service refuses a longer one with a 422. */
    public static final int MAX_METRIC_CODE = 64;

    public TemporalCandidateIn {
        Objects.requireNonNull(placeId, "placeId");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(resolution, "resolution");
        Objects.requireNonNull(beforeValue, "beforeValue");
        Objects.requireNonNull(afterValue, "afterValue");
        Objects.requireNonNull(metricCode, "metricCode");
        Objects.requireNonNull(verdictReasonCode, "verdictReasonCode");
        Objects.requireNonNull(beforeSnapshotId, "beforeSnapshotId");
        Objects.requireNonNull(afterSnapshotId, "afterSnapshotId");
        if (metricCode.isBlank()) {
            throw new IllegalArgumentException("metricCode must not be blank");
        }
        if (metricCode.length() > MAX_METRIC_CODE) {
            throw new IllegalArgumentException("metricCode must be at most " + MAX_METRIC_CODE + " characters");
        }
        if (resolution == ForecastResolution.DAY && time != null) {
            throw new IllegalArgumentException("a DAY resolution candidate carries no time");
        }
    }

    /** The slot the service would propose: a DAY candidate keeps the time the item already has. */
    public LocalTime effectiveStartTime(LocalTime currentStartTime) {
        return time != null ? time : currentStartTime;
    }
}
