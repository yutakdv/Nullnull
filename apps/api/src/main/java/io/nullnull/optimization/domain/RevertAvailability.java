package io.nullnull.optimization.domain;

/**
 * Whether the traveller can still take an applied optimization back, as of this response.
 *
 * <p>Computed at response time and never stored. Storing it would create a second copy of an answer
 * that changes with the clock and with the trip, and the two would disagree the moment either moved
 * - which is the shape ERD section 2 avoids for this field by naming it a projection.
 *
 * <p>{@code AVAILABLE} is advisory. A caller that reads it has learned what the server believed when
 * it answered, not permission: {@code revertOptimizationDecision} rechecks owner, kind, window and
 * version inside its own transaction. Treating this value as the authority would put the decision in
 * the read path, where nothing serialises it.
 *
 * <p>The absence of the field means the server does not compute it, never that a client may decide
 * from its own clock (docs/api/README.md section 5).
 */
public enum RevertAvailability {

    /** An APPLY is on record, inside its window, and the trip is still where it left it. */
    AVAILABLE,

    /** An APPLY is on record and its 24-hour window has closed. */
    EXPIRED,

    /** The APPLY has already been taken back; a REVERT cannot itself be reverted. */
    REVERTED,

    /**
     * There is nothing to take back, or taking it back would no longer restore what it changed.
     *
     * <p>Two different situations share this value because the contract says so: a run with no APPLY
     * decision, and an APPLY whose trip has moved since. They are one answer to the caller - the undo
     * affordance is not offered - and splitting them would publish the reason a refusal would give.
     */
    NOT_APPLICABLE
}
