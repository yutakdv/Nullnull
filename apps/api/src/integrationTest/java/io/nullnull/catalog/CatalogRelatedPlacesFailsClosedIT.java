package io.nullnull.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

/**
 * The closed gate answers 503, not a 200 whose state would be a claim.
 *
 * <p>This is the half of BA-024's honesty that lives at runtime. While the catalog publication gate
 * is shut we cannot look at all, so neither NONE ("we looked and found none") nor UNKNOWN ("the
 * attempt finished without an answer, and polling will not resolve it") is true - a closed gate does
 * resolve, which is why SOURCE_UNAVAILABLE is retryable and why this route answers it exactly as
 * listFeed and getPlace do rather than inventing a second meaning for the same shut flag.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-024 related places fail closed")
class CatalogRelatedPlacesFailsClosedIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    SessionService sessions;

    @Test
    @DisplayName("BA-024 the default gate answers 503 SOURCE_UNAVAILABLE instead of an empty result")
    void theClosedGateRefusesRatherThanAnsweringEmpty() throws Exception {
        SessionService.Bootstrap owner = sessions.bootstrap(null, "ko-KR", "Asia/Seoul");

        mvc.perform(get("/api/v1/places/{id}/related", UUID.randomUUID())
                        .cookie(new Cookie("__Host-nullnull_session", owner.cookie)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SOURCE_UNAVAILABLE"))
                // Not a 200 carrying a state: an empty list here would be indistinguishable from a
                // completed lookup that found nothing, which is the claim this gate exists to prevent.
                .andExpect(jsonPath("$.state").doesNotExist())
                .andExpect(jsonPath("$.items").doesNotExist());
    }
}
