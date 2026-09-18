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
    private final MissingCredential missingCredential;

    public ApiException(ProblemCode code, String detail) {
        this(code, code.defaultStatus(), detail, code.defaultRetryable(), null);
    }

    public ApiException(ProblemCode code, HttpStatus status, String detail, boolean retryable,
            Integer retryAfterSeconds) {
        this(code, status, detail, retryable, retryAfterSeconds, java.util.List.of(), null);
    }

    public ApiException(ProblemCode code, String detail, java.util.List<FieldError> errors) {
        this(code, code.defaultStatus(), detail, code.defaultRetryable(), null, errors, null);
    }

    /**
     * The one 401 that says why: the request carried no session cookie at all. Only the session interceptor may
     * raise it (ArchitectureRulesTest pins the single caller) - a second producer would widen the bit from "no
     * cookie was sent" to "this cookie is no longer good", which is what every other failure must not reveal.
     */
    public static ApiException missingSessionCookie(String detail) {
        return new ApiException(ProblemCode.UNAUTHORIZED, ProblemCode.UNAUTHORIZED.defaultStatus(), detail,
                ProblemCode.UNAUTHORIZED.defaultRetryable(), null, java.util.List.of(),
                MissingCredential.SESSION_COOKIE);
    }

    private ApiException(ProblemCode code, HttpStatus status, String detail, boolean retryable,
            Integer retryAfterSeconds, java.util.List<FieldError> errors, MissingCredential missingCredential) {
        super(Objects.requireNonNull(detail, "detail"));
        this.fieldErrors = java.util.List.copyOf(errors);
        this.code = Objects.requireNonNull(code, "code");
        this.status = Objects.requireNonNull(status, "status");
        this.retryable = retryable;
        this.retryAfterSeconds = retryAfterSeconds;
        this.missingCredential = missingCredential;
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

    public MissingCredential missingCredential() {
        return missingCredential;
    }
}
