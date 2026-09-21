package io.nullnull.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.ContractResponse;
import io.nullnull.testsupport.JsonShape;
import io.nullnull.testsupport.OwnedRows;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * #16: the seven TripMutationResult fixtures, and the two trip-detail fixtures they start from, describe
 * the shape the server really sends.
 *
 * <p>A TripMutationResult carries the whole trip, and which keys it holds depends on what the trip
 * holds: a DATE lock brings {@code date}, a TIME lock brings {@code startTime} and
 * {@code toleranceMinutes}. So a fixture cannot be compared with a response from just any trip. Each
 * case here builds, over HTTP, the trip trips/trip-detail-scheduled.json describes - 경복궁 with
 * MUST_VISIT and DATE locks, 인사동 with a TIME lock, 명동 scheduled from a candidate, five
 * candidates - makes the one change its fixture shows, and compares every level's keys. The getTrip cases
 * compare that trip itself with trip-detail-scheduled, and with trip-detail-reservation once 명동 holds the
 * RESERVATION lock instead.
 *
 * <p>Every place here is referenced to the KTO source the way the canonical ingest writes one, so the
 * fixtures carry the provider credit a trip screen draws (CMP-ATT-001). A test place without that reference
 * made every fixture say {@code sourceAttribution: null}, which is what the server sends only for a place
 * with no external source.
 */
@SpringBootTest(properties = "nullnull.catalog.public-enabled=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-040 BA-041 the TripMutationResult fixtures describe the shape the server really sends")
class TripMutationFixtureIT {

    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

    /** The source and the registry revision the canonical ingest references KTO places to (V012). */
    private static final String KTO = "KTO_KOR_SERVICE_2";
    private static final int KTO_REVISION = 4;

    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> trips = new ArrayList<>();
    private final List<UUID> places = new ArrayList<>();

    /** Only this class's rows, each named by an id it created (AGENTS.md rule 6). */
    @AfterEach
    void removeOnlyOwnRows() {
        // The trip takes its items, candidates and their sources, and constraints with it.
        for (UUID tripId : trips) {
            jdbc.update("DELETE FROM trips WHERE id = ?", tripId);
        }
        // With the external references hanging off them, which do not cascade.
        OwnedRows.remove(jdbc, "places", places);
    }

    @Test
    @DisplayName("BA-030 getTrip of that trip has trip-detail-scheduled's shape, items[].crowd aside (#105)")
    void getTripScheduled() throws Exception {
        assertTripShape(scheduled(false), "trips/trip-detail-scheduled.json");
    }

    @Test
    @DisplayName("BA-030 getTrip of that trip with a RESERVATION lock has trip-detail-reservation's shape, items[].crowd aside (#105)")
    void getTripReservation() throws Exception {
        assertTripShape(scheduled(true), "trips/trip-detail-reservation.json");
    }

    @Test
    @DisplayName("BA-040 addTripItem schedules a candidate onto an empty day")
    void addTripItem() throws Exception {
        Scheduled trip = scheduled();

        JsonNode body = body(send(trip, post(trip.items()), "\"4\"",
                "{\"placeId\":\"" + trip.seoulForest() + "\",\"candidateId\":\"" + trip.seoulForestCandidate()
                        + "\",\"date\":\"2026-10-06\",\"position\":0}")
                .andExpect(status().isCreated()));

        // The new item is the one the candidate was scheduled onto, on the day that was empty.
        assertThat(changed(body)).containsExactly(itemOn(body, trip.seoulForest()));
        assertShape("addTripItem", 201, body, "trips/mutation-add.json");
    }

    @Test
    @DisplayName("BA-040 updateTripItem moves an unlocked item's start time")
    void updateTripItem() throws Exception {
        Scheduled trip = scheduled();

        JsonNode body = body(mvc.perform(patch(trip.items() + "/" + trip.myeongdongItem())
                        .cookie(cookie(trip.owner()))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", trip.owner().csrf.token)
                        .header("If-Match", "\"4\"")
                        .contentType("application/merge-patch+json")
                        .content("{\"startTime\":\"15:00:00\"}"))
                .andExpect(status().isOk()));

        assertThat(changed(body)).containsExactly(trip.myeongdongItem().toString());
        assertShape("updateTripItem", 200, body, "trips/mutation-update.json");
    }

    @Test
    @DisplayName("BA-040 reorderTripItems moves an item to another day")
    void reorderTripItems() throws Exception {
        Scheduled trip = scheduled();

        JsonNode body = body(send(trip, post(trip.items() + "/reorder"), "\"4\"",
                "{\"items\":[{\"itemId\":\"" + trip.myeongdongItem()
                        + "\",\"date\":\"2026-10-07\",\"position\":0}]}")
                .andExpect(status().isOk()));

        assertThat(changed(body)).containsExactly(trip.myeongdongItem().toString());
        assertShape("reorderTripItems", 200, body, "trips/mutation-reorder.json");
    }

    @Test
    @DisplayName("BA-040 BA-042 replaceTripItem swaps a MUST_VISIT place once the lock is named, keeping its DATE lock")
    void replaceTripItem() throws Exception {
        Scheduled trip = scheduled();

        JsonNode body = body(send(trip, post(trip.items() + "/" + trip.gyeongbokgungItem() + "/replace"), "\"4\"",
                "{\"replacementPlaceId\":\"" + trip.yeonhui() + "\",\"releaseConstraints\":[\"MUST_VISIT\"]}")
                .andExpect(status().isOk()));

        // The item keeps its id and its slot; only the place changes (the fixture relies on both).
        assertThat(changed(body)).containsExactly(trip.gyeongbokgungItem().toString());
        assertThat(itemOn(body, trip.yeonhui())).isEqualTo(trip.gyeongbokgungItem().toString());
        assertShape("replaceTripItem", 200, body, "trips/mutation-replace.json");
    }

    @Test
    @DisplayName("BA-041 setTripItemConstraint adds a TIME lock to an unlocked item")
    void setTripItemConstraint() throws Exception {
        Scheduled trip = scheduled();

        JsonNode body = body(mvc.perform(put(trip.items() + "/" + trip.myeongdongItem() + "/constraints/TIME")
                        .cookie(cookie(trip.owner()))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", trip.owner().csrf.token)
                        .header("If-Match", "\"4\"")
                        .contentType("application/json")
                        .content("{\"type\":\"TIME\",\"locked\":true,\"startTime\":\"14:00:00\","
                                + "\"toleranceMinutes\":30}"))
                .andExpect(status().isOk()));

        assertThat(changed(body)).containsExactly(trip.myeongdongItem().toString());
        assertShape("setTripItemConstraint", 200, body, "trips/mutation-constraint-set.json");
    }

    @Test
    @DisplayName("BA-041 removeTripItemConstraint releases a TIME lock and leaves the others")
    void removeTripItemConstraint() throws Exception {
        Scheduled trip = scheduled();

        JsonNode body = body(mvc.perform(delete(trip.items() + "/" + trip.insadongItem() + "/constraints/TIME")
                        .cookie(cookie(trip.owner()))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", trip.owner().csrf.token)
                        .header("If-Match", "\"4\""))
                .andExpect(status().isOk()));

        assertThat(changed(body)).containsExactly(trip.insadongItem().toString());
        assertShape("removeTripItemConstraint", 200, body, "trips/mutation-constraint-remove.json");
    }

    @Test
    @DisplayName("BA-040 removeTripItem restores a candidate-backed item to its candidate")
    void removeTripItem() throws Exception {
        Scheduled trip = scheduled();

        JsonNode body = body(mvc.perform(delete(trip.items() + "/" + trip.myeongdongItem())
                        .param("disposition", "RESTORE_CANDIDATE")
                        .cookie(cookie(trip.owner()))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", trip.owner().csrf.token)
                        .header("If-Match", "\"4\""))
                .andExpect(status().isOk()));

        assertThat(changed(body)).containsExactly(trip.myeongdongItem().toString());
        assertShape("removeTripItem", 200, body, "trips/mutation-remove.json");
    }

    /** The ids a case needs of the trip trips/trip-detail-scheduled.json describes. */
    private record Scheduled(SessionService.Bootstrap owner, UUID tripId, UUID gyeongbokgungItem,
            UUID insadongItem, UUID myeongdongItem, UUID yeonhui, UUID seoulForest, UUID seoulForestCandidate) {

        String items() {
            return "/api/v1/trips/" + tripId + "/items";
        }
    }

    /**
     * Version 4 here, version 3 in the fixture: the fixture's trip reached its schedule through an
     * APPLY (run-applied), and this one through three adds. Only the version differs by that.
     */
    private Scheduled scheduled() throws Exception {
        return scheduled(false);
    }

    /**
     * With {@code reservation}, 명동 is instead the trip-detail-reservation item: 18:30 for 90 minutes,
     * with a DATE lock and a RESERVATION lock for that slot.
     */
    private Scheduled scheduled(boolean reservation) throws Exception {
        SessionService.Bootstrap owner = sessions.bootstrap(null, null, null);
        String created = mvc.perform(post("/api/v1/trips")
                        .cookie(cookie(owner))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "trip-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"title\":\"서울 가을 여행\",\"startDate\":\"2026-10-04\","
                                + "\"endDate\":\"2026-10-07\",\"timezone\":\"Asia/Seoul\","
                                + "\"planningLevel\":\"MUST_VISIT_ONLY\",\"interests\":["
                                + "{\"code\":\"FRIENDS\",\"weight\":3},{\"code\":\"FOOD\",\"weight\":5},"
                                + "{\"code\":\"CULTURE\",\"weight\":3}]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID tripId = UUID.fromString(created.replaceFirst("(?s)^.*?\"id\":\"([^\"]+)\".*$", "$1"));
        trips.add(tripId);

        UUID gyeongbokgung = place("경복궁");
        UUID insadong = place("인사동");
        UUID myeongdong = place("명동");
        UUID yeonhui = place("연희동 카페거리");
        UUID seoulForest = place("서울숲");
        // Five candidates, as the fixture counts: every status counts (JdbcTripStore.candidateCounts).
        candidate(owner, tripId, yeonhui);
        UUID seoulForestCandidate = candidate(owner, tripId, seoulForest);
        UUID myeongdongCandidate = candidate(owner, tripId, myeongdong);
        candidate(owner, tripId, place("후보 넷째"));
        candidate(owner, tripId, place("후보 다섯째"));

        Scheduled partial = new Scheduled(owner, tripId, null, null, null, yeonhui, seoulForest,
                seoulForestCandidate);
        UUID gyeongbokgungItem = added(send(partial, post(partial.items()), "\"1\"",
                "{\"placeId\":\"" + gyeongbokgung + "\",\"date\":\"2026-10-04\",\"position\":0,"
                        + "\"startTime\":\"09:30:00\",\"durationMinutes\":120,\"constraints\":["
                        + "{\"type\":\"MUST_VISIT\",\"locked\":true},"
                        + "{\"type\":\"DATE\",\"locked\":true,\"date\":\"2026-10-04\"}]}"));
        UUID insadongItem = added(send(partial, post(partial.items()), "\"2\"",
                "{\"placeId\":\"" + insadong + "\",\"date\":\"2026-10-04\",\"position\":1,"
                        + "\"startTime\":\"13:00:00\",\"durationMinutes\":90,\"constraints\":["
                        + "{\"type\":\"TIME\",\"locked\":true,\"startTime\":\"13:00:00\",\"toleranceMinutes\":30}]}"));
        String slot = reservation
                ? "\"startTime\":\"18:30:00\",\"durationMinutes\":90,\"constraints\":["
                        + "{\"type\":\"DATE\",\"locked\":true,\"date\":\"2026-10-05\"},"
                        + "{\"type\":\"RESERVATION\",\"locked\":true,\"date\":\"2026-10-05\","
                        + "\"startTime\":\"18:30:00\",\"endTime\":\"20:00:00\"}]"
                : "\"startTime\":\"14:00:00\",\"durationMinutes\":120";
        UUID myeongdongItem = added(send(partial, post(partial.items()), "\"3\"",
                "{\"placeId\":\"" + myeongdong + "\",\"candidateId\":\"" + myeongdongCandidate
                        + "\",\"date\":\"2026-10-05\",\"position\":0," + slot + "}"));
        return new Scheduled(owner, tripId, gyeongbokgungItem, insadongItem, myeongdongItem, yeonhui,
                seoulForest, seoulForestCandidate);
    }

    @Test
    @DisplayName("BA-030-T5 getTrip gives a place with an external source its provider's credit as the source registry records it")
    void aTripPlaceCarriesItsProvidersCredit() throws Exception {
        Scheduled trip = scheduled(false);
        JsonNode body = JSON.readTree(mvc.perform(get("/api/v1/trips/" + trip.tripId()).cookie(cookie(trip.owner())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        Map<String, Object> revision = jdbc.queryForMap("""
                SELECT canonical_contract->>'displayName' AS display_name,
                       canonical_contract->>'attributionTemplate' AS attribution,
                       canonical_contract->>'officialUrl' AS official_url,
                       canonical_contract->'license'->>'url' AS license_url,
                       canonical_contract->'license'->>'name' AS license_name
                  FROM source_registry_revisions WHERE source_code = ? AND version = ?
                """, KTO, KTO_REVISION);

        JsonNode credit = placeNamed(body, "경복궁").get("sourceAttribution");
        assertThat(credit.get("source").asString()).isEqualTo(KTO);
        assertThat(credit.get("sourceRegistryVersion").asInt()).isEqualTo(KTO_REVISION);
        assertThat(credit.get("attribution").asString()).as("the text CMP-ATT-001 requires on a KTO screen")
                .isEqualTo("출처: ⓒ한국관광공사").isEqualTo(revision.get("attribution"));
        assertThat(credit.get("sourceDisplayName").asString()).isEqualTo(revision.get("display_name"));
        assertThat(credit.get("officialUrl").asString()).isEqualTo(revision.get("official_url"));
        assertThat(credit.get("licenseUrl").asString()).isEqualTo(revision.get("license_url"));
        assertThat(credit.get("license").asString()).isEqualTo(revision.get("license_name"));
    }

    @Test
    @DisplayName("BA-030-T6 getTrip gives a place with no external source no credit, not a default one")
    void aPlaceWithoutASourceCarriesNoCredit() throws Exception {
        Scheduled trip = scheduled(false);
        UUID ownPlace = placeWithoutASource("외부 출처가 없는 장소");
        send(trip, post(trip.items()), "\"4\"",
                "{\"placeId\":\"" + ownPlace + "\",\"date\":\"2026-10-06\",\"position\":0}")
                .andExpect(status().isCreated());

        JsonNode body = JSON.readTree(mvc.perform(get("/api/v1/trips/" + trip.tripId()).cookie(cookie(trip.owner())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(placeNamed(body, "외부 출처가 없는 장소").get("sourceAttribution").isNull()).isTrue();
        // Not vacuous: the same response credits the referenced places around it.
        assertThat(placeNamed(body, "경복궁").get("sourceAttribution").isObject()).isTrue();
    }

    private static JsonNode placeNamed(JsonNode trip, String name) {
        List<JsonNode> found = new ArrayList<>();
        trip.get("days").forEach(day -> day.get("items").forEach(item -> {
            if (item.get("place").get("name").asString().equals(name)) {
                found.add(item.get("place"));
            }
        }));
        assertThat(found).as("items on %s", name).hasSize(1);
        return found.get(0);
    }

    private ResultActions send(Scheduled trip, MockHttpServletRequestBuilder request, String ifMatch, String body)
            throws Exception {
        return mvc.perform(request
                .cookie(cookie(trip.owner()))
                .header("Origin", "http://localhost:5173")
                .header("X-CSRF-Token", trip.owner().csrf.token)
                .header("If-Match", ifMatch)
                .header("Idempotency-Key", "fixture-" + UUID.randomUUID())
                .contentType("application/json")
                .content(body));
    }

    private static UUID added(ResultActions result) throws Exception {
        String body = result.andExpect(status().isCreated())
                .andExpect(jsonPath("$.changedItemIds.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(body.replaceFirst("(?s)^.*\"changedItemIds\":\\[\"([^\"]+)\".*$", "$1"));
    }

    private UUID candidate(SessionService.Bootstrap owner, UUID tripId, UUID placeId) throws Exception {
        return UUID.fromString(mvc.perform(post("/api/v1/trips/" + tripId + "/candidates")
                        .cookie(cookie(owner))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "cand-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"placeId\":\"" + placeId + "\",\"source\":{\"type\":\"SEARCH\"}}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s)^.*?\"id\":\"([^\"]+)\".*$", "$1"));
    }

    /** A KTO place: the place and its external reference, as JdbcCanonicalCatalogStore writes the pair. */
    private UUID place(String name) {
        UUID id = placeWithoutASource(name);
        jdbc.update("""
                INSERT INTO place_external_refs
                    (id, place_id, source_code, source_registry_version, external_id, external_type, verified_at)
                VALUES (?, ?, ?, ?, ?, 'KTO_CONTENT_TYPE:12', ?)
                """, UUID.randomUUID(), id, KTO, KTO_REVISION, "fixture-" + id, OffsetDateTime.now());
        return id;
    }

    private UUID placeWithoutASource(String name) {
        UUID id = UUID.randomUUID();
        places.add(id);
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO places (id, canonical_name, category_code, region_code, status,"
                + " created_at, updated_at) VALUES (?, ?, 'HS', '11', 'ACTIVE', ?, ?)", id, name, now, now);
        return id;
    }

    private static JsonNode body(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static List<String> changed(JsonNode body) {
        List<String> ids = new ArrayList<>();
        body.get("changedItemIds").forEach(id -> ids.add(id.asString()));
        return ids;
    }

    /** The id of the one item the trip holds for this place. */
    private static String itemOn(JsonNode body, UUID placeId) {
        List<String> ids = new ArrayList<>();
        body.get("trip").get("days").forEach(day -> day.get("items").forEach(item -> {
            if (item.get("place").get("id").asString().equals(placeId.toString())) {
                ids.add(item.get("id").asString());
            }
        }));
        assertThat(ids).hasSize(1);
        return ids.get(0);
    }

    /**
     * The trip-detail fixtures still hold items[].crowd: null, a key the server never sends. The contract
     * declares TripItem.crowd with no producer - the kind #105 is deciding - and an FE test reads the
     * fixture's null (trip-screen.test.tsx), so whether the server sends null or the fixtures drop the key
     * waits for that decision. The path is taken out by name, and only once the fixture is seen to hold
     * it, so this fails the day the fixture drops it and the exclusion has to go with it.
     */
    private void assertTripShape(Scheduled trip, String fixture) throws Exception {
        JsonNode body = JSON.readTree(mvc.perform(get("/api/v1/trips/" + trip.tripId()).cookie(cookie(trip.owner())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        ContractResponse.assertValid("getTrip", 200, body);
        JsonNode onDisk = JsonShape.fixture(fixture);
        java.util.SortedSet<String> expected = JsonShape.of(onDisk);
        assertThat(expected.remove("$.days[].items[].crowd")).as("%s still holds items[].crowd", fixture).isTrue();
        // textProvenance (BA-086) is left out of this shape comparison because only
        // places/place-detail.json carries it among the fixtures with a sourceAttribution. Remove
        // this exclusion once the representative fixtures carry textProvenance - until then the
        // FE's mocks for this response never see the field.
        assertThat(JsonShape.withoutField(body, "textProvenance")).isEqualTo(expected);
        assertEveryPlaceCredited(body);
        // Order is not shape, and the fixture follows the server's: interests by code, each item's locks
        // by type (JdbcTripStore's ORDER BY). This trip holds the fixture's places and locks, so the two
        // lists must be the same lists.
        assertThat(order(body)).isEqualTo(order(onDisk));
    }

    private static List<String> order(JsonNode trip) {
        List<String> order = new ArrayList<>();
        trip.get("interests").forEach(interest -> order.add(interest.get("code").asString()));
        trip.get("days").forEach(day -> day.get("items").forEach(item -> {
            StringBuilder locks = new StringBuilder(day.get("date").asString() + " " + item.get("place").get("name").asString());
            item.get("constraints").forEach(lock -> locks.append(' ').append(lock.get("type").asString()));
            order.add(locks.toString());
        }));
        return order;
    }

    private static void assertShape(String operationId, int status, JsonNode body, String fixture) {
        ContractResponse.assertValid(operationId, status, body);
        // The fixture Frontend mocks this mutation against has the keys the server sends, everywhere.
        // textProvenance (BA-086) is left out of this shape comparison because only
        // places/place-detail.json carries it among the fixtures with a sourceAttribution. Remove
        // this exclusion once the representative fixtures carry textProvenance - until then the
        // FE's mocks for this response never see the field.
        assertThat(JsonShape.withoutField(body, "textProvenance"))
                .isEqualTo(JsonShape.of(JsonShape.fixture(fixture)));
        assertEveryPlaceCredited(body);
    }

    /**
     * Every place this class seeds is referenced to KTO, so every summary in a response must carry the
     * credit. The shape comparison cannot see one place losing it: JsonShape merges array elements, so a
     * null next to a credited sibling leaves the key set unchanged.
     */
    private static void assertEveryPlaceCredited(JsonNode node) {
        if (node.isObject()) {
            if (node.has("id") && node.has("name") && node.has("sourceAttribution")) {
                assertThat(node.get("sourceAttribution").isObject())
                        .as("the credit of %s", node.get("name")).isTrue();
            }
            node.properties().forEach(field -> assertEveryPlaceCredited(field.getValue()));
        } else if (node.isArray()) {
            node.forEach(TripMutationFixtureIT::assertEveryPlaceCredited);
        }
    }

    private static Cookie cookie(SessionService.Bootstrap owner) {
        return new Cookie("__Host-nullnull_session", owner.cookie);
    }
}
