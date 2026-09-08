package io.nullnull.recommendation.domain.slot;

import java.util.List;
import java.util.Objects;

/**
 * Mirrors {@code SlotEvaluateResponse}. {@code CHECKING} means a verification job is still running,
 * {@code UNKNOWN} means a fact is missing and {@code NONE} means every date was refused on known
 * facts; only {@code EXACT} (or a {@code CHECKING} run) may carry an eligible slot. P0 never answers
 * SIMILAR, the fifth value of the public enum, and {@code reasons} carries internal codes only.
 */
public record SlotEvaluateResponse(String policyVersion, String policyHash, String pipelineVersion, State state,
        List<SlotOut> slots, List<String> reasons) {

    public enum State { EXACT, CHECKING, UNKNOWN, NONE }

    public SlotEvaluateResponse {
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(policyHash, "policyHash");
        Objects.requireNonNull(pipelineVersion, "pipelineVersion");
        Objects.requireNonNull(state, "state");
        slots = List.copyOf(Objects.requireNonNull(slots, "slots"));
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
    }
}
