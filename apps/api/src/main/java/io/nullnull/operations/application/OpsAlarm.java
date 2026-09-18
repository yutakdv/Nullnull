package io.nullnull.operations.application;

import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

/**
 * The one definition of the lines an operator alarms on (BA-072): a fixed phrase, then fields in a fixed
 * order.
 *
 * <pre>
 * ops.alarm name=DELETION_PARTIAL_FAILED jobId=&lt;uuid&gt; attempt=&lt;n&gt; errorCode=&lt;CODE&gt;
 * ops.alarm name=DELETION_FAILED jobId=&lt;uuid&gt; attempt=&lt;n&gt; errorCode=&lt;CODE&gt;
 * ops.alarm name=JOB_LEASE_RETAKEN type=&lt;type&gt; jobId=&lt;uuid&gt; attempt=&lt;n&gt; maxAttempts=&lt;m&gt;
 * ops.alarm name=JOB_DEAD_LETTER type=&lt;type&gt; jobId=&lt;uuid&gt; attempt=&lt;n&gt; errorCode=&lt;CODE&gt;
 * ops.alarm name=DELETION_RECEIPT_EXPIRED_UNFINISHED status=&lt;STATUS&gt; attempt=&lt;n&gt;
 * </pre>
 *
 * <p>A metric filter matches the quoted phrase {@code "ops.alarm name=<NAME>"}, which holds a space and an
 * {@code =}. Every value on these lines comes from a closed alphabet with neither: a job type
 * ({@code JobRequest}), an error code ({@code JobExecutionException}), a deletion status (V006's CHECK), a
 * UUID and a number. Of what a caller can put into other lines, X-Request-ID cannot hold them
 * (RequestIdFilter) and neither can an HTTP method (a token). The raw query string can reach the access
 * log only where APP_ACCESS_LOG_INCLUDE_QUERY is on, never in production, and is not argued here: a filter
 * in such an environment should also match the logger. The logs are plain text, not structured, so a
 * filter can only match the phrase, and the phrase is only as trustworthy as this argument.
 *
 * <p>The only identifier is the job id: it is never sent to a client (job ids precede this class in
 * JobWorker's own lines). No owner id - the privacy log allowlist admits an irreversible owner hash only
 * (PRIVACY_REQUIREMENTS §8) - no deletion request id, which a client holds and a raw id is exactly what the
 * access log refuses to print, no deduplication key (it names the owner), no payload, no token and no
 * throwable. The receipt line carries no id at all; the rows it can have spoken for are found in the
 * database instead (DeletionReceiptExpiry).
 */
public final class OpsAlarm {

    private static final Logger log = LoggerFactory.getLogger(OpsAlarm.class);

    static final String TOKEN = "ops.alarm";

    public enum Name {
        /** A deletion attempt failed and another attempt is due. */
        DELETION_PARTIAL_FAILED(Level.WARN),
        /** A deletion's last attempt failed: the owner's data is not all erased and nothing will retry. */
        DELETION_FAILED(Level.ERROR),
        /** A job's lease lapsed with attempts left and another worker took it over. */
        JOB_LEASE_RETAKEN(Level.WARN),
        /** A job ended without succeeding, from a thrown failure or from a lease that lapsed on the last attempt. */
        JOB_DEAD_LETTER(Level.ERROR),
        /** A deletion receipt expired while the deletion was still unfinished: the person can no longer see it. */
        DELETION_RECEIPT_EXPIRED_UNFINISHED(Level.ERROR);

        private final Level level;

        Name(Level level) {
            this.level = level;
        }

        public Level level() {
            return level;
        }

        /** What a metric filter quotes: the token and the name. */
        public String phrase() {
            return TOKEN + " name=" + name();
        }
    }

    /** One rendered line and the name it was rendered for. */
    public record Alarm(Name name, String line) {
        public Alarm {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(line, "line");
        }
    }

    private OpsAlarm() {
    }

    public static Alarm deletionPartialFailed(UUID jobId, int attempt, String errorCode) {
        return of(Name.DELETION_PARTIAL_FAILED, "jobId=" + jobId, "attempt=" + attempt, "errorCode=" + errorCode);
    }

    public static Alarm deletionFailed(UUID jobId, int attempt, String errorCode) {
        return of(Name.DELETION_FAILED, "jobId=" + jobId, "attempt=" + attempt, "errorCode=" + errorCode);
    }

    public static Alarm jobLeaseRetaken(String type, UUID jobId, int attempt, int maxAttempts) {
        return of(Name.JOB_LEASE_RETAKEN, "type=" + type, "jobId=" + jobId, "attempt=" + attempt,
                "maxAttempts=" + maxAttempts);
    }

    public static Alarm jobDeadLetter(String type, UUID jobId, int attempt, String errorCode) {
        return of(Name.JOB_DEAD_LETTER, "type=" + type, "jobId=" + jobId, "attempt=" + attempt,
                "errorCode=" + errorCode);
    }

    public static Alarm deletionReceiptExpiredUnfinished(String status, int attempt) {
        return of(Name.DELETION_RECEIPT_EXPIRED_UNFINISHED, "status=" + status, "attempt=" + attempt);
    }

    /** Logs the line at its name's level. Callers emit only after the state it reports has committed. */
    public static void emit(Alarm alarm) {
        Objects.requireNonNull(alarm, "alarm");
        if (alarm.name().level() == Level.ERROR) {
            log.error("{}", alarm.line());
        } else {
            log.warn("{}", alarm.line());
        }
    }

    private static Alarm of(Name name, String... fields) {
        return new Alarm(name, name.phrase() + " " + String.join(" ", fields));
    }
}
