package io.nullnull.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * BA-040 updateTripItem: merge-patch on one item, judged against the item it would produce.
 *
 * <p>The case that decides whether the lock check is in the right place is the one that changes only
 * the start time. It touches no date, so a check reading the fields the caller sent would let it
 * through, and a TIME lock exists precisely to refuse it.
 */
@SpringBootTest(properties = "nullnull.catalog.public-enabled=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-040 trip item update")
class TripItemUpdateIT {

    private static final LocalDate DAY_ONE = LocalDate.parse("2026-10-04");

    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("BA-040-T3 a null clears the field and an absent one leaves it alone")
    void mergePatchTellsAbsentFromNull() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID itemId = insertItem(tripId, place("고칠 장소"), DAY_ONE, 0, "09:30:00", 90, "원래 메모");

        // note is sent as null and startTime is not sent at all. If the server could not tell the
        // two apart, one of them would be wrong whichever way it guessed.
        update(owner, tripId, itemId, "\"1\"", "{\"note\":null,\"durationMinutes\":120}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trip.version").value(2));

        assertThat(jdbc.queryForObject("SELECT coalesce(note, '-') || ' ' || duration_minutes"
                        + " || ' ' || coalesce(start_time::text, '-') FROM trip_items WHERE id = ?",
                String.class, itemId)).isEqualTo("- 120 09:30:00");
    }

    @Test
    @DisplayName("BA-040 changing only the start time is refused by a TIME lock it did not name")
    void aLockIsJudgedOnTheItemThePatchWouldProduce() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID itemId = insertItem(tripId, place("시각이 고정된 장소"), DAY_ONE, 0, "09:00:00", null, null);
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO trip_constraints (id, trip_id, trip_item_id, type, source,"
                + " start_time_value, tolerance_minutes, created_at, updated_at)"
                + " VALUES (?, ?, ?, 'TIME', 'USER', TIME '09:00:00', 30, ?, ?)",
                UUID.randomUUID(), tripId, itemId, now, now);

        // The patch names no date, so a check reading only what was sent sees nothing to judge. The
        // lock is about the clock, and 13:00 is four hours past a 30-minute tolerance.
        update(owner, tripId, itemId, "\"1\"", "{\"startTime\":\"13:00:00\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOCK_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT start_time::text FROM trip_items WHERE id = ?",
                String.class, itemId)).isEqualTo("09:00:00");
        assertThat(version(tripId)).isEqualTo(1);

        // Inside the tolerance the same lock allows it, so the refusal above was the lock working
        // rather than the operation refusing every time change.
        update(owner, tripId, itemId, "\"1\"", "{\"startTime\":\"09:20:00\"}")
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT start_time::text FROM trip_items WHERE id = ?",
                String.class, itemId)).isEqualTo("09:20:00");
    }

    @Test
    @DisplayName("BA-040 naming the lock releases it and the same edit goes through")
    void namingTheLockReleasesIt() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID itemId = insertItem(tripId, place("풀고 고칠 장소"), DAY_ONE, 0, "09:00:00", null, null);
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO trip_constraints (id, trip_id, trip_item_id, type, source,"
                + " start_time_value, tolerance_minutes, created_at, updated_at)"
                + " VALUES (?, ?, ?, 'TIME', 'USER', TIME '09:00:00', 30, ?, ?)",
                UUID.randomUUID(), tripId, itemId, now, now);

        update(owner, tripId, itemId, "\"1\"",
                "{\"startTime\":\"13:00:00\",\"releaseConstraints\":[\"TIME\"]}")
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_constraints WHERE trip_item_id = ?",
                Integer.class, itemId)).isZero();
        assertThat(jdbc.queryForObject("SELECT start_time::text FROM trip_items WHERE id = ?",
                String.class, itemId)).isEqualTo("13:00:00");
    }

    @Test
    @DisplayName("BA-040 an empty patch and an unknown field are both refused")
    void thePatchHasToSaySomethingTheContractDeclares() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID itemId = insertItem(tripId, place("장소"), DAY_ONE, 0, null, null, null);

        // minProperties: 1. An empty patch that still raised the version would make a successful
        // If-Match mean two different things.
        update(owner, tripId, itemId, "\"1\"", "{}")
                .andExpect(status().isUnprocessableContent());
        // A typo'd field must not read as success - the contract's schemas are closed.
        update(owner, tripId, itemId, "\"1\"", "{\"positon\":2}")
                .andExpect(status().isUnprocessableContent());
        assertThat(version(tripId)).isEqualTo(1);
    }

    @Test
    @DisplayName("BA-040 moving onto an occupied slot is refused before anything is written")
    void twoItemsCannotShareASlot() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID first = insertItem(tripId, place("첫 장소"), DAY_ONE, 0, null, null, null);
        insertItem(tripId, place("둘째 장소"), DAY_ONE, 1, null, null, null);

        // A single-item patch has no legitimate intermediate state, so this is a real conflict rather
        // than the transient one reorder defers the constraint for.
        update(owner, tripId, first, "\"1\"", "{\"position\":1}")
                .andExpect(status().isUnprocessableContent());
        assertThat(jdbc.queryForObject("SELECT position FROM trip_items WHERE id = ?", Integer.class,
                first)).isZero();
    }

    private ResultActions update(SessionService.Bootstrap owner, UUID tripId, UUID itemId,
            String ifMatch, String body) throws Exception {
        return mvc.perform(patch("/api/v1/trips/" + tripId + "/items/" + itemId)
                .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                .header("Origin", "http://localhost:5173")
                .header("X-CSRF-Token", owner.csrf.token)
                .header("If-Match", ifMatch)
                .contentType("application/merge-patch+json")
                .content(body));
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

    private UUID place(String name) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO places (id, canonical_name, category_code, region_code, status,"
                + " created_at, updated_at) VALUES (?, ?, 'HS', '11', 'ACTIVE', ?, ?)", id, name, now, now);
        return id;
    }

    private UUID insertItem(UUID tripId, UUID placeId, LocalDate date, int position, String startTime,
            Integer duration, String note) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO trip_items (id, trip_id, place_id, trip_date, position, start_time,"
                + " duration_minutes, note, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, CAST(? AS time), ?, ?, ?, ?)",
                id, tripId, placeId, java.sql.Date.valueOf(date), position, startTime, duration, note,
                now, now);
        return id;
    }

    private long version(UUID tripId) {
        return jdbc.queryForObject("SELECT version FROM trips WHERE id = ?", Long.class, tripId);
    }
}
