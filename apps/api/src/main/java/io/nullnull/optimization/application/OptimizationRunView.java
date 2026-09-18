package io.nullnull.optimization.application;

import io.nullnull.optimization.domain.OptimizationRun;
import io.nullnull.optimization.domain.RevertAvailability;
import java.util.Objects;

/**
 * A run as a reader sees it: the stored row plus the one thing about it that is true only now.
 *
 * <p>The projection is carried beside the run rather than inside it because {@link OptimizationRun}
 * is what the store reads and writes, and a field that exists only in a response would have to be
 * null on every path that persists one. {@code TripView} pairs a trip with its candidate count for
 * the same reason.
 */
public record OptimizationRunView(OptimizationRun run, RevertAvailability revertAvailability) {

    public OptimizationRunView {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(revertAvailability, "revertAvailability");
    }
}
