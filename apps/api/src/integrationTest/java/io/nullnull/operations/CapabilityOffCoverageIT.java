package io.nullnull.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
            DemoCapabilities.OPTIMIZATION, "createOptimization (also OptimizationCapabilityOffIT)",
            DemoCapabilities.LIVE, "queryLiveAreas (also LiveAreaApiIT)");

    /**
     * Capabilities whose flag is off and which no operation refuses, because no operation reads them
     * yet. Each leaves this list when its slice lands - B10 for both - and the entry has to be deleted
     * then, because the capability will be in the other register and being in both fails.
     */
    private static final Map<String, String> NO_OPERATION_YET = new LinkedHashMap<>(Map.of(
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
        // And the Live tab's route, which arrived with B10. A well-formed body for the same reason
        // as above: queryLiveAreas judges the request shape before the capability, so a malformed
        // viewport would answer 400 and never reach the gate this register is about. The viewport is
        // coarse and inside the contract's rules, which LiveAreaApiIT measures on its own.
        //
        // LIVE MOVED REGISTERS AND STILL DOES NOT SHIP READY, but the reason has changed and the
        // old one is now false. This used to say the flag cannot be turned on because
        // DemoCapabilityQuery keeps live in WITHOUT_A_SOURCE; BA-090 took it out, and that list now
        // holds replay alone. The flag CAN be turned on and defaults OFF, exactly like
        // optimization's - so what keeps this register honest is the assertion above that no
        // capability ships READY, not a startup refusal. What this block records is unchanged:
        // something refuses while the flag is off.
        //
        // One operation is driven, not all three. listLiveAreaPlaces and getLivePlace go through
        // the same LiveCapability.require(), and this register's claim is per capability rather
        // than per route - LivePlaceCapabilityOffIT drives those two at this same default.
        mvc.perform(post("/api/v1/live/areas")
                        .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                        .header("Origin", ORIGIN)
                        .contentType("application/json")
                        .content("{\"mode\":\"AUTO\",\"viewport\":{\"west\":126.977,"
                                + "\"south\":37.579,\"east\":127.007,\"north\":37.609}}"))
                .andExpect(status().isForbidden())
                // The code, not just the status: a POST with no Origin is ALSO 403 here, from the
                // same-origin check that runs before any of this. Without this line the register
                // would be satisfied by a request that never reached the capability at all - which
                // is what the first run of this block actually did.
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(Set.copyOf(REFUSED_BY_AN_OPERATION.keySet()))
                .containsExactlyInAnyOrder(DemoCapabilities.OPTIMIZATION, DemoCapabilities.LIVE);
    }
}
