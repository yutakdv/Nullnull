package io.nullnull.recommendation.domain;

/**
 * Hard eligibility outcome. {@code UNKNOWN} is never promoted to eligible
 * (docs/architecture/RECOMMENDATION_ALGORITHM.md §3.1).
 */
public enum EligibilityState {
    ELIGIBLE,
    INELIGIBLE,
    UNKNOWN
}
