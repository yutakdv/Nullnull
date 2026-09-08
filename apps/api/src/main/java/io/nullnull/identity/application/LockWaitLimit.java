package io.nullnull.identity.application;

import java.time.Duration;

/**
 * Bounds how long the current transaction waits for a row lock.
 *
 * <p>Without a bound, two commands for the same owner serialise on the owner-lifecycle row and one
 * stuck command blocks every later command for that owner indefinitely while holding a pooled
 * connection. With a bound the second caller fails fast with
 * {@link CommandLockTimeoutException} and its connection returns to the pool.
 *
 * <p>The limit applies to the current transaction only, so it never leaks to the next user of the
 * pooled connection.
 */
public interface LockWaitLimit {

    /**
     * Applies the limit to the current transaction. Requires an existing transaction: a limit set
     * outside one would be discarded before the locks it is meant to bound are taken.
     */
    void applyToCurrentTransaction(Duration timeout);
}
