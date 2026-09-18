package io.nullnull.optimization.application;

import io.nullnull.optimization.domain.OptimizationDecisionKind;
import io.nullnull.optimization.domain.OptimizationScope;
import io.nullnull.optimization.domain.OptimizationStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The owner's optimization history, newest first.
 *
 * <p>Separate from {@link OptimizationRunStore} because it answers a different question with a
 * different row. The store hands back whole runs, including the frozen evidence a decision has to
 * revalidate; this returns only what the profile screen renders. The contract is explicit that the
 * history must not duplicate itinerary content ("This view never returns or causes a duplicate
 * itinerary snapshot"), and a projection that cannot carry proposals cannot leak them by accident.
 *
 * <p>The read this serves is the one {@code optimization_runs_owner_queued_idx} was created for -
 * V024 says so in its comment, two cards before anything called it.
 */
public interface OptimizationHistoryQuery {

    /**
     * One page of the owner's runs, ordered {@code queued_at DESC, id DESC}.
     *
     * @param ownerId derived from the session, never from the request
     * @param tripId  optional filter; null lists every trip's runs
     * @param after   resume after this row, or null for the first page
     * @param limit   rows to fetch; callers ask for one more than the page size to learn whether a
     *                next page exists without a second query
     */
    List<HistoryRow> page(UUID ownerId, UUID tripId, PageKey after, int limit);

    /** The row a history page ended on, in the terms {@code queued_at DESC, id DESC} sorts by. */
    record PageKey(Instant queuedAt, UUID runId) {
        public PageKey {
            Objects.requireNonNull(queuedAt, "queuedAt");
            Objects.requireNonNull(runId, "runId");
        }
    }

    /**
     * What the profile history shows: state, time, and where to go. No proposal, no snapshot, no
     * item.
     *
     * <p>{@code expiresAt} is here even though the contract's item has no such field, because the
     * status a reader sees must be computed the same way the detail computes it. A run stored READY
     * whose preview has expired reads EXPIRED from {@code getOptimization}; if the list reported the
     * stored value instead, the same run would carry two different states on two screens and the
     * traveller would be told a preview is waiting for them that is not.
     *
     * <p>{@code decision} and {@code decidedAt} come from the run's LATEST decision rather than its
     * first. A reverted run has two - the APPLY and the REVERT that undid it - and the one that
     * describes the run's current state is the later one, which is also what makes this column agree
     * with {@code status}: REVERTED next to REVERT rather than next to APPLY.
     */
    record HistoryRow(UUID runId, UUID tripId, String tripTitle, OptimizationScope scope,
            OptimizationStatus status, Instant queuedAt, Instant expiresAt,
            OptimizationDecisionKind decision, Instant decidedAt) {

        public HistoryRow {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(tripId, "tripId");
            Objects.requireNonNull(tripTitle, "tripTitle");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(queuedAt, "queuedAt");
            if ((decision == null) != (decidedAt == null)) {
                throw new IllegalArgumentException(
                        "a decision and the moment it was taken are present together or not at all");
            }
        }
    }
}
