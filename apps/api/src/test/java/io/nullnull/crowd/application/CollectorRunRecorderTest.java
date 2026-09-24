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

    /**
     * KTO keeps the path it had. A-065 moved one source's provider-declared errors off quarantine and
     * said "KTO untouched" in the same breath; this is what holds the second half. Point the shared
     * path at the Seoul rule and this goes red while every Seoul test stays green.
     */
    @Test
    @DisplayName("BA-020-T9 the shared path still closes a run the provider declared an error on QUARANTINED")
    void theSharedPathStillQuarantinesAProviderDeclaredError() {
        CapturingAudit audit = new CapturingAudit();
        CollectorRunRecorder recorder = recorder(audit);
        UUID run = recorder.start("KTO_KOR_SERVICE_2", IngestAudit.TriggerType.READ_THROUGH, "v1", Instant.EPOCH);
        UUID log = recorder.reserve(run, "KTO_KOR_SERVICE_2", "SEARCH", "request-1234", "sha").ingestLogId();

        assertThat(recorder.finalizeSingleCall(run, log, 200, 12, 1, null,
                new ProviderResponseValidator.Verdict(ProviderResponseValidator.Outcome.PROVIDER_ERROR, 1),
                Instant.EPOCH.plusSeconds(1))).isFalse();
        assertThat(audit.call.validationResult()).isEqualTo(IngestAudit.ValidationResult.PROVIDER_ERROR);
        assertThat(audit.finish.status()).isEqualTo(IngestAudit.RunStatus.QUARANTINED);
        assertThat(audit.finish.errorCode()).isEqualTo("PROVIDER_ERROR");
    }

    /**
     * The whole table, not the one row A-065 is about: a path that also let RANGE or MAPPING_UNCERTAIN
     * through as FAILED would pass a test that only asked about PROVIDER_ERROR.
     */
    @Test
    @DisplayName("the Seoul path closes only a provider-declared error FAILED, and quarantines every other refusal")
    void theSeoulPathRetriesOnlyWhatTheProviderDeclared() {
        for (ProviderResponseValidator.Outcome outcome : ProviderResponseValidator.Outcome.values()) {
            CapturingAudit audit = new CapturingAudit();
            CollectorRunRecorder recorder = recorder(audit);
            UUID run = recorder.start("SEOUL_CITYDATA", IngestAudit.TriggerType.SCHEDULED, "v1", Instant.EPOCH);
            UUID log = recorder.reserve(run, "SEOUL_CITYDATA", "CITYDATA", "request-1234", "sha").ingestLogId();

            boolean accepted = recorder.finalizeSingleCallRetryingProviderErrors(run, log, 200, 12, 1, null,
                    new ProviderResponseValidator.Verdict(outcome, outcome == ProviderResponseValidator.Outcome.OK ? 0 : 1),
                    Instant.EPOCH.plusSeconds(1));

            IngestAudit.RunStatus expected = switch (outcome) {
                case OK -> IngestAudit.RunStatus.COMPLETED;
                case PROVIDER_ERROR -> IngestAudit.RunStatus.FAILED;
                default -> IngestAudit.RunStatus.QUARANTINED;
            };
            assertThat(audit.finish.status()).as("%s", outcome).isEqualTo(expected);
            assertThat(accepted).as("%s: only OK writes", outcome).isEqualTo(outcome == ProviderResponseValidator.Outcome.OK);
            assertThat(audit.call.validationResult()).as("%s: the verdict's own word", outcome)
                    .isEqualTo(IngestAudit.ValidationResult.valueOf(outcome.name()));
            assertThat(audit.finish.errorCode()).as("%s", outcome)
                    .isEqualTo(outcome == ProviderResponseValidator.Outcome.OK ? null : outcome.name());
        }
    }

    private static CollectorRunRecorder recorder(CapturingAudit audit) {
        SourceQuotaStore quotaStore = (request, start, end) ->
                new SourceQuotaStore.Reservation(UUID.randomUUID(), 1, 1000, java.util.List.of());
        return new CollectorRunRecorder(audit,
                new SourceQuotaGuard(quotaStore, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)));
    }

    private static final class CapturingAudit implements IngestAudit {
        private CallRecord call;
        private FinishRun finish;

        @Override public UUID startRun(StartRun command) { return command.runId(); }
        @Override public void record(CallRecord record) { call = record; }
        @Override public void finishRun(FinishRun command) { finish = command; }
    }
}
