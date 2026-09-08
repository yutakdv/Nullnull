package io.nullnull.shared.problem;

import io.nullnull.shared.http.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

/** Builds {@code application/problem+json} responses with the request id and instance path. */
public final class ProblemResponses {

    private ProblemResponses() {
    }

    public static ResponseEntity<Problem> build(HttpServletRequest request, ProblemCode code,
            HttpStatus status, String detail, boolean retryable, Integer retryAfterSeconds,
            List<FieldError> fieldErrors) {
        // instance is the REAL request URI, and deliberately not the route template that logs use
        // (io.nullnull.shared.http.RouteTemplate). Two different audiences: this body goes to the one
        // caller that just sent the URL and the contract types instance as a uri-reference, while a log
        // line is kept by an aggregator and may not carry resource identifiers
        // (docs/security/PRIVACY_REQUIREMENTS.md §8).
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

    /**
     * Writes the same body straight onto the response, for a refusal raised in a servlet filter where
     * no {@code @RestControllerAdvice} can run and no {@code ResponseEntity} is returned anywhere.
     * The one caller today is {@link io.nullnull.shared.http.RequestSizeLimitFilter}.
     */
    public static void write(HttpServletRequest request, HttpServletResponse response,
            ObjectMapper json, ProblemCode code, HttpStatus status, String detail) throws IOException {
        Problem problem = Problem.of(code, status.value(), detail, request.getRequestURI(),
                RequestIdFilter.current(request), code.defaultRetryable());
        response.setStatus(status.value());
        response.setContentType(Problem.MEDIA_TYPE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getOutputStream()
                .write(json.writeValueAsString(problem).getBytes(StandardCharsets.UTF_8));
    }
}
