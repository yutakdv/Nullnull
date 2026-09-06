package io.nullnull.recommendation.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.nullnull.recommendation.application.RecommendationUnavailableException;
import io.nullnull.recommendation.domain.feed.FeedCandidateIn;
import io.nullnull.recommendation.domain.feed.FeedRankRequest;
import io.nullnull.recommendation.domain.feed.FeedRankResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpRecommendationGatewayTest {

    static final String BODY = """
            {"policyVersion":"policy-v1","policyHash":"%s","pipelineVersion":"nullnull-ai-pipeline-v1","sortVersion":1,
             "evaluated":2,"orderedPostIds":["00000000-0000-0000-0000-000000000001"],"rejectedByReason":{"NOT_PUBLISHED":1},
             "stageCounts":[{"stage":"source:request","inputCount":0,"outputCount":2}]}
            """.formatted("a".repeat(64));

    /** A response the record cannot instantiate: orderedPostIds is required by the contract. */
    static final String BODY_WITHOUT_ORDER = """
            {"policyVersion":"policy-v1","policyHash":"%s","pipelineVersion":"nullnull-ai-pipeline-v1","sortVersion":1,
             "evaluated":2,"rejectedByReason":{"NOT_PUBLISHED":1},
             "stageCounts":[{"stage":"source:request","inputCount":0,"outputCount":2}]}
            """.formatted("a".repeat(64));

    MockRestServiceServer server;
    HttpRecommendationGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ai.test:8090");
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new HttpRecommendationGateway(builder.build(), () -> "req_test-0001");
    }

    @Test
    void postsCamelCaseBodyAndPropagatesRequestId() {
        server.expect(requestTo("http://ai.test:8090/internal/v1/feed/rank")).andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Request-ID", "req_test-0001"))
                .andExpect(jsonPath("$.sortVersion").value(1))
                .andExpect(jsonPath("$.evaluatedAt").value("2026-09-06T00:00:00Z"))
                .andExpect(jsonPath("$.candidates[0].postId").value("00000000-0000-0000-0000-000000000001"))
                .andExpect(jsonPath("$.candidates[0].primaryPlaceId").value("018f3f8e-9b67-7a21-8d31-31d315b93a01"))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));
        FeedRankResponse response = gateway.rankFeed(requestFor("00000000-0000-0000-0000-000000000001"));
        assertThat(response.orderedPostIds()).containsExactly(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        assertThat(response.policyHash()).hasSize(64);
        server.verify();
    }

    @Test
    void serverErrorIsRetryableUnavailable() {
        // policy() is an idempotent GET, so it is attempted twice before the caller falls back.
        server.expect(ExpectedCount.times(2), requestTo("http://ai.test:8090/internal/v1/policy"))
                .andExpect(method(HttpMethod.GET)).andRespond(withServerError());
        assertThatThrownBy(gateway::policy).isInstanceOf(RecommendationUnavailableException.class)
                .satisfies(e -> assertThat(((RecommendationUnavailableException) e).retryable()).isTrue());
        server.verify();
    }

    @Test
    void feedRankIsNeverRetried() {
        server.expect(ExpectedCount.once(), requestTo("http://ai.test:8090/internal/v1/feed/rank"))
                .andRespond(withServerError());
        assertThatThrownBy(() -> gateway.rankFeed(requestFor("00000000-0000-0000-0000-000000000001")))
                .isInstanceOf(RecommendationUnavailableException.class)
                .satisfies(e -> assertThat(((RecommendationUnavailableException) e).retryable()).isTrue());
        server.verify();
    }

    @Test
    void clientErrorIsNotRetryable() {
        server.expect(ExpectedCount.once(), requestTo("http://ai.test:8090/internal/v1/policy"))
                .andRespond(withBadRequest());
        assertThatThrownBy(gateway::policy).isInstanceOf(RecommendationUnavailableException.class)
                .satisfies(e -> assertThat(((RecommendationUnavailableException) e).retryable()).isFalse());
        server.verify();
    }

    @Test
    void unreadableResponseIsRetryableUnavailable() {
        // A body the contract records reject must reach the documented fallback, not escape as a Spring exception.
        server.expect(ExpectedCount.once(), requestTo("http://ai.test:8090/internal/v1/feed/rank"))
                .andRespond(withSuccess(BODY_WITHOUT_ORDER, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> gateway.rankFeed(requestFor("00000000-0000-0000-0000-000000000001")))
                .isInstanceOf(RecommendationUnavailableException.class)
                .satisfies(e -> assertThat(((RecommendationUnavailableException) e).retryable()).isTrue());
        server.verify();
    }

    @Test
    void responseIdsOutsideTheRequestAreRejected() {
        server.expect(requestTo("http://ai.test:8090/internal/v1/feed/rank")).andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));
        FeedRankRequest request = requestFor("00000000-0000-0000-0000-000000000009");
        assertThatThrownBy(() -> gateway.rankFeed(request)).isInstanceOf(RecommendationUnavailableException.class)
                .hasMessageContaining("not in the request");
    }

    private static FeedRankRequest requestFor(String postId) {
        return new FeedRankRequest(Instant.parse("2026-09-06T00:00:00Z"), "ko", 1, List.of(
                new FeedCandidateIn(UUID.fromString(postId), Instant.parse("2026-09-05T00:00:00Z"),
                        FeedCandidateIn.PostStatus.PUBLISHED, UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93a01"))));
    }
}
