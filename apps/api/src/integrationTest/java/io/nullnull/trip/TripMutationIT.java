package io.nullnull.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * BA-031: trip metadata update and deletion over HTTP and a real PostgreSQL.
 *
 * <p>replaceTripInterests is absent: the interest vocabulary is still open (FCR-020), so there is
 * nothing to validate a replacement against and the operation is not implemented.
 */
@SpringBootTest(properties = "nullnull.catalog.public-enabled=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
class TripMutationIT {

    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private SessionService.Bootstrap owner() {
        return sessions.bootstrap(null, null, null);
    }

    private Cookie cookie(SessionService.Bootstrap owner) {
        return new Cookie("__Host-nullnull_session", owner.cookie);
    }

    private String createTrip(SessionService.Bootstrap owner, String start, String end) throws Exception {
        String body = "{\"startDate\":\"" + start + "\",\"endDate\":\"" + end
                + "\",\"timezone\":\"Asia/Seoul\",\"planningLevel\":\"NOTHING\",\"interests\":[]}";
        String created = mvc.perform(post("/api/v1/trips").cookie(cookie(owner))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "create-" + UUID.randomUUID())
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return created.replaceFirst("(?s)^.*?\"id\":\"([^\"]+)\".*$", "$1");
    }

    private ResultActions patchTrip(SessionService.Bootstrap owner, String id, String etag, String body)
            throws Exception {
        return mvc.perform(patch("/api/v1/trips/" + id).cookie(cookie(owner))
                .header("Origin", "http://localhost:5173")
                .header("X-CSRF-Token", owner.csrf.token)
                .header("If-Match", etag)
                .contentType("application/merge-patch+json").content(body));
    }

    /** A place row the seeded items and locks can point at; trip_items.place_id is a foreign key. */
    private UUID seedPlace() {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO places (id, canonical_name, category_code, region_code, status,"
                        + " created_at, updated_at) VALUES (?, ?, 'HS', '11', 'ACTIVE', ?, ?)",
                id, "테스트 장소", now, now);
        return id;
    }

    @Test
    @DisplayName("BA-031-T1 two tabs race on one trip and only one wins; the loser sees the version")
    void onlyOneOfTwoConcurrentUpdatesWins() throws Exception {
        var owner = owner();
        String id = createTrip(owner, "2026-10-04", "2026-10-07");

        // Both tabs hold the same ETag, which is exactly the state If-Match exists to arbitrate.
        patchTrip(owner, id, "\"1\"", "{\"title\":\"첫 번째 탭\"}")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""));
        patchTrip(owner, id, "\"1\"", "{\"title\":\"두 번째 탭\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRIP_CHANGED"));

        // The latest edit stands, and the version moved exactly once.
        assertThat(jdbc.queryForObject("SELECT title FROM trips WHERE id = ?", String.class,
                UUID.fromString(id))).isEqualTo("첫 번째 탭");
        assertThat(jdbc.queryForObject("SELECT version FROM trips WHERE id = ?", Long.class,
                UUID.fromString(id))).isEqualTo(2L);
    }

    @Test
    @DisplayName("BA-031-T1 many fields in one patch still raise the version exactly once")
    void onepatchIsOneVersion() throws Exception {
        var owner = owner();
        String id = createTrip(owner, "2026-10-04", "2026-10-07");
        patchTrip(owner, id, "\"1\"",
                "{\"title\":\"셋 다\",\"planningLevel\":\"MOSTLY_PLANNED\",\"endDate\":\"2026-10-09\"}")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.days.length()").value(6));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_revisions WHERE trip_id = ?",
                Integer.class, UUID.fromString(id))).isEqualTo(2);
    }

    @Test
    @DisplayName("BA-031-T2 a shrink past a scheduled item is refused and changes nothing")
    void aShrinkPastAnItemChangesNothing() throws Exception {
        var owner = owner();
        UUID place = seedPlace();
        String body = "{\"startDate\":\"2026-10-04\",\"endDate\":\"2026-10-07\",\"timezone\":\"Asia/Seoul\","
                + "\"planningLevel\":\"NOTHING\",\"interests\":[],\"seedItems\":["
                + "{\"placeId\":\"" + place + "\",\"date\":\"2026-10-07\",\"position\":0}]}";
        String created = mvc.perform(post("/api/v1/trips").cookie(cookie(owner))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "seed-" + UUID.randomUUID())
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.days.length()").value(4))
                .andReturn().getResponse().getContentAsString();
        String id = created.replaceFirst("(?s)^.*?\"id\":\"([^\"]+)\".*$", "$1");

        patchTrip(owner, id, "\"1\"", "{\"endDate\":\"2026-10-05\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("ItemOutsideRange"));

        // Nothing moved: not the item, not the range, not the version.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_items WHERE trip_id = ?",
                Integer.class, UUID.fromString(id))).isOne();
        assertThat(jdbc.queryForObject("SELECT trip_date::text FROM trip_items WHERE trip_id = ?",
                String.class, UUID.fromString(id))).isEqualTo("2026-10-07");
        assertThat(jdbc.queryForObject("SELECT end_date::text FROM trips WHERE id = ?", String.class,
                UUID.fromString(id))).isEqualTo("2026-10-07");
        assertThat(jdbc.queryForObject("SELECT version FROM trips WHERE id = ?", Long.class,
                UUID.fromString(id))).isEqualTo(1L);
    }

    @Test
    @DisplayName("BA-031-T2 a RESERVATION lock outside the new range refuses the shrink by itself")
    void aReservationOutsideTheRangeRefusesTheShrink() throws Exception {
        var owner = owner();
        UUID place = seedPlace();
        // The item sits on the 4th, which survives the shrink; only its booking is on the 7th.
        String body = "{\"startDate\":\"2026-10-04\",\"endDate\":\"2026-10-07\",\"timezone\":\"Asia/Seoul\","
                + "\"planningLevel\":\"NOTHING\",\"interests\":[],\"seedItems\":["
                + "{\"placeId\":\"" + place + "\",\"date\":\"2026-10-04\",\"position\":0,"
                + "\"constraints\":[{\"type\":\"RESERVATION\",\"locked\":true,\"source\":\"USER\","
                + "\"date\":\"2026-10-07\",\"startTime\":\"13:00:00\"}]}]}";
        String created = mvc.perform(post("/api/v1/trips").cookie(cookie(owner))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "res-" + UUID.randomUUID())
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = created.replaceFirst("(?s)^.*?\"id\":\"([^\"]+)\".*$", "$1");

        patchTrip(owner, id, "\"1\"", "{\"endDate\":\"2026-10-05\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.fieldErrors[0].code").value("LockedDateOutsideRange"));
        // The lock is still there. A refused shrink must not have released it (invariant 7).
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_constraints WHERE trip_id = ?",
                Integer.class, UUID.fromString(id))).isOne();
    }

    @Test
    @DisplayName("BA-031-T2 a shrink that keeps everything inside is allowed")
    void aShrinkThatKeepsEverythingInsideIsAllowed() throws Exception {
        var owner = owner();
        String id = createTrip(owner, "2026-10-04", "2026-10-09");
        patchTrip(owner, id, "\"1\"", "{\"endDate\":\"2026-10-05\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days.length()").value(2));
    }

    @Test
    @DisplayName("BA-031-T3 deleting a trip clears the owner's active trip and every owned row")
    void deleteRemovesTheAggregateAndClearsTheActiveTrip() throws Exception {
        var owner = owner();
        UUID place = seedPlace();
        String body = "{\"startDate\":\"2026-10-04\",\"endDate\":\"2026-10-07\",\"timezone\":\"Asia/Seoul\","
                + "\"planningLevel\":\"NOTHING\",\"interests\":[{\"code\":\"FOOD\",\"weight\":3}],"
                + "\"seedItems\":[{\"placeId\":\"" + place + "\",\"date\":\"2026-10-04\",\"position\":0,"
                + "\"constraints\":[{\"type\":\"MUST_VISIT\",\"locked\":true,\"source\":\"USER\"}]}]}";
        String created = mvc.perform(post("/api/v1/trips").cookie(cookie(owner))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "del-" + UUID.randomUUID())
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = created.replaceFirst("(?s)^.*?\"id\":\"([^\"]+)\".*$", "$1");

        mvc.perform(patch("/api/v1/me").cookie(cookie(owner))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .contentType("application/merge-patch+json")
                        .content("{\"activeTripId\":\"" + id + "\"}"))
                .andExpect(status().isOk());

        mvc.perform(delete("/api/v1/trips/" + id).cookie(cookie(owner))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("If-Match", "\"1\"")
                        .header("Idempotency-Key", "delete-" + UUID.randomUUID()))
                .andExpect(status().isNoContent());

        UUID tripId = UUID.fromString(id);
        for (String table : new String[] {"trips", "trip_interests", "trip_items", "trip_constraints",
                "trip_revisions"}) {
            String column = "trips".equals(table) ? "id" : "trip_id";
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " = ?",
                    Integer.class, tripId)).as(table).isZero();
        }
        // The pointer cannot outlive what it points at.
        assertThat(jdbc.queryForObject("SELECT active_trip_id FROM owners WHERE id = ?", UUID.class,
                owner.owner.id())).isNull();
        mvc.perform(get("/api/v1/trips/" + id).cookie(cookie(owner))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("BA-031-T3 a stale If-Match refuses the delete and the trip survives")
    void aStaleIfMatchRefusesTheDelete() throws Exception {
        var owner = owner();
        String id = createTrip(owner, "2026-10-04", "2026-10-07");
        patchTrip(owner, id, "\"1\"", "{\"title\":\"바뀐 제목\"}").andExpect(status().isOk());

        mvc.perform(delete("/api/v1/trips/" + id).cookie(cookie(owner))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("If-Match", "\"1\"")
                        .header("Idempotency-Key", "stale-" + UUID.randomUUID()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRIP_CHANGED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trips WHERE id = ?", Integer.class,
                UUID.fromString(id))).isOne();
    }

    @Test
    @DisplayName("BA-031-T3 another owner cannot delete or patch a trip that is not theirs")
    void anotherOwnerCannotMutateIt() throws Exception {
        var mine = owner();
        var theirs = owner();
        String id = createTrip(mine, "2026-10-04", "2026-10-07");

        patchTrip(theirs, id, "\"1\"", "{\"title\":\"남의 여행\"}").andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/trips/" + id).cookie(cookie(theirs))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", theirs.csrf.token)
                        .header("If-Match", "\"1\"")
                        .header("Idempotency-Key", "foreign-" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trips WHERE id = ?", Integer.class,
                UUID.fromString(id))).isOne();
    }

    @Test
    @DisplayName("BA-031-T1 a malformed or absent If-Match is refused before anything is read")
    void ifMatchMustBeTheQuotedVersion() throws Exception {
        var owner = owner();
        String id = createTrip(owner, "2026-10-04", "2026-10-07");
        for (String bad : new String[] {"1", "\"0\"", "W/\"1\"", "\"abc\""}) {
            patchTrip(owner, id, bad, "{\"title\":\"x\"}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        assertThat(jdbc.queryForObject("SELECT version FROM trips WHERE id = ?", Long.class,
                UUID.fromString(id))).isEqualTo(1L);
    }

    @Test
    @DisplayName("BA-031-T1 an unknown patch field is refused rather than ignored")
    void anUnknownPatchFieldIsRefused() throws Exception {
        var owner = owner();
        String id = createTrip(owner, "2026-10-04", "2026-10-07");
        patchTrip(owner, id, "\"1\"", "{\"nonsense\":true}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("nonsense"));
        // An empty patch is refused too: it would raise the version for nothing.
        patchTrip(owner, id, "\"1\"", "{}").andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("BA-031 replaceTripInterests swaps the whole set and raises the version once")
    void replacingInterestsIsOneVersion() throws Exception {
        var owner = owner();
        String id = createTrip(owner, "2026-10-04", "2026-10-07");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/trips/" + id + "/interests").cookie(cookie(owner))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("If-Match", "\"1\"")
                        .contentType("application/json")
                        .content("{\"interests\":[{\"code\":\"FOOD\",\"weight\":3},"
                                + "{\"code\":\"NATURE\",\"weight\":3}]}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.interests.length()").value(2));

        // Replace, not merge: the previous set is gone rather than added to.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/trips/" + id + "/interests").cookie(cookie(owner))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("If-Match", "\"2\"")
                        .contentType("application/json")
                        .content("{\"interests\":[{\"code\":\"ALONE\",\"weight\":3}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interests.length()").value(1))
                .andExpect(jsonPath("$.interests[0].code").value("ALONE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_interests WHERE trip_id = ?",
                Integer.class, UUID.fromString(id))).isOne();
        // An empty set is valid: the wizard allows choosing nothing.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/trips/" + id + "/interests").cookie(cookie(owner))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("If-Match", "\"3\"")
                        .contentType("application/json").content("{\"interests\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interests").isEmpty());
    }

    @Test
    @DisplayName("BA-030 an interest code outside the FCR-020 vocabulary is refused")
    void anUnsupportedInterestCodeIsRefused() throws Exception {
        var owner = owner();
        // Plausible but not a chip. Before FCR-020 settled this was accepted, which would have left
        // rows no screen could render.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/trips").cookie(cookie(owner))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "vocab-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"startDate\":\"2026-10-04\",\"endDate\":\"2026-10-07\","
                                + "\"timezone\":\"Asia/Seoul\",\"planningLevel\":\"NOTHING\","
                                + "\"interests\":[{\"code\":\"SHOPPING\",\"weight\":3}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.fieldErrors[0].code").value("Unsupported"))
                // The rejected value is never echoed back.
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("SHOPPING"))));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trips WHERE owner_id = ?", Integer.class,
                owner.owner.id())).isZero();
    }

    @Test
    @DisplayName("BA-031-T1 changing the timezone keeps the local dates and wall-clock times")
    void changingTheTimezoneKeepsWallClockValues() throws Exception {
        var owner = owner();
        UUID place = seedPlace();
        String body = "{\"startDate\":\"2026-10-04\",\"endDate\":\"2026-10-07\",\"timezone\":\"Asia/Seoul\","
                + "\"planningLevel\":\"NOTHING\",\"interests\":[],\"seedItems\":["
                + "{\"placeId\":\"" + place + "\",\"date\":\"2026-10-04\",\"position\":0,"
                + "\"startTime\":\"09:30:00\"}]}";
        String created = mvc.perform(post("/api/v1/trips").cookie(cookie(owner))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "tz-" + UUID.randomUUID())
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = created.replaceFirst("(?s)^.*?\"id\":\"([^\"]+)\".*$", "$1");

        patchTrip(owner, id, "\"1\"", "{\"timezone\":\"Asia/Tokyo\"}").andExpect(status().isOk());

        // The user corrected which zone their 09:30 was always in; they did not ask for a different
        // 09:30. Storing an instant instead of a wall-clock time is what would have moved it.
        assertThat(jdbc.queryForObject("SELECT start_time::text FROM trip_items WHERE trip_id = ?",
                String.class, UUID.fromString(id))).isEqualTo("09:30:00");
        assertThat(jdbc.queryForObject("SELECT trip_date::text FROM trip_items WHERE trip_id = ?",
                String.class, UUID.fromString(id))).isEqualTo("2026-10-04");
    }
}
