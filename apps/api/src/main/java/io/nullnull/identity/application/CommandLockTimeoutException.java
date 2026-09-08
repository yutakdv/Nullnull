package io.nullnull.identity.application;

/**
 * The guarded transaction gave up waiting for a row lock instead of blocking forever. It is raised
 * when the {@code nullnull.idempotency.lock-timeout} bound expires while taking the owner-lifecycle
 * lock or the idempotency reservation, which means another command for the same owner is still in
 * flight. Nothing was reserved and nothing was applied: the whole transaction rolls back.
 *
 * <p>A dedicated exception rather than a driver exception, so callers never have to recognise a
 * PostgreSQL SQLState, and rather than {@link io.nullnull.shared.problem.ApiException}, so the module
 * that raises it does not also pick its public status.
 *
 * <p><strong>BA-003 decided the public contract</strong> that BA-002 left open, and the answer is not
 * a 24th {@code ProblemCode}. This timeout is raised while acquiring the owner row or the idempotency
 * reservation, which both happen BEFORE the command runs, so nothing has been reserved and nothing has
 * been applied: a repeat is structurally safe rather than merely idempotent. {@link IdempotencyGuard}
 * therefore absorbs transient contention itself with a bounded retry instead of publishing it. If the
 * retry budget is exhausted, a command has held the owner row for seconds - and since commands are
 * short and external calls inside a transaction are forbidden, that is a server defect the user cannot
 * fix by pressing the button again. {@code io.nullnull.identity.api.IdentityProblemHandler} maps the
 * exhausted case to {@code INTERNAL_ERROR} (500, not retryable) with an operator-facing log line.
 *
 * <p>Adding {@code COMMAND_IN_PROGRESS} (409, retryable=false) only becomes right if measurement shows
 * genuine user-visible contention; that needs a CON ticket with an FE CTA and a Figma state, because
 * CLAUDE.md requires both for a new code.
 */
public class CommandLockTimeoutException extends RuntimeException {

    public CommandLockTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
