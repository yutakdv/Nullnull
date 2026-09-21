package io.nullnull.crowd;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.crowd.application.SourceRegistryStore;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Releasing a quarantined source, and the two ways it must NOT happen.
 *
 * <p>A quarantined latest run shuts a source for every gateway that calls
 * {@link SourceRegistryStore#conditionAt}, and the guard runs before {@code collector.start}. Without
 * a release that is a deadlock, not a guard: no newer run can be recorded to displace the quarantined
 * one, so the source stays shut forever. On 2026-09-21 {@code SEOUL_CITYDATA} locked itself this way
 * on its own five-minute schedule, and the staging database is not reachable from outside the VPC.
 *
 * <p>The release is a reviewed {@code RESOLVED} incident rather than a rewritten run: the refusal
 * happened and its row stays. That vocabulary already existed - {@code RESOLVED} is in
 * {@code source_incident_disposition_check} and {@code reviewed_at} is NOT NULL - so this wires a
 * record that was designed for human review into the guard that needed it.
 *
 * <p><strong>Three clauses, because a test that only proves the release cannot tell a working guard
 * from a deleted one.</strong> T4 is the guard still guarding, T5 is the release working, and T6 is
 * the boundary that stops an older review from being resurrected as a release for a refusal it
 * predates.
 *
 * <p>The rows are a source of this test's own, created and dropped here. {@code conditionAt} reads
 * the latest run <em>for a source code</em>, so borrowing a shared source would make the answer
 * depend on whatever else ran first in the gate's single database.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-020 source quarantine release")
class SourceQuarantineReleaseIT {

    private static final String SOURCE = "NULLNULL_QUARANTINE_IT";
    private static final Instant RUN_AT = Instant.parse("2026-09-21T04:00:00Z");

    @Autowired JdbcTemplate jdbc;
    @Autowired SourceRegistryStore registryStore;
    @Autowired PlatformTransactionManager txManager;

    @Test
    @DisplayName("BA-020-T4 a quarantined latest run keeps the source shut while no review releases it")
    void quarantineWithoutAReviewKeepsBlocking() {
        UUID run = UUID.randomUUID();
        try {
            seedSource();
            seedQuarantinedRun(run);

            assertThat(registryStore.conditionAt(SOURCE, RUN_AT.plus(1, ChronoUnit.HOURS))
                    .latestRunQuarantined())
                    .as("no reviewed release exists, so the quarantine still holds")
                    .isTrue();
        } finally {
            cleanUp(run);
        }
    }

    @Test
    @DisplayName("BA-020-T5 a RESOLVED incident reviewed after the run releases the source")
    void aReviewAfterTheRunReleasesIt() {
        UUID run = UUID.randomUUID();
        UUID incident = UUID.randomUUID();
        try {
            seedSource();
            seedQuarantinedRun(run);
            seedResolvedIncident(incident, RUN_AT.plusSeconds(60));

            assertThat(registryStore.conditionAt(SOURCE, RUN_AT.plus(1, ChronoUnit.HOURS))
                    .latestRunQuarantined())
                    .as("a human reviewed the refusal and closed it, so the source may collect again")
                    .isFalse();
            assertThat(jdbc.queryForObject("SELECT status FROM collector_runs WHERE id = ?",
                    String.class, run))
                    .as("the release does not rewrite what happened: the run stays QUARANTINED")
                    .isEqualTo("QUARANTINED");
        } finally {
            cleanUp(run, incident);
        }
    }

    @Test
    @DisplayName("BA-020-T6 a RESOLVED incident reviewed before the run does not release it")
    void aReviewOlderThanTheRunDoesNotRelease() {
        UUID run = UUID.randomUUID();
        UUID incident = UUID.randomUUID();
        try {
            seedSource();
            seedQuarantinedRun(run);
            // One second earlier is enough: the question is not how old the review is but whether it
            // could have been about this refusal at all, and a review filed first could not have been.
            seedResolvedIncident(incident, RUN_AT.minusSeconds(1));

            assertThat(registryStore.conditionAt(SOURCE, RUN_AT.plus(1, ChronoUnit.HOURS))
                    .latestRunQuarantined())
                    .as("a review that predates the refusal says nothing about it")
                    .isTrue();
        } finally {
            cleanUp(run, incident);
        }
    }

    @Test
    @DisplayName("BA-020-T7 the operator release writes a review that conditionAt then honours")
    void theOperatorReleaseReopensTheSource() {
        UUID run = UUID.randomUUID();
        try {
            seedSource();
            seedQuarantinedRun(run);
            Instant reviewedAt = RUN_AT.plus(1, ChronoUnit.HOURS);

            // T5 proves a hand-seeded review releases; this proves the TOOL writes one that does. Without
            // it the two could disagree on scope or disposition and the release would be written and ignored.
            assertThat(registryStore.releaseLatestQuarantine(SOURCE, "release-" + run, reviewedAt))
                    .as("the latest run is quarantined, so there is something to release")
                    .hasValueSatisfying(released -> assertThat(released.runId()).isEqualTo(run));
            assertThat(registryStore.conditionAt(SOURCE, reviewedAt.plusSeconds(1)).latestRunQuarantined())
                    .as("the review the tool wrote is one the guard accepts")
                    .isFalse();
        } finally {
            jdbc.update("DELETE FROM source_quality_incidents WHERE source_code = ?", SOURCE);
            cleanUp(run);
        }
    }

    @Test
    @DisplayName("BA-020-T8 the operator release refuses a source that is not quarantined and writes nothing")
    void theReleaseRefusesWhenNothingIsShut() {
        UUID run = UUID.randomUUID();
        try {
            seedSource();
            jdbc.update("""
                    INSERT INTO collector_runs
                        (id, source_code, status, trigger_type, records_received, records_accepted,
                         records_rejected, schema_version, started_at, finished_at)
                    VALUES (?, ?, 'COMPLETED', 'SCHEDULED', 1, 1, 0, 'disabled', ?, ?)
                    """, run, SOURCE, Timestamp.from(RUN_AT), Timestamp.from(RUN_AT.plusSeconds(1)));

            assertThat(registryStore.releaseLatestQuarantine(SOURCE, "release-" + run, RUN_AT.plusSeconds(60)))
                    .as("a completed run is not shut, so there is nothing to release")
                    .isEmpty();
            // Empty is only half of it: a tool that returned empty AND wrote an incident would leave a stray
            // RESOLVED row able to release a quarantine that happens later.
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM source_quality_incidents WHERE source_code = ?", Integer.class, SOURCE))
                    .as("a refused release writes no review")
                    .isZero();
        } finally {
            jdbc.update("DELETE FROM source_quality_incidents WHERE source_code = ?", SOURCE);
            cleanUp(run);
        }
    }

    /**
     * The source and its first revision go in together because they reference each other:
     * {@code source_registry_current_revision_fk} is DEFERRABLE INITIALLY DEFERRED and
     * {@code current_revision} is NOT NULL, so neither row can be written alone.
     */
    private void seedSource() {
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            seedRegistryRow();
            jdbc.update("""
                    INSERT INTO source_registry_revisions
                        (source_code, version, canonical_contract, contract_hash, reviewed_at, created_at)
                    VALUES (?, 1, '{"fixture":true}'::jsonb, ?, ?, ?)
                    ON CONFLICT (source_code, version) DO NOTHING
                    """, SOURCE, "0".repeat(64), Timestamp.from(RUN_AT), Timestamp.from(RUN_AT));
        });
    }

    private void seedRegistryRow() {
        jdbc.update("""
                INSERT INTO source_registry
                    (code, display_name, source_state, license_name, license_url, license_review_state,
                     official_url, terms_url, default_scope, metric_definition, approval_state,
                     quota_policy, attribution_template, retention_policy, refresh_expectation,
                     provider_schema_version, current_revision, stale_after_seconds, enabled,
                     contest_use, reviewed_at, updated_at)
                VALUES (?, '격리 해제 test', 'QUALITATIVE', NULL, NULL, 'NOT_APPLICABLE', NULL, NULL,
                        'PLACE', 'test fixture', 'DISABLED',
                        '{"perDay":1000,"thresholds":[60,80,90]}'::jsonb, NULL, 'test', 'test',
                        'disabled', 1, NULL, false, '{"required":false}'::jsonb, ?, ?)
                ON CONFLICT (code) DO NOTHING
                """, SOURCE, Timestamp.from(RUN_AT), Timestamp.from(RUN_AT));
    }

    private void seedQuarantinedRun(UUID run) {
        jdbc.update("""
                INSERT INTO collector_runs
                    (id, source_code, status, trigger_type, records_received, records_accepted,
                     records_rejected, schema_version, started_at, finished_at)
                VALUES (?, ?, 'QUARANTINED', 'SCHEDULED', 1, 0, 1, 'disabled', ?, ?)
                """, run, SOURCE, Timestamp.from(RUN_AT), Timestamp.from(RUN_AT.plusSeconds(1)));
    }

    private void seedResolvedIncident(UUID incident, Instant reviewedAt) {
        jdbc.update("""
                INSERT INTO source_quality_incidents
                    (id, source_code, incident_code, affected_from, affected_to, scope,
                     official_notice_url, disposition, reviewed_at)
                VALUES (?, ?, ?, ?, ?, 'LIVE_AREA', NULL, 'RESOLVED', ?)
                """, incident, SOURCE, "release-" + incident, Timestamp.from(RUN_AT.minusSeconds(30)),
                Timestamp.from(RUN_AT.plusSeconds(30)), Timestamp.from(reviewedAt));
    }

    private void cleanUp(UUID run, UUID... incidents) {
        for (UUID incident : incidents) {
            jdbc.update("DELETE FROM source_quality_incidents WHERE id = ?", incident);
        }
        jdbc.update("DELETE FROM collector_runs WHERE id = ?", run);
        // Revisions first, both in one transaction: revisions -> source is a plain FK so it must go
        // first, and the current_revision FK pointing back at them is DEFERRABLE, so by commit the
        // source row it would have checked is gone too. current_revision is NOT NULL, so there is no
        // nulling it out of the way.
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            jdbc.update("DELETE FROM source_registry_revisions WHERE source_code = ?", SOURCE);
            jdbc.update("DELETE FROM source_registry WHERE code = ?", SOURCE);
        });
    }
}
