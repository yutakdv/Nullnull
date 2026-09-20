package io.nullnull.crowd.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One normalized reading of one Seoul live area, plus the forecast the same response carried.
 *
 * <p>There is no numeric value here and that is the provider's shape, not an omission. The manual
 * (v8.5, 2026-04) defines the congestion level as four steps - 여유 / 보통 / 약간 붐빔 / 붐빔 - computed
 * as a percentage of THAT PLACE'S OWN past average (여유 ≤50%, 보통 50-75%, 약간 붐빔 75-100%, 붐빔 >100%).
 * The population figures it publishes beside it are a MIN/MAX range, which no single value can hold.
 *
 * <p>Two consequences the readers of this type must keep: the level is not a magnitude, and it is not
 * comparable between areas, because each area's baseline is its own.
 */
public record SeoulLiveAreaObservation(String areaCode, String areaName, String congestionLevel,
        Instant observedAt, String forecastIssueId, List<ForecastPoint> forecastPoints) {

    /** The registry code every snapshot from this provider references (source_registry.code). */
    public static final String SOURCE_CODE = "SEOUL_CITYDATA";

    /** One published forecast step: when it is for, and the level expected then. */
    public record ForecastPoint(Instant targetAt, String congestionLevel) {
        public ForecastPoint {
            Objects.requireNonNull(targetAt, "targetAt");
            Objects.requireNonNull(congestionLevel, "congestionLevel");
        }
    }

    public SeoulLiveAreaObservation {
        Objects.requireNonNull(areaCode, "areaCode");
        Objects.requireNonNull(areaName, "areaName");
        Objects.requireNonNull(congestionLevel, "congestionLevel");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(forecastIssueId, "forecastIssueId");
        forecastPoints = List.copyOf(forecastPoints == null ? List.of() : forecastPoints);
    }
}
