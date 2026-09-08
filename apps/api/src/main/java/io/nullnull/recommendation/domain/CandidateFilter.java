package io.nullnull.recommendation.domain;

/** Hard eligibility check over an immutable, already hydrated candidate. Pure and side-effect free. */
@FunctionalInterface
public interface CandidateFilter<C> {

    Eligibility evaluate(RecommendationContext context, C candidate);
}
