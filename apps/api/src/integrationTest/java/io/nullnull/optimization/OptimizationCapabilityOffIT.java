package io.nullnull.optimization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * BA-050 with the optimization flag at its DEFAULT, which is off - the state every deployed
 * environment is in.
 *
 * <p>A sibling of {@code FeedFailsClosedIT} and for the same reason: a gate that is only ever tested
 * open has not been tested. {@code OptimizationRunIT} turns the flag on to exercise the endpoint, so
 * without this file the default configuration - the one the contest profile ships - would be the only
 * configuration nothing asserts.
 *
 * <p>What it asserts is also where in the order the refusal sits. The trip in this request does not
 * exist, and the answer is still 403 rather than 404 - so a disabled server cannot be used to probe
 * which trips exist. The body's own shape is checked before this point, in the controller, and that
 * is not a leak: a malformed body is told so without anything being read.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-050 optimization refuses while its capability is off")
class OptimizationCapabilityOffIT {

    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("BA-050-T2 createOptimization is refused while the capability is off")
    void theEndpointIsClosedByDefault() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID strangerTrip = UUID.randomUUID();

        mvc.perform(post("/api/v1/trips/" + strangerTrip + "/optimizations")
                        .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("If-Match", "\"1\"")
                        .header("Idempotency-Key", "optimize-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"scope\":\"ITEM\",\"targetItemId\":\"" + UUID.randomUUID()
                                + "\",\"inputTripVersion\":1,\"includeCandidates\":false}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                // Not 503: a capability that is off by configuration will not become available by
                // waiting, so a retryable answer would have clients poll a feature that is not coming.
                .andExpect(jsonPath("$.retryable").value(false));

        // Named rows, not the table. The required gate runs every suite against one database, where
        // "no run exists" is a sentence about every test that ran before this one rather than about
        // this refusal. Scoped to the owner and not to strangerTrip: that trip id was never created
        // and optimization_runs.trip_id is a foreign key to trips, so a count naming it is zero
        // whether the gate holds or not. The owner was really bootstrapped, so a run that got past
        // the gate would carry it and be counted here.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM optimization_runs WHERE requested_by_owner_id = ?",
                Integer.class, owner.owner.id())).isZero();
    }

    @Test
    @DisplayName("BA-050 the readiness projection and the endpoint agree about being off")
    void theCapabilityResponseAndTheEndpointSayTheSameThing() throws Exception {
        // The two are bound to one flag deliberately. An endpoint that worked while this response said
        // UNAVAILABLE would not be a disabled feature but a live path the Frontend has been told to
        // ignore, and ENVIRONMENT.md §6 makes this response the thing it believes.
        var owner = sessions.bootstrap(null, null, null);
        mvc.perform(get("/api/v1/demo/readiness")
                        .cookie(new Cookie("__Host-nullnull_session", owner.cookie)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capabilities[?(@.name == 'optimization')].status")
                        .value("UNAVAILABLE"));
    }
}
