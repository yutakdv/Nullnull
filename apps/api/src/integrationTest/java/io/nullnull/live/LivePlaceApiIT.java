package io.nullnull.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code listLiveAreaPlaces} and {@code getLivePlace} over real HTTP.
 *
 * <p>These two routes are where {@code LiveCoverage} and {@code seoul_live_area_maps} get their first
 * caller. Both existed before this slice and neither was reachable: the rule that an area observation
 * is not a POI measurement was enforced by a class nothing invoked, and the table had no Java reader
 * at all.
 *
 * <p>The Live flag is ON here and the catalog's publication gate is open, because the clause under
 * test needs both: a place comes from the catalog projection and its reading from the Live one, and
 * with either closed the route answers before it decides anything.
 */
@SpringBootTest(properties = {
        "nullnull.capabilities.live=true",
        "nullnull.catalog.public-enabled=true",
        "NULLNULL_CURSOR_SECRET=test-live-place-cursor-secret-that-is-long-enough"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-091 Live 장소 목록과 상세")
class LivePlaceApiIT {

    private static final String SOURCE = "SEOUL_CITYDATA";
    private static final String ORIGIN = "http://localhost:5173";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Unique per run, so the search below finds this class's places and nobody else's. */
    private static final String RUN = "live-" + UUID.randomUUID();

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

    /**
     * Places first: {@code seoul_live_area_maps} references both places and live_areas without
     * cascade, so removing the areas while a mapping still points at them fails. OwnedRows follows
     * the mapping rows out from the place side.
     */
    @AfterEach
    void removeOnlyWhatThisTestCreated() {
        OwnedRows.remove(jdbc, "places", OwnedRows.appeared(jdbc, "places", placesBefore));
        OwnedRows.remove(jdbc, "crowd_snapshots", OwnedRows.appeared(jdbc, "crowd_snapshots", snapshotsBefore));
        OwnedRows.remove(jdbc, "snapshot_sets", OwnedRows.appeared(jdbc, "snapshot_sets", setsBefore));
        OwnedRows.remove(jdbc, "live_areas", OwnedRows.appeared(jdbc, "live_areas", areasBefore));
        OwnedRows.remove(jdbc, "collector_runs", OwnedRows.appeared(jdbc, "collector_runs", runsBefore));
    }

    @Test
    @DisplayName("BA-091-T6 searchPlaces 로 고른 canonical 장소에 대해 getLivePlace 가 coverage 를 답한다")
    void aPlaceChosenThroughSearchIsAnsweredWithItsLiveCoverage() throws Exception {
        SessionService.Bootstrap owner = owner();
        Instant now = clock.instant();
        UUID area = area("POI101", "광화문·덕수궁", now.minusSeconds(60), now.minusSeconds(55), "약간 붐빔");
        UUID covered = place("경복궁");
        UUID uncovered = place("아무도 매핑하지 않은 곳");
        map(covered, area, "AREA", "0.9000", false);

        // The flow the card names, end to end: the id the Live screen asks about is the one
        // searchPlaces handed over, never one this test made up. A route that answered for any UUID
        // would pass a test that skipped this step, and the thing being proven is that the catalog's
        // canonical id and the Live module's mapping key are the same id.
        JsonNode hits = JSON.readTree(search(owner, "{\"query\":\"" + RUN + "\",\"limit\":50}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        UUID chosen = idOfNamed(hits, "경복궁");
        assertThat(chosen).as("searchPlaces returns the canonical id this test mapped").isEqualTo(covered);

        JsonNode detail = JSON.readTree(livePlace(owner, chosen)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andReturn().getResponse().getContentAsString());

        assertThat(detail.get("place").get("id").asString()).isEqualTo(covered.toString());
        assertThat(detail.get("dataState").asString()).isEqualTo("LIVE");
        JsonNode crowd = detail.get("crowd");
        // The reading is the area's, and it arrives saying so: AREA rather than the stored DIRECT,
        // the mapping's confidence, and a scope that still names the region it measured.
        assertThat(crowd.get("ordinalLevel").asString()).isEqualTo("3");
        assertThat(crowd.get("provenance").get("mappingType").asString()).isEqualTo("AREA");
        assertThat(crowd.get("provenance").get("fallbackUsed").asBoolean()).isFalse();
        assertThat(crowd.get("provenance").get("confidence").decimalValue())
                .isEqualByComparingTo("0.9000");
        assertThat(crowd.get("provenance").get("scope").asString()).isEqualTo("LIVE_AREA");
        // Invariant 8: an area's number under a place is never a comparison.
        assertThat(crowd.get("provenance").get("comparisonEligible").asBoolean()).isFalse();
        // BA-024's projection, reused rather than reimplemented - so the two states this build can
        // emit stay the two BA-024-T7 pins, and Live does not invent a third.
        assertThat(detail.get("related").get("state").asString()).isEqualTo("UNKNOWN");
        assertThat(detail.get("related").get("reason").asString()).isEqualTo("SOURCE_DISABLED");

        // The other half, and it is what makes the above non-vacuous: a route that attached the
        // newest reading to whatever place it was asked about would pass every line so far.
        JsonNode absent = JSON.readTree(livePlace(owner, uncovered)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(absent.get("dataState").asString()).isEqualTo("UNAVAILABLE");
        // Null, not zero and not the neighbouring area's number. Absence is expressible here
        // precisely so that it is not filled.
        assertThat(absent.get("crowd").isNull()).isTrue();
    }

    @Test
    @DisplayName("BA-091-T5 Live 목록 응답은 cursor 를 발급하지 않는다")
    void theLiveListIssuesNoCursor() throws Exception {
        SessionService.Bootstrap owner = owner();
        Instant now = clock.instant();
        UUID area = area("POI102", "홍대 관광특구", now.minusSeconds(60), now.minusSeconds(55), "보통");
        UUID first = place("홍대 장소 하나");
        UUID second = place("홍대 장소 둘");
        map(first, area, "AREA", "0.9000", false);
        map(second, area, "AREA_FALLBACK", "0.4000", true);

        String body = listPlaces(owner, area)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andReturn().getResponse().getContentAsString();
        JsonNode page = JSON.readTree(body);

        // A TRAP, not a preference. There is no owner-bound cursor to check here because no Live
        // route issues one - the response is a bare array with no page envelope, which the contract
        // fixes and this reads back from a real response. The day someone adds paging to this list
        // these two lines go red, and that is the moment to decide the cursor's owner binding;
        // BA-022-T2 and BA-070-T1 own that layer and this card does not re-prove it.
        assertThat(page.isArray()).as("the Live list is an array, not a page envelope").isTrue();
        assertThat(body).doesNotContain("cursor", "nextCursor", "hasMore");
        assertThat(page).hasSize(2);

        // And it is populated, so the absence above is about a cursor rather than about an empty
        // answer: "contains no cursor" is cheap to pass by returning nothing at all.
        assertThat(mappingTypeOf(page, first)).isEqualTo("AREA");
        assertThat(mappingTypeOf(page, second)).isEqualTo("AREA_FALLBACK");
        // The fallback row says its reading is about the surrounding area, on the row itself.
        assertThat(crowdOf(page, second).get("provenance").get("fallbackUsed").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("BA-091 관측이 없는 구역의 장소는 매핑이 있어도 값을 받지 못한다")
    void aMappedPlaceInASilentAreaCarriesNothing() throws Exception {
        SessionService.Bootstrap owner = owner();
        // An area with no reading at all - upserted, never observed. A mapping says where a place
        // would read its value from; it does not say there is one to read, and an implementation
        // that attached something because a mapping exists gets exactly this case wrong.
        UUID silent = areas.upsertArea(SOURCE, new LiveAreaStore.AreaUpsert("POI103", "이태원 관광특구")).id();
        UUID mapped = place("조용한 구역의 장소");
        map(mapped, silent, "AREA", "0.9000", false);

        JsonNode page = JSON.readTree(listPlaces(owner, silent)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(page).hasSize(1);
        assertThat(page.get(0).get("place").get("id").asString()).isEqualTo(mapped.toString());
        // NONE, and NONE appears here only - the mapping row still says AREA, and this field answers
        // a different question: not "how is this place tied to an area" but "may it carry a value".
        assertThat(page.get(0).get("mappingType").asString()).isEqualTo("NONE");
        assertThat(page.get(0).get("fallbackUsed").asBoolean()).isFalse();
        assertThat(page.get(0).get("crowd").isNull()).isTrue();

        JsonNode detail = JSON.readTree(livePlace(owner, mapped)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(detail.get("dataState").asString()).isEqualTo("UNAVAILABLE");
        assertThat(detail.get("crowd").isNull()).isTrue();
    }

    @Test
    @DisplayName("BA-091 매핑이 없는 구역은 빈 목록이고, 없는 구역은 404 다")
    void anUnmappedAreaIsEmptyAndAnUnknownAreaIsNotFound() throws Exception {
        SessionService.Bootstrap owner = owner();
        UUID empty = areas.upsertArea(SOURCE, new LiveAreaStore.AreaUpsert("POI104", "잠실 관광특구")).id();

        // Two different absences, and collapsing them would blame the provider for a gap on our
        // side: the area exists and reports, what is missing is our own review work.
        JsonNode none = JSON.readTree(listPlaces(owner, empty)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(none.isArray()).isTrue();
        assertThat(none).isEmpty();

        listPlaces(owner, UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("BA-091 폐기된 id 로 물어도 canonical 장소의 coverage 가 나온다")
    void aRetiredAliasIsAnsweredForTheCanonicalPlaceItResolvesTo() throws Exception {
        SessionService.Bootstrap owner = owner();
        Instant now = clock.instant();
        UUID area = area("POI105", "강남역", now.minusSeconds(60), now.minusSeconds(55), "붐빔");
        UUID canonical = place("살아 있는 장소");
        map(canonical, area, "AREA", "0.9000", false);
        // A merge moves the duplicate's content to the canonical row before retiring it, and V010
        // refuses to deprecate a place that still holds any - so the fixture does the same.
        UUID retired = place("폐기된 장소");
        jdbc.update("DELETE FROM place_localizations WHERE place_id = ?", retired);
        jdbc.update("UPDATE places SET status = 'DEPRECATED', canonical_place_id = ? WHERE id = ?",
                canonical, retired);

        JsonNode detail = JSON.readTree(livePlace(owner, retired)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(detail.get("place").get("id").asString()).isEqualTo(canonical.toString());
        // The mapping was reviewed against the canonical row and the question arrived under the old
        // id; resolving one and looking up the other is what keeps the answer the same place's.
        assertThat(detail.get("dataState").asString()).isEqualTo("LIVE");
        assertThat(detail.get("crowd").get("provenance").get("mappingType").asString()).isEqualTo("AREA");
    }

    @Test
    @DisplayName("BA-091 한 canonical 장소를 가리키게 된 매핑 둘은 목록에 한 줄이다")
    void twoMappingsThatNowPointAtOneCanonicalPlaceAreOneRow() throws Exception {
        SessionService.Bootstrap owner = owner();
        Instant now = clock.instant();
        UUID area = area("POI106", "서울숲", now.minusSeconds(60), now.minusSeconds(55), "보통");
        UUID first = place("살아 있는 장소");
        UUID second = place("합쳐진 장소");
        // THE ORDER IS CHOSEN, NOT LEFT TO THE IDS. The query hands these over in place_id order and
        // the ids are random, so a fixture that mapped them by variable name would put the AREA row
        // first about half the time - and then "keep whichever came first" would look correct on
        // half the runs. The FALLBACK is given the lower id on purpose, so the direct mapping always
        // arrives second and can only win by being the stronger one.
        UUID fallback = first.toString().compareTo(second.toString()) < 0 ? first : second;
        UUID direct = fallback.equals(first) ? second : first;
        map(direct, area, "AREA", "0.9000", false);
        map(fallback, area, "AREA_FALLBACK", "0.4000", true);
        // The merge points the fallback's place at the direct one, so the canonical row is the
        // direct place and the retired row is the weaker claim about it.
        UUID canonical = direct;
        UUID retired = fallback;
        jdbc.update("DELETE FROM place_localizations WHERE place_id = ?", retired);
        jdbc.update("UPDATE places SET status = 'DEPRECATED', canonical_place_id = ? WHERE id = ?",
                canonical, retired);

        JsonNode page = JSON.readTree(listPlaces(owner, area)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        // ONE row. The internal contract forbids a repeated place for related places in the same
        // words, and the reason is the same here: the reader cannot tell which of the two rows is
        // the place, and the screen would show it twice with different terms.
        assertThat(page).hasSize(1);
        assertThat(page.get(0).get("place").get("id").asString()).isEqualTo(canonical.toString());
        // And it is the DIRECT mapping that describes it, not the fallback. Both rows cover - every
        // mapping in this list names the same area, so coverage is the same question for all of
        // them - so the only thing that can settle the merge is how each place is tied to that
        // area. "Keep the first" would have let the lower place_id decide which evidence grade the
        // reader sees, and the two fixtures above are deliberately seeded so that the AREA row is
        // NOT reliably the lower one: the ids are random.
        assertThat(page.get(0).get("mappingType").asString()).isEqualTo("AREA");
        assertThat(page.get(0).get("fallbackUsed").asBoolean()).isFalse();
        assertThat(crowdOf(page, canonical).get("provenance").get("mappingType").asString())
                .isEqualTo("AREA");
    }

    private ResultActions livePlace(SessionService.Bootstrap owner, UUID placeId) throws Exception {
        return mvc.perform(get("/api/v1/live/places/{id}", placeId).cookie(cookie(owner)));
    }

    private ResultActions listPlaces(SessionService.Bootstrap owner, UUID areaId) throws Exception {
        return mvc.perform(get("/api/v1/live/areas/{id}/places", areaId).cookie(cookie(owner)));
    }

    private ResultActions search(SessionService.Bootstrap owner, String body) throws Exception {
        return mvc.perform(post("/api/v1/places/search").cookie(cookie(owner))
                .header("Origin", ORIGIN).contentType("application/json").content(body));
    }

    private SessionService.Bootstrap owner() {
        return sessions.bootstrap(null, "ko-KR", "Asia/Seoul");
    }

    private static Cookie cookie(SessionService.Bootstrap owner) {
        return new Cookie("__Host-nullnull_session", owner.cookie);
    }

    private static UUID idOfNamed(JsonNode searchPage, String suffix) {
        for (JsonNode item : searchPage.get("items")) {
            if (item.get("name").asString().endsWith(suffix)) {
                return UUID.fromString(item.get("id").asString());
            }
        }
        throw new AssertionError("searchPlaces did not return a place named ..." + suffix);
    }

    private static String mappingTypeOf(JsonNode page, UUID placeId) {
        return rowOf(page, placeId).get("mappingType").asString();
    }

    private static JsonNode crowdOf(JsonNode page, UUID placeId) {
        return rowOf(page, placeId).get("crowd");
    }

    private static JsonNode rowOf(JsonNode page, UUID placeId) {
        for (JsonNode row : page) {
            if (row.get("place").get("id").asString().equals(placeId.toString())) {
                return row;
            }
        }
        throw new AssertionError("place not on the page: " + placeId);
    }

    /** One area with one stored reading, the same way {@code LiveAreaReadIT} builds them. */
    private UUID area(String externalId, String name, Instant observed, Instant fetched, String step) {
        UUID areaId = areas.upsertArea(SOURCE, new LiveAreaStore.AreaUpsert(externalId, name)).id();
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO collector_runs
                    (id, source_code, status, trigger_type, records_received, records_accepted,
                     records_rejected, schema_version, started_at, finished_at)
                VALUES (?, ?, 'COMPLETED', 'SCHEDULED', 1, 1, 0, 'seoul-citydata-v8.5', ?, ?)
                """, runId, SOURCE, Timestamp.from(fetched), Timestamp.from(fetched));
        snapshots.save(SeoulLiveSnapshotStore.Reading.of(UUID.randomUUID(), UUID.randomUUID(), runId, 2L,
                areaId, observed, fetched, 300L, SeoulCongestionStage.of(step)));
        return areaId;
    }

    /** The name carries this run's token so searchPlaces finds this class's rows and nobody else's. */
    private UUID place(String name) {
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.update("""
                INSERT INTO places
                    (id, canonical_name, category_code, latitude, longitude, region_code, status,
                     created_at, updated_at)
                VALUES (?, ?, 'A0201', 37.579617, 126.977041, '11', 'ACTIVE', ?, ?)
                """, id, RUN + " " + name, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO place_localizations (id, place_id, locale, name, address, updated_at)
                VALUES (?, ?, 'ko-KR', ?, '서울시 어딘가', ?)
                """, UUID.randomUUID(), id, RUN + " " + name, Timestamp.from(now));
        return id;
    }

    private void map(UUID placeId, UUID areaId, String mappingType, String confidence, boolean fallbackUsed) {
        jdbc.update("""
                INSERT INTO seoul_live_area_maps
                    (id, place_id, live_area_id, mapping_type, confidence, fallback_used, verified_at)
                VALUES (?, ?, ?, ?, ?::numeric, ?, ?)
                """, UUID.randomUUID(), placeId, areaId, mappingType, confidence, fallbackUsed,
                Timestamp.from(clock.instant()));
    }
}
