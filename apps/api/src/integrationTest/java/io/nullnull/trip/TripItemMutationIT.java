package io.nullnull.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.identity.application.SessionService;
import io.nullnull.identity.domain.IdempotencyRecord;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import io.nullnull.trip.domain.TripItem;
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
import org.springframework.test.web.servlet.MvcResult;

/**
 * BA-040 addTripItem and removeTripItem against a real PostgreSQL.
 *
 * <p>The catalog gate is open here on purpose. {@code TripDetailFailsClosedIT} owns the closed case;
 * these are the cases that only exist once a response CAN be built.
 */
@SpringBootTest(properties = {"nullnull.catalog.public-enabled=true",
        "NULLNULL_CURSOR_SECRET=test-trip-items-secret-that-is-long-enough"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-040 trip item mutation")
class TripItemMutationIT {

    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("BA-040-T2 scheduling a candidate and restoring it move the candidate with the item")
    void theCandidateTransitionLandsWithTheItem() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        Cookie cookie = cookie(owner);
        UUID place = place("후보에서 올라온 장소");
        UUID tripId = createTrip(owner, "2026-10-04", "2026-10-05");

        UUID candidateId = UUID.fromString(mvc.perform(post("/api/v1/trips/" + tripId + "/candidates")
                        .cookie(cookie)
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "cand-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"placeId\":\"" + place + "\",\"source\":{\"type\":\"SEARCH\"}}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s)^.*?\"id\":\"([^\"]+)\".*$", "$1"));

        // Saving a candidate does not touch the schedule (invariant 2), so the trip is still at 1.
        assertThat(version(tripId)).isEqualTo(1);

        String added = mvc.perform(post("/api/v1/trips/" + tripId + "/items")
                        .cookie(cookie)
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("If-Match", "\"1\"")
                        .header("Idempotency-Key", "add-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"placeId\":\"" + place + "\",\"candidateId\":\"" + candidateId
                                + "\",\"date\":\"2026-10-04\",\"position\":0}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.trip.version").value(2))
                .andExpect(jsonPath("$.changedItemIds.length()").value(1))
                .andExpect(jsonPath("$.trip.days[0].items[0].place.id").value(place.toString()))
                .andReturn().getResponse().getContentAsString();
        UUID itemId = UUID.fromString(added.replaceFirst(
                "(?s)^.*\"changedItemIds\":\\[\"([^\"]+)\".*$", "$1"));

        // The candidate did not merely change status: it names the item it was scheduled onto. The
        // CHECK makes those one fact, and reading both is what tells a wrong pointer from a right one.
        assertThat(jdbc.queryForObject("SELECT status || ' ' || coalesce(scheduled_trip_item_id::text,"
                        + " '-') FROM trip_candidates WHERE id = ?", String.class, candidateId))
                .isEqualTo("SCHEDULED " + itemId);

        mvc.perform(delete("/api/v1/trips/" + tripId + "/items/" + itemId)
                        .param("disposition", "RESTORE_CANDIDATE")
                        .cookie(cookie)
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("If-Match", "\"2\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trip.version").value(3))
                .andExpect(jsonPath("$.trip.days[0].items").isEmpty());

        // Back to ACTIVE, pointing at nothing, and it is the SAME row - restoring a candidate is not
        // saving a new one, so the note and the source it was saved with survive the round trip.
        assertThat(jdbc.queryForObject("SELECT status || ' ' || coalesce(scheduled_trip_item_id::text,"
                        + " '-') FROM trip_candidates WHERE id = ?", String.class, candidateId))
                .isEqualTo("ACTIVE -");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_candidates WHERE trip_id = ?",
                Integer.class, tripId)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_items WHERE trip_id = ?",
                Integer.class, tripId)).isZero();
    }

    @Test
    @DisplayName("BA-040 restoring an item that was never a candidate records TRIP_SEED, not an invented source")
    void restoringASeededItemNamesWhereItCameFrom() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        Cookie cookie = cookie(owner);
        UUID place = place("여행과 함께 도착한 장소");
        UUID tripId = createTrip(owner, "2026-10-04", "2026-10-05");
        UUID itemId = insertItem(tripId, place, LocalDate.parse("2026-10-04"), 0);

        mvc.perform(delete("/api/v1/trips/" + tripId + "/items/" + itemId)
                        .param("disposition", "RESTORE_CANDIDATE")
                        .cookie(cookie)
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("If-Match", "\"1\""))
                .andExpect(status().isOk());

        // A candidate must say where its place came from. This one came from none of the four words
        // that existed before V019, and SEARCH would claim the traveller looked it up.
        assertThat(jdbc.queryForObject("SELECT c.status || ' ' || s.source_type"
                        + " FROM trip_candidates c JOIN candidate_sources s ON s.candidate_id = c.id"
                        + " WHERE c.trip_id = ?", String.class, tripId))
                .isEqualTo("ACTIVE TRIP_SEED");
    }

    @Test
    @DisplayName("BA-040 REMOVE dismisses the candidate instead of restoring it")
    void removingCompletelyDismissesTheCandidate() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        Cookie cookie = cookie(owner);
        UUID place = place("일정에서 지우는 장소");
        UUID tripId = createTrip(owner, "2026-10-04", "2026-10-05");
        UUID itemId = insertItem(tripId, place, LocalDate.parse("2026-10-04"), 0);
        UUID candidateId = insertScheduledCandidate(tripId, place, itemId);

        mvc.perform(delete("/api/v1/trips/" + tripId + "/items/" + itemId)
                        .param("disposition", "REMOVE")
                        .cookie(cookie)
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("If-Match", "\"1\""))
                .andExpect(status().isOk());

        // SCHEDULED to DISMISSED, which the ERD allows only along this path. Reaching it through
        // removeTripCandidate is still refused, and BA-034 pins that separately.
        assertThat(jdbc.queryForObject("SELECT status || ' ' || coalesce(scheduled_trip_item_id::text,"
                        + " '-') FROM trip_candidates WHERE id = ?", String.class, candidateId))
                .isEqualTo("DISMISSED -");
    }

    @Test
    @DisplayName("BA-040 a replayed Idempotency-Key adds one item and schedules the candidate once")
    void replayingTheKeyRepeatsNeitherTheItemNorTheTransition() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        Cookie cookie = cookie(owner);
        UUID place = place("두 번 눌린 장소");
        UUID tripId = createTrip(owner, "2026-10-04", "2026-10-05");
        UUID candidateId = insertActiveCandidate(tripId, place);
        String key = "replay-" + UUID.randomUUID();
        String body = "{\"placeId\":\"" + place + "\",\"candidateId\":\"" + candidateId
                + "\",\"date\":\"2026-10-04\",\"position\":0}";

        String first = addItem(tripId, owner, cookie, key, "\"1\"", body)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        // The SAME If-Match is sent again, which is what a retry actually looks like: the client
        // never saw the first answer, so it still holds version 1. The guard has to answer before
        // anything reads the version, or the retry would be a 409 the caller cannot resolve.
        String second = addItem(tripId, owner, cookie, key, "\"1\"", body)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_items WHERE trip_id = ?",
                Integer.class, tripId)).isOne();
        assertThat(version(tripId)).isEqualTo(2);
        // The transition ran inside the guarded command, so the replay did not run it again. Moving
        // it outside execute() would schedule the candidate a second time - against the item the
        // FIRST attempt created - and this count is what would notice.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_candidates WHERE trip_id = ?"
                        + " AND status = 'SCHEDULED'", Integer.class, tripId)).isOne();
    }

    @Test
    @DisplayName("BA-040 the same key with a different body or a different If-Match is a reused key")
    void theKeyCoversBothTheBodyAndThePrecondition() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        Cookie cookie = cookie(owner);
        UUID place = place("첫 요청의 장소");
        UUID other = place("두 번째 요청의 장소");
        UUID tripId = createTrip(owner, "2026-10-04", "2026-10-05");
        String key = "reuse-" + UUID.randomUUID();
        String body = "{\"placeId\":\"" + place + "\",\"date\":\"2026-10-04\",\"position\":0}";

        addItem(tripId, owner, cookie, key, "\"1\"", body).andExpect(status().isCreated());

        // Clause two of the idempotency contract, which only createTrip was testing: the same key
        // with a different body is a reused key, never a silent second answer to the first request.
        addItem(tripId, owner, cookie, key, "\"1\"",
                "{\"placeId\":\"" + other + "\",\"date\":\"2026-10-04\",\"position\":1}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        // And the precondition counts too. Same body, same key, a different If-Match: the caller is
        // acting on a different trip state, which is a different command. This is what keeps the
        // fourth component of the canonical hash (docs/api/README.md section 5) a real component -
        // while the version was folded into the body slot, nothing reached the presence branch.
        addItem(tripId, owner, cookie, key, "\"2\"", body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_items WHERE trip_id = ?",
                Integer.class, tripId)).isOne();
    }

    @Test
    @DisplayName("BA-040 the stored idempotent response does not grow with the trip")
    void whatTheGuardStoresIsTheItemIdAndNotTheResponse() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        Cookie cookie = cookie(owner);
        // MAX_PER_DAY per day until one slot short of a full trip, so the request under test is the
        // one that fills it. Derived from the constants: a change to either must move this test.
        int days = (TripItem.MAX_PER_TRIP + TripItem.MAX_PER_DAY - 1) / TripItem.MAX_PER_DAY;
        LocalDate start = LocalDate.parse("2026-10-04");
        UUID tripId = createTrip(owner, start.toString(), start.plusDays(days - 1L).toString());
        int filled = 0;
        for (int day = 0; day < days && filled < TripItem.MAX_PER_TRIP - 1; day++) {
            for (int slot = 0; slot < TripItem.MAX_PER_DAY
                    && filled < TripItem.MAX_PER_TRIP - 1; slot++) {
                insertItem(tripId, place("가득 찬 여행의 장소 " + filled), start.plusDays(day), slot);
                filled++;
            }
        }
        UUID last = place("마지막 한 자리를 채우는 장소");

        MvcResult result = addItem(tripId, owner, cookie, "full-" + UUID.randomUUID(), "\"1\"",
                "{\"placeId\":\"" + last + "\",\"date\":\"" + start.plusDays(days - 1L)
                        + "\",\"position\":" + (TripItem.MAX_PER_DAY - 1) + "}")
                .andExpect(status().isCreated())
                .andReturn();

        int responseBytes = result.getResponse().getContentAsByteArray().length;
        // Scoped to this owner: every case in this class adds an item, and the container is shared.
        int storedBytes = jdbc.queryForObject(
                "SELECT octet_length(response_body::text) FROM idempotency_records"
                        + " WHERE route_key = 'POST /trips/{tripId}/items' AND owner_id = ?",
                Integer.class, owner.owner.id());
        System.out.println("[BA-040] items=" + TripItem.MAX_PER_TRIP + " responseBytes="
                + responseBytes + " storedBytes=" + storedBytes
                + " limit=" + IdempotencyRecord.RESPONSE_BODY_MAX_BYTES);

        // Measured, and the measurement is the point. The stored row holds one uuid, so it stays put
        // however large the trip grows; the response carries every day, every item and a PlaceSummary
        // each, so it grows with the trip.
        //
        // This full trip's response does NOT cross 65,536 on its own - it came to roughly 41,000 -
        // and that is the uncomfortable half of the result. The places here carry a short name and
        // nothing else: no address, no thumbnail URL, no attribution, no licence, all of which a real
        // catalog place has and all of which PlaceSummary publishes. Storing the response would
        // therefore pass in a test like this one and cross the bound on production data, and
        // requireStorable runs AFTER the command - so the first trip large enough would answer 500 to
        // a request that had already worked, and keep answering 500 on every retry of that key.
        //
        // The assertions are on the two properties that hold regardless: what is stored is flat, and
        // the response is already the same order of magnitude as the bound with almost no content.
        assertThat(storedBytes).as("the stored projection is one item id, not the response")
                .isLessThan(128);
        assertThat(responseBytes).as("a full trip is already comparable to the record bound")
                .isGreaterThan(IdempotencyRecord.RESPONSE_BODY_MAX_BYTES / 2);
        assertThat(responseBytes).as("and it dwarfs what is actually stored for it")
                .isGreaterThan(storedBytes * 100);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_items WHERE trip_id = ?",
                Integer.class, tripId)).isEqualTo(TripItem.MAX_PER_TRIP);
    }

    @Test
    @DisplayName("BA-040 the database refuses to delete an item a SCHEDULED candidate still names")
    void theOrderOfTheTwoWritesIsTheDatabasesRule() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID place = place("순서를 증명하는 장소");
        UUID tripId = createTrip(owner, "2026-10-04", "2026-10-05");
        UUID itemId = insertItem(tripId, place, LocalDate.parse("2026-10-04"), 0);
        insertScheduledCandidate(tripId, place, itemId);

        // removeTripItem moves the candidate first and says in a comment that the order is forced.
        // This is that claim, executed: ON DELETE SET NULL would blank the pointer while the status
        // still said SCHEDULED, and trip_candidates_scheduled_shape_check refuses that row.
        assertThatThrownBy(() -> jdbc.update("DELETE FROM trip_items WHERE id = ?", itemId))
                .hasMessageContaining("trip_candidates_scheduled_shape_check");
    }

    private Cookie cookie(SessionService.Bootstrap owner) {
        return new Cookie("__Host-nullnull_session", owner.cookie);
    }

    private org.springframework.test.web.servlet.ResultActions addItem(UUID tripId,
            SessionService.Bootstrap owner, Cookie cookie, String key, String ifMatch, String body)
            throws Exception {
        return mvc.perform(post("/api/v1/trips/" + tripId + "/items")
                .cookie(cookie)
                .header("Origin", "http://localhost:5173")
                .header("X-CSRF-Token", owner.csrf.token)
                .header("If-Match", ifMatch)
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body));
    }

    private UUID createTrip(SessionService.Bootstrap owner, String startDate, String endDate)
            throws Exception {
        String created = mvc.perform(post("/api/v1/trips")
                        .cookie(cookie(owner))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "trip-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"startDate\":\"" + startDate + "\",\"endDate\":\"" + endDate
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
                + " created_at, updated_at) VALUES (?, ?, 'HS', '11', 'ACTIVE', ?, ?)",
                id, name, now, now);
        return id;
    }

    /** Straight to the table, so a case does not depend on the endpoint it is not testing. */
    private UUID insertItem(UUID tripId, UUID placeId, LocalDate date, int position) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO trip_items (id, trip_id, place_id, trip_date, position, created_at,"
                + " updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, tripId, placeId, java.sql.Date.valueOf(date), position, now, now);
        return id;
    }

    private UUID insertActiveCandidate(UUID tripId, UUID placeId) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO trip_candidates (id, trip_id, place_id, status, created_at,"
                + " updated_at) VALUES (?, ?, ?, 'ACTIVE', ?, ?)", id, tripId, placeId, now, now);
        jdbc.update("INSERT INTO candidate_sources (id, candidate_id, source_type, created_at)"
                + " VALUES (?, ?, 'SEARCH', ?)", UUID.randomUUID(), id, now);
        return id;
    }

    private UUID insertScheduledCandidate(UUID tripId, UUID placeId, UUID itemId) {
        UUID id = insertActiveCandidate(tripId, placeId);
        jdbc.update("UPDATE trip_candidates SET status = 'SCHEDULED', scheduled_trip_item_id = ?"
                + " WHERE id = ?", itemId, id);
        return id;
    }

    private long version(UUID tripId) {
        return jdbc.queryForObject("SELECT version FROM trips WHERE id = ?", Long.class, tripId);
    }
}
