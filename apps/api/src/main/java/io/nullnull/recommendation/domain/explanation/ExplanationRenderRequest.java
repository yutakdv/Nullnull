package io.nullnull.recommendation.domain.explanation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/**
 * Mirrors {@code ExplanationRenderRequest}: the only values an explanation may mention (§9.1
 * allowlist). {@code placeName} is the approved catalog name, {@code metricLabel} the published
 * label of the compared metric and {@code attribution} the source registry's own line; the two
 * values are a pair {@code TemporalComparisonPolicy} already judged comparable. The body carries no
 * owner, session, coordinate or raw itinerary text, and a time is null when the slot carries none.
 *
 * <p>The bounds and the improvement rule are checked here as well as in the service: an explanation
 * exists for a change that lowers the metric, so a request that claims nothing to explain is a bug
 * on this side and never reaches the network.
 */
public record ExplanationRenderRequest(String locale, String placeName, LocalDate beforeDate, LocalTime beforeTime,
        LocalDate afterDate, LocalTime afterTime, BigDecimal beforeValue, BigDecimal afterValue, String metricLabel,
        String attribution, String forecastIssueId) {

    public static final int MAX_PLACE_NAME = 200;
    public static final int MAX_METRIC_LABEL = 64;
    public static final int MAX_ATTRIBUTION = 200;
    public static final int MAX_FORECAST_ISSUE_ID = 64;

    public ExplanationRenderRequest {
        Objects.requireNonNull(locale, "locale");
        Objects.requireNonNull(beforeDate, "beforeDate");
        Objects.requireNonNull(afterDate, "afterDate");
        Objects.requireNonNull(beforeValue, "beforeValue");
        Objects.requireNonNull(afterValue, "afterValue");
        if (!locale.equals("ko") && !locale.equals("en")) {
            throw new IllegalArgumentException("P0 explanations exist for ko and en only");
        }
        requireBounded(placeName, MAX_PLACE_NAME, "placeName");
        requireBounded(metricLabel, MAX_METRIC_LABEL, "metricLabel");
        requireBounded(attribution, MAX_ATTRIBUTION, "attribution");
        if (forecastIssueId != null && forecastIssueId.length() > MAX_FORECAST_ISSUE_ID) {
            throw new IllegalArgumentException("forecastIssueId must be at most " + MAX_FORECAST_ISSUE_ID
                    + " characters");
        }
        if (beforeValue.compareTo(afterValue) <= 0) {
            throw new IllegalArgumentException("an explanation states a verified improvement: the metric must be lower after");
        }
    }

    /** How many points the metric is lower after the change; §9.1 allows no other derived number. */
    public BigDecimal pointDelta() {
        return beforeValue.subtract(afterValue);
    }

    private static void requireBounded(String value, int limit, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.length() > limit) {
            throw new IllegalArgumentException(name + " must be at most " + limit + " characters");
        }
    }
}
