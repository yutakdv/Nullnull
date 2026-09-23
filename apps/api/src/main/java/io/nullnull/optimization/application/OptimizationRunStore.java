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
     * evidence of the worker that took over. A retry can re-freeze the sets but never moves the
     * deadline: the first one recorded is the run's (#340).
     *
     * <p>Neither {@code data_fingerprint} nor {@code algorithm_version} is written here. Both are
     * §8 values derived from the recommendation service's answer, and this slice never asks for one.
     */
    boolean recordFrozenEvidence(UUID runId, Instant expiresAt, List<UUID> snapshotSetIds);

    /**
     * Publishes a preview: RUNNING to READY, with the evidence hash that APPLY will revalidate
     * against and the algorithm that produced it.
     *
     * <p>Separate from {@link #transition} because READY is the one status that cannot be entered on
     * its own. V024 refuses a READY row without a fingerprint and an expiry - a preview with no hash
     * has nothing for APPLY to check, and one with no expiry never stops being offerable - so the
     * status and the evidence are written together or not at all.
     *
     * <p>A run whose evidence was never frozen, or whose deadline is not after {@code at}, is refused
     * here rather than by the constraint or by the reader's 410, so the caller gets a decision it can
     * act on instead of a preview that is gone the moment it is stored (#340).
     *
     * @return true when this caller published the preview
     */
    boolean markReady(UUID runId, String dataFingerprint, String algorithmVersion,
            String policyVersion, String policyHash, String catalogVersion, Instant at);

    /**
     * Ends a run with a code and the sentence that goes with it - or, when the run's deadline had
     * already passed at {@code at}, as EXPIRED without either (#340): readers have been shown EXPIRED
     * since that deadline, and a terminal status a reader saw is not replaced by another.
     *
     * @return true when this call ended the run, whichever of the two it recorded
     */
    boolean fail(UUID runId, OptimizationStatus from, OptimizationFailureCode code, String message,
            Instant at);
}
