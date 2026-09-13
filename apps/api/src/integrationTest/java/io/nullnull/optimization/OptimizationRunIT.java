package io.nullnull.optimization;

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
import java.time.LocalDate;
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
 * BA-050 createOptimization and getOptimization over HTTP and a real PostgreSQL.
 *
 * <p>The capability flag is ON here, which no deployed environment does. That is the point of the
 * flag being a flag: this suite is the only caller allowed to see the endpoint work, and every other
 * environment gets the refusal {@code OptimizationCapabilityOffIT} asserts.
 *
 * <p>Nothing in this file expects a READY run. The slice that writes a preview is BA-051; this one
 * queues the work, freezes what an answer would be judged against, and refuses the runs whose input
 * moved. A run that survives all of that simply waits, which is what the expiry assertions are about.
 */
@SpringBootTest(properties = {"nullnull.catalog.public-enabled=true",
        "nullnull.capabilities.optimization=true"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-050 optimization run, snapshot and polling")
class OptimizationRunIT {

    private static final LocalDate DAY_ONE = LocalDate.parse("2026-10-04");

    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("BA-050-T1 a body whose target does not match its scope is refused and creates nothing")
    void theTargetShapeIsEnforced() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID itemId = insertItem(tripId, DAY_ONE);

        // ITEM without a target.
        create(owner, tripId, "\"1\"", "{\"scope\":\"ITEM\",\"inputTripVersion\":1,"
                + "\"includeCandidates\":false}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // ITEM carrying the other scope's target as well.
        create(owner, tripId, "\"1\"", "{\"scope\":\"ITEM\",\"targetItemId\":\"" + itemId + "\","
                + "\"targetDate\":\"2026-10-04\",\"inputTripVersion\":1,\"includeCandidates\":false}")
                .andExpect(status().isUnprocessableContent());
        // A target the trip does not contain.
        create(owner, tripId, "\"1\"", "{\"scope\":\"ITEM\",\"targetItemId\":\"" + UUID.randomUUID()
                + "\",\"inputTripVersion\":1,\"includeCandidates\":false}")
                .andExpect(status().isUnprocessableContent());
        // The P0 refusal of the candidate-aware path, which has no implementation to enable.
        create(owner, tripId, "\"1\"", "{\"scope\":\"ITEM\",\"targetItemId\":\"" + itemId
                + "\",\"inputTripVersion\":1,\"includeCandidates\":true}")
                .andExpect(status().isUnprocessableContent());
        // Two statements of the same precondition that disagree.
        create(owner, tripId, "\"1\"", "{\"scope\":\"ITEM\",\"targetItemId\":\"" + itemId
                + "\",\"inputTripVersion\":2,\"includeCandidates\":false}")
                .andExpect(status().isBadRequest());

        assertThat(runCount(tripId))
                .as("a refused request leaves no run behind to be polled or resumed")
                .isZero();
    }

    @Test
    @DisplayName("BA-050-T2 a well-formed DAY or TRIP request is refused because the scope is P1")
    void theP1ScopesAreRefusedEvenWhenWellFormed() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        insertItem(tripId, DAY_ONE);

        // Nothing about these bodies is malformed: DAY carries exactly targetDate and TRIP carries
        // neither target, which is what the operation asks of them. They are refused for the other
        // reason, which is why this is not the same assertion as T1.
        create(owner, tripId, "\"1\"", "{\"scope\":\"DAY\",\"targetDate\":\"2026-10-04\","
                + "\"inputTripVersion\":1,\"includeCandidates\":false}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("scope"));
        create(owner, tripId, "\"1\"", "{\"scope\":\"TRIP\",\"inputTripVersion\":1,"
                + "\"includeCandidates\":false}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("scope"));

        assertThat(runCount(tripId)).isZero();
    }

    @Test
    @DisplayName("BA-050-T3 an accepted run and its job are created together")
    void theRunAndItsJobAreOneUnitOfWork() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID itemId = insertItem(tripId, DAY_ONE);

        String body = create(owner, tripId, "\"1\"", itemRequest(itemId, 1))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.inputTripVersion").value(1))
                .andExpect(jsonPath("$.proposals").isEmpty())
                .andExpect(jsonPath("$.decisions").isEmpty())
                .andReturn().getResponse().getContentAsString();
        UUID runId = UUID.fromString(body.replaceFirst("(?s)^.*?\"id\":\"([^\"]+)\".*$", "$1"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM optimization_runs WHERE id = ?",
                Integer.class, runId)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM background_jobs WHERE deduplication_key = ?",
                Integer.class, "optimization:" + runId))
                .as("a run with no job would wait for a worker that never comes")
                .isOne();
        // The run did not touch the trip: a preview is preview-only (invariant 3).
        assertThat(jdbc.queryForObject("SELECT version FROM trips WHERE id = ?", Long.class, tripId))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("BA-050-T3 the same Idempotency-Key returns the same run rather than a second one")
    void aRetryReturnsTheRunItAlreadyCreated() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID itemId = insertItem(tripId, DAY_ONE);
        String key = "optimize-" + UUID.randomUUID();

        String first = create(owner, tripId, "\"1\"", itemRequest(itemId, 1), key)
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String second = create(owner, tripId, "\"1\"", itemRequest(itemId, 1), key)
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();

        assertThat(runId(second)).isEqualTo(runId(first));
        assertThat(runCount(tripId)).isOne();
    }

    @Test
    @DisplayName("BA-050-T1 an item whose date and time are both pinned has nothing to propose")
    void aFullyPinnedTargetIsRefused() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID itemId = insertItem(tripId, DAY_ONE);
        lock(tripId, itemId, "DATE");
        lock(tripId, itemId, "TIME");

        create(owner, tripId, "\"1\"", itemRequest(itemId, 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOCK_CONFLICT"));
        assertThat(runCount(tripId)).isZero();
    }

    @Test
    @DisplayName("BA-050-T7 a poll carries Retry-After while the run is pending and not after")
    void retryAfterIsPresentOnlyWhilePending() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID itemId = insertItem(tripId, DAY_ONE);
        UUID runId = runId(create(owner, tripId, "\"1\"", itemRequest(itemId, 1))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());

        poll(owner, runId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(header().string("Retry-After", "2"))
                .andExpect(header().string("Cache-Control", "private, no-store"));

        // Ended by hand, because what this asserts is the header rule rather than the worker.
        jdbc.update("UPDATE optimization_runs SET status = 'FAILED', failure_code = 'TRIP_CHANGED',"
                + " failure_message = 'ended by the test', started_at = now(), completed_at = now()"
                + " WHERE id = ?", runId);
        poll(owner, runId)
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failure.code").value("TRIP_CHANGED"))
                .andExpect(jsonPath("$.failure.retryable").value(false))
                .andExpect(header().doesNotExist("Retry-After"));
    }

    @Test
    @DisplayName("BA-050-T7 a run whose preview deadline has passed reads as EXPIRED, and stays there")
    void anUndecidedRunPastItsDeadlineReadsAsExpired() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID itemId = insertItem(tripId, DAY_ONE);
        UUID runId = runId(create(owner, tripId, "\"1\"", itemRequest(itemId, 1))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());

        jdbc.update("UPDATE optimization_runs SET status = 'RUNNING', started_at = now(),"
                + " expires_at = now() - interval '1 minute' WHERE id = ?", runId);

        poll(owner, runId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                // A status computed at read time still has to look settled: nothing is coming.
                .andExpect(header().doesNotExist("Retry-After"));
        poll(owner, runId).andExpect(jsonPath("$.status").value("EXPIRED"));
    }

    /**
     * BA-052-T4, which BA-050 can prove even though BA-052 has not been built.
     *
     * <p>The claim is about precedence in the READ, and the read exists now: getOptimization says
     * "Preview expiry prevents a new APPLY/KEEP, but must not turn an already APPLIED or REVERTED
     * run into PREVIEW_EXPIRED". A decision is a fact that happened; an expiry is a deadline that
     * passed, and a deadline cannot un-happen a fact.
     *
     * <p>The APPLIED row is written directly because the operation that would create it is BA-052's
     * and does not exist. That is the one thing this test takes on credit, and it is a state the
     * schema already accepts rather than an invented one. What is under test is not how the row got
     * there but what the projection does with it, and that code is in front of us.
     */
    @Test
    @DisplayName("BA-052-T4 a run with a decision stays decided after its preview deadline passes")
    void anExpiredDeadlineDoesNotUndoADecision() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID itemId = insertItem(tripId, DAY_ONE);
        UUID runId = runId(create(owner, tripId, "\"1\"", itemRequest(itemId, 1))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());

        jdbc.update("UPDATE optimization_runs SET status = 'APPLIED', started_at = now(),"
                + " completed_at = now(), data_fingerprint = ?, expires_at = now() - interval '1 hour'"
                + " WHERE id = ?", "a".repeat(64), runId);

        poll(owner, runId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(header().doesNotExist("Retry-After"));

        // The same row with the decision taken away DOES expire, so the assertion above is about the
        // decision and not about terminal statuses in general.
        jdbc.update("UPDATE optimization_runs SET status = 'RUNNING', completed_at = NULL WHERE id = ?",
                runId);
        poll(owner, runId).andExpect(jsonPath("$.status").value("EXPIRED"));
    }

    @Test
    @DisplayName("BA-050 another owner's run is not readable and not distinguishable from one that is gone")
    void aForeignRunIsNotFound() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        var stranger = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID itemId = insertItem(tripId, DAY_ONE);
        UUID runId = runId(create(owner, tripId, "\"1\"", itemRequest(itemId, 1))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());

        poll(stranger, runId).andExpect(status().isNotFound());
        poll(stranger, UUID.randomUUID()).andExpect(status().isNotFound());
    }

    private static String itemRequest(UUID itemId, long version) {
        return "{\"scope\":\"ITEM\",\"targetItemId\":\"" + itemId + "\",\"inputTripVersion\":" + version
                + ",\"includeCandidates\":false,\"objective\":\"REDUCE_CROWD\"}";
    }

    private static UUID runId(String body) {
        return UUID.fromString(body.replaceFirst("(?s)^.*?\"id\":\"([^\"]+)\".*$", "$1"));
    }

    private ResultActions create(SessionService.Bootstrap owner, UUID tripId, String ifMatch, String body)
            throws Exception {
        return create(owner, tripId, ifMatch, body, "optimize-" + UUID.randomUUID());
    }

    private ResultActions create(SessionService.Bootstrap owner, UUID tripId, String ifMatch, String body,
            String idempotencyKey) throws Exception {
        return mvc.perform(post("/api/v1/trips/" + tripId + "/optimizations")
                .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                .header("Origin", "http://localhost:5173")
                .header("X-CSRF-Token", owner.csrf.token)
                .header("If-Match", ifMatch)
                .header("Idempotency-Key", idempotencyKey)
                .contentType("application/json")
                .content(body));
    }

    private ResultActions poll(SessionService.Bootstrap owner, UUID runId) throws Exception {
        return mvc.perform(get("/api/v1/optimizations/" + runId)
                .cookie(new Cookie("__Host-nullnull_session", owner.cookie)));
    }

    private int runCount(UUID tripId) {
        return jdbc.queryForObject("SELECT count(*) FROM optimization_runs WHERE trip_id = ?",
                Integer.class, tripId);
    }

    private UUID createTrip(SessionService.Bootstrap owner) throws Exception {
        String created = mvc.perform(post("/api/v1/trips")
                        .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "trip-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"startDate\":\"2026-10-04\",\"endDate\":\"2026-10-05\","
                                + "\"timezone\":\"Asia/Seoul\",\"planningLevel\":\"NOTHING\","
                                + "\"interests\":[]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return runId(created);
    }

    private UUID insertItem(UUID tripId, LocalDate date) {
        UUID placeId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO places (id, canonical_name, category_code, region_code, status,"
                + " created_at, updated_at) VALUES (?, '최적화 test 장소', 'HS', '11', 'ACTIVE', ?, ?)",
                placeId, now, now);
        jdbc.update("INSERT INTO trip_items (id, trip_id, place_id, trip_date, position, start_time,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, 0, CAST('09:00:00' AS time), ?, ?)",
                id, tripId, placeId, java.sql.Date.valueOf(date), now, now);
        return id;
    }

    private void lock(UUID tripId, UUID itemId, String type) {
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO trip_constraints (id, trip_id, trip_item_id, type, source,"
                        + " date_value, start_time_value, tolerance_minutes, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'USER', ?, ?, ?, ?, ?)",
                UUID.randomUUID(), tripId, itemId, type,
                "DATE".equals(type) ? java.sql.Date.valueOf(DAY_ONE) : null,
                "TIME".equals(type) ? java.sql.Time.valueOf("09:00:00") : null,
                "TIME".equals(type) ? 30 : null, now, now);
    }
}
