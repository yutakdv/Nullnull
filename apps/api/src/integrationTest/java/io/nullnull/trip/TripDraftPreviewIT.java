package io.nullnull.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.catalog.application.CatalogPlaceQuery;
import io.nullnull.catalog.application.CatalogPlaceQuery.CatalogPlaceSummary;
import io.nullnull.identity.application.SessionService;
import io.nullnull.recommendation.application.RecommendationGateway;
import io.nullnull.recommendation.domain.draft.DraftComposeRequest;
import io.nullnull.recommendation.domain.draft.DraftComposeResponse;
import io.nullnull.recommendation.domain.draft.DraftPlaceIn;
import io.nullnull.recommendation.domain.draft.DraftStopOut;
import io.nullnull.recommendation.domain.item.OpeningWindowIn;
import io.nullnull.testsupport.OwnedRows;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * BA-055 previewTripDraft with the catalog published: what is sent to {@code apps/ai}, what comes
 * back, and that nothing is written.
 *
 * <p>{@code apps/ai} is mocked because what is under test is Spring's half - the hydration, the
 * projection and the absence of any write - not the placement rule, which is {@code apps/ai}'s and has
 * its own corpus (REC-DRAFT-01~04, ADR-0006). The mock answers with {@link #composeLikeAppsAi}, a
 * small restatement of that rule, so an answer changes when the request changes; a canned answer
 * would pass whatever Spring sent.
 *
 * <p>The pool is narrowed to this class's own places. The required gate runs every class against one
 * database (AGENTS.md rule 6), where any other class's places are in the catalog too; reading the
 * real pool and filtering it keeps the real query in the path while the assertions stay about rows
 * this class made.
 */
@SpringBootTest(properties = "nullnull.catalog.public-enabled=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-055 trip draft preview")
class TripDraftPreviewIT {

    private static final LocalDate DAY_ONE = LocalDate.of(2026, 10, 4);
    private static final LocalDate DAY_TWO = DAY_ONE.plusDays(1);
    private static final String HOURS_SOURCE = "NULLNULL_CURATED_HOURS";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean RecommendationGateway recommendations;
    @MockitoSpyBean CatalogPlaceQuery catalog;

    private final Set<UUID> mine = new HashSet<>();
    private final List<UUID> owners = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void narrowThePoolToThisClassesPlaces() {
        doAnswer(invocation -> ((List<CatalogPlaceSummary>) invocation.callRealMethod()).stream()
                .filter(place -> mine.contains(place.id()))
                .toList())
                .when(catalog).activePool(anyInt(), any(), any());
        when(recommendations.composeDraft(any())).thenAnswer(invocation -> composeLikeAppsAi(invocation.getArgument(0)));
    }

    @AfterEach
    void removeOnlyOwnRows() {
        for (UUID owner : owners) {
            // trip_items, trip_candidates and the rest hang off trips with ON DELETE CASCADE.
            jdbc.update("DELETE FROM trips WHERE owner_id = ?", owner);
            jdbc.update("DELETE FROM idempotency_records WHERE owner_id = ?", owner);
        }
        OwnedRows.remove(jdbc, "places", List.copyOf(mine));
    }

    @Test
    @DisplayName("BA-055-T1 a preview writes no trip, item, candidate or idempotency row for its owner")
    void aPreviewWritesNothing() throws Exception {
        place("미리보기 장소 하나");
        place("미리보기 장소 둘");
        SessionService.Bootstrap owner = owner();
        Map<String, Integer> before = ownerRowCounts(owner.owner.id());

        JsonNode draft = read(preview(owner, DAY_ONE, DAY_TWO).andExpect(status().isOk()));

        // Non-vacuous: the call really produced a draft with stops, so "nothing was written" is about a
        // request that did something rather than one that failed before it could.
        assertThat(draft.get("state").asString()).isEqualTo("READY");
        assertThat(stops(draft)).hasSize(2);
        assertThat(ownerRowCounts(owner.owner.id())).isEqualTo(before)
                .containsOnlyKeys("trips", "trip_items", "trip_candidates", "idempotency_records")
                .allSatisfy((table, count) -> assertThat(count).as(table).isZero());
    }

    @Test
    @DisplayName("BA-055-T2 the same request twice sends the same pool and returns the same stops")
    void theSameRequestTwiceGivesTheSameDraft() throws Exception {
        place("결정성 장소 하나");
        place("결정성 장소 둘");
        place("결정성 장소 셋");
        SessionService.Bootstrap owner = owner();

        JsonNode first = read(preview(owner, DAY_ONE, DAY_TWO).andExpect(status().isOk()));
        JsonNode second = read(preview(owner, DAY_ONE, DAY_TWO).andExpect(status().isOk()));

        ArgumentCaptor<DraftComposeRequest> sent = ArgumentCaptor.forClass(DraftComposeRequest.class);
        verify(recommendations, times(2)).composeDraft(sent.capture());
        assertThat(sent.getAllValues().get(0).pool()).isEqualTo(sent.getAllValues().get(1).pool());
        assertThat(first.get("days")).isEqualTo(second.get("days"));
        assertThat(stops(first)).hasSize(3);
    }

    @Test
    @DisplayName("BA-055-T3 every stop confirmed as a seed item through createTrip is accepted, one item per stop")
    void theDraftConfirmsThroughCreateTrip() throws Exception {
        place("확정 장소 하나");
        place("확정 장소 둘");
        place("확정 장소 셋");
        place("확정 장소 넷");
        SessionService.Bootstrap owner = owner();
        JsonNode draft = read(preview(owner, DAY_ONE, DAY_TWO).andExpect(status().isOk()));
        List<JsonNode> stops = stops(draft);
        assertThat(stops).hasSize(4);

        // The rule the contract states: each stop is one SeedTripItem with no time and no constraint.
        StringBuilder seeds = new StringBuilder();
        for (JsonNode stop : stops) {
            if (!seeds.isEmpty()) {
                seeds.append(',');
            }
            seeds.append("{\"placeId\":\"").append(stop.get("place").get("id").asString())
                    .append("\",\"date\":\"").append(stop.get("date").asString())
                    .append("\",\"position\":").append(stop.get("position").asInt())
                    .append(",\"startTime\":null,\"constraints\":[]}");
        }
        String body = "{\"startDate\":\"" + DAY_ONE + "\",\"endDate\":\"" + DAY_TWO
                + "\",\"timezone\":\"Asia/Seoul\",\"planningLevel\":\"NOTHING\",\"interests\":[],"
                + "\"seedItems\":[" + seeds + "]}";

        JsonNode created = read(mvc.perform(post("/api/v1/trips")
                        .cookie(cookie(owner))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated()));

        Set<String> items = new HashSet<>();
        for (JsonNode day : created.get("days")) {
            for (JsonNode item : day.get("items")) {
                items.add(item.get("place").get("id").asString() + "@" + item.get("date").asString()
                        + "#" + item.get("position").asInt());
            }
        }
        Set<String> proposed = new HashSet<>();
        for (JsonNode stop : stops) {
            proposed.add(stop.get("place").get("id").asString() + "@" + stop.get("date").asString()
                    + "#" + stop.get("position").asInt());
        }
        assertThat(items).hasSize(stops.size()).isEqualTo(proposed);
    }

    @Test
    @DisplayName("BA-055-T6 a verified closure reaches apps/ai as CLOSED and an unverified date reaches it with no window")
    void closedDatesHoldNoStopAndUnverifiedDatesSayUnknown() throws Exception {
        UUID closedOnDayOne = place("첫날 휴무 장소");
        closed(observation(closedOnDayOne), DAY_ONE);
        SessionService.Bootstrap owner = owner();

        JsonNode draft = read(preview(owner, DAY_ONE, DAY_TWO).andExpect(status().isOk()));

        // What left this server: the verified closure, and nothing at all for the date nobody read.
        ArgumentCaptor<DraftComposeRequest> sent = ArgumentCaptor.forClass(DraftComposeRequest.class);
        verify(recommendations).composeDraft(sent.capture());
        DraftPlaceIn hydrated = sent.getValue().pool().stream()
                .filter(place -> place.placeId().equals(closedOnDayOne)).findFirst().orElseThrow();
        assertThat(hydrated.openingHours()).containsOnlyKeys(DAY_ONE);
        assertThat(hydrated.openingHours().get(DAY_ONE).state()).isEqualTo(OpeningWindowIn.OpeningState.CLOSED);

        List<JsonNode> placed = stops(draft).stream()
                .filter(stop -> stop.get("place").get("id").asString().equals(closedOnDayOne.toString()))
                .toList();
        assertThat(placed).singleElement().satisfies(stop -> {
            assertThat(stop.get("date").asString()).isEqualTo(DAY_TWO.toString());
            assertThat(stop.get("hoursState").asString()).isEqualTo("UNKNOWN");
        });
    }

    @Test
    @DisplayName("BA-055-T9 with no public place the draft is EMPTY, keeps every date and holds no stop")
    void noPlaceGivesAnEmptyDraftWithEveryDate() throws Exception {
        SessionService.Bootstrap owner = owner();

        preview(owner, DAY_ONE, DAY_ONE.plusDays(2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("EMPTY"))
                .andExpect(jsonPath("$.reasons[0]").value("NO_ELIGIBLE_PLACES"))
                .andExpect(jsonPath("$.days.length()").value(3))
                .andExpect(jsonPath("$.days[0].date").value(DAY_ONE.toString()))
                .andExpect(jsonPath("$.days[2].date").value(DAY_ONE.plusDays(2).toString()))
                .andExpect(jsonPath("$.days[*].stops[*]").isEmpty())
                .andExpect(jsonPath("$.basis").value(org.hamcrest.Matchers.contains("DATE_RANGE")));

        ArgumentCaptor<DraftComposeRequest> sent = ArgumentCaptor.forClass(DraftComposeRequest.class);
        verify(recommendations).composeDraft(sent.capture());
        assertThat(sent.getValue().pool()).isEmpty();
    }

    @Test
    @DisplayName("BA-055-T10 OPENING_HOURS_VERIFIED is a basis only when at least one stop is OPEN")
    void verifiedHoursAreABasisOnlyWhenAStopIsOpen() throws Exception {
        UUID unverified = place("영업시간 미확인 장소");
        SessionService.Bootstrap owner = owner();

        preview(owner, DAY_ONE, DAY_TWO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].stops[0].hoursState").value("UNKNOWN"))
                .andExpect(jsonPath("$.basis").value(org.hamcrest.Matchers.contains("DATE_RANGE")));

        open(observation(unverified), DAY_ONE, LocalTime.of(9, 0), LocalTime.of(18, 0));

        preview(owner, DAY_ONE, DAY_TWO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].stops[0].hoursState").value("OPEN"))
                .andExpect(jsonPath("$.basis").value(
                        org.hamcrest.Matchers.contains("DATE_RANGE", "OPENING_HOURS_VERIFIED")));
    }

    @Test
    @DisplayName("BA-055-T11 the preview response is sent with Cache-Control: private, no-store")
    void theResponseIsNeverCached() throws Exception {
        place("캐시 금지 장소");
        preview(owner(), DAY_ONE, DAY_TWO)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"));
    }

    @Test
    @DisplayName("a catalog larger than one pool sends the first hundred by id and says POOL_TRUNCATED")
    void aPoolOverTheCapIsTruncatedAndSaysSo() throws Exception {
        List<CatalogPlaceSummary> many = new ArrayList<>();
        for (int n = 0; n < DraftComposeRequest.MAX_POOL + 1; n++) {
            many.add(new CatalogPlaceSummary(new UUID(0L, n + 1L), "장소 " + n, "HS", "11", null, null, null,
                    null, null, null));
        }
        doAnswer(invocation -> many.subList(0, Math.min(many.size(), invocation.getArgument(0))))
                .when(catalog).activePool(anyInt(), any(), any());

        preview(owner(), DAY_ONE, DAY_TWO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reasons").value(org.hamcrest.Matchers.hasItem("POOL_TRUNCATED")));

        ArgumentCaptor<DraftComposeRequest> sent = ArgumentCaptor.forClass(DraftComposeRequest.class);
        verify(recommendations).composeDraft(sent.capture());
        assertThat(sent.getValue().pool()).hasSize(DraftComposeRequest.MAX_POOL)
                .extracting(DraftPlaceIn::placeId).doesNotContain(new UUID(0L, DraftComposeRequest.MAX_POOL + 1L));
    }

    @Test
    @DisplayName("a range longer than 30 days is refused with 422 before anything is asked")
    void aTooLongRangeIsRefused() throws Exception {
        preview(owner(), DAY_ONE, DAY_ONE.plusDays(30))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verify(recommendations, times(0)).composeDraft(any());
    }

    /**
     * The placement rule of {@code apps/ai/src/nullnull_ai/draft/composer.py}, restated for the mock:
     * places by id, a date that is not verified CLOSED and holds fewer than the cap, the date with the
     * fewest stops (earliest on a tie), OPEN only on a verified OPEN window. Test code only - Spring
     * does not compute a draft (ADR-0006).
     */
    private static DraftComposeResponse composeLikeAppsAi(DraftComposeRequest request) {
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate date = request.tripStart(); !date.isAfter(request.tripEnd()); date = date.plusDays(1)) {
            dates.add(date);
        }
        Map<LocalDate, List<DraftStopOut>> placed = new HashMap<>();
        dates.forEach(date -> placed.put(date, new ArrayList<>()));
        for (DraftPlaceIn place : request.pool().stream()
                .sorted(Comparator.comparing(candidate -> candidate.placeId().toString())).toList()) {
            LocalDate best = null;
            for (LocalDate date : dates) {
                OpeningWindowIn window = place.openingHours().get(date);
                boolean closed = window != null && window.state() == OpeningWindowIn.OpeningState.CLOSED;
                if (closed || placed.get(date).size() >= request.maxStopsPerDay()) {
                    continue;
                }
                if (best == null || placed.get(date).size() < placed.get(best).size()) {
                    best = date;
                }
            }
            if (best == null) {
                continue;
            }
            OpeningWindowIn window = place.openingHours().get(best);
            boolean open = window != null && window.state() == OpeningWindowIn.OpeningState.OPEN;
            placed.get(best).add(new DraftStopOut(place.placeId(), best, placed.get(best).size(),
                    open ? DraftStopOut.HoursState.OPEN : DraftStopOut.HoursState.UNKNOWN));
        }
        List<DraftStopOut> stops = dates.stream().flatMap(date -> placed.get(date).stream()).toList();
        return new DraftComposeResponse("policy-v1", "a".repeat(64), "nullnull-ai-pipeline-v1",
                stops.isEmpty() ? DraftComposeResponse.State.EMPTY : DraftComposeResponse.State.READY, stops,
                stops.isEmpty() ? List.of("NO_ELIGIBLE_PLACES") : List.of(), request.pool().size(), Map.of());
    }

    private SessionService.Bootstrap owner() {
        SessionService.Bootstrap owner = sessions.bootstrap(null, null, null);
        owners.add(owner.owner.id());
        return owner;
    }

    private static Cookie cookie(SessionService.Bootstrap owner) {
        return new Cookie("__Host-nullnull_session", owner.cookie);
    }

    private ResultActions preview(SessionService.Bootstrap owner, LocalDate start, LocalDate end) throws Exception {
        return mvc.perform(post("/api/v1/trip-drafts/preview")
                .cookie(cookie(owner))
                .header("Origin", "http://localhost:5173")
                .contentType("application/json")
                .content("{\"startDate\":\"" + start + "\",\"endDate\":\"" + end + "\",\"timezone\":\"Asia/Seoul\"}"));
    }

    private static JsonNode read(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static List<JsonNode> stops(JsonNode draft) {
        List<JsonNode> stops = new ArrayList<>();
        draft.get("days").forEach(day -> day.get("stops").forEach(stops::add));
        return stops;
    }

    /** Rows scoped to this owner - never a table-wide count, which on the shared gate DB counts other classes. */
    private Map<String, Integer> ownerRowCounts(UUID ownerId) {
        Map<String, Integer> counts = new HashMap<>();
        counts.put("trips", jdbc.queryForObject("SELECT count(*) FROM trips WHERE owner_id = ?", Integer.class,
                ownerId));
        counts.put("trip_items", jdbc.queryForObject("SELECT count(*) FROM trip_items item JOIN trips trip"
                + " ON trip.id = item.trip_id WHERE trip.owner_id = ?", Integer.class, ownerId));
        counts.put("trip_candidates", jdbc.queryForObject("SELECT count(*) FROM trip_candidates candidate"
                + " JOIN trips trip ON trip.id = candidate.trip_id WHERE trip.owner_id = ?", Integer.class, ownerId));
        counts.put("idempotency_records", jdbc.queryForObject(
                "SELECT count(*) FROM idempotency_records WHERE owner_id = ?", Integer.class, ownerId));
        return counts;
    }

    private UUID place(String name) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO places
                    (id, canonical_name, category_code, latitude, longitude, region_code, status,
                     created_at, updated_at)
                VALUES (?, ?, 'A0201', 37.579617, 126.977041, '11', 'ACTIVE', ?, ?)
                """, id, name, now, now);
        mine.add(id);
        return id;
    }

    private UUID observation(UUID placeId) {
        UUID id = UUID.randomUUID();
        Instant observedAt = Instant.now().minus(1, ChronoUnit.DAYS);
        jdbc.update("""
                INSERT INTO place_hours_observations
                    (id, place_id, source_code, source_registry_version, outcome, observed_at,
                     evidence_url, stale_at, created_at)
                VALUES (?, ?, ?, 1, 'OBSERVED', ?, 'https://royal.cha.go.kr/example/hours', ?, ?)
                """, id, placeId, HOURS_SOURCE, Timestamp.from(observedAt),
                Timestamp.from(observedAt.plus(30, ChronoUnit.DAYS)), Timestamp.from(observedAt));
        return id;
    }

    private void open(UUID observationId, LocalDate date, LocalTime opensAt, LocalTime closesAt) {
        jdbc.update("""
                INSERT INTO place_hours_windows (id, observation_id, effective_on, state, opens_at, closes_at)
                VALUES (?, ?, ?, 'OPEN', ?, ?)
                """, UUID.randomUUID(), observationId, java.sql.Date.valueOf(date),
                java.sql.Time.valueOf(opensAt), java.sql.Time.valueOf(closesAt));
    }

    private void closed(UUID observationId, LocalDate date) {
        jdbc.update("""
                INSERT INTO place_hours_windows (id, observation_id, effective_on, state, opens_at, closes_at)
                VALUES (?, ?, ?, 'CLOSED', NULL, NULL)
                """, UUID.randomUUID(), observationId, java.sql.Date.valueOf(date));
    }
}
