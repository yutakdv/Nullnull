package io.nullnull.recommendation.infrastructure;

import io.nullnull.recommendation.application.RecommendationGateway;
import io.nullnull.recommendation.application.RecommendationUnavailableException;
import io.nullnull.recommendation.domain.PolicyDescriptor;
import io.nullnull.recommendation.domain.feed.FeedCandidateIn;
import io.nullnull.recommendation.domain.feed.FeedRankRequest;
import io.nullnull.recommendation.domain.feed.FeedRankResponse;
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
 * Only the idempotent GET is retried; {@code rankFeed} is a POST and is attempted once.
 */
public class HttpRecommendationGateway implements RecommendationGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpRecommendationGateway.class);

    /** One bounded retry for the idempotent policy read; the caller's fallback covers the rest. */
    private static final int POLICY_ATTEMPTS = 2;

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

    /** A 4xx means this service hydrated an invalid request: an alert, not a transient outage. */
    private RecommendationUnavailableException rejected(String operation, HttpClientErrorException exception) {
        log.error("recommendation request rejected operation={} status={} — hydration bug in the API, retry will not help",
                operation, exception.getStatusCode().value());
        return new RecommendationUnavailableException("recommendation request rejected", false, exception);
    }
}
