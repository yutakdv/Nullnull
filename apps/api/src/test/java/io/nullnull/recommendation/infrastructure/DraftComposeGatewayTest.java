package io.nullnull.recommendation.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.nullnull.recommendation.application.RecommendationUnavailableException;
import io.nullnull.recommendation.domain.draft.DraftComposeRequest;
import io.nullnull.recommendation.domain.draft.DraftComposeResponse;
import io.nullnull.recommendation.domain.draft.DraftPlaceIn;
import io.nullnull.recommendation.domain.draft.DraftStopOut;
import io.nullnull.recommendation.domain.item.OpeningWindowIn;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * BA-055: the post-condition {@code composeDraft} holds {@code apps/ai}'s answer to.
 *
 * <p>Each refusal is its own case with its own well-formed-but-wrong body, so dropping any one rule
 * turns exactly one case red. Every refusal is {@code retryable: false}: the service answered, the
 * answer breaks the contract, and the same request would get the same answer.
 */
class DraftComposeGatewayTest {

    static final String URL = "http://ai.test:8090/internal/v1/drafts/compose";
    static final LocalDate DAY_ONE = LocalDate.of(2026, 10, 4);
    static final LocalDate DAY_TWO = LocalDate.of(2026, 10, 5);
    static final UUID OPEN_ON_DAY_ONE = UUID.fromString("00000000-0000-7000-8000-000000000001");
    static final UUID CLOSED_ON_DAY_ONE = UUID.fromString("00000000-0000-7000-8000-000000000002");
    static final UUID UNVERIFIED = UUID.fromString("00000000-0000-7000-8000-000000000003");
    static final UUID OUTSIDE_THE_POOL = UUID.fromString("00000000-0000-7000-8000-0000000000ff");

    MockRestServiceServer server;
    HttpRecommendationGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ai.test:8090");
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new HttpRecommendationGateway(builder.build(), () -> "req_test-0001");
    }

    /** Two dates, a cap of two, three places: one verified OPEN and one verified CLOSED on day one, one unverified. */
    static DraftComposeRequest request() {
        return new DraftComposeRequest(Instant.parse("2026-09-18T02:00:00Z"), DAY_ONE, DAY_TWO, "Asia/Seoul", 2,
                List.of(new DraftPlaceIn(OPEN_ON_DAY_ONE,
                                Map.of(DAY_ONE, OpeningWindowIn.open(LocalTime.of(9, 0), LocalTime.of(18, 0)))),
                        new DraftPlaceIn(CLOSED_ON_DAY_ONE, Map.of(DAY_ONE, OpeningWindowIn.closed())),
                        new DraftPlaceIn(UNVERIFIED, Map.of())));
    }

    static String stop(UUID placeId, LocalDate date, int position, String hoursState) {
        return """
                {"placeId":"%s","date":"%s","position":%d,"hoursState":"%s"}""".formatted(placeId, date, position,
                hoursState);
    }

    static String body(String state, String... stops) {
        return """
                {"policyVersion":"policy-v1","policyHash":"%s","pipelineVersion":"nullnull-ai-pipeline-v1",
                 "state":"%s","stops":[%s],"reasons":[],"evaluated":3,"rejectedByReason":{}}
                """.formatted("a".repeat(64), state, String.join(",", stops));
    }

    void answer(String body) {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    void assertRejectedAsAContractBreak(String reason) {
        assertThatThrownBy(() -> gateway.composeDraft(request()))
                .isInstanceOf(RecommendationUnavailableException.class)
                .hasMessageContaining(reason)
                .satisfies(e -> assertThat(((RecommendationUnavailableException) e).retryable()).isFalse());
        server.verify();
    }

    @Test
    @DisplayName("a valid draft is read, and the request goes out camelCase with the verified windows only")
    void aValidDraftIsRead() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.tripStart").value("2026-10-04"))
                .andExpect(jsonPath("$.maxStopsPerDay").value(2))
                .andExpect(jsonPath("$.pool[0].openingHours['2026-10-04'].state").value("OPEN"))
                .andExpect(jsonPath("$.pool[1].openingHours['2026-10-04'].state").value("CLOSED"))
                .andExpect(jsonPath("$.pool[2].openingHours").isEmpty())
                .andRespond(withSuccess(body("READY", stop(OPEN_ON_DAY_ONE, DAY_ONE, 0, "OPEN"),
                        stop(CLOSED_ON_DAY_ONE, DAY_TWO, 0, "UNKNOWN"), stop(UNVERIFIED, DAY_ONE, 1, "UNKNOWN")),
                        MediaType.APPLICATION_JSON));

        DraftComposeResponse response = gateway.composeDraft(request());

        assertThat(response.state()).isEqualTo(DraftComposeResponse.State.READY);
        assertThat(response.stops()).extracting(DraftStopOut::placeId)
                .containsExactly(OPEN_ON_DAY_ONE, CLOSED_ON_DAY_ONE, UNVERIFIED);
        server.verify();
    }

    @Test
    @DisplayName("BA-055-T7 a stop naming a place outside the pool is refused as a contract break")
    void aPlaceOutsideThePoolIsRefused() {
        answer(body("READY", stop(OUTSIDE_THE_POOL, DAY_ONE, 0, "UNKNOWN")));
        assertRejectedAsAContractBreak("not in the pool");
    }

    @Test
    @DisplayName("BA-055-T15 a place placed twice is refused as a contract break")
    void aDuplicatePlaceIsRefused() {
        answer(body("READY", stop(UNVERIFIED, DAY_ONE, 0, "UNKNOWN"), stop(UNVERIFIED, DAY_TWO, 0, "UNKNOWN")));
        assertRejectedAsAContractBreak("placed once");
    }

    @Test
    @DisplayName("BA-055-T16 a date holding more stops than the per-day cap is refused as a contract break")
    void aDayOverTheCapIsRefused() {
        // Three distinct pool places on one date, positions 0..2 without a gap: only the cap is wrong.
        answer(body("READY", stop(OPEN_ON_DAY_ONE, DAY_TWO, 0, "UNKNOWN"),
                stop(CLOSED_ON_DAY_ONE, DAY_TWO, 1, "UNKNOWN"), stop(UNVERIFIED, DAY_TWO, 2, "UNKNOWN")));
        assertRejectedAsAContractBreak("more stops on one date");
    }

    @Test
    @DisplayName("BA-055-T17 positions with a gap on one date are refused as a contract break")
    void aPositionGapIsRefused() {
        answer(body("READY", stop(OPEN_ON_DAY_ONE, DAY_ONE, 0, "OPEN"), stop(UNVERIFIED, DAY_ONE, 2, "UNKNOWN")));
        assertRejectedAsAContractBreak("0..n-1");
    }

    @Test
    @DisplayName("BA-055-T13 a stop on a date verified as closed is refused as a contract break")
    void aStopOnAVerifiedClosedDateIsRefused() {
        answer(body("READY", stop(CLOSED_ON_DAY_ONE, DAY_ONE, 0, "UNKNOWN")));
        assertRejectedAsAContractBreak("verified as closed");
    }

    @Test
    @DisplayName("BA-055-T14 a stop on an unverified date that claims OPEN is refused as a contract break")
    void anUnverifiedDateCannotBeLabelledOpen() {
        answer(body("READY", stop(UNVERIFIED, DAY_ONE, 0, "OPEN")));
        assertRejectedAsAContractBreak("verified OPEN window");
    }

    @Test
    @DisplayName("BA-055-T18 a READY draft with no stop is refused, so an empty answer cannot pass as a proposal")
    void theStateAgreesWithTheStops() {
        answer(body("READY"));
        assertRejectedAsAContractBreak("EMPTY exactly when");
    }

    @Test
    @DisplayName("BA-055-T19 a reason the internal contract does not declare is refused")
    void anUndeclaredReasonIsRefused() {
        answer(body("EMPTY").replace("\"reasons\":[]", "\"reasons\":[\"CROWDED\"]"));
        assertRejectedAsAContractBreak("does not declare");
    }

    @Test
    @DisplayName("BA-055-T21 a stop dated outside the trip is refused as a contract break")
    void aStopOutsideTheTripIsRefused() {
        answer(body("READY", stop(UNVERIFIED, DAY_TWO.plusDays(1), 0, "UNKNOWN")));
        assertRejectedAsAContractBreak("outside the trip range");
    }

    @Test
    @DisplayName("BA-055-T22 an answer carrying no policy hash is refused as a contract break")
    void anAnswerWithoutAPolicyHashIsRefused() {
        // Otherwise a valid one-stop draft, so the blank hash is the only thing wrong with it.
        answer(body("READY", stop(UNVERIFIED, DAY_ONE, 0, "UNKNOWN")).replace("a".repeat(64), ""));
        assertRejectedAsAContractBreak("no policy hash");
    }

    @Test
    @DisplayName("a 4xx is a hydration bug and not retryable; a 5xx is an outage and is")
    void rejectionAndOutageAreTold() {
        server.expect(requestTo(URL)).andRespond(withBadRequest());
        assertThatThrownBy(() -> gateway.composeDraft(request()))
                .isInstanceOf(RecommendationUnavailableException.class)
                .satisfies(e -> assertThat(((RecommendationUnavailableException) e).retryable()).isFalse());
        server.verify();
        server.reset();
        server.expect(requestTo(URL)).andRespond(withServerError());
        assertThatThrownBy(() -> gateway.composeDraft(request()))
                .isInstanceOf(RecommendationUnavailableException.class)
                .satisfies(e -> assertThat(((RecommendationUnavailableException) e).retryable()).isTrue());
        server.verify();
    }
}
