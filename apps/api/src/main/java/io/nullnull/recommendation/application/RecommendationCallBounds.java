package io.nullnull.recommendation.application;

import java.time.Duration;
import java.util.Objects;

/**
 * The longest one call to {@code apps/ai} can take, as the configured client allows it.
 *
 * <p>A caller that holds something for the length of a call - the idempotency reservation an APPLY takes
 * before it asks for the policy (#340) - sizes the hold from this rather than from a figure of its own, so
 * the two cannot drift apart when the client's timeouts change. It is a liveness bound, not a promise:
 * DNS resolution, scheduling and clock skew are outside it, and a holder must stay correct when it is
 * exceeded.
 *
 * @param policy the longest {@link RecommendationGateway#policy()} can take, every attempt included
 */
public record RecommendationCallBounds(Duration policy) {

    public RecommendationCallBounds {
        Objects.requireNonNull(policy, "policy");
        if (policy.isNegative() || policy.isZero()) {
            throw new IllegalArgumentException("the policy call bound must be positive");
        }
    }
}
