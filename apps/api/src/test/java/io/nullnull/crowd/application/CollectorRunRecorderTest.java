package io.nullnull.crowd.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.operations.application.IngestAudit;
import io.nullnull.shared.provider.ProviderResponseValidator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CollectorRunRecorderTest {

    @Test
    @DisplayName("BA-020-T1 schema drift closes the run as quarantined and refuses canonical writes")
    void driftQuarantinesRun() {
        CapturingAudit audit = new CapturingAudit();
        SourceQuotaStore quotaStore = (request, start, end) ->
                new SourceQuotaStore.Reservation(UUID.randomUUID(), 1, 1000, java.util.List.of());
        CollectorRunRecorder recorder = new CollectorRunRecorder(audit,
                new SourceQuotaGuard(quotaStore, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)));
        UUID run = recorder.start("KTO_KOR_SERVICE_2", IngestAudit.TriggerType.READ_THROUGH,
                "v1", Instant.EPOCH);
        UUID log = recorder.reserve(run, "KTO_KOR_SERVICE_2", "SEARCH", "request-1234", "sha").ingestLogId();

        assertThat(recorder.finalizeSingleCall(run, log, 200, 12, 3, "a".repeat(64),
                new ProviderResponseValidator.Verdict(ProviderResponseValidator.Outcome.ENUM_DRIFT, 1),
                Instant.EPOCH.plusSeconds(1))).isFalse();
        assertThat(audit.call.validationResult()).isEqualTo(IngestAudit.ValidationResult.ENUM_DRIFT);
        assertThat(audit.finish.status()).isEqualTo(IngestAudit.RunStatus.QUARANTINED);
        assertThat(audit.finish.accepted()).isZero();
    }

    private static final class CapturingAudit implements IngestAudit {
        private CallRecord call;
        private FinishRun finish;

        @Override public UUID startRun(StartRun command) { return command.runId(); }
        @Override public void record(CallRecord record) { call = record; }
        @Override public void finishRun(FinishRun command) { finish = command; }
    }
}
