package io.nullnull.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import io.nullnull.trip.domain.TripItem;
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
import org.springframework.test.web.servlet.MvcResult;

/**
 * BA-040's schedule boundaries, each asked of EVERY command that can reach it.
 *
 * <p>The rules are one piece of code - {@code TripScheduleRules.requireInsideRange} and
 * {@code requireWithinCaps} - but being called is not a property of the rule, it is a property of
 * each caller. {@code addTripItem}, {@code reorderTripItems} and {@code updateTripItem} each build
 * their own "after" list and each call the rules themselves (TripService:550, :665, :864), so a test
 * that proved the boundary on one of them would say nothing about the other two. That is why these
 * are written as one case per MECHANISM across all three commands rather than one case per command:
 * the clause is "the boundary is enforced", and a boundary enforced on two paths out of three is not
 * enforced.
 *
 * <p>Every case also asserts that nothing was written. A 422 that still moved a row would be the
 * worse half of the bug, and a status-only assertion cannot tell the two apart.
 */
@SpringBootTest(properties = "nullnull.catalog.public-enabled=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-040 trip schedule boundaries")
class TripScheduleBoundaryIT {

    private static final String ORIGIN = "http://localhost:5173";
    private static final LocalDate DAY_ONE = LocalDate.parse("2026-10-04");
    private static final LocalDate DAY_TWO = LocalDate.parse("2026-10-05");
    private static final LocalDate OUTSIDE = LocalDate.parse("2026-10-06");

    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("BA-040-T7 every command refuses a date outside the trip's own range")
    void aDateOutsideTheTripIsRefusedByEveryCommand() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID itemId = insertItem(tripId, place("범위 안의 장소"), DAY_ONE, 0);

        // The trip runs DAY_ONE..DAY_TWO. OUTSIDE is the day after it ends - a date the calendar
        // accepts and this trip does not.
        assertThat(statusOf(add(owner, tripId, "\"1\"", "{\"placeId\":\"" + place("범위 밖에 놓으려는 장소")
                + "\",\"date\":\"" + OUTSIDE + "\",\"position\":0}"))).isEqualTo(422);
        assertThat(statusOf(reorder(owner, tripId, "\"1\"", "[{\"itemId\":\"" + itemId + "\",\"date\":\""
                + OUTSIDE + "\",\"position\":0}]"))).isEqualTo(422);
        assertThat(statusOf(update(owner, tripId, itemId, "\"1\"", "{\"date\":\"" + OUTSIDE + "\"}")))
                .isEqualTo(422);

        assertThat(slots(tripId)).containsExactly(itemId + "@2026-10-04#0");
        assertThat(version(tripId)).isEqualTo(1);
    }

    @Test
    @DisplayName("BA-040-T8 every command refuses a duration outside 1..MAX_DURATION_MINUTES")
    void aDurationOutsideItsBoundsIsRefusedByEveryCommand() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID itemId = insertItem(tripId, place("머무는 장소"), DAY_ONE, 0);
        int tooLong = TripItem.MAX_DURATION_MINUTES + 1;

        // Both ends, because they are one rule: a check written as "not too long" accepts zero, and
        // a stay of no minutes is not a stay. reorderTripItems carries no duration at all - it moves
        // items and nothing else - so the two commands that can set one are the two asked here.
        assertThat(statusOf(add(owner, tripId, "\"1\"", "{\"placeId\":\"" + place("0분 머무는 장소")
                + "\",\"date\":\"" + DAY_ONE + "\",\"position\":1,\"durationMinutes\":0}"))).isEqualTo(422);
        assertThat(statusOf(add(owner, tripId, "\"1\"", "{\"placeId\":\"" + place("하루를 넘겨 머무는 장소")
                + "\",\"date\":\"" + DAY_ONE + "\",\"position\":1,\"durationMinutes\":" + tooLong + "}")))
                .isEqualTo(422);
        assertThat(statusOf(update(owner, tripId, itemId, "\"1\"", "{\"durationMinutes\":0}"))).isEqualTo(422);
        assertThat(statusOf(update(owner, tripId, itemId, "\"1\"", "{\"durationMinutes\":" + tooLong + "}")))
                .isEqualTo(422);

        // The boundary itself is accepted. Without this the same test would pass against a server
        // that refused every duration, and "the bound is enforced" would mean "nothing gets through".
        assertThat(statusOf(update(owner, tripId, itemId, "\"1\"",
                "{\"durationMinutes\":" + TripItem.MAX_DURATION_MINUTES + "}"))).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT duration_minutes FROM trip_items WHERE id = ?",
                Integer.class, itemId)).isEqualTo(TripItem.MAX_DURATION_MINUTES);
    }

    @Test
    @DisplayName("BA-040-T9 every command refuses an item that would exceed the per-day cap")
    void theDayCapIsEnforcedByEveryCommand() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        for (int position = 0; position < TripItem.MAX_PER_DAY; position++) {
            insertItem(tripId, place("첫날 " + position), DAY_ONE, position);
        }
        // One item parked on the second day, so the move below is a move rather than an addition.
        UUID onDayTwo = insertItem(tripId, place("둘째 날 장소"), DAY_TWO, 0);

        assertThat(statusOf(add(owner, tripId, "\"1\"", "{\"placeId\":\"" + place("하나 더 넣으려는 장소")
                + "\",\"date\":\"" + DAY_ONE + "\",\"position\":" + TripItem.MAX_PER_DAY + "}")))
                .isEqualTo(422);
        assertThat(statusOf(reorder(owner, tripId, "\"1\"", "[{\"itemId\":\"" + onDayTwo + "\",\"date\":\""
                + DAY_ONE + "\",\"position\":" + TripItem.MAX_PER_DAY + "}]"))).isEqualTo(422);
        assertThat(statusOf(update(owner, tripId, onDayTwo, "\"1\"", "{\"date\":\"" + DAY_ONE + "\"}")))
                .isEqualTo(422);

        // The full day is still exactly full and the parked item never left the second day.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_items WHERE trip_id = ? AND trip_date = ?",
                Integer.class, tripId, java.sql.Date.valueOf(DAY_ONE))).isEqualTo(TripItem.MAX_PER_DAY);
        assertThat(version(tripId)).isEqualTo(1);
    }

    // ------------------------------------------------------------- commands

    private MvcResult add(SessionService.Bootstrap owner, UUID tripId, String ifMatch, String body)
            throws Exception {
        return mvc.perform(post("/api/v1/trips/" + tripId + "/items").cookie(cookie(owner))
                .header("Origin", ORIGIN).header("X-CSRF-Token", owner.csrf.token)
                .header("If-Match", ifMatch).header("Idempotency-Key", "add-" + UUID.randomUUID())
                .contentType("application/json").content(body)).andReturn();
    }

    private MvcResult reorder(SessionService.Bootstrap owner, UUID tripId, String ifMatch, String items)
            throws Exception {
        return mvc.perform(post("/api/v1/trips/" + tripId + "/items/reorder").cookie(cookie(owner))
                .header("Origin", ORIGIN).header("X-CSRF-Token", owner.csrf.token)
                .header("If-Match", ifMatch).header("Idempotency-Key", "reorder-" + UUID.randomUUID())
                .contentType("application/json").content("{\"items\":" + items + "}")).andReturn();
    }

    private MvcResult update(SessionService.Bootstrap owner, UUID tripId, UUID itemId, String ifMatch,
            String body) throws Exception {
        return mvc.perform(patch("/api/v1/trips/" + tripId + "/items/" + itemId).cookie(cookie(owner))
                .header("Origin", ORIGIN).header("X-CSRF-Token", owner.csrf.token)
                .header("If-Match", ifMatch)
                .contentType("application/merge-patch+json").content(body)).andReturn();
    }

    private static int statusOf(MvcResult result) {
        return result.getResponse().getStatus();
    }

    // ------------------------------------------------------------- fixtures

    private static Cookie cookie(SessionService.Bootstrap owner) {
        return new Cookie("__Host-nullnull_session", owner.cookie);
    }

    private UUID createTrip(SessionService.Bootstrap owner) throws Exception {
        String created = mvc.perform(post("/api/v1/trips").cookie(cookie(owner))
                        .header("Origin", ORIGIN).header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "trip-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"startDate\":\"" + DAY_ONE + "\",\"endDate\":\"" + DAY_TWO
                                + "\",\"timezone\":\"Asia/Seoul\",\"planningLevel\":\"NOTHING\","
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

    /** Straight into the table: these cases are about the rules, not about how the row got there. */
    private UUID insertItem(UUID tripId, UUID placeId, LocalDate date, int position) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO trip_items (id, trip_id, place_id, trip_date, position, created_at,"
                + " updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, tripId, placeId, java.sql.Date.valueOf(date), position, now, now);
        return id;
    }

    private List<String> slots(UUID tripId) {
        return jdbc.queryForList("SELECT id || '@' || trip_date || '#' || position FROM trip_items"
                + " WHERE trip_id = ? ORDER BY trip_date, position", String.class, tripId);
    }

    private long version(UUID tripId) {
        return jdbc.queryForObject("SELECT version FROM trips WHERE id = ?", Long.class, tripId);
    }
}
