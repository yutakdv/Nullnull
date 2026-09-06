package io.nullnull.recommendation.domain;

import java.util.List;

/**
 * Deterministic ordering and truncation of scored candidates. Tie-breaks use fixed keys only:
 * no randomness, wall clock, unordered map iteration or arrival order (§5.5, §6).
 */
@FunctionalInterface
public interface CandidateSelector<C> {

    List<C> select(List<C> scoredCandidates, SelectionPolicy policy);
}
