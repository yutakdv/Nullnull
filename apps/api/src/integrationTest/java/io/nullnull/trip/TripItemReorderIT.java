package io.nullnull.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * BA-040 reorderTripItems: the atomic move, and the first trip mutation that consults locks.
 *
 * <p>Two claims are worth executing rather than describing. A swap passes through a state where two
 * items hold one slot, which the constraint refused until V020 made it deferrable - so "swapping
 * works" is a statement about the database, not the service. And a lock the request does not name
 * refuses the move, which is invariant 7 reaching a mutation path for the first time: until now only
 * the optimizer's re-validation called LockChecks.
 */
@SpringBootTest(properties = "nullnull.catalog.public-enabled=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-040 trip item reorder")
class TripItemReorderIT {

    private static final LocalDate DAY_ONE = LocalDate.parse("2026-10-04");
    private static final LocalDate DAY_TWO = LocalDate.parse("2026-10-05");

    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("BA-040-T1 two items swap slots in one request, which no per-statement check allows")
    void aSwapIsJudgedOnTheResultRatherThanOnEachRowAsItMoves() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID first = insertItem(tripId, place("먼저 있던 장소"), DAY_ONE, 0);
        UUID second = insertItem(tripId, place("나중에 있던 장소"), DAY_ONE, 1);

        // Whichever UPDATE runs first puts both rows on one slot. Before V020 this was impossible to
        // express at all, in any statement order - the constraint fired mid-transaction.
        reorder(owner, tripId, "\"1\"", "[{\"itemId\":\"" + first + "\",\"date\":\"" + DAY_ONE
                + "\",\"position\":1},{\"itemId\":\"" + second + "\",\"date\":\"" + DAY_ONE
                + "\",\"position\":0}]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trip.version").value(2))
                .andExpect(jsonPath("$.changedItemIds.length()").value(2));

        assertThat(slots(tripId)).containsExactly(second + "@2026-10-04#0", first + "@2026-10-04#1");
    }

    @Test
    @DisplayName("BA-040-T5 an item moves to another day and the day it left closes up behind it")
    void anItemMovesAcrossDays() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID staying = insertItem(tripId, place("첫날에 남는 장소"), DAY_ONE, 0);
        UUID moving = insertItem(tripId, place("둘째 날로 가는 장소"), DAY_ONE, 1);

        // Both entries are sent. The one that stays is named too, because a day's positions are only
        // contiguous if the request says what the whole day looks like afterwards - moving one item
        // out of the middle otherwise leaves a hole nothing closes.
        reorder(owner, tripId, "\"1\"", "[{\"itemId\":\"" + moving + "\",\"date\":\"" + DAY_TWO
                + "\",\"position\":0},{\"itemId\":\"" + staying + "\",\"date\":\"" + DAY_ONE
                + "\",\"position\":0}]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trip.version").value(2))
                .andExpect(jsonPath("$.changedItemIds.length()").value(2));

        // The date changed, not just the position: a reorder that could only shuffle within one day
        // would pass every same-day test in this file and still leave the user unable to move an
        // item to the next day at all.
        assertThat(slots(tripId)).containsExactly(staying + "@2026-10-04#0", moving + "@2026-10-05#0");
    }

    @Test
    @DisplayName("BA-040 a DATE lock the request does not name refuses the move and changes nothing")
    void anUnnamedLockRefusesTheMove() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID itemId = insertItem(tripId, place("날짜가 고정된 장소"), DAY_ONE, 0);
        lock(tripId, itemId, "DATE", DAY_ONE);

        reorder(owner, tripId, "\"1\"", "[{\"itemId\":\"" + itemId + "\",\"date\":\"" + DAY_TWO
                + "\",\"position\":0}]")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOCK_CONFLICT"));

        // Nothing moved, the lock is still there, and the version did not rise - a refused command
        // must not look like a command that happened.
        assertThat(slots(tripId)).containsExactly(itemId + "@2026-10-04#0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_constraints WHERE trip_item_id = ?",
                Integer.class, itemId)).isOne();
        assertThat(version(tripId)).isEqualTo(1);
    }

    @Test
    @DisplayName("BA-040 naming the lock releases it and lets the same move through")
    void aNamedLockIsReleasedAndTheMoveProceeds() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID itemId = insertItem(tripId, place("풀고 옮기는 장소"), DAY_ONE, 0);
        lock(tripId, itemId, "DATE", DAY_ONE);

        reorder(owner, tripId, "\"1\"", "[{\"itemId\":\"" + itemId + "\",\"date\":\"" + DAY_TWO
                + "\",\"position\":0,\"releaseConstraints\":[\"DATE\"]}]")
                .andExpect(status().isOk());

        assertThat(slots(tripId)).containsExactly(itemId + "@2026-10-05#0");
        // Released means the row is gone (ERD: only locked=true rows are stored), so the lock cannot
        // come back by itself and the next move is judged without it.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_constraints WHERE trip_item_id = ?",
                Integer.class, itemId)).isZero();
    }

    @Test
    @DisplayName("BA-040 a MUST_VISIT lock never refuses a reorder and cannot be released by one")
    void aPlaceLockIsNotATemporalLock() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID itemId = insertItem(tripId, place("꼭 가는 장소"), DAY_ONE, 0);
        lock(tripId, itemId, "MUST_VISIT", null);

        // A reorder keeps the place, so the lock that pins the place cannot be in the way.
        reorder(owner, tripId, "\"1\"", "[{\"itemId\":\"" + itemId + "\",\"date\":\"" + DAY_TWO
                + "\",\"position\":0}]")
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_constraints WHERE trip_item_id = ?"
                + " AND type = 'MUST_VISIT'", Integer.class, itemId)).isOne();

        // And naming it is refused rather than honoured: the contract's enum cannot express it, and
        // a command with no reason to touch that lock must not delete it.
        reorder(owner, tripId, "\"2\"", "[{\"itemId\":\"" + itemId + "\",\"date\":\"" + DAY_ONE
                + "\",\"position\":0,\"releaseConstraints\":[\"MUST_VISIT\"]}]")
                .andExpect(status().isUnprocessableContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_constraints WHERE trip_item_id = ?"
                + " AND type = 'MUST_VISIT'", Integer.class, itemId)).isOne();
    }

    @Test
    @DisplayName("BA-040-T6 an item of another trip is refused before anything moves")
    void anItemThisTripDoesNotHoldIsRefused() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID mine = insertItem(tripId, place("내 여행의 장소"), DAY_ONE, 0);
        UUID otherTrip = createTrip(owner);
        UUID theirs = insertItem(otherTrip, place("다른 여행의 장소"), DAY_ONE, 0);

        reorder(owner, tripId, "\"1\"", "[{\"itemId\":\"" + mine + "\",\"date\":\"" + DAY_ONE
                + "\",\"position\":1},{\"itemId\":\"" + theirs + "\",\"date\":\"" + DAY_ONE
                + "\",\"position\":0}]")
                .andExpect(status().isUnprocessableContent());

        // The valid half of the request did not land either.
        assertThat(slots(tripId)).containsExactly(mine + "@2026-10-04#0");
        assertThat(version(tripId)).isEqualTo(1);
    }

    private ResultActions reorder(SessionService.Bootstrap owner, UUID tripId, String ifMatch,
            String items) throws Exception {
        return mvc.perform(post("/api/v1/trips/" + tripId + "/items/reorder")
                .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                .header("Origin", "http://localhost:5173")
                .header("X-CSRF-Token", owner.csrf.token)
                .header("If-Match", ifMatch)
                .header("Idempotency-Key", "reorder-" + UUID.randomUUID())
                .contentType("application/json")
                .content("{\"items\":" + items + "}"));
    }

    private UUID createTrip(SessionService.Bootstrap owner) throws Exception {
        String created = mvc.perform(post("/api/v1/trips")
                        .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
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

    private UUID insertItem(UUID tripId, UUID placeId, LocalDate date, int position) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO trip_items (id, trip_id, place_id, trip_date, position, created_at,"
                + " updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, tripId, placeId, java.sql.Date.valueOf(date), position, now, now);
        return id;
    }

    private void lock(UUID tripId, UUID itemId, String type, LocalDate dateValue) {
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO trip_constraints (id, trip_id, trip_item_id, type, source,"
                + " date_value, created_at, updated_at) VALUES (?, ?, ?, ?, 'USER', ?, ?, ?)",
                UUID.randomUUID(), tripId, itemId, type,
                dateValue == null ? null : java.sql.Date.valueOf(dateValue), now, now);
    }

    /** Every item as "id@date#position", ordered the way a day is rendered. */
    private List<String> slots(UUID tripId) {
        return jdbc.queryForList("SELECT id || '@' || trip_date || '#' || position FROM trip_items"
                + " WHERE trip_id = ? ORDER BY trip_date, position", String.class, tripId);
    }

    private long version(UUID tripId) {
        return jdbc.queryForObject("SELECT version FROM trips WHERE id = ?", Long.class, tripId);
    }
}
