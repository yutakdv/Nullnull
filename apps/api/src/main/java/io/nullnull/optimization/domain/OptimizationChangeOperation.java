package io.nullnull.optimization.domain;

/**
 * What a single change does to the itinerary, in the contract's own vocabulary.
 *
 * <p>The three shapes the contract discriminates on collapse to five names here: MOVE, REORDER and
 * REPLACE all carry a before and an after, ADD carries only an after, REMOVE only a before. Which
 * halves are present is not a property of this enum but of the change, and {@link OptimizationChange}
 * is where that is enforced.
 */
public enum OptimizationChangeOperation {
    MOVE, REORDER, REPLACE, ADD, REMOVE;

    /** True when this operation describes an item that did not exist before the proposal. */
    public boolean createsTheItem() {
        return this == ADD;
    }

    /** True when this operation leaves no item behind. */
    public boolean removesTheItem() {
        return this == REMOVE;
    }
}
