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
 * BA-040 replaceTripItem: the schedule stays, the place changes, and the outgoing place comes back.
 *
 * <p>Three of the request's four fields are refused rather than honoured, and those refusals are
 * tested as carefully as the success: they are the difference between "the server does not support
 * this yet" and "the server quietly ignored what you sent".
 */
@SpringBootTest(properties = "nullnull.catalog.public-enabled=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-040 trip item replacement")
class TripItemReplaceIT {

    private static final LocalDate DAY_ONE = LocalDate.parse("2026-10-04");

    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("BA-040 the place changes, the schedule does not, and the outgoing place returns as a candidate")
    void replacingKeepsTheSlotAndGivesThePlaceBack() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID outgoing = place("나가는 장소");
        UUID incoming = place("들어오는 장소");
        UUID itemId = insertItem(tripId, outgoing, DAY_ONE, 3, "09:30:00");

        replace(owner, tripId, itemId, "\"1\"",
                "{\"replacementPlaceId\":\"" + incoming + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trip.version").value(2))
                .andExpect(jsonPath("$.changedItemIds[0]").value(itemId.toString()));

        // Same row, same slot, same clock time: only the place moved.
        assertThat(jdbc.queryForObject("SELECT place_id || ' ' || trip_date || ' ' || position"
                        + " || ' ' || start_time FROM trip_items WHERE id = ?", String.class, itemId))
                .isEqualTo(incoming + " 2026-10-04 3 09:30:00");

        // The outgoing place is a candidate again, with a source rather than an invented one. It was
        // never a candidate before, so TRIP_SEED is the only true answer about where it came from.
        assertThat(jdbc.queryForObject("SELECT c.place_id || ' ' || c.status || ' ' || s.source_type"
                        + " FROM trip_candidates c JOIN candidate_sources s ON s.candidate_id = c.id"
                        + " WHERE c.trip_id = ?", String.class, tripId))
                .isEqualTo(outgoing + " ACTIVE TRIP_SEED");
    }

    @Test
    @DisplayName("BA-040 a MUST_VISIT lock refuses the replacement until the request names it")
    void thePlaceLockHasToBeNamed() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID outgoing = place("꼭 가기로 한 장소");
        UUID incoming = place("대신 넣을 장소");
        UUID itemId = insertItem(tripId, outgoing, DAY_ONE, 0, null);
        lock(tripId, itemId, "MUST_VISIT");

        // Unnamed: refused, and nothing about the trip moved.
        replace(owner, tripId, itemId, "\"1\"", "{\"replacementPlaceId\":\"" + incoming + "\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOCK_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT place_id FROM trip_items WHERE id = ?", UUID.class,
                itemId)).isEqualTo(outgoing);
        assertThat(version(tripId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_candidates WHERE trip_id = ?",
                Integer.class, tripId)).isZero();

        // Named: released, and the same request now goes through. A released lock is a deleted row.
        replace(owner, tripId, itemId, "\"1\"", "{\"replacementPlaceId\":\"" + incoming
                + "\",\"releaseConstraints\":[\"MUST_VISIT\"]}")
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT place_id FROM trip_items WHERE id = ?", UUID.class,
                itemId)).isEqualTo(incoming);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_constraints WHERE trip_item_id = ?",
                Integer.class, itemId)).isZero();
    }

    @Test
    @DisplayName("BA-040 a DATE lock is carried across untouched because a replacement keeps the schedule")
    void aScheduleLockIsNotInTheWay() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID itemId = insertItem(tripId, place("날짜가 고정된 장소"), DAY_ONE, 0, null);
        jdbc.update("INSERT INTO trip_constraints (id, trip_id, trip_item_id, type, source,"
                + " date_value, created_at, updated_at) VALUES (?, ?, ?, 'DATE', 'USER', ?, ?, ?)",
                UUID.randomUUID(), tripId, itemId, java.sql.Date.valueOf(DAY_ONE),
                OffsetDateTime.now(), OffsetDateTime.now());

        replace(owner, tripId, itemId, "\"1\"",
                "{\"replacementPlaceId\":\"" + place("들어오는 장소") + "\"}")
                .andExpect(status().isOk());

        // Still there: the replacement never proposed a different date, so the lock was never asked.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_constraints WHERE trip_item_id = ?"
                + " AND type = 'DATE'", Integer.class, itemId)).isOne();
    }

    @Test
    @DisplayName("BA-040 naming DATE is refused rather than honoured, because it was never in the way")
    void aScheduleLockCannotBeReleasedHere() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID itemId = insertItem(tripId, place("장소"), DAY_ONE, 0, null);

        replace(owner, tripId, itemId, "\"1\"", "{\"replacementPlaceId\":\"" + place("다른 장소")
                + "\",\"releaseConstraints\":[\"DATE\"]}")
                .andExpect(status().isUnprocessableContent());
        assertThat(version(tripId)).isEqualTo(1);
    }

    @Test
    @DisplayName("BA-040 relationId and preserveDateTime=false are refused, not accepted and ignored")
    void theFieldsWithNoMeaningBehindThemAreRefused() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID itemId = insertItem(tripId, place("원래 장소"), DAY_ONE, 0, null);
        UUID incoming = place("새 장소");

        // No operation issues a relation id and no table stores one, so a caller cannot hold a value
        // this could check. Accepting it would let a client believe its chosen relation was verified.
        replace(owner, tripId, itemId, "\"1\"", "{\"replacementPlaceId\":\"" + incoming
                + "\",\"relationId\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("relationId"));

        // What false should do has never been decided (#203). Ignoring it would tell the caller the
        // schedule had been released when it had not.
        replace(owner, tripId, itemId, "\"1\"", "{\"replacementPlaceId\":\"" + incoming
                + "\",\"preserveDateTime\":false}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("preserveDateTime"));

        // true is the documented default and is accepted, so the refusal is about the value.
        replace(owner, tripId, itemId, "\"1\"", "{\"replacementPlaceId\":\"" + incoming
                + "\",\"preserveDateTime\":true}")
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("BA-040 a candidate-backed item gives its own candidate back instead of a new one")
    void anItemThatCameFromACandidateRestoresThatCandidate() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID outgoing = place("후보에서 온 장소");
        UUID itemId = insertItem(tripId, outgoing, DAY_ONE, 0, null);
        UUID candidateId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO trip_candidates (id, trip_id, place_id, status,"
                + " scheduled_trip_item_id, note, created_at, updated_at)"
                + " VALUES (?, ?, ?, 'SCHEDULED', ?, '원래 메모', ?, ?)",
                candidateId, tripId, outgoing, itemId, now, now);
        jdbc.update("INSERT INTO candidate_sources (id, candidate_id, source_type, created_at)"
                + " VALUES (?, ?, 'SEARCH', ?)", UUID.randomUUID(), candidateId, now);

        replace(owner, tripId, itemId, "\"1\"",
                "{\"replacementPlaceId\":\"" + place("바꿔 넣는 장소") + "\"}")
                .andExpect(status().isOk());

        // The SAME row comes back, so the note and the SEARCH source it was saved with survive - a
        // new TRIP_SEED candidate here would have invented a provenance and lost the user's note.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_candidates WHERE trip_id = ?",
                Integer.class, tripId)).isOne();
        assertThat(jdbc.queryForObject("SELECT c.status || ' ' || c.note || ' ' || s.source_type"
                        + " FROM trip_candidates c JOIN candidate_sources s ON s.candidate_id = c.id"
                        + " WHERE c.id = ?", String.class, candidateId))
                .isEqualTo("ACTIVE 원래 메모 SEARCH");
    }

    private ResultActions replace(SessionService.Bootstrap owner, UUID tripId, UUID itemId,
            String ifMatch, String body) throws Exception {
        return mvc.perform(post("/api/v1/trips/" + tripId + "/items/" + itemId + "/replace")
                .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                .header("Origin", "http://localhost:5173")
                .header("X-CSRF-Token", owner.csrf.token)
                .header("If-Match", ifMatch)
                .header("Idempotency-Key", "replace-" + UUID.randomUUID())
                .contentType("application/json")
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

    private UUID insertItem(UUID tripId, UUID placeId, LocalDate date, int position, String startTime) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO trip_items (id, trip_id, place_id, trip_date, position, start_time,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, CAST(? AS time), ?, ?)",
                id, tripId, placeId, java.sql.Date.valueOf(date), position, startTime, now, now);
        return id;
    }

    private void lock(UUID tripId, UUID itemId, String type) {
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO trip_constraints (id, trip_id, trip_item_id, type, source,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, 'USER', ?, ?)",
                UUID.randomUUID(), tripId, itemId, type, now, now);
    }

    private long version(UUID tripId) {
        return jdbc.queryForObject("SELECT version FROM trips WHERE id = ?", Long.class, tripId);
    }
}
