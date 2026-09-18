package io.nullnull.optimization;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.testsupport.TestcontainersConfiguration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * BA-052, the V030 half: what the database will accept as a decision.
 *
 * <p>Raw SQL, and every rejection followed by the same row made legal - the discipline that turns
 * "it threw" into "it threw for this reason". The application layer is not involved on purpose: the
 * question is what survives a writer that forgets whatever the service checks first.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-052 optimization decision schema guards")
class OptimizationDecisionSchemaIT {

    @Autowired
    JdbcTemplate jdbc;

    private UUID ownerId;
    private UUID tripId;
    private UUID runId;
    private UUID proposalId;

    @AfterEach
    void removeOnlyOwnFixtures() {
        jdbc.update("DELETE FROM optimization_decisions WHERE run_id = ?", runId);
        jdbc.update("DELETE FROM optimization_changes WHERE proposal_id = ?", proposalId);
        jdbc.update("DELETE FROM optimization_proposals WHERE run_id = ?", runId);
        jdbc.update("DELETE FROM optimization_runs WHERE id = ?", runId);
        jdbc.update("DELETE FROM trips WHERE id = ?", tripId);
        jdbc.update("DELETE FROM owners WHERE id = ?", ownerId);
    }

    @Test
    @DisplayName("BA-052-T9 a decision carries exactly the fields its kind has, in all three shapes")
    void eachDecisionCarriesItsOwnFields() {
        seed();
        UUID before = revision();
        UUID after = revision();

        // Every refused row breaks ONE conjunct of the shape CHECK and is refused by that constraint by
        // name. A row that broke two conjuncts - or that also broke the reverted_decision_id foreign
        // key, as a random target does - would stay refused whichever conjunct was removed, and "it
        // threw" would prove nothing about the one this line is about.

        // A KEEP changes nothing, so it carries none of the five.
        refusedByShape("KEEP", 7L, null, null, null, false);
        refusedByShape("KEEP", null, before, null, null, false);
        refusedByShape("KEEP", null, null, after, null, false);
        refusedByShape("KEEP", null, null, null, null, true);
        UUID kept = decision("KEEP", null, null, null, null, false);
        // Removed again so the run can take the APPLY the rest of the case needs: one initial
        // decision per run is V030's partial index, a different rule from this one.
        jdbc.update("DELETE FROM optimization_decisions WHERE id = ?", kept);

        // An APPLY without a revert window is an APPLY nobody could take back.
        refusedByShape("APPLY", 7L, before, after, null, false);
        UUID applied = decision("APPLY", 7L, before, after, null, true);
        // With a real APPLY to point at, a target is the only thing wrong with these two.
        refusedByShape("KEEP", null, null, null, applied, false);
        refusedByShape("APPLY", 7L, before, after, applied, true);

        // A REVERT cannot itself be reverted, which the contract says by leaving revertUntil out of
        // RevertOptimizationDecision. The target is real, so the foreign key cannot be what refuses it.
        refusedByShape("REVERT", 8L, revision(), revision(), applied, true);
        assertThatCode(() -> decision("REVERT", 8L, revision(), revision(), applied, false))
                .doesNotThrowAnyException();
    }

    private void refusedByShape(String kind, Long resultingVersion, UUID before, UUID after,
            UUID reverted, boolean revertWindow) {
        assertThatThrownBy(() -> decision(kind, resultingVersion, before, after, reverted, revertWindow))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("optimization_decisions_shape_check");
    }

    @Test
    @DisplayName("BA-052-T10 a run takes one initial decision, and a REVERT is not one")
    void oneInitialDecisionPerRun() {
        seed();
        UUID applied = decision("APPLY", 7L, revision(), revision(), null, true);

        // The second line under a race: even with the application check removed, the database keeps
        // one winner rather than whichever transaction read first.
        assertThatThrownBy(() -> decision("APPLY", 7L, revision(), revision(), null, true))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> decision("KEEP", null, null, null, null, false))
                .isInstanceOf(DataAccessException.class);

        // And the guard must not over-reach: undoing that APPLY is a second decision on the same run
        // and has to be allowed, or the revert window could never be used.
        assertThatCode(() -> decision("REVERT", 8L, revision(), revision(), applied, false))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("BA-053-T8 the database refuses a second REVERT of one APPLY")
    void oneRevertPerApply() {
        seed();
        UUID applied = decision("APPLY", 7L, revision(), revision(), null, true);

        // The first REVERT of it is accepted - the control that the refusal below is about the second
        // one and not about REVERT rows in general.
        assertThatCode(() -> decision("REVERT", 8L, revision(), revision(), applied, false))
                .doesNotThrowAnyException();

        // The second line under a race. From HTTP a second undo meets the trip module's re-read of the
        // trip version first - even when both read the APPLY before either wrote, which BA-053-T7
        // races - so this is the layer where V033's unique index is the arbiter. V030's partial index
        // cannot be what refuses it - it leaves REVERTs out.
        assertThatThrownBy(() -> decision("REVERT", 9L, revision(), revision(), applied, false))
                .isInstanceOf(DataAccessException.class);
    }

    private UUID decision(String kind, Long resultingVersion, UUID before, UUID after, UUID reverted,
            boolean revertWindow) {
        UUID id = UUID.randomUUID();
        OffsetDateTime at = OffsetDateTime.now();
        jdbc.update("INSERT INTO optimization_decisions (id, run_id, proposal_id, owner_id, decision,"
                + " expected_trip_version, resulting_trip_version, before_revision_id,"
                + " after_revision_id, reverted_decision_id, revert_until, decided_at)"
                + " VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?)",
                id, runId, proposalId, ownerId, kind, resultingVersion, before, after, reverted,
                revertWindow ? at.plusHours(24) : null, at);
        return id;
    }

    /** A revision row the decision can point at; the FK is real, so the id has to be. */
    private UUID revision() {
        UUID id = UUID.randomUUID();
        // version is unique per trip and starts at 1; snapshot_hash is pinned to 64 lowercase hex.
        jdbc.update("INSERT INTO trip_revisions (id, trip_id, version, snapshot_schema_version,"
                + " snapshot_hash, aggregate_snapshot, created_at)"
                + " VALUES (?, ?, ?, 'v1', ?, '{}'::jsonb, ?)",
                id, tripId, ++revisions, "b".repeat(64), OffsetDateTime.now());
        return id;
    }

    private long revisions;

    private void seed() {
        ownerId = UUID.randomUUID();
        tripId = UUID.randomUUID();
        runId = UUID.randomUUID();
        proposalId = UUID.randomUUID();
        OffsetDateTime at = OffsetDateTime.now();
        jdbc.update("INSERT INTO owners (id, kind, account_id, locale, timezone, created_at)"
                + " VALUES (?, 'ANONYMOUS', NULL, 'ko-KR', 'Asia/Seoul', ?)", ownerId, at);
        jdbc.update("INSERT INTO trips (id, owner_id, title, start_date, end_date, timezone,"
                + " planning_level, status, version, created_at, updated_at)"
                + " VALUES (?, ?, 'decision schema fixture', ?::date, ?::date, 'Asia/Seoul',"
                + " 'NOTHING', 'DRAFT', 1, ?, ?)", tripId, ownerId, "2026-10-05", "2026-10-08", at, at);
        // A READY run, in full. V024 requires a terminal-or-ready run to carry startedAt, completedAt,
        // the evidence fingerprint and an expiry - a READY preview with no hash has nothing for APPLY
        // to revalidate against, and one with no expiry never stops being offerable. Seeding a
        // half-filled run here would be testing decisions against a run the system cannot produce.
        // V032: a row that carries a fingerprint carries the inputs it was computed from. This
        // fixture wrote the digest alone and the CHECK refused it - correctly, because a digest
        // nobody can recompute is the defect that migration exists to stop.
        jdbc.update("INSERT INTO optimization_runs (id, trip_id, requested_by_owner_id, scope,"
                + " include_candidates, status, input_trip_version, data_fingerprint,"
                + " algorithm_version, policy_version, policy_hash, catalog_version,"
                + " queued_at, started_at, completed_at, expires_at)"
                + " VALUES (?, ?, ?, 'TRIP', false, 'READY', 1, ?, 'pipeline-v1', 'policy-v1', ?,"
                + " 'KTO_KOR_SERVICE_2:7', ?, ?, ?, ?)",
                runId, tripId, ownerId, "a".repeat(64), "b".repeat(64), at, at, at, at.plusHours(1));
        jdbc.update("INSERT INTO optimization_proposals (id, run_id, rank, summary,"
                + " comparison_eligible, comparison_reason_code, crowd_delta, validation_summary,"
                + " created_at) VALUES (?, ?, 1, 'a proposal', true, NULL, NULL, '{}'::jsonb, ?)",
                proposalId, runId, at);
    }
}
