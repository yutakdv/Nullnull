package io.nullnull.shared.problem;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Objects;

/**
 * RFC 9457 Problem Details with the stable Nullnull {@code code}. Field set and nullability follow
 * {@code Problem} in docs/api/openapi.yaml: the first eight fields are required, the optional
 * fields are omitted from JSON when null so that {@code additionalProperties: false} and the
 * non-nullable {@code fieldErrors} array type both hold.
 */
public record Problem(
        String type,
        String title,
        int status,
        ProblemCode code,
        String detail,
        String instance,
        String requestId,
        boolean retryable,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<FieldError> fieldErrors,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long currentTripVersion,
        @JsonInclude(JsonInclude.Include.NON_NULL) String recomputeUrl) {

    public static final String MEDIA_TYPE = "application/problem+json";

    public Problem {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(requestId, "requestId");
        if (status < 400 || status > 599) {
            throw new IllegalArgumentException("problem status must be 4xx or 5xx: " + status);
        }
        fieldErrors = fieldErrors == null ? null : List.copyOf(fieldErrors);
    }

    public static Problem of(ProblemCode code, int status, String detail, String instance,
            String requestId, boolean retryable) {
        return new Problem(code.typeReference(), code.title(), status, code, detail, instance,
                requestId, retryable, null, null, null);
    }

    public Problem withFieldErrors(List<FieldError> errors) {
        return new Problem(type, title, status, code, detail, instance, requestId, retryable,
                errors, currentTripVersion, recomputeUrl);
    }
}
