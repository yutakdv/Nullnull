package io.nullnull.recommendation.domain;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Fixed-point score with its named contributions. {@link BigDecimal} rules out NaN/Infinity by
 * construction; the policy fixes scale and rounding mode before any comparison (§5.5).
 *
 * @param score         total score at policy scale
 * @param contributions ordered, named partial terms used for explanations and golden tests
 */
public record ScoreBreakdown(BigDecimal score, Map<String, BigDecimal> contributions) {

    public ScoreBreakdown {
        Objects.requireNonNull(score, "score");
        Objects.requireNonNull(contributions, "contributions");
        contributions.forEach((name, value) -> {
            Objects.requireNonNull(name, "contribution name");
            Objects.requireNonNull(value, "contribution value for " + name);
        });
        contributions = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(contributions));
    }
}
