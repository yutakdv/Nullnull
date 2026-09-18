package io.nullnull.crowd;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.testsupport.TestcontainersConfiguration;
import io.nullnull.crowd.application.KtoCallInventory;
import io.nullnull.crowd.application.KtoCallInventoryQuery;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
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
 * CMP-KTO-006: the inventory reads the call-audit as {@code check_actual_call_evidence.py} judges a call.
 *
 * <p>Every row here carries a release made up for this class, and the query is scoped by release, so the
 * shared gate database cannot change these counts (AGENTS.md rule 6).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("CMP-KTO-006 the KTO call inventory")
class KtoCallInventoryIT {

    private static final Instant AT = Instant.parse("2026-09-20T01:00:00Z");

    @Autowired KtoCallInventoryQuery inventory;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> runs = new ArrayList<>();
    private final List<UUID> logs = new ArrayList<>();

    @AfterEach
    void removeOnlyOwnRows() {
        for (UUID log : logs) {
            jdbc.update("DELETE FROM api_ingest_logs WHERE id = ?", log);
        }
        for (UUID run : runs) {
            jdbc.update("DELETE FROM collector_runs WHERE id = ?", run);
        }
    }

    @Test
    @DisplayName("a release's list is its usable KTO calls; rejected calls and replays are counted apart, other releases and other sources not at all")
    void theListIsWhatTheReleaseActuallyUsed() {
        String release = "inventory-" + UUID.randomUUID();
        String other = "inventory-" + UUID.randomUUID();

        UUID detail = run("KTO_KOR_SERVICE_2", "READ_THROUGH");
        call(detail, "KOR_SERVICE_2_DETAIL_COMMON", "OK", "OK", release, AT);
        call(detail, "KOR_SERVICE_2_DETAIL_COMMON", "OK", "OK", release, AT.plusSeconds(60));
        call(detail, "KOR_SERVICE_2_DETAIL_COMMON", "HTTP_ERROR", "PROVIDER_ERROR", release, AT);
        call(detail, "KOR_SERVICE_2_DETAIL_COMMON", "OK", "SCHEMA_DRIFT", release, AT);
        call(detail, "KOR_SERVICE_2_DETAIL_COMMON", "OK", "OK", other, AT);

        UUID forecast = run("KTO_CONCENTRATION_FORECAST", "READ_THROUGH");
        call(forecast, "TATS_CNCTR_RATE_LIST", "OK", "OK", release, AT.plusSeconds(120));

        UUID replay = run("KTO_CONCENTRATION_FORECAST", "REPLAY");
        call(replay, "TATS_CNCTR_RATE_LIST", "OK", "OK", release, AT);

        UUID ours = run("NULLNULL_CATALOG_RULE", "MANUAL");
        call(ours, "CATALOG_RULE_DERIVE", "OK", "OK", release, AT);

        KtoCallInventory found = inventory.forRelease(release);

        assertThat(found.release()).isEqualTo(release);
        assertThat(found.operations()).containsExactly(
                new KtoCallInventory.Operation("KTO_CONCENTRATION_FORECAST", "TATS_CNCTR_RATE_LIST", 1,
                        AT.plusSeconds(120), AT.plusSeconds(120)),
                new KtoCallInventory.Operation("KTO_KOR_SERVICE_2", "KOR_SERVICE_2_DETAIL_COMMON", 2,
                        AT, AT.plusSeconds(60)));
        assertThat(found.rejectedCalls()).as("an error and a drifted answer, not dropped silently").isEqualTo(2);
        assertThat(found.replayCalls()).as("a replay proves nothing about the provider").isEqualTo(1);
    }

    @Test
    @DisplayName("a release that called nothing usable reads as empty, with its rejections still counted")
    void aReleaseOfOnlyRejectionsIsEmptyNotMissing() {
        String release = "inventory-" + UUID.randomUUID();
        UUID detail = run("KTO_KOR_SERVICE_2", "READ_THROUGH");
        call(detail, "KOR_SERVICE_2_DETAIL_COMMON", "TIMEOUT", "PENDING", release, AT);

        KtoCallInventory found = inventory.forRelease(release);

        assertThat(found.operations()).isEmpty();
        assertThat(found.rejectedCalls()).isOne();
        assertThat(inventory.forRelease("inventory-" + UUID.randomUUID()).operations()).isEmpty();
    }

    private UUID run(String source, String trigger) {
        UUID id = UUID.randomUUID();
        runs.add(id);
        jdbc.update("""
                INSERT INTO collector_runs
                    (id, source_code, status, trigger_type, records_received, records_accepted, records_rejected,
                     schema_version, started_at, finished_at)
                VALUES (?, ?, 'COMPLETED', ?, 0, 0, 0, 'inventory-test', ?, ?)
                """, id, source, trigger, Timestamp.from(AT), Timestamp.from(AT));
        return id;
    }

    private void call(UUID run, String endpoint, String outcome, String validation, String release, Instant at) {
        UUID id = UUID.randomUUID();
        logs.add(id);
        jdbc.update("""
                INSERT INTO api_ingest_logs
                    (id, collector_run_id, endpoint_key, outcome, http_status, duration_ms, response_count,
                     release_version, request_id, payload_hash, validation_result, created_at)
                VALUES (?, ?, ?, ?, NULL, 10, NULL, ?, ?, NULL, ?, ?)
                """, id, run, endpoint, outcome, release, "req-" + id, validation, Timestamp.from(at));
    }
}
