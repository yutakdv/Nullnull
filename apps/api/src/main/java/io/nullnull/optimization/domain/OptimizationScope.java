package io.nullnull.optimization.domain;

/**
 * What an optimization run was asked to move.
 *
 * <p>All three are in the contract's enum and only {@code ITEM} runs in P0. The other two are not
 * dropped from the vocabulary, because a request enum a client may send is not something a build can
 * narrow without breaking it; they are refused at the boundary instead, with the reason named
 * ({@code OptimizationCapability}). ENVIRONMENT.md §6 lists FEATURE_OPTIMIZATION_DAY and
 * FEATURE_OPTIMIZATION_TRIP as P1, and neither has a property in this application: an OFF flag for a
 * feature with no implementation would be a true sentence about nothing.
 */
public enum OptimizationScope {

    ITEM(true),
    DAY(false),
    TRIP(false);

    private final boolean availableInP0;

    OptimizationScope(boolean availableInP0) {
        this.availableInP0 = availableInP0;
    }

    public boolean availableInP0() {
        return availableInP0;
    }

    public static OptimizationScope of(String value) {
        for (OptimizationScope scope : values()) {
            if (scope.name().equals(value)) {
                return scope;
            }
        }
        throw new IllegalArgumentException("unknown optimization scope: " + value);
    }
}
