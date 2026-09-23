package io.nullnull.optimization;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.optimization.application.OptimizationRunStore;
import io.nullnull.optimization.domain.OptimizationFailureCode;
import io.nullnull.optimization.domain.OptimizationStatus;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Publishing a preview, and the two ways it must not happen.
 *
 * <p>READY is the one status a run cannot enter on its own: V024 refuses it without an evidence hash
 * and an expiry, because a preview with no hash has nothing for APPLY to revalidate against and one
 * with no expiry never stops being offerable. So the store writes the status and the evidence
 * together, and refuses rather than throws when the run is not in a state to be published.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("optimization run readiness")
class OptimizationRunReadinessIT {

    private static final String FINGERPRINT = "b".repeat(64);
    // V032: the inputs the fingerprint was computed from, frozen beside it. The hash has to be a
    // SHA-256 digest or the CHECK refuses the row - which is the point of asserting it here too.
    private static final String POLICY_HASH = "a".repeat(64);
    private static final String CATALOG_VERSION = "KTO_KOR_SERVICE_2:7";

    @Autowired
    OptimizationRunStore runs;

    @Autowired
    JdbcTemplate jdbc;

    private UUID ownerId;
    private UUID tripId;
    private UUID runId;

    @AfterEach
    void removeOnlyOwnFixtures() {
        jdbc.update("DELETE FROM optimization_run_snapshot_sets WHERE run_id = ?", runId);
        jdbc.update("DELETE FROM optimization_runs WHERE id = ?", runId);
        jdbc.update("DELETE FROM trips WHERE id = ?", tripId);
        jdbc.update("DELETE FROM owners WHERE id = ?", ownerId);
    }

    @Test
    @DisplayName("a running run with frozen evidence becomes READY, carrying the hash APPLY will check")
    void aFrozenRunPublishes() {
        seedRunning();
        assertThat(runs.recordFrozenEvidence(runId, Instant.now().plusSeconds(600), List.of())).isTrue();

        assertThat(runs.markReady(runId, FINGERPRINT, "pipeline-v1", "policy-v1", POLICY_HASH,
                CATALOG_VERSION, Instant.now())).isTrue();

        assertThat(status()).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT data_fingerprint FROM optimization_runs WHERE id = ?",
                String.class, runId)).isEqualTo(FINGERPRINT);
        // The digest without its inputs is a value nobody can recompute. V032's CHECK refuses a row
        // that has one and not the others, but a CHECK only judges rows it is shown - that markReady
        // actually writes all three is this statement's to prove, and nothing else's.
        assertThat(jdbc.queryForMap("SELECT policy_version, policy_hash, catalog_version"
                + " FROM optimization_runs WHERE id = ?", runId))
                .containsEntry("policy_version", "policy-v1")
                .containsEntry("policy_hash", POLICY_HASH)
                .containsEntry("catalog_version", CATALOG_VERSION);
        assertThat(jdbc.queryForObject("SELECT completed_at IS NOT NULL FROM optimization_runs"
                + " WHERE id = ?", Boolean.class, runId)).isTrue();
    }

    @Test
    @DisplayName("a run whose evidence was never frozen is refused, not thrown at")
    void anUnfrozenRunCannotPublish() {
        seedRunning();

        // The CHECK would refuse this too, but as an exception in the middle of a job. Asking in the
        // WHERE turns it into an answer the handler can act on - and the run stays RUNNING, so the
        // next attempt can still freeze and publish.
        assertThat(runs.markReady(runId, FINGERPRINT, "pipeline-v1", "policy-v1", POLICY_HASH,
                CATALOG_VERSION, Instant.now())).isFalse();
        assertThat(status()).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("only a RUNNING run publishes, so a re-taken job cannot republish a finished one")
    void aFinishedRunIsNotRepublished() {
        seedRunning();
        runs.recordFrozenEvidence(runId, Instant.now().plusSeconds(600), List.of());
        assertThat(runs.markReady(runId, FINGERPRINT, "pipeline-v1", "policy-v1", POLICY_HASH,
                CATALOG_VERSION, Instant.now())).isTrue();

        // At-least-once delivery makes the second call normal rather than exceptional. It must change
        // nothing: the preview was already published, with its own hash and its own deadline.
        //
        // Every value differs from the first call's, including the three V032 added. Repeating them
        // would let a statement that overwrites them pass - "unchanged" is only observable against
        // something that would otherwise have changed.
        assertThat(runs.markReady(runId, "c".repeat(64), "pipeline-v2", "policy-v2", "d".repeat(64),
                "KTO_KOR_SERVICE_2:8", Instant.now())).isFalse();
        assertThat(jdbc.queryForMap("SELECT data_fingerprint, policy_version, policy_hash,"
                + " catalog_version FROM optimization_runs WHERE id = ?", runId))
                .containsEntry("data_fingerprint", FINGERPRINT)
                .containsEntry("policy_version", "policy-v1")
                .containsEntry("policy_hash", POLICY_HASH)
                .containsEntry("catalog_version", CATALOG_VERSION);
    }

    @Test
    @DisplayName("BA-051-T32 the store refuses to publish a run at its deadline and publishes it a second before")
    void aRunPastItsDeadlineCannotPublish() {
        seedRunning();
        // Truncated to microseconds, the precision timestamptz keeps, so the instant the run stores and
        // the instant it is compared with are the same value - the boundary itself, not a neighbour of it.
        Instant deadline = Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.MICROS);
        runs.recordFrozenEvidence(runId, deadline, List.of());

        // At the deadline the preview is already gone (previewExpired is "not before"), so publishing it
        // would store a READY that reads 410 the moment it exists.
        assertThat(runs.markReady(runId, FINGERPRINT, "pipeline-v1", "policy-v1", POLICY_HASH,
                CATALOG_VERSION, deadline)).isFalse();
        assertThat(status()).isEqualTo("RUNNING");

        // The negative control: the same call a second earlier publishes, so the refusal above is the
        // deadline's and not something else about this run.
        assertThat(runs.markReady(runId, FINGERPRINT, "pipeline-v1", "policy-v1", POLICY_HASH,
                CATALOG_VERSION, deadline.minusSeconds(1))).isTrue();
        assertThat(status()).isEqualTo("READY");
    }

    @Test
    @DisplayName("BA-051-T30 re-freezing a run keeps the deadline it first recorded")
    void reFreezingKeepsTheFirstDeadline() {
        seedRunning();
        Instant first = Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.MICROS);
        assertThat(runs.recordFrozenEvidence(runId, first, List.of())).isTrue();

        // A retry freezes again, later. It still answers true - the sets are replaced - but the deadline
        // is the one the run already had.
        assertThat(runs.recordFrozenEvidence(runId, first.plusSeconds(60), List.of())).isTrue();
        assertThat(jdbc.queryForObject("SELECT expires_at FROM optimization_runs WHERE id = ?",
                java.sql.Timestamp.class, runId).toInstant()).isEqualTo(first);
    }

    @Test
    @DisplayName("BA-051-T31 a failure the store is asked to record at or after the run's deadline is stored as EXPIRED without a code")
    void aFailureAfterTheDeadlineIsStoredAsExpiry() {
        seedRunning();
        Instant deadline = Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.MICROS);
        runs.recordFrozenEvidence(runId, deadline, List.of());

        assertThat(runs.fail(runId, OptimizationStatus.RUNNING, OptimizationFailureCode.NO_IMPROVEMENT,
                "Nothing offered was better.", deadline)).isTrue();
        assertThat(jdbc.queryForMap("SELECT status, failure_code, failure_message, completed_at"
                + " FROM optimization_runs WHERE id = ?", runId))
                .containsEntry("status", "EXPIRED")
                .containsEntry("failure_code", null)
                .containsEntry("failure_message", null)
                .containsEntry("completed_at", java.sql.Timestamp.from(deadline));
    }

    private String status() {
        return jdbc.queryForObject("SELECT status FROM optimization_runs WHERE id = ?", String.class, runId);
    }

    private void seedRunning() {
        ownerId = UUID.randomUUID();
        tripId = UUID.randomUUID();
        runId = UUID.randomUUID();
        OffsetDateTime at = OffsetDateTime.now();
        jdbc.update("INSERT INTO owners (id, kind, account_id, locale, timezone, created_at)"
                + " VALUES (?, 'ANONYMOUS', NULL, 'ko-KR', 'Asia/Seoul', ?)", ownerId, at);
        jdbc.update("INSERT INTO trips (id, owner_id, title, start_date, end_date, timezone,"
                + " planning_level, status, version, created_at, updated_at)"
                + " VALUES (?, ?, 'readiness fixture', ?::date, ?::date, 'Asia/Seoul',"
                + " 'NOTHING', 'DRAFT', 1, ?, ?)", tripId, ownerId, "2026-10-05", "2026-10-08", at, at);
        jdbc.update("INSERT INTO optimization_runs (id, trip_id, requested_by_owner_id, scope,"
                + " include_candidates, status, input_trip_version, queued_at, started_at)"
                + " VALUES (?, ?, ?, 'TRIP', false, 'RUNNING', 1, ?, ?)", runId, tripId, ownerId, at, at);
    }
}
