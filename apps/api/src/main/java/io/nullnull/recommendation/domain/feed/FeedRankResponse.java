package io.nullnull.recommendation.domain.feed;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Mirrors {@code FeedRankResponse}; stageCounts are operational counters, never user data. */
public record FeedRankResponse(String policyVersion, String policyHash, String pipelineVersion, int sortVersion,
        int evaluated, List<UUID> orderedPostIds, Map<String, Integer> rejectedByReason, List<StageCount> stageCounts) {

    public record StageCount(String stage, int inputCount, int outputCount) {
    }

    public FeedRankResponse {
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(policyHash, "policyHash");
        Objects.requireNonNull(pipelineVersion, "pipelineVersion");
        orderedPostIds = List.copyOf(Objects.requireNonNull(orderedPostIds, "orderedPostIds"));
        rejectedByReason = Map.copyOf(Objects.requireNonNull(rejectedByReason, "rejectedByReason"));
        stageCounts = List.copyOf(Objects.requireNonNull(stageCounts, "stageCounts"));
    }
}
