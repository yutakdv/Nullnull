package io.nullnull.identity.application;

import io.nullnull.operations.application.TtlEraser;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retention for replay records: 24 hours by scheduled hard delete
 * (docs/architecture/ERD.md §6, {@code idempotency record}).
 *
 * <p>The window itself is {@code nullnull.idempotency.ttl}, stamped into each row as
 * {@code expires_at} when it is reserved, so changing the TTL changes what future rows promise and
 * this sweep needs no retention setting of its own. Identity owns the deletion because identity owns
 * the table; the operations module only owns the schedule.
 *
 * <p>The delete waits for row locks like any other statement, so this transaction sets
 * {@code nullnull.idempotency.lock-timeout} - the same bound a command gets - before it runs. Without
 * it the sweep blocks for as long as one expired row is held: measured at over twelve seconds against a
 * single row held {@code FOR UPDATE} from another connection, with no end in sight, and while it blocks
 * it pins a scheduler thread and a pooled connection and delays every other module's sweep behind it.
 * Bounded, a contended tick fails fast, is logged by {@link io.nullnull.operations.application.TtlSweep}
 * and simply runs again on the next interval; the rows it did not reach are still expired and are still
 * refused a replay by {@link IdempotencyGuard}.
 */
@Component
public class ExpiredIdempotencyRecordEraser implements TtlEraser {

    private final IdempotencyRecordStore records;
    private final LockWaitLimit lockWaitLimit;
    private final Duration lockTimeout;

    public ExpiredIdempotencyRecordEraser(IdempotencyRecordStore records, LockWaitLimit lockWaitLimit,
            @Value("${nullnull.idempotency.lock-timeout}") Duration lockTimeout) {
        this.records = records;
        this.lockWaitLimit = lockWaitLimit;
        // The floor for this property is validated by IdempotencyGuard, which shares it and fails the
        // same context start; repeating the check here would only duplicate the message.
        this.lockTimeout = lockTimeout;
    }

    @Override
    public String name() {
        return "idempotency-records";
    }

    @Override
    @Transactional
    public int erase(Instant now) {
        lockWaitLimit.applyToCurrentTransaction(lockTimeout);
        return records.deleteExpired(now);
    }
}
