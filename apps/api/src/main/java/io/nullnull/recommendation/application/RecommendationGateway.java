package io.nullnull.recommendation.application;

import io.nullnull.recommendation.domain.PolicyDescriptor;
import io.nullnull.recommendation.domain.feed.FeedRankRequest;
import io.nullnull.recommendation.domain.feed.FeedRankResponse;

/**
 * Port to the recommendation service ({@code apps/ai}, internal contract v1,
 * {@code apps/ai/contracts/recommendation-internal-v1.json}). Callers hydrate every fact, never
 * send owner/session identifiers or raw itinerary text, and re-validate trip invariants before
 * persisting anything the service returns. Implementations live in {@code recommendation.infrastructure}.
 */
public interface RecommendationGateway {

    PolicyDescriptor policy();

    FeedRankResponse rankFeed(FeedRankRequest request);
}
