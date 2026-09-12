package io.nullnull.trip.api;

import io.nullnull.shared.problem.FieldError;
import io.nullnull.shared.problem.Problem;
import io.nullnull.shared.problem.ProblemCode;
import io.nullnull.shared.problem.ProblemResponses;
import io.nullnull.trip.domain.TripValidationException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The public mapping of the trip module's own failures. It lives here rather than in
 * {@code io.nullnull.shared.problem} because {@code shared} may not depend on a module, and
 * {@code trip.domain} may not depend on Spring - so the pure exception carries field names and this
 * advice is where they become a Problem.
 *
 * <p>Ordered first for the same reason the identity advice is: {@code GlobalExceptionHandler} has a
 * catch-all, and without this a rejected date range would come back as an unexplained 500.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TripProblemHandler {

    /**
     * A trip input the domain refused. 422, not 400: the request parsed and its types were right, and
     * what failed is a domain constraint - the date range, an interest weight, a repeated interest
     * code. The per-field answer travels in {@code fieldErrors}; the {@code detail} stays the generic
     * fallback and never repeats the value that was rejected.
     */
    @ExceptionHandler(TripValidationException.class)
    public ResponseEntity<Problem> handleValidation(TripValidationException exception,
            HttpServletRequest request) {
        List<FieldError> fieldErrors = exception.violations().stream()
                .map(violation -> new FieldError(violation.field(), violation.code(), violation.message()))
                .toList();
        return ProblemResponses.build(request, ProblemCode.VALIDATION_FAILED,
                HttpStatus.UNPROCESSABLE_CONTENT, exception.getMessage(), false, null, fieldErrors);
    }
}
