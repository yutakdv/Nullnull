package io.nullnull.crowd;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * queryPlaceCrowdForecasts with the catalog publication flag at its DEFAULT, which is closed - the
 * setting the submission profile runs until staging call evidence exists. CrowdForecastQueryIT opens
 * the gate to test the batch, so without this class nothing would look at the configuration users get.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-023 the batch crowd forecast query is fail-closed while the catalog is")
class CrowdForecastQueryFailsClosedIT {

    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;

    @Test
    @DisplayName("BA-023-T13 a closed catalog answers the batch 503 SOURCE_UNAVAILABLE")
    void aClosedCatalogAnswersTheWholeBatchOnce() throws Exception {
        query("{\"placeIds\":[\"" + UUID.randomUUID() + "\",\"" + UUID.randomUUID()
                + "\"],\"from\":\"2026-10-04T15:00:00Z\",\"to\":\"2026-10-05T14:59:59Z\"}")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SOURCE_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    /** The order the contract states: a malformed request is refused as such even while the gate is shut. */
    @Test
    @DisplayName("a malformed batch is 422 before the closed gate is consulted")
    void validationComesBeforeTheGate() throws Exception {
        query("{\"placeIds\":[],\"from\":\"2026-10-04T15:00:00Z\",\"to\":\"2026-10-05T14:59:59Z\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private ResultActions query(String body) throws Exception {
        SessionService.Bootstrap owner = sessions.bootstrap(null, null, null);
        return mvc.perform(post("/api/v1/places/crowd-forecasts/query")
                .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                .header("Origin", "http://localhost:5173")
                .contentType("application/json")
                .content(body));
    }
}
