package io.nullnull.operations.infrastructure.jobs;

import io.nullnull.operations.application.JobLockTimeoutException;
import java.sql.SQLException;
import java.util.function.Supplier;

/**
 * Translates the driver's expired {@code lock_timeout} into the operations module's own exception, so
 * no layer above this package has to recognise a PostgreSQL SQLState. Every queue statement that can
 * wait for the job row goes through it.
 *
 * <p>Setting the bound and translating its expiry are two different things and both are needed:
 * {@code JdbcJobQueue} sets {@code lock_timeout} on each of its transactions, and without this
 * translation the expiry surfaces as {@code CannotAcquireLockException}, which the worker's catch-all
 * charges to the handler as a failed attempt.
 *
 * <p>A near-copy of {@code identity.infrastructure.persistence.BoundedLockWait} on purpose: that one is
 * package-private inside another module's infrastructure package, which §4 forbids this module from
 * importing, and each module names its own application exception.
 */
final class BoundedJobLockWait {

    /** PostgreSQL {@code lock_not_available}: raised when the {@code lock_timeout} bound expires. */
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private BoundedJobLockWait() {
    }

    static <T> T on(String operation, Supplier<T> lockingStatement) {
        try {
            return lockingStatement.get();
        } catch (RuntimeException failure) {
            if (isLockTimeout(failure)) {
                // No key, payload or SQL in the message: the operation name and the bound, nothing else.
                throw new JobLockTimeoutException("Timed out waiting for the job row during " + operation
                        + "; another transaction holds it.", failure);
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
