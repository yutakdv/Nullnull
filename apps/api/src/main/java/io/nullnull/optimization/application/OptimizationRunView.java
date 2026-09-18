package io.nullnull.optimization.application;

import io.nullnull.optimization.domain.OptimizationDecision;
import io.nullnull.optimization.domain.OptimizationRun;
import io.nullnull.optimization.domain.RevertAvailability;
import java.util.List;
import java.util.Objects;

/**
 * A run as a reader sees it: the stored row, what was proposed and decided under it, and the one
 * thing about it that is true only now.
 *
 * <p>The projection is carried beside the run rather than inside it because {@link OptimizationRun}
 * is what the store reads and writes, and a field that exists only in a response would have to be
 * null on every path that persists one. {@code TripView} pairs a trip with its candidate count for
 * the same reason.
 *
 * <p>{@code decisions} are in the order the contract describes - the initial APPLY or KEEP, then
 * the REVERT of an APPLY - which {@code OptimizationService} arranges by kind rather than by time.
 */
public record OptimizationRunView(OptimizationRun run, RevertAvailability revertAvailability,
        List<OptimizationProposalView> proposals, List<OptimizationDecision> decisions) {

    public OptimizationRunView {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(revertAvailability, "revertAvailability");
        proposals = List.copyOf(Objects.requireNonNull(proposals, "proposals"));
        decisions = List.copyOf(Objects.requireNonNull(decisions, "decisions"));
    }
}
