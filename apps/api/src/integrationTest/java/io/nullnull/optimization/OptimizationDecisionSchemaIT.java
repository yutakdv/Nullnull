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

        // A KEEP changes nothing, so a trip version recorded on one would claim it did.
        assertThatThrownBy(() -> decision("KEEP", 7L, revision(), revision(), null, true))
                .isInstanceOf(DataAccessException.class);
        // An APPLY without a revert window is an APPLY nobody could take back.
        assertThatThrownBy(() -> decision("APPLY", 7L, revision(), revision(), null, false))
                .isInstanceOf(DataAccessException.class);
        // A REVERT cannot itself be reverted, which the contract says by leaving revertUntil out of
        // RevertOptimizationDecision.
        assertThatThrownBy(() -> decision("REVERT", 7L, revision(), revision(), UUID.randomUUID(), true))
                .isInstanceOf(DataAccessException.class);

        assertThatCode(() -> decision("KEEP", null, null, null, null, false)).doesNotThrowAnyException();
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
