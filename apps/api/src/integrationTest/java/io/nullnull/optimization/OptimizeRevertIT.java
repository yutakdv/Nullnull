package io.nullnull.optimization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import io.nullnull.testsupport.MutableClock;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * BA-053 revertOptimizationDecision and listOptimizationHistory.
 *
 * <p>Every case starts from a real APPLY: the BA-051 pipeline runs end to end, the traveller's APPLY
 * moves the itinerary, and only then is the undo attempted. A hand-written decision row would let
 * these assertions pass against an APPLY no code path can produce - and this card in particular
 * cannot afford that, because what a revert writes back is exactly what the APPLY recorded.
 *
 * <p>The gateway is mocked because it is the process boundary (ADR-0006); the decision store, the
 * trip module and the changes being reversed are real.
 */
@SpringBootTest(properties = {"nullnull.catalog.public-enabled=true",
        "nullnull.capabilities.optimization=true", "nullnull.jobs.enabled=true",
        "nullnull.jobs.poll-interval=PT0.02S", "nullnull.jobs.retry-backoff=PT1S",
        "nullnull.jobs.max-retry-backoff=PT1S"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-053 taking an optimization back, and BA-054 saying whether it can be")
class OptimizeRevertIT {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate DAY_ONE = LocalDate.parse("2026-10-04");
    private static final LocalDate DAY_TWO = LocalDate.parse("2026-10-05");
    private static final LocalTime AT_NINE = LocalTime.of(9, 0);
    private static final String FORECAST_SOURCE = "KTO_CONCENTRATION_FORECAST";
    private static final BigDecimal CROWDED = new BigDecimal("80.0000");
    private static final BigDecimal QUIET = new BigDecimal("20.0000");
    private static final String ORIGIN = "http://localhost:5173";

    /**
     * A clock this class can move, because the revert window is closed by time passing.
     *
     * <p>The first attempt aged the row instead - {@code UPDATE optimization_decisions SET
     * revert_until} - and V030's immutability trigger refused it. That refusal was right and it is
     * the schema saying the technique was wrong: a decision is a record of what a person chose, so
     * there is no supported way to make one older than it is. Moving the clock closes the window the
     * way the window actually closes.
     *
     * <p>TripImportIT warns that pushing a clock expires the caller's session before it can reach the
     * thing being tested. That warning is about its own coincidence - a 24-hour draft and a 24-hour
     * key retention ending together - and not a general bar: sessions idle at P30D and cap at P90D,
     * so the 25 hours needed here is far inside both. Measured before relying on it.
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class Time {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        MutableClock revertClock() {
            return MutableClock.at(Instant.parse("2026-10-01T00:00:00Z"));
        }
    }

    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired MutableClock clock;
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
        // Decisions before trips: optimization_decisions.before_revision_id/after_revision_id
        // reference trip_revisions WITHOUT cascade, so deleting the trip would try to take its
        // revisions while a decision still points at them. Every case here that succeeds writes at
        // least one such decision, and the reverts write two.
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

    // ------------------------------------------------------------------ T1

    @Test
    @DisplayName("BA-053-T1 a revert inside the window puts the itinerary back and ends the run REVERTED")
    void aRevertInsideTheWindowRestoresTheItinerary() throws Exception {
        Fixture fixture = fixture();
        UUID runId = readyRun(fixture);
        UUID proposalId = proposalOf(runId);

        UUID applied = decisionIdOf(decide(fixture, runId, proposalId, "APPLY", "\"1\"")
                .andExpect(status().isOk()));
        assertThat(itemDate(fixture.itemId()))
                .as("the apply really moved the item, so the undo has something to undo")
                .isEqualTo(DAY_TWO.toString());

        // Time passes between deciding and undoing, and the sequence below needs it to. A frozen
        // clock stamps both decisions with the same decidedAt AND mints both ids from the same
        // instant, so ORDER BY decided_at, id loses both of its keys at once and the two rows come
        // back in either order. This assertion was green before the clock was fixed, for a reason
        // that was never stated: the wall clock happened to tick.
        clock.advance(Duration.ofSeconds(1));

        revert(fixture, applied, "\"2\"", "revert-" + UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("REVERT"))
                .andExpect(jsonPath("$.revertedDecisionId").value(applied.toString()))
                // A new revision, never an overwrite: the contract says history is never rewritten,
                // so the trip moved forward to get back to where it was.
                .andExpect(jsonPath("$.resultingTripVersion").value(3));

        assertThat(itemDate(fixture.itemId()))
                .as("the recorded change was written back to the value it replaced")
                .isEqualTo(DAY_ONE.toString());
        assertThat(runColumn(runId, "status")).isEqualTo("REVERTED");
        assertThat(decisionKinds(runId)).containsExactly("APPLY", "REVERT");
    }

    @Test
    @DisplayName("BA-053-T1 a revert one second before revertUntil still restores the itinerary")
    void aRevertJustInsideTheWindowRestoresTheItinerary() throws Exception {
        Fixture fixture = fixture();
        UUID runId = readyRun(fixture);
        ResultActions decided = decide(fixture, runId, proposalOf(runId), "APPLY", "\"1\"")
                .andExpect(status().isOk());
        UUID applied = decisionIdOf(decided);
        Instant revertUntil = revertUntilOf(decided);

        // The last moment the window is open. The boundary is read from the APPLY rather than computed
        // here, so the case measures the stored window and not this file's idea of 24 hours.
        clock.set(revertUntil.minusSeconds(1));

        // A fresh CSRF token: nearly a day has passed and the token lives PT2H (see BA-053-T5).
        revertWith(fixture, applied, "\"2\"", "revert-" + UUID.randomUUID(), freshCsrf(fixture))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("REVERT"));
        assertThat(itemDate(fixture.itemId())).isEqualTo(DAY_ONE.toString());
        assertThat(runColumn(runId, "status")).isEqualTo("REVERTED");
    }

    @Test
    @DisplayName("BA-053-T4 a revert at exactly revertUntil is refused as expired")
    void aRevertAtTheBoundaryIsRefused() throws Exception {
        Fixture fixture = fixture();
        UUID runId = readyRun(fixture);
        ResultActions decided = decide(fixture, runId, proposalOf(runId), "APPLY", "\"1\"")
                .andExpect(status().isOk());
        UUID applied = decisionIdOf(decided);
        Instant revertUntil = revertUntilOf(decided);

        // revertUntil is the first instant the undo is gone, not the last one it is offered:
        // OptimizationService compares !now.isBefore(revertUntil). A comparison written as isAfter
        // would let this one instant through, and only a case AT the boundary can tell the two apart -
        // one second either side answers the same for both.
        clock.set(revertUntil);

        revertWith(fixture, applied, "\"2\"", "revert-" + UUID.randomUUID(), freshCsrf(fixture))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("REVERT_WINDOW_EXPIRED"));
        assertThat(itemDate(fixture.itemId())).isEqualTo(DAY_TWO.toString());
        assertThat(decisionKinds(runId)).containsExactly("APPLY");
    }

    @Test
    @DisplayName("BA-053-T5 a revert after the window closes is refused and changes nothing")
    void aRevertAfterTheWindowIsRefused() throws Exception {
        Fixture fixture = fixture();
        UUID runId = readyRun(fixture);
        UUID proposalId = proposalOf(runId);
        UUID applied = decisionIdOf(decide(fixture, runId, proposalId, "APPLY", "\"1\"")
                .andExpect(status().isOk()));

        // Past the stored revertUntil, which the APPLY set to decidedAt + 24h.
        clock.advance(Duration.ofHours(25));

        // A fresh CSRF token, because the request carries TWO time-bound credentials and they expire
        // on different schedules: the session idles at P30D but APP_CSRF_TOKEN_TTL is PT2H. Checking
        // only the session and concluding "25 hours is safe" is how this first ran - the call came
        // back 403 from the CSRF filter, having never reached the window it was meant to measure.
        // The 410 assertion below is what caught that, by failing on the status rather than the code.
        revertWith(fixture, applied, "\"2\"", "revert-" + UUID.randomUUID(), freshCsrf(fixture))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("REVERT_WINDOW_EXPIRED"))
                // Not retryable: waiting does not reopen a window that closed by elapsing.
                .andExpect(jsonPath("$.retryable").value(false));

        assertThat(itemDate(fixture.itemId()))
                .as("a refused revert leaves the applied itinerary exactly as it was")
                .isEqualTo(DAY_TWO.toString());
        assertThat(runColumn(runId, "status")).isEqualTo("APPLIED");
        assertThat(decisionKinds(runId)).containsExactly("APPLY");
    }

    @Test
    @DisplayName("BA-053-T6 the same Idempotency-Key replays one revert instead of performing two")
    void theSameKeyReplaysTheRevert() throws Exception {
        Fixture fixture = fixture();
        UUID runId = readyRun(fixture);
        UUID proposalId = proposalOf(runId);
        UUID applied = decisionIdOf(decide(fixture, runId, proposalId, "APPLY", "\"1\"")
                .andExpect(status().isOk()));

        // Same reason as the case above: the sequence assertion needs the two decisions to be
        // distinguishable by the order they were taken in.
        clock.advance(Duration.ofSeconds(1));

        String key = "revert-" + UUID.randomUUID();
        String first = revert(fixture, applied, "\"2\"", key).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String replayed = revert(fixture, applied, "\"2\"", key).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(replayed).as("a retry returns the stored answer rather than undoing again")
                .isEqualTo(first);
        assertThat(decisionKinds(runId)).containsExactly("APPLY", "REVERT");
        assertThat(itemDate(fixture.itemId())).isEqualTo(DAY_ONE.toString());
    }

    // ------------------------------------------------------------------ T2

    @Test
    @DisplayName("BA-053-T2 an edit made after the apply refuses the revert")
    void aLaterEditRefusesTheRevert() throws Exception {
        Fixture fixture = fixture();
        UUID runId = readyRun(fixture);
        UUID proposalId = proposalOf(runId);
        UUID applied = decisionIdOf(decide(fixture, runId, proposalId, "APPLY", "\"1\"")
                .andExpect(status().isOk()));

        // The edit is a note, deliberately: note is one of the two fields the stored aggregate
        // snapshot omits (the other is durationMinutes). If this operation restored the snapshot
        // rather than reversing the recorded changes, this is the value it would silently erase -
        // so the field that makes the card's correction concrete is also the one that moves the
        // version here.
        mvc.perform(patch("/api/v1/trips/" + fixture.tripId() + "/items/" + fixture.itemId())
                        .cookie(cookie(fixture.owner()))
                        .header("Origin", ORIGIN)
                        .header("X-CSRF-Token", fixture.owner().csrf.token)
                        .header("If-Match", "\"2\"")
                        .contentType("application/merge-patch+json")
                        .content("{\"note\":\"직접 적어 둔 메모\"}"))
                .andExpect(status().isOk());

        // If-Match carries "3", which is the CURRENT version - so the precondition is satisfied and
        // cannot be what refuses this. What refuses it is that the APPLY produced version 2: the
        // before-values on record describe a trip that no longer exists. That is why the check
        // compares against the decision's resultingTripVersion rather than trusting If-Match.
        revert(fixture, applied, "\"3\"", "revert-" + UUID.randomUUID())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRIP_CHANGED"));

        assertThat(itemDate(fixture.itemId())).isEqualTo(DAY_TWO.toString());
        assertThat(noteOf(fixture.itemId()))
                .as("the traveller's own later edit is still there")
                .isEqualTo("직접 적어 둔 메모");
        assertThat(decisionKinds(runId)).containsExactly("APPLY");
    }

    @Test
    @DisplayName("BA-053-T2 a trip metadata edit made after the apply refuses the revert")
    void aLaterMetadataEditRefusesTheRevert() throws Exception {
        Fixture fixture = fixture();
        UUID runId = readyRun(fixture);
        UUID applied = decisionIdOf(decide(fixture, runId, proposalOf(runId), "APPLY", "\"1\"")
                .andExpect(status().isOk()));

        // A trip-level edit touches no row the APPLY moved, so nothing but the version can tell the
        // revert that this is no longer the trip the APPLY left.
        mvc.perform(patch("/api/v1/trips/" + fixture.tripId())
                        .cookie(cookie(fixture.owner()))
                        .header("Origin", ORIGIN)
                        .header("X-CSRF-Token", fixture.owner().csrf.token)
                        .header("If-Match", "\"2\"")
                        .contentType("application/merge-patch+json")
                        .content("{\"title\":\"직접 고친 제목\"}"))
                .andExpect(status().isOk());
        // Without this the refusal below could come from a stale If-Match rather than from the edit.
        assertThat(tripVersion(fixture.tripId())).as("the edit raised the version").isEqualTo(3L);
        clock.advance(Duration.ofSeconds(1));

        revert(fixture, applied, "\"3\"", "revert-" + UUID.randomUUID())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRIP_CHANGED"));
        assertThat(itemDate(fixture.itemId())).isEqualTo(DAY_TWO.toString());
        assertThat(jdbc.queryForObject("SELECT title FROM trips WHERE id = ?", String.class,
                fixture.tripId())).as("the traveller's own later edit is still there").isEqualTo("직접 고친 제목");
        assertThat(decisionKinds(runId)).containsExactly("APPLY");
    }

    @Test
    @DisplayName("BA-053-T2 an interests edit made after the apply refuses the revert")
    void aLaterInterestsEditRefusesTheRevert() throws Exception {
        Fixture fixture = fixture();
        UUID runId = readyRun(fixture);
        UUID applied = decisionIdOf(decide(fixture, runId, proposalOf(runId), "APPLY", "\"1\"")
                .andExpect(status().isOk()));

        mvc.perform(put("/api/v1/trips/" + fixture.tripId() + "/interests")
                        .cookie(cookie(fixture.owner()))
                        .header("Origin", ORIGIN)
                        .header("X-CSRF-Token", fixture.owner().csrf.token)
                        .header("If-Match", "\"2\"")
                        .contentType("application/json")
                        .content("{\"interests\":[{\"code\":\"FOOD\",\"weight\":3}]}"))
                .andExpect(status().isOk());
        assertThat(tripVersion(fixture.tripId())).as("the edit raised the version").isEqualTo(3L);
        clock.advance(Duration.ofSeconds(1));

        revert(fixture, applied, "\"3\"", "revert-" + UUID.randomUUID())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRIP_CHANGED"));
        assertThat(itemDate(fixture.itemId())).isEqualTo(DAY_TWO.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_interests WHERE trip_id = ?",
                Integer.class, fixture.tripId())).as("the traveller's own later edit is still there").isOne();
        assertThat(decisionKinds(runId)).containsExactly("APPLY");
    }

    @Test
    @DisplayName("BA-053-T9 a KEEP cannot be reverted")
    void onlyAnApplyCanBeReverted() throws Exception {
        Fixture fixture = fixture();
        UUID runId = readyRun(fixture);
        UUID proposalId = proposalOf(runId);
        UUID kept = decisionIdOf(decide(fixture, runId, proposalId, "KEEP", "\"1\"")
                .andExpect(status().isOk()));

        // V030's own comment assigns this check to BA-053: a CHECK constraint sees only its own row,
        // and reverted_decision_id points at a different one. V033's unique index cannot see it
        // either - it forbids two reverts of the same target, not a wrong kind of target.
        revert(fixture, kept, "\"1\"", "revert-" + UUID.randomUUID())
                .andExpect(status().isNotFound());

        assertThat(decisionKinds(runId)).containsExactly("KEEP");
        assertThat(runColumn(runId, "status")).isEqualTo("KEPT");
    }

    @Test
    @DisplayName("BA-053-T9 a REVERT cannot itself be reverted")
    void aRevertCannotBeReverted() throws Exception {
        Fixture fixture = fixture();
        UUID runId = readyRun(fixture);
        UUID applied = decisionIdOf(decide(fixture, runId, proposalOf(runId), "APPLY", "\"1\"")
                .andExpect(status().isOk()));
        clock.advance(Duration.ofSeconds(1));
        UUID reverted = decisionIdOf(revert(fixture, applied, "\"2\"", "revert-" + UUID.randomUUID())
                .andExpect(status().isOk()));
        clock.advance(Duration.ofSeconds(1));

        // The same kind check as the KEEP case, reached by the other decision that is not an APPLY.
        // The If-Match is the version the REVERT produced, so a stale precondition cannot be what
        // refuses it.
        revert(fixture, reverted, "\"3\"", "revert-" + UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(decisionKinds(runId)).containsExactly("APPLY", "REVERT");
        assertThat(itemDate(fixture.itemId())).isEqualTo(DAY_ONE.toString());
    }

    @Test
    @DisplayName("BA-053-T11 a revert leaves candidate changes alone, which no version check could reach")
    void candidateChangesSurviveTheRevert() throws Exception {
        Fixture fixture = fixture();
        UUID runId = readyRun(fixture);
        UUID proposalId = proposalOf(runId);
        UUID applied = decisionIdOf(decide(fixture, runId, proposalId, "APPLY", "\"1\"")
                .andExpect(status().isOk()));

        // A place that is NOT on the itinerary: saving a candidate for one that is already scheduled
        // is a different rule's territory (#165's transition matrix), and this case is about what a
        // revert touches rather than about what a save is allowed to do.
        UUID saved = insertPlace("BA-053 후보 장소");
        mvc.perform(post("/api/v1/trips/" + fixture.tripId() + "/candidates")
                .cookie(cookie(fixture.owner()))
                .header("Origin", ORIGIN)
                .header("X-CSRF-Token", fixture.owner().csrf.token)
                .header("Idempotency-Key", "candidate-" + UUID.randomUUID())
                .contentType("application/json")
                .content("{\"placeId\":\"" + saved + "\",\"source\":{\"type\":\"SEARCH\"}}"));
        assertThat(candidateStatus(fixture.tripId(), saved))
                .as("the candidate this case is about was really saved")
                .isEqualTo("ACTIVE");

        // #165 Q3 decided ACTIVE and DISMISSED alike, and a restore widened to candidates could as easily
        // revive a dismissal as undo a save - so a second place is saved and then dismissed after the APPLY.
        UUID dismissed = insertPlace("BA-053 지운 후보 장소");
        mvc.perform(post("/api/v1/trips/" + fixture.tripId() + "/candidates")
                .cookie(cookie(fixture.owner()))
                .header("Origin", ORIGIN)
                .header("X-CSRF-Token", fixture.owner().csrf.token)
                .header("Idempotency-Key", "candidate-" + UUID.randomUUID())
                .contentType("application/json")
                .content("{\"placeId\":\"" + dismissed + "\",\"source\":{\"type\":\"SEARCH\"}}"));
        UUID dismissedId = jdbc.queryForObject("SELECT id FROM trip_candidates WHERE trip_id = ? AND place_id = ?",
                UUID.class, fixture.tripId(), dismissed);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/trips/" + fixture.tripId() + "/candidates/" + dismissedId)
                        .cookie(cookie(fixture.owner()))
                        .header("Origin", ORIGIN)
                        .header("X-CSRF-Token", fixture.owner().csrf.token))
                .andExpect(status().isNoContent());
        assertThat(candidateStatuses(fixture.tripId(), dismissed))
                .as("the candidate this case is about was really dismissed")
                .containsExactly("DISMISSED");

        // V016 says it outright: "there is no trigger or column here that touches trips, and that
        // absence is the point". So saving a candidate leaves the version at 2, and the revert's
        // TRIP_CHANGED check - which compares the caller's version against the one the APPLY
        // produced - CANNOT see this change at all. Nothing protects the candidate except the fact
        // that a revert writes back optimization_changes and touches nothing else.
        //
        // That is a property no guard enforces, so without this case it would hold by accident until
        // someone widened the restore. It is asserted here for that reason rather than because any
        // document says a snapshot would have wiped it: ERD section 9 says aggregate_snapshot carries
        // candidate linkage, but TripService.canonicalItems writes "candidates":[] unconditionally,
        // so today's snapshot has no candidates in it to restore.
        assertThat(tripVersion(fixture.tripId()))
                .as("saving a candidate does not raise the schedule version (invariant 2)")
                .isEqualTo(2L);

        clock.advance(Duration.ofSeconds(1));
        revert(fixture, applied, "\"2\"", "revert-" + UUID.randomUUID()).andExpect(status().isOk());

        assertThat(candidateStatus(fixture.tripId(), saved))
                .as("the traveller's candidate survives an undo of an unrelated optimization")
                .isEqualTo("ACTIVE");
        // Every row for the place, not the first: a revived dismissal would be a second, ACTIVE row beside it.
        assertThat(candidateStatuses(fixture.tripId(), dismissed))
                .as("and so does the traveller's dismissal")
                .containsExactly("DISMISSED");
        assertThat(itemDate(fixture.itemId())).isEqualTo(DAY_ONE.toString());
    }

    // ------------------------------------------------------------------ T3

    @Test
    @DisplayName("BA-053-T3 history lists only the caller's runs, filters by trip, and goes with the trip")
    void historyIsOwnerScopedFilteredAndCascades() throws Exception {
        Fixture mine = fixture();
        UUID myRun = readyRun(mine);
        Fixture stranger = fixture();
        UUID strangerRun = readyRun(stranger);

        String page = mvc.perform(get("/api/v1/optimizations").cookie(cookie(mine.owner())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.runId == '" + myRun + "')]").exists())
                // Invariant 11: another owner's run is not merely hidden from the body, it is absent.
                .andExpect(jsonPath("$.items[?(@.runId == '" + strangerRun + "')]").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(page).as("the history carries the link and the trip, not the itinerary")
                .contains("/trip/" + mine.tripId() + "/optimizations/" + myRun)
                .doesNotContain("\"proposals\"")
                .doesNotContain("\"changes\"");

        // A filter for a trip that is not the run's leaves the listing empty rather than unfiltered.
        mvc.perform(get("/api/v1/optimizations").param("tripId", stranger.tripId().toString())
                        .cookie(cookie(mine.owner())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));

        mvc.perform(get("/api/v1/optimizations").param("tripId", mine.tripId().toString())
                        .cookie(cookie(mine.owner())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.runId == '" + myRun + "')]").exists());

        // ERD section 6: a run lives for as long as its trip. Deleting the trip is the product's
        // erasure path, and a history line that outlived it would name an itinerary nobody can read.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/trips/" + mine.tripId())
                        .cookie(cookie(mine.owner()))
                        .header("Origin", ORIGIN)
                        .header("X-CSRF-Token", mine.owner().csrf.token)
                        .header("If-Match", "\"1\"")
                        // Required, like every retryable command. Sending only If-Match answered 400,
                        // which is a rejected request rather than a failed precondition - the two are
                        // easy to read as one when a delete is expected to fail for other reasons.
                        .header("Idempotency-Key", "delete-" + UUID.randomUUID()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/optimizations").cookie(cookie(mine.owner())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.runId == '" + myRun + "')]").doesNotExist());
    }

    @Test
    @DisplayName("BA-053-T10 a history cursor past its fifteen minutes is refused as expired")
    void anExpiredHistoryCursorIsRefused() throws Exception {
        Fixture fixture = fixture();
        UUID first = readyRun(fixture);
        decide(fixture, first, proposalOf(first), "KEEP", "\"1\"").andExpect(status().isOk());
        // A second run on the same trip, so a one-row page has a page after it.
        readyRun(fixture);

        String cursor = nextCursorOf(history(fixture, null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        // The negative control: the same cursor is accepted while it is fresh.
        history(fixture, cursor).andExpect(status().isOk());

        // OptimizationCursorProperties.CURSOR_TTL is fifteen minutes.
        clock.advance(Duration.ofMinutes(16));

        history(fixture, cursor)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CURSOR_EXPIRED"));
    }

    private ResultActions history(Fixture fixture, String cursor) throws Exception {
        var request = get("/api/v1/optimizations").param("limit", "1").cookie(cookie(fixture.owner()));
        if (cursor != null) {
            request.param("cursor", cursor);
        }
        return mvc.perform(request);
    }

    private static String nextCursorOf(String body) {
        java.util.regex.Matcher found = java.util.regex.Pattern.compile("\"nextCursor\":\"([^\"]+)\"")
                .matcher(body);
        assertThat(found.find()).as("a one-row page of two runs hands out a cursor").isTrue();
        return found.group(1);
    }

    @Test
    @DisplayName("BA-053-T7 two reverts of one APPLY sent at once: the trip version keeps one, the other is refused")
    void concurrentRevertsRecordOne() throws Exception {
        Fixture fixture = fixture();
        UUID runId = readyRun(fixture);
        UUID applied = decisionIdOf(decide(fixture, runId, proposalOf(runId), "APPLY", "\"1\"")
                .andExpect(status().isOk()));
        clock.advance(Duration.ofSeconds(1));

        // revertOptimizationDecision reads the APPLY and the run BEFORE the idempotency guard takes the
        // owner lock, so two reverts can both read before either writes. They are made to: both wait
        // on the decisions table to read, then the trip row is held so whichever enters its
        // transaction first stops there, holding the owner lock, while the other queues on the owner
        // row having read the APPLY. Only when that state is observed is the trip row let go. The
        // owner row itself cannot be what is held - the session check in front of the controller
        // locks it too, and both requests would stop before reading anything.
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try (java.sql.Connection decisionsHold = dataSource.getConnection();
                java.sql.Connection tripHold = dataSource.getConnection()) {
            decisionsHold.setAutoCommit(false);
            tripHold.setAutoCommit(false);
            int decisionsPid = backendPid(decisionsHold);
            int tripPid = backendPid(tripHold);
            try (java.sql.Statement statement = decisionsHold.createStatement()) {
                statement.execute("LOCK TABLE optimization_decisions IN ACCESS EXCLUSIVE MODE");
            }
            Future<MvcResult> first = callers.submit(() ->
                    revert(fixture, applied, "\"2\"", "revert-" + UUID.randomUUID()).andReturn());
            Future<MvcResult> second = callers.submit(() ->
                    revert(fixture, applied, "\"2\"", "revert-" + UUID.randomUUID()).andReturn());
            awaitBlocked(decisionsPid, "from optimization_decisions", 2);

            try (java.sql.PreparedStatement lock = tripHold.prepareStatement(
                    "SELECT id FROM trips WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, fixture.tripId());
                lock.executeQuery();
            }
            decisionsHold.rollback();
            awaitBlocked(tripPid, "from trips", 1);
            org.awaitility.Awaitility.await().atMost(30, TimeUnit.SECONDS).until(() -> jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                            + " AND query ILIKE '%from owners%'", Integer.class) == 1);
            tripHold.rollback();

            MvcResult a = first.get(60, TimeUnit.SECONDS);
            MvcResult b = second.get(60, TimeUnit.SECONDS);
            assertThat(List.of(a.getResponse().getStatus(), b.getResponse().getStatus()))
                    .containsExactlyInAnyOrder(200, 409);
            MvcResult refused = a.getResponse().getStatus() == 409 ? a : b;
            // The loser read the APPLY before the winner wrote, passed the resultingTripVersion check
            // on that read, and met the trip module's own read of the trip - version 3 now.
            assertThat(refused.getResponse().getContentAsString()).contains("\"TRIP_CHANGED\"");
        } finally {
            callers.shutdownNow();
        }
        assertThat(decisionKinds(runId)).containsExactly("APPLY", "REVERT");
        assertThat(itemDate(fixture.itemId())).isEqualTo(DAY_ONE.toString());
        assertThat(tripVersion(fixture.tripId())).as("moved back once").isEqualTo(3L);
    }

    private static int backendPid(java.sql.Connection connection) throws java.sql.SQLException {
        try (java.sql.Statement statement = connection.createStatement();
                java.sql.ResultSet pid = statement.executeQuery("SELECT pg_backend_pid()")) {
            pid.next();
            return pid.getInt(1);
        }
    }

    private void awaitBlocked(int holderPid, String queryFragment, int expected) {
        org.awaitility.Awaitility.await().atMost(30, TimeUnit.SECONDS).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))"
                        + " AND query ILIKE ?", Integer.class, holderPid, "%" + queryFragment + "%") == expected);
    }

    // ------------------------------------------------------- BA-054 projection

    @Test
    @DisplayName("BA-054-T1 a run with no APPLY decision reports NOT_APPLICABLE")
    void noApplyDecisionIsNotApplicable() throws Exception {
        Fixture fixture = fixture();
        UUID runId = readyRun(fixture);

        // READY, nothing decided. There is no undo to offer and no reason to pretend otherwise.
        assertThat(availability(fixture, runId)).isEqualTo("NOT_APPLICABLE");
    }

    @Test
    @DisplayName("BA-054-T2 a run already taken back reports REVERTED")
    void anAlreadyRevertedRunIsReverted() throws Exception {
        Fixture fixture = fixture();
        UUID runId = readyRun(fixture);
        UUID applied = decisionIdOf(decide(fixture, runId, proposalOf(runId), "APPLY", "\"1\"")
                .andExpect(status().isOk()));
        clock.advance(Duration.ofSeconds(1));
        revert(fixture, applied, "\"2\"", "revert-" + UUID.randomUUID()).andExpect(status().isOk());

        assertThat(availability(fixture, runId)).isEqualTo("REVERTED");
    }

    @Test
    @DisplayName("BA-054-T3 an APPLY past its window reports EXPIRED")
    void aClosedWindowIsExpired() throws Exception {
        Fixture fixture = fixture();
        UUID runId = readyRun(fixture);
        decide(fixture, runId, proposalOf(runId), "APPLY", "\"1\"").andExpect(status().isOk());

        assertThat(availability(fixture, runId))
                .as("still inside the window before the clock moves")
                .isEqualTo("AVAILABLE");
        clock.advance(Duration.ofHours(25));

        // The run is still APPLIED - a closed window does not un-apply anything - and only this
        // projection changes. Asserting the status too keeps that distinction from drifting.
        assertThat(availability(fixture, runId)).isEqualTo("EXPIRED");
        assertThat(runColumn(runId, "status")).isEqualTo("APPLIED");
    }

    @Test
    @DisplayName("BA-054-T4 an APPLY whose trip has since moved reports NOT_APPLICABLE")
    void aMovedTripIsNotApplicable() throws Exception {
        Fixture fixture = fixture();
        UUID runId = readyRun(fixture);
        decide(fixture, runId, proposalOf(runId), "APPLY", "\"1\"").andExpect(status().isOk());
        assertThat(availability(fixture, runId)).isEqualTo("AVAILABLE");

        editTheNote(fixture, "\"2\"");

        // Same value as T1 and a different reason: there IS an apply, but its before-values describe
        // a trip that no longer exists. The contract collapses the two on purpose - the caller is
        // told the undo is not offered, not why.
        assertThat(availability(fixture, runId)).isEqualTo("NOT_APPLICABLE");
    }

    @Test
    @DisplayName("BA-054-T5 an APPLY inside its window on an unchanged trip reports AVAILABLE")
    void anUndoableApplyIsAvailable() throws Exception {
        Fixture fixture = fixture();
        UUID runId = readyRun(fixture);
        decide(fixture, runId, proposalOf(runId), "APPLY", "\"1\"").andExpect(status().isOk());

        assertThat(availability(fixture, runId)).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("BA-054-T6 AVAILABLE is advisory — the revert still refuses a trip that moved")
    void availableDoesNotAuthoriseTheRevert() throws Exception {
        Fixture fixture = fixture();
        UUID runId = readyRun(fixture);
        UUID applied = decisionIdOf(decide(fixture, runId, proposalOf(runId), "APPLY", "\"1\"")
                .andExpect(status().isOk()));
        assertThat(availability(fixture, runId)).isEqualTo("AVAILABLE");

        // The read said yes, and then the world moved. If the projection were treated as permission
        // the revert would go through on the strength of a value that was true a moment ago - which
        // is exactly what a read-then-write race is. The mutation must re-decide for itself.
        editTheNote(fixture, "\"2\"");
        clock.advance(Duration.ofSeconds(1));

        revert(fixture, applied, "\"3\"", "revert-" + UUID.randomUUID())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRIP_CHANGED"));
        assertThat(decisionKinds(runId)).containsExactly("APPLY");
    }

    @Test
    @DisplayName("BA-054-T7 AVAILABLE is advisory — another owner's revert is the not-found a missing decision gets")
    void availableDoesNotAuthoriseAnotherOwner() throws Exception {
        Fixture fixture = fixture();
        UUID runId = readyRun(fixture);
        UUID applied = decisionIdOf(decide(fixture, runId, proposalOf(runId), "APPLY", "\"1\"")
                .andExpect(status().isOk()));
        assertThat(availability(fixture, runId)).isEqualTo("AVAILABLE");
        clock.advance(Duration.ofSeconds(1));

        // The run is undoable and the id is real; only the caller is wrong. Invariant 11 asks for the
        // answer a decision that never existed gets, not a refusal that confirms this one does.
        SessionService.Bootstrap stranger = sessions.bootstrap(null, null, null);
        revertAs(stranger, applied, "\"2\"", "revert-" + UUID.randomUUID(), stranger.csrf.token)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        revertAs(stranger, UUID.randomUUID(), "\"2\"", "revert-" + UUID.randomUUID(), stranger.csrf.token)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(decisionKinds(runId)).containsExactly("APPLY");
        assertThat(itemDate(fixture.itemId())).isEqualTo(DAY_TWO.toString());

        // The negative control. Two 404s agree just as easily when the request dies before ownership is
        // ever asked - a header the route rejects, a precondition it refuses - so the same call from the
        // owner has to go through, or the two answers above measured validation rather than ownership.
        revert(fixture, applied, "\"2\"", "revert-" + UUID.randomUUID()).andExpect(status().isOk());
    }

    @Test
    @DisplayName("BA-054-T8 AVAILABLE is advisory — a window that closes after it was read still refuses the revert")
    void availableDoesNotHoldTheWindowOpen() throws Exception {
        Fixture fixture = fixture();
        UUID runId = readyRun(fixture);
        UUID applied = decisionIdOf(decide(fixture, runId, proposalOf(runId), "APPLY", "\"1\"")
                .andExpect(status().isOk()));
        assertThat(availability(fixture, runId)).isEqualTo("AVAILABLE");

        // The caller read AVAILABLE and then waited. The window is stored on the APPLY, so the revert
        // asks it again rather than trusting an answer that was true when it was given.
        clock.advance(Duration.ofHours(25));

        // A fresh CSRF token for the reason the BA-053-T5 after-window case gives: PT2H, not P30D.
        revertWith(fixture, applied, "\"2\"", "revert-" + UUID.randomUUID(), freshCsrf(fixture))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("REVERT_WINDOW_EXPIRED"));
        assertThat(decisionKinds(runId)).containsExactly("APPLY");
        assertThat(itemDate(fixture.itemId())).isEqualTo(DAY_TWO.toString());
    }

    @Test
    @DisplayName("BA-054-T9 AVAILABLE is advisory — an APPLY already undone elsewhere is not undone twice")
    void availableDoesNotAuthoriseASecondUndo() throws Exception {
        Fixture fixture = fixture();
        UUID runId = readyRun(fixture);
        UUID applied = decisionIdOf(decide(fixture, runId, proposalOf(runId), "APPLY", "\"1\"")
                .andExpect(status().isOk()));
        assertThat(availability(fixture, runId)).isEqualTo("AVAILABLE");
        clock.advance(Duration.ofSeconds(1));

        // Another tab undoes it first, under its own key.
        revert(fixture, applied, "\"2\"", "revert-" + UUID.randomUUID()).andExpect(status().isOk());
        clock.advance(Duration.ofSeconds(1));

        // This tab still holds what it read: AVAILABLE, at version 2. A different key, so this is a
        // second undo and not a replay - replay is BA-053-T6's.
        //
        // The outcome is the claim, not the line that produces it. From HTTP the refusal comes from the
        // trip version, which the first undo moved and the trip module re-reads inside the
        // transaction - so even two undos that both read the APPLY before either writes meet it first
        // (BA-053-T7 races them). V033's unique reverted_decision_id sits behind it and is proven on
        // its own at the SQL layer by BA-053-T8. Hence 409 and either conflict code: the status says
        // the domain refused it rather than CSRF or validation, and neither code is pinned.
        revert(fixture, applied, "\"2\"", "revert-" + UUID.randomUUID())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(
                        org.hamcrest.Matchers.oneOf("TRIP_CHANGED", "DATA_CHANGED")));

        assertThat(decisionKinds(runId)).containsExactly("APPLY", "REVERT");
        assertThat(itemDate(fixture.itemId())).isEqualTo(DAY_ONE.toString());
        assertThat(tripVersion(fixture.tripId()))
                .as("one undo moved the trip once; a second would have moved it again")
                .isEqualTo(3L);
    }

    /** The projection as a caller reads it, through the operation that publishes it. */
    private String availability(Fixture fixture, UUID runId) throws Exception {
        return mvc.perform(get("/api/v1/optimizations/" + runId).cookie(cookie(fixture.owner())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s)^.*?\"revertAvailability\":\"([^\"]+)\".*$", "$1");
    }

    /** A note edit: it raises the trip version and is one of the two fields the snapshot omits. */
    private void editTheNote(Fixture fixture, String ifMatch) throws Exception {
        mvc.perform(patch("/api/v1/trips/" + fixture.tripId() + "/items/" + fixture.itemId())
                        .cookie(cookie(fixture.owner()))
                        .header("Origin", ORIGIN)
                        .header("X-CSRF-Token", fixture.owner().csrf.token)
                        .header("If-Match", ifMatch)
                        .contentType("application/merge-patch+json")
                        .content("{\"note\":\"직접 적어 둔 메모\"}"))
                .andExpect(status().isOk());
    }

    // ----------------------------------------------------------- fixtures

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
                .as("every case below acts on a preview the pipeline actually produced")
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
                .header("Origin", ORIGIN)
                .header("X-CSRF-Token", fixture.owner().csrf.token)
                .header("If-Match", ifMatch)
                .header("Idempotency-Key", "decide-" + UUID.randomUUID())
                .contentType("application/json")
                .content("{\"proposalId\":\"" + proposalId + "\",\"decision\":\"" + kind + "\"}"));
    }

    private ResultActions revert(Fixture fixture, UUID decisionId, String ifMatch, String key)
            throws Exception {
        return revertWith(fixture, decisionId, ifMatch, key, fixture.owner().csrf.token);
    }

    private ResultActions revertWith(Fixture fixture, UUID decisionId, String ifMatch, String key,
            String csrf) throws Exception {
        return revertAs(fixture.owner(), decisionId, ifMatch, key, csrf);
    }

    private ResultActions revertAs(SessionService.Bootstrap caller, UUID decisionId, String ifMatch,
            String key, String csrf) throws Exception {
        return mvc.perform(post("/api/v1/optimization-decisions/" + decisionId + "/revert")
                .cookie(cookie(caller))
                .header("Origin", ORIGIN)
                .header("X-CSRF-Token", csrf)
                .header("If-Match", ifMatch)
                .header("Idempotency-Key", key));
    }

    /** A new token on the SAME session, the way SessionTimeIT's BA-010-T2 renews one. */
    private String freshCsrf(Fixture fixture) {
        return sessions.issueCsrf(sessions.resolve(fixture.owner().cookie, false)).token;
    }

    private static Instant revertUntilOf(ResultActions decided) throws Exception {
        String body = decided.andReturn().getResponse().getContentAsString();
        java.util.regex.Matcher found = java.util.regex.Pattern.compile("\"revertUntil\":\"([^\"]+)\"")
                .matcher(body);
        assertThat(found.find()).as("an APPLY states its window").isTrue();
        return Instant.parse(found.group(1));
    }

    private static UUID decisionIdOf(ResultActions decided) throws Exception {
        String body = decided.andReturn().getResponse().getContentAsString();
        return UUID.fromString(body.replaceFirst("(?s)^.*?\"id\":\"([^\"]+)\".*$", "$1"));
    }

    /**
     * Stubbed with {@code doAnswer}, not {@code when}, because this class stubs more than once.
     *
     * <p>{@code when(mock.proposeItem(any()))} CALLS the mock to record the invocation, and on the
     * second call the previous answer runs for real with a null argument. The first run of T3 died
     * exactly there - {@code request is null} inside this lambda - which reads like a fixture that
     * built a bad request and is actually the stubbing syntax invoking the stub it is replacing.
     * {@code doAnswer(...).when(mock).proposeItem(any())} never invokes it.
     */
    private void answerFromTheRequest() {
        PolicyDescriptor policy = new PolicyDescriptor(PolicyPins.V1.policyVersion(),
                PolicyPins.V1.policyHash(), PolicyPins.V1.pipelineVersion(), "test-service");
        org.mockito.Mockito.doReturn(policy).when(recommendations).policy();
        org.mockito.Mockito.doAnswer(call -> Map.of(DAY_ONE, openAllDay(), DAY_TWO, openAllDay()))
                .when(hours).windowsFor(any(), any(), any(), any());
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
                    candidate.beforeSnapshotId(), candidate.afterSnapshotId(), Map.of());
            return new ItemProposeResponse(policy.policyVersion(), policy.policyHash(),
                    policy.pipelineVersion(), ItemProposeResponse.Outcome.PROPOSALS,
                    List.of(proposal), List.of(), 1, Map.of());
        }).when(recommendations).proposeItem(any());
        org.mockito.Mockito.doAnswer(call ->
                new ExplanationRenderResponse(policy.policyVersion(), policy.policyHash(),
                        policy.pipelineVersion(), "이 날이 덜 붐빕니다.", "TEMPLATE"))
                .when(recommendations).renderExplanation(any());
    }

    private static CatalogOpeningWindow openAllDay() {
        return new CatalogOpeningWindow(CatalogOpeningWindow.State.OPEN, LocalTime.of(8, 0),
                LocalTime.of(20, 0));
    }

    private UUID queue(Fixture fixture) throws Exception {
        String created = mvc.perform(post("/api/v1/trips/" + fixture.tripId() + "/optimizations")
                        .cookie(cookie(fixture.owner()))
                        .header("Origin", ORIGIN)
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
                        .header("Origin", ORIGIN)
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
        return insertPlace("BA-053 대상 장소");
    }

    private UUID insertPlace(String name) {
        UUID placeId = UUID.randomUUID();
        places.add(placeId);
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), SEOUL);
        jdbc.update("INSERT INTO places (id, canonical_name, category_code, latitude, longitude,"
                + " region_code, status, created_at, updated_at)"
                + " VALUES (?, ?, 'A0101', 37.566535, 126.978001, '1', 'ACTIVE', ?, ?)",
                placeId, name, now, now);
        jdbc.update("INSERT INTO place_localizations (id, place_id, locale, name, address, updated_at)"
                + " VALUES (?, ?, 'ko-KR', ?, '서울시 종로구', ?)",
                UUID.randomUUID(), placeId, name, now);
        return placeId;
    }

    private UUID insertItem(UUID tripId, UUID placeId) {
        UUID itemId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), SEOUL);
        jdbc.update("INSERT INTO trip_items (id, trip_id, place_id, trip_date, position, start_time,"
                + " duration_minutes, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, 0, CAST(? AS time), 90, ?, ?)",
                itemId, tripId, placeId, java.sql.Date.valueOf(DAY_ONE), AT_NINE.toString(), now, now);
        return itemId;
    }

    /**
     * Fixture times come from the same clock the application reads.
     *
     * <p>Not tidiness: once one test advances the clock 25 hours, a fixture stamped with the wall
     * clock would sit 25 hours in the app's past, and its snapshots would be stale before the
     * pipeline ever looked at them. The failure would land in whichever test ran next, which is the
     * kind of order-dependent red that gets blamed on the test that reports it.
     */
    private void insertForecasts(UUID placeId) {
        Instant fetchedAt = clock.instant();
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
        String issue = "ba053-" + set;
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
                + " ?, ?, 'kto-tats-cnctr-rate-v4.1', NULL, 'PLACE', 'BA-053 fixture', 'DIRECT',"
                + " false, ?)",
                UUID.randomUUID(), set, FORECAST_SOURCE, sourceVersion, placeId,
                Timestamp.from(targetAt), Timestamp.from(fetchedAt), Timestamp.from(staleAt), value,
                issue, issue, Timestamp.from(fetchedAt));
    }

    private List<String> decisionKinds(UUID runId) {
        return jdbc.queryForList("SELECT decision FROM optimization_decisions WHERE run_id = ?"
                + " ORDER BY decided_at, id", String.class, runId);
    }

    /** Named by trip and place, so it reads this class's own row and not another class's. */
    private String candidateStatus(UUID tripId, UUID placeId) {
        List<String> found = jdbc.queryForList(
                "SELECT status FROM trip_candidates WHERE trip_id = ? AND place_id = ?",
                String.class, tripId, placeId);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Every row for the place. The partial unique index lets a DISMISSED row sit beside a live one, so the
     * first row alone cannot tell a kept dismissal from a revived one.
     */
    private List<String> candidateStatuses(UUID tripId, UUID placeId) {
        return jdbc.queryForList("SELECT status FROM trip_candidates WHERE trip_id = ? AND place_id = ?",
                String.class, tripId, placeId);
    }

    private long tripVersion(UUID tripId) {
        return jdbc.queryForObject("SELECT version FROM trips WHERE id = ?", Long.class, tripId);
    }

    private String itemDate(UUID itemId) {
        return jdbc.queryForObject("SELECT trip_date FROM trip_items WHERE id = ?", String.class, itemId);
    }

    private String noteOf(UUID itemId) {
        return jdbc.queryForObject("SELECT note FROM trip_items WHERE id = ?", String.class, itemId);
    }

    private String runColumn(UUID runId, String column) {
        Object value = jdbc.queryForMap("SELECT * FROM optimization_runs WHERE id = ?", runId).get(column);
        return value == null ? null : value.toString();
    }

    private static Cookie cookie(SessionService.Bootstrap owner) {
        return new Cookie("__Host-nullnull_session", owner.cookie);
    }
}
