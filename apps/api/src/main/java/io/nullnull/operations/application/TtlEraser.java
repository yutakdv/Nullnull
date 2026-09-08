package io.nullnull.operations.application;

import java.time.Instant;

/**
 * One module's retention sweep, run by {@link TtlSweep}.
 *
 * <p>docs/architecture/ERD.md §6 gives every stored kind a retention and a deletion method, and each
 * of them belongs to the module that owns the table: a sweep that reached across modules would be
 * exactly the cross-module table access §4 forbids. Implement this next to the store it deletes from
 * and expose it as a bean; nothing else is needed to join the schedule.
 *
 * <p>Two erasers exist today - expired idempotency records (24 hours) and finished job rows. Revoked
 * sessions, CSRF tokens and deletion status tokens arrive with their own slices and add themselves
 * the same way.
 */
public interface TtlEraser {

    /** Stable name for the sweep log; also what a report row is keyed by. */
    String name();

    /**
     * Deletes what is past retention at {@code now} and returns how many rows went.
     *
     * <p>Must be exact: deleting a row that is still inside its retention is data loss, and leaving a
     * row that is past it breaks the promise made to the user in the privacy policy.
     */
    int erase(Instant now);
}
