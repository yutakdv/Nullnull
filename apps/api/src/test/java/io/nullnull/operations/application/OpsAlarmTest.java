package io.nullnull.operations.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.nullnull.operations.application.OpsAlarm.Name;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The rendered lines, as literals. A metric filter quotes {@code "ops.alarm name=<NAME>"} and nothing reads
 * the Java to find out what that phrase is, so this is the only place a change to it is visible - the
 * integration tests build their expected lines with the same factory and cannot see a reordering.
 */
@DisplayName("BA-072 ops.alarm lines")
class OpsAlarmTest {

    private static final UUID JOB = UUID.fromString("01890000-0000-7000-8000-000000000001");

    @Test
    @DisplayName("every name renders one fixed line, fields in a fixed order")
    void everyNameRendersItsLiteralLine() {
        assertThat(OpsAlarm.deletionPartialFailed(JOB, 1, "OWNER_DATA_ERASE_FAILED").line()).isEqualTo(
                "ops.alarm name=DELETION_PARTIAL_FAILED jobId=01890000-0000-7000-8000-000000000001 attempt=1"
                        + " errorCode=OWNER_DATA_ERASE_FAILED");
        assertThat(OpsAlarm.deletionFailed(JOB, 5, "OWNER_DATA_ERASE_FAILED").line()).isEqualTo(
                "ops.alarm name=DELETION_FAILED jobId=01890000-0000-7000-8000-000000000001 attempt=5"
                        + " errorCode=OWNER_DATA_ERASE_FAILED");
        assertThat(OpsAlarm.jobLeaseRetaken("delete-owner-data", JOB, 2, 5).line()).isEqualTo(
                "ops.alarm name=JOB_LEASE_RETAKEN type=delete-owner-data"
                        + " jobId=01890000-0000-7000-8000-000000000001 attempt=2 maxAttempts=5");
        assertThat(OpsAlarm.jobDeadLetter("delete-owner-data", JOB, 5, "LEASE_EXPIRED").line()).isEqualTo(
                "ops.alarm name=JOB_DEAD_LETTER type=delete-owner-data"
                        + " jobId=01890000-0000-7000-8000-000000000001 attempt=5 errorCode=LEASE_EXPIRED");
        assertThat(OpsAlarm.deletionReceiptExpiredUnfinished("PARTIAL_FAILED", 2).line()).isEqualTo(
                "ops.alarm name=DELETION_RECEIPT_EXPIRED_UNFINISHED status=PARTIAL_FAILED attempt=2");
    }

    @Test
    @DisplayName("no phrase is the start of another, so a filter on one never matches a second name")
    void noPhraseIsAPrefixOfAnother() {
        for (Name one : Name.values()) {
            for (Name other : Name.values()) {
                if (one != other) {
                    // A filter is a substring match; a rendered line holds its phrase once, at the start.
                    assertThat(other.phrase()).as("%s is matched by the filter for %s", other, one)
                            .doesNotStartWith(one.phrase());
                }
            }
        }
    }

    @Test
    @DisplayName("a retry-in-progress name is a warning and a name that needs a person is an error")
    void emitLogsAtTheNamesLevel() {
        ch.qos.logback.classic.Logger logger =
                ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(OpsAlarm.class.getName());
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        try {
            OpsAlarm.emit(OpsAlarm.deletionPartialFailed(JOB, 1, "OWNER_DATA_ERASE_FAILED"));
            OpsAlarm.emit(OpsAlarm.deletionFailed(JOB, 5, "OWNER_DATA_ERASE_FAILED"));
            OpsAlarm.emit(OpsAlarm.jobLeaseRetaken("t", JOB, 2, 5));
            OpsAlarm.emit(OpsAlarm.jobDeadLetter("t", JOB, 5, "LEASE_EXPIRED"));
            OpsAlarm.emit(OpsAlarm.deletionReceiptExpiredUnfinished("RUNNING", 1));
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
        assertThat(captured.list).extracting(ILoggingEvent::getLevel).containsExactly(
                Level.WARN, Level.ERROR, Level.WARN, Level.ERROR, Level.ERROR);
        assertThat(captured.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).startsWith("ops.alarm name=");
            assertThat(event.getThrowableProxy()).as("one line, no stack").isNull();
        });
    }
}
