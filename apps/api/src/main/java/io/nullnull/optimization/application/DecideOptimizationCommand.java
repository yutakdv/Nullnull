package io.nullnull.optimization.application;

import io.nullnull.optimization.domain.OptimizationDecisionKind;
import io.nullnull.trip.domain.TripValidationException;
import java.util.UUID;

/**
 * What the traveller decided, as the contract lets them say it.
 *
 * <p>REVERT is absent on purpose. {@code OptimizationDecisionRequest} admits only APPLY and KEEP -
 * a revert is a different operation on a different path, against a decision rather than a run - so
 * a command that could carry REVERT would accept a request the API never offers.
 */
public record DecideOptimizationCommand(UUID proposalId, OptimizationDecisionKind decision) {

    public DecideOptimizationCommand {
        if (proposalId == null) {
            throw new TripValidationException("proposalId", "NotNull", "proposalId is required");
        }
        if (decision == null) {
            throw new TripValidationException("decision", "NotNull", "decision is required");
        }
        if (decision == OptimizationDecisionKind.REVERT) {
            throw new TripValidationException("decision", "Enum", "decision must be APPLY or KEEP");
        }
    }
}
