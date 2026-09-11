package io.nullnull.shared.problem;

import java.util.Objects;
import org.springframework.http.HttpStatus;

/**
 * Thrown by application/api code to produce a Problem response. The detail is user-safe text:
 * never a stack trace, SQL, provider body, secret or user input.
 */
public class ApiException extends RuntimeException {

    private final java.util.List<FieldError> fieldErrors;
    private final ProblemCode code;
    private final HttpStatus status;
    private final boolean retryable;
    private final Integer retryAfterSeconds;

    public ApiException(ProblemCode code, String detail) {
        this(code, code.defaultStatus(), detail, code.defaultRetryable(), null);
    }

    public ApiException(ProblemCode code, HttpStatus status, String detail, boolean retryable,
            Integer retryAfterSeconds) {
        this(code, status, detail, retryable, retryAfterSeconds, java.util.List.of());
    }

    public ApiException(ProblemCode code, String detail, java.util.List<FieldError> errors) {
        this(code, code.defaultStatus(), detail, code.defaultRetryable(), null, errors);
    }

    private ApiException(ProblemCode code, HttpStatus status, String detail, boolean retryable,
            Integer retryAfterSeconds, java.util.List<FieldError> errors) {
        super(Objects.requireNonNull(detail, "detail"));
        this.fieldErrors = java.util.List.copyOf(errors);
        this.code = Objects.requireNonNull(code, "code");
        this.status = Objects.requireNonNull(status, "status");
        this.retryable = retryable;
        this.retryAfterSeconds = retryAfterSeconds;
        if (!status.isError()) {
            throw new IllegalArgumentException("ApiException requires an error status: " + status);
        }
    }

    public java.util.List<FieldError> fieldErrors() { return fieldErrors; }

    public ProblemCode code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public boolean retryable() {
        return retryable;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
