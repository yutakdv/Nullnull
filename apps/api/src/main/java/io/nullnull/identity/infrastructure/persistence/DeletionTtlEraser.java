package io.nullnull.identity.infrastructure.persistence;

import io.nullnull.identity.application.DeletionReceiptExpiry;
import io.nullnull.identity.application.DeletionStore;
import io.nullnull.operations.application.TtlEraser;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two transactions, receipts first: the expiry commits and reports before the hard delete runs, so a hard
 * delete that fails cannot take back a line already logged, and an expiry that fails stops this tick
 * before anything is removed (TtlSweep logs it and the next tick tries again).
 */
@Component
public class DeletionTtlEraser implements TtlEraser {
    private final DeletionReceiptExpiry receipts;
    private final DeletionStore deletions;
    private final TransactionTemplate transactions;

    public DeletionTtlEraser(DeletionReceiptExpiry receipts, DeletionStore deletions,
            PlatformTransactionManager transactionManager) {
        this.receipts=receipts; this.deletions=deletions;
        this.transactions=new TransactionTemplate(transactionManager);
    }
    @Override public String name() { return "deletion-receipts"; }
    @Override
    public int erase(Instant now) {
        int expired = receipts.expire(now).size();
        return expired + Objects.requireNonNull(
                transactions.execute(status -> deletions.hardDeleteEligibleOwners(now)), "hard deleted owners");
    }
}
