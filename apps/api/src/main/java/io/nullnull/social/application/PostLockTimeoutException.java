package io.nullnull.social.application;

/**
 * A post row another transaction held for longer than a withdrawal waits
 * ({@code nullnull.posts.withdrawal-lock-timeout}).
 *
 * <p>Named so the operator task's failure line says what happened ({@code reason=PostLockTimeoutException})
 * instead of the driver's uncategorised SQL error, which is what an expired {@code lock_timeout}
 * otherwise arrives as. Nothing was written; running the task again is the answer.
 */
public class PostLockTimeoutException extends RuntimeException {

    public PostLockTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
