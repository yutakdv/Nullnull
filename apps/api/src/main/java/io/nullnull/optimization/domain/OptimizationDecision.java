package io.nullnull.optimization.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One answer a traveller gave about a proposal, and what it did to the trip.
 *
 * <p>The three shapes V030 writes as a CHECK are mirrored here, so a decision the database would
 * refuse cannot be built in memory and travel half the call path first. KEEP carries no trip effect
 * at all; APPLY carries the revisions it moved between and the window in which it can be undone;
 * REVERT carries the decision it undoes and no window of its own, because a REVERT that could be
 * reverted has no end.
 *
 * <p>{@code expectedTripVersion} is kept for KEEP too. A KEEP is also an answer about a particular
 * trip, and without the version there is no way to say which one it answered - which matters when
 * the same run is polled after the trip has moved.
 */
public record OptimizationDecision(UUID id, UUID runId, UUID proposalId, UUID ownerId,
        OptimizationDecisionKind decision, long expectedTripVersion, Long resultingTripVersion,
        UUID beforeRevisionId, UUID afterRevisionId, UUID revertedDecisionId, Instant revertUntil,
        Instant decidedAt) {

    public OptimizationDecision {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(proposalId, "proposalId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(decidedAt, "decidedAt");
        if (expectedTripVersion < 1) {
            throw new IllegalArgumentException("expectedTripVersion starts at 1");
        }
        boolean movesTheTrip = resultingTripVersion != null && beforeRevisionId != null
                && afterRevisionId != null;
        if (decision.changesTheTrip() != movesTheTrip) {
            throw new IllegalArgumentException(
                    decision + " carries a resulting version and both revisions unless it is a KEEP");
        }
        if ((revertedDecisionId != null) != (decision == OptimizationDecisionKind.REVERT)) {
            throw new IllegalArgumentException("only a REVERT names the decision it undoes");
        }
        if ((revertUntil != null) != (decision == OptimizationDecisionKind.APPLY)) {
            throw new IllegalArgumentException("only an APPLY can still be taken back");
        }
    }

    /** The trip version this decision produced, or the one it was made against when it changed nothing. */
    public long effectiveTripVersion() {
        return resultingTripVersion == null ? expectedTripVersion : resultingTripVersion;
    }
}
