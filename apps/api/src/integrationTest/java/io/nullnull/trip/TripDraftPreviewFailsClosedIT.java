package io.nullnull.trip;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.identity.application.SessionService;
import io.nullnull.recommendation.application.RecommendationGateway;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * BA-055 with the catalog publication flag at its DEFAULT, which is closed - the setting the
 * submission profile runs until staging call evidence exists. TripDraftPreviewIT opens the gate to
 * test the draft, so without this class nothing would look at the configuration users actually get.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-055 the trip draft preview is fail-closed while the catalog is")
class TripDraftPreviewFailsClosedIT {

    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    @MockitoBean RecommendationGateway recommendations;

    @Test
    @DisplayName("BA-055-T4 a closed catalog answers 503 SOURCE_UNAVAILABLE and never asks apps/ai")
    void aClosedCatalogIsRefusedBeforeTheServiceIsAsked() throws Exception {
        var owner = sessions.bootstrap(null, null, null);

        mvc.perform(post("/api/v1/trip-drafts/preview")
                        .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                        .header("Origin", "http://localhost:5173")
                        .contentType("application/json")
                        .content("{\"startDate\":\"2026-10-04\",\"endDate\":\"2026-10-05\",\"timezone\":\"Asia/Seoul\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SOURCE_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true));

        verify(recommendations, never()).composeDraft(any());
    }
}
