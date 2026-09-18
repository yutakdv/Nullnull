package io.nullnull.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.identity.application.SessionService;
import io.nullnull.recommendation.application.RecommendationGateway;
import io.nullnull.recommendation.domain.draft.DraftComposeRequest;
import io.nullnull.recommendation.domain.draft.DraftComposeResponse;
import io.nullnull.recommendation.domain.draft.DraftPlaceIn;
import io.nullnull.recommendation.domain.draft.DraftStopOut;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * previewTripDraft's real response, validated against {@code TripDraftPreview} with a 2020-12
 * evaluator. The contract example is checked by check-examples.mjs; this checks what the server
 * actually serialises - an Instant, a nested PlaceSummary, an empty day - which an example cannot.
 */
@SpringBootTest(properties = "nullnull.catalog.public-enabled=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("previewTripDraft response satisfies the published schema")
class TripDraftPreviewContractTest {

    @Autowired MockMvc mvc;
    @Autowired SessionService sessions;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean RecommendationGateway recommendations;

    private final UUID place = UUID.randomUUID();

    @AfterEach
    void removeOwnPlace() {
        jdbc.update("DELETE FROM places WHERE id = ?", place);
    }

    @Test
    @DisplayName("a READY draft with a stop and an empty day is a valid TripDraftPreview")
    void theServedDraftSatisfiesTheSchema() throws Exception {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO places
                    (id, canonical_name, category_code, latitude, longitude, region_code, status,
                     created_at, updated_at)
                VALUES (?, '계약 검사 장소', 'A0201', 37.579617, 126.977041, '11', 'ACTIVE', ?, ?)
                """, place, now, now);
        when(recommendations.composeDraft(any())).thenAnswer(invocation -> {
            DraftComposeRequest request = invocation.getArgument(0);
            // Place only this class's place, so the answer does not depend on what else the shared
            // gate database holds.
            boolean present = request.pool().stream().map(DraftPlaceIn::placeId).anyMatch(place::equals);
            List<DraftStopOut> stops = present
                    ? List.of(new DraftStopOut(place, request.tripStart(), 0, DraftStopOut.HoursState.UNKNOWN))
                    : List.of();
            return new DraftComposeResponse("policy-v1", "a".repeat(64), "nullnull-ai-pipeline-v1",
                    present ? DraftComposeResponse.State.READY : DraftComposeResponse.State.EMPTY, stops,
                    present ? List.of() : List.of("NO_ELIGIBLE_PLACES"), request.pool().size(), Map.of());
        });
        var owner = sessions.bootstrap(null, null, null);

        String body = mvc.perform(post("/api/v1/trip-drafts/preview")
                        .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                        .header("Origin", "http://localhost:5173")
                        .contentType("application/json")
                        .content("{\"startDate\":\"2026-10-04\",\"endDate\":\"2026-10-05\",\"timezone\":\"Asia/Seoul\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains(place.toString()).contains("\"stops\":[]");
        assertThat(new JsonSchemaCheck(OpenApiDocument.load()).validate("TripDraftPreview", body)).isEmpty();
    }
}
