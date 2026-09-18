package io.nullnull.optimization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.catalog.application.CatalogHoursQuery;
import io.nullnull.catalog.application.CatalogHoursQuery.CatalogOpeningWindow;
import io.nullnull.identity.application.IdempotencyRecordStore;
import io.nullnull.identity.application.SessionService;
import io.nullnull.optimization.application.OptimizationDecisionStore;
import io.nullnull.optimization.application.OptimizationRunStore;
import io.nullnull.optimization.domain.OptimizationStatus;
import io.nullnull.recommendation.application.RecommendationGateway;
import io.nullnull.recommendation.domain.PolicyDescriptor;
import io.nullnull.recommendation.domain.PolicyPins;
import io.nullnull.recommendation.domain.explanation.ExplanationRenderResponse;
import io.nullnull.recommendation.domain.item.ItemProposalOut;
import io.nullnull.recommendation.domain.item.ItemProposeRequest;
import io.nullnull.recommendation.domain.item.ItemProposeResponse;
import io.nullnull.recommendation.domain.item.TemporalCandidateIn;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import io.nullnull.trip.application.TripService;
import io.nullnull.trip.application.TripStore;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * BA-052-T2: an APPLY that fails at any of its write points leaves nothing behind.
 *
 * <p>Separate from {@code OptimizeDecisionIT} because four stores are spied on here, and a fault
 * stubbed into a bean every case shares would reach cases that are not about faults.
 *
 * <p>An APPLY writes, in this order: the idempotency reservation; each change's item row (P1 is
 * between two of them); then {@code TripStore.updateMetadata}, which raises the version, deletes and
 * re-inserts the trip's interests and writes the revision (P2 ends there); the decision (P3); the
 * run's status (P4); and last the stored response (P5). The interests are not asserted below: the
 * rewrite puts back the rows it removed, so no fault can leave them in an observable partial state.
 *
 * <p>They are ONE transaction: {@code IdempotencyGuard.execute} runs the command inside its own
 * {@code TransactionTemplate}, and {@code TripService.applyOptimizationMoves} joins it with a plain
 * {@code @Transactional}. Nothing on the path leaves it - counted by reading the path and running
 * {@code grep -rnw "REQUIRES_NEW\|NOT_SUPPORTED\|TransactionalEventListener\|afterCommit\|registerSynchronization"}
 * over main, which matched three comments in operations/application and no code. The one other
 * transaction-synchronisation API in main, {@code TransactionSynchronizationManager}, is only ever
 * asked {@code isActualTransactionActive()} (IdempotencyGuard, JobContext); it registers nothing.
 *
 * <p>One transaction does not make one point enough, because the points catch different defects.
 * P2 to P5 each prove the transaction rolls back from a later place: P5 is the strongest of them,
 * with the decision, the run's status and the reservation all written and all undone. P1 is a
 * different defect - a loop over the changes that swallows the second one's failure commits the
 * first, one transaction or not, and no fault after the loop can see that. P0 cannot reach P1 from
 * HTTP (an ITEM proposal carries one MOVE), so it is driven through the trip module, which takes a
 * list.
 */
@SpringBootTest(properties = {"nullnull.catalog.public-enabled=true",
        "nullnull.capabilities.optimization=true", "nullnull.jobs.enabled=true",
        "nullnull.jobs.poll-interval=PT0.02S", "nullnull.jobs.retry-backoff=PT1S",
        "nullnull.jobs.max-retry-backoff=PT1S"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-052 a failed apply")
class OptimizeDecisionFaultIT {

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
    @MockitoBean RecommendationGateway recommendations;
    @MockitoBean CatalogHoursQuery hours;
    @MockitoSpyBean OptimizationDecisionStore decisions;
    @MockitoSpyBean OptimizationRunStore runs;
    @MockitoSpyBean IdempotencyRecordStore records;
    @MockitoSpyBean TripStore tripStore;
    @Autowired TripService tripService;

    private final List<UUID> trips = new ArrayList<>();
    private final List<UUID> places = new ArrayList<>();
    private final List<UUID> snapshotSets = new ArrayList<>();
    private final List<UUID> collectorRuns = new ArrayList<>();
    private final List<UUID> runIds = new ArrayList<>();

    @AfterEach
    void removeOnlyOwnFixtures() {
        for (UUID runId : runIds) {
            jdbc.update("DELETE FROM background_jobs WHERE deduplication_key = ?", "optimization:" + runId);
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

    @Test
    @DisplayName("BA-052-T2 P3: an apply that fails recording the decision changes no trip row")
    void aFailedApplyLeavesNothingBehind() throws Exception {
        Fixture fixture = fixture();
        UUID runId = readyRun(fixture);
        UUID proposalId = proposalOf(runId);

        long versionBefore = tripVersion(fixture.tripId());
        int revisionsBefore = revisionCount(fixture.tripId());

        // The decision write throws after the trip has already been moved in the same transaction -
        // the write V030's partial unique index arbitrates at (BA-052-T10).
        org.mockito.Mockito.doThrow(new TransientDataAccessResourceException(
                        "injected fault at the decision write"))
                .when(decisions).insertIfFirst(any());

        forgetSetupCalls();
        mvc.perform(post("/api/v1/optimizations/" + runId + "/decisions")
                        .cookie(cookie(fixture.owner()))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", fixture.owner().csrf.token)
                        .header("If-Match", "\"1\"")
                        .header("Idempotency-Key", "decide-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"proposalId\":\"" + proposalId + "\",\"decision\":\"APPLY\"}"))
                .andExpect(status().is5xxServerError());
        org.mockito.Mockito.verify(decisions).insertIfFirst(any());

        // Zero partial application, stated as the three rows an apply would have touched.
        assertThat(itemDate(fixture.itemId()))
                .as("the item the preview proposed moving is where it was")
                .isEqualTo(DAY_ONE.toString());
        assertThat(tripVersion(fixture.tripId()))
                .as("a version raised for a decision nobody recorded would be an ETag nothing explains")
                .isEqualTo(versionBefore);
        assertThat(revisionCount(fixture.tripId()))
                .as("and no revision records a change that did not happen")
                .isEqualTo(revisionsBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM optimization_decisions WHERE run_id = ?",
                Integer.class, runId)).isZero();
        assertThat(runColumn(runId, "status"))
                .as("the run is still offering its preview, so the same key may be retried")
                .isEqualTo("READY");

        // The negative control. Without it every assertion above is also true of a request that
        // never reached the write at all - and of an apply path that does nothing whatsoever.
        // A reset spy is the real store again, so this is the unfaulted command end to end.
        org.mockito.Mockito.reset(decisions);

        apply(fixture, runId, proposalId, "decide-" + UUID.randomUUID()).andExpect(status().isOk());

        assertThat(itemDate(fixture.itemId()))
                .as("the same request without the fault does move the item, so the rollback above was real")
                .isEqualTo(DAY_TWO.toString());
        assertThat(tripVersion(fixture.tripId())).isEqualTo(versionBefore + 1);
    }

    @Test
    @DisplayName("BA-052-T2 P1: a failure between two changes does not commit the first one")
    void aFailureBetweenChangesCommitsNeitherChange() throws Exception {
        Fixture fixture = fixture();
        UUID second = insertItemAt(fixture.tripId(), insertPlace(), 1, LocalTime.of(11, 0));
        long versionBefore = tripVersion(fixture.tripId());
        int revisionsBefore = revisionCount(fixture.tripId());
        List<TripService.ItemMove> moves = List.of(
                new TripService.ItemMove(fixture.itemId(), DAY_TWO, 0, null),
                new TripService.ItemMove(second, DAY_TWO, 1, null));

        // The first move is real and the second fails, so the defect this looks for has something to
        // commit: a day half applied.
        org.mockito.Mockito.doCallRealMethod()
                .doThrow(new TransientDataAccessResourceException("injected fault at the second move"))
                .when(tripStore).moveItem(any(), any(), any(), anyInt(), any());

        assertThatThrownBy(() -> tripService.applyOptimizationMoves(fixture.owner().owner.id(),
                fixture.tripId(), versionBefore, moves))
                .isInstanceOf(TransientDataAccessResourceException.class);

        org.mockito.Mockito.verify(tripStore, org.mockito.Mockito.times(2))
                .moveItem(any(), any(), any(), anyInt(), any());
        assertThat(itemDate(fixture.itemId()))
                .as("the first move reached the database and went back with the second's failure")
                .isEqualTo(DAY_ONE.toString());
        assertThat(itemDate(second)).isEqualTo(DAY_ONE.toString());
        assertThat(tripVersion(fixture.tripId())).isEqualTo(versionBefore);
        assertThat(revisionCount(fixture.tripId())).isEqualTo(revisionsBefore);

        // The negative control: the same list without the fault moves both.
        org.mockito.Mockito.reset(tripStore);
        tripService.applyOptimizationMoves(fixture.owner().owner.id(), fixture.tripId(), versionBefore,
                moves);
        assertThat(itemDate(fixture.itemId())).isEqualTo(DAY_TWO.toString());
        assertThat(itemDate(second)).isEqualTo(DAY_TWO.toString());
    }

    @Test
    @DisplayName("BA-052-T2 P2: an apply that fails raising the version leaves the moved item where it was")
    void aFailureRaisingTheVersionLeavesNothingBehind() throws Exception {
        Fixture fixture = fixture();
        UUID runId = readyRun(fixture);
        UUID proposalId = proposalOf(runId);
        long versionBefore = tripVersion(fixture.tripId());
        int revisionsBefore = revisionCount(fixture.tripId());

        // updateMetadata is the version bump and the revision in one call; the item has moved by then.
        org.mockito.Mockito.doThrow(new TransientDataAccessResourceException(
                        "injected fault at the version write"))
                .when(tripStore).updateMetadata(any(), any(), any(), any());

        forgetSetupCalls();
        apply(fixture, runId, proposalId, "decide-" + UUID.randomUUID())
                .andExpect(status().is5xxServerError());
        // Reached, so the rows below were undone rather than never written.
        org.mockito.Mockito.verify(tripStore).moveItem(any(), any(), any(), anyInt(), any());
        org.mockito.Mockito.verify(tripStore).updateMetadata(any(), any(), any(), any());
        assertNothingApplied(fixture, runId, versionBefore, revisionsBefore);

        org.mockito.Mockito.reset(tripStore);
        apply(fixture, runId, proposalId, "decide-" + UUID.randomUUID()).andExpect(status().isOk());
        assertThat(itemDate(fixture.itemId())).isEqualTo(DAY_TWO.toString());
    }

    @Test
    @DisplayName("BA-052-T2 P4: an apply that fails ending the run takes the decision back with the trip")
    void aFailureEndingTheRunLeavesNothingBehind() throws Exception {
        Fixture fixture = fixture();
        UUID runId = readyRun(fixture);
        UUID proposalId = proposalOf(runId);
        long versionBefore = tripVersion(fixture.tripId());
        int revisionsBefore = revisionCount(fixture.tripId());

        // Only the READY -> APPLIED step: the worker's own transitions got this run to READY above.
        org.mockito.Mockito.doThrow(new TransientDataAccessResourceException(
                        "injected fault at the run transition"))
                .when(runs).transition(eq(runId), eq(OptimizationStatus.READY),
                        eq(OptimizationStatus.APPLIED), any());

        forgetSetupCalls();
        apply(fixture, runId, proposalId, "decide-" + UUID.randomUUID())
                .andExpect(status().is5xxServerError());
        // The decision row had been written by this point, so zero here is a rollback, not an absence.
        org.mockito.Mockito.verify(decisions).insertIfFirst(any());
        org.mockito.Mockito.verify(runs).transition(eq(runId), eq(OptimizationStatus.READY),
                eq(OptimizationStatus.APPLIED), any());
        assertNothingApplied(fixture, runId, versionBefore, revisionsBefore);

        org.mockito.Mockito.reset(runs);
        apply(fixture, runId, proposalId, "decide-" + UUID.randomUUID()).andExpect(status().isOk());
        assertThat(itemDate(fixture.itemId())).isEqualTo(DAY_TWO.toString());
    }

    @Test
    @DisplayName("BA-052-T2 P5: an apply that fails storing its response leaves no write behind, the reservation included")
    void aFailureStoringTheResponseLeavesNothingBehind() throws Exception {
        Fixture fixture = fixture();
        UUID runId = readyRun(fixture);
        UUID proposalId = proposalOf(runId);
        long versionBefore = tripVersion(fixture.tripId());
        int revisionsBefore = revisionCount(fixture.tripId());

        // The last write: every other one, the reservation first among them, has happened by now.
        //
        // Stubbed on the spy INSIDE the proxy. JdbcIdempotencyRecordStore's methods are
        // @Transactional(MANDATORY), so the injected bean is a transaction proxy around the spy, and
        // a stubbing call made through it meets the interceptor first - which refuses it for having
        // no transaction, before Mockito ever sees the call. The first run died exactly there, and the
        // argument matchers it had registered leaked into the next case's first spy call as a 500.
        org.mockito.Mockito.doThrow(new TransientDataAccessResourceException(
                        "injected fault at the response write"))
                .when(recordSpy()).complete(any(), anyInt(), any());

        forgetSetupCalls();
        String key = "decide-" + UUID.randomUUID();
        apply(fixture, runId, proposalId, key).andExpect(status().is5xxServerError());
        org.mockito.Mockito.verify(recordSpy()).complete(any(), anyInt(), any());
        org.mockito.Mockito.verify(runs).transition(eq(runId), eq(OptimizationStatus.READY),
                eq(OptimizationStatus.APPLIED), any());
        assertNothingApplied(fixture, runId, versionBefore, revisionsBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records"
                + " WHERE owner_id = ? AND idempotency_key = ?", Integer.class,
                fixture.owner().owner.id(), key))
                .as("the reservation, the first write of all, went back with the last")
                .isZero();

        // The negative control, and the contract's sentence: the same key can replay a failed APPLY.
        org.mockito.Mockito.reset(recordSpy());
        apply(fixture, runId, proposalId, key).andExpect(status().isOk());
        assertThat(itemDate(fixture.itemId())).isEqualTo(DAY_TWO.toString());
        assertThat(tripVersion(fixture.tripId())).isEqualTo(versionBefore + 1);
    }

    /**
     * The fixture drives real commands - creating the trip, queueing the run - through the same
     * spies, so a verify after the APPLY would count those too. Stubs stay; only the record goes.
     */
    private void forgetSetupCalls() {
        org.mockito.Mockito.clearInvocations(decisions, runs, recordSpy(), tripStore);
    }

    private IdempotencyRecordStore recordSpy() {
        return org.springframework.test.util.AopTestUtils.getUltimateTargetObject(records);
    }

    /** The rows an APPLY would have touched, each as it was before the attempt. */
    private void assertNothingApplied(Fixture fixture, UUID runId, long versionBefore,
            int revisionsBefore) {
        assertThat(itemDate(fixture.itemId())).as("the item").isEqualTo(DAY_ONE.toString());
        assertThat(tripVersion(fixture.tripId())).as("the version").isEqualTo(versionBefore);
        assertThat(revisionCount(fixture.tripId())).as("the revisions").isEqualTo(revisionsBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM optimization_decisions WHERE run_id = ?",
                Integer.class, runId)).as("the decision").isZero();
        assertThat(runColumn(runId, "status")).as("the run").isEqualTo("READY");
    }

    private ResultActions apply(Fixture fixture, UUID runId, UUID proposalId, String key)
            throws Exception {
        return mvc.perform(post("/api/v1/optimizations/" + runId + "/decisions")
                .cookie(cookie(fixture.owner()))
                .header("Origin", "http://localhost:5173")
                .header("X-CSRF-Token", fixture.owner().csrf.token)
                .header("If-Match", "\"1\"")
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content("{\"proposalId\":\"" + proposalId + "\",\"decision\":\"APPLY\"}"));
    }

    private UUID insertItemAt(UUID tripId, UUID placeId, int position, LocalTime start) {
        UUID itemId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO trip_items (id, trip_id, place_id, trip_date, position, start_time,"
                + " duration_minutes, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, CAST(? AS time), 90, ?, ?)",
                itemId, tripId, placeId, java.sql.Date.valueOf(DAY_ONE), position, start.toString(), now,
                now);
        return itemId;
    }

    // ---- fixture -------------------------------------------------------------------------------

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

    private UUID readyRun(Fixture fixture) throws Exception {
        answerFromTheRequest();
        UUID runId = queue(fixture);
        org.awaitility.Awaitility.await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(50, TimeUnit.MILLISECONDS)
                .until(() -> List.of("READY", "FAILED", "APPLIED", "REVERTED")
                        .contains(runColumn(runId, "status")));
        assertThat(runColumn(runId, "status")).isEqualTo("READY");
        return runId;
    }

    private UUID proposalOf(UUID runId) {
        return jdbc.queryForObject("SELECT id FROM optimization_proposals WHERE run_id = ?",
                UUID.class, runId);
    }

    private void answerFromTheRequest() {
        PolicyDescriptor policy = new PolicyDescriptor(PolicyPins.V1.policyVersion(),
                PolicyPins.V1.policyHash(), PolicyPins.V1.pipelineVersion(), "test-service");
        org.mockito.Mockito.when(recommendations.policy()).thenReturn(policy);
        org.mockito.Mockito.when(hours.windowsFor(any(), any(), any(), any())).thenAnswer(call ->
                Map.of(DAY_ONE, openAllDay(), DAY_TWO, openAllDay()));
        org.mockito.Mockito.when(recommendations.proposeItem(any())).thenAnswer(call -> {
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
                    candidate.beforeSnapshotId(), candidate.afterSnapshotId(),
                    // What apps/ai asserts for a proposal it returns: every lock the request carried,
                    // held. ProposalRevalidator refuses a map that differs from its own verdicts.
                    lockChecksFor(request));
            return new ItemProposeResponse(policy.policyVersion(), policy.policyHash(),
                    policy.pipelineVersion(), ItemProposeResponse.Outcome.PROPOSALS,
                    List.of(proposal), List.of(), 1, Map.of());
        });
        org.mockito.Mockito.when(recommendations.renderExplanation(any())).thenAnswer(call ->
                new ExplanationRenderResponse(policy.policyVersion(), policy.policyHash(),
                        policy.pipelineVersion(), "이 날이 덜 붐빕니다.", "TEMPLATE"));
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
                + " VALUES (?, 'BA-052 실패 장소', 'A0101', 37.566535, 126.978001, '1', 'ACTIVE', ?, ?)",
                placeId, now, now);
        jdbc.update("INSERT INTO place_localizations (id, place_id, locale, name, address, updated_at)"
                + " VALUES (?, ?, 'ko-KR', 'BA-052 실패 장소', '서울시 종로구', ?)",
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
        String issue = "ba052f-" + set;
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
                + " ?, ?, 'kto-tats-cnctr-rate-v4.1', NULL, 'PLACE', 'BA-052 fixture', 'DIRECT',"
                + " false, ?)",
                UUID.randomUUID(), set, FORECAST_SOURCE, sourceVersion, placeId,
                Timestamp.from(targetAt), Timestamp.from(fetchedAt), Timestamp.from(staleAt), value,
                issue, issue, Timestamp.from(fetchedAt));
    }

    private long tripVersion(UUID tripId) {
        return jdbc.queryForObject("SELECT version FROM trips WHERE id = ?", Long.class, tripId);
    }

    private int revisionCount(UUID tripId) {
        return jdbc.queryForObject("SELECT count(*) FROM trip_revisions WHERE trip_id = ?",
                Integer.class, tripId);
    }

    private String itemDate(UUID itemId) {
        return jdbc.queryForObject("SELECT trip_date FROM trip_items WHERE id = ?", String.class, itemId);
    }

    private String runColumn(UUID runId, String column) {
        Object value = jdbc.queryForMap("SELECT * FROM optimization_runs WHERE id = ?", runId).get(column);
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
}
