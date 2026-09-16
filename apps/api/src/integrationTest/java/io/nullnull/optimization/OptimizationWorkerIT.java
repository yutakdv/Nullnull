package io.nullnull.optimization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.identity.application.SessionService;
import io.nullnull.optimization.application.OptimizationRunStore;
import io.nullnull.optimization.domain.OptimizationStatus;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * BA-050's worker: what it freezes, what it refuses, and what happens when two of them meet.
 *
 * <p>The real worker runs here ({@code nullnull.jobs.enabled=true}), so these are assertions about
 * the job as it is actually claimed and leased rather than about a handler called by hand.
 *
 * <p>There is no test for a run reaching READY, and that is not an omission. This slice has nothing
 * to put in a preview: generating the candidates an {@code items/propose} call would carry is BA-051
 * step 1, and storing what comes back is behind {@code ProposalRevalidator}, which is BA-051's too.
 * What BA-050 owns is the gate in front of that write, and a gate is proven by the thing it stops.
 */
@SpringBootTest(properties = {"nullnull.catalog.public-enabled=true",
        "nullnull.capabilities.optimization=true", "nullnull.jobs.enabled=true",
        "nullnull.jobs.poll-interval=PT0.02S", "nullnull.jobs.retry-backoff=PT1S",
        "nullnull.jobs.max-retry-backoff=PT1S"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-050 the optimization worker")
class OptimizationWorkerIT {

    private static final LocalDate DAY_ONE = LocalDate.parse("2026-10-04");

    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired OptimizationRunStore runs;

    @Test
    @DisplayName("BA-050-T6 a trip that moved after the run froze its input fails the run")
    void aMovedTripClosesTheGate() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID itemId = insertItem(tripId, DAY_ONE);
        UUID runId = queue(owner, tripId, itemId);

        // The first attempt is allowed to finish before anything is changed. Editing the trip while
        // the worker is mid-attempt would be a race this test cannot win at a 20ms poll, and a test
        // that sometimes changes the trip after the gate has already run is a test that sometimes
        // asserts nothing.
        //
        // It used to assert the run was still RUNNING here, and that assertion was correct until
        // BA-051 arrived: in BA-050 passing the gate was not an end state, so the run waited. Now the
        // first attempt goes on to ask for a preview and ends the run - with DATA_INSUFFICIENT, since
        // this fixture stores no forecast. The clause under test is unchanged; what expired is the
        // step that got the run into position for it.
        //
        // So the run is put back to undecided explicitly. That is stronger than the old wait, not
        // weaker: the failure code this test reads afterwards cannot have come from the first
        // attempt, because this statement cleared it.
        awaitJobSettled(runId);
        jdbc.update("UPDATE optimization_runs SET status = 'QUEUED', started_at = NULL,"
                + " completed_at = NULL, failure_code = NULL, failure_message = NULL WHERE id = ?", runId);

        // The edit a user makes while their preview is being computed. Version 1 is what the run froze.
        jdbc.update("UPDATE trips SET version = 2, updated_at = now() WHERE id = ?", tripId);
        redeliver(runId);

        Map<String, Object> run = awaitTerminal(runId);
        assertThat(run.get("status")).isEqualTo("FAILED");
        assertThat(run.get("failure_code")).isEqualTo("TRIP_CHANGED");
        assertThat(run.get("failure_message")).asString().contains("changed");
        // The evidence was frozen before the gate ran: the gate is a question ABOUT the frozen input,
        // so a run that failed it must still carry the deadline it had been working towards.
        assertThat(run.get("expires_at")).isNotNull();
        // And not a fingerprint. That column means the §8 value RunFingerprint computes, whose
        // inputs include a policy hash only the recommendation service's answer carries; a run that
        // never asked cannot have one, and filling it with something else would be the wrong value
        // under the right name.
        assertThat(run.get("data_fingerprint")).isNull();
    }

    @Test
    @DisplayName("BA-050-T6 a trip deleted while the run was in flight takes the run with it, quietly")
    void aDeletedTripTakesItsRunsWithIt() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID itemId = insertItem(tripId, DAY_ONE);
        UUID runId = queue(owner, tripId, itemId);

        // The other way a run's input stops holding, and it does not reach the gate at all:
        // optimization_runs.trip_id cascades, so deleting the trip deletes the run. The worker still
        // has a job for it, and what it must NOT do is dead-letter - deleting a trip is something
        // users do, and an operator alert per deletion would be noise about normal behaviour.
        jdbc.update("DELETE FROM trips WHERE id = ?", tripId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM optimization_runs WHERE id = ?",
                Integer.class, runId)).isZero();
        awaitJobSettled(runId);
        assertThat(jdbc.queryForObject("SELECT status FROM background_jobs WHERE deduplication_key = ?",
                String.class, "optimization:" + runId))
                .as("a run that was deleted is finished work, not a failure to alert on")
                .isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("BA-050-T5 only one worker can carry a run forward, and the loser writes nothing")
    void twoWorkersCannotBothStartTheSameRun() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID itemId = insertItem(tripId, DAY_ONE);
        UUID runId = queue(owner, tripId, itemId);
        awaitJobSettled(runId);
        jdbc.update("UPDATE optimization_runs SET status = 'QUEUED', started_at = NULL,"
                + " completed_at = NULL, failure_code = NULL, failure_message = NULL WHERE id = ?", runId);

        // The transition is the whole of the mutual exclusion: it names the status it is moving FROM,
        // so the second caller updates no row rather than overwriting the first one's start.
        assertThat(runs.transition(runId, OptimizationStatus.QUEUED, OptimizationStatus.RUNNING,
                java.time.Instant.now())).isTrue();
        assertThat(runs.transition(runId, OptimizationStatus.QUEUED, OptimizationStatus.RUNNING,
                java.time.Instant.now()))
                .as("the second worker finds the run already started and does nothing")
                .isFalse();
    }

    @Test
    @DisplayName("BA-050-T4 a job whose lease expired is taken again and the run still ends once")
    void anAbandonedRunIsPickedUpAgain() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID itemId = insertItem(tripId, DAY_ONE);
        UUID runId = queue(owner, tripId, itemId);
        awaitJobSettled(runId);

        // A worker that died mid-attempt, which is a different re-delivery from the one above: the
        // job row is RUNNING with a lapsed lease rather than waiting to be handed out, so it is
        // CLAIM_EXPIRED_LEASE that has to find it and not CLAIM_READY_OR_RETRY.
        jdbc.update("UPDATE trips SET version = 2 WHERE id = ?", tripId);
        // locked_by travels with lease_until: background_jobs_lease_pair_check requires both or
        // neither, so a lapsed lease with no owner is not a state a crashed worker could leave.
        jdbc.update("UPDATE background_jobs SET status = 'RUNNING', completed_at = NULL,"
                + " locked_by = 'worker-that-died', lease_until = now() - interval '1 minute',"
                + " attempt_count = 1 WHERE deduplication_key = ?", "optimization:" + runId);

        // Waits on the ATTEMPT COUNTER, not on the run reaching a terminal status.
        //
        // The run was already terminal when this test wrote the lapsed lease - BA-051's first attempt
        // ends it - so a wait for "the run has settled" now returns before the reclaim has happened
        // and the counter is read at 1. That is how this test failed, and the failure was real: the
        // thing being waited for had stopped being downstream of the thing being tested.
        //
        // The counter reaching 2 IS the evidence that CLAIM_EXPIRED_LEASE found the job, which is the
        // path this case exists for. Waiting on it also keeps the re-take from being skipped
        // silently: if nothing ever reclaims the job, this fails here rather than passing on
        // assertions about a state nothing produced.
        awaitAttempt(runId, 2);
        assertThat(runStatus(runId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM optimization_runs WHERE trip_id = ?",
                Integer.class, tripId))
                .as("a re-taken job finishes the run it was given, and does not create a second one")
                .isOne();
    }

    private UUID queue(SessionService.Bootstrap owner, UUID tripId, UUID itemId) throws Exception {
        String body = mvc.perform(post("/api/v1/trips/" + tripId + "/optimizations")
                        .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("If-Match", "\"1\"")
                        .header("Idempotency-Key", "optimize-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"scope\":\"ITEM\",\"targetItemId\":\"" + itemId
                                + "\",\"inputTripVersion\":1,\"includeCandidates\":false}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(body.replaceFirst("(?s)^.*?\"id\":\"([^\"]+)\".*$", "$1"));
    }

    private Map<String, Object> awaitTerminal(UUID runId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            Map<String, Object> run = jdbc.queryForMap("SELECT status, failure_code, failure_message,"
                    + " data_fingerprint, expires_at FROM optimization_runs WHERE id = ?", runId);
            if (List.of("FAILED", "EXPIRED", "READY").contains(run.get("status"))) {
                return run;
            }
            Thread.sleep(25);
        }
        return fail("the optimization run did not settle within the test deadline");
    }

    /**
     * Waits for the attempt to COMPLETE, and refuses to accept FAILED as "settled".
     *
     * <p>Both are terminal for the job row, so a helper that took either would let a handler which
     * throws on every attempt read as a finished attempt - and every assertion after it would be
     * about a run nothing had processed. This is how the missing unit of work around the evidence
     * read was found: the handler was being refused by JobUnitOfWorkGuard, the job was dead-lettering
     * and the run sat RUNNING, and a helper that accepted FAILED would have hidden all three.
     */
    private void awaitJobSettled(UUID runId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        String status = null;
        while (System.nanoTime() < deadline) {
            status = jdbc.queryForObject("SELECT status FROM background_jobs"
                    + " WHERE deduplication_key = ?", String.class, "optimization:" + runId);
            if ("COMPLETED".equals(status)) {
                return;
            }
            if ("FAILED".equals(status)) {
                fail("the optimization job dead-lettered instead of completing its attempt: "
                        + jdbc.queryForObject("SELECT last_error_code FROM background_jobs"
                                + " WHERE deduplication_key = ?", String.class, "optimization:" + runId));
            }
            Thread.sleep(25);
        }
        fail("the optimization job never settled; last status was " + status);
    }

    /**
     * Waits for the job to have been taken {@code attempts} times.
     *
     * <p>Separate from {@link #awaitTerminal} because they answer different questions, and one of
     * them stopped being a proxy for the other: a run can be terminal while the job that was handed
     * out again has not been picked up yet.
     */
    private void awaitAttempt(UUID runId, int attempts) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        Integer count = null;
        while (System.nanoTime() < deadline) {
            count = jdbc.queryForObject("SELECT attempt_count FROM background_jobs"
                    + " WHERE deduplication_key = ?", Integer.class, "optimization:" + runId);
            if (count != null && count >= attempts) {
                return;
            }
            Thread.sleep(25);
        }
        fail("the job was never taken " + attempts + " times; attempt_count was " + count);
    }

    /** Hands the job back to the queue so the worker runs another attempt on the same run. */
    private void redeliver(UUID runId) {
        jdbc.update("UPDATE background_jobs SET status = 'READY', completed_at = NULL,"
                + " next_attempt_at = now(), lease_until = NULL, locked_by = NULL"
                + " WHERE deduplication_key = ?", "optimization:" + runId);
    }

    private String runStatus(UUID runId) {
        return jdbc.queryForObject("SELECT status FROM optimization_runs WHERE id = ?", String.class,
                runId);
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
        return UUID.fromString(created.replaceFirst("(?s)^.*?\"id\":\"([^\"]+)\".*$", "$1"));
    }

    private UUID insertItem(UUID tripId, LocalDate date) {
        UUID placeId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO places (id, canonical_name, category_code, region_code, status,"
                + " created_at, updated_at) VALUES (?, '최적화 worker 장소', 'HS', '11', 'ACTIVE', ?, ?)",
                placeId, now, now);
        jdbc.update("INSERT INTO trip_items (id, trip_id, place_id, trip_date, position, start_time,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, 0, CAST('09:00:00' AS time), ?, ?)",
                id, tripId, placeId, java.sql.Date.valueOf(date), now, now);
        return id;
    }
}
