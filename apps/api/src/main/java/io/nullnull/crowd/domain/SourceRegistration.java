package io.nullnull.crowd.domain;

import java.time.Instant;
import java.util.Objects;

/** Immutable read model of the active registry revision used to gate every provider call. */
public record SourceRegistration(
        String code,
        String displayName,
        SourceState sourceState,
        ApprovalState approvalState,
        LicenseReviewState licenseReviewState,
        int quotaPerDay,
        Long staleAfterSeconds,
        boolean enabled,
        long currentRevision,
        String providerSchemaVersion,
        Instant reviewedAt) {

    public SourceRegistration {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(sourceState, "sourceState");
        Objects.requireNonNull(approvalState, "approvalState");
        Objects.requireNonNull(licenseReviewState, "licenseReviewState");
        Objects.requireNonNull(providerSchemaVersion, "providerSchemaVersion");
        Objects.requireNonNull(reviewedAt, "reviewedAt");
        if (quotaPerDay < 1 || currentRevision < 1) {
            throw new IllegalArgumentException("quotaPerDay and currentRevision must be positive");
        }
        if (staleAfterSeconds != null && staleAfterSeconds < 1) {
            throw new IllegalArgumentException("staleAfterSeconds must be positive when present");
        }
    }

    public boolean collectionEnabled() {
        return enabled && staleAfterSeconds != null && approvalState.permitsCollection();
    }
}
