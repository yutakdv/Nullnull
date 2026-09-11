package io.nullnull.crowd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.nullnull.crowd.application.QuotaExhaustedException;
import io.nullnull.crowd.application.CollectorRunSourceMismatchException;
import io.nullnull.crowd.application.SourceQuotaGuard;
import io.nullnull.crowd.application.SourceRegistryQuery;
import io.nullnull.crowd.infrastructure.SourceHealth;
import io.nullnull.identity.application.SessionService;
import io.nullnull.operations.application.IngestAudit;
import io.nullnull.operations.application.ReadinessProbe;
import io.nullnull.shared.provider.ProviderException;
import io.nullnull.shared.provider.ProviderHttpClient;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.StubProviderServer;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "nullnull.sources.KTO_KOR_SERVICE_2.allowed-hosts[0]=127.0.0.1",
        "nullnull.provider.request-timeout=PT5S"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-020 source registry and quota")
class SourceRegistryIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired IngestAudit audit;
    @Autowired io.nullnull.crowd.application.SourceQuotaStore quotaStore;
    @Autowired SourceRegistryQuery registry;
    @Autowired io.nullnull.crowd.application.SourceRegistryStore registryStore;
    @Autowired ProviderHttpClient provider;
    @Autowired MockMvc mvc;
    @Autowired SessionService sessions;

    @Test
    @DisplayName("BA-020-T2 KST quota refuses call 100 percent plus one and warns 60 80 90 once")
    void quotaIsAtomicAndThresholdsAreOnce() {
        String source = "KTO_KOR_SERVICE_2";
        Instant now = Instant.parse("2026-09-10T15:30:00Z"); // 2026-09-11 00:30 KST
        UUID run = UUID.randomUUID();
        Logger logger = (Logger) LoggerFactory.getLogger(SourceQuotaGuard.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            jdbc.update("UPDATE source_registry SET quota_policy = '{\"perDay\":10,\"thresholds\":[60,80,90]}'::jsonb"
                    + " WHERE code = ?", source);
            audit.startRun(new IngestAudit.StartRun(run, source, IngestAudit.TriggerType.MANUAL,
                    "test-v1", now));
            SourceQuotaGuard guard = new SourceQuotaGuard(quotaStore, Clock.fixed(now, ZoneOffset.UTC));
            for (int call = 1; call <= 10; call++) {
                assertThat(guard.acquire(run, source, "TEST_ENDPOINT", "quota-request-" + call,
                        "test-release").used()).isEqualTo(call);
            }
            assertThatThrownBy(() -> guard.acquire(run, source, "TEST_ENDPOINT", "quota-request-11",
                    "test-release")).isInstanceOf(QuotaExhaustedException.class)
                    .hasMessage("QUOTA_EXHAUSTED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM api_ingest_logs WHERE collector_run_id = ?",
                    Integer.class, run)).isEqualTo(10);
            List<String> warnings = events.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.startsWith("source.quota.threshold")).toList();
            assertThat(warnings).hasSize(3);
            assertThat(warnings).anySatisfy(message -> assertThat(message).contains("threshold=60", "used=6"));
            assertThat(warnings).anySatisfy(message -> assertThat(message).contains("threshold=80", "used=8"));
            assertThat(warnings).anySatisfy(message -> assertThat(message).contains("threshold=90", "used=9"));
        } finally {
            logger.detachAppender(events);
            jdbc.update("DELETE FROM api_ingest_logs WHERE collector_run_id = ?", run);
            jdbc.update("DELETE FROM collector_runs WHERE id = ?", run);
            jdbc.update("UPDATE source_registry SET quota_policy = '{\"perDay\":1000,\"thresholds\":[60,80,90]}'::jsonb"
                    + " WHERE code = ?", source);
        }
    }

    @Test
    @DisplayName("BA-020-T1 approval stale policy and incidents determine source health without provider IO")
    void registryAndIncidentHealthFailClosed() {
        assertThat(registry.enabledFor("production")).extracting(source -> source.code())
                .contains("KTO_KOR_SERVICE_2", "KTO_CONCENTRATION_FORECAST", "NULLNULL_CATALOG_RULE")
                .doesNotContain("KTO_RELATED_PLACES", "SEOUL_CITYDATA", "DEMO_REPLAY");
        assertThatThrownBy(() -> registry.enabledFor("prod"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("environment must be one of");
        SourceHealth health = new SourceHealth("KTO_KOR_SERVICE_2", registryStore);
        Instant at = Instant.parse("2026-09-10T00:00:00Z");
        UUID incident = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO source_quality_incidents
                        (id, source_code, incident_code, affected_from, affected_to, scope,
                         disposition, reviewed_at)
                    VALUES (?, 'KTO_KOR_SERVICE_2', ?, ?, ?, 'PLACE', 'QUARANTINE', ?)
                    """, incident, "test-" + incident, java.sql.Timestamp.from(at.minusSeconds(1)),
                    java.sql.Timestamp.from(at.plusSeconds(1)), java.sql.Timestamp.from(at));
            assertThat(health.probe(at).status()).isEqualTo(ReadinessProbe.ProbeStatus.DEGRADED);
            assertThat(health.probe(at).detail()).isEqualTo("provider incident active");
        } finally {
            jdbc.update("DELETE FROM source_quality_incidents WHERE id = ?", incident);
        }
        assertThat(new SourceHealth("KTO_RELATED_PLACES", registryStore).probe(at).detail())
                .isEqualTo("disabled");
    }

    @Test
    @DisplayName("BA-020-T1 immutable registry revision hashes every collection control")
    void registryRevisionIsCompleteAndVerifiable() {
        String contract = jdbc.queryForObject("""
                SELECT canonical_contract::text
                  FROM source_registry_revisions
                 WHERE source_code = 'KTO_KOR_SERVICE_2' AND version = 1
                """, String.class);
        String storedHash = jdbc.queryForObject("""
                SELECT contract_hash
                  FROM source_registry_revisions
                 WHERE source_code = 'KTO_KOR_SERVICE_2' AND version = 1
                """, String.class);
        String calculatedHash = jdbc.queryForObject(
                "SELECT encode(sha256(convert_to(?::text, 'UTF8')), 'hex')", String.class, contract);

        assertThat(storedHash).isEqualTo(calculatedHash);
        assertThat(contract).contains("\"approvalState\"", "\"quotaPolicy\"", "\"license\"",
                "\"contestUse\"", "\"refreshExpectation\"", "\"retentionPolicy\"",
                "\"providerSchemaVersion\"", "\"staleAfterSeconds\"");
    }

    @Test
    @DisplayName("BA-020-T2 quota reservation refuses a collector run from another source")
    void quotaReservationMustUseItsOwnCollectorRun() {
        Instant now = Instant.parse("2026-09-10T00:00:00Z");
        UUID run = UUID.randomUUID();
        audit.startRun(new IngestAudit.StartRun(run, "KTO_KOR_SERVICE_2", IngestAudit.TriggerType.MANUAL,
                "test-v1", now));
        try {
            SourceQuotaGuard guard = new SourceQuotaGuard(quotaStore, Clock.fixed(now, ZoneOffset.UTC));
            assertThatThrownBy(() -> guard.acquire(run, "NULLNULL_CATALOG_RULE", "TEST_ENDPOINT",
                    "mismatched-run", "test-release"))
                    .isInstanceOf(CollectorRunSourceMismatchException.class)
                    .hasMessage("COLLECTOR_RUN_SOURCE_MISMATCH");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM api_ingest_logs WHERE collector_run_id = ?",
                    Integer.class, run)).isZero();
        } finally {
            jdbc.update("DELETE FROM api_ingest_logs WHERE collector_run_id = ?", run);
            jdbc.update("DELETE FROM collector_runs WHERE id = ?", run);
        }
    }

    @Test
    @DisplayName("REC-DATA-05 provider canary remains out of the sanitized error and audit database")
    void providerCanaryCannotReachTheAuditLedger() throws Exception {
        String canary = "fake-secret-key-never-retain";
        Instant now = Instant.parse("2026-09-10T00:00:00Z");
        UUID run = UUID.randomUUID();
        audit.startRun(new IngestAudit.StartRun(run, "KTO_KOR_SERVICE_2", IngestAudit.TriggerType.MANUAL,
                "test-v1", now));
        try (StubProviderServer stub = new StubProviderServer()
                .enqueue(new StubProviderServer.Response(500, "provider says " + canary))) {
            SourceQuotaGuard guard = new SourceQuotaGuard(quotaStore, Clock.fixed(now, ZoneOffset.UTC));
            var reservation = guard.acquire(run, "KTO_KOR_SERVICE_2", "TEST_ENDPOINT", "canary-request",
                    "test-release");
            assertThatThrownBy(() -> provider.get("KTO_KOR_SERVICE_2", stub.uri("serviceKey=" + canary)).join())
                    .hasRootCauseInstanceOf(ProviderException.class)
                    .rootCause().hasMessage("HTTP_STATUS");
            audit.record(new IngestAudit.CallRecord(reservation.ingestLogId(),
                    IngestAudit.CallOutcome.HTTP_ERROR, 500, 1, 0, null,
                    IngestAudit.ValidationResult.PROVIDER_ERROR));
            String auditRow = jdbc.queryForObject("""
                    SELECT row_to_json(l)::text FROM api_ingest_logs l WHERE l.id = ?
                    """, String.class, reservation.ingestLogId());
            assertThat(auditRow).doesNotContain(canary, "serviceKey", "provider says");
        } finally {
            jdbc.update("DELETE FROM api_ingest_logs WHERE collector_run_id = ?", run);
            jdbc.update("DELETE FROM collector_runs WHERE id = ?", run);
        }
    }

    @Test
    @DisplayName("BA-020-T3 slow provider concurrency does not occupy readiness or owner request threads")
    void slowProviderDoesNotBlockApiRequests() throws Exception {
        try (StubProviderServer stub = new StubProviderServer()) {
            for (int index = 0; index < 4; index++) {
                stub.enqueue(new StubProviderServer.Response(200, "{}", Duration.ofSeconds(2), java.util.Map.of()));
            }
            List<java.util.concurrent.CompletableFuture<ProviderHttpClient.ProviderResponse>> calls = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                calls.add(provider.get("KTO_KOR_SERVICE_2", stub.uri(null)));
            }
            Instant waitUntil = Instant.now().plusSeconds(1);
            while (stub.calls() < 4 && Instant.now().isBefore(waitUntil)) {
                Thread.sleep(10);
            }
            assertThat(stub.calls()).isEqualTo(4);

            mvc.perform(get("/api/v1/health/ready")).andExpect(status().isOk());
            SessionService.Bootstrap owner = sessions.bootstrap(null, null, null);
            mvc.perform(get("/api/v1/me").cookie(new Cookie("__Host-nullnull_session", owner.cookie)))
                    .andExpect(status().isOk());
            assertThat(calls).allSatisfy(call -> assertThat(call).isNotDone());
            calls.forEach(java.util.concurrent.CompletableFuture::join);
        }
    }
}
