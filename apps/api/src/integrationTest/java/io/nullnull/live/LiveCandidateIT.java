package io.nullnull.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.crowd.application.SeoulLiveSnapshotStore;
import io.nullnull.crowd.domain.SeoulCongestionStage;
import io.nullnull.identity.application.SessionService;
import io.nullnull.live.application.LiveAreaStore;
import io.nullnull.testsupport.OwnedRows;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * A place discovered on the Live tab, saved as a candidate - invariant 2 on the one source type
 * nothing had ever driven.
 *
 * <p>{@code CandidateSourceType.LIVE} has been in the enum, in {@code V016}'s CHECK and in the
 * contract's closed {@code AddCandidateSource.type} since B04, and the contract says why in the
 * schema's own words: "LIVE already belongs to a tab that does not exist yet". BA-034 proves
 * invariant 2 for SEARCH and POST and never sends LIVE - measured: the only two literals its
 * {@code add} helper can produce are {@code {"type":"SEARCH"}} and {@code {"type":"POST",...}}. So
 * the value was declared in three places, refused by none of them, and exercised by nothing.
 *
 * <p><strong>The place id comes from getLivePlace, not from the fixture variable.</strong> That is
 * the whole difference between this file and a fourth copy of BA-034: the clause is that the id the
 * Live screen hands a user is an id the candidate route accepts, and reading it back out of the Live
 * response is what makes the two ends actually meet.
 */
@SpringBootTest(properties = {
        "nullnull.capabilities.live=true",
        "nullnull.catalog.public-enabled=true",
        "NULLNULL_CURSOR_SECRET=test-live-candidate-secret-that-is-long-enough"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-091 Live 에서 고른 장소를 후보로 저장한다")
class LiveCandidateIT {

    private static final String SOURCE = "SEOUL_CITYDATA";
    private static final String ORIGIN = "http://localhost:5173";

    @Autowired MockMvc mvc;
    @Autowired SessionService sessions;
    @Autowired LiveAreaStore areas;
    @Autowired SeoulLiveSnapshotStore snapshots;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    private List<UUID> placesBefore;
    private List<UUID> snapshotsBefore;
    private List<UUID> setsBefore;
    private List<UUID> areasBefore;
    private List<UUID> runsBefore;

    @BeforeEach
    void noteRowsAlreadyPresent() {
        placesBefore = OwnedRows.snapshot(jdbc, "places");
        snapshotsBefore = OwnedRows.snapshot(jdbc, "crowd_snapshots");
        setsBefore = OwnedRows.snapshot(jdbc, "snapshot_sets");
        areasBefore = OwnedRows.snapshot(jdbc, "live_areas");
        runsBefore = OwnedRows.snapshot(jdbc, "collector_runs");
    }

    @AfterEach
    void removeOnlyWhatThisTestCreated() {
        OwnedRows.remove(jdbc, "places", OwnedRows.appeared(jdbc, "places", placesBefore));
        OwnedRows.remove(jdbc, "crowd_snapshots", OwnedRows.appeared(jdbc, "crowd_snapshots", snapshotsBefore));
        OwnedRows.remove(jdbc, "snapshot_sets", OwnedRows.appeared(jdbc, "snapshot_sets", setsBefore));
        OwnedRows.remove(jdbc, "live_areas", OwnedRows.appeared(jdbc, "live_areas", areasBefore));
        OwnedRows.remove(jdbc, "collector_runs", OwnedRows.appeared(jdbc, "collector_runs", runsBefore));
    }

    @Test
    @DisplayName("BA-091-T3 Live 에서 고른 장소를 후보로 저장해도 일정은 바뀌지 않는다")
    void savingALivePlaceAsACandidateTouchesNoSchedule() throws Exception {
        SessionService.Bootstrap owner = owner();
        String tripId = trip(owner);
        UUID place = livePlaceSeenOnTheLiveTab(owner, "POI301", "경복궁 후보");

        add(owner, tripId, place, "live-new-" + UUID.randomUUID())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.duplicate").value(false))
                // The server says it in the body, which is what makes invariant 2 checkable by the
                // client rather than only by this test. The schema pins the field to const false.
                .andExpect(jsonPath("$.tripScheduleChanged").value(false))
                // sources, plural: TripCandidate carries a LIST, because one place can be saved
                // from more than one place and BA-034-T1 converges them onto a single candidate.
                .andExpect(jsonPath("$.candidate.sources.length()").value(1))
                .andExpect(jsonPath("$.candidate.sources[0].type").value("LIVE"));

        assertScheduleUntouched(tripId);
        // And the source really was stored as LIVE - V016's CHECK accepts it and nothing had ever
        // written one, so "the route returned LIVE" and "a LIVE row exists" are two statements.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM candidate_sources cs
                  JOIN trip_candidates tc ON tc.id = cs.candidate_id
                 WHERE tc.trip_id = ? AND cs.source_type = 'LIVE' AND cs.post_id IS NULL
                """, Integer.class, UUID.fromString(tripId))).isOne();
    }

    @Test
    @DisplayName("BA-091 같은 Live 장소를 다시 저장하면 duplicate 이고 일정은 그대로다")
    void savingTheSameLivePlaceAgainIsADuplicateAndStillTouchesNothing() throws Exception {
        SessionService.Bootstrap owner = owner();
        String tripId = trip(owner);
        UUID place = livePlaceSeenOnTheLiveTab(owner, "POI302", "중복 후보");

        add(owner, tripId, place, "live-first-" + UUID.randomUUID()).andExpect(status().isCreated());
        // A DIFFERENT key, so this is the duplicate branch rather than the idempotent replay below -
        // the two are told apart by nothing else, and conflating them would leave one untested.
        add(owner, tripId, place, "live-second-" + UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.tripScheduleChanged").value(false));

        assertScheduleUntouched(tripId);
        assertThat(candidateCount(tripId)).as("one place, one active candidate").isOne();
    }

    @Test
    @DisplayName("BA-091 같은 key 로 재시도해도 후보는 하나이고 일정은 그대로다")
    void aRetryWithTheSameKeyLeavesOneCandidateAndTheScheduleWhereItWas() throws Exception {
        SessionService.Bootstrap owner = owner();
        String tripId = trip(owner);
        UUID place = livePlaceSeenOnTheLiveTab(owner, "POI303", "재시도 후보");
        String key = "live-retry-" + UUID.randomUUID();

        String first = add(owner, tripId, place, key)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String replayed = add(owner, tripId, place, key)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        // The stored response replayed byte for byte, not recomputed: a second save that happened to
        // produce an equivalent body would also pass a looser check, and it would have written a
        // second row.
        assertThat(replayed).isEqualTo(first);
        assertScheduleUntouched(tripId);
        assertThat(candidateCount(tripId)).isOne();
    }

    /**
     * Version, items and revision count - three separate ways invariant 2 could break, the same
     * three {@code CandidateIT.assertScheduleUntouched} watches. A write that bumped only the
     * version would still have broken it, and so would one that added an item without a revision.
     */
    private void assertScheduleUntouched(String tripId) {
        UUID trip = UUID.fromString(tripId);
        assertThat(jdbc.queryForObject("SELECT version FROM trips WHERE id = ?", Long.class, trip))
                .as("trip schedule version").isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_items WHERE trip_id = ?",
                Integer.class, trip)).as("trip items").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_revisions WHERE trip_id = ?",
                Integer.class, trip)).as("trip revisions").isOne();
    }

    private Integer candidateCount(String tripId) {
        return jdbc.queryForObject("SELECT count(*) FROM trip_candidates WHERE trip_id = ? AND status <> 'DISMISSED'",
                Integer.class, UUID.fromString(tripId));
    }

    /**
     * The Live flow, not a fixture shortcut: an area with a reading, a place mapped into it, and the
     * id read back out of {@code getLivePlace}'s own response.
     */
    private UUID livePlaceSeenOnTheLiveTab(SessionService.Bootstrap owner, String externalId, String name)
            throws Exception {
        Instant now = clock.instant();
        UUID areaId = areas.upsertArea(SOURCE, new LiveAreaStore.AreaUpsert(externalId, name + " 구역")).id();
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO collector_runs
                    (id, source_code, status, trigger_type, records_received, records_accepted,
                     records_rejected, schema_version, started_at, finished_at)
                VALUES (?, ?, 'COMPLETED', 'SCHEDULED', 1, 1, 0, 'seoul-citydata-v8.5', ?, ?)
                """, runId, SOURCE, Timestamp.from(now), Timestamp.from(now));
        snapshots.save(SeoulLiveSnapshotStore.Reading.of(UUID.randomUUID(), UUID.randomUUID(), runId, 2L,
                areaId, now.minusSeconds(60), now.minusSeconds(55), 300L, SeoulCongestionStage.of("보통")));
        UUID placeId = place(name);
        jdbc.update("""
                INSERT INTO seoul_live_area_maps
                    (id, place_id, live_area_id, mapping_type, confidence, fallback_used, verified_at)
                VALUES (?, ?, ?, 'AREA', 0.9000, false, ?)
                """, UUID.randomUUID(), placeId, areaId, Timestamp.from(now));

        String body = mvc.perform(get("/api/v1/live/places/{id}", placeId).cookie(cookie(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataState").value("LIVE"))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(body).get("place").get("id").asString());
    }

    private ResultActions add(SessionService.Bootstrap owner, String tripId, UUID placeId, String key)
            throws Exception {
        // No postId: V016's candidate_sources_post_shape_check refuses one on anything but POST, so
        // a LIVE save that carried a post would be rejected by the database rather than by us.
        return mvc.perform(post("/api/v1/trips/{id}/candidates", tripId).cookie(cookie(owner))
                .header("Origin", ORIGIN)
                .header("X-CSRF-Token", owner.csrf.token)
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content("{\"placeId\":\"" + placeId + "\",\"source\":{\"type\":\"LIVE\"}}"));
    }

    private String trip(SessionService.Bootstrap owner) throws Exception {
        String created = mvc.perform(post("/api/v1/trips").cookie(cookie(owner))
                        .header("Origin", ORIGIN)
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "trip-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"startDate\":\"2026-10-04\",\"endDate\":\"2026-10-07\","
                                + "\"timezone\":\"Asia/Seoul\",\"planningLevel\":\"NOTHING\",\"interests\":[]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return tools.jackson.databind.json.JsonMapper.builder().build().readTree(created)
                .get("id").asString();
    }

    private UUID place(String name) {
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.update("""
                INSERT INTO places
                    (id, canonical_name, category_code, latitude, longitude, region_code, status,
                     created_at, updated_at)
                VALUES (?, ?, 'A0201', 37.579617, 126.977041, '11', 'ACTIVE', ?, ?)
                """, id, name, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO place_localizations (id, place_id, locale, name, address, updated_at)
                VALUES (?, ?, 'ko-KR', ?, '서울시 어딘가', ?)
                """, UUID.randomUUID(), id, name, Timestamp.from(now));
        return id;
    }

    private SessionService.Bootstrap owner() {
        return sessions.bootstrap(null, "ko-KR", "Asia/Seoul");
    }

    private static Cookie cookie(SessionService.Bootstrap owner) {
        return new Cookie("__Host-nullnull_session", owner.cookie);
    }
}
