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

import io.nullnull.crowd.domain.ComparisonReasonCode;
import io.nullnull.recommendation.application.RecommendationUnavailableException;
import io.nullnull.recommendation.domain.feed.FeedCandidateIn;
import io.nullnull.recommendation.domain.feed.FeedRankRequest;
import io.nullnull.recommendation.domain.feed.FeedRankResponse;
import io.nullnull.recommendation.domain.item.ItemProposeRequest;
import io.nullnull.recommendation.domain.item.ItemProposeResponse;
import io.nullnull.recommendation.domain.item.OpeningWindowIn;
import io.nullnull.recommendation.domain.item.TargetItemIn;
import io.nullnull.recommendation.domain.item.TemporalCandidateIn;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
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

    static final UUID TRIP = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93c01");
    static final UUID PLACE = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93a01");
    static final UUID ITEM = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93b01");
    static final UUID BEFORE_SNAPSHOT = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93d01");
    static final UUID AFTER_SNAPSHOT = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93d02");
    static final LocalDate D12 = LocalDate.of(2026, 9, 12);
    static final LocalDate D13 = LocalDate.of(2026, 9, 13);
    static final LocalDate D14 = LocalDate.of(2026, 9, 14);

    /** The service answers with string decimals; Spring must read them as exact BigDecimal values. */
    static final String PROPOSAL_BODY = """
            {"policyVersion":"policy-v1","policyHash":"%s","pipelineVersion":"nullnull-ai-pipeline-v1",
             "outcome":"PROPOSALS","reasons":[],"evaluated":1,"rejectedByReason":{},
             "proposals":[{"rank":%d,"date":"2026-09-13","startTime":"%s",
              "beforeInstant":"2026-09-12T01:00:00Z","afterInstant":"2026-09-13T01:00:00Z",
              "score":"0.120000","improvement":"40","relief":"0.400000","changeCost":"1.000000",
              "beforeSnapshotId":"%s","afterSnapshotId":"%s","lockChecks":{"DATE":true}}]}
            """;

    static String proposalBody(int rank, String startTime, UUID beforeSnapshot, UUID afterSnapshot) {
        return PROPOSAL_BODY.formatted("a".repeat(64), rank, startTime, beforeSnapshot, afterSnapshot);
    }

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

    /** One HOUR candidate at 10:00 on D13, or a DAY candidate that keeps the item's own 10:00. */
    private static ItemProposeRequest proposeRequest(LocalTime candidateTime) {
        TemporalCandidateIn candidate = new TemporalCandidateIn(PLACE, D13, candidateTime,
                candidateTime == null ? TemporalCandidateIn.ForecastResolution.DAY
                        : TemporalCandidateIn.ForecastResolution.HOUR,
                new BigDecimal("80"), new BigDecimal("40"), "KTO_RELATIVE_CONCENTRATION_INDEX", true,
                ComparisonReasonCode.SAME_METRIC_AND_ISSUE, BEFORE_SNAPSHOT, AFTER_SNAPSHOT);
        return new ItemProposeRequest(Instant.parse("2026-09-06T00:00:00Z"), TRIP, 7, D12, D14, "Asia/Seoul",
                new TargetItemIn(ITEM, PLACE, D12, LocalTime.of(10, 0), 90, 1), List.of(), List.of(),
                Map.of(D12, OpeningWindowIn.open(LocalTime.of(9, 0), LocalTime.of(18, 0))),
                ItemProposeRequest.RouteEvidence.NONE, List.of(candidate));
    }

    private static void expectPropose(MockRestServiceServer server, String body) {
        server.expect(ExpectedCount.once(), requestTo("http://ai.test:8090/internal/v1/items/propose"))
                .andExpect(method(HttpMethod.POST)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    void proposeItemPostsHydratedFactsAndReadsDecimalsExactly() {
        server.expect(requestTo("http://ai.test:8090/internal/v1/items/propose")).andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Request-ID", "req_test-0001"))
                .andExpect(jsonPath("$.tripZone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.target.startTime").value("10:00:00"))
                .andExpect(jsonPath("$.routeEvidence").value("NONE"))
                .andExpect(jsonPath("$.openingHours['2026-09-12'].state").value("OPEN"))
                .andExpect(jsonPath("$.candidates[0].resolution").value("HOUR"))
                .andExpect(jsonPath("$.candidates[0].verdictReasonCode").value("SAME_METRIC_AND_ISSUE"))
                .andExpect(jsonPath("$.candidates[0].beforeSnapshotId").value(BEFORE_SNAPSHOT.toString()))
                .andRespond(withSuccess(proposalBody(1, "10:00:00", BEFORE_SNAPSHOT, AFTER_SNAPSHOT),
                        MediaType.APPLICATION_JSON));
        ItemProposeResponse response = gateway.proposeItem(proposeRequest(LocalTime.of(10, 0)));
        assertThat(response.outcome()).isEqualTo(ItemProposeResponse.Outcome.PROPOSALS);
        assertThat(response.proposals()).singleElement().satisfies(proposal -> {
            assertThat(proposal.score()).isEqualByComparingTo("0.120000");
            assertThat(proposal.changeCost()).isEqualByComparingTo("1.000000");
            assertThat(proposal.startTime()).isEqualTo(LocalTime.of(10, 0));
            assertThat(proposal.lockChecks()).containsEntry("DATE", true);
        });
        server.verify();
    }

    @Test
    void aDayResolutionCandidateKeepsTheItemsOwnStartTime() {
        // The candidate carries no time, so 10:00 is the item's current start time, not an invented slot.
        expectPropose(server, proposalBody(1, "10:00:00", BEFORE_SNAPSHOT, AFTER_SNAPSHOT));
        assertThat(gateway.proposeItem(proposeRequest(null)).proposals()).hasSize(1);
        server.verify();
    }

    @Test
    void aProposedSlotOutsideTheRequestIsRejected() {
        expectPropose(server, proposalBody(1, "11:00:00", BEFORE_SNAPSHOT, AFTER_SNAPSHOT));
        ItemProposeRequest request = proposeRequest(LocalTime.of(10, 0));
        assertThatThrownBy(() -> gateway.proposeItem(request)).isInstanceOf(RecommendationUnavailableException.class)
                .hasMessageContaining("slot that was not in the request")
                .satisfies(e -> assertThat(((RecommendationUnavailableException) e).retryable()).isFalse());
    }

    @Test
    void aSnapshotPairOutsideTheRequestIsRejected() {
        expectPropose(server, proposalBody(1, "10:00:00", AFTER_SNAPSHOT, BEFORE_SNAPSHOT));
        ItemProposeRequest request = proposeRequest(LocalTime.of(10, 0));
        assertThatThrownBy(() -> gateway.proposeItem(request)).isInstanceOf(RecommendationUnavailableException.class)
                .hasMessageContaining("snapshot pair that was not in the request")
                .satisfies(e -> assertThat(((RecommendationUnavailableException) e).retryable()).isFalse());
    }

    @Test
    void ranksThatDoNotStartAtOneAreRejected() {
        expectPropose(server, proposalBody(2, "10:00:00", BEFORE_SNAPSHOT, AFTER_SNAPSHOT));
        ItemProposeRequest request = proposeRequest(LocalTime.of(10, 0));
        assertThatThrownBy(() -> gateway.proposeItem(request)).isInstanceOf(RecommendationUnavailableException.class)
                .hasMessageContaining("ranks must run 1..n")
                .satisfies(e -> assertThat(((RecommendationUnavailableException) e).retryable()).isFalse());
    }

    @Test
    void proposeItemIsNeverRetried() {
        server.expect(ExpectedCount.once(), requestTo("http://ai.test:8090/internal/v1/items/propose"))
                .andRespond(withServerError());
        ItemProposeRequest request = proposeRequest(LocalTime.of(10, 0));
        assertThatThrownBy(() -> gateway.proposeItem(request)).isInstanceOf(RecommendationUnavailableException.class)
                .satisfies(e -> assertThat(((RecommendationUnavailableException) e).retryable()).isTrue());
        server.verify();
    }

    @Test
    void aRejectedProposeRequestIsNotRetryable() {
        server.expect(ExpectedCount.once(), requestTo("http://ai.test:8090/internal/v1/items/propose"))
                .andRespond(withBadRequest());
        ItemProposeRequest request = proposeRequest(LocalTime.of(10, 0));
        assertThatThrownBy(() -> gateway.proposeItem(request)).isInstanceOf(RecommendationUnavailableException.class)
                .satisfies(e -> assertThat(((RecommendationUnavailableException) e).retryable()).isFalse());
        server.verify();
    }

    private static FeedRankRequest requestFor(String postId) {
        return new FeedRankRequest(Instant.parse("2026-09-06T00:00:00Z"), "ko", 1, List.of(
                new FeedCandidateIn(UUID.fromString(postId), Instant.parse("2026-09-05T00:00:00Z"),
                        FeedCandidateIn.PostStatus.PUBLISHED, UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93a01"))));
    }
}
