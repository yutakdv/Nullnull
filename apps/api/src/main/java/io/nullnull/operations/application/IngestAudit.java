package io.nullnull.operations.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Safe ingest ledger. Its API deliberately has no credential, URI, query, request body or user-data
 * argument, so an adapter cannot accidentally persist any of them.
 */
public interface IngestAudit {

    UUID startRun(StartRun command);

    void record(CallRecord record);

    void finishRun(FinishRun command);

    enum TriggerType { SCHEDULED, READ_THROUGH, MANUAL, REPLAY }

    enum RunStatus { COMPLETED, FAILED, QUARANTINED }

    enum CallOutcome {
        STARTED, OK, HTTP_ERROR, TIMEOUT, IO_ERROR, QUOTA_EXHAUSTED, CIRCUIT_OPEN, VALIDATION_FAILED
    }

    /**
     * Mirrors {@link io.nullnull.shared.provider.ProviderResponseValidator.Outcome} by NAME:
     * CollectorRunRecorder bridges the two with {@code valueOf(verdict.outcome().name())}, so a value
     * added to one and not the other fails at runtime on the branch that produces it, and nowhere
     * else. ProviderOutcomeVocabularyTest pins the two enums and the V018 CHECK together.
     */
    enum ValidationResult { PENDING, OK, SCHEMA_DRIFT, ENUM_DRIFT, RANGE, TIME_SKEW, PROVIDER_ERROR,
        MAPPING_UNCERTAIN }

    record StartRun(UUID runId, String sourceCode, TriggerType triggerType, String schemaVersion,
            Instant startedAt) {
    }

    record CallRecord(UUID ingestLogId, CallOutcome outcome, Integer httpStatus, Integer durationMs,
            Integer responseCount, String payloadHash, ValidationResult validationResult) {
    }

    record FinishRun(UUID runId, RunStatus status, int received, int accepted, int rejected,
            String errorCode, Instant finishedAt) {
    }
}
