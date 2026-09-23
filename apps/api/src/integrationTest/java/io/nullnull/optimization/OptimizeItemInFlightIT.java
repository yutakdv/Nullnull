package io.nullnull.optimization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.catalog.application.CatalogHoursQuery;
import io.nullnull.catalog.application.CatalogHoursQuery.CatalogOpeningWindow;
import io.nullnull.identity.application.SessionService;
import io.nullnull.recommendation.application.RecommendationGateway;
import io.nullnull.recommendation.application.RecommendationUnavailableException;
import io.nullnull.recommendation.application.RunFingerprint;
import io.nullnull.recommendation.domain.PolicyDescriptor;
import io.nullnull.recommendation.domain.PolicyPins;
import io.nullnull.recommendation.domain.explanation.ExplanationRenderResponse;
import io.nullnull.recommendation.domain.item.ItemProposalOut;
import io.nullnull.recommendation.domain.item.ItemProposeRequest;
import io.nullnull.recommendation.domain.item.ItemProposeResponse;
import io.nullnull.recommendation.domain.item.TemporalCandidateIn;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * BA-051 (#340): what a run in flight does when the trip it froze, or its own deadline, moves while it
 * computes.
 *
 * <p>Every hook sits inside a mocked {@code apps/ai} answer. Those run on the job worker's thread with no
 * transaction open (BA-051-T4), after the run froze its input and before it publishes - the window this
 * file is about. The hooks write with plain SQL, which commits on its own, so what the handler meets
 * afterwards is exactly a concurrent writer's committed change.
 *
 * <p>The clock is the system clock, as in {@code OptimizeItemIT}. A deadline is moved by writing
 * {@code expires_at}, never by moving a clock: the job's lease is measured on the same clock, and pushing
 * it past a fifteen-minute preview would expire the lease first and make the run end for that reason.
 */
@SpringBootTest(properties = {"nullnull.catalog.public-enabled=true",
        "nullnull.capabilities.optimization=true", "nullnull.jobs.enabled=true",
        "nullnull.jobs.poll-interval=PT0.02S", "nullnull.jobs.retry-backoff=PT1S",
        "nullnull.jobs.max-retry-backoff=PT1S"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-051 a run in flight when its trip or its deadline moves")
class OptimizeItemInFlightIT {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate DAY_ONE = LocalDate.parse("2026-10-04");
    private static final LocalDate DAY_TWO = LocalDate.parse("2026-10-05");
    private static final LocalTime AT_NINE = LocalTime.of(9, 0);
    private static final String FORECAST_SOURCE = "KTO_CONCENTRATION_FORECAST";
    private static final String NORMALIZATION = "kto-tats-cnctr-rate-v4.1";
    private static final BigDecimal CROWDED = new BigDecimal("80.0000");
    private static final BigDecimal QUIET = new BigDecimal("20.0000");

    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @MockitoBean RecommendationGateway recommendations;
    @MockitoBean CatalogHoursQuery hours;

    private final List<UUID> trips = new ArrayList<>();
    private final List<UUID> places = new ArrayList<>();
    private final List<UUID> snapshotSets = new ArrayList<>();
    private final List<UUID> collectorRuns = new ArrayList<>();
    private final List<UUID> runIds = new ArrayList<>();

    /** How many times the handler asked {@code apps/ai} for a proposal, across every attempt. */
    private final AtomicInteger proposeCalls = new AtomicInteger();

    /** Only this class's rows, each named by an id it created (AGENTS.md rule 6), in the order the references point. */
    @AfterEach
    void removeOnlyOwnFixtures() {
        for (UUID runId : runIds) {
            jdbc.update("DELETE FROM background_jobs WHERE deduplication_key = ?", "optimization:" + runId);
        }
        // The trip takes its items, its runs, their frozen-set rows and their proposals with it (V013, V024).
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

    @Test
    @DisplayName("BA-051-T26 a trip edited while apps/ai computes the preview ends the run TRIP_CHANGED and stores no preview")
    void aTripEditedWhileTheAnswerIsComputedStoresNoPreview() throws Exception {
        Fixture fixture = fixture();
        // The edit lands AFTER the run passed its checks on the way in and was asked about, which is the
        // window the publish step alone can close: nothing between here and the preview store looks at
        // the trip again unless publish does.
        answer(fixture, () -> jdbc.update("UPDATE trips SET version = version + 1 WHERE id = ?",
                fixture.tripId()), () -> { });

        UUID runId = queue(fixture);
        awaitSettled(runId);

        assertThat(proposeCalls).as("the edit came after apps/ai was asked, so the answer existed").hasValue(1);
        assertThat(runColumn(runId, "status")).isEqualTo("FAILED");
        assertThat(runColumn(runId, "failure_code")).isEqualTo("TRIP_CHANGED");
        assertThat(proposalCount(runId)).as("a preview of the itinerary before the edit is not stored").isZero();
    }

    @Test
    @DisplayName("BA-051-T27 a trip edit that commits while preparation waits for the trip ends the run TRIP_CHANGED before apps/ai is asked")
    void aTripEditCommittedWhilePreparationWaitsIsSeenBeforeAsking() throws Exception {
        Fixture fixture = fixture();
        answer(fixture, () -> { }, () -> { });

        // FOR NO KEY UPDATE, the lock a trip edit itself takes. Not FOR UPDATE: that one also refuses the
        // FOR KEY SHARE the run's own insert takes on its trip_id foreign key, so createOptimization would
        // queue behind this holder and the run would never be created.
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            int holderPid = backendPid(holder);
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT id FROM trips WHERE id = ? FOR NO KEY UPDATE")) {
                lock.setObject(1, fixture.tripId());
                lock.executeQuery().close();
            }

            UUID runId = queue(fixture);

            // The worker has frozen the run and is waiting for the trip in the unit of work it prepares
            // in. Only that wait makes the rest of this case deterministic: the edit below commits while
            // the check that has to see it is already underway.
            org.awaitility.Awaitility.await().atMost(15, TimeUnit.SECONDS)
                    .pollInterval(20, TimeUnit.MILLISECONDS)
                    .until(() -> jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity"
                            + " WHERE ? = ANY(pg_blocking_pids(pid)) AND query ILIKE '%trips%'",
                            Integer.class, holderPid) > 0);

            try (PreparedStatement move = holder.prepareStatement(
                    "UPDATE trip_items SET trip_date = ? WHERE id = ?")) {
                move.setObject(1, java.sql.Date.valueOf(DAY_TWO));
                move.setObject(2, fixture.itemId());
                move.executeUpdate();
            }
            try (PreparedStatement bump = holder.prepareStatement(
                    "UPDATE trips SET version = version + 1 WHERE id = ?")) {
                bump.setObject(1, fixture.tripId());
                bump.executeUpdate();
            }
            holder.commit();

            awaitSettled(runId);
            assertThat(runColumn(runId, "status")).isEqualTo("FAILED");
            assertThat(runColumn(runId, "failure_code")).isEqualTo("TRIP_CHANGED");
            assertThat(proposeCalls).as("a question about an itinerary that no longer exists is not asked")
                    .hasValue(0);
        }
    }

    @Test
    @DisplayName("BA-051-T28 a run whose deadline passes while apps/ai computes ends EXPIRED with no failure code and no preview")
    void aDeadlinePassedWhileComputingEndsExpired() throws Exception {
        Fixture fixture = fixture();
        answer(fixture, () -> { }, () -> jdbc.update("UPDATE optimization_runs"
                + " SET expires_at = queued_at - interval '1 minute' WHERE trip_id = ?", fixture.tripId()));

        UUID runId = queue(fixture);
        awaitSettled(runId);

        assertThat(proposeCalls).as("the deadline passed after the answer came back").hasValue(1);
        assertThat(runColumn(runId, "status")).isEqualTo("EXPIRED");
        assertThat(runColumn(runId, "failure_code")).as("expiry is a status, not a failure").isNull();
        assertThat(runColumn(runId, "completed_at")).isNotNull();
        assertThat(proposalCount(runId)).isZero();
        // A run that never had a preview reads 200 EXPIRED (BA-050-T7); 410 is for a READY preview that
        // expired (BA-051-T5), and this one was never READY.
        poll(fixture, runId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"));
    }

    @Test
    @DisplayName("BA-051-T29 a READY run's fingerprint is computed over the deadline the run stores")
    void theFingerprintClaimsTheStoredDeadline() throws Exception {
        Fixture fixture = fixture();
        // Seven minutes, not milliseconds: RunFingerprint hashes validUntil in whole seconds, so two
        // candidate deadlines inside one second hash alike and the comparison below would prove nothing.
        answer(fixture, () -> { }, () -> jdbc.update("UPDATE optimization_runs"
                + " SET expires_at = expires_at + interval '7 minutes' WHERE trip_id = ?", fixture.tripId()));

        UUID runId = queue(fixture);
        awaitSettled(runId);
        assertThat(runColumn(runId, "status")).isEqualTo("READY");

        Map<String, Object> run = jdbc.queryForMap("SELECT * FROM optimization_runs WHERE id = ?", runId);
        Instant stored = ((Timestamp) run.get("expires_at")).toInstant();
        String overStoredDeadline = fingerprintOf(run, fixture, stored);
        String overFrozenDeadline = fingerprintOf(run, fixture, stored.minus(Duration.ofMinutes(7)));
        assertThat(overStoredDeadline).as("the two deadlines must hash apart, or this case is vacuous")
                .isNotEqualTo(overFrozenDeadline);
        assertThat(run.get("data_fingerprint"))
                .as("fingerprint over the frozen (pre-shift) deadline would be %s", overFrozenDeadline)
                .isEqualTo(overStoredDeadline);
    }

    @Test
    @DisplayName("BA-051-T30 a retried attempt keeps the deadline its first attempt froze")
    void aRetryKeepsTheFirstDeadline() throws Exception {
        Fixture fixture = fixture();
        AtomicReference<Timestamp> firstDeadline = new AtomicReference<>();
        answer(fixture, () -> {
            if (proposeCalls.get() == 1) {
                // Read inside the first attempt, after it froze and before it fails: the deadline that
                // attempt set. The retry waits nullnull.jobs.retry-backoff (PT1S) before freezing again,
                // so a deadline recomputed on the retry cannot land in the same microsecond.
                firstDeadline.set(jdbc.queryForObject(
                        "SELECT expires_at FROM optimization_runs WHERE trip_id = ?", Timestamp.class,
                        fixture.tripId()));
                throw new RecommendationUnavailableException("injected: apps/ai did not answer", true, null);
            }
        }, () -> { });

        UUID runId = queue(fixture);
        awaitSettled(runId);

        assertThat(runColumn(runId, "status")).isEqualTo("READY");
        assertThat(proposeCalls).as("the first attempt failed and the second one answered").hasValue(2);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM background_jobs WHERE deduplication_key = ?",
                Integer.class, "optimization:" + runId)).isEqualTo(2);
        assertThat(firstDeadline.get()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT expires_at FROM optimization_runs WHERE id = ?", Timestamp.class,
                runId)).isEqualTo(firstDeadline.get());
    }

    @Test
    @DisplayName("BA-051-T31 a run that ends after its deadline is recorded EXPIRED, not with a failure code")
    void aRunEndedAfterItsDeadlineIsExpiredNotFailed() throws Exception {
        Fixture fixture = fixture();
        answerNoImprovement(() -> jdbc.update("UPDATE optimization_runs"
                + " SET expires_at = queued_at - interval '1 minute' WHERE trip_id = ?", fixture.tripId()));

        UUID runId = queue(fixture);
        awaitSettled(runId);

        assertThat(proposeCalls).as("apps/ai answered NO_IMPROVEMENT after the deadline passed").hasValue(1);
        // Since the deadline passed, every reader of this run has been shown EXPIRED (asReadNow). A
        // NO_IMPROVEMENT written now would replace one terminal status a reader saw with another.
        assertThat(runColumn(runId, "status")).isEqualTo("EXPIRED");
        assertThat(runColumn(runId, "failure_code")).isNull();
    }

    /**
     * The answers a working {@code apps/ai} gives, built from the request the handler assembled, with a
     * hook run just before the proposal is returned and another just before an explanation is.
     *
     * <p>{@code doAnswer} rather than {@code when(...)}: re-stubbing with {@code when} calls the answer it
     * replaces with null arguments (OptimizeRevertIT says so), which would run a hook for no run at all.
     */
    private void answer(Fixture fixture, Runnable beforeProposal, Runnable beforeExplanation) {
        PolicyDescriptor policy = policy();
        org.mockito.Mockito.doReturn(policy).when(recommendations).policy();
        openBothDays();
        org.mockito.Mockito.doAnswer(invocation -> {
            proposeCalls.incrementAndGet();
            beforeProposal.run();
            ItemProposeRequest request = invocation.getArgument(0);
            TemporalCandidateIn candidate = request.candidates().stream()
                    .filter(offered -> offered.date().equals(DAY_TWO)).findFirst()
                    .orElseThrow(() -> new AssertionError("no candidate for " + DAY_TWO + " in "
                            + request.candidates()));
            ItemProposalOut proposal = new ItemProposalOut(1, candidate.date(),
                    candidate.effectiveStartTime(request.target().startTime()),
                    DAY_ONE.atStartOfDay(SEOUL).toInstant(), DAY_TWO.atStartOfDay(SEOUL).toInstant(),
                    new BigDecimal("0.480000"), candidate.beforeValue().subtract(candidate.afterValue()),
                    new BigDecimal("0.600000"), new BigDecimal("0.000000"),
                    candidate.beforeSnapshotId(), candidate.afterSnapshotId(), lockChecksFor(request));
            return new ItemProposeResponse(policy.policyVersion(), policy.policyHash(),
                    policy.pipelineVersion(), ItemProposeResponse.Outcome.PROPOSALS, List.of(proposal),
                    List.of(), 1, Map.of());
        }).when(recommendations).proposeItem(any());
        org.mockito.Mockito.doAnswer(invocation -> {
            beforeExplanation.run();
            return new ExplanationRenderResponse(policy.policyVersion(), policy.policyHash(),
                    policy.pipelineVersion(), "이 날이 덜 붐빕니다.", "TEMPLATE");
        }).when(recommendations).renderExplanation(any());
    }

    /** {@code apps/ai} judging every day and finding none better, after {@code beforeAnswer} has run. */
    private void answerNoImprovement(Runnable beforeAnswer) {
        PolicyDescriptor policy = policy();
        org.mockito.Mockito.doReturn(policy).when(recommendations).policy();
        openBothDays();
        org.mockito.Mockito.doAnswer(invocation -> {
            proposeCalls.incrementAndGet();
            beforeAnswer.run();
            return new ItemProposeResponse(policy.policyVersion(), policy.policyHash(),
                    policy.pipelineVersion(), ItemProposeResponse.Outcome.NO_IMPROVEMENT, List.of(),
                    List.of(), 1, Map.of());
        }).when(recommendations).proposeItem(any());
    }

    private static PolicyDescriptor policy() {
        return new PolicyDescriptor(PolicyPins.V1.policyVersion(), PolicyPins.V1.policyHash(),
                PolicyPins.V1.pipelineVersion(), "test-service");
    }

    /** Both trip days open wide enough to hold the stay; an absent window is OPENING_HOURS_UNKNOWN, not open. */
    private void openBothDays() {
        CatalogOpeningWindow open = new CatalogOpeningWindow(CatalogOpeningWindow.State.OPEN, LocalTime.of(8, 0),
                LocalTime.of(20, 0));
        org.mockito.Mockito.doAnswer(invocation -> Map.of(DAY_ONE, open, DAY_TWO, open))
                .when(hours).windowsFor(any(), any(), any(), any());
    }

    private static Map<String, Boolean> lockChecksFor(ItemProposeRequest request) {
        Map<String, Boolean> checks = new java.util.LinkedHashMap<>();
        request.locks().forEach(lock -> checks.put(lock.type().name(), true));
        return checks;
    }

    /**
     * The fingerprint this run would carry over {@code validUntil}, rebuilt from what the run and the
     * fixture stored. The snapshot ids are the pair the one candidate compared (TemporalCandidateAssembler
     * pins before and after), and the registry version is the forecast source's current revision.
     */
    private String fingerprintOf(Map<String, Object> run, Fixture fixture, Instant validUntil) {
        long sourceVersion = jdbc.queryForObject(
                "SELECT current_revision FROM source_registry WHERE code = ?", Long.class, FORECAST_SOURCE);
        return RunFingerprint.of(new RunFingerprint.Inputs((UUID) run.get("input_revision_id"),
                ((Number) run.get("input_trip_version")).longValue(),
                Set.of(pointOn(fixture.placeId(), DAY_ONE), pointOn(fixture.placeId(), DAY_TWO)),
                Map.of(FORECAST_SOURCE, Math.toIntExact(sourceVersion)), NORMALIZATION,
                (String) run.get("policy_version"), (String) run.get("policy_hash"),
                (String) run.get("algorithm_version"), (String) run.get("catalog_version"), validUntil));
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
                + " VALUES (?, 'BA-051 in-flight 장소', 'A0101', 37.566535, 126.978001, '1', 'ACTIVE', ?, ?)",
                placeId, now, now);
        jdbc.update("INSERT INTO place_localizations (id, place_id, locale, name, address, updated_at)"
                + " VALUES (?, ?, 'ko-KR', 'BA-051 in-flight 장소', '서울시 종로구', ?)",
                UUID.randomUUID(), placeId, now);
        return placeId;
    }

    /** A duration, because without one the revalidator refuses every proposal as DURATION_UNKNOWN. */
    private UUID insertItem(UUID tripId, UUID placeId) {
        UUID itemId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO trip_items (id, trip_id, place_id, trip_date, position, start_time,"
                + " duration_minutes, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, 0, CAST(? AS time), 90, ?, ?)",
                itemId, tripId, placeId, java.sql.Date.valueOf(DAY_ONE), AT_NINE.toString(), now, now);
        return itemId;
    }

    /** One fresh forecast set covering both trip days: crowded where the item is, quiet the day after. */
    private void insertForecasts(UUID placeId) {
        Instant fetchedAt = Instant.now();
        Instant staleAt = fetchedAt.plus(Duration.ofHours(12));
        long sourceVersion = jdbc.queryForObject(
                "SELECT current_revision FROM source_registry WHERE code = ?", Long.class, FORECAST_SOURCE);
        UUID collectorRun = UUID.randomUUID();
        collectorRuns.add(collectorRun);
        jdbc.update("INSERT INTO collector_runs (id, source_code, status, trigger_type,"
                + " records_received, records_accepted, records_rejected, schema_version, started_at,"
                + " finished_at) VALUES (?, ?, 'COMPLETED', 'READ_THROUGH', 2, 2, 0, ?, ?, ?)",
                collectorRun, FORECAST_SOURCE, NORMALIZATION, Timestamp.from(fetchedAt),
                Timestamp.from(fetchedAt));
        UUID set = UUID.randomUUID();
        snapshotSets.add(set);
        String issue = "ba051-inflight-" + set;
        jdbc.update("INSERT INTO snapshot_sets (id, source_code, source_registry_version,"
                + " collector_run_id, source_state, forecast_issue_id, comparison_group_id,"
                + " observed_at, fetched_at, stale_at, normalization_version, created_at)"
                + " VALUES (?, ?, ?, ?, 'FORECAST', ?, ?, NULL, ?, ?, ?, ?)",
                set, FORECAST_SOURCE, sourceVersion, collectorRun, issue, issue,
                Timestamp.from(fetchedAt), Timestamp.from(staleAt), NORMALIZATION, Timestamp.from(fetchedAt));
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
                + " ?, ?, ?, NULL, 'PLACE', 'BA-051 in-flight fixture', 'DIRECT', false, ?)",
                UUID.randomUUID(), set, FORECAST_SOURCE, sourceVersion, placeId,
                Timestamp.from(targetAt), Timestamp.from(fetchedAt), Timestamp.from(staleAt), value,
                issue, issue, NORMALIZATION, Timestamp.from(fetchedAt));
    }

    private UUID pointOn(UUID placeId, LocalDate day) {
        return jdbc.queryForObject("SELECT id FROM crowd_snapshots WHERE place_id = ? AND target_at = ?",
                UUID.class, placeId, Timestamp.from(day.atStartOfDay(SEOUL).toInstant()));
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

    /** Waits for a stored status the worker will not move again; EXPIRED is one now that it can be stored. */
    private void awaitSettled(UUID runId) {
        org.awaitility.Awaitility.await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(50, TimeUnit.MILLISECONDS)
                .until(() -> List.of("READY", "FAILED", "EXPIRED", "APPLIED", "REVERTED")
                        .contains(runColumn(runId, "status")));
        // Settled means the job is done too, not only the run: a job still RUNNING could write again.
        org.awaitility.Awaitility.await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(50, TimeUnit.MILLISECONDS)
                .until(() -> "COMPLETED".equals(jdbc.queryForObject(
                        "SELECT status FROM background_jobs WHERE deduplication_key = ?", String.class,
                        "optimization:" + runId)));
    }

    private ResultActions poll(Fixture fixture, UUID runId) throws Exception {
        return mvc.perform(get("/api/v1/optimizations/" + runId).cookie(cookie(fixture.owner())));
    }

    private int proposalCount(UUID runId) {
        return jdbc.queryForObject("SELECT count(*) FROM optimization_proposals WHERE run_id = ?", Integer.class,
                runId);
    }

    private String runColumn(UUID runId, String column) {
        Object value = jdbc.queryForMap("SELECT * FROM optimization_runs WHERE id = ?", runId).get(column);
        return value == null ? null : value.toString();
    }

    private static int backendPid(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet pid = statement.executeQuery("SELECT pg_backend_pid()")) {
            pid.next();
            return pid.getInt(1);
        }
    }

    private static Cookie cookie(SessionService.Bootstrap owner) {
        return new Cookie("__Host-nullnull_session", owner.cookie);
    }
}
