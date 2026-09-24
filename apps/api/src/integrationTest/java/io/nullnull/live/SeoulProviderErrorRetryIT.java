package io.nullnull.live;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.crowd.application.CollectorRunRecorder;
import io.nullnull.crowd.application.SourceRegistryStore;
import io.nullnull.operations.application.IngestAudit;
import io.nullnull.shared.provider.ProviderResponseValidator;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * What the Seoul path writes for a provider-declared error, read back through the same question every
 * gateway asks before its next call ({@link SourceRegistryStore#conditionAt}).
 *
 * <p>BA-090-T19 shows the gateway choosing FAILED; that is a recorded status, and a status is only a
 * retry if the next call is not stopped at it. That part lives in SQL - {@code conditionAt} reads the
 * latest run's status - so it is measured against the real database, not a fake that returns what the
 * test tells it to.
 *
 * <p>The rows are a source of this test's own, created and dropped here, for the reason
 * SourceQuarantineReleaseIT gives: {@code conditionAt} reads the latest run for a source code, and the
 * gate runs every suite in one database. The source is enabled and DEV_APPROVED because a reservation
 * refuses anything else (JdbcSourceQuotaStore), and the run goes through the real recorder, reservation
 * included, because the recorder only updates a call the reservation opened.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-090 Seoul provider error retry")
class SeoulProviderErrorRetryIT {

    private static final String SOURCE = "NULLNULL_PROVIDER_ERROR_IT";
    private static final Instant AT = Instant.parse("2026-09-24T05:00:00Z");

    @Autowired JdbcTemplate jdbc;
    @Autowired SourceRegistryStore registryStore;
    @Autowired CollectorRunRecorder collector;
    @Autowired PlatformTransactionManager txManager;

    @Test
    @DisplayName("BA-090-T20 서울 제공자 오류로 닫힌 run 은 다음 수집을 막지 않는다")
    void aProviderErrorRunLeavesTheNextCollectionOpen() {
        List<UUID> runs = new ArrayList<>();
        try {
            seedSource();
            runs.add(refusedOnTheSeoulPath(ProviderResponseValidator.Outcome.PROVIDER_ERROR, AT));

            assertThat(status(runs.get(0))).isEqualTo("FAILED");
            assertThat(registryStore.conditionAt(SOURCE, AT.plusSeconds(300)).latestRunQuarantined())
                    .as("the provider said it failed; the next tick asks again")
                    .isFalse();

            // THE CONTROL. The same path on drift must still shut the source, or a conditionAt that
            // never answered "quarantined" - or a path that closed everything FAILED - passes the above.
            runs.add(refusedOnTheSeoulPath(ProviderResponseValidator.Outcome.ENUM_DRIFT, AT.plusSeconds(300)));
            assertThat(status(runs.get(1))).isEqualTo("QUARANTINED");
            assertThat(registryStore.conditionAt(SOURCE, AT.plusSeconds(600)).latestRunQuarantined())
                    .as("drift is not the provider saying so; it stays shut until reviewed")
                    .isTrue();
        } finally {
            cleanUp(runs);
        }
    }

    private UUID refusedOnTheSeoulPath(ProviderResponseValidator.Outcome outcome, Instant at) {
        UUID run = collector.start(SOURCE, IngestAudit.TriggerType.SCHEDULED, "it", at);
        UUID log = collector.reserve(run, SOURCE, "CITYDATA", "seoul-it-" + UUID.randomUUID(), "it").ingestLogId();
        assertThat(collector.finalizeSingleCallRetryingProviderErrors(run, log, 200, 5, 1, null,
                new ProviderResponseValidator.Verdict(outcome, 1), at.plusSeconds(1))).isFalse();
        return run;
    }

    private String status(UUID run) {
        return jdbc.queryForObject("SELECT status FROM collector_runs WHERE id = ?", String.class, run);
    }

    private void seedSource() {
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO source_registry
                        (code, display_name, source_state, license_name, license_url, license_review_state,
                         official_url, terms_url, default_scope, metric_definition, approval_state,
                         quota_policy, attribution_template, retention_policy, refresh_expectation,
                         provider_schema_version, current_revision, stale_after_seconds, enabled,
                         contest_use, reviewed_at, updated_at)
                    VALUES (?, '제공자 오류 재시도 test', 'LIVE', NULL, NULL, 'NOT_APPLICABLE', NULL, NULL,
                            'LIVE_AREA', 'test fixture', 'DEV_APPROVED',
                            '{"perDay":1000,"thresholds":[60,80,90]}'::jsonb, NULL, 'test', 'test',
                            'it', 1, 300, true, '{"required":false}'::jsonb, ?, ?)
                    ON CONFLICT (code) DO NOTHING
                    """, SOURCE, Timestamp.from(AT), Timestamp.from(AT));
            jdbc.update("""
                    INSERT INTO source_registry_revisions
                        (source_code, version, canonical_contract, contract_hash, reviewed_at, created_at)
                    VALUES (?, 1, '{"fixture":true}'::jsonb, ?, ?, ?)
                    ON CONFLICT (source_code, version) DO NOTHING
                    """, SOURCE, "0".repeat(64), Timestamp.from(AT), Timestamp.from(AT));
        });
    }

    private void cleanUp(List<UUID> runs) {
        for (UUID run : runs) {
            jdbc.update("DELETE FROM api_ingest_logs WHERE collector_run_id = ?", run);
            jdbc.update("DELETE FROM collector_runs WHERE id = ?", run);
        }
        // Revisions first, in one transaction with the source, as SourceQuarantineReleaseIT does: the
        // revision FK to the source is plain, and the current_revision FK back is DEFERRABLE.
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            jdbc.update("DELETE FROM source_registry_revisions WHERE source_code = ?", SOURCE);
            jdbc.update("DELETE FROM source_registry WHERE code = ?", SOURCE);
        });
    }
}
