package io.nullnull.operations.application;

/**
 * A queue statement gave up waiting for the job row instead of blocking forever: the
 * {@code nullnull.jobs.lock-timeout} bound expired while another transaction held the row.
 *
 * <p>This is contention, not a handler failure, and the difference is the point of the type. Before it
 * existed the driver's raw {@code CannotAcquireLockException} reached the worker's catch-all and was
 * recorded as {@code HANDLER_ERROR}, so a healthy job could be dead-lettered by losing a race with
 * another worker's still-open unit of work. The worker now lets such an attempt go: nothing was
 * written, the lease lapses, and the job is re-taken with no attempt charged to the handler.
 *
 * <p>The identity module has the same translation for its own statements
 * ({@code CommandLockTimeoutException}), and deliberately not a shared one: its helper is private to
 * {@code identity.infrastructure.persistence} and §4 forbids one module from reaching into another's
 * infrastructure package.
 *
 * <p>No public HTTP contract: background work has no caller to answer.
 */
public class JobLockTimeoutException extends RuntimeException {

    public JobLockTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
