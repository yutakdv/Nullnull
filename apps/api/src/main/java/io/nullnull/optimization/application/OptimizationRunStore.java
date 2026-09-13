package io.nullnull.optimization.application;

import io.nullnull.optimization.domain.OptimizationFailureCode;
import io.nullnull.optimization.domain.OptimizationRun;
import io.nullnull.optimization.domain.OptimizationStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for runs and the evidence they froze. */
public interface OptimizationRunStore {

    void insert(OptimizationRun run);

    Optional<OptimizationRun> find(UUID runId);

    /** The owner's own run, so a foreign id is indistinguishable from one that does not exist. */
    Optional<OptimizationRun> findForOwner(UUID ownerId, UUID runId);

    /**
     * Moves a run from one status to another, and only from the one named.
     *
     * <p>The expected status is a parameter rather than a read-then-write, so two workers racing to
     * start the same run produce one winner and one no-op instead of two attempts that both believed
     * they had started it. That is also what makes "run 상태가 역행하지 않는다" hold under concurrency
     * rather than only in a single-threaded reading of the code.
     *
     * @return true when this caller performed the move
     */
    boolean transition(UUID runId, OptimizationStatus from, OptimizationStatus to, Instant at);

    /**
     * Records what the run froze: which snapshot sets were in force, and when the preview stops
     * being offerable.
     *
     * <p>Only while the run is still RUNNING - a worker whose lease lapsed must not overwrite the
     * evidence of the worker that took over.
     *
     * <p>Neither {@code data_fingerprint} nor {@code algorithm_version} is written here. Both are
     * §8 values derived from the recommendation service's answer, and this slice never asks for one.
     */
    boolean recordFrozenEvidence(UUID runId, Instant expiresAt, List<UUID> snapshotSetIds);

    /** Ends a run with a code and the sentence that goes with it. */
    boolean fail(UUID runId, OptimizationStatus from, OptimizationFailureCode code, String message,
            Instant at);
}
