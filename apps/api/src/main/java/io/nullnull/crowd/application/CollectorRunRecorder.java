package io.nullnull.crowd.application;

import io.nullnull.operations.application.IngestAudit;
import io.nullnull.shared.provider.ProviderResponseValidator;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** One-call collector ledger used by the read-through adapters introduced in C2. */
@Service
public class CollectorRunRecorder {

    private final IngestAudit audit;
    private final SourceQuotaGuard quota;

    public CollectorRunRecorder(IngestAudit audit, SourceQuotaGuard quota) {
        this.audit = audit;
        this.quota = quota;
    }

    public UUID start(String sourceCode, IngestAudit.TriggerType trigger, String schemaVersion, Instant at) {
        return audit.startRun(new IngestAudit.StartRun(UUID.randomUUID(), sourceCode, trigger, schemaVersion, at));
    }

    public SourceQuotaStore.Reservation reserve(UUID runId, String sourceCode, String endpointKey,
            String requestId, String releaseVersion) {
        return quota.acquire(runId, sourceCode, endpointKey, requestId, releaseVersion);
    }

    /** Returns true only when the caller may write a canonical row or snapshot. */
    public boolean finalizeSingleCall(UUID runId, UUID ingestLogId, int httpStatus, int durationMs,
            int responseCount, String payloadHash, ProviderResponseValidator.Verdict verdict, Instant at) {
        IngestAudit.ValidationResult validation = IngestAudit.ValidationResult.valueOf(verdict.outcome().name());
        boolean accepted = verdict.permitsCanonicalWrite();
        audit.record(new IngestAudit.CallRecord(ingestLogId,
                accepted ? IngestAudit.CallOutcome.OK : IngestAudit.CallOutcome.VALIDATION_FAILED,
                httpStatus, durationMs, responseCount, payloadHash, validation));
        audit.finishRun(new IngestAudit.FinishRun(runId,
                accepted ? IngestAudit.RunStatus.COMPLETED : IngestAudit.RunStatus.QUARANTINED,
                responseCount, accepted ? responseCount : 0, accepted ? 0 : responseCount,
                accepted ? null : verdict.outcome().name(), at));
        return accepted;
    }

    /** Closes a reserved call without retaining any provider message, URI, body or credential. */
    public void failSingleCall(UUID runId, UUID ingestLogId, IngestAudit.CallOutcome outcome, Integer httpStatus,
            int durationMs, String errorCode, Instant at) {
        if (outcome == IngestAudit.CallOutcome.STARTED || outcome == IngestAudit.CallOutcome.OK
                || outcome == IngestAudit.CallOutcome.QUOTA_EXHAUSTED) {
            throw new IllegalArgumentException("failure outcome required");
        }
        audit.record(new IngestAudit.CallRecord(ingestLogId, outcome, httpStatus, durationMs, 0, null,
                IngestAudit.ValidationResult.PROVIDER_ERROR));
        failRun(runId, errorCode, at);
    }

    /** Closes a run which could not reserve a provider call, such as a quota refusal. */
    public void failRun(UUID runId, String errorCode, Instant at) {
        if (errorCode == null || !errorCode.matches("[A-Z0-9_:-]{2,100}")) {
            throw new IllegalArgumentException("safe errorCode required");
        }
        audit.finishRun(new IngestAudit.FinishRun(runId, IngestAudit.RunStatus.FAILED,
                0, 0, 0, errorCode, at));
    }
}
