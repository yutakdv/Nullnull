package io.nullnull.recommendation.domain.item;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Mirrors {@code ItemProposeResponse}. Exactly one outcome maps to each async failure plane, so a
 * caller never has to guess why nothing was proposed: LOCK_CONFLICT, ROUTE_UNAVAILABLE,
 * DATA_INSUFFICIENT and NO_IMPROVEMENT are distinct, and {@code reasons} carries internal codes only.
 */
public record ItemProposeResponse(String policyVersion, String policyHash, String pipelineVersion, Outcome outcome,
        List<ItemProposalOut> proposals, List<String> reasons, int evaluated, Map<String, Integer> rejectedByReason) {

    public enum Outcome { PROPOSALS, LOCK_CONFLICT, ROUTE_UNAVAILABLE, DATA_INSUFFICIENT, NO_IMPROVEMENT }

    public ItemProposeResponse {
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(policyHash, "policyHash");
        Objects.requireNonNull(pipelineVersion, "pipelineVersion");
        Objects.requireNonNull(outcome, "outcome");
        proposals = List.copyOf(Objects.requireNonNull(proposals, "proposals"));
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
        rejectedByReason = Map.copyOf(Objects.requireNonNull(rejectedByReason, "rejectedByReason"));
    }
}
