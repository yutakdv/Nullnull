package io.nullnull.identity.infrastructure.persistence;

import io.nullnull.identity.application.CommandLockTimeoutException;
import java.sql.SQLException;
import java.util.function.Supplier;

/**
 * Translates the driver's expired {@code lock_timeout} into the identity module's own exception, so
 * no layer above this package has to recognise a PostgreSQL SQLState. Every operation that can wait
 * for a row lock inside a guarded transaction goes through it.
 */
final class BoundedLockWait {

    /** PostgreSQL {@code lock_not_available}: raised when the {@code lock_timeout} bound expires. */
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private BoundedLockWait() {
    }

    static <T> T on(Supplier<T> lockingOperation) {
        try {
            return lockingOperation.get();
        } catch (RuntimeException failure) {
            if (isLockTimeout(failure)) {
                // No owner, key or SQL in the message: another command for this owner holds the lock.
                throw new CommandLockTimeoutException(
                        "Timed out waiting for a row lock; another command for this owner is still "
                                + "in flight.", failure);
            }
            throw failure;
        }
    }

    private static boolean isLockTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && LOCK_NOT_AVAILABLE.equals(sql.getSQLState())) {
                return true;
            }
            if (cause.getCause() == cause) {
                return false;
            }
        }
        return false;
    }
}
