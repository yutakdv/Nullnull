package io.nullnull.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** BA-034 over HTTP and a real PostgreSQL. */
@SpringBootTest(properties = {
        "nullnull.catalog.public-enabled=true",
        "NULLNULL_CURSOR_SECRET=test-candidate-cursor-secret-that-is-long-enough"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-034 trip candidates")
class CandidateIT {

    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private SessionService.Bootstrap owner() {
        return sessions.bootstrap(null, null, null);
    }

    private Cookie cookie(SessionService.Bootstrap owner) {
        return new Cookie("__Host-nullnull_session", owner.cookie);
    }

    private UUID place(String name) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO places (id, canonical_name, category_code, region_code, status,"
                + " created_at, updated_at) VALUES (?, ?, 'HS', '11', 'ACTIVE', ?, ?)", id, name, now, now);
        return id;
    }

    private UUID curatedPost(String title, UUID placeId) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO posts (id, status, title, body, cover_url, published_at, created_at,"
                        + " updated_at) VALUES (?, 'PUBLISHED', ?, '본문',"
                        + " 'https://example.test/c.jpg', ?, ?, ?)", id, title, now, now, now);
        jdbc.update("INSERT INTO post_places (post_id, place_id, position, mention_type)"
                + " VALUES (?, ?, 0, 'PRIMARY')", id, placeId);
        return id;
    }

    private String trip(SessionService.Bootstrap owner) throws Exception {
        String created = mvc.perform(post("/api/v1/trips").cookie(cookie(owner))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "trip-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"startDate\":\"2026-10-04\",\"endDate\":\"2026-10-07\","
                                + "\"timezone\":\"Asia/Seoul\",\"planningLevel\":\"NOTHING\",\"interests\":[]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return created.replaceFirst("(?s)^.*?\"id\":\"([^\"]+)\".*$", "$1");
    }

    private org.springframework.test.web.servlet.ResultActions add(SessionService.Bootstrap owner,
            String tripId, UUID placeId, UUID postId, String key) throws Exception {
        String source = postId == null
                ? "{\"type\":\"SEARCH\"}"
                : "{\"type\":\"POST\",\"postId\":\"" + postId + "\"}";
        return mvc.perform(post("/api/v1/trips/" + tripId + "/candidates").cookie(cookie(owner))
                .header("Origin", "http://localhost:5173")
                .header("X-CSRF-Token", owner.csrf.token)
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content("{\"placeId\":\"" + placeId + "\",\"source\":" + source + "}"));
    }

    @Test
    @DisplayName("BA-034-T1 the same POI from a different post converges on ONE active candidate")
    void differentPostsSamePlaceConverge() throws Exception {
        var owner = owner();
        String tripId = trip(owner);
        UUID placeId = place("경복궁");
        UUID firstPost = curatedPost("첫 글", placeId);
        UUID secondPost = curatedPost("둘째 글", placeId);

        String first = add(owner, tripId, placeId, firstPost, "a-" + UUID.randomUUID())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.duplicate").value(false))
                // const false in the schema: this operation cannot change a schedule, and saying so
                // in the body is what makes invariant 2 checkable by the client.
                .andExpect(jsonPath("$.tripScheduleChanged").value(false))
                .andReturn().getResponse().getContentAsString();
        String candidateId = first.replaceAll(".*\"candidate\":\\{\"id\":\"([^\"]+)\".*", "$1");

        add(owner, tripId, placeId, secondPost, "b-" + UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.candidate.id").value(candidateId))
                // Both posts are recorded as sources of the one candidate.
                .andExpect(jsonPath("$.candidate.sources.length()").value(2));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_candidates WHERE trip_id = ?",
                Integer.class, UUID.fromString(tripId))).isOne();
    }

    @Test
    @DisplayName("BA-034-T1 concurrent saves with different keys still produce one row")
    void concurrentSavesConverge() throws Exception {
        var owner = owner();
        String tripId = trip(owner);
        UUID placeId = place("동시 저장");

        // Different Idempotency-Keys, so the guard does not collapse them - the partial unique index
        // is what has to. A service-level "check then insert" would let both through here.
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int attempt = 0; attempt < 4; attempt++) {
            String key = "race-" + UUID.randomUUID();
            calls.add(() -> add(owner, tripId, placeId, null, key).andReturn().getResponse().getStatus());
        }
        List<Future<Integer>> results = pool.invokeAll(calls);
        pool.shutdown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> result : results) {
            statuses.add(result.get());
        }

        assertThat(statuses).as("exactly one create, the rest duplicates").containsOnly(201, 200);
        assertThat(statuses.stream().filter(status -> status == 201).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_candidates WHERE trip_id = ?",
                Integer.class, UUID.fromString(tripId))).isOne();
    }

    @Test
    @DisplayName("BA-034-T2 saving a candidate changes no item and no trip version")
    void savingTouchesNoSchedule() throws Exception {
        var owner = owner();
        String tripId = trip(owner);
        UUID placeId = place("일정 무관");
        add(owner, tripId, placeId, null, "s-" + UUID.randomUUID()).andExpect(status().isCreated());

        UUID trip = UUID.fromString(tripId);
        assertThat(jdbc.queryForObject("SELECT version FROM trips WHERE id = ?", Long.class, trip))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_items WHERE trip_id = ?",
                Integer.class, trip)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_revisions WHERE trip_id = ?",
                Integer.class, trip)).isOne();
    }

    @Test
    @DisplayName("BA-034-T2 a rejected save leaves no candidate and no source behind")
    void aRejectedSaveWritesNothing() throws Exception {
        var owner = owner();
        String tripId = trip(owner);
        // A POST source with no post named. The domain refuses it before anything is written.
        mvc.perform(post("/api/v1/trips/" + tripId + "/candidates").cookie(cookie(owner))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "bad-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"placeId\":\"" + place("없음") + "\",\"source\":{\"type\":\"POST\"}}"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_candidates WHERE trip_id = ?",
                Integer.class, UUID.fromString(tripId))).isZero();
        // Scoped to this trip: the suite shares one database, so a global count is about the other
        // tests rather than this one.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_sources source"
                        + " JOIN trip_candidates candidate ON candidate.id = source.candidate_id"
                        + " WHERE candidate.trip_id = ?", Integer.class, UUID.fromString(tripId)))
                .isZero();
    }

    @Test
    @DisplayName("BA-034-T3 a dismissed place can be saved again as a NEW candidate")
    void aDismissedPlaceCanBeSavedAgain() throws Exception {
        var owner = owner();
        String tripId = trip(owner);
        UUID placeId = place("다시 저장");
        String created = add(owner, tripId, placeId, null, "one-" + UUID.randomUUID())
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String candidateId = created.replaceAll(".*\"candidate\":\\{\"id\":\"([^\"]+)\".*", "$1");

        mvc.perform(delete("/api/v1/trips/" + tripId + "/candidates/" + candidateId)
                        .cookie(cookie(owner))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token))
                .andExpect(status().isNoContent());

        // A NEW row, not the dismissed one revived: the dismissal stays as the record that the user
        // once said no, and the partial unique index excludes DISMISSED so this can insert.
        String again = add(owner, tripId, placeId, null, "two-" + UUID.randomUUID())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.duplicate").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(again).doesNotContain(candidateId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_candidates WHERE trip_id = ?",
                Integer.class, UUID.fromString(tripId))).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_candidates WHERE trip_id = ?"
                        + " AND status = 'DISMISSED'", Integer.class, UUID.fromString(tripId))).isOne();
    }

    @Test
    @DisplayName("BA-034-T3 a SCHEDULED candidate is not dismissed here")
    void aScheduledCandidateIsRefused() throws Exception {
        var owner = owner();
        String tripId = trip(owner);
        UUID placeId = place("예정됨");
        String created = add(owner, tripId, placeId, null, "sch-" + UUID.randomUUID())
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String candidateId = created.replaceAll(".*\"candidate\":\\{\"id\":\"([^\"]+)\".*", "$1");
        // Put it on the schedule the way BA-040 eventually will.
        UUID itemId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO trip_items (id, trip_id, place_id, trip_date, position, created_at,"
                        + " updated_at) VALUES (?, ?, ?, '2026-10-04', 0, ?, ?)",
                itemId, UUID.fromString(tripId), placeId, now, now);
        jdbc.update("UPDATE trip_candidates SET status = 'SCHEDULED', scheduled_trip_item_id = ?"
                + " WHERE id = ?", itemId, UUID.fromString(candidateId));

        // 409: taking it off the plan is a schedule change and must move the trip's version with it.
        // Dismissing it here would remove the place while the ETag said nothing had changed.
        mvc.perform(delete("/api/v1/trips/" + tripId + "/candidates/" + candidateId)
                        .cookie(cookie(owner))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOCK_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT status FROM trip_candidates WHERE id = ?", String.class,
                UUID.fromString(candidateId))).isEqualTo("SCHEDULED");
    }

    @Test
    @DisplayName("BA-034-T3 another owner cannot read, add to or dismiss in a trip that is not theirs")
    void ownersAreSeparated() throws Exception {
        var mine = owner();
        var theirs = owner();
        String tripId = trip(mine);
        UUID placeId = place("남의 여행");
        String created = add(mine, tripId, placeId, null, "own-" + UUID.randomUUID())
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String candidateId = created.replaceAll(".*\"candidate\":\\{\"id\":\"([^\"]+)\".*", "$1");

        // 404 everywhere, not 403: distinguishing them would let a caller probe which trips exist.
        mvc.perform(get("/api/v1/trips/" + tripId + "/candidates").cookie(cookie(theirs)))
                .andExpect(status().isNotFound());
        add(theirs, tripId, place("추가 시도"), null, "x-" + UUID.randomUUID())
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/trips/" + tripId + "/candidates/" + candidateId)
                        .cookie(cookie(theirs))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", theirs.csrf.token))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT status FROM trip_candidates WHERE id = ?", String.class,
                UUID.fromString(candidateId))).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("BA-034-T3 dismissing twice is success, not a conflict")
    void dismissIsIdempotent() throws Exception {
        var owner = owner();
        String tripId = trip(owner);
        String created = add(owner, tripId, place("두 번 해제"), null, "d-" + UUID.randomUUID())
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String candidateId = created.replaceAll(".*\"candidate\":\\{\"id\":\"([^\"]+)\".*", "$1");
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(delete("/api/v1/trips/" + tripId + "/candidates/" + candidateId)
                            .cookie(cookie(owner))
                            .header("Origin", "http://localhost:5173")
                            .header("X-CSRF-Token", owner.csrf.token))
                    .andExpect(status().isNoContent());
        }
    }

    @Test
    @DisplayName("BA-034 the feed reports SAVED_TO_SELECTED_TRIP once a candidate exists")
    void theFeedSeesTheCandidate() throws Exception {
        var owner = owner();
        String tripId = trip(owner);
        UUID placeId = place("피드 연동");
        curatedPost("피드 글", placeId);

        mvc.perform(get("/api/v1/feed").param("tripId", tripId).cookie(cookie(owner)))
                .andExpect(jsonPath("$.items[0].candidateState").value("NOT_SAVED"));
        add(owner, tripId, placeId, null, "f-" + UUID.randomUUID()).andExpect(status().isCreated());
        // The value BA-032 could not produce until this slice existed. It was left unimplemented
        // rather than guessed at, and it is now observable.
        mvc.perform(get("/api/v1/feed").param("tripId", tripId).cookie(cookie(owner)))
                .andExpect(jsonPath("$.items[0].candidateState").value("SAVED_TO_SELECTED_TRIP"));
    }

    @Test
    @DisplayName("BA-034 the CandidateSaveResult fixtures describe the shape the server really sends")
    void theSaveResultFixturesMatchTheServer() throws Exception {
        // Not a value comparison - ids, timestamps and place names differ by construction. What has
        // to agree is the SHAPE: which keys exist at each level, and the two fields that carry the
        // operation's meaning. Frontend mocks against these files, so a key the server never sends
        // (or never sends) is a screen built on something that will not arrive.
        var owner = owner();
        String tripId = trip(owner);
        UUID placeId = place("fixture 대조");
        UUID postId = curatedPost("fixture 글", placeId);

        String created = add(owner, tripId, placeId, postId, "fx1-" + UUID.randomUUID())
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String duplicate = add(owner, tripId, placeId, postId, "fx2-" + UUID.randomUUID())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertSameShape("save-result-created.json", created, false);
        assertSameShape("save-result-duplicate.json", duplicate, true);
    }

    private void assertSameShape(String fixtureName, String response, boolean expectedDuplicate)
            throws Exception {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var actual = json.readTree(response);
        var fixture = json.readTree(java.nio.file.Files.readString(
                java.nio.file.Path.of("../../packages/contracts/fixtures/candidates/" + fixtureName)));

        assertThat(fieldNames(actual)).as("%s top level", fixtureName)
                .containsExactlyInAnyOrderElementsOf(fieldNames(fixture));
        assertThat(fieldNames(actual.get("candidate"))).as("%s candidate", fixtureName)
                .containsExactlyInAnyOrderElementsOf(fieldNames(fixture.get("candidate")));
        assertThat(fieldNames(actual.get("candidate").get("sources").get(0)))
                .as("%s source", fixtureName)
                .containsExactlyInAnyOrderElementsOf(fieldNames(fixture.get("candidate")
                        .get("sources").get(0)));
        assertThat(fieldNames(actual.get("candidate").get("place"))).as("%s place", fixtureName)
                .containsExactlyInAnyOrderElementsOf(fieldNames(fixture.get("candidate").get("place")));

        assertThat(actual.get("duplicate").asBoolean()).isEqualTo(expectedDuplicate);
        assertThat(fixture.get("duplicate").asBoolean()).isEqualTo(expectedDuplicate);
        assertThat(actual.get("tripScheduleChanged").asBoolean()).isFalse();
        assertThat(actual.get("candidate").get("status").asString())
                .isEqualTo(fixture.get("candidate").get("status").asString());
        assertThat(actual.get("candidate").get("scheduledTripItemId").isNull())
                .as("%s scheduledTripItemId nullness", fixtureName)
                .isEqualTo(fixture.get("candidate").get("scheduledTripItemId").isNull());
    }

    private static List<String> fieldNames(tools.jackson.databind.JsonNode node) {
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    @Test
    @DisplayName("BA-034 listTripCandidates filters by status and pages with an opaque cursor")
    void listingFiltersAndPages() throws Exception {
        var owner = owner();
        String tripId = trip(owner);
        for (int index = 0; index < 3; index++) {
            add(owner, tripId, place("후보 " + index), null, "l-" + UUID.randomUUID())
                    .andExpect(status().isCreated());
        }
        String first = mvc.perform(get("/api/v1/trips/" + tripId + "/candidates").param("limit", "2")
                        .cookie(cookie(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.page.hasMore").value(true))
                .andReturn().getResponse().getContentAsString();
        String cursor = first.replaceAll(".*\"nextCursor\":\"([^\"]+)\".*", "$1");
        mvc.perform(get("/api/v1/trips/" + tripId + "/candidates").param("limit", "2")
                        .param("cursor", cursor).cookie(cookie(owner)))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.page.hasMore").value(false));
        mvc.perform(get("/api/v1/trips/" + tripId + "/candidates").param("status", "DISMISSED")
                        .cookie(cookie(owner)))
                .andExpect(jsonPath("$.items.length()").value(0));
    }
}
