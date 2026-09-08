package io.nullnull.recommendation.domain.related;

import java.util.List;
import java.util.Objects;

/**
 * Mirrors {@code RelatedRankResponse}. {@code CHECKING} means a verification job is still running and
 * {@code UNKNOWN} that the lookup itself did not settle - both may still carry what is already known.
 * {@code NONE} is the only state that claims there is nothing to relate, and {@code reasons} carries
 * internal codes only.
 */
public record RelatedRankResponse(String policyVersion, String policyHash, String pipelineVersion, State state,
        List<RelatedItemOut> items, List<String> reasons) {

    public enum State { EXACT, SIMILAR, NONE, CHECKING, UNKNOWN }

    public RelatedRankResponse {
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(policyHash, "policyHash");
        Objects.requireNonNull(pipelineVersion, "pipelineVersion");
        Objects.requireNonNull(state, "state");
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
    }
}
