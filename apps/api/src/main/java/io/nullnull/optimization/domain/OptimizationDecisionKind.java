package io.nullnull.optimization.domain;

/**
 * What a traveller did about a run's proposal.
 *
 * <p>APPLY and KEEP are the two answers to "shall I change your trip"; REVERT is not a third answer
 * but an answer about an earlier APPLY. That asymmetry is why a run takes at most one of the first
 * two and may still take a REVERT afterwards, and why the contract publishes REVERT only from the
 * revert endpoint.
 */
public enum OptimizationDecisionKind {
    APPLY, KEEP, REVERT;

    /** True for the answers that close a run's question; a REVERT answers a decision instead. */
    public boolean isInitial() {
        return this != REVERT;
    }

    /** True when this decision changed the itinerary and therefore has a before and an after. */
    public boolean changesTheTrip() {
        return this != KEEP;
    }
}
