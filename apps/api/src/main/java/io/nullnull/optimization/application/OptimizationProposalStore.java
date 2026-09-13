package io.nullnull.optimization.application;

import io.nullnull.optimization.domain.OptimizationProposal;
import java.util.List;
import java.util.UUID;

/**
 * Persistence boundary for what a run proposed.
 *
 * <p>There is no update and no delete of a single proposal, and that is the interface saying what
 * V029's triggers enforce: a proposal is what a traveller was shown, so it is written once and read
 * afterwards. Proposals leave when their run does, by the cascade.
 */
public interface OptimizationProposalStore {

    /**
     * Stores a run's proposals and their changes together.
     *
     * <p>One call rather than one per proposal, because a run's proposals are ranked against each
     * other: a partial set is not a smaller answer, it is a different one.
     */
    void insertAll(List<OptimizationProposal> proposals);

    /** A run's proposals in rank order, each with its changes in sequence order. */
    List<OptimizationProposal> findByRun(UUID runId);
}
