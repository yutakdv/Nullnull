package io.nullnull.recommendation.infrastructure;

import io.nullnull.recommendation.application.RecommendationGateway;
import io.nullnull.recommendation.application.RecommendationUnavailableException;
import io.nullnull.recommendation.domain.PolicyDescriptor;
import io.nullnull.recommendation.domain.feed.FeedCandidateIn;
import io.nullnull.recommendation.domain.feed.FeedRankRequest;
import io.nullnull.recommendation.domain.feed.FeedRankResponse;
import io.nullnull.recommendation.domain.item.ItemProposalOut;
import io.nullnull.recommendation.domain.item.ItemProposeRequest;
import io.nullnull.recommendation.domain.item.ItemProposeResponse;
import io.nullnull.recommendation.domain.item.TemporalCandidateIn;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Internal contract v1 over HTTP. Called outside any DB transaction. Response identifiers are
 * checked against the request so the service can never introduce an id Spring did not hydrate.
 * Only the idempotent GET is retried; {@code rankFeed} and {@code proposeItem} are POSTs and are
 * attempted once.
 */
public class HttpRecommendationGateway implements RecommendationGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpRecommendationGateway.class);

    /** One bounded retry for the idempotent policy read; the caller's fallback covers the rest. */
    private static final int POLICY_ATTEMPTS = 2;

    /** policy-v1 candidateCaps.itemProposals: the service may never rank more than three previews. */
    private static final int MAX_PROPOSALS = 3;

    private final RestClient client;
    private final Supplier<String> requestId;

    /** {@code client} already carries the base URL and the connect/read timeouts applied by {@link RecommendationClientConfiguration}. */
    public HttpRecommendationGateway(RestClient client, Supplier<String> requestId) {
        this.client = client;
        this.requestId = requestId;
    }

    @Override
    public PolicyDescriptor policy() {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= POLICY_ATTEMPTS; attempt++) {
            try {
                PolicyDescriptor descriptor = client.get().uri("/internal/v1/policy")
                        .header("X-Request-ID", requestId.get()).retrieve().body(PolicyDescriptor.class);
                if (descriptor == null) {
                    throw new RecommendationUnavailableException("empty policy response", true, null);
                }
                return descriptor;
            } catch (HttpClientErrorException exception) {
                throw rejected("policy", exception);
            } catch (RestClientException | HttpMessageConversionException exception) {
                // 5xx, unknown status, transport failure and an unreadable body all end in the fallback.
                log.warn("recommendation call failed operation=policy attempt={} of {}", attempt, POLICY_ATTEMPTS);
                lastFailure = exception;
            }
        }
        throw new RecommendationUnavailableException("recommendation service unavailable", true, lastFailure);
    }

    @Override
    public FeedRankResponse rankFeed(FeedRankRequest request) {
        FeedRankResponse response;
        try {
            response = client.post().uri("/internal/v1/feed/rank")
                    .header("X-Request-ID", requestId.get())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(FeedRankResponse.class);
        } catch (HttpClientErrorException exception) {
            throw rejected("feedRank", exception);
        } catch (RestClientException | HttpMessageConversionException exception) {
            throw new RecommendationUnavailableException("recommendation service unavailable", true, exception);
        }
        if (response == null) {
            throw new RecommendationUnavailableException("empty feed rank response", true, null);
        }
        Set<UUID> requested = new HashSet<>();
        for (FeedCandidateIn candidate : request.candidates()) {
            requested.add(candidate.postId());
        }
        for (UUID postId : response.orderedPostIds()) {
            if (!requested.contains(postId)) {
                throw new RecommendationUnavailableException("service returned a post id not in the request", false, null);
            }
        }
        return response;
    }

    @Override
    public ItemProposeResponse proposeItem(ItemProposeRequest request) {
        ItemProposeResponse response;
        try {
            response = client.post().uri("/internal/v1/items/propose")
                    .header("X-Request-ID", requestId.get())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(ItemProposeResponse.class);
        } catch (HttpClientErrorException exception) {
            throw rejected("itemPropose", exception);
        } catch (RestClientException | HttpMessageConversionException exception) {
            throw new RecommendationUnavailableException("recommendation service unavailable", true, exception);
        }
        if (response == null) {
            throw new RecommendationUnavailableException("empty item propose response", true, null);
        }
        verifyResponse(request, response);
        return response;
    }

    /** One (date, startTime) slot; a DAY-resolution candidate keeps the start time the item already has. */
    private record Slot(LocalDate date, LocalTime startTime) {
    }

    private record SnapshotPair(UUID before, UUID after) {
    }

    /**
     * A proposal may only name a slot and a snapshot pair this service hydrated, and the ranks must be
     * 1..n without a gap. A violation is a contract break, not an outage: retrying cannot fix it.
     */
    private static void verifyResponse(ItemProposeRequest request, ItemProposeResponse response) {
        if (response.policyHash().isBlank()) {
            throw unusable("item proposal response carries no policy hash");
        }
        if (response.proposals().size() > MAX_PROPOSALS) {
            throw unusable("service returned more item proposals than the policy allows");
        }
        Set<Slot> slots = new HashSet<>();
        Set<SnapshotPair> pairs = new HashSet<>();
        for (TemporalCandidateIn candidate : request.candidates()) {
            slots.add(new Slot(candidate.date(), candidate.effectiveStartTime(request.target().startTime())));
            pairs.add(new SnapshotPair(candidate.beforeSnapshotId(), candidate.afterSnapshotId()));
        }
        int expectedRank = 1;
        for (ItemProposalOut proposal : response.proposals()) {
            if (proposal.rank() != expectedRank++) {
                throw unusable("item proposal ranks must run 1..n without a gap");
            }
            if (!slots.contains(new Slot(proposal.date(), proposal.startTime()))) {
                throw unusable("service proposed a slot that was not in the request");
            }
            if (!pairs.contains(new SnapshotPair(proposal.beforeSnapshotId(), proposal.afterSnapshotId()))) {
                throw unusable("service returned a snapshot pair that was not in the request");
            }
        }
    }

    /** The service answered, but the answer breaks the contract: never retry, never persist. */
    private static RecommendationUnavailableException unusable(String message) {
        log.error("recommendation response rejected — {}", message);
        return new RecommendationUnavailableException(message, false, null);
    }

    /** A 4xx means this service hydrated an invalid request: an alert, not a transient outage. */
    private RecommendationUnavailableException rejected(String operation, HttpClientErrorException exception) {
        log.error("recommendation request rejected operation={} status={} — hydration bug in the API, retry will not help",
                operation, exception.getStatusCode().value());
        return new RecommendationUnavailableException("recommendation request rejected", false, exception);
    }
}
