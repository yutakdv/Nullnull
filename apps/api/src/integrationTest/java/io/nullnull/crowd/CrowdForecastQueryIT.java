package io.nullnull.crowd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.ContractResponse;
import io.nullnull.testsupport.JsonShape;
import io.nullnull.testsupport.MutableClock;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * queryPlaceCrowdForecasts (#105): the single route's answer for many places, in the request's order.
 *
 * <p>Same properties as CrowdForecastApiIT and the same pinned clock (its Time bean). Not the same
 * Spring context: Time is nested there and only imported here, and build.gradle.kts caps the context
 * cache at one, so this class starts its own.
 *
 * <p>The single route is the reference everything here is compared with - not a re-derivation of what
 * it should say - because "the batch answers what getPlaceCrowdForecast answers" is the promise.
 */
@SpringBootTest(properties = {
        "nullnull.catalog.public-enabled=true",
        "NULLNULL_CURSOR_SECRET=test-catalog-cursor-secret-that-is-long-enough"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class, CrowdForecastApiIT.Time.class})
@DisplayName("BA-023 batch crowd forecast query")
class CrowdForecastQueryIT {

    private static final String FORECAST_SOURCE = "KTO_CONCENTRATION_FORECAST";
    private static final String PLACE_SOURCE = "KTO_KOR_SERVICE_2";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired MockMvc mvc;
    @Autowired SessionService sessions;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;

    private final List<UUID> snapshotSets = new ArrayList<>();
    private final List<UUID> collectorRuns = new ArrayList<>();
    private final List<UUID> externalReferences = new ArrayList<>();
    private final List<UUID> localizations = new ArrayList<>();
    private final List<UUID> deprecatedPlaces = new ArrayList<>();
    private final List<UUID> places = new ArrayList<>();

    @AfterEach
    void removeOnlyOwnFixtures() {
        for (UUID snapshotSet : snapshotSets) {
            jdbc.update("DELETE FROM crowd_snapshots WHERE snapshot_set_id = ?", snapshotSet);
        }
        for (UUID snapshotSet : snapshotSets) {
            jdbc.update("DELETE FROM snapshot_sets WHERE id = ?", snapshotSet);
        }
        for (UUID run : collectorRuns) {
            jdbc.update("DELETE FROM collector_runs WHERE id = ?", run);
        }
        for (UUID reference : externalReferences) {
            jdbc.update("DELETE FROM place_external_refs WHERE id = ?", reference);
        }
        for (UUID localization : localizations) {
            jdbc.update("DELETE FROM place_localizations WHERE id = ?", localization);
        }
        // A deprecated row points at its canonical one and places does not cascade, so it goes first.
        for (UUID place : deprecatedPlaces) {
            jdbc.update("DELETE FROM places WHERE id = ?", place);
        }
        for (UUID place : places) {
            jdbc.update("DELETE FROM places WHERE id = ?", place);
        }
    }

    /**
     * The request order is built to be neither the ids' order, nor the values', nor the states' - in
     * either direction - so a server that sorted by any of them answers a different list.
     */
    @Test
    @DisplayName("BA-023-T7 the batch has one item per requested id, and item i answers id i")
    void itemIAnswersRequestedIdI() throws Exception {
        SessionService.Bootstrap owner = owner();
        List<UUID> sorted = new ArrayList<>(List.of(activePlace("T7 a"), activePlace("T7 b"),
                activePlace("T7 c"), activePlace("T7 d")));
        // PostgreSQL orders uuid by its bytes, which is the order of the lowercase text form.
        sorted.sort((left, right) -> left.toString().compareTo(right.toString()));
        UUID forty = sorted.get(2);
        UUID ten = sorted.get(0);
        UUID thirty = sorted.get(3);
        UUID uncovered = sorted.get(1);
        Instant target = clock.instant().plus(Duration.ofDays(1));
        freshSet(forty, List.of(target), 40);
        staleSet(ten, List.of(target), 10);
        freshSet(thirty, List.of(target), 30);
        UUID mergedIntoThirty = deprecatedPlace(thirty);
        UUID unknown = UUID.randomUUID();

        MvcResult result = query(owner, List.of(forty, ten, mergedIntoThirty, uncovered, unknown), target, target)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andReturn();

        JsonNode items = JSON.readTree(result.getResponse().getContentAsString()).get("items");
        assertThat(items).hasSize(5);
        assertThat(ids(items)).containsExactly(forty.toString(), ten.toString(), thirty.toString(),
                uncovered.toString(), unknown.toString());
        assertThat(states(items)).containsExactly("FORECAST", "STALE", "FORECAST", "UNAVAILABLE", "UNAVAILABLE");
        assertThat(items.get(0).get("points").get(0).get("value").asInt()).isEqualTo(40);
        assertThat(items.get(1).get("points").get(0).get("value").asInt()).isEqualTo(10);
        assertThat(items.get(2).get("points").get(0).get("value").asInt()).isEqualTo(30);
        assertThat(items.get(3).get("unavailableReason").asText()).isEqualTo("NO_COVERAGE");
        assertThat(items.get(4).get("unavailableReason").asText()).isEqualTo("PLACE_UNAVAILABLE");
        ContractResponse.assertValid("queryPlaceCrowdForecasts", 200, result.getResponse().getContentAsString());
    }

    /**
     * Each readable item is compared with getPlaceCrowdForecast's whole response for the same id and
     * window - the set it chose, its points, their provenance. The fixtures cover what choosing a set
     * depends on: a newer fresh set beside an older one, a stale set only, a fresh set whose points all
     * fall outside the window beside a stale one inside it, two sets fetched at the same instant (the
     * id decides), an older fresh set beside a newer stale one (fresh wins, so the two reads cannot be
     * one "newest" read), one set shared by two places beside a newer set for one of them (the pair
     * match, not two id lists), nothing at all, and a merged id.
     */
    @Test
    @DisplayName("BA-023-T8 each batch item equals getPlaceCrowdForecast for the same place and window")
    void eachItemEqualsTheSingleRoute() throws Exception {
        SessionService.Bootstrap owner = owner();
        Instant from = clock.instant().plus(Duration.ofDays(1));
        Instant to = clock.instant().plus(Duration.ofDays(3));
        List<Instant> targets = List.of(from, clock.instant().plus(Duration.ofDays(2)));

        UUID newer = activePlace("T8 newer set");
        insertForecastSet(UUID.randomUUID(), newer, "t8-older", clock.instant().minus(Duration.ofHours(2)),
                clock.instant().plus(Duration.ofHours(20)), targets, BigDecimal.valueOf(21));
        insertForecastSet(UUID.randomUUID(), newer, "t8-newer", clock.instant().minus(Duration.ofHours(1)),
                clock.instant().plus(Duration.ofHours(21)), targets, BigDecimal.valueOf(22));

        UUID staleOnly = activePlace("T8 stale only");
        staleSet(staleOnly, targets, 23);

        UUID freshOutsideWindow = activePlace("T8 fresh outside the window");
        freshSet(freshOutsideWindow, List.of(to.plus(Duration.ofDays(1))), 24);
        staleSet(freshOutsideWindow, List.of(from), 25);

        UUID tie = activePlace("T8 fetched at the same instant");
        Instant sameFetch = clock.instant().minus(Duration.ofMinutes(30));
        List<UUID> tiedSets = new ArrayList<>(List.of(UUID.randomUUID(), UUID.randomUUID()));
        tiedSets.sort((left, right) -> left.toString().compareTo(right.toString()));
        // The smaller id is written first, so an order that ignores the id tie-break tends to meet it
        // first; the single route's ORDER BY takes the larger.
        insertForecastSet(tiedSets.get(0), tie, "t8-tie-small", sameFetch, clock.instant().plus(Duration.ofHours(23)),
                targets, BigDecimal.valueOf(26));
        insertForecastSet(tiedSets.get(1), tie, "t8-tie-large", sameFetch, clock.instant().plus(Duration.ofHours(23)),
                targets, BigDecimal.valueOf(27));

        UUID freshBeatsNewerStale = activePlace("T8 an older fresh set beside a newer stale one");
        insertForecastSet(UUID.randomUUID(), freshBeatsNewerStale, "t8-fresh", clock.instant().minus(Duration.ofHours(3)),
                clock.instant().plus(Duration.ofHours(21)), targets, BigDecimal.valueOf(28));
        insertForecastSet(UUID.randomUUID(), freshBeatsNewerStale, "t8-stale-newer",
                clock.instant().minus(Duration.ofMinutes(30)), clock.instant().minus(Duration.ofMinutes(1)), targets,
                BigDecimal.valueOf(29));

        // One set holding points for two requested places, and a newer set for the second only: the
        // second must answer from its own newer set, never with the shared set's points mixed in.
        UUID sharesAnOlderSet = activePlace("T8 answered by a set it shares");
        UUID hasItsOwnNewerSet = activePlace("T8 answered by its own newer set");
        insertForecastSet(UUID.randomUUID(), List.of(sharesAnOlderSet, hasItsOwnNewerSet), "t8-shared",
                clock.instant().minus(Duration.ofHours(2)), clock.instant().plus(Duration.ofHours(20)), targets,
                BigDecimal.valueOf(30));
        insertForecastSet(UUID.randomUUID(), hasItsOwnNewerSet, "t8-own", clock.instant().minus(Duration.ofHours(1)),
                clock.instant().plus(Duration.ofHours(21)), targets, BigDecimal.valueOf(31));

        UUID uncovered = activePlace("T8 no coverage");
        UUID merged = deprecatedPlace(newer);

        List<UUID> requested = List.of(newer, staleOnly, freshOutsideWindow, tie, freshBeatsNewerStale,
                sharesAnOlderSet, hasItsOwnNewerSet, uncovered, merged);
        MvcResult batch = query(owner, requested, from, to).andExpect(status().isOk()).andReturn();
        JsonNode items = JSON.readTree(batch.getResponse().getContentAsString()).get("items");
        assertThat(items).hasSize(requested.size());
        for (int at = 0; at < requested.size(); at++) {
            JsonNode single = JSON.readTree(single(owner, requested.get(at), from, to)
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(items.get(at)).as("item %s answers %s like getPlaceCrowdForecast", at, requested.get(at))
                    .isEqualTo(single);
        }
        // Not vacuous: the cases above really reach each branch the single route has.
        assertThat(states(items)).containsExactly("FORECAST", "STALE", "STALE", "FORECAST", "FORECAST",
                "FORECAST", "FORECAST", "UNAVAILABLE", "FORECAST");
        assertThat(items.get(0).get("points").get(0).get("value").asInt()).isEqualTo(22);
        assertThat(items.get(2).get("points").get(0).get("value").asInt()).isEqualTo(25);
        assertThat(items.get(3).get("points").get(0).get("value").asInt()).isEqualTo(27);
        assertThat(items.get(4).get("points").get(0).get("value").asInt()).isEqualTo(28);
        assertThat(items.get(5).get("points")).extracting(point -> point.get("value").asInt()).containsOnly(30);
        assertThat(items.get(6).get("points")).extracting(point -> point.get("value").asInt())
                .containsExactly(31, 31);

        // The fixture Frontend mocks against has the keys the server sends, everywhere (#16).
        ContractResponse.assertValid("queryPlaceCrowdForecasts", 200, batch.getResponse().getContentAsString());
        assertThat(JsonShape.of(withUnreadablePlace(owner, batch, from, to)))
                .isEqualTo(JsonShape.of(JsonShape.fixture("crowd/forecast-query.json")));
    }

    @Test
    @DisplayName("BA-023-T9 an id that cannot be read answers its own item UNAVAILABLE and leaves the others as they are")
    void anUnreadablePlaceAnswersItsOwnItem() throws Exception {
        SessionService.Bootstrap owner = owner();
        Instant target = clock.instant().plus(Duration.ofDays(1));
        UUID readable = activePlace("T9 readable");
        freshSet(readable, List.of(target), 33);
        UUID withoutCoordinates = placeWithoutCoordinates("T9 without coordinates");
        // A forecast of its own, so it is the missing coordinates and not a missing set that decides.
        freshSet(withoutCoordinates, List.of(target), 34);
        UUID unknown = UUID.randomUUID();

        JsonNode alone = JSON.readTree(query(owner, List.of(readable), target, target)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("items").get(0);
        MvcResult mixed = query(owner, List.of(unknown, withoutCoordinates, readable), target, target)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andReturn();
        JsonNode items = JSON.readTree(mixed.getResponse().getContentAsString()).get("items");

        for (int at : new int[] {0, 1}) {
            UUID requested = at == 0 ? unknown : withoutCoordinates;
            assertThat(items.get(at).get("placeId").asText()).isEqualTo(requested.toString());
            assertThat(items.get(at).get("state").asText()).isEqualTo("UNAVAILABLE");
            assertThat(items.get(at).get("points")).isEmpty();
            assertThat(items.get(at).get("unavailableReason").asText()).isEqualTo("PLACE_UNAVAILABLE");
        }
        assertThat(items.get(2)).isEqualTo(alone);
        assertThat(alone.get("state").asText()).isEqualTo("FORECAST");
        // The single route refuses the same two ids as a whole request; the batch answers them in place.
        single(owner, unknown, target, target).andExpect(status().isNotFound());
        single(owner, withoutCoordinates, target, target).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("BA-023-T10 fifty-one placeIds are refused with 422 VALIDATION_FAILED")
    void fiftyOnePlaceIdsAreRefused() throws Exception {
        SessionService.Bootstrap owner = owner();
        Instant target = clock.instant().plus(Duration.ofDays(1));
        List<UUID> fiftyOne = IntStream.range(0, 51).mapToObj(at -> UUID.randomUUID()).toList();
        query(owner, fiftyOne, target, target)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("placeIds"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("Size"));
        // Fifty is the limit, not the first refused size.
        query(owner, fiftyOne.subList(0, 50), target, target).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(50));
    }

    @Test
    @DisplayName("BA-023-T11 an empty placeIds is refused with 422 VALIDATION_FAILED")
    void emptyPlaceIdsAreRefused() throws Exception {
        SessionService.Bootstrap owner = owner();
        Instant target = clock.instant().plus(Duration.ofDays(1));
        query(owner, List.of(), target, target)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("placeIds"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("Size"));
    }

    @Test
    @DisplayName("BA-023-T12 a repeated place id is refused with 422 VALIDATION_FAILED")
    void repeatedPlaceIdsAreRefused() throws Exception {
        SessionService.Bootstrap owner = owner();
        Instant target = clock.instant().plus(Duration.ofDays(1));
        UUID place = UUID.randomUUID();
        query(owner, List.of(place, UUID.randomUUID(), place), target, target)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("placeIds"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("Duplicate"));
    }

    /*
     * BA-023-T16..T19, one absence each: four separate checks in forecastMany's validation, any one of
     * which can regress on its own. A body record binds a missing field as null, and a null that got
     * past validation would have been a NullPointerException - in the range check for from and to, in
     * the size check or List.copyOf for placeIds - which the catch-all answers 500.
     */

    @Test
    @DisplayName("BA-023-T16 a request without placeIds is refused with 422 VALIDATION_FAILED, not 500")
    void aMissingPlaceIdsIsRefusedNotFailed() throws Exception {
        String target = clock.instant().plus(Duration.ofDays(1)).toString();
        expectNotNull("{\"from\":\"" + target + "\",\"to\":\"" + target + "\"}", "placeIds");
    }

    @Test
    @DisplayName("BA-023-T17 a placeIds holding null is refused with 422 VALIDATION_FAILED, not 500")
    void aNullPlaceIdIsRefusedNotFailed() throws Exception {
        String target = clock.instant().plus(Duration.ofDays(1)).toString();
        expectNotNull("{\"placeIds\":[\"" + UUID.randomUUID() + "\",null],\"from\":\"" + target + "\",\"to\":\""
                + target + "\"}", "placeIds");
    }

    @Test
    @DisplayName("BA-023-T18 a request without from is refused with 422 VALIDATION_FAILED, not 500")
    void aMissingFromIsRefusedNotFailed() throws Exception {
        String target = clock.instant().plus(Duration.ofDays(1)).toString();
        expectNotNull("{\"placeIds\":[\"" + UUID.randomUUID() + "\"],\"to\":\"" + target + "\"}", "from");
    }

    @Test
    @DisplayName("BA-023-T19 a request without to is refused with 422 VALIDATION_FAILED, not 500")
    void aMissingToIsRefusedNotFailed() throws Exception {
        String target = clock.instant().plus(Duration.ofDays(1)).toString();
        expectNotNull("{\"placeIds\":[\"" + UUID.randomUUID() + "\"],\"from\":\"" + target + "\"}", "to");
    }

    private void expectNotNull(String body, String field) throws Exception {
        queryRaw(owner(), body)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value(field))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("NotNull"));
    }

    /**
     * Jackson reads "+300000-01-01T00:00:00Z" (and a bare number, as epoch seconds) into a valid
     * Instant, and a zero-length window passes the relative range check. PostgreSQL's timestamptz ends
     * in 294276 AD, so without an absolute bound the first query to bind it fails and the catch-all
     * answers 500 - but only when some requested id is readable, since otherwise nothing is bound.
     * The place here is readable on purpose.
     */
    @Test
    @DisplayName("BA-023-T20 a from or to outside the years 0001-9999 is refused with 422 VALIDATION_FAILED, not 500")
    void aWindowOutsideTheCalendarIsRefusedNotFailed() throws Exception {
        SessionService.Bootstrap owner = owner();
        UUID readable = activePlace("T20 readable");
        for (String instant : List.of("+300000-01-01T00:00:00Z", "-0001-12-31T00:00:00Z")) {
            queryRaw(owner, "{\"placeIds\":[\"" + readable + "\"],\"from\":\"" + instant + "\",\"to\":\"" + instant
                    + "\"}")
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("to"))
                    .andExpect(jsonPath("$.fieldErrors[0].code").value("INVALID_RANGE"));
        }
    }

    /** The bound lives in CrowdForecastProperties, which the single route shares. */
    @Test
    @DisplayName("the single route refuses the same out-of-calendar window with 422 rather than 500")
    void theSingleRouteRefusesTheSameWindow() throws Exception {
        SessionService.Bootstrap owner = owner();
        UUID readable = activePlace("T20 single route");
        mvc.perform(get("/api/v1/places/{placeId}/crowd-forecast", readable).cookie(cookie(owner))
                        .param("from", "+300000-01-01T00:00:00Z").param("to", "+300000-01-01T00:00:00Z"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("a range the single route refuses is refused for the whole batch, and an unknown field is 400")
    void theSingleRoutesRangeAndTheBodyShapeStillApply() throws Exception {
        SessionService.Bootstrap owner = owner();
        Instant from = clock.instant();
        query(owner, List.of(UUID.randomUUID()), from, from.plus(Duration.ofDays(31)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("to"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("INVALID_RANGE"));
        queryRaw(owner, "{\"placeIds\":[\"" + UUID.randomUUID() + "\"],\"from\":\"" + from + "\",\"to\":\"" + from
                + "\",\"locale\":\"ko-KR\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    /**
     * The batch response of T8 with one unreadable id added, so every face the fixture shows - a
     * forecast, a stale fallback, NO_COVERAGE and PLACE_UNAVAILABLE - is in the compared shape.
     */
    private JsonNode withUnreadablePlace(SessionService.Bootstrap owner, MvcResult batch, Instant from, Instant to)
            throws Exception {
        ObjectNode response = (ObjectNode) JSON.readTree(batch.getResponse().getContentAsString());
        JsonNode unreadable = JSON.readTree(query(owner, List.of(UUID.randomUUID()), from, to)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("items").get(0);
        ((ArrayNode) response.get("items")).add(unreadable);
        return response;
    }

    private ResultActions query(SessionService.Bootstrap owner, List<UUID> placeIds, Instant from, Instant to)
            throws Exception {
        ObjectNode body = JSON.createObjectNode();
        ArrayNode ids = body.putArray("placeIds");
        placeIds.forEach(id -> ids.add(id.toString()));
        body.put("from", from.toString());
        body.put("to", to.toString());
        return queryRaw(owner, JSON.writeValueAsString(body));
    }

    private ResultActions queryRaw(SessionService.Bootstrap owner, String body) throws Exception {
        return mvc.perform(post("/api/v1/places/crowd-forecasts/query").cookie(cookie(owner))
                .header("Origin", "http://localhost:5173")
                .contentType("application/json").content(body));
    }

    private ResultActions single(SessionService.Bootstrap owner, UUID place, Instant from, Instant to)
            throws Exception {
        return mvc.perform(get("/api/v1/places/{placeId}/crowd-forecast", place).cookie(cookie(owner))
                .param("from", from.toString()).param("to", to.toString()));
    }

    private static List<String> ids(JsonNode items) {
        List<String> ids = new ArrayList<>();
        items.forEach(item -> ids.add(item.get("placeId").asText()));
        return ids;
    }

    private static List<String> states(JsonNode items) {
        List<String> states = new ArrayList<>();
        items.forEach(item -> states.add(item.get("state").asText()));
        return states;
    }

    private SessionService.Bootstrap owner() {
        return sessions.bootstrap(null, "ko-KR", "Asia/Seoul");
    }

    private void freshSet(UUID place, List<Instant> targets, int value) {
        insertForecastSet(UUID.randomUUID(), place, "fresh-" + UUID.randomUUID(),
                clock.instant().minus(Duration.ofMinutes(5)), clock.instant().plus(Duration.ofHours(23)), targets,
                BigDecimal.valueOf(value));
    }

    private void staleSet(UUID place, List<Instant> targets, int value) {
        insertForecastSet(UUID.randomUUID(), place, "stale-" + UUID.randomUUID(),
                clock.instant().minus(Duration.ofDays(2)), clock.instant().minus(Duration.ofHours(1)), targets,
                BigDecimal.valueOf(value));
    }

    private UUID activePlace(String name) {
        return place(name, new BigDecimal("37.566535"), new BigDecimal("126.978001"));
    }

    private UUID placeWithoutCoordinates(String name) {
        return place(name, null, null);
    }

    private UUID place(String name, BigDecimal latitude, BigDecimal longitude) {
        UUID place = UUID.randomUUID();
        places.add(place);
        jdbc.update("""
                INSERT INTO places
                    (id, canonical_name, category_code, latitude, longitude, region_code, status, created_at, updated_at)
                VALUES (?, ?, 'A0101', ?, ?, '1', 'ACTIVE', ?, ?)
                """, place, name, latitude, longitude, timestamp(), timestamp());
        UUID localization = UUID.randomUUID();
        localizations.add(localization);
        jdbc.update("""
                INSERT INTO place_localizations (id, place_id, locale, name, address, updated_at)
                VALUES (?, ?, 'ko-KR', ?, '서울시 종로구', ?)
                """, localization, place, name, timestamp());
        UUID reference = UUID.randomUUID();
        externalReferences.add(reference);
        jdbc.update("""
                INSERT INTO place_external_refs
                    (id, place_id, source_code, source_registry_version, external_id, external_type, verified_at)
                VALUES (?, ?, ?, ?, ?, 'KTO_CONTENT_TYPE:12', ?)
                """, reference, place, PLACE_SOURCE, sourceVersion(PLACE_SOURCE), "fixture-" + place, timestamp());
        return place;
    }

    /** An old id merged into {@code canonical}; it has no coordinates of its own, as a merged row need not. */
    private UUID deprecatedPlace(UUID canonical) {
        UUID place = UUID.randomUUID();
        deprecatedPlaces.add(place);
        jdbc.update("""
                INSERT INTO places
                    (id, canonical_place_id, canonical_name, category_code, latitude, longitude, region_code,
                     status, created_at, updated_at)
                VALUES (?, ?, 'merged fixture', 'A0101', NULL, NULL, '1', 'DEPRECATED', ?, ?)
                """, place, canonical, timestamp(), timestamp());
        return place;
    }

    private void insertForecastSet(UUID set, UUID place, String issue, Instant fetchedAt, Instant staleAt,
            List<Instant> targets, BigDecimal value) {
        insertForecastSet(set, List.of(place), issue, fetchedAt, staleAt, targets, value);
    }

    /** One set holding points for every given place - legal, since snapshot_sets has no place column. */
    private void insertForecastSet(UUID set, List<UUID> heldPlaces, String issue, Instant fetchedAt,
            Instant staleAt, List<Instant> targets, BigDecimal value) {
        long sourceVersion = sourceVersion(FORECAST_SOURCE);
        UUID run = UUID.randomUUID();
        collectorRuns.add(run);
        jdbc.update("""
                INSERT INTO collector_runs
                    (id, source_code, status, trigger_type, records_received, records_accepted, records_rejected,
                     schema_version, started_at, finished_at)
                VALUES (?, ?, 'COMPLETED', 'READ_THROUGH', ?, ?, 0, 'kto-tats-cnctr-rate-v4.1', ?, ?)
                """, run, FORECAST_SOURCE, heldPlaces.size() * targets.size(), heldPlaces.size() * targets.size(),
                Timestamp.from(fetchedAt), Timestamp.from(fetchedAt));
        snapshotSets.add(set);
        jdbc.update("""
                INSERT INTO snapshot_sets
                    (id, source_code, source_registry_version, collector_run_id, source_state, forecast_issue_id,
                     comparison_group_id, observed_at, fetched_at, stale_at, normalization_version, created_at)
                VALUES (?, ?, ?, ?, 'FORECAST', ?, ?, NULL, ?, ?, 'kto-tats-cnctr-rate-v4.1', ?)
                """, set, FORECAST_SOURCE, sourceVersion, run, issue, issue, Timestamp.from(fetchedAt),
                Timestamp.from(staleAt), Timestamp.from(fetchedAt));
        for (UUID place : heldPlaces) {
            for (Instant target : targets) {
                jdbc.update("""
                        INSERT INTO crowd_snapshots
                            (id, snapshot_set_id, source_code, source_registry_version, place_id, source_state,
                             observed_at, target_at, fetched_at, stale_at, metric_code, value, unit, ordinal_level,
                             confidence, quality_flags, forecast_issue_id, comparison_group_id, normalization_version,
                             observed_at_skew_seconds, scope, scope_label, mapping_type, fallback_used, created_at)
                        VALUES (?, ?, ?, ?, ?, 'FORECAST', NULL, ?, ?, ?, 'KTO_RELATIVE_CONCENTRATION_INDEX', ?,
                                'relative-index', NULL, NULL, '[]'::jsonb, ?, ?, 'kto-tats-cnctr-rate-v4.1', NULL,
                                'PLACE', 'C4 fixture place', 'DIRECT', false, ?)
                        """, UUID.randomUUID(), set, FORECAST_SOURCE, sourceVersion, place, Timestamp.from(target),
                        Timestamp.from(fetchedAt), Timestamp.from(staleAt), value, issue, issue,
                        Timestamp.from(fetchedAt));
            }
        }
    }

    private long sourceVersion(String sourceCode) {
        return jdbc.queryForObject("SELECT current_revision FROM source_registry WHERE code = ?", Long.class,
                sourceCode);
    }

    private Timestamp timestamp() {
        return Timestamp.from(clock.instant());
    }

    private static Cookie cookie(SessionService.Bootstrap owner) {
        return new Cookie("__Host-nullnull_session", owner.cookie);
    }
}
