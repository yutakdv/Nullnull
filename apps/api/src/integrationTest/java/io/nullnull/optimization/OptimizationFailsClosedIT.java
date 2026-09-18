package io.nullnull.optimization;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
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

/**
 * A run's proposals are catalog text, so they are served only while the catalog is published.
 *
 * <p>A proposal's summary names the place and its dataProvenance quotes the source registry. getTrip
 * answers a trip with items 503 while the catalog is closed (TripDetailFailsClosedIT); a run holding
 * proposals is the same kind of read. The catalog gate is left at its default - closed - which is the
 * configuration a submission build without KTO evidence actually runs.
 *
 * <p>The proposals are written with SQL because the worker cannot write one here: it refuses to judge
 * while the catalog is closed (OptimizeItemHandler), and jobs are off so the run stays as created. The
 * state this constructs is a run judged while the catalog was open, read after it was closed.
 */
@SpringBootTest(properties = {"nullnull.capabilities.optimization=true", "nullnull.jobs.enabled=false"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("getOptimization and createOptimization while the catalog is closed")
class OptimizationFailsClosedIT {

    private static final String ORIGIN = "http://localhost:5173";
    private static final LocalDate DAY_ONE = LocalDate.parse("2026-10-04");
    private static final LocalDate DAY_TWO = LocalDate.parse("2026-10-05");

    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> trips = new ArrayList<>();
    private final List<UUID> places = new ArrayList<>();
    private final List<UUID> runIds = new ArrayList<>();

    /** Only this class's rows, each named by an id it created (AGENTS.md rule 6). */
    @AfterEach
    void removeOnlyOwnFixtures() {
        for (UUID runId : runIds) {
            jdbc.update("DELETE FROM background_jobs WHERE deduplication_key = ?", "optimization:" + runId);
        }
        // The trip takes its item, its runs and each run's proposals and changes with it (V013, V024, V029).
        for (UUID tripId : trips) {
            jdbc.update("DELETE FROM trips WHERE id = ?", tripId);
        }
        for (UUID placeId : places) {
            jdbc.update("DELETE FROM places WHERE id = ?", placeId);
        }
    }

    @Test
    @DisplayName("BA-051-T19 getOptimization answers a run with no proposal and is 503 for one that holds a proposal")
    void aRunHoldingProposalsIsNotServedWhileTheCatalogIsClosed() throws Exception {
        Fixture fixture = fixture();
        String key = "optimize-" + UUID.randomUUID();
        UUID runId = create(fixture, key);

        // The control: nothing of the catalog's is in this run, so it is answered.
        read(fixture, runId).andExpect(status().isOk())
                .andExpect(jsonPath("$.proposals").isEmpty());

        seedProposal(runId, fixture);

        read(fixture, runId).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SOURCE_UNAVAILABLE"))
                .andExpect(jsonPath("$.proposals").doesNotExist());
    }

    @Test
    @DisplayName("BA-051-T20 a replayed createOptimization is 503 once its run holds a proposal")
    void aReplayedCreateIsNotServedOnceItsRunHoldsProposals() throws Exception {
        Fixture fixture = fixture();
        String key = "optimize-" + UUID.randomUUID();
        UUID runId = create(fixture, key);

        // The control: a replay of the same key answers the same run while it holds nothing.
        post(fixture, key).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(runId.toString()));

        seedProposal(runId, fixture);

        post(fixture, key).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SOURCE_UNAVAILABLE"));
    }

    private record Fixture(UUID tripId, UUID itemId, UUID placeId, SessionService.Bootstrap owner) {
    }

    private Fixture fixture() throws Exception {
        SessionService.Bootstrap owner = sessions.bootstrap(null, null, null);
        // No seedItems: creating a trip with items is what a closed catalog refuses (BA-030-T4).
        String created = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/trips")
                        .cookie(cookie(owner))
                        .header("Origin", ORIGIN)
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "trip-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"startDate\":\"" + DAY_ONE + "\",\"endDate\":\"" + DAY_TWO + "\","
                                + "\"timezone\":\"Asia/Seoul\",\"planningLevel\":\"NOTHING\",\"interests\":[]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID tripId = UUID.fromString(created.replaceFirst("(?s)^.*?\"id\":\"([^\"]+)\".*$", "$1"));
        trips.add(tripId);
        UUID placeId = UUID.randomUUID();
        places.add(placeId);
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO places (id, canonical_name, category_code, latitude, longitude,"
                + " region_code, status, created_at, updated_at)"
                + " VALUES (?, 'closed catalog fixture', 'A0101', 37.566535, 126.978001, '1', 'ACTIVE', ?, ?)",
                placeId, now, now);
        UUID itemId = UUID.randomUUID();
        jdbc.update("INSERT INTO trip_items (id, trip_id, place_id, trip_date, position, start_time,"
                + " duration_minutes, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, 0, CAST('09:00' AS time), 90, ?, ?)",
                itemId, tripId, placeId, java.sql.Date.valueOf(DAY_ONE), now, now);
        return new Fixture(tripId, itemId, placeId, owner);
    }

    private UUID create(Fixture fixture, String key) throws Exception {
        String created = post(fixture, key).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        UUID runId = UUID.fromString(created.replaceFirst("(?s)^.*?\"id\":\"([^\"]+)\".*$", "$1"));
        runIds.add(runId);
        return runId;
    }

    private ResultActions post(Fixture fixture, String key) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/trips/" + fixture.tripId() + "/optimizations")
                .cookie(cookie(fixture.owner()))
                .header("Origin", ORIGIN)
                .header("X-CSRF-Token", fixture.owner().csrf.token)
                .header("If-Match", "\"1\"")
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content("{\"scope\":\"ITEM\",\"targetItemId\":\"" + fixture.itemId()
                        + "\",\"inputTripVersion\":1,\"includeCandidates\":false,"
                        + "\"objective\":\"REDUCE_CROWD\"}"));
    }

    private ResultActions read(Fixture fixture, UUID runId) throws Exception {
        return mvc.perform(get("/api/v1/optimizations/" + runId).cookie(cookie(fixture.owner())));
    }

    /** One stored proposal moving the item a day on - what a run judged while the catalog was open holds. */
    private void seedProposal(UUID runId, Fixture fixture) {
        UUID proposalId = UUID.randomUUID();
        jdbc.update("INSERT INTO optimization_proposals (id, run_id, rank, summary, comparison_eligible,"
                + " comparison_reason_code, crowd_delta, travel_minutes_delta, validation_summary, created_at)"
                + " VALUES (?, ?, 1, 'closed catalog fixture 방문을 옮기면 덜 붐벼요.', true, NULL, -10,"
                + " NULL, '{\"checks\":{}}'::jsonb, ?)", proposalId, runId, OffsetDateTime.now());
        jdbc.update("INSERT INTO optimization_changes (id, proposal_id, trip_item_id, operation, before_value,"
                + " after_value, sequence) VALUES (?, ?, ?, 'MOVE', ?::jsonb, ?::jsonb, 0)",
                UUID.randomUUID(), proposalId, fixture.itemId(), state(fixture, DAY_ONE), state(fixture, DAY_TWO));
    }

    private static String state(Fixture fixture, LocalDate day) {
        return "{\"placeId\":\"" + fixture.placeId() + "\",\"date\":\"" + day
                + "\",\"position\":0,\"startTime\":\"09:00\"}";
    }

    private static Cookie cookie(SessionService.Bootstrap owner) {
        return new Cookie("__Host-nullnull_session", owner.cookie);
    }
}
