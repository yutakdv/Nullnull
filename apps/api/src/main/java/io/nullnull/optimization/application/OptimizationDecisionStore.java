package io.nullnull.optimization.application;

import io.nullnull.optimization.domain.OptimizationDecision;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What a traveller decided about a run.
 *
 * <p>A decision is written once and never edited - V030 puts a trigger behind that - so there is no
 * update here. A REVERT is a second row that points at the APPLY it undoes, which is why this reads
 * back a list rather than "the decision".
 */
public interface OptimizationDecisionStore {

    /**
     * Records the run's initial decision, and answers whether this call is the one that recorded it.
     *
     * <p>Returns false when the run already has an initial decision. That is decided by the database
     * - {@code optimization_decisions_one_initial_per_run} is a partial unique index over
     * {@code run_id} where {@code decision <> 'REVERT'} - and not by a read this method did first.
     * Two callers racing to APPLY the same run therefore produce one row and one refusal even if
     * every application-level check above is removed, which is the second line under BA-052-T1.
     *
     * <p>The boolean rather than an exception is the same shape {@code OptimizationRunStore.transition}
     * already uses for mutual exclusion in this module: the caller asked to be the one who decides,
     * and the answer is yes or no.
     */
    boolean insertIfFirst(OptimizationDecision decision);

    /** A run's decisions, oldest first - an APPLY and the REVERT that undoes it read as a sequence. */
    List<OptimizationDecision> findByRun(UUID runId);

    /**
     * The owner's own decision, so a foreign id is indistinguishable from one that does not exist.
     *
     * <p>The same shape {@code OptimizationRunStore.findForOwner} has, and for invariant 11's reason:
     * a 403 on someone else's decision answers the question the caller was actually asking.
     */
    Optional<OptimizationDecision> findForOwner(UUID ownerId, UUID decisionId);
}
