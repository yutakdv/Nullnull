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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * BA-051: the run that actually produces a preview.
 *
 * <p>BA-050 proved the gate in front of this write. Here the whole path runs - stored forecasts
 * become candidates, {@code apps/ai} is asked, the answer is re-validated against the request it
 * came from, and a preview is stored and published.
 *
 * <p><strong>The gateway is mocked; the revalidator is not.</strong> The gateway is the process
 * boundary (ADR-0006) and the arithmetic behind it has its own corpus, so calling a real service
 * here would test somebody else's code over a network. {@code ProposalRevalidator} is the opposite:
 * it is this server's refusal to store an answer it cannot justify, and mocking it would delete the
 * only thing standing between a wrong answer and a stored preview.
 *
 * <p>That combination is what makes the mock honest. The answer is built <em>from the request the
 * handler assembled</em> - its snapshot ids, its values, its slots - because the real revalidator
 * refuses anything else. A mock returning a constant would not survive one line of this file, which
 * is the point: the fixture cannot drift away from what the code actually asked for.
 */
@SpringBootTest(properties = {"nullnull.catalog.public-enabled=true",
        "nullnull.capabilities.optimization=true", "nullnull.jobs.enabled=true",
        "nullnull.jobs.poll-interval=PT0.02S", "nullnull.jobs.retry-backoff=PT1S",
        "nullnull.jobs.max-retry-backoff=PT1S"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-051 the ITEM optimizer produces a preview")
class OptimizeItemIT {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate DAY_ONE = LocalDate.parse("2026-10-04");
    private static final LocalDate DAY_TWO = LocalDate.parse("2026-10-05");
    private static final LocalTime AT_NINE = LocalTime.of(9, 0);
    private static final String FORECAST_SOURCE = "KTO_CONCENTRATION_FORECAST";

    /**
     * Crowded where the item is, quiet the day after. The gap is 60, and it has to clear the policy's
     * own minimum of 5 for {@code KTO_RELATIVE_CONCENTRATION_INDEX} - a fixture whose improvement sat
     * under that pin would be refused by the revalidator and the test would be measuring the pin
     * rather than the pipeline.
     */
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

    /**
     * Only this class's rows, each named by an id it created.
     *
     * <p>The required gate runs every suite against one database, so a blanket delete here would
     * either die on another class's rows or take them with it (AGENTS.md rule 6). {@code places} in
     * particular is referenced without cascade on purpose.
     */
    @AfterEach
    void removeOnlyOwnFixtures() {
        for (UUID runId : runIds) {
            jdbc.update("DELETE FROM background_jobs WHERE deduplication_key = ?", "optimization:" + runId);
        }
        // Trips first, and the order is the whole point. A run records which snapshot sets it froze
        // in optimization_run_snapshot_sets, so deleting the sets while a run still points at them
        // fails on that foreign key - which is what this class did until it got far enough to freeze
        // anything. The rows have to go in the direction the references point.
        //
        // The trip takes its items, its runs, each run's frozen-evidence rows, and each run's
        // proposals and changes with it; that cascade is declared in V013 and V024.
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
    @DisplayName("BA-051 a run with comparable forecasts reaches READY carrying its proposal")
    void aComparableForecastBecomesAStoredPreview() throws Exception {
        Fixture fixture = fixture();
        AtomicReference<ItemProposeRequest> asked = new AtomicReference<>();
        answerFromTheRequest(asked, new AtomicBoolean(), new AtomicBoolean());

        UUID runId = queue(fixture);
        awaitTerminal(runId);

        assertThat(runColumn(runId, "status")).isEqualTo("READY");
        // A fingerprint is the §8 value over what this run froze. READY without one would be a
        // preview nobody could later prove was computed from this evidence.
        assertThat(runColumn(runId, "data_fingerprint")).isNotNull();
        assertThat(runColumn(runId, "failure_code")).isNull();

        Map<String, Object> proposal = jdbc.queryForMap(
                "SELECT * FROM optimization_proposals WHERE run_id = ?", runId);
        assertThat(proposal.get("rank")).isEqualTo(1);
        assertThat(proposal.get("comparison_eligible")).isEqualTo(true);
        assertThat(proposal.get("comparison_reason_code")).isNull();
        // after - before: moving off the crowded day is an improvement, so the delta is negative.
        // Stored as the measurement it is rather than as its absolute value, because a reader cannot
        // tell which direction an unsigned number went.
        assertThat((BigDecimal) proposal.get("crowd_delta"))
                .isEqualByComparingTo(QUIET.subtract(CROWDED));
        // P0 has no route provider, so this is null on every row the system can currently write.
        assertThat(proposal.get("travel_minutes_delta")).isNull();
        assertThat(proposal.get("summary")).isEqualTo("이 날이 덜 붐빕니다.");

        Map<String, Object> change = jdbc.queryForMap("SELECT * FROM optimization_changes WHERE"
                + " proposal_id = ?", proposal.get("id"));
        assertThat(change.get("operation")).isEqualTo("MOVE");
        assertThat(change.get("before_value").toString()).contains(DAY_ONE.toString());
        assertThat(change.get("after_value").toString()).contains(DAY_TWO.toString());
    }

    @Test
    @DisplayName("BA-051-T4 no transaction is open when items/propose is called")
    void appsAiIsAskedOutsideTheUnitOfWork() throws Exception {
        Fixture fixture = fixture();
        AtomicBoolean transactionAtProposeTime = new AtomicBoolean(true);
        answerFromTheRequest(new AtomicReference<>(), transactionAtProposeTime, new AtomicBoolean());

        UUID runId = queue(fixture);
        awaitTerminal(runId);

        // Measured inside the answer - the callee's own view of the caller's transaction - rather
        // than by trusting the guard that sits beside the call. A guard asserts the same fact from
        // the side that could be deleted without anything noticing.
        assertThat(transactionAtProposeTime)
                .as("a network round trip inside a transaction holds a database connection for its"
                        + " whole duration, and JobContext refuses to open one inside another anyway")
                .isFalse();
        // Non-vacuous: the call has to have happened at all. Without this the assertion above is
        // satisfied by a run that failed before it ever asked.
        assertThat(runColumn(runId, "status")).isEqualTo("READY");
    }

    @Test
    @DisplayName("BA-051 no transaction is open when an explanation is rendered either")
    void explanationsAreRenderedOutsideTheUnitOfWork() throws Exception {
        Fixture fixture = fixture();
        AtomicBoolean transactionAtRenderTime = new AtomicBoolean(true);
        answerFromTheRequest(new AtomicReference<>(), new AtomicBoolean(), transactionAtRenderTime);

        UUID runId = queue(fixture);
        awaitTerminal(runId);

        // The second call out of this process, and it is made once per proposal. Rendering inside
        // the transaction that stores the preview would hold the connection for as many round trips
        // as there are proposals. Carried as its own case rather than folded into BA-051-T4, whose
        // clause names items/propose.
        assertThat(transactionAtRenderTime).isFalse();
        assertThat(runColumn(runId, "status")).isEqualTo("READY");
    }

    @Test
    @DisplayName("BA-051-T10 computing a preview writes nothing to the itinerary")
    void aPreviewChangesNothingAboutTheTrip() throws Exception {
        Fixture fixture = fixture();
        answerFromTheRequest(new AtomicReference<>(), new AtomicBoolean(), new AtomicBoolean());

        Map<String, Object> itemBefore = jdbc.queryForMap("SELECT * FROM trip_items WHERE id = ?",
                fixture.itemId());
        long versionBefore = jdbc.queryForObject("SELECT version FROM trips WHERE id = ?", Long.class,
                fixture.tripId());
        int revisionsBefore = jdbc.queryForObject("SELECT count(*) FROM trip_revisions WHERE trip_id = ?",
                Integer.class, fixture.tripId());

        UUID runId = queue(fixture);
        awaitTerminal(runId);
        assertThat(runColumn(runId, "status")).isEqualTo("READY");

        // Invariant 3. The preview exists and says the item should move; the item has not moved, the
        // trip's version has not changed, and no revision was recorded. A proposal is an offer, and
        // nothing about the itinerary is true differently because one was made.
        assertThat(jdbc.queryForMap("SELECT * FROM trip_items WHERE id = ?", fixture.itemId()))
                .isEqualTo(itemBefore);
        assertThat(jdbc.queryForObject("SELECT version FROM trips WHERE id = ?", Long.class,
                fixture.tripId())).isEqualTo(versionBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_revisions WHERE trip_id = ?",
                Integer.class, fixture.tripId())).isEqualTo(revisionsBefore);
        // And the preview really is one that proposes a change - otherwise "nothing moved" is what a
        // preview proposing nothing would also show.
        assertThat(jdbc.queryForObject("SELECT after_value::text FROM optimization_changes c"
                + " JOIN optimization_proposals p ON p.id = c.proposal_id WHERE p.run_id = ?",
                String.class, runId)).contains(DAY_TWO.toString());
    }

    @Test
    @DisplayName("BA-051 a trip with no stored forecast fails as DATA_INSUFFICIENT, not NO_IMPROVEMENT")
    void aTripWithNothingToCompareSaysSo() throws Exception {
        // Everything except the forecasts, so the run gets as far as assembling candidates and finds
        // none. #225 added this code precisely so the answer would not have to borrow one that means
        // something else.
        Fixture fixture = fixtureWithoutForecasts();
        answerFromTheRequest(new AtomicReference<>(), new AtomicBoolean(), new AtomicBoolean());

        UUID runId = queue(fixture);
        awaitTerminal(runId);

        assertThat(runColumn(runId, "status")).isEqualTo("FAILED");
        assertThat(runColumn(runId, "failure_code")).isEqualTo("DATA_INSUFFICIENT");
        // Nothing was judged, so nothing may be stored as having been judged.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM optimization_proposals WHERE run_id = ?",
                Integer.class, runId)).isZero();
    }

    @Test
    @DisplayName("BA-051-T5 a READY preview past its deadline answers 410 PREVIEW_EXPIRED")
    void anExpiredPreviewIsGoneRatherThanReadable() throws Exception {
        Fixture fixture = fixture();
        answerFromTheRequest(new AtomicReference<>(), new AtomicBoolean(), new AtomicBoolean());

        UUID runId = queue(fixture);
        awaitTerminal(runId);

        // The negative control, and it has to come first. Without it, "410 after the deadline" is
        // also what a run that was never readable at all would produce - the assertion would be
        // satisfied by a server that refuses this run for any reason whatsoever.
        poll(fixture, runId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));

        // The deadline passes, written as a past expires_at rather than by moving a clock. The
        // window closing is a property of the row, not of time: pushing the test clock far enough
        // to expire a preview also expires the caller's session, and it could no longer ask.
        jdbc.update("UPDATE optimization_runs SET expires_at = queued_at - interval '1 minute'"
                + " WHERE id = ?", runId);

        poll(fixture, runId)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("PREVIEW_EXPIRED"))
                // Not retryable, and that is not a detail: asking again cannot bring the preview
                // back, so a retryable answer would have clients poll for something that is gone.
                .andExpect(jsonPath("$.retryable").value(false));
    }

    /**
     * An answer built out of the request the handler actually assembled.
     *
     * <p>Each flag is written at the moment its call is made, so a caller that opened a transaction
     * first is recorded by the callee rather than reported by the caller.
     */
    private void answerFromTheRequest(AtomicReference<ItemProposeRequest> asked,
            AtomicBoolean transactionAtProposeTime, AtomicBoolean transactionAtRenderTime) {
        PolicyDescriptor policy = new PolicyDescriptor(PolicyPins.V1.policyVersion(),
                PolicyPins.V1.policyHash(), PolicyPins.V1.pipelineVersion(), "test-service");
        org.mockito.Mockito.when(recommendations.policy()).thenReturn(policy);
        org.mockito.Mockito.when(hours.windowsFor(any(), any(), any(), any())).thenAnswer(invocation ->
                Map.of(DAY_ONE, openAllDay(), DAY_TWO, openAllDay()));
        org.mockito.Mockito.when(recommendations.proposeItem(any())).thenAnswer(invocation -> {
            transactionAtProposeTime.set(TransactionSynchronizationManager.isActualTransactionActive());
            ItemProposeRequest request = invocation.getArgument(0);
            asked.set(request);
            TemporalCandidateIn candidate = request.candidates().stream()
                    .filter(offered -> offered.date().equals(DAY_TWO)).findFirst()
                    .orElseThrow(() -> new AssertionError("the handler offered no candidate for "
                            + DAY_TWO + "; it offered " + request.candidates()));
            ItemProposalOut proposal = new ItemProposalOut(1, candidate.date(),
                    candidate.effectiveStartTime(request.target().startTime()),
                    DAY_ONE.atStartOfDay(SEOUL).toInstant(), DAY_TWO.atStartOfDay(SEOUL).toInstant(),
                    new BigDecimal("0.480000"),
                    // Exactly before - after. The revalidator recomputes this from the candidate it
                    // hydrated, so an invented number is refused as IMPROVEMENT_MISMATCH.
                    candidate.beforeValue().subtract(candidate.afterValue()),
                    new BigDecimal("0.600000"), new BigDecimal("0.000000"),
                    candidate.beforeSnapshotId(), candidate.afterSnapshotId(), Map.of());
            return new ItemProposeResponse(policy.policyVersion(), policy.policyHash(),
                    policy.pipelineVersion(), ItemProposeResponse.Outcome.PROPOSALS,
                    List.of(proposal), List.of(), 1, Map.of());
        });
        org.mockito.Mockito.when(recommendations.renderExplanation(any())).thenAnswer(invocation -> {
            transactionAtRenderTime.set(TransactionSynchronizationManager.isActualTransactionActive());
            return new ExplanationRenderResponse(policy.policyVersion(), policy.policyHash(),
                    policy.pipelineVersion(), "이 날이 덜 붐빕니다.", "TEMPLATE");
        });
    }

    /**
     * Both trip days open wide enough to hold the stay.
     *
     * <p>An absent window is {@code OPENING_HOURS_UNKNOWN} to the revalidator, not "open" - the
     * catalog has no curated hours for a fixture place, so leaving this out would refuse every
     * proposal for a reason this file is not about.
     */
    private static CatalogOpeningWindow openAllDay() {
        return new CatalogOpeningWindow(CatalogOpeningWindow.State.OPEN, LocalTime.of(8, 0),
                LocalTime.of(20, 0));
    }

    private record Fixture(UUID tripId, UUID itemId, UUID placeId, SessionService.Bootstrap owner) {
    }

    private Fixture fixture() throws Exception {
        Fixture fixture = fixtureWithoutForecasts();
        insertForecasts(fixture.placeId());
        return fixture;
    }

    private Fixture fixtureWithoutForecasts() throws Exception {
        SessionService.Bootstrap owner = sessions.bootstrap(null, null, null);
        UUID tripId = createTrip(owner);
        UUID placeId = insertPlace();
        UUID itemId = insertItem(tripId, placeId);
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
                + " VALUES (?, 'BA-051 대상 장소', 'A0101', 37.566535, 126.978001, '1', 'ACTIVE', ?, ?)",
                placeId, now, now);
        // The handler reads a localised name because it travels into a sentence a traveller reads.
        jdbc.update("INSERT INTO place_localizations (id, place_id, locale, name, address, updated_at)"
                + " VALUES (?, ?, 'ko-KR', 'BA-051 대상 장소', '서울시 종로구', ?)",
                UUID.randomUUID(), placeId, now);
        return placeId;
    }

    private UUID insertItem(UUID tripId, UUID placeId) {
        UUID itemId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        // A duration, deliberately. Without one the revalidator answers DURATION_UNKNOWN for every
        // proposal - it cannot say a stay fits inside opening hours without knowing how long it is -
        // and the run would fail for a reason that has nothing to do with what this file measures.
        jdbc.update("INSERT INTO trip_items (id, trip_id, place_id, trip_date, position, start_time,"
                + " duration_minutes, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, 0, CAST(? AS time), 90, ?, ?)",
                itemId, tripId, placeId, java.sql.Date.valueOf(DAY_ONE), AT_NINE.toString(), now, now);
        return itemId;
    }

    /**
     * One fresh forecast set covering both trip days for this place.
     *
     * <p>Both points come from the same set, the same issue and the same normalization version,
     * because that is what makes them comparable at all - a pair drawn from two sets is refused by
     * {@code CrowdProvenanceProjection} and would arrive at the service marked ineligible.
     */
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
        String issue = "ba051-" + set;
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
        // Seoul midnight, which is where the one forecast source P0 has puts its points. The
        // assembler resolves candidates by day for exactly this reason.
        Instant targetAt = day.atStartOfDay(SEOUL).toInstant();
        jdbc.update("INSERT INTO crowd_snapshots (id, snapshot_set_id, source_code,"
                + " source_registry_version, place_id, source_state, observed_at, target_at,"
                + " fetched_at, stale_at, metric_code, value, unit, ordinal_level, confidence,"
                + " quality_flags, forecast_issue_id, comparison_group_id, normalization_version,"
                + " observed_at_skew_seconds, scope, scope_label, mapping_type, fallback_used,"
                + " created_at) VALUES (?, ?, ?, ?, ?, 'FORECAST', NULL, ?, ?, ?,"
                + " 'KTO_RELATIVE_CONCENTRATION_INDEX', ?, 'relative-index', NULL, NULL, '[]'::jsonb,"
                + " ?, ?, 'kto-tats-cnctr-rate-v4.1', NULL, 'PLACE', 'BA-051 fixture', 'DIRECT',"
                + " false, ?)",
                UUID.randomUUID(), set, FORECAST_SOURCE, sourceVersion, placeId,
                Timestamp.from(targetAt), Timestamp.from(fetchedAt), Timestamp.from(staleAt), value,
                issue, issue, Timestamp.from(fetchedAt));
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

    /** Waits for a status the worker will not move again. */
    private void awaitTerminal(UUID runId) {
        org.awaitility.Awaitility.await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(50, TimeUnit.MILLISECONDS)
                .until(() -> {
                    String status = runColumn(runId, "status");
                    return List.of("READY", "FAILED", "APPLIED", "REVERTED").contains(status);
                });
    }

    private ResultActions poll(Fixture fixture, UUID runId) throws Exception {
        return mvc.perform(get("/api/v1/optimizations/" + runId).cookie(cookie(fixture.owner())));
    }

    private String runColumn(UUID runId, String column) {
        Object value = jdbc.queryForMap("SELECT * FROM optimization_runs WHERE id = ?", runId).get(column);
        return value == null ? null : value.toString();
    }

    private static Cookie cookie(SessionService.Bootstrap owner) {
        return new Cookie("__Host-nullnull_session", owner.cookie);
    }
}
