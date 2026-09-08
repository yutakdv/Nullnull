package io.nullnull.recommendation.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Fixed evaluation inputs shared by every stage of one recommendation computation.
 * Reproduction unit for P0 is input snapshot + evaluatedAt + catalog/taxonomy version + source
 * snapshot ids + policy hash (§6). Trip and source snapshot bundles are added by the callers
 * that own them; this record never reads a clock, a database or the network.
 *
 * @param evaluatedAt    the single "now" used for freshness, expiry and time arithmetic
 * @param policyVersion  e.g. {@code policy-v1}
 * @param policyHash     SHA-256 hex of the policy file bytes
 * @param catalogVersion catalog/taxonomy version the candidate facts were hydrated from
 */
public record RecommendationContext(Instant evaluatedAt, String policyVersion, String policyHash,
        String catalogVersion) {

    public RecommendationContext {
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        requireNonBlank(policyVersion, "policyVersion");
        requireNonBlank(policyHash, "policyHash");
        requireNonBlank(catalogVersion, "catalogVersion");
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
