package io.nullnull.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * BA-030 Phase A over HTTP and a real PostgreSQL: createTrip, getTrip and listTrips.
 *
 * <p>seedItems is not exercised because the command cannot express it while PM-008 is open (#145).
 */
@SpringBootTest(properties = "nullnull.catalog.public-enabled=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
class TripCreationIT {

    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private SessionService.Bootstrap owner() {
        return sessions.bootstrap(null, null, null);
    }

    private ResultActions create(SessionService.Bootstrap owner, String key, String body) throws Exception {
        return mvc.perform(post("/api/v1/trips")
                .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                .header("Origin", "http://localhost:5173")
                .header("X-CSRF-Token", owner.csrf.token)
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body));
    }

    private static String body(String startDate, String endDate) {
        return "{\"startDate\":\"" + startDate + "\",\"endDate\":\"" + endDate
                + "\",\"timezone\":\"Asia/Seoul\",\"planningLevel\":\"NOTHING\",\"interests\":[]}";
    }

    @Test
    @DisplayName("BA-030-T1 a created trip seeds one empty day per date at version 1")
    void createSeedsOneEmptyDayPerDate() throws Exception {
        var owner = owner();
        create(owner, "create-" + UUID.randomUUID(), body("2026-10-04", "2026-10-07"))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.candidateCount").value(0))
                .andExpect(jsonPath("$.interests").isEmpty())
                .andExpect(jsonPath("$.candidates").isEmpty())
                .andExpect(jsonPath("$.days.length()").value(4))
                .andExpect(jsonPath("$.days[0].date").value("2026-10-04"))
                .andExpect(jsonPath("$.days[3].date").value("2026-10-07"))
                .andExpect(jsonPath("$.days[0].items").isEmpty())
                // The owner is anonymous with the default ko-KR locale, so the title is the Korean
                // default the contract names. It is never asked of a generator.
                .andExpect(jsonPath("$.title").value("새 여행"));
    }

    @Test
    @DisplayName("BA-030-T1 a reversed or over-long range is refused and writes nothing")
    void invalidRangesAreRefused() throws Exception {
        var owner = owner();
        create(owner, "bad-" + UUID.randomUUID(), body("2026-10-07", "2026-10-04"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("endDate"));
        create(owner, "long-" + UUID.randomUUID(), body("2026-10-01", "2026-10-31"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.fieldErrors[0].code").value("DateRangeTooLong"));
        // Exactly 30 days is the boundary and is accepted.
        create(owner, "edge-" + UUID.randomUUID(), body("2026-10-01", "2026-10-30"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.days.length()").value(30));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trips WHERE owner_id = ?", Integer.class,
                owner.owner.id())).isOne();
    }

    @Test
    @DisplayName("BA-030-T1 an unknown timezone is refused without echoing what was sent")
    void unknownTimezoneIsRefused() throws Exception {
        var owner = owner();
        create(owner, "tz-" + UUID.randomUUID(),
                "{\"startDate\":\"2026-10-04\",\"endDate\":\"2026-10-07\",\"timezone\":\"Mars/Olympus\","
                        + "\"planningLevel\":\"NOTHING\",\"interests\":[]}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("timezone"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Mars"))));
    }

    @Test
    @DisplayName("BA-030-T2 a repeated Idempotency-Key creates one trip and replays the same body")
    void repeatedKeyCreatesOneTrip() throws Exception {
        var owner = owner();
        String key = "same-" + UUID.randomUUID();
        String first = create(owner, key, body("2026-10-04", "2026-10-07"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String replay = create(owner, key, body("2026-10-04", "2026-10-07"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trips WHERE owner_id = ?", Integer.class,
                owner.owner.id())).isOne();
        // One revision, not two: a replay re-runs no effect. Scoped to this owner because the
        // suite shares one database.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_revisions r JOIN trips t"
                + " ON t.id = r.trip_id WHERE t.owner_id = ?", Integer.class, owner.owner.id())).isOne();
    }

    @Test
    @DisplayName("BA-030-T2 the same key with a different body is a reused key, not a second answer")
    void sameKeyDifferentBodyIsRejected() throws Exception {
        var owner = owner();
        String key = "reuse-" + UUID.randomUUID();
        create(owner, key, body("2026-10-04", "2026-10-07")).andExpect(status().isCreated());
        create(owner, key, body("2026-11-04", "2026-11-07"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trips WHERE owner_id = ?", Integer.class,
                owner.owner.id())).isOne();
    }

    @Test
    @DisplayName("BA-030-T2 a rejected create leaves no trip, interest or revision behind")
    void aRejectedCreateWritesNothing() throws Exception {
        var owner = owner();
        create(owner, "dup-" + UUID.randomUUID(),
                "{\"startDate\":\"2026-10-04\",\"endDate\":\"2026-10-07\",\"timezone\":\"Asia/Seoul\","
                        + "\"planningLevel\":\"NOTHING\",\"interests\":"
                        + "[{\"code\":\"FOOD\",\"weight\":1},{\"code\":\"FOOD\",\"weight\":5}]}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.fieldErrors[0].code").value("Duplicate"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trips WHERE owner_id = ?", Integer.class,
                owner.owner.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_interests i JOIN trips t"
                + " ON t.id = i.trip_id WHERE t.owner_id = ?", Integer.class, owner.owner.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_revisions r JOIN trips t"
                + " ON t.id = r.trip_id WHERE t.owner_id = ?", Integer.class, owner.owner.id())).isZero();
    }

    @Test
    @DisplayName("BA-030-T3 getTrip returns the complete view with the version as its ETag")
    void getReturnsTheCompleteViewAndEtag() throws Exception {
        var owner = owner();
        String created = create(owner, "get-" + UUID.randomUUID(),
                "{\"startDate\":\"2026-10-04\",\"endDate\":\"2026-10-05\",\"timezone\":\"Asia/Seoul\","
                        + "\"planningLevel\":\"MUST_VISIT_ONLY\",\"interests\":"
                        + "[{\"code\":\"FOOD\",\"weight\":3}]}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = created.replaceFirst("(?s)^.*?\"id\":\"([^\"]+)\".*$", "$1");

        mvc.perform(get("/api/v1/trips/" + id)
                        .cookie(new Cookie("__Host-nullnull_session", owner.cookie)))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.planningLevel").value("MUST_VISIT_ONLY"))
                .andExpect(jsonPath("$.interests[0].code").value("FOOD"))
                .andExpect(jsonPath("$.interests[0].weight").value(3))
                .andExpect(jsonPath("$.days.length()").value(2));
    }

    @Test
    @DisplayName("BA-030-T3 another owner's trip is absent, not forbidden")
    void anotherOwnersTripIsAbsent() throws Exception {
        var mine = owner();
        var theirs = owner();
        String created = create(mine, "mine-" + UUID.randomUUID(), body("2026-10-04", "2026-10-07"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = created.replaceFirst("(?s)^.*?\"id\":\"([^\"]+)\".*$", "$1");

        // 404, not 403: telling the two apart would let a caller probe which trip ids exist. The
        // body must be identical to the one an unknown id produces.
        String foreign = mvc.perform(get("/api/v1/trips/" + id)
                        .cookie(new Cookie("__Host-nullnull_session", theirs.cookie)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
        String unknown = mvc.perform(get("/api/v1/trips/" + UUID.randomUUID())
                        .cookie(new Cookie("__Host-nullnull_session", theirs.cookie)))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertThat(detailOf(foreign)).isEqualTo(detailOf(unknown));
    }

    @Test
    @DisplayName("BA-030-T3 listTrips pages with an opaque cursor and lists only this owner's trips")
    void listPagesAndIsOwnerScoped() throws Exception {
        var mine = owner();
        var theirs = owner();
        for (int day = 1; day <= 3; day++) {
            create(mine, "list-" + UUID.randomUUID(), body("2026-10-0" + day, "2026-10-0" + (day + 1)))
                    .andExpect(status().isCreated());
        }
        create(theirs, "other-" + UUID.randomUUID(), body("2026-12-01", "2026-12-02"))
                .andExpect(status().isCreated());

        String first = mvc.perform(get("/api/v1/trips").param("limit", "2")
                        .cookie(new Cookie("__Host-nullnull_session", mine.cookie)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.page.hasMore").value(true))
                // Most recent start date first, so the newest trip leads.
                .andExpect(jsonPath("$.items[0].startDate").value("2026-10-03"))
                .andReturn().getResponse().getContentAsString();
        String cursor = first.replaceAll(".*\"nextCursor\":\"([^\"]+)\".*", "$1");
        assertThat(cursor).doesNotContain(mine.owner.id().toString());

        mvc.perform(get("/api/v1/trips").param("limit", "2").param("cursor", cursor)
                        .cookie(new Cookie("__Host-nullnull_session", mine.cookie)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.page.hasMore").value(false))
                .andExpect(jsonPath("$.page.nextCursor").isEmpty());

        // The other owner sees only their own, and a cursor cut for one owner does not open another's
        // listing.
        mvc.perform(get("/api/v1/trips")
                        .cookie(new Cookie("__Host-nullnull_session", theirs.cookie)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/v1/trips").param("cursor", cursor)
                        .cookie(new Cookie("__Host-nullnull_session", theirs.cookie)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("BA-030-T3 a trip becomes selectable as the owner's active trip")
    void aCreatedTripCanBecomeTheActiveTrip() throws Exception {
        // Before BA-030 the production trip port always answered no, so no identifier could be
        // selected. This is the behaviour that replaced it.
        var owner = owner();
        String created = create(owner, "active-" + UUID.randomUUID(), body("2026-10-04", "2026-10-07"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = created.replaceFirst("(?s)^.*?\"id\":\"([^\"]+)\".*$", "$1");

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/me")
                        .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .contentType("application/merge-patch+json")
                        .content("{\"activeTripId\":\"" + id + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeTripId").value(id));
    }

    /** The parts of a Problem that must match; requestId and instance legitimately differ. */
    private static String detailOf(String problem) {
        return problem.replaceAll("\"requestId\":\"[^\"]+\"", "")
                .replaceAll("\"instance\":\"[^\"]+\"", "");
    }
    @Test
    @DisplayName("BA-030-T2 a RESERVATION and a DATE lock coexist on one item (#144, invariant 7)")
    void reservationAndDateLockCoexistOnOneItem() throws Exception {
        // The fixture trips/trip-detail-reservation.json teaches Frontend this pair, and invariant
        // 7's "independent" only means something where two locks sit on the SAME item - no fixture
        // had that. A hand-written fixture is not evidence the server accepts it, so this creates
        // the pair through the real create path.
        var owner = owner();
        UUID place = UUID.randomUUID();
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
        jdbc.update("INSERT INTO places (id, canonical_name, category_code, region_code, status,"
                + " created_at, updated_at) VALUES (?, ?, 'HS', '11', 'ACTIVE', ?, ?)",
                place, "테스트 장소", now, now);
        String body = "{\"startDate\":\"2026-10-04\",\"endDate\":\"2026-10-07\",\"timezone\":\"Asia/Seoul\","
                + "\"planningLevel\":\"NOTHING\",\"interests\":[],\"seedItems\":["
                + "{\"placeId\":\"" + place + "\",\"date\":\"2026-10-05\",\"position\":0,"
                + "\"startTime\":\"18:30:00\",\"constraints\":["
                + "{\"type\":\"DATE\",\"locked\":true,\"source\":\"USER\",\"date\":\"2026-10-05\"},"
                + "{\"type\":\"RESERVATION\",\"locked\":true,\"source\":\"USER\","
                + "\"date\":\"2026-10-05\",\"startTime\":\"18:30:00\",\"endTime\":\"20:00:00\"}]}]}";
        String created = mvc.perform(post("/api/v1/trips")
                        .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "reservation-" + UUID.randomUUID())
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(created.replaceFirst("(?s)^.*?\"id\":\"([^\"]+)\".*$", "$1"));

        // Both are stored, and neither released the other: two rows, each with its own values.
        // Rendered as one string per row so a missing value is visible rather than absent.
        assertThat(jdbc.queryForList(
                "SELECT c.type || ' ' || coalesce(c.date_value::text, '-')"
                        + " || ' ' || coalesce(c.start_time_value::text, '-')"
                        + " || ' ' || coalesce(c.end_time_value::text, '-') AS row"
                        + " FROM trip_constraints c JOIN trip_items i ON i.id = c.trip_item_id"
                        + " WHERE i.trip_id = ? ORDER BY c.type", String.class, id))
                .containsExactly("DATE 2026-10-05 - -", "RESERVATION 2026-10-05 18:30:00 20:00:00");
        // The item sits inside its own reservation window rather than beside it.
        assertThat(jdbc.queryForObject("SELECT start_time::text FROM trip_items WHERE trip_id = ?",
                String.class, id)).isEqualTo("18:30:00");
    }

    @Test
    @DisplayName("BA-040-T1 getTrip projects the stored item onto its day with the place it names")
    void theDetailProjectionCarriesItems() throws Exception {
        // This case used to assert the opposite - that days came back empty - and said it would turn
        // RED the moment BA-040 projected items, which is when the fixtures become server-verifiable
        // and the real assertion replaces it. That happened; this is the real assertion.
        var owner = owner();
        UUID place = UUID.randomUUID();
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
        jdbc.update("INSERT INTO places (id, canonical_name, category_code, region_code, status,"
                + " created_at, updated_at) VALUES (?, ?, 'HS', '11', 'ACTIVE', ?, ?)",
                place, "테스트 장소", now, now);
        String created = mvc.perform(post("/api/v1/trips")
                        .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "projection-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"startDate\":\"2026-10-04\",\"endDate\":\"2026-10-05\","
                                + "\"timezone\":\"Asia/Seoul\",\"planningLevel\":\"NOTHING\","
                                + "\"interests\":[],\"seedItems\":[{\"placeId\":\"" + place
                                + "\",\"date\":\"2026-10-04\",\"position\":0}]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(created.replaceFirst("(?s)^.*?\"id\":\"([^\"]+)\".*$", "$1"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_items WHERE trip_id = ?",
                Integer.class, id)).isOne();
        mvc.perform(get("/api/v1/trips/" + id)
                        .cookie(new Cookie("__Host-nullnull_session", owner.cookie)))
                .andExpect(status().isOk())
                // The day the item sits on carries it, with the place resolved through the catalog's
                // gated projection - TripItem.place is required, so an id alone is not an answer.
                .andExpect(jsonPath("$.days[0].date").value("2026-10-04"))
                .andExpect(jsonPath("$.days[0].items.length()").value(1))
                .andExpect(jsonPath("$.days[0].items[0].place.id").value(place.toString()))
                .andExpect(jsonPath("$.days[0].items[0].place.name").value("테스트 장소"))
                .andExpect(jsonPath("$.days[0].items[0].position").value(0))
                .andExpect(jsonPath("$.days[0].items[0].startTime").doesNotExist())
                // The other day of the range is still present and empty: the array is the trip's
                // calendar, not the list of days that happen to be busy.
                .andExpect(jsonPath("$.days[1].date").value("2026-10-05"))
                .andExpect(jsonPath("$.days[1].items").isEmpty());

        // createTrip answers from the same projection. It rebuilds its response from the stored
        // idempotency record, so fixing getTrip alone would have left the create response carrying
        // items: [] - the silent-empty shape #162 ruled out.
        assertThat(created).contains("\"place\"");
    }

}
