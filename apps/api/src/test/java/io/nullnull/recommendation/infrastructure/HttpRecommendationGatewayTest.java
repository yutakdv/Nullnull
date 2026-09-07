package io.nullnull.recommendation.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.nullnull.crowd.domain.ComparisonReasonCode;
import io.nullnull.recommendation.application.RecommendationUnavailableException;
import io.nullnull.recommendation.domain.explanation.ExplanationRenderRequest;
import io.nullnull.recommendation.domain.explanation.ExplanationRenderResponse;
import io.nullnull.recommendation.domain.feed.FeedCandidateIn;
import io.nullnull.recommendation.domain.feed.FeedRankRequest;
import io.nullnull.recommendation.domain.feed.FeedRankResponse;
import io.nullnull.recommendation.domain.item.ItemProposeRequest;
import io.nullnull.recommendation.domain.item.ItemProposeResponse;
import io.nullnull.recommendation.domain.item.OpeningWindowIn;
import io.nullnull.recommendation.domain.item.TargetItemIn;
import io.nullnull.recommendation.domain.item.TemporalCandidateIn;
import io.nullnull.recommendation.domain.related.PlaceCategoryIn;
import io.nullnull.recommendation.domain.related.RelatedItemOut;
import io.nullnull.recommendation.domain.related.RelatedRankRequest;
import io.nullnull.recommendation.domain.related.RelatedRankRequest.LookupOutcome;
import io.nullnull.recommendation.domain.related.RelatedRankResponse;
import io.nullnull.recommendation.domain.related.RelationCandidateIn;
import io.nullnull.recommendation.domain.related.RelationCandidateIn.MappingCertainty;
import io.nullnull.recommendation.domain.related.RelationCandidateIn.RelationTier;
import io.nullnull.recommendation.domain.slot.SlotEvaluateRequest;
import io.nullnull.recommendation.domain.slot.SlotEvaluateResponse;
import io.nullnull.recommendation.domain.slot.SlotOut;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
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
    static final UUID CANDIDATE = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93e01");
    static final UUID BEFORE_SNAPSHOT = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93d01");
    static final UUID AFTER_SNAPSHOT = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93d02");
    static final UUID RELATED_ONE = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93a11");
    static final UUID RELATED_TWO = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93a12");
    static final String TAXONOMY = "taxonomy-test-1";
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

    /** One candidate's date slots. The service always answers a date and never a time (P0, §5.3). */
    static final String SLOT_BODY = """
            {"policyVersion":"policy-v1","policyHash":"%s","pipelineVersion":"nullnull-ai-pipeline-v1",
             "state":"%s","reasons":[],"slots":[%s]}
            """;

    static String slot(String date, String suggestedTime, boolean eligible, String reasonCode) {
        return """
                {"date":"%s","suggestedTime":%s,"eligible":%s,"reasonCode":%s}""".formatted(date,
                suggestedTime == null ? "null" : "\"" + suggestedTime + "\"", eligible,
                reasonCode == null ? "null" : "\"" + reasonCode + "\"");
    }

    static String slotBody(String state, String... slots) {
        return SLOT_BODY.formatted("a".repeat(64), state, String.join(",", slots));
    }

    private static SlotEvaluateRequest slotRequest() {
        return slotRequest(D14, 60);
    }

    private static SlotEvaluateRequest slotRequest(LocalDate tripEnd, Integer durationMinutes) {
        return new SlotEvaluateRequest(Instant.parse("2026-09-06T00:00:00Z"), TRIP, CANDIDATE, PLACE, D12, tripEnd,
                "Asia/Seoul", durationMinutes, List.of(),
                Map.of(D12, OpeningWindowIn.open(LocalTime.of(9, 0), LocalTime.of(18, 0))), List.of(),
                ItemProposeRequest.RouteEvidence.NONE, 20, false);
    }

    private static void expectSlots(MockRestServiceServer server, String body) {
        server.expect(ExpectedCount.once(), requestTo("http://ai.test:8090/internal/v1/slots/evaluate"))
                .andExpect(method(HttpMethod.POST)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    void evaluateSlotsPostsHydratedFactsAndReadsDateOnlySlots() {
        server.expect(requestTo("http://ai.test:8090/internal/v1/slots/evaluate")).andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Request-ID", "req_test-0001"))
                .andExpect(jsonPath("$.candidateId").value(CANDIDATE.toString()))
                .andExpect(jsonPath("$.tripZone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.maxItemsPerDay").value(20))
                .andExpect(jsonPath("$.checking").value(false))
                .andExpect(jsonPath("$.datesWithSamePlace").isEmpty())
                .andExpect(jsonPath("$.openingHours['2026-09-12'].state").value("OPEN"))
                .andRespond(withSuccess(slotBody("EXACT", slot("2026-09-12", null, true, null),
                        slot("2026-09-13", null, false, "CLOSED")), MediaType.APPLICATION_JSON));
        SlotEvaluateResponse response = gateway.evaluateSlots(slotRequest());
        assertThat(response.state()).isEqualTo(SlotEvaluateResponse.State.EXACT);
        assertThat(response.slots()).extracting(SlotOut::date).containsExactly(D12, D13);
        assertThat(response.slots()).allSatisfy(slot -> assertThat(slot.suggestedTime()).isNull());
        assertThat(response.slots().get(1).reasonCode()).isEqualTo("CLOSED");
        server.verify();
    }

    @Test
    void aSlotOutsideTheTripRangeIsRejected() {
        expectSlots(server, slotBody("EXACT", slot("2026-09-15", null, true, null)));
        assertThatThrownBy(() -> gateway.evaluateSlots(slotRequest()))
                .isInstanceOf(RecommendationUnavailableException.class).hasMessageContaining("outside the trip range")
                .satisfies(e -> assertThat(((RecommendationUnavailableException) e).retryable()).isFalse());
    }

    @Test
    void slotDatesMustAscendWithoutARepeat() {
        expectSlots(server, slotBody("EXACT", slot("2026-09-13", null, true, null),
                slot("2026-09-13", null, false, "CLOSED")));
        assertThatThrownBy(() -> gateway.evaluateSlots(slotRequest()))
                .isInstanceOf(RecommendationUnavailableException.class).hasMessageContaining("ascend without a repeat");
    }

    @Test
    void aSuggestedTimeIsRejectedBecauseP0NeverInventsOne() {
        expectSlots(server, slotBody("EXACT", slot("2026-09-12", "10:00:00", true, null)));
        assertThatThrownBy(() -> gateway.evaluateSlots(slotRequest()))
                .isInstanceOf(RecommendationUnavailableException.class).hasMessageContaining("suggested time")
                .satisfies(e -> assertThat(((RecommendationUnavailableException) e).retryable()).isFalse());
    }

    @Test
    void aSlotWithoutAReasonForItsRefusalIsRejected() {
        expectSlots(server, slotBody("NONE", slot("2026-09-12", null, false, null)));
        assertThatThrownBy(() -> gateway.evaluateSlots(slotRequest()))
                .isInstanceOf(RecommendationUnavailableException.class).hasMessageContaining("reason code exactly when");
    }

    @Test
    void anExactStateWithoutAnEligibleSlotIsRejected() {
        expectSlots(server, slotBody("EXACT", slot("2026-09-12", null, false, "CLOSED")));
        assertThatThrownBy(() -> gateway.evaluateSlots(slotRequest()))
                .isInstanceOf(RecommendationUnavailableException.class)
                .hasMessageContaining("EXACT needs at least one eligible slot");
    }

    @Test
    void anUnsettledStateThatStillOffersASlotIsRejected() {
        expectSlots(server, slotBody("UNKNOWN", slot("2026-09-12", null, true, null)));
        assertThatThrownBy(() -> gateway.evaluateSlots(slotRequest()))
                .isInstanceOf(RecommendationUnavailableException.class)
                .hasMessageContaining("only EXACT or CHECKING");
    }

    @Test
    void moreSlotsThanThePolicyCapAreRejected() {
        // The service truncates a long trip at candidateCaps.slotDates (30) and reports DATE_CAP_EXCEEDED.
        String[] slots = new String[31];
        for (int offset = 0; offset < slots.length; offset++) {
            slots[offset] = slot(D12.plusDays(offset).toString(), null, true, null);
        }
        expectSlots(server, slotBody("EXACT", slots));
        SlotEvaluateRequest request = slotRequest(D12.plusDays(40), 60);
        assertThatThrownBy(() -> gateway.evaluateSlots(request))
                .isInstanceOf(RecommendationUnavailableException.class).hasMessageContaining("more slots than the policy");
    }

    @Test
    void anUnverifiedStayLengthIsSentAsAnExplicitNull() {
        // durationMinutes is required-nullable in the contract, so an omitted key would be a 422 at runtime.
        server.expect(requestTo("http://ai.test:8090/internal/v1/slots/evaluate"))
                .andExpect(content().string(containsString("\"durationMinutes\":null")))
                .andRespond(withSuccess(slotBody("EXACT", slot("2026-09-12", null, true, null)),
                        MediaType.APPLICATION_JSON));
        assertThat(gateway.evaluateSlots(slotRequest(D14, null)).slots()).hasSize(1);
        server.verify();
    }

    @Test
    void evaluateSlotsIsNeverRetried() {
        server.expect(ExpectedCount.once(), requestTo("http://ai.test:8090/internal/v1/slots/evaluate"))
                .andRespond(withServerError());
        SlotEvaluateRequest request = slotRequest();
        assertThatThrownBy(() -> gateway.evaluateSlots(request)).isInstanceOf(RecommendationUnavailableException.class)
                .satisfies(e -> assertThat(((RecommendationUnavailableException) e).retryable()).isTrue());
        server.verify();
    }

    @Test
    void aRejectedSlotRequestIsNotRetryable() {
        server.expect(ExpectedCount.once(), requestTo("http://ai.test:8090/internal/v1/slots/evaluate"))
                .andRespond(withBadRequest());
        SlotEvaluateRequest request = slotRequest();
        assertThatThrownBy(() -> gateway.evaluateSlots(request)).isInstanceOf(RecommendationUnavailableException.class)
                .satisfies(e -> assertThat(((RecommendationUnavailableException) e).retryable()).isFalse());
        server.verify();
    }

    /** One source place's verified relations. `categoryMatch` travels as a string, null when unknown. */
    static final String RELATED_BODY = """
            {"policyVersion":"policy-v1","policyHash":"%s","pipelineVersion":"nullnull-ai-pipeline-v1",
             "state":"%s","reasons":[],"items":[%s]}
            """;

    static String relatedItem(UUID placeId, String tier, String categoryMatch, int evidenceCount, String... channels) {
        return """
                {"placeId":"%s","tier":"%s","categoryMatch":%s,"evidenceCount":%d,"channels":[%s]}""".formatted(placeId,
                tier, categoryMatch == null ? "null" : "\"" + categoryMatch + "\"", evidenceCount,
                Arrays.stream(channels).map(channel -> "\"" + channel + "\"").collect(Collectors.joining(",")));
    }

    static String relatedBody(String state, String... items) {
        return RELATED_BODY.formatted("a".repeat(64), state, String.join(",", items));
    }

    private static RelationCandidateIn relation(UUID target, RelationTier tier, String channel) {
        return new RelationCandidateIn(PLACE, target, tier, "KTO_RELATED_PLACES", channel, new BigDecimal("0.7"),
                Instant.parse("2026-09-05T00:00:00Z"), null, MappingCertainty.CERTAIN);
    }

    private static RelatedRankRequest relatedRequest(RelationCandidateIn... candidates) {
        return new RelatedRankRequest(Instant.parse("2026-09-06T00:00:00Z"), PLACE,
                new PlaceCategoryIn(PLACE, "PALACE", "HERITAGE", TAXONOMY), List.of(candidates),
                List.of(new PlaceCategoryIn(RELATED_ONE, "PALACE", "HERITAGE", TAXONOMY)), LookupOutcome.COMPLETE);
    }

    private static RelatedRankRequest relatedRequest() {
        return relatedRequest(relation(RELATED_ONE, RelationTier.EXACT, "kto-direct"),
                relation(RELATED_TWO, RelationTier.SIMILAR, "category"));
    }

    private static void expectRelated(MockRestServiceServer server, String body) {
        server.expect(ExpectedCount.once(), requestTo("http://ai.test:8090/internal/v1/related/rank"))
                .andExpect(method(HttpMethod.POST)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    void rankRelatedPostsHydratedRelationsAndReadsCategoryMatchExactly() {
        server.expect(requestTo("http://ai.test:8090/internal/v1/related/rank")).andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Request-ID", "req_test-0001"))
                .andExpect(jsonPath("$.sourcePlaceId").value(PLACE.toString()))
                .andExpect(jsonPath("$.sourceCategory.categoryCode").value("PALACE"))
                .andExpect(jsonPath("$.lookupOutcome").value("COMPLETE"))
                .andExpect(jsonPath("$.candidates[0].tier").value("EXACT"))
                .andExpect(jsonPath("$.candidates[0].mapping").value("CERTAIN"))
                .andExpect(jsonPath("$.candidates[0].channel").value("kto-direct"))
                // expiresAt is required-nullable in the contract, so an omitted key would be a 422 at runtime.
                .andExpect(content().string(containsString("\"expiresAt\":null")))
                .andRespond(withSuccess(relatedBody("EXACT",
                        relatedItem(RELATED_ONE, "EXACT", "1", 2, "category", "kto-direct"),
                        relatedItem(RELATED_TWO, "SIMILAR", null, 1, "category")), MediaType.APPLICATION_JSON));
        RelatedRankResponse response = gateway.rankRelated(relatedRequest());
        assertThat(response.state()).isEqualTo(RelatedRankResponse.State.EXACT);
        assertThat(response.items()).extracting(RelatedItemOut::placeId).containsExactly(RELATED_ONE, RELATED_TWO);
        assertThat(response.items().get(0).categoryMatch()).isEqualByComparingTo("1");
        assertThat(response.items().get(0).channels()).containsExactly("category", "kto-direct");
        assertThat(response.items().get(1).categoryMatch()).as("a missing category is unknown, not 0").isNull();
        server.verify();
    }

    @Test
    void anUnsettledLookupMayStillCarryWhatIsAlreadyVerified() {
        expectRelated(server, relatedBody("CHECKING", relatedItem(RELATED_TWO, "SIMILAR", "0", 1, "category")));
        assertThat(gateway.rankRelated(relatedRequest()).state()).isEqualTo(RelatedRankResponse.State.CHECKING);
        server.verify();
    }

    @Test
    void aRelatedPlaceOutsideTheRequestIsRejected() {
        expectRelated(server, relatedBody("EXACT", relatedItem(ITEM, "EXACT", "1", 1, "kto-direct")));
        assertThatThrownBy(() -> gateway.rankRelated(relatedRequest()))
                .isInstanceOf(RecommendationUnavailableException.class)
                .hasMessageContaining("related place that was not in the request")
                .satisfies(e -> assertThat(((RecommendationUnavailableException) e).retryable()).isFalse());
    }

    @Test
    void aRelatedPlaceThatIsTheSourceIsRejected() {
        // The request does carry a self-referencing row, so the target check alone would let it through.
        expectRelated(server, relatedBody("EXACT", relatedItem(PLACE, "EXACT", "1", 1, "kto-direct")));
        RelatedRankRequest request = relatedRequest(relation(PLACE, RelationTier.EXACT, "kto-direct"));
        assertThatThrownBy(() -> gateway.rankRelated(request)).isInstanceOf(RecommendationUnavailableException.class)
                .hasMessageContaining("never related to itself");
    }

    @Test
    void aRepeatedRelatedPlaceIsRejected() {
        expectRelated(server, relatedBody("EXACT", relatedItem(RELATED_ONE, "EXACT", "1", 1, "kto-direct"),
                relatedItem(RELATED_ONE, "EXACT", "1", 1, "kto-direct")));
        assertThatThrownBy(() -> gateway.rankRelated(relatedRequest()))
                .isInstanceOf(RecommendationUnavailableException.class).hasMessageContaining("merged, not repeated");
    }

    @Test
    void aRelatedPlaceWithoutEvidenceIsRejected() {
        expectRelated(server, relatedBody("EXACT", relatedItem(RELATED_ONE, "EXACT", "1", 0, "kto-direct")));
        assertThatThrownBy(() -> gateway.rankRelated(relatedRequest()))
                .isInstanceOf(RecommendationUnavailableException.class)
                .hasMessageContaining("at least one evidence row");
    }

    @Test
    void aChannelOutsideTheRequestIsRejected() {
        expectRelated(server, relatedBody("EXACT", relatedItem(RELATED_ONE, "EXACT", "1", 1, "popularity")));
        assertThatThrownBy(() -> gateway.rankRelated(relatedRequest()))
                .isInstanceOf(RecommendationUnavailableException.class)
                .hasMessageContaining("channel that was not in the request");
    }

    @Test
    void aStateThatDisagreesWithTheFirstRelatedPlaceIsRejected() {
        // All three expectations are registered before the first call: the mock server is ordered.
        expectRelated(server, relatedBody("EXACT", relatedItem(RELATED_TWO, "SIMILAR", "0", 1, "category")));
        expectRelated(server, relatedBody("SIMILAR", relatedItem(RELATED_ONE, "EXACT", "1", 1, "kto-direct")));
        expectRelated(server, relatedBody("NONE", relatedItem(RELATED_ONE, "EXACT", "1", 1, "kto-direct")));
        assertThatThrownBy(() -> gateway.rankRelated(relatedRequest()))
                .isInstanceOf(RecommendationUnavailableException.class).hasMessageContaining("EXACT needs an EXACT");
        assertThatThrownBy(() -> gateway.rankRelated(relatedRequest()))
                .isInstanceOf(RecommendationUnavailableException.class)
                .hasMessageContaining("SIMILAR needs a SIMILAR");
        assertThatThrownBy(() -> gateway.rankRelated(relatedRequest()))
                .isInstanceOf(RecommendationUnavailableException.class)
                .hasMessageContaining("NONE carries no related place");
        server.verify();
    }

    @Test
    void moreRelatedPlacesThanThePolicyCapAreRejected() {
        // policy-v1 candidateCaps.relatedMerged = 300: the service truncates and reports MERGED_CAP_EXCEEDED.
        String[] items = new String[301];
        RelationCandidateIn[] candidates = new RelationCandidateIn[301];
        for (int index = 0; index < items.length; index++) {
            UUID target = new UUID(1L, index);
            items[index] = relatedItem(target, "SIMILAR", null, 1, "category");
            candidates[index] = relation(target, RelationTier.SIMILAR, "category");
        }
        expectRelated(server, relatedBody("SIMILAR", items));
        RelatedRankRequest request = relatedRequest(candidates);
        assertThatThrownBy(() -> gateway.rankRelated(request))
                .isInstanceOf(RecommendationUnavailableException.class)
                .hasMessageContaining("more related places than the policy");
    }

    @Test
    void rankRelatedIsNeverRetried() {
        server.expect(ExpectedCount.once(), requestTo("http://ai.test:8090/internal/v1/related/rank"))
                .andRespond(withServerError());
        RelatedRankRequest request = relatedRequest();
        assertThatThrownBy(() -> gateway.rankRelated(request)).isInstanceOf(RecommendationUnavailableException.class)
                .satisfies(e -> assertThat(((RecommendationUnavailableException) e).retryable()).isTrue());
        server.verify();
    }

    @Test
    void aRejectedRelatedRequestIsNotRetryable() {
        server.expect(ExpectedCount.once(), requestTo("http://ai.test:8090/internal/v1/related/rank"))
                .andRespond(withBadRequest());
        RelatedRankRequest request = relatedRequest();
        assertThatThrownBy(() -> gateway.rankRelated(request)).isInstanceOf(RecommendationUnavailableException.class)
                .satisfies(e -> assertThat(((RecommendationUnavailableException) e).retryable()).isFalse());
        server.verify();
    }

    /** One explanation: the sentence, and which writer produced it. */
    static final String EXPLANATION_BODY = """
            {"policyVersion":"policy-v1","policyHash":"%s","pipelineVersion":"nullnull-ai-pipeline-v1",
             "summary":"%s","source":"%s"}
            """;

    static final String ATTRIBUTION = "Source: Korea Tourism Organization";
    static final String SENTENCE = "Moving Gyeongbokgung from Sep 12 10:00 to Sep 12 12:00 lowers relative "
            + "concentration index from 80 to 60 (20 points). " + ATTRIBUTION;

    static String explanationBody(String summary, String source) {
        return EXPLANATION_BODY.formatted("a".repeat(64), summary, source);
    }

    private static ExplanationRenderRequest explanationRequest() {
        return new ExplanationRenderRequest("en", "Gyeongbokgung", D12, LocalTime.of(10, 0), D12, LocalTime.of(12, 0),
                new BigDecimal("80"), new BigDecimal("60"), "relative concentration index", ATTRIBUTION, "issue-1");
    }

    private static void expectExplanation(MockRestServiceServer server, String body) {
        server.expect(ExpectedCount.once(), requestTo("http://ai.test:8090/internal/v1/explanations/render"))
                .andExpect(method(HttpMethod.POST)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    void renderExplanationPostsOnlyAllowedFactsAndReadsTheSentenceAndItsWriter() {
        server.expect(requestTo("http://ai.test:8090/internal/v1/explanations/render")).andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Request-ID", "req_test-0001"))
                .andExpect(jsonPath("$.locale").value("en"))
                .andExpect(jsonPath("$.placeName").value("Gyeongbokgung"))
                .andExpect(jsonPath("$.beforeTime").value("10:00:00"))
                .andExpect(jsonPath("$.afterDate").value("2026-09-12"))
                .andExpect(jsonPath("$.attribution").value(ATTRIBUTION))
                .andExpect(jsonPath("$.forecastIssueId").value("issue-1"))
                // No owner, session, coordinate or itinerary text belongs in an explanation (§9.1).
                .andExpect(jsonPath("$.ownerId").doesNotExist())
                .andRespond(withSuccess(explanationBody(SENTENCE, "TEMPLATE"), MediaType.APPLICATION_JSON));
        ExplanationRenderResponse response = gateway.renderExplanation(explanationRequest());
        assertThat(response.summary()).isEqualTo(SENTENCE);
        assertThat(response.source()).isEqualTo("TEMPLATE");
        assertThat(response.policyHash()).hasSize(64);
        server.verify();
    }

    @Test
    void anAcceptedModelRewriteIsReportedAsSuch() {
        expectExplanation(server, explanationBody(SENTENCE, "LLM"));
        assertThat(gateway.renderExplanation(explanationRequest()).source())
                .as("a model outage is visible as TEMPLATE, never as a missing explanation")
                .isEqualTo("LLM");
        server.verify();
    }

    @Test
    void aDateOnlySlotSendsExplicitNullsRatherThanOmittingTheKeys() {
        server.expect(requestTo("http://ai.test:8090/internal/v1/explanations/render"))
                .andExpect(content().string(containsString("\"beforeTime\":null")))
                .andExpect(content().string(containsString("\"afterTime\":null")))
                .andRespond(withSuccess(explanationBody(SENTENCE, "TEMPLATE"), MediaType.APPLICATION_JSON));
        ExplanationRenderRequest request = new ExplanationRenderRequest("en", "Gyeongbokgung", D12, null, D13, null,
                new BigDecimal("80"), new BigDecimal("60"), "relative concentration index", ATTRIBUTION, null);
        assertThat(gateway.renderExplanation(request).summary()).isEqualTo(SENTENCE);
        server.verify();
    }

    @Test
    void anExplanationThatDroppedItsAttributionIsRejected() {
        expectExplanation(server, explanationBody("The index falls from 80 to 60 (20 points).", "LLM"));
        assertThatThrownBy(() -> gateway.renderExplanation(explanationRequest()))
                .isInstanceOf(RecommendationUnavailableException.class)
                .hasMessageContaining("source attribution")
                .satisfies(e -> assertThat(((RecommendationUnavailableException) e).retryable()).isFalse());
        server.verify();
    }

    @Test
    void anEmptyLongOrMultiLineExplanationIsRejected() {
        // All three expectations are registered before the first call: the mock server is ordered.
        expectExplanation(server, explanationBody("   ", "TEMPLATE"));
        expectExplanation(server, explanationBody("a".repeat(501), "TEMPLATE"));
        expectExplanation(server, explanationBody(SENTENCE.replace("lowers", "\\nlowers"), "TEMPLATE"));
        assertThatThrownBy(() -> gateway.renderExplanation(explanationRequest()))
                .isInstanceOf(RecommendationUnavailableException.class).hasMessageContaining("empty sentence");
        assertThatThrownBy(() -> gateway.renderExplanation(explanationRequest()))
                .isInstanceOf(RecommendationUnavailableException.class).hasMessageContaining("longer than the contract");
        assertThatThrownBy(() -> gateway.renderExplanation(explanationRequest()))
                .isInstanceOf(RecommendationUnavailableException.class).hasMessageContaining("single line");
        server.verify();
    }

    @Test
    void anExplanationWithoutAPolicyHashIsRejected() {
        expectExplanation(server, EXPLANATION_BODY.formatted("", SENTENCE, "TEMPLATE"));
        assertThatThrownBy(() -> gateway.renderExplanation(explanationRequest()))
                .isInstanceOf(RecommendationUnavailableException.class).hasMessageContaining("no policy hash");
        server.verify();
    }

    @Test
    void aWriterOutsideTheTwoPublishedValuesIsAContractBreakAndNotAnOutage() {
        expectExplanation(server, explanationBody(SENTENCE, "ORACLE"));
        assertThatThrownBy(() -> gateway.renderExplanation(explanationRequest()))
                .isInstanceOf(RecommendationUnavailableException.class)
                .hasMessageContaining("writer outside the two published values")
                .as("retrying cannot turn an unknown writer into a known one")
                .satisfies(e -> assertThat(((RecommendationUnavailableException) e).retryable()).isFalse());
        server.verify();
    }

    @Test
    void renderExplanationIsNeverRetriedAndARejectedRequestIsNotRetryable() {
        server.expect(ExpectedCount.once(), requestTo("http://ai.test:8090/internal/v1/explanations/render"))
                .andRespond(withServerError());
        server.expect(ExpectedCount.once(), requestTo("http://ai.test:8090/internal/v1/explanations/render"))
                .andRespond(withBadRequest());
        assertThatThrownBy(() -> gateway.renderExplanation(explanationRequest()))
                .isInstanceOf(RecommendationUnavailableException.class)
                .satisfies(e -> assertThat(((RecommendationUnavailableException) e).retryable()).isTrue());
        assertThatThrownBy(() -> gateway.renderExplanation(explanationRequest()))
                .isInstanceOf(RecommendationUnavailableException.class)
                .satisfies(e -> assertThat(((RecommendationUnavailableException) e).retryable()).isFalse());
        server.verify();
    }

    private static FeedRankRequest requestFor(String postId) {
        return new FeedRankRequest(Instant.parse("2026-09-06T00:00:00Z"), "ko", 1, List.of(
                new FeedCandidateIn(UUID.fromString(postId), Instant.parse("2026-09-05T00:00:00Z"),
                        FeedCandidateIn.PostStatus.PUBLISHED, UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93a01"))));
    }
}
