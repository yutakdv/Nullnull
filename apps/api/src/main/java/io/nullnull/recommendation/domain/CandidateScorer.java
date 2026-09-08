package io.nullnull.recommendation.domain;

/**
 * Independent per-candidate score. The score of one candidate must not change when other
 * candidates are added, removed, reordered or split across batches (§6).
 */
@FunctionalInterface
public interface CandidateScorer<C> {

    ScoreBreakdown score(RecommendationContext context, C eligibleCandidate);
}
