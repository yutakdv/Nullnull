package io.nullnull.operations.domain;

/**
 * Lifecycle of a row in {@code background_jobs} (docs/architecture/ERD.md §5, enum
 * {@code BackgroundJobStatus}). The names are persisted as text and checked by
 * {@code background_jobs_status_check}, so they are part of the schema and not a private detail.
 *
 * <p>{@code READY} and {@code RETRY} are the two claimable states and differ only in how the row got
 * there: {@code READY} was enqueued, {@code RETRY} failed an attempt and carries a back-off
 * {@code next_attempt_at}. Keeping them apart lets an operator see at a glance whether a queue is
 * backed up or retrying. {@code COMPLETED} and {@code FAILED} are terminal; {@code FAILED} is the
 * dead-letter state reached by exhausting {@code max_attempts}.
 */
public enum JobStatus {
    READY,
    RETRY,
    RUNNING,
    COMPLETED,
    FAILED;

    /** True for the two terminal states, which the retention sweep is allowed to delete. */
    public boolean finished() {
        return this == COMPLETED || this == FAILED;
    }
}
