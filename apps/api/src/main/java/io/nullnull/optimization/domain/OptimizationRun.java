package io.nullnull.optimization.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One question asked of the optimizer, and everything needed to judge its answer later.
 *
 * <p>A run is immutable in the parts that describe the question - trip, scope, target, the version
 * and revision it froze - and moves only in the parts that describe the answer. That split is why
 * {@code inputTripVersion} is not refreshed when the trip changes: the run is a record of what was
 * asked, and a run whose input silently followed the trip could not detect that the trip moved.
 *
 * <p>{@code expiresAt} is the preview's own deadline, not the row's. An expired preview refuses a new
 * decision; it does not turn a decision that was already recorded into an expiry (getOptimization
 * says so outright), which is why expiry lives here beside the status rather than replacing it.
 */
public record OptimizationRun(UUID id, UUID tripId, UUID ownerId, OptimizationScope scope,
        UUID targetItemId, LocalDate targetDate, boolean includeCandidates, OptimizationStatus status,
        long inputTripVersion, UUID inputRevisionId, String dataFingerprint, String algorithmVersion,
        OptimizationFailureCode failureCode, String failureMessage, Instant queuedAt, Instant startedAt,
        Instant completedAt, Instant expiresAt, List<UUID> snapshotSetIds) {

    public OptimizationRun {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tripId, "tripId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(queuedAt, "queuedAt");
        // The same rule V024 writes as optimization_runs_target_check, here so that a run built in
        // memory cannot be shaped in a way the database would refuse.
        boolean targetShape = switch (scope) {
            case ITEM -> targetItemId != null && targetDate == null;
            case DAY -> targetDate != null && targetItemId == null;
            case TRIP -> targetItemId == null && targetDate == null;
        };
        if (!targetShape) {
            throw new IllegalArgumentException(scope + " does not take this target");
        }
        if (inputTripVersion < 1) {
            throw new IllegalArgumentException("inputTripVersion must be at least 1");
        }
        if ((failureCode == null) != (failureMessage == null)) {
            throw new IllegalArgumentException("a failure is a code and the sentence that goes with it");
        }
        if ((status == OptimizationStatus.FAILED) != (failureCode != null)) {
            throw new IllegalArgumentException("only a FAILED run carries a failure code");
        }
        snapshotSetIds = snapshotSetIds == null ? List.of() : List.copyOf(snapshotSetIds);
    }

    /** A run as it was read, before its frozen snapshot sets were looked up. */
    public OptimizationRun(UUID id, UUID tripId, UUID ownerId, OptimizationScope scope, UUID targetItemId,
            LocalDate targetDate, boolean includeCandidates, OptimizationStatus status,
            long inputTripVersion, UUID inputRevisionId, String dataFingerprint, String algorithmVersion,
            OptimizationFailureCode failureCode, String failureMessage, Instant queuedAt,
            Instant startedAt, Instant completedAt, Instant expiresAt) {
        this(id, tripId, ownerId, scope, targetItemId, targetDate, includeCandidates, status,
                inputTripVersion, inputRevisionId, dataFingerprint, algorithmVersion, failureCode,
                failureMessage, queuedAt, startedAt, completedAt, expiresAt, List.of());
    }

    /** A preview whose deadline has passed, which is not the same as a run that is EXPIRED. */
    public boolean previewExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }
}
