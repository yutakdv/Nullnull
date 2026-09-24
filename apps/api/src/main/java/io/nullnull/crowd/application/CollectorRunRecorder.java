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

    /**
     * Returns true only when the caller may write a canonical row or snapshot. Every refusal finishes
     * the run QUARANTINED, which stops the source's next call until a review releases it.
     */
    public boolean finalizeSingleCall(UUID runId, UUID ingestLogId, int httpStatus, int durationMs,
            int responseCount, String payloadHash, ProviderResponseValidator.Verdict verdict, Instant at) {
        return finalizeCall(runId, ingestLogId, httpStatus, durationMs, responseCount, payloadHash, verdict, at,
                false);
    }

    /**
     * As {@link #finalizeSingleCall}, except that a refusal the provider declared itself - a
     * {@code PROVIDER_ERROR} verdict - finishes the run FAILED, which the next call is not stopped at.
     * Every other refusal is QUARANTINED exactly as there: drift is not the provider saying something
     * went wrong, it is us no longer understanding what it sends.
     *
     * <p>Only the Seoul adapter uses this (A-0b, owner decision 2026-09-24). One refused Seoul response
     * quarantined SEOUL_CITYDATA for about ten hours on 2026-09-23: a QUARANTINED latest run stops
     * every later tick before it can ask again, so a provider that had recovered was never asked. The
     * KTO gateways keep {@link #finalizeSingleCall}; A-0b left them as they were.
     */
    public boolean finalizeSingleCallRetryingProviderErrors(UUID runId, UUID ingestLogId, int httpStatus,
            int durationMs, int responseCount, String payloadHash, ProviderResponseValidator.Verdict verdict,
            Instant at) {
        return finalizeCall(runId, ingestLogId, httpStatus, durationMs, responseCount, payloadHash, verdict, at,
                true);
    }

    private boolean finalizeCall(UUID runId, UUID ingestLogId, int httpStatus, int durationMs, int responseCount,
            String payloadHash, ProviderResponseValidator.Verdict verdict, Instant at, boolean retryProviderErrors) {
        IngestAudit.ValidationResult validation = IngestAudit.ValidationResult.valueOf(verdict.outcome().name());
        boolean accepted = verdict.permitsCanonicalWrite();
        audit.record(new IngestAudit.CallRecord(ingestLogId,
                accepted ? IngestAudit.CallOutcome.OK : IngestAudit.CallOutcome.VALIDATION_FAILED,
                httpStatus, durationMs, responseCount, payloadHash, validation));
        IngestAudit.RunStatus refused =
                retryProviderErrors && verdict.outcome() == ProviderResponseValidator.Outcome.PROVIDER_ERROR
                        ? IngestAudit.RunStatus.FAILED : IngestAudit.RunStatus.QUARANTINED;
        audit.finishRun(new IngestAudit.FinishRun(runId,
                accepted ? IngestAudit.RunStatus.COMPLETED : refused,
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
