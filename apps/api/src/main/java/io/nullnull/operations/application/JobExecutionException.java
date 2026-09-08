package io.nullnull.operations.application;

import java.util.regex.Pattern;

/**
 * A handler failure that names a stable error code for {@code background_jobs.last_error_code}.
 *
 * <p>Any other exception is recorded as {@link #DEFAULT_ERROR_CODE}, which is honest but tells an
 * operator nothing beyond "the handler threw". A handler that can distinguish its failure modes
 * (provider quota, schema drift, a missing aggregate) should throw this with a code it also
 * documents in its runbook, so an alert can route on the code instead of on a stack trace.
 *
 * <p>The message is written into logs, so it follows the same rule as a Problem detail: it never
 * echoes user text, a payload value or a secret.
 */
public class JobExecutionException extends RuntimeException {

    /** What an unclassified handler exception is recorded as. */
    public static final String DEFAULT_ERROR_CODE = "HANDLER_ERROR";

    /** Fits {@code last_error_code varchar(64)} and stays greppable in an alert rule. */
    static final Pattern ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private final String errorCode;

    public JobExecutionException(String errorCode, String message) {
        this(errorCode, message, null);
    }

    public JobExecutionException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        if (errorCode == null || !ERROR_CODE.matcher(errorCode).matches()) {
            throw new IllegalArgumentException("job error code must match " + ERROR_CODE.pattern());
        }
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }

    /**
     * The code recorded for a failed attempt: the handler's own when it named one, otherwise
     * {@link #DEFAULT_ERROR_CODE}. One place, so the worker and any future alert rule agree.
     */
    public static String codeOf(Throwable failure) {
        return failure instanceof JobExecutionException named ? named.errorCode() : DEFAULT_ERROR_CODE;
    }
}
