package io.nullnull.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.identity.application.SessionService;
import io.nullnull.operations.application.DemoCapabilities;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * BA-070-T4: every P1 capability is off, and every one of them is off in a way something checks.
 *
 * <p>Each capability already has a file for its own flag - {@code OptimizationCapabilityOffIT},
 * {@code FeedFailsClosedIT} - and that is the half that existed. What did not is the statement that
 * ties the published list to those files: {@code getDemoReadiness} names three capabilities, and
 * nothing said that all three are accounted for. A fourth added tomorrow would be published as off
 * and have nothing behind it, and no test would notice.
 *
 * <p>So this is a register, the shape {@code CrowdQualityFlagCoverageIT} and
 * {@code RelationStateCoverageTest} use: every capability is either refused by an operation here, or
 * listed with the reason no operation refuses it yet. A capability in neither register fails, and one
 * in both fails - which is what keeps the list honest as the set shrinks.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-070 every P1 capability is off and accounted for")
class CapabilityOffCoverageIT {

    private static final String ORIGIN = "http://localhost:5173";

    /**
     * Capabilities with an operation that refuses while the flag is off, and the file that drives it.
     * The refusal is re-driven here rather than trusted, because a register that only names a file is
     * a claim about a file.
     */
    private static final Map<String, String> REFUSED_BY_AN_OPERATION = Map.of(
            DemoCapabilities.OPTIMIZATION, "createOptimization (also OptimizationCapabilityOffIT)");

    /**
     * Capabilities whose flag is off and which no operation refuses, because no operation reads them
     * yet. Each leaves this list when its slice lands - B10 for both - and the entry has to be deleted
     * then, because the capability will be in the other register and being in both fails.
     */
    private static final Map<String, String> NO_OPERATION_YET = new LinkedHashMap<>(Map.of(
            DemoCapabilities.LIVE, "B10 has no Live route; A-033 defers the tab and allows a mockup",
            DemoCapabilities.REPLAY, "no replay manifest reader exists; DEMO_REPLAY is DISABLED in V007"));

    @Autowired MockMvc mvc;
    @Autowired SessionService sessions;

    @Test
    @DisplayName("BA-070-T4 every published capability is off, and each is in exactly one register")
    void everyCapabilityIsOffAndAccountedFor() throws Exception {
        SessionService.Bootstrap owner = sessions.bootstrap(null, "ko-KR", "Asia/Seoul");
        String body = mvc.perform(get("/api/v1/demo/readiness")
                        .cookie(new Cookie("__Host-nullnull_session", owner.cookie)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode published = JsonMapper.builder().build().readTree(body).get("capabilities");
        Map<String, String> statuses = new LinkedHashMap<>();
        published.forEach(entry -> statuses.put(entry.get("name").asText(), entry.get("status").asText()));

        // The published set is the server's own list, not a copy of it. A capability added to
        // DemoCapabilities without a register entry fails here rather than shipping unexamined.
        assertThat(statuses.keySet()).containsExactlyInAnyOrderElementsOf(DemoCapabilities.NAMES);
        assertThat(statuses.values()).as("every P1 capability ships off").allSatisfy(
                status -> assertThat(status).isNotEqualTo("READY"));

        for (String capability : DemoCapabilities.NAMES) {
            boolean refused = REFUSED_BY_AN_OPERATION.containsKey(capability);
            boolean pending = NO_OPERATION_YET.containsKey(capability);
            assertThat(refused ^ pending)
                    .as("%s must be in exactly one register: refused=%s, pending=%s", capability,
                            refused, pending)
                    .isTrue();
        }

        // And the one that claims an operation actually has one. The trip does not exist and the
        // answer is still the capability refusal, so a disabled server cannot be used to probe which
        // trips do.
        mvc.perform(post("/api/v1/trips/{id}/optimizations", UUID.randomUUID())
                        .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                        .header("Origin", ORIGIN).header("X-CSRF-Token", owner.csrf.token)
                        .header("If-Match", "\"1\"")
                        .header("Idempotency-Key", "capability-off-register-" + UUID.randomUUID())
                        .contentType("application/json")
                        // A well-formed body on purpose: the controller checks shape before the
                        // capability, so a malformed one answers 400 and never reaches the gate this
                        // register is about.
                        .content("{\"scope\":\"ITEM\",\"targetItemId\":\"" + UUID.randomUUID()
                                + "\",\"inputTripVersion\":1,\"includeCandidates\":false}"))
                .andExpect(status().isForbidden());
        assertThat(Set.copyOf(REFUSED_BY_AN_OPERATION.keySet()))
                .containsExactly(DemoCapabilities.OPTIMIZATION);
    }
}
