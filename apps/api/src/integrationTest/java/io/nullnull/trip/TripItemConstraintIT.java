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
        // The fourth type, which this case used to stop short of. Three types coexisting does not
        // say four do - RESERVATION is the only lock that pins a date and a time together, so it is
        // the one most likely to collide with the two that pin them separately.
        set(owner, tripId, itemId, "RESERVATION", "\"4\"",
                "{\"type\":\"RESERVATION\",\"locked\":true,\"source\":\"USER\",\"date\":\""
                        + DAY_ONE + "\",\"startTime\":\"09:00:00\"}")
                .andExpect(status().isOk());
        assertThat(types(itemId)).containsExactly("DATE", "MUST_VISIT", "RESERVATION", "TIME");

        // Re-setting DATE replaces DATE and nothing else. The unique index is what makes that a
        // storage fact: a second DATE row cannot exist, and TIME is a different row entirely.
        set(owner, tripId, itemId, "DATE", "\"5\"",
                "{\"type\":\"DATE\",\"locked\":true,\"date\":\"" + DAY_ONE + "\"}")
                .andExpect(status().isOk());
        assertThat(types(itemId)).containsExactly("DATE", "MUST_VISIT", "RESERVATION", "TIME");

        // And removing one removes one. A release that took its neighbours with it would be the
        // auto-release invariant 7 forbids, arriving as an implementation detail.
        remove(owner, tripId, itemId, "TIME", "\"6\"").andExpect(status().isOk());
        assertThat(types(itemId)).containsExactly("DATE", "MUST_VISIT", "RESERVATION");
        // Removing the one that pins two values leaves both of the locks that pin them singly.
        remove(owner, tripId, itemId, "RESERVATION", "\"7\"").andExpect(status().isOk());
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
        // And a RESERVATION, which the assertion names and this case used to leave out. It is the
        // one that matters most: the other three are the user's own intentions, while a reservation
        // is a promise made to someone else. Storing one the item already breaks would record a
        // booking the itinerary cannot keep - and the user never had a chance to satisfy it.
        set(owner, tripId, itemId, "RESERVATION", "\"1\"",
                "{\"type\":\"RESERVATION\",\"locked\":true,\"source\":\"USER\",\"date\":\""
                        + DAY_TWO + "\",\"startTime\":\"09:00:00\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOCK_CONFLICT"));
        // The date matches but the time does not: RESERVATION pins both, so half-right is refused.
        set(owner, tripId, itemId, "RESERVATION", "\"1\"",
                "{\"type\":\"RESERVATION\",\"locked\":true,\"source\":\"USER\",\"date\":\""
                        + DAY_ONE + "\",\"startTime\":\"13:00:00\"}")
                .andExpect(status().isConflict());

        assertThat(types(itemId)).isEmpty();
        assertThat(version(tripId)).isEqualTo(1);

        // The same lock for the day the item is actually on is accepted, so the refusals above are
        // the rule working rather than the operation declining every DATE lock.
        set(owner, tripId, itemId, "DATE", "\"1\"",
                "{\"type\":\"DATE\",\"locked\":true,\"date\":\"" + DAY_ONE + "\"}")
                .andExpect(status().isOk());
        assertThat(types(itemId)).containsExactly("DATE");
        // Same for the reservation: the item's own date and time are accepted, so the two refusals
        // above are the rule working rather than the operation declining every RESERVATION.
        set(owner, tripId, itemId, "RESERVATION", "\"2\"",
                "{\"type\":\"RESERVATION\",\"locked\":true,\"source\":\"USER\",\"date\":\""
                        + DAY_ONE + "\",\"startTime\":\"09:00:00\"}")
                .andExpect(status().isOk());
        assertThat(types(itemId)).containsExactly("DATE", "RESERVATION");
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

    @Test
    @DisplayName("BA-034 a candidate saved with mustVisit becomes a MUST_VISIT lock when it is scheduled")
    void theIntentionBecomesALockAtPromotion() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID placeId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO places (id, canonical_name, category_code, region_code, status,"
                + " created_at, updated_at) VALUES (?, '꼭 가는 장소', 'HS', '11', 'ACTIVE', ?, ?)",
                placeId, now, now);

        UUID candidateId = UUID.fromString(mvc.perform(post("/api/v1/trips/" + tripId + "/candidates")
                        .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "must-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"placeId\":\"" + placeId + "\",\"source\":{\"type\":\"SEARCH\"},"
                                + "\"mustVisit\":true}"))
                .andExpect(status().isCreated())
                // Carried back, so the screen that asked the question can show the answer.
                .andExpect(jsonPath("$.candidate.mustVisit").value(true))
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s)^.*?\"id\":\"([^\"]+)\".*$", "$1"));

        // While it is a candidate it is an intention and nothing else: locks live on items, and
        // there is no item yet. Nothing in trip_constraints can refer to a candidate at all.
        assertThat(jdbc.queryForObject("SELECT must_visit FROM trip_candidates WHERE id = ?",
                Boolean.class, candidateId)).isTrue();

        String added = mvc.perform(post("/api/v1/trips/" + tripId + "/items")
                        .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("If-Match", "\"1\"")
                        .header("Idempotency-Key", "sched-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"placeId\":\"" + placeId + "\",\"candidateId\":\"" + candidateId
                                + "\",\"date\":\"" + DAY_ONE + "\",\"position\":0}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID itemId = UUID.fromString(added.replaceFirst(
                "(?s)^.*\"changedItemIds\":\\[\"([^\"]+)\".*$", "$1"));

        // The lock the candidate asked for exists on the item, in the same transaction that created
        // it. Without this the field would be something the server accepts and forgets.
        assertThat(types(itemId)).containsExactly("MUST_VISIT");
        assertThat(jdbc.queryForObject("SELECT source FROM trip_constraints WHERE trip_item_id = ?",
                String.class, itemId)).isEqualTo("USER");
    }

    @Test
    @DisplayName("BA-034 a candidate saved without mustVisit produces no lock")
    void theAbsenceOfTheIntentionLocksNothing() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID placeId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO places (id, canonical_name, category_code, region_code, status,"
                + " created_at, updated_at) VALUES (?, '그냥 후보', 'HS', '11', 'ACTIVE', ?, ?)",
                placeId, now, now);

        // The field omitted entirely, which is what a client that has not adopted it sends.
        UUID candidateId = UUID.fromString(mvc.perform(post("/api/v1/trips/" + tripId + "/candidates")
                        .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "plain-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"placeId\":\"" + placeId + "\",\"source\":{\"type\":\"SEARCH\"}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.candidate.mustVisit").value(false))
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s)^.*?\"id\":\"([^\"]+)\".*$", "$1"));

        String added = mvc.perform(post("/api/v1/trips/" + tripId + "/items")
                        .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("If-Match", "\"1\"")
                        .header("Idempotency-Key", "plain-sched-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"placeId\":\"" + placeId + "\",\"candidateId\":\"" + candidateId
                                + "\",\"date\":\"" + DAY_ONE + "\",\"position\":0}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID itemId = UUID.fromString(added.replaceFirst(
                "(?s)^.*\"changedItemIds\":\\[\"([^\"]+)\".*$", "$1"));

        // The other half of the pair. Without it, a reader that locked unconditionally would pass
        // the test above and nothing would say so.
        assertThat(types(itemId)).isEmpty();
    }

    @Test
    @DisplayName("BA-034 removing an item restores the candidate with the lock the ITEM carried")
    void theRestoredCandidateAnswersFromTheItemNotFromMemory() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID placeId = place("나중에 꼭 가기로 한 장소");

        // Saved WITHOUT the intention, so the candidate's stored flag is false.
        UUID candidateId = saveCandidate(owner, tripId, placeId, false);
        UUID itemId = schedule(owner, tripId, placeId, candidateId, "\"1\"");
        assertThat(types(itemId)).isEmpty();

        // The lock is placed after scheduling, which is the case the stored flag cannot know about.
        set(owner, tripId, itemId, "MUST_VISIT", "\"2\"",
                "{\"type\":\"MUST_VISIT\",\"locked\":true}").andExpect(status().isOk());

        mvc.perform(delete("/api/v1/trips/" + tripId + "/items/" + itemId)
                        .param("disposition", "RESTORE_CANDIDATE")
                        .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("If-Match", "\"3\""))
                .andExpect(status().isOk());

        // The SAME candidate row comes back - this is the restore path, not the fallback that
        // creates a new one - and it carries what the item carried, not what it was saved with.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_candidates WHERE trip_id = ?",
                Integer.class, tripId)).isOne();
        assertThat(jdbc.queryForObject("SELECT status || ' ' || must_visit FROM trip_candidates"
                + " WHERE id = ?", String.class, candidateId)).isEqualTo("ACTIVE true");
    }

    @Test
    @DisplayName("BA-034 replacing a place returns its candidate without the intention the user released")
    void theReplacedCandidateDoesNotGetBackWhatWasReleased() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID placeId = place("꼭 가려다 바꾼 장소");

        // Saved WITH the intention, so the stored flag is true and scheduling makes it a lock.
        UUID candidateId = saveCandidate(owner, tripId, placeId, true);
        UUID itemId = schedule(owner, tripId, placeId, candidateId, "\"1\"");
        assertThat(types(itemId)).containsExactly("MUST_VISIT");

        // Replacing requires naming that lock (#199), which is the traveller letting the place go.
        mvc.perform(post("/api/v1/trips/" + tripId + "/items/" + itemId + "/replace")
                        .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("If-Match", "\"2\"")
                        .header("Idempotency-Key", "rep-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"replacementPlaceId\":\"" + place("대신 넣는 장소") + "\","
                                + "\"releaseConstraints\":[\"MUST_VISIT\"]}"))
                .andExpect(status().isOk());

        // Back as a candidate, without the intention. Returning it true would restore what the
        // traveller had just named and released, which is the whole reason replace answers false.
        assertThat(jdbc.queryForObject("SELECT status || ' ' || must_visit FROM trip_candidates"
                + " WHERE id = ?", String.class, candidateId)).isEqualTo("ACTIVE false");
    }

    private UUID saveCandidate(SessionService.Bootstrap owner, UUID tripId, UUID placeId,
            boolean mustVisit) throws Exception {
        return UUID.fromString(mvc.perform(post("/api/v1/trips/" + tripId + "/candidates")
                        .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "cand-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"placeId\":\"" + placeId + "\",\"source\":{\"type\":\"SEARCH\"},"
                                + "\"mustVisit\":" + mustVisit + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s)^.*?\"id\":\"([^\"]+)\".*$", "$1"));
    }

    private UUID schedule(SessionService.Bootstrap owner, UUID tripId, UUID placeId, UUID candidateId,
            String ifMatch) throws Exception {
        String added = mvc.perform(post("/api/v1/trips/" + tripId + "/items")
                        .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("If-Match", ifMatch)
                        .header("Idempotency-Key", "sch-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"placeId\":\"" + placeId + "\",\"candidateId\":\"" + candidateId
                                + "\",\"date\":\"" + DAY_ONE + "\",\"position\":0}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(added.replaceFirst(
                "(?s)^.*\"changedItemIds\":\\[\"([^\"]+)\".*$", "$1"));
    }

    private UUID place(String name) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO places (id, canonical_name, category_code, region_code, status,"
                + " created_at, updated_at) VALUES (?, ?, 'HS', '11', 'ACTIVE', ?, ?)", id, name, now, now);
        return id;
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
