package io.nullnull.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.catalog.application.CatalogHoursQuery;
import io.nullnull.catalog.application.CatalogHoursQuery.CatalogOpeningWindow;
import io.nullnull.identity.application.SessionService;
import io.nullnull.recommendation.application.RecommendationGateway;
import io.nullnull.recommendation.application.RecommendationUnavailableException;
import io.nullnull.recommendation.domain.item.OpeningWindowIn;
import io.nullnull.recommendation.domain.slot.SlotEvaluateRequest;
import io.nullnull.recommendation.domain.slot.SlotEvaluateResponse;
import io.nullnull.recommendation.domain.slot.SlotOut;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * BA-042 getCandidateTripMatches: which dates could hold this candidate, and what is sent to decide.
 *
 * <p>The evaluator is mocked because what is under test here is the hydration and the projection -
 * which facts leave this server, and which answer comes back - not the slot arithmetic, which is
 * {@code apps/ai}'s and has its own corpus (ADR-0006). The two mocked collaborators are exactly the
 * two boundaries this slice crosses.
 */
@SpringBootTest(properties = "nullnull.catalog.public-enabled=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-042 candidate trip matches")
class CandidateMatchIT {

    private static final LocalDate DAY_ONE = LocalDate.parse("2026-10-04");
    private static final LocalDate DAY_TWO = LocalDate.parse("2026-10-05");

    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean RecommendationGateway recommendations;
    @MockitoBean CatalogHoursQuery hours;

    @Test
    @DisplayName("BA-042-T1 a slot with a suggested time and one without are both answered, and differ")
    void slotsCarryTheirOwnSuggestedTime() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID placeId = place("후보 장소");
        UUID candidateId = candidate(owner, tripId, placeId);
        when(hours.windowsFor(any(), any(), any(), any())).thenReturn(Map.of());
        when(recommendations.evaluateSlots(any())).thenReturn(response(SlotEvaluateResponse.State.EXACT,
                List.of(new SlotOut(DAY_ONE, LocalTime.of(14, 0), true, null),
                        new SlotOut(DAY_TWO, null, true, null))));

        mvc.perform(get("/api/v1/trips/" + tripId + "/candidates/" + candidateId + "/matches")
                        .cookie(cookie(owner)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.candidateId").value(candidateId.toString()))
                .andExpect(jsonPath("$.state").value("EXACT"))
                // A date-only slot keeps a null time rather than borrowing its neighbour's. The two
                // shapes are different answers: one proposes an hour, the other proposes a day.
                .andExpect(jsonPath("$.slots[0].suggestedTime").value("14:00:00"))
                .andExpect(jsonPath("$.slots[1].suggestedTime").isEmpty())
                .andExpect(jsonPath("$.slots[1].eligible").value(true))
                // No place content anywhere in the response: this is why the operation is not behind
                // the catalog publication gate.
                .andExpect(jsonPath("$.slots[0].place").doesNotExist())
                .andExpect(jsonPath("$.place").doesNotExist());
    }

    @Test
    @DisplayName("BA-042-T5 a date nobody verified is sent as absent, never as closed")
    void unverifiedDatesAreOmittedRatherThanClosed() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID placeId = place("일부만 확인된 장소");
        UUID candidateId = candidate(owner, tripId, placeId);
        // Day one was verified as open; day two nobody looked at. The trip spans both.
        when(hours.windowsFor(any(), any(), any(), any())).thenReturn(Map.of(DAY_ONE,
                new CatalogOpeningWindow(CatalogOpeningWindow.State.OPEN, LocalTime.of(9, 0),
                        LocalTime.of(18, 0))));
        when(recommendations.evaluateSlots(any())).thenReturn(response(SlotEvaluateResponse.State.NONE,
                List.of()));

        mvc.perform(get("/api/v1/trips/" + tripId + "/candidates/" + candidateId + "/matches")
                .cookie(cookie(owner))).andExpect(status().isOk());

        SlotEvaluateRequest sent = captureRequest();
        assertThat(sent.openingHours()).containsOnlyKeys(DAY_ONE);
        assertThat(sent.openingHours().get(DAY_ONE).state())
                .isEqualTo(OpeningWindowIn.OpeningState.OPEN);
        // The assertion that matters: day two is ABSENT, not present-and-closed. A closed entry
        // would tell the evaluator the place is shut on a day nobody checked, and the evaluator
        // would then rule it out for a reason that is not true.
        assertThat(sent.openingHours()).doesNotContainKey(DAY_TWO);
    }

    @Test
    @DisplayName("BA-042-T7 the outgoing request never asks for CHECKING, because nothing verifies a candidate")
    void theRequestNeverClaimsAVerificationIsRunning() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID candidateId = candidate(owner, tripId, place("확인 중이 아닌 장소"));
        when(hours.windowsFor(any(), any(), any(), any())).thenReturn(Map.of());
        when(recommendations.evaluateSlots(any())).thenReturn(response(SlotEvaluateResponse.State.NONE,
                List.of()));

        mvc.perform(get("/api/v1/trips/" + tripId + "/candidates/" + candidateId + "/matches")
                .cookie(cookie(owner))).andExpect(status().isOk());

        // This is the evidence CandidateMatchStateCoverageTest points at for CHECKING. That state is
        // in the service's vocabulary, so no enum can rule it out; what rules it out is that the
        // input which reaches it is never set, and a call site is the only place that can be shown.
        assertThat(captureRequest().checking())
                .as("CHECKING needs a verification job, and P0 registers none")
                .isFalse();
        // Route evidence is NONE for the same kind of reason: P0 has no route provider, so the
        // evaluator is told there is none rather than being left to read silence as a confirmation.
        assertThat(captureRequest().routeEvidence())
                .isEqualTo(io.nullnull.recommendation.domain.item.ItemProposeRequest.RouteEvidence.NONE);
    }

    /**
     * BA-042-T4's half that lives on this side.
     *
     * <p>The clause is about day boundaries under DST, and none of that arithmetic happens here:
     * this server passes local dates and a zone id, catalog compares {@code effective_on} (a DATE)
     * against date bounds, and neither converts a date to an instant. Turning dates, a zone and
     * times into a verdict is the evaluator's job, so the 23- and 25-hour days belong to its corpus.
     *
     * <p>What Spring can get wrong is which zone and which range it sends - the server's default
     * instead of the trip's, or a range that is not the trip's own. That is what this pins.
     */
    @Test
    @DisplayName("BA-042-T4 the request carries the trip's own zone and date range, not the server's")
    void theTripsZoneAndRangeAreSentAsTheTripHoldsThem() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID candidateId = candidate(owner, tripId, place("시간대 장소"));
        when(hours.windowsFor(any(), any(), any(), any())).thenReturn(Map.of());
        when(recommendations.evaluateSlots(any())).thenReturn(response(SlotEvaluateResponse.State.NONE,
                List.of()));

        mvc.perform(get("/api/v1/trips/" + tripId + "/candidates/" + candidateId + "/matches")
                .cookie(cookie(owner))).andExpect(status().isOk());

        SlotEvaluateRequest sent = captureRequest();
        assertThat(sent.tripZone()).isEqualTo("Asia/Seoul");
        assertThat(sent.tripStart()).isEqualTo(DAY_ONE);
        assertThat(sent.tripEnd()).isEqualTo(DAY_TWO);
        // And catalog is asked about the same range, so a window can never be fetched for a date the
        // evaluator was not told about.
        verify(hours).windowsFor(any(), org.mockito.ArgumentMatchers.eq(DAY_ONE),
                org.mockito.ArgumentMatchers.eq(DAY_TWO), any());
    }

    @Test
    @DisplayName("BA-042-T7 an evaluator that does not answer is UNKNOWN, not NONE")
    void anUnavailableEvaluatorIsUnknown() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID candidateId = candidate(owner, tripId, place("평가기 장애 장소"));
        when(hours.windowsFor(any(), any(), any(), any())).thenReturn(Map.of());
        when(recommendations.evaluateSlots(any()))
                .thenThrow(new RecommendationUnavailableException("down", true, null));

        mvc.perform(get("/api/v1/trips/" + tripId + "/candidates/" + candidateId + "/matches")
                        .cookie(cookie(owner)))
                .andExpect(status().isOk())
                // NONE would claim every date was checked and rejected. UNKNOWN says the question
                // was not answered, which is what happened.
                .andExpect(jsonPath("$.state").value("UNKNOWN"))
                .andExpect(jsonPath("$.slots").isEmpty());
    }

    @Test
    @DisplayName("BA-042 asking about a candidate changes no trip, item or candidate row")
    void askingChangesNothing() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID candidateId = candidate(owner, tripId, place("읽기만 하는 장소"));
        when(hours.windowsFor(any(), any(), any(), any())).thenReturn(Map.of());
        when(recommendations.evaluateSlots(any())).thenReturn(response(SlotEvaluateResponse.State.EXACT,
                List.of(new SlotOut(DAY_ONE, null, true, null))));
        long versionBefore = version(tripId);

        mvc.perform(get("/api/v1/trips/" + tripId + "/candidates/" + candidateId + "/matches")
                .cookie(cookie(owner))).andExpect(status().isOk());

        // Invariants 1 and 2: matching is a question. A SavedPost, a TripCandidate and a TripItem
        // stay distinct, and asking where something could go does not put it there.
        assertThat(version(tripId)).isEqualTo(versionBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_items WHERE trip_id = ?",
                Integer.class, tripId)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM trip_candidates WHERE id = ?", String.class,
                candidateId)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("BA-042 another owner's candidate is not readable and not distinguishable from one that is gone")
    void aForeignCandidateIsNotFound() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        var stranger = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID candidateId = candidate(owner, tripId, place("남의 후보 장소"));

        mvc.perform(get("/api/v1/trips/" + tripId + "/candidates/" + candidateId + "/matches")
                .cookie(cookie(stranger))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/trips/" + tripId + "/candidates/" + UUID.randomUUID() + "/matches")
                .cookie(cookie(owner))).andExpect(status().isNotFound());
    }

    private SlotEvaluateRequest captureRequest() {
        ArgumentCaptor<SlotEvaluateRequest> captor = ArgumentCaptor.forClass(SlotEvaluateRequest.class);
        verify(recommendations, org.mockito.Mockito.atLeastOnce()).evaluateSlots(captor.capture());
        return captor.getValue();
    }

    private static SlotEvaluateResponse response(SlotEvaluateResponse.State state, List<SlotOut> slots) {
        return new SlotEvaluateResponse("policy-v1", "a".repeat(64), "nullnull-ai-pipeline-v1", state,
                slots, List.of());
    }

    private Cookie cookie(SessionService.Bootstrap owner) {
        return new Cookie("__Host-nullnull_session", owner.cookie);
    }

    private long version(UUID tripId) {
        return jdbc.queryForObject("SELECT version FROM trips WHERE id = ?", Long.class, tripId);
    }

    private UUID candidate(SessionService.Bootstrap owner, UUID tripId, UUID placeId) throws Exception {
        String body = mvc.perform(post("/api/v1/trips/" + tripId + "/candidates").cookie(cookie(owner))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "candidate-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"placeId\":\"" + placeId + "\",\"source\":{\"type\":\"SEARCH\"}}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(body.replaceFirst("(?s)^.*?\"id\":\"([^\"]+)\".*$", "$1"));
    }

    private UUID createTrip(SessionService.Bootstrap owner) throws Exception {
        String created = mvc.perform(post("/api/v1/trips").cookie(cookie(owner))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "trip-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"startDate\":\"2026-10-04\",\"endDate\":\"2026-10-05\","
                                + "\"timezone\":\"Asia/Seoul\",\"planningLevel\":\"NOTHING\","
                                + "\"interests\":[]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(created.replaceFirst("(?s)^.*?\"id\":\"([^\"]+)\".*$", "$1"));
    }

    private UUID place(String name) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO places (id, canonical_name, category_code, region_code, status,"
                + " created_at, updated_at) VALUES (?, ?, 'HS', '11', 'ACTIVE', ?, ?)", id, name, now, now);
        return id;
    }
}
