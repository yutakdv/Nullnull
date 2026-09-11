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

    enum ValidationResult { PENDING, OK, SCHEMA_DRIFT, ENUM_DRIFT, RANGE, TIME_SKEW, PROVIDER_ERROR }

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
