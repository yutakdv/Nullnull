package io.nullnull.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.nullnull.operations.application.OpsAlarm;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;

@DisplayName("BA-072 deletion receipt expiry lines")
class DeletionReceiptExpiryTest {

    private static final Instant NOW = Instant.parse("2026-03-11T05:06:07Z");
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private ch.qos.logback.classic.Logger alarmLogger;
    private ListAppender<ILoggingEvent> alarms;

    @BeforeEach
    void captureAlarms() {
        alarmLogger = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(OpsAlarm.class.getName());
        alarms = new ListAppender<>();
        alarms.start();
        alarmLogger.addAppender(alarms);
    }

    @AfterEach
    void release() {
        alarmLogger.detachAppender(alarms);
        alarms.stop();
    }

    @Test
    @DisplayName("only a receipt that expired before its deletion finished is reported, without its id")
    void onlyUnfinishedReceiptsAreReported() {
        DeletionStore store = mock(DeletionStore.class);
        List<ExpiredReceipt> expired = List.of(receipt("ACCEPTED", 0), receipt("RUNNING", 3),
                receipt("PARTIAL_FAILED", 2), receipt("COMPLETED", 1), receipt("FAILED", 5));
        when(store.expireStatusTokens(NOW)).thenReturn(expired);

        assertThat(new DeletionReceiptExpiry(store, new TransactionManager(false)).expire(NOW))
                .as("every expired receipt is answered, reported or not").isEqualTo(expired);

        assertThat(alarms.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "ops.alarm name=DELETION_RECEIPT_EXPIRED_UNFINISHED status=ACCEPTED attempt=0",
                "ops.alarm name=DELETION_RECEIPT_EXPIRED_UNFINISHED status=RUNNING attempt=3",
                "ops.alarm name=DELETION_RECEIPT_EXPIRED_UNFINISHED status=PARTIAL_FAILED attempt=2");
        assertThat(alarms.list).allSatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).doesNotContainPattern(UUID_PATTERN);
        });
    }

    @Test
    @DisplayName("an expiry that does not commit reports nothing")
    void anExpiryThatDoesNotCommitReportsNothing() {
        DeletionStore store = mock(DeletionStore.class);
        when(store.expireStatusTokens(NOW)).thenReturn(List.of(receipt("RUNNING", 1)));

        assertThatThrownBy(() -> new DeletionReceiptExpiry(store, new TransactionManager(true)).expire(NOW))
                .isInstanceOf(TransactionSystemException.class);
        assertThat(alarms.list).as("the hashes are still there, so the next sweep reports them").isEmpty();
    }

    private static ExpiredReceipt receipt(String status, int attempt) {
        return new ExpiredReceipt(UUID.randomUUID(), status, attempt);
    }

    private static final class TransactionManager implements PlatformTransactionManager {
        private final boolean commitFails;

        TransactionManager(boolean commitFails) {
            this.commitFails = commitFails;
        }

        @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus(true);
        }
        @Override public void commit(TransactionStatus status) {
            if (commitFails) {
                throw new TransactionSystemException("synthetic commit failure");
            }
        }
        @Override public void rollback(TransactionStatus status) {
        }
    }
}
