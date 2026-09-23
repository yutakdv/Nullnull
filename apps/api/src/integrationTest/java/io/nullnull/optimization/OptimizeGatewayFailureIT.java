package io.nullnull.optimization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.nullnull.catalog.application.CatalogHoursQuery;
import io.nullnull.catalog.application.CatalogHoursQuery.CatalogOpeningWindow;
import io.nullnull.identity.application.SessionService;
import io.nullnull.recommendation.application.RecommendationGateway;
import io.nullnull.recommendation.application.RecommendationUnavailableException;
import io.nullnull.recommendation.domain.PolicyPins;
import io.nullnull.recommendation.domain.explanation.ExplanationRenderResponse;
import io.nullnull.recommendation.domain.item.ItemProposalOut;
import io.nullnull.recommendation.domain.item.ItemProposeRequest;
import io.nullnull.recommendation.domain.item.ItemProposeResponse;
import io.nullnull.recommendation.domain.item.TemporalCandidateIn;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * #252: what an {@code apps/ai} that does not give a usable policy answer does to an APPLY and to the
 * optimization worker, through the REAL gateway over a real socket (the #250 device,
 * {@code TripDraftPreviewGatewayIT}).
 *
 * <p>Only {@code policy()} goes over the wire. The gateway is a spy of the real bean, so proposing and
 * explaining are answered in-process the way {@code OptimizeDecisionIT} answers them - the run reaches
 * READY through the real pipeline, and then the one call this is about meets a real transport failure
 * or a real answer outside the contract. A mocked {@code policy()} could only show how the service
 * maps an exception it was handed; this shows which exception a dropped connection, a 5xx, a stall, a
 * 422 and an unparsable body actually become.
 */
// The read timeout is shortened so a stall outlasts it without the suite waiting the production value.
// The owner lock timeout is shortened with it, keeping production's proportion: 3s there is shorter than a
// policy check's two 5s attempts, so a check made under the owner's lock outlasts it (#271, BA-052-T19).
@SpringBootTest(properties = {"nullnull.catalog.public-enabled=true",
        "nullnull.capabilities.optimization=true", "nullnull.jobs.enabled=true",
        "nullnull.jobs.poll-interval=PT0.02S", "nullnull.jobs.retry-backoff=PT1S",
        "nullnull.jobs.max-retry-backoff=PT1S", "nullnull.ai.read-timeout=PT1S",
        "nullnull.idempotency.lock-timeout=PT0.5S"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("#252 an apps/ai that cannot give a usable policy answer")
class OptimizeGatewayFailureIT {

    enum Mode { OK, DROP_CONNECTION, UNAVAILABLE_503, STALL, REJECT_422, BAD_HASH }

    private static final AtomicReference<Mode> MODE = new AtomicReference<>(Mode.OK);
    /** How many policy requests the stub has started to stall, so a test can act while one is held. */
    private static final java.util.concurrent.atomic.AtomicInteger STALLED = new java.util.concurrent.atomic.AtomicInteger();
    private static final HttpServer STUB = startStub();

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate DAY_ONE = LocalDate.parse("2026-10-04");
    private static final LocalDate DAY_TWO = LocalDate.parse("2026-10-05");
    private static final LocalTime AT_NINE = LocalTime.of(9, 0);
    private static final String FORECAST_SOURCE = "KTO_CONCENTRATION_FORECAST";
    private static final BigDecimal CROWDED = new BigDecimal("80.0000");
    private static final BigDecimal QUIET = new BigDecimal("20.0000");

    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean RecommendationGateway recommendations;
    @MockitoBean CatalogHoursQuery hours;

    private final List<UUID> trips = new ArrayList<>();
    private final List<UUID> places = new ArrayList<>();
    private final List<UUID> snapshotSets = new ArrayList<>();
    private final List<UUID> collectorRuns = new ArrayList<>();
    private final List<UUID> runIds = new ArrayList<>();
    private ListAppender<ILoggingEvent> logs;

    @DynamicPropertySource
    static void recommendationServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("nullnull.ai.base-url", () -> "http://127.0.0.1:" + STUB.getAddress().getPort());
    }

    @AfterAll
    static void stopStub() {
        STUB.stop(0);
    }

    @BeforeEach
    void listen() {
        MODE.set(Mode.OK);
        logs = new ListAppender<>();
        logs.start();
        rootLogger().addAppender(logs);
    }

    /** Only rows this class created, each named by an id it minted (AGENTS.md rule 6). */
    @AfterEach
    void removeOnlyOwnFixtures() {
        rootLogger().detachAppender(logs);
        MODE.set(Mode.OK);
        for (UUID runId : runIds) {
            jdbc.update("DELETE FROM background_jobs WHERE deduplication_key = ?", "optimization:" + runId);
        }
        for (UUID runId : runIds) {
            jdbc.update("DELETE FROM optimization_decisions WHERE run_id = ?", runId);
        }
        for (UUID tripId : trips) {
            jdbc.update("DELETE FROM trips WHERE id = ?", tripId);
        }
        for (UUID set : snapshotSets) {
            jdbc.update("DELETE FROM crowd_snapshots WHERE snapshot_set_id = ?", set);
        }
        for (UUID set : snapshotSets) {
            jdbc.update("DELETE FROM snapshot_sets WHERE id = ?", set);
        }
        for (UUID run : collectorRuns) {
            jdbc.update("DELETE FROM collector_runs WHERE id = ?", run);
        }
        for (UUID placeId : places) {
            jdbc.update("DELETE FROM place_localizations WHERE place_id = ?", placeId);
        }
        for (UUID placeId : places) {
            jdbc.update("DELETE FROM places WHERE id = ?", placeId);
        }
    }

    // ------------------------------------------------------------------ APPLY

    @Test
    @DisplayName("BA-052-T16 BA-052-T18 an apps/ai that does not answer during APPLY is 503 APPLY_FAILED, writes nothing, and the same key applies once it is back")
    void anUnansweredPolicyCheckIsARetryableApplyFailure() throws Exception {
        for (Mode mode : List.of(Mode.DROP_CONNECTION, Mode.UNAVAILABLE_503)) {
            Fixture fixture = fixture();
            UUID runId = readyRun(fixture);
            UUID proposalId = proposalOf(runId);
            String key = "decide-" + UUID.randomUUID();

            MODE.set(mode);
            decide(fixture, runId, proposalId, key)
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("APPLY_FAILED"))
                    .andExpect(jsonPath("$.retryable").value(true));
            assertNothingWritten(fixture, runId, mode);

            // The contract's promise for this code: nothing was written, so the identical request is the
            // right one to send again - and it applies once the service answers.
            MODE.set(Mode.OK);
            decide(fixture, runId, proposalId, key).andExpect(status().isOk());
            assertThat(tripVersion(fixture.tripId())).as(mode.name()).isEqualTo(2L);
        }
        assertNoUnhandledFailure();
    }

    @Test
    @DisplayName("BA-052-T25 an APPLY whose policy lookup fails leaves no reservation of its key behind")
    void aFailedLookupFreesItsKey() throws Exception {
        Fixture fixture = fixture();
        UUID runId = readyRun(fixture);
        UUID proposalId = proposalOf(runId);
        String key = "decide-" + UUID.randomUUID();

        // The key is reserved BEFORE the lookup now (#340), so a lookup that fails has a reservation to give
        // back. Left to its lease instead, the same key sent again would wait out the lease - seconds of an
        // APPLY the contract calls retryable - before it could run.
        MODE.set(Mode.DROP_CONNECTION);
        decide(fixture, runId, proposalId, key)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("APPLY_FAILED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE idempotency_key = ?",
                Integer.class, key)).as("no reservation left for the key").isZero();
        assertNothingWritten(fixture, runId, Mode.DROP_CONNECTION);
        assertNoUnhandledFailure();
    }

    @Test
    @DisplayName("BA-052-T16 an apps/ai that stalls during APPLY is 503 APPLY_FAILED once the read timeout passes")
    void aStalledPolicyCheckIsARetryableApplyFailure() throws Exception {
        Fixture fixture = fixture();
        UUID runId = readyRun(fixture);
        UUID proposalId = proposalOf(runId);

        MODE.set(Mode.STALL);
        long started = System.nanoTime();
        decide(fixture, runId, proposalId, "decide-" + UUID.randomUUID())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("APPLY_FAILED"))
                .andExpect(jsonPath("$.retryable").value(true));
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertNothingWritten(fixture, runId, Mode.STALL);
        // Two policy attempts, each bounded by the one-second read timeout. The check runs before the
        // decision's guard (#271), so none of this is time the owner's lock is held - BA-052-T19.
        assertThat(elapsed).as("elapsed ms").isBetween(1_500L, 6_000L);
        assertNoUnhandledFailure();
    }

    @Test
    @DisplayName("BA-052-T17 an apps/ai that answers outside its contract during APPLY is 500 INTERNAL_ERROR, not retryable, and writes nothing")
    void anUnusablePolicyAnswerIsNotARetryableFailure() throws Exception {
        for (Mode mode : List.of(Mode.REJECT_422, Mode.BAD_HASH)) {
            MODE.set(Mode.OK);
            Fixture fixture = fixture();
            UUID runId = readyRun(fixture);
            UUID proposalId = proposalOf(runId);

            MODE.set(mode);
            decide(fixture, runId, proposalId, "decide-" + UUID.randomUUID())
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.retryable").value(false));
            assertNothingWritten(fixture, runId, mode);
        }
        assertNoUnhandledFailure();
    }

    @Test
    @DisplayName("BA-052-T19 while apps/ai stalls during an APPLY, the owner's other requests are still answered")
    void aStalledPolicyCheckDoesNotHoldTheOwner() throws Exception {
        // Measured before #271 at production values: the APPLY took 10s, and the same owner's getTrip and
        // createOptimization were each 500 INTERNAL_ERROR after the 3s lock timeout - resolving a session
        // locks the owner, and the check was holding that lock while it waited for apps/ai.
        answerFromTheRequest();
        Fixture fixture = fixture();
        UUID runId = readyRun(fixture);
        UUID proposalId = proposalOf(runId);
        int stalledBefore = STALLED.get();
        MODE.set(Mode.STALL);
        java.util.concurrent.ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<org.springframework.test.web.servlet.MvcResult> apply = pool.submit(
                    () -> decide(fixture, runId, proposalId, "decide-" + UUID.randomUUID()).andReturn());
            org.awaitility.Awaitility.await().atMost(10, TimeUnit.SECONDS)
                    .pollInterval(10, TimeUnit.MILLISECONDS)
                    .until(() -> STALLED.get() > stalledBefore);

            mvc.perform(get("/api/v1/trips/" + fixture.tripId()).cookie(cookie(fixture.owner())))
                    .andExpect(status().isOk());
            assertThat(apply.isDone()).as("the read was answered while the APPLY was still waiting").isFalse();

            org.springframework.test.web.servlet.MvcResult applied = apply.get(30, TimeUnit.SECONDS);
            assertThat(applied.getResponse().getStatus()).isEqualTo(503);
        } finally {
            pool.shutdownNow();
        }
        assertNothingWritten(fixture, runId, Mode.STALL);
        assertNoUnhandledFailure();
    }

    @Test
    @DisplayName("BA-052-T20 a replay of an APPLY that was answered does not ask apps/ai")
    void aReplayOfAnAnsweredApplyNeedsNoAppsAi() throws Exception {
        answerFromTheRequest();
        Fixture fixture = fixture();
        UUID runId = readyRun(fixture);
        UUID proposalId = proposalOf(runId);
        String key = "decide-" + UUID.randomUUID();
        decide(fixture, runId, proposalId, key).andExpect(status().isOk());

        long before = policyCalls();
        MODE.set(Mode.DROP_CONNECTION);
        decide(fixture, runId, proposalId, key).andExpect(status().isOk());
        assertThat(policyCalls() - before).as("the stored answer is replayed without apps/ai").isZero();
        assertThat(tripVersion(fixture.tripId())).as("applied once").isEqualTo(2L);
    }

    @Test
    @DisplayName("BA-052-T21 a decision on a run no longer offering a preview is refused without asking apps/ai")
    void aRunThatCannotBeDecidedIsRefusedWithoutAppsAi() throws Exception {
        // #271 moved the policy check in front of the guard. The run the command will refuse is refused
        // there too, as before: an apps/ai outage must not turn "already decided" into "try again".
        answerFromTheRequest();
        Fixture fixture = fixture();
        UUID runId = readyRun(fixture);
        UUID proposalId = proposalOf(runId);
        decide(fixture, runId, proposalId, "decide-" + UUID.randomUUID()).andExpect(status().isOk());

        long before = policyCalls();
        MODE.set(Mode.DROP_CONNECTION);
        mvc.perform(post("/api/v1/optimizations/" + runId + "/decisions")
                        .cookie(cookie(fixture.owner()))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", fixture.owner().csrf.token)
                        .header("If-Match", "\"2\"")
                        .header("Idempotency-Key", "decide-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"proposalId\":\"" + proposalId + "\",\"decision\":\"APPLY\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATA_CHANGED"));
        assertThat(policyCalls() - before).as("a run that cannot be decided needs no policy").isZero();
    }

    private long policyCalls() {
        return org.mockito.Mockito.mockingDetails(recommendations).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("policy")).count();
    }

    // ------------------------------------------------------------------ worker

    @Test
    @DisplayName("BA-051-T18 a run whose policy answer is outside the contract fails its job on the first attempt")
    void anUnusableAnswerIsNotRetried() throws Exception {
        answerFromTheRequest();
        Fixture fixture = fixture();
        MODE.set(Mode.BAD_HASH);
        UUID runId = queue(fixture);
        org.awaitility.Awaitility.await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(50, TimeUnit.MILLISECONDS)
                .until(() -> "FAILED".equals(jobColumn(runId, "status")));
        assertThat(jobColumn(runId, "attempt_count"))
                .as("the same request gets the same answer, so a second attempt is a wasted one")
                .isEqualTo("1");
        assertThat(jobColumn(runId, "last_error_code")).isEqualTo("RECOMMENDATION_UNUSABLE");
    }

    @Test
    @DisplayName("BA-005-T6 an optimize-item job whose payload names no run is dead-lettered on its first attempt, not retried")
    void anUnreadablePayloadIsNotRetried() {
        // Registered as a run id so the cleanup, which removes jobs by their deduplication key, takes it.
        UUID key = UUID.randomUUID();
        runIds.add(key);
        java.sql.Timestamp now = java.sql.Timestamp.from(java.time.Instant.now());
        jdbc.update("""
                INSERT INTO background_jobs (id, type, deduplication_key, status, payload_reference,
                    attempt_count, max_attempts, next_attempt_at, created_at)
                VALUES (?, 'optimize-item', ?, 'READY', '{"runId":"not-a-run"}'::jsonb, 0, 3, ?, ?)
                """, UUID.randomUUID(), "optimization:" + key, now, now);
        org.awaitility.Awaitility.await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(50, TimeUnit.MILLISECONDS)
                .until(() -> "FAILED".equals(jobColumn(key, "status")));
        assertThat(jobColumn(key, "last_error_code")).isEqualTo("INVALID_JOB_PAYLOAD");
        assertThat(jobColumn(key, "attempt_count")).as("the next attempt would read the same payload").isEqualTo("1");
    }

    @Test
    @DisplayName("BA-051-T18 a run whose proposal or explanation is unusable fails its job on the first attempt too")
    void everyCallOfTheRunIsJudgedTheSameWay() throws Exception {
        // The handler makes three calls, and only policy() goes over the wire above. The other two are
        // thrown in-process, which is enough here: which exception a real failure becomes is the
        // gateway's (TripDraftPreviewGatewayIT, and policy() above); this is about the handler not
        // having a call site that forgets to say the failure is final.
        for (String call : List.of("proposeItem", "renderExplanation")) {
            answerFromTheRequest();
            RecommendationUnavailableException unusable =
                    new RecommendationUnavailableException("recommendation response outside the contract", false, null);
            if (call.equals("proposeItem")) {
                org.mockito.Mockito.doThrow(unusable).when(recommendations).proposeItem(any());
            } else {
                org.mockito.Mockito.doThrow(unusable).when(recommendations).renderExplanation(any());
            }
            UUID runId = queue(fixture());
            org.awaitility.Awaitility.await().atMost(30, TimeUnit.SECONDS)
                    .pollInterval(50, TimeUnit.MILLISECONDS)
                    .until(() -> "FAILED".equals(jobColumn(runId, "status")));
            assertThat(jobColumn(runId, "attempt_count")).as(call).isEqualTo("1");
            assertThat(jobColumn(runId, "last_error_code")).as(call).isEqualTo("RECOMMENDATION_UNUSABLE");
        }
    }

    @Test
    @DisplayName("a run whose policy call went unanswered is retried, and reaches READY once apps/ai answers")
    void anUnansweredCallIsRetried() throws Exception {
        answerFromTheRequest();
        Fixture fixture = fixture();
        MODE.set(Mode.DROP_CONNECTION);
        UUID runId = queue(fixture);
        org.awaitility.Awaitility.await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(20, TimeUnit.MILLISECONDS)
                .until(() -> "RETRY".equals(jobColumn(runId, "status")));
        assertThat(jobColumn(runId, "last_error_code")).isEqualTo("RECOMMENDATION_UNAVAILABLE");
        MODE.set(Mode.OK);
        org.awaitility.Awaitility.await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(50, TimeUnit.MILLISECONDS)
                .until(() -> "READY".equals(runColumn(runId, "status")));
    }

    // ------------------------------------------------------------------ dead letter (#261)

    @Test
    @DisplayName("BA-051-T23 a run whose job ends on an answer outside the contract ends FAILED as INTERNAL_ERROR, not retryable")
    void aRunEndedByAnUnusableAnswerFailsAsAnInternalError() throws Exception {
        answerFromTheRequest();
        Fixture fixture = fixture();
        MODE.set(Mode.BAD_HASH);
        UUID runId = queue(fixture);
        awaitRun(runId, "FAILED");
        assertThat(jobColumn(runId, "status")).isEqualTo("FAILED");
        assertThat(jobColumn(runId, "last_error_code")).isEqualTo("RECOMMENDATION_UNUSABLE");
        assertPublishedFailure(fixture, runId, "INTERNAL_ERROR", false);
    }

    @Test
    @DisplayName("BA-051-T24 a run whose every attempt met an apps/ai that did not answer ends FAILED as RECOMMENDATION_UNAVAILABLE, retryable")
    void aRunWhoseAttemptsAllWentUnansweredFailsAsUnavailable() throws Exception {
        answerFromTheRequest();
        Fixture fixture = fixture();
        MODE.set(Mode.UNAVAILABLE_503);
        UUID runId = queue(fixture);
        awaitRun(runId, "FAILED");
        assertThat(jobColumn(runId, "attempt_count")).as("every attempt was spent")
                .isEqualTo(jobColumn(runId, "max_attempts"));
        assertThat(jobColumn(runId, "last_error_code")).isEqualTo("RECOMMENDATION_UNAVAILABLE");
        assertPublishedFailure(fixture, runId, "RECOMMENDATION_UNAVAILABLE", true);
    }

    @Test
    @DisplayName("BA-051-T25 a run whose job was abandoned with no attempt left ends FAILED as INTERNAL_ERROR")
    void aRunWhoseJobWasAbandonedFailsAsAnInternalError() throws Exception {
        answerFromTheRequest();
        Fixture fixture = fixture();
        MODE.set(Mode.DROP_CONNECTION);
        UUID runId = queue(fixture);
        org.awaitility.Awaitility.await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(20, TimeUnit.MILLISECONDS)
                .until(() -> "RETRY".equals(jobColumn(runId, "status")));
        // What a worker that died mid-attempt leaves once its attempts are spent: RUNNING under a lease
        // nobody renews. Built from the RETRY row while its back-off holds it, so no worker has it; the
        // lapsed lease is far in the past so it is lapsed whatever clock the application reads.
        int abandoned = jdbc.update("UPDATE background_jobs SET status = 'RUNNING',"
                + " attempt_count = max_attempts, locked_by = 'a-worker-that-died',"
                + " lease_until = TIMESTAMPTZ '2000-01-01 00:00:00+00'"
                + " WHERE deduplication_key = ? AND status = 'RETRY'", "optimization:" + runId);
        assertThat(abandoned).as("the job was still waiting out its back-off").isOne();
        assertThat(runColumn(runId, "status")).isEqualTo("RUNNING");
        awaitRun(runId, "FAILED");
        assertThat(jobColumn(runId, "status")).isEqualTo("FAILED");
        assertThat(jobColumn(runId, "last_error_code")).isEqualTo("LEASE_EXPIRED");
        assertPublishedFailure(fixture, runId, "INTERNAL_ERROR", false);
    }

    // ------------------------------------------------------------------ support

    private void awaitRun(UUID runId, String status) {
        org.awaitility.Awaitility.await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(50, TimeUnit.MILLISECONDS)
                .until(() -> status.equals(runColumn(runId, "status")));
    }

    /** What the client is told, through getOptimization - the code alone would not show retryable. */
    private void assertPublishedFailure(Fixture fixture, UUID runId, String code, boolean retryable)
            throws Exception {
        mvc.perform(get("/api/v1/optimizations/" + runId).cookie(cookie(fixture.owner())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failure.code").value(code))
                .andExpect(jsonPath("$.failure.retryable").value(retryable));
    }

    private void assertNothingWritten(Fixture fixture, UUID runId, Mode mode) {
        assertThat(tripVersion(fixture.tripId())).as(mode + " trip version").isEqualTo(1L);
        assertThat(decisionCount(runId)).as(mode + " decisions").isZero();
        assertThat(runColumn(runId, "status")).as(mode + " run").isEqualTo("READY");
    }

    private void assertNoUnhandledFailure() {
        assertThat(logs.list).as("an expected outage is not an unhandled server failure")
                .noneMatch(event -> event.getFormattedMessage().startsWith("unhandled exception"));
    }

    private record Fixture(UUID tripId, UUID itemId, UUID placeId, SessionService.Bootstrap owner) {
    }

    private Fixture fixture() throws Exception {
        SessionService.Bootstrap owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID placeId = insertPlace();
        UUID itemId = insertItem(tripId, placeId);
        insertForecasts(placeId);
        return new Fixture(tripId, itemId, placeId, owner);
    }

    /** Drives the BA-051 pipeline until the run really is READY, with policy() answered over the socket. */
    private UUID readyRun(Fixture fixture) throws Exception {
        answerFromTheRequest();
        UUID runId = queue(fixture);
        org.awaitility.Awaitility.await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(50, TimeUnit.MILLISECONDS)
                .until(() -> List.of("READY", "FAILED").contains(runColumn(runId, "status")));
        assertThat(runColumn(runId, "status")).as("a preview the pipeline actually produced").isEqualTo("READY");
        return runId;
    }

    private void answerFromTheRequest() {
        String version = PolicyPins.V1.policyVersion();
        String hash = PolicyPins.V1.policyHash();
        String pipeline = PolicyPins.V1.pipelineVersion();
        org.mockito.Mockito.when(hours.windowsFor(any(), any(), any(), any())).thenAnswer(call ->
                Map.of(DAY_ONE, openAllDay(), DAY_TWO, openAllDay()));
        org.mockito.Mockito.doAnswer(call -> {
            ItemProposeRequest request = call.getArgument(0);
            TemporalCandidateIn candidate = request.candidates().stream()
                    .filter(offered -> offered.date().equals(DAY_TWO)).findFirst()
                    .orElseThrow(() -> new AssertionError("no candidate for " + DAY_TWO));
            ItemProposalOut proposal = new ItemProposalOut(1, candidate.date(),
                    candidate.effectiveStartTime(request.target().startTime()),
                    DAY_ONE.atStartOfDay(SEOUL).toInstant(), DAY_TWO.atStartOfDay(SEOUL).toInstant(),
                    new BigDecimal("0.480000"),
                    candidate.beforeValue().subtract(candidate.afterValue()),
                    new BigDecimal("0.600000"), new BigDecimal("0.000000"),
                    candidate.beforeSnapshotId(), candidate.afterSnapshotId(), lockChecksFor(request));
            return new ItemProposeResponse(version, hash, pipeline, ItemProposeResponse.Outcome.PROPOSALS,
                    List.of(proposal), List.of(), 1, Map.of());
        }).when(recommendations).proposeItem(any());
        org.mockito.Mockito.doAnswer(call -> new ExplanationRenderResponse(version, hash, pipeline,
                "이 날이 덜 붐빕니다.", "TEMPLATE")).when(recommendations).renderExplanation(any());
    }

    private static CatalogOpeningWindow openAllDay() {
        return new CatalogOpeningWindow(CatalogOpeningWindow.State.OPEN, LocalTime.of(8, 0),
                LocalTime.of(20, 0));
    }

    private UUID queue(Fixture fixture) throws Exception {
        String created = mvc.perform(post("/api/v1/trips/" + fixture.tripId() + "/optimizations")
                        .cookie(cookie(fixture.owner()))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", fixture.owner().csrf.token)
                        .header("If-Match", "\"1\"")
                        .header("Idempotency-Key", "optimize-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"scope\":\"ITEM\",\"targetItemId\":\"" + fixture.itemId()
                                + "\",\"inputTripVersion\":1,\"includeCandidates\":false,"
                                + "\"objective\":\"REDUCE_CROWD\"}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        UUID runId = UUID.fromString(created.replaceFirst("(?s)^.*?\"id\":\"([^\"]+)\".*$", "$1"));
        runIds.add(runId);
        return runId;
    }

    private UUID proposalOf(UUID runId) {
        return jdbc.queryForObject("SELECT id FROM optimization_proposals WHERE run_id = ?", UUID.class, runId);
    }

    private ResultActions decide(Fixture fixture, UUID runId, UUID proposalId, String key) throws Exception {
        return mvc.perform(post("/api/v1/optimizations/" + runId + "/decisions")
                .cookie(cookie(fixture.owner()))
                .header("Origin", "http://localhost:5173")
                .header("X-CSRF-Token", fixture.owner().csrf.token)
                .header("If-Match", "\"1\"")
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content("{\"proposalId\":\"" + proposalId + "\",\"decision\":\"APPLY\"}"));
    }

    private UUID createTrip(SessionService.Bootstrap owner) throws Exception {
        String created = mvc.perform(post("/api/v1/trips")
                        .cookie(cookie(owner))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "trip-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"startDate\":\"" + DAY_ONE + "\",\"endDate\":\"" + DAY_TWO + "\","
                                + "\"timezone\":\"Asia/Seoul\",\"planningLevel\":\"NOTHING\","
                                + "\"interests\":[]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID tripId = UUID.fromString(created.replaceFirst("(?s)^.*?\"id\":\"([^\"]+)\".*$", "$1"));
        trips.add(tripId);
        return tripId;
    }

    private UUID insertPlace() {
        UUID placeId = UUID.randomUUID();
        places.add(placeId);
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO places (id, canonical_name, category_code, latitude, longitude,"
                + " region_code, status, created_at, updated_at)"
                + " VALUES (?, '#252 대상 장소', 'A0101', 37.566535, 126.978001, '1', 'ACTIVE', ?, ?)",
                placeId, now, now);
        jdbc.update("INSERT INTO place_localizations (id, place_id, locale, name, address, updated_at)"
                + " VALUES (?, ?, 'ko-KR', '#252 대상 장소', '서울시 종로구', ?)",
                UUID.randomUUID(), placeId, now);
        return placeId;
    }

    private UUID insertItem(UUID tripId, UUID placeId) {
        UUID itemId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO trip_items (id, trip_id, place_id, trip_date, position, start_time,"
                + " duration_minutes, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, 0, CAST(? AS time), 90, ?, ?)",
                itemId, tripId, placeId, java.sql.Date.valueOf(DAY_ONE), AT_NINE.toString(), now, now);
        return itemId;
    }

    private void insertForecasts(UUID placeId) {
        Instant fetchedAt = Instant.now();
        Instant staleAt = fetchedAt.plus(Duration.ofHours(12));
        long sourceVersion = jdbc.queryForObject(
                "SELECT current_revision FROM source_registry WHERE code = ?", Long.class, FORECAST_SOURCE);
        UUID collectorRun = UUID.randomUUID();
        collectorRuns.add(collectorRun);
        jdbc.update("INSERT INTO collector_runs (id, source_code, status, trigger_type,"
                + " records_received, records_accepted, records_rejected, schema_version, started_at,"
                + " finished_at) VALUES (?, ?, 'COMPLETED', 'READ_THROUGH', 2, 2, 0,"
                + " 'kto-tats-cnctr-rate-v4.1', ?, ?)",
                collectorRun, FORECAST_SOURCE, Timestamp.from(fetchedAt), Timestamp.from(fetchedAt));
        UUID set = UUID.randomUUID();
        snapshotSets.add(set);
        String issue = "i252-" + set;
        jdbc.update("INSERT INTO snapshot_sets (id, source_code, source_registry_version,"
                + " collector_run_id, source_state, forecast_issue_id, comparison_group_id,"
                + " observed_at, fetched_at, stale_at, normalization_version, created_at)"
                + " VALUES (?, ?, ?, ?, 'FORECAST', ?, ?, NULL, ?, ?, 'kto-tats-cnctr-rate-v4.1', ?)",
                set, FORECAST_SOURCE, sourceVersion, collectorRun, issue, issue,
                Timestamp.from(fetchedAt), Timestamp.from(staleAt), Timestamp.from(fetchedAt));
        insertSnapshot(set, placeId, sourceVersion, DAY_ONE, CROWDED, issue, fetchedAt, staleAt);
        insertSnapshot(set, placeId, sourceVersion, DAY_TWO, QUIET, issue, fetchedAt, staleAt);
    }

    private void insertSnapshot(UUID set, UUID placeId, long sourceVersion, LocalDate day,
            BigDecimal value, String issue, Instant fetchedAt, Instant staleAt) {
        Instant targetAt = day.atStartOfDay(SEOUL).toInstant();
        jdbc.update("INSERT INTO crowd_snapshots (id, snapshot_set_id, source_code,"
                + " source_registry_version, place_id, source_state, observed_at, target_at,"
                + " fetched_at, stale_at, metric_code, value, unit, ordinal_level, confidence,"
                + " quality_flags, forecast_issue_id, comparison_group_id, normalization_version,"
                + " observed_at_skew_seconds, scope, scope_label, mapping_type, fallback_used,"
                + " created_at) VALUES (?, ?, ?, ?, ?, 'FORECAST', NULL, ?, ?, ?,"
                + " 'KTO_RELATIVE_CONCENTRATION_INDEX', ?, 'relative-index', NULL, NULL, '[]'::jsonb,"
                + " ?, ?, 'kto-tats-cnctr-rate-v4.1', NULL, 'PLACE', '#252 fixture', 'DIRECT',"
                + " false, ?)",
                UUID.randomUUID(), set, FORECAST_SOURCE, sourceVersion, placeId,
                Timestamp.from(targetAt), Timestamp.from(fetchedAt), Timestamp.from(staleAt), value,
                issue, issue, Timestamp.from(fetchedAt));
    }

    private int decisionCount(UUID runId) {
        return jdbc.queryForObject("SELECT count(*) FROM optimization_decisions WHERE run_id = ?",
                Integer.class, runId);
    }

    private long tripVersion(UUID tripId) {
        return jdbc.queryForObject("SELECT version FROM trips WHERE id = ?", Long.class, tripId);
    }

    private String runColumn(UUID runId, String column) {
        Object value = jdbc.queryForMap("SELECT * FROM optimization_runs WHERE id = ?", runId).get(column);
        return value == null ? null : value.toString();
    }

    private String jobColumn(UUID runId, String column) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM background_jobs WHERE deduplication_key = ?", "optimization:" + runId);
        if (rows.isEmpty()) {
            return null;
        }
        Object value = rows.get(0).get(column);
        return value == null ? null : value.toString();
    }

    private static Cookie cookie(SessionService.Bootstrap owner) {
        return new Cookie("__Host-nullnull_session", owner.cookie);
    }

    private static Map<String, Boolean> lockChecksFor(ItemProposeRequest request) {
        Map<String, Boolean> checks = new java.util.LinkedHashMap<>();
        request.locks().forEach(lock -> checks.put(lock.type().name(), true));
        return checks;
    }

    private static Logger rootLogger() {
        return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/v1/policy", OptimizeGatewayFailureIT::respond);
            // STALL holds its handler past the client's timeout; on the default single dispatcher thread
            // the retry would queue behind it.
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("cannot start the apps/ai stub", exception);
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        String policy = """
                {"policyVersion":"%s","policyHash":"%s","pipelineVersion":"%s","serviceVersion":"stub"}"""
                .formatted(PolicyPins.V1.policyVersion(), PolicyPins.V1.policyHash(),
                        PolicyPins.V1.pipelineVersion());
        switch (MODE.get()) {
            case OK -> send(exchange, 200, policy);
            // What a crashed or restarting container looks like from here: the socket answers, then nothing.
            case DROP_CONNECTION -> exchange.close();
            // apps/ai, or the load balancer in front of it during a rollout, saying it cannot answer now.
            case UNAVAILABLE_503 -> send(exchange, 503, "{\"detail\":\"restarting\"}");
            case STALL -> {
                STALLED.incrementAndGet();
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
                OutputStream out = exchange.getResponseBody();
                out.write("{\"policyVersion\":".getBytes(StandardCharsets.UTF_8));
                out.flush();
                try {
                    Thread.sleep(3_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                exchange.close();
            }
            case REJECT_422 -> send(exchange, 422, "{\"detail\":[{\"msg\":\"rejected\"}]}");
            // Parses as JSON and breaks the contract: a policy hash is a SHA-256 hex digest.
            case BAD_HASH -> send(exchange, 200, policy.replace(PolicyPins.V1.policyHash(), "not-a-digest"));
        }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
