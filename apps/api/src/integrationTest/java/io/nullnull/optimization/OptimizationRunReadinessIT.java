package io.nullnull.optimization;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.optimization.application.OptimizationRunStore;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.time.Instant;
import java.time.OffsetDateTime;
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

        assertThat(runs.markReady(runId, FINGERPRINT, "policy-v1", Instant.now())).isTrue();

        assertThat(status()).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT data_fingerprint FROM optimization_runs WHERE id = ?",
                String.class, runId)).isEqualTo(FINGERPRINT);
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
        assertThat(runs.markReady(runId, FINGERPRINT, "policy-v1", Instant.now())).isFalse();
        assertThat(status()).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("only a RUNNING run publishes, so a re-taken job cannot republish a finished one")
    void aFinishedRunIsNotRepublished() {
        seedRunning();
        runs.recordFrozenEvidence(runId, Instant.now().plusSeconds(600), List.of());
        assertThat(runs.markReady(runId, FINGERPRINT, "policy-v1", Instant.now())).isTrue();

        // At-least-once delivery makes the second call normal rather than exceptional. It must change
        // nothing: the preview was already published, with its own hash and its own deadline.
        assertThat(runs.markReady(runId, "c".repeat(64), "policy-v2", Instant.now())).isFalse();
        assertThat(jdbc.queryForObject("SELECT data_fingerprint FROM optimization_runs WHERE id = ?",
                String.class, runId)).isEqualTo(FINGERPRINT);
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
