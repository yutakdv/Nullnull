package io.nullnull.identity.infrastructure.persistence;

import io.nullnull.identity.application.LockWaitLimit;
import java.time.Duration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code SET LOCAL lock_timeout} on the connection the current transaction already holds. LOCAL, so
 * PostgreSQL restores the previous value at commit or rollback and the pooled connection carries
 * nothing into the next transaction.
 */
@Repository
public class JdbcLockWaitLimit implements LockWaitLimit {

    private final JdbcClient jdbc;

    JdbcLockWaitLimit(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** MANDATORY: SET LOCAL outside a transaction is discarded without any error. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyToCurrentTransaction(Duration timeout) {
        long milliseconds = timeout.toMillis();
        if (milliseconds <= 0) {
            // PostgreSQL reads 0 as "wait forever", which is the opposite of a bound.
            throw new IllegalArgumentException("lock wait limit must be at least 1ms: " + timeout);
        }
        // SET takes no bind parameters, so the value is inlined; it is a long derived from
        // configuration, never text from a request.
        jdbc.sql("SET LOCAL lock_timeout = " + milliseconds).update();
    }
}
