package io.nullnull.shared.problem;

import io.nullnull.shared.http.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Builds {@code application/problem+json} responses with the request id and instance path. */
public final class ProblemResponses {

    private ProblemResponses() {
    }

    public static ResponseEntity<Problem> build(HttpServletRequest request, ProblemCode code,
            HttpStatus status, String detail, boolean retryable, Integer retryAfterSeconds,
            List<FieldError> fieldErrors) {
        String instance = request.getRequestURI();
        Problem problem = Problem.of(code, status.value(), detail, instance,
                RequestIdFilter.current(request), retryable);
        if (fieldErrors != null && !fieldErrors.isEmpty()) {
            problem = problem.withFieldErrors(fieldErrors);
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
                .contentType(MediaType.parseMediaType(Problem.MEDIA_TYPE));
        if (retryAfterSeconds != null) {
            builder.header(HttpHeaders.RETRY_AFTER, Integer.toString(retryAfterSeconds));
        }
        return builder.body(problem);
    }

    public static ResponseEntity<Problem> of(HttpServletRequest request, ApiException exception) {
        return build(request, exception.code(), exception.status(), exception.getMessage(),
                exception.retryable(), exception.retryAfterSeconds(), null);
    }
}
