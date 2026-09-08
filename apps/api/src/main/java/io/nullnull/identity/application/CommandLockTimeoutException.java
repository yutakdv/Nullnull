package io.nullnull.identity.application;

/**
 * The guarded transaction gave up waiting for a row lock instead of blocking forever. It is raised
 * when the {@code nullnull.idempotency.lock-timeout} bound expires while taking the owner-lifecycle
 * lock or the idempotency reservation, which means another command for the same owner is still in
 * flight. Nothing was reserved and nothing was applied: the whole transaction rolls back.
 *
 * <p>A dedicated exception rather than a driver exception, so callers never have to recognise a
 * PostgreSQL SQLState, and rather than {@link io.nullnull.shared.problem.ApiException}, because the
 * public error contract for this case does not exist yet.
 *
 * <p><strong>The API layer must map this to a retryable Problem</strong> (the client may repeat the
 * same request with the same {@code Idempotency-Key}). Which public {@code ProblemCode} that is
 * remains an OPEN CONTRACT QUESTION for card BA-003: none of the published codes in
 * docs/api/openapi.yaml means "another command for this session is still in flight", and inventing
 * one here would freeze a FE-facing shape the Frontend has not approved. A1 ships no HTTP endpoint,
 * so until BA-003 decides, this exception has no mapping and the catch-all handler turns it into
 * {@code INTERNAL_ERROR}.
 */
public class CommandLockTimeoutException extends RuntimeException {

    public CommandLockTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
