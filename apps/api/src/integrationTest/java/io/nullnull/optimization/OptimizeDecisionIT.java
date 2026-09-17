package io.nullnull.optimization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.catalog.application.CatalogHoursQuery;
import io.nullnull.catalog.application.CatalogHoursQuery.CatalogOpeningWindow;
import io.nullnull.identity.application.SessionService;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * BA-052 decideOptimization: what a traveller's answer is allowed to do, and what refuses it.
 *
 * <p>Every case here starts from a real READY run - the BA-051 pipeline runs end to end, so the
 * proposal being decided is one the server actually produced and re-validated. A fixture that wrote
 * a READY row by hand would let these assertions pass against a preview no code path can make.
 *
 * <p>The gateway is mocked because it is the process boundary (ADR-0006); the revalidator, the
 * decision store and the trip module are real, because they are what this card is about.
 */
@SpringBootTest(properties = {"nullnull.catalog.public-enabled=true",
        "nullnull.capabilities.optimization=true", "nullnull.jobs.enabled=true",
        "nullnull.jobs.poll-interval=PT0.02S", "nullnull.jobs.retry-backoff=PT1S",
        "nullnull.jobs.max-retry-backoff=PT1S"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-052 the traveller's decision")
class OptimizeDecisionIT {

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

    private final List<UUID> trips = new ArrayList<>();
    private final List<UUID> places = new ArrayList<>();
    private final List<UUID> snapshotSets = new ArrayList<>();
    private final List<UUID> collectorRuns = new ArrayList<>();
    private final List<UUID> runIds = new ArrayList<>();

    /** Only rows this class created, each named by an id it minted (AGENTS.md rule 6). */
    @AfterEach
    void removeOnlyOwnFixtures() {
        for (UUID runId : runIds) {
            jdbc.update("DELETE FROM background_jobs WHERE deduplication_key = ?", "optimization:" + runId);
        }
        // Decisions before trips. A decision names the revisions it moved between, and
        // optimization_decisions.before_revision_id/after_revision_id reference trip_revisions
        // WITHOUT cascade - so deleting the trip tries to take its revisions while a decision still
        // points at them. The cases that refuse write no decision and never met this; the two that
        // apply did. Rows go in the direction the references point, one level deeper than the
        // comment below first applied it.
        for (UUID runId : runIds) {
            jdbc.update("DELETE FROM optimization_decisions WHERE run_id = ?", runId);
        }
        // Trips next: a run points at the sets it froze, so the sets cannot go while a run holds them.
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
    @DisplayName("BA-052-T1 a run takes one initial decision, and the second is refused")
    void onlyTheFirstInitialDecisionIsRecorded() throws Exception {
        Fixture fixture = fixture();
        UUID runId = readyRun(fixture);
        UUID proposalId = proposalOf(runId);

        decide(fixture, runId, proposalId, "APPLY", "\"1\"").andExpect(status().isOk());

        // A different key, so this is a second decision rather than a replay of the first. The trip
        // moved to version 2, so the If-Match a second caller would hold is that one.
        decide(fixture, runId, proposalId, "KEEP", "\"2\"")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATA_CHANGED"));

        // TWO guards produce this, and neither is individually necessary - measured, not assumed:
        //
        //   status != READY disabled            red=0   the index refuses instead
        //   insertIfFirst's answer ignored      red=0   the status check refuses instead
        //   both disabled                       red=1   this case, and only this case
        //
        // So this asserts the outcome, not a line. Say it that way rather than crediting one of
        // them: a reader who deletes "the load-bearing check" would find the suite still green and
        // conclude the other is dead code. The index is proven where it can actually arbitrate - at
        // the SQL layer, by OptimizationDecisionSchemaIT's BA-052-T10 - because from HTTP two
        // decisions by one owner are serialised before they meet it: IdempotencyGuard locks the
        // owner row as its first statement and holds it through commit.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM optimization_decisions WHERE run_id = ?",
                Integer.class, runId))
                .as("a decided run refuses a second decision, and records one row")
                .isOne();
    }

    @Test
    @DisplayName("BA-052-T3 a trip that moved since the preview refuses the decision")
    void aMovedTripRefusesTheDecision() throws Exception {
        Fixture fixture = fixture();
        UUID runId = readyRun(fixture);
        UUID proposalId = proposalOf(runId);

        // The edit a user makes in another tab while looking at the preview.
        jdbc.update("UPDATE trips SET version = 2, updated_at = now() WHERE id = ?", fixture.tripId());

        decide(fixture, runId, proposalId, "APPLY", "\"1\"")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRIP_CHANGED"));

        assertThat(decisionCount(runId)).as("a refused decision records nothing").isZero();
        assertThat(itemDate(fixture.itemId())).as("and moves nothing").isEqualTo(DAY_ONE.toString());
    }

    @Test
    @DisplayName("BA-052-T5 a preview whose frozen evidence is gone refuses the decision")
    void vanishedEvidenceRefusesTheDecision() throws Exception {
        Fixture fixture = fixture();
        UUID runId = readyRun(fixture);
        UUID proposalId = proposalOf(runId);

        // The state a retention sweep would create. Nothing in production removes these rows today,
        // which is exactly why the test has to make the state: a guard with no producer is proven by
        // constructing what it guards against. V011's immutability triggers are BEFORE UPDATE, so a
        // delete is possible where an edit is not.
        //
        // Scoped to the sets THIS class froze. The required gate runs every suite against one
        // database, so a delete that named no set would take another class's evidence with it.
        for (UUID set : snapshotSets) {
            jdbc.update("DELETE FROM crowd_snapshots WHERE snapshot_set_id = ?", set);
        }

        decide(fixture, runId, proposalId, "APPLY", "\"1\"")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATA_CHANGED"))
                // One sentence, not two. "Changed" is not a case that can occur - crowd snapshots are
                // immutable - so a second detail would be one no input could ever produce.
                .andExpect(jsonPath("$.detail").value(
                        "The forecast evidence this preview was judged against is no longer stored."));

        assertThat(decisionCount(runId)).isZero();
        assertThat(itemDate(fixture.itemId())).isEqualTo(DAY_ONE.toString());
    }

    @Test
    @DisplayName("BA-052-T6 a preview past its deadline refuses the decision")
    void anExpiredPreviewRefusesTheDecision() throws Exception {
        Fixture fixture = fixture();
        UUID runId = readyRun(fixture);
        UUID proposalId = proposalOf(runId);

        // The window closing is a property of the row, not of time: moving the test clock far enough
        // would expire the caller's session before it could ask.
        jdbc.update("UPDATE optimization_runs SET expires_at = queued_at - interval '1 minute'"
                + " WHERE id = ?", runId);

        decide(fixture, runId, proposalId, "APPLY", "\"1\"")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATA_CHANGED"));

        assertThat(decisionCount(runId)).isZero();
        assertThat(itemDate(fixture.itemId())).isEqualTo(DAY_ONE.toString());
    }

    @Test
    @DisplayName("BA-052-T7 a preview judged under a withdrawn policy refuses the decision")
    void aWithdrawnPolicyRefusesTheDecision() throws Exception {
        Fixture fixture = fixture();
        UUID runId = readyRun(fixture);
        UUID proposalId = proposalOf(runId);

        // The policy is revised after the preview was computed. Only the hash moves: a release that
        // renamed itself would be caught by the version too, and this is the harder half - a
        // revision nobody announced. The run recorded both when it reached READY (V032), so the
        // comparison is against what this preview was actually judged under rather than against
        // whatever the service reports today.
        org.mockito.Mockito.when(recommendations.policy()).thenReturn(new PolicyDescriptor(
                PolicyPins.V1.policyVersion(), "f".repeat(64), PolicyPins.V1.pipelineVersion(),
                "test-service"));

        decide(fixture, runId, proposalId, "APPLY", "\"1\"")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATA_CHANGED"));

        assertThat(decisionCount(runId)).as("a refused decision records nothing").isZero();
        assertThat(itemDate(fixture.itemId())).as("and moves nothing").isEqualTo(DAY_ONE.toString());
    }

    @Test
    @DisplayName("BA-052-T8 an applied decision releases no lock")
    void applyReleasesNoLock() throws Exception {
        Fixture fixture = fixture();
        // MUST_VISIT pins the place, not the schedule, so it does not refuse the move. That choice is
        // the whole test: a lock that refused would make the apply fail, and "the lock is still there"
        // would then be true of an apply that never touched the item at all.
        lock(fixture.tripId(), fixture.itemId(), "MUST_VISIT");
        UUID runId = readyRun(fixture);
        UUID proposalId = proposalOf(runId);

        decide(fixture, runId, proposalId, "APPLY", "\"1\"").andExpect(status().isOk());

        // The negative control: the apply really did move the item, so the lock survived a change
        // rather than surviving inaction (invariant 7 - the four locks never release automatically).
        assertThat(itemDate(fixture.itemId())).isEqualTo(DAY_TWO.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_constraints WHERE trip_item_id = ?"
                + " AND type = 'MUST_VISIT'", Integer.class, fixture.itemId()))
                .as("an APPLY is the machine's proposal being accepted, not a person answering a lock")
                .isOne();
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

    /** Drives the BA-051 pipeline until the run really is READY with a stored proposal. */
    private UUID readyRun(Fixture fixture) throws Exception {
        answerFromTheRequest();
        UUID runId = queue(fixture);
        org.awaitility.Awaitility.await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(50, TimeUnit.MILLISECONDS)
                .until(() -> List.of("READY", "FAILED", "APPLIED", "REVERTED")
                        .contains(runColumn(runId, "status")));
        assertThat(runColumn(runId, "status"))
                .as("every case below decides a preview the pipeline actually produced")
                .isEqualTo("READY");
        return runId;
    }

    private UUID proposalOf(UUID runId) {
        return jdbc.queryForObject("SELECT id FROM optimization_proposals WHERE run_id = ?",
                UUID.class, runId);
    }

    private ResultActions decide(Fixture fixture, UUID runId, UUID proposalId, String kind,
            String ifMatch) throws Exception {
        return mvc.perform(post("/api/v1/optimizations/" + runId + "/decisions")
                .cookie(cookie(fixture.owner()))
                .header("Origin", "http://localhost:5173")
                .header("X-CSRF-Token", fixture.owner().csrf.token)
                .header("If-Match", ifMatch)
                .header("Idempotency-Key", "decide-" + UUID.randomUUID())
                .contentType("application/json")
                .content("{\"proposalId\":\"" + proposalId + "\",\"decision\":\"" + kind + "\"}"));
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
                    candidate.beforeSnapshotId(), candidate.afterSnapshotId(), Map.of());
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
                + " VALUES (?, 'BA-052 대상 장소', 'A0101', 37.566535, 126.978001, '1', 'ACTIVE', ?, ?)",
                placeId, now, now);
        jdbc.update("INSERT INTO place_localizations (id, place_id, locale, name, address, updated_at)"
                + " VALUES (?, ?, 'ko-KR', 'BA-052 대상 장소', '서울시 종로구', ?)",
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

    private void lock(UUID tripId, UUID itemId, String type) {
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO trip_constraints (id, trip_id, trip_item_id, type, source,"
                + " date_value, start_time_value, tolerance_minutes, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, 'USER', NULL, NULL, NULL, ?, ?)",
                UUID.randomUUID(), tripId, itemId, type, now, now);
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
        String issue = "ba052-" + set;
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

    private int decisionCount(UUID runId) {
        return jdbc.queryForObject("SELECT count(*) FROM optimization_decisions WHERE run_id = ?",
                Integer.class, runId);
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
}
