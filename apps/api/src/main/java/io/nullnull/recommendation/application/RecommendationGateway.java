package io.nullnull.recommendation.application;

import io.nullnull.recommendation.domain.PolicyDescriptor;
import io.nullnull.recommendation.domain.feed.FeedRankRequest;
import io.nullnull.recommendation.domain.feed.FeedRankResponse;
import io.nullnull.recommendation.domain.item.ItemProposeRequest;
import io.nullnull.recommendation.domain.item.ItemProposeResponse;
import io.nullnull.recommendation.domain.related.RelatedRankRequest;
import io.nullnull.recommendation.domain.related.RelatedRankResponse;
import io.nullnull.recommendation.domain.slot.SlotEvaluateRequest;
import io.nullnull.recommendation.domain.slot.SlotEvaluateResponse;

/**
 * Port to the recommendation service ({@code apps/ai}, internal contract v1,
 * {@code apps/ai/contracts/recommendation-internal-v1.json}). Callers hydrate every fact, never
 * send owner/session identifiers or raw itinerary text, and re-validate trip invariants before
 * persisting anything the service returns. Implementations live in {@code recommendation.infrastructure}.
 */
public interface RecommendationGateway {

    PolicyDescriptor policy();

    FeedRankResponse rankFeed(FeedRankRequest request);

    /**
     * ITEM optimization preview. The answer is never applied on its own: the caller re-validates the
     * trip's locks, range and version, and the user approves the change.
     */
    ItemProposeResponse proposeItem(ItemProposeRequest request);

    /**
     * Which trip dates could hold one candidate. The answer proposes dates only: a slot never carries
     * a time, and scheduling still goes through the trip's own validation and the user's approval.
     */
    SlotEvaluateResponse evaluateSlots(SlotEvaluateRequest request);

    /**
     * Which verified places relate to one source place. Only relations this API hydrated take part, and
     * the answer never claims a comparison the evidence does not support: an unsettled lookup comes back
     * as CHECKING or UNKNOWN rather than as an empty list.
     */
    RelatedRankResponse rankRelated(RelatedRankRequest request);
}
