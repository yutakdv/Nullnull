package io.nullnull.recommendation.domain.draft;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Mirrors {@code DraftComposeResponse}. {@code EMPTY} means no place could be put on any date - it is
 * an answer about the pool, not a stand-in for an outage. {@code reasons} carries the two codes the
 * service declares and {@code rejectedByReason} counts the places it skipped.
 */
public record DraftComposeResponse(String policyVersion, String policyHash, String pipelineVersion, State state,
        List<DraftStopOut> stops, List<String> reasons, int evaluated, Map<String, Integer> rejectedByReason) {

    public enum State { READY, EMPTY }

    /** The reason codes the internal contract declares; the gateway refuses any other. */
    public static final Set<String> REASONS = Set.of("NO_ELIGIBLE_PLACES", "ALL_DATES_FULL");

    public DraftComposeResponse {
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(policyHash, "policyHash");
        Objects.requireNonNull(pipelineVersion, "pipelineVersion");
        Objects.requireNonNull(state, "state");
        stops = List.copyOf(Objects.requireNonNull(stops, "stops"));
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
        rejectedByReason = Map.copyOf(Objects.requireNonNull(rejectedByReason, "rejectedByReason"));
    }
}
