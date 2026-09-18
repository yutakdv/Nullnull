package io.nullnull.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.JsonShape;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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
 * #16: the seven TripMutationResult fixtures describe the shape the server really sends.
 *
 * <p>A TripMutationResult carries the whole trip, and which keys it holds depends on what the trip
 * holds: a DATE lock brings {@code date}, a TIME lock brings {@code startTime} and
 * {@code toleranceMinutes}. So a fixture cannot be compared with a response from just any trip. Each
 * case here builds, over HTTP, the trip trips/trip-detail-scheduled.json describes - 경복궁 with
 * MUST_VISIT and DATE locks, 인사동 with a TIME lock, 명동 scheduled from a candidate, five
 * candidates - makes the one change its fixture shows, and compares every level's keys.
 */
@SpringBootTest(properties = "nullnull.catalog.public-enabled=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-040 BA-041 the TripMutationResult fixtures describe the shape the server really sends")
class TripMutationFixtureIT {

    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

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
        for (UUID placeId : places) {
            jdbc.update("DELETE FROM places WHERE id = ?", placeId);
        }
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
        assertShape(body, "trips/mutation-add.json");
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
        assertShape(body, "trips/mutation-update.json");
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
        assertShape(body, "trips/mutation-reorder.json");
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
        assertShape(body, "trips/mutation-replace.json");
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
        assertShape(body, "trips/mutation-constraint-set.json");
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
        assertShape(body, "trips/mutation-constraint-remove.json");
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
        assertShape(body, "trips/mutation-remove.json");
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
        UUID myeongdongItem = added(send(partial, post(partial.items()), "\"3\"",
                "{\"placeId\":\"" + myeongdong + "\",\"candidateId\":\"" + myeongdongCandidate
                        + "\",\"date\":\"2026-10-05\",\"position\":0,\"startTime\":\"14:00:00\","
                        + "\"durationMinutes\":120}"));
        return new Scheduled(owner, tripId, gyeongbokgungItem, insadongItem, myeongdongItem, yeonhui,
                seoulForest, seoulForestCandidate);
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

    private UUID place(String name) {
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

    private static void assertShape(JsonNode body, String fixture) {
        // The fixture Frontend mocks this mutation against has the keys the server sends, everywhere.
        assertThat(JsonShape.of(body)).isEqualTo(JsonShape.of(JsonShape.fixture(fixture)));
    }

    private static Cookie cookie(SessionService.Bootstrap owner) {
        return new Cookie("__Host-nullnull_session", owner.cookie);
    }
}
