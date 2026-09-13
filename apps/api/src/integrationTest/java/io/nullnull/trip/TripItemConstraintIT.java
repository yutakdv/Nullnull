package io.nullnull.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * BA-041 setTripItemConstraint and removeTripItemConstraint over HTTP and a real PostgreSQL.
 *
 * <p>The four locks are independent (invariant 7), and independence is storage's: they are separate
 * rows under a unique {@code (trip_item_id, type)} index, so no service-level care is what keeps
 * setting one from disturbing another. What the service adds is that a lock must be true of the item
 * when it is set - a DATE lock for a day the item is not on would be broken the moment it is stored,
 * and every later edit would be refused by a constraint the user never had a chance to satisfy.
 */
@SpringBootTest(properties = "nullnull.catalog.public-enabled=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-041 independent item locks")
class TripItemConstraintIT {

    private static final LocalDate DAY_ONE = LocalDate.parse("2026-10-04");
    private static final LocalDate DAY_TWO = LocalDate.parse("2026-10-05");

    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("BA-041-T1 setting one lock leaves the other three exactly as they were")
    void theFourLocksAreIndependent() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID itemId = insertItem(tripId, DAY_ONE, "09:00:00");

        set(owner, tripId, itemId, "MUST_VISIT", "\"1\"",
                "{\"type\":\"MUST_VISIT\",\"locked\":true}").andExpect(status().isOk());
        set(owner, tripId, itemId, "DATE", "\"2\"",
                "{\"type\":\"DATE\",\"locked\":true,\"date\":\"" + DAY_ONE + "\"}")
                .andExpect(status().isOk());
        set(owner, tripId, itemId, "TIME", "\"3\"",
                "{\"type\":\"TIME\",\"locked\":true,\"startTime\":\"09:00:00\","
                        + "\"toleranceMinutes\":30}")
                .andExpect(status().isOk());
        assertThat(types(itemId)).containsExactly("DATE", "MUST_VISIT", "TIME");

        // Re-setting DATE replaces DATE and nothing else. The unique index is what makes that a
        // storage fact: a second DATE row cannot exist, and TIME is a different row entirely.
        set(owner, tripId, itemId, "DATE", "\"4\"",
                "{\"type\":\"DATE\",\"locked\":true,\"date\":\"" + DAY_ONE + "\"}")
                .andExpect(status().isOk());
        assertThat(types(itemId)).containsExactly("DATE", "MUST_VISIT", "TIME");

        // And removing one removes one. A release that took its neighbours with it would be the
        // auto-release invariant 7 forbids, arriving as an implementation detail.
        remove(owner, tripId, itemId, "TIME", "\"5\"").andExpect(status().isOk());
        assertThat(types(itemId)).containsExactly("DATE", "MUST_VISIT");
    }

    @Test
    @DisplayName("BA-041-T2 a lock the item already breaks is refused rather than stored")
    void aLockHasToBeTrueWhenItIsSet() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID itemId = insertItem(tripId, DAY_ONE, "09:00:00");

        // The item is on day one. A DATE lock for day two is broken the instant it is stored, and
        // afterwards every edit would be refused by a constraint the user could not have met.
        set(owner, tripId, itemId, "DATE", "\"1\"",
                "{\"type\":\"DATE\",\"locked\":true,\"date\":\"" + DAY_TWO + "\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOCK_CONFLICT"));
        // A TIME lock four hours off the item's own start time, same reasoning.
        set(owner, tripId, itemId, "TIME", "\"1\"",
                "{\"type\":\"TIME\",\"locked\":true,\"startTime\":\"13:00:00\","
                        + "\"toleranceMinutes\":30}")
                .andExpect(status().isConflict());

        assertThat(types(itemId)).isEmpty();
        assertThat(version(tripId)).isEqualTo(1);

        // The same lock for the day the item is actually on is accepted, so the refusals above are
        // the rule working rather than the operation declining every DATE lock.
        set(owner, tripId, itemId, "DATE", "\"1\"",
                "{\"type\":\"DATE\",\"locked\":true,\"date\":\"" + DAY_ONE + "\"}")
                .andExpect(status().isOk());
        assertThat(types(itemId)).containsExactly("DATE");
    }

    @Test
    @DisplayName("BA-041-T3 a failed set leaves no row and no version change")
    void aRefusedCommandChangesNothing() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID itemId = insertItem(tripId, DAY_ONE, null);
        set(owner, tripId, itemId, "MUST_VISIT", "\"1\"",
                "{\"type\":\"MUST_VISIT\",\"locked\":true}").andExpect(status().isOk());

        // The body names one type and the path another. Refused rather than resolved: the caller
        // meant one of them and the server cannot know which.
        set(owner, tripId, itemId, "DATE", "\"2\"",
                "{\"type\":\"TIME\",\"locked\":true,\"startTime\":\"09:00:00\","
                        + "\"toleranceMinutes\":0}")
                .andExpect(status().isUnprocessableContent());
        // Removing a lock the item never carried is 404, not a quiet 200: answering success would
        // tell the caller a release happened.
        remove(owner, tripId, itemId, "RESERVATION", "\"2\"").andExpect(status().isNotFound());

        assertThat(types(itemId)).containsExactly("MUST_VISIT");
        assertThat(version(tripId)).isEqualTo(2);
    }

    @Test
    @DisplayName("BA-041-T4 a stale If-Match is refused and the trip version does not move")
    void aStaleEtagChangesNothing() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID itemId = insertItem(tripId, DAY_ONE, null);
        set(owner, tripId, itemId, "MUST_VISIT", "\"1\"",
                "{\"type\":\"MUST_VISIT\",\"locked\":true}").andExpect(status().isOk());

        // The version the caller holds is the one before that set. Both operations are checked, so
        // neither can be the one that slips through with an ETag the caller never refreshed.
        set(owner, tripId, itemId, "DATE", "\"1\"",
                "{\"type\":\"DATE\",\"locked\":true,\"date\":\"" + DAY_ONE + "\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRIP_CHANGED"));
        remove(owner, tripId, itemId, "MUST_VISIT", "\"1\"")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRIP_CHANGED"));

        assertThat(types(itemId)).containsExactly("MUST_VISIT");
        assertThat(version(tripId)).isEqualTo(2);
    }

    private ResultActions set(SessionService.Bootstrap owner, UUID tripId, UUID itemId, String type,
            String ifMatch, String body) throws Exception {
        return mvc.perform(put("/api/v1/trips/" + tripId + "/items/" + itemId + "/constraints/" + type)
                .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                .header("Origin", "http://localhost:5173")
                .header("X-CSRF-Token", owner.csrf.token)
                .header("If-Match", ifMatch)
                .contentType("application/json")
                .content(body));
    }

    private ResultActions remove(SessionService.Bootstrap owner, UUID tripId, UUID itemId, String type,
            String ifMatch) throws Exception {
        return mvc.perform(delete("/api/v1/trips/" + tripId + "/items/" + itemId
                        + "/constraints/" + type)
                .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                .header("Origin", "http://localhost:5173")
                .header("X-CSRF-Token", owner.csrf.token)
                .header("If-Match", ifMatch));
    }

    private UUID createTrip(SessionService.Bootstrap owner) throws Exception {
        String created = mvc.perform(post("/api/v1/trips")
                        .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
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

    private UUID insertItem(UUID tripId, LocalDate date, String startTime) {
        UUID placeId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO places (id, canonical_name, category_code, region_code, status,"
                + " created_at, updated_at) VALUES (?, '잠금 test 장소', 'HS', '11', 'ACTIVE', ?, ?)",
                placeId, now, now);
        jdbc.update("INSERT INTO trip_items (id, trip_id, place_id, trip_date, position, start_time,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, 0, CAST(? AS time), ?, ?)",
                id, tripId, placeId, java.sql.Date.valueOf(date), startTime, now, now);
        return id;
    }

    private List<String> types(UUID itemId) {
        return jdbc.queryForList("SELECT type FROM trip_constraints WHERE trip_item_id = ?"
                + " ORDER BY type", String.class, itemId);
    }

    private long version(UUID tripId) {
        return jdbc.queryForObject("SELECT version FROM trips WHERE id = ?", Long.class, tripId);
    }
}
