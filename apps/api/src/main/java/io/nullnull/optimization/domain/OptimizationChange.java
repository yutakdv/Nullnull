package io.nullnull.optimization.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * One edit inside a proposal: what an item looks like now, and what it would look like.
 *
 * <p>Both states are carried as JSON rather than a typed item, because what is stored is the diff a
 * traveller was shown - not a live item that later editing could change underneath it. A proposal
 * read back a day later has to say what was proposed then.
 *
 * <p>{@code tripItemId} is not a foreign key in the table and is not resolved here either: an ADD
 * reserves an id for an item that exists only if the proposal is applied.
 */
public record OptimizationChange(UUID id, UUID tripItemId, OptimizationChangeOperation operation,
        String beforeValue, String afterValue, int sequence) {

    public OptimizationChange {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tripItemId, "tripItemId");
        Objects.requireNonNull(operation, "operation");
        // The same rule V029 writes as optimization_changes_before_check and _after_check, here so a
        // change built in memory cannot be shaped in a way the database would refuse. Both
        // directions: an ADD must have no before, and anything that is not an ADD must have one.
        if ((beforeValue == null) != operation.createsTheItem()) {
            throw new IllegalArgumentException(operation + " carries a before state unless it is an ADD");
        }
        if ((afterValue == null) != operation.removesTheItem()) {
            throw new IllegalArgumentException(operation + " carries an after state unless it is a REMOVE");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
    }
}
