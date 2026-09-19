package io.nullnull.identity.application;

import io.nullnull.operations.application.OpsAlarm;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Expires deletion receipts and reports each one that ran out before its deletion finished (BA-072):
 * after that the person cannot see the deletion any more, and only an operator can.
 *
 * <p>Detected at the transition, not counted per sweep. A receipt loses its hash exactly once, in the
 * one statement that returns the row, so the line is once per request without a column to remember it.
 * A per-sweep count of such rows would repeat every minute until the tombstone is hard deleted and would
 * need a marker migration to tell a new case from an old one.
 *
 * <p>The line is emitted only after the expiry committed; a pass that rolls back logs nothing and its rows
 * stay due for the next sweep. A crash between the commit and the line loses the line. What remains while
 * the tombstone is retained is every request whose receipt ran out before it finished,
 * {@code status_token_hash IS NULL AND (completed_at IS NULL OR completed_at > status_token_expires_at)}:
 * a superset of the lines, since it also holds a request that finished after its expiry but before a sweep
 * reached it, and one that finished later stays in it as COMPLETED or FAILED.
 */
@Component
public class DeletionReceiptExpiry {

    private final DeletionStore deletions;
    private final TransactionTemplate transactions;

    public DeletionReceiptExpiry(DeletionStore deletions, PlatformTransactionManager transactionManager) {
        this.deletions = deletions;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public List<ExpiredReceipt> expire(Instant now) {
        List<ExpiredReceipt> expired = Objects.requireNonNull(
                transactions.execute(status -> deletions.expireStatusTokens(now)), "expired receipts");
        for (ExpiredReceipt receipt : expired) {
            if (receipt.unfinished()) {
                OpsAlarm.emit(OpsAlarm.deletionReceiptExpiredUnfinished(receipt.status(), receipt.attempt()));
            }
        }
        return expired;
    }
}
