package io.nullnull.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
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
 * BA-040 with the catalog publication flag at its DEFAULT, which is closed (#162, decision A).
 *
 * <p>What decides the answer is not "does this trip have items" but "can this response be built
 * honestly". A trip with no items needs no place from the catalog, so it is answered. One item makes
 * {@code TripItem.place} required with nothing to put in it, and 503 says exactly that - the same
 * sentence listFeed says for the same reason.
 *
 * <p>Written as a single test on purpose. Kept as prose, "empty is fine, item is 503" reads as an
 * arbitrary rule and the next reader is free to make it uniformly 503 (which refuses answers that
 * could be built) or to drop the items and return 200 (the silent-empty shape the decision ruled
 * out). Holding the three states in one case puts the condition in the code.
 *
 * <p>The name carries the work ID but no acceptance ID. BA-040-T1 is reorder atomicity and
 * BA-040-T2 is the candidate transition landing with the item; neither is what this proves, and
 * an acceptance ID in a @DisplayName is counted as covered by check_test_reports.py on sight.
 */
@SpringBootTest(properties = "NULLNULL_CURSOR_SECRET=test-trip-closed-secret-that-is-long-enough")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-040 the trip detail is fail-closed while the catalog is")
class TripDetailFailsClosedIT {

    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("BA-040 an empty trip is answered, a scheduled one is 503, and creating one fails before it writes")
    void theTripDetailDoesNotRouteAroundTheCatalogGate() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        Cookie cookie = new Cookie("__Host-nullnull_session", owner.cookie);
        OffsetDateTime now = OffsetDateTime.now();
        UUID placeId = UUID.randomUUID();
        jdbc.update("INSERT INTO places (id, canonical_name, category_code, region_code, status,"
                + " created_at, updated_at) VALUES (?, ?, 'HS', '11', 'ACTIVE', ?, ?)",
                placeId, "닫힌 게이트 뒤의 장소", now, now);

        String created = mvc.perform(post("/api/v1/trips")
                        .cookie(cookie)
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "closed-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"startDate\":\"2026-10-04\",\"endDate\":\"2026-10-05\","
                                + "\"timezone\":\"Asia/Seoul\",\"planningLevel\":\"NOTHING\","
                                + "\"interests\":[]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID tripId = UUID.fromString(created.replaceFirst("(?s)^.*?\"id\":\"([^\"]+)\".*$", "$1"));

        // 1. No items, so no place is needed and the trip is readable with the catalog closed.
        mvc.perform(get("/api/v1/trips/" + tripId).cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days.length()").value(2))
                .andExpect(jsonPath("$.days[0].items").isEmpty());

        // 2. One item, written straight to the table so this case does not depend on a mutation
        //    endpoint existing yet. Now the response has a required place it cannot fill.
        jdbc.update("INSERT INTO trip_items (id, trip_id, place_id, trip_date, position,"
                + " created_at, updated_at) VALUES (?, ?, ?, DATE '2026-10-04', 0, ?, ?)",
                UUID.randomUUID(), tripId, placeId, now, now);
        mvc.perform(get("/api/v1/trips/" + tripId).cookie(cookie))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SOURCE_UNAVAILABLE"));

        // 3. createTrip with seed items refuses BEFORE writing. Discovering it afterwards would leave
        //    the trip created, the caller holding a 503, and the stored idempotent response replaying
        //    that 503 for the life of the key - so the count below is the assertion that matters.
        int tripsBefore = jdbc.queryForObject("SELECT count(*) FROM trips WHERE owner_id = ?",
                Integer.class, owner.owner.id());
        mvc.perform(post("/api/v1/trips")
                        .cookie(cookie)
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "closed-seed-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"startDate\":\"2026-10-04\",\"endDate\":\"2026-10-05\","
                                + "\"timezone\":\"Asia/Seoul\",\"planningLevel\":\"NOTHING\","
                                + "\"interests\":[],\"seedItems\":[{\"placeId\":\"" + placeId
                                + "\",\"date\":\"2026-10-04\",\"position\":0}]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SOURCE_UNAVAILABLE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trips WHERE owner_id = ?",
                Integer.class, owner.owner.id())).isEqualTo(tripsBefore);
    }
}
