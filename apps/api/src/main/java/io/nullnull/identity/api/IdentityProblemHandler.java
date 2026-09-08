package io.nullnull.identity.api;

import io.nullnull.identity.application.CommandLockTimeoutException;
import io.nullnull.shared.http.RequestIdFilter;
import io.nullnull.shared.http.RouteTemplate;
import io.nullnull.shared.problem.Problem;
import io.nullnull.shared.problem.ProblemCode;
import io.nullnull.shared.problem.ProblemResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The public mapping of the identity module's own failures. It lives here, not in
 * {@code io.nullnull.shared.problem}, because {@code shared} may not depend on a module
 * ({@code ArchitectureRulesTest.sharedPackageStaysTechnicalOnly}) and the module that raises an
 * exception is the one that owns what it means to a caller.
 *
 * <p>Ordered first: {@code GlobalExceptionHandler} carries a catch-all {@code Exception} handler, and
 * Spring stops at the first advice with a match. Without this order the two would race and a mapped
 * failure would sometimes come back as an unexplained 500.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IdentityProblemHandler {

    private static final Logger log = LoggerFactory.getLogger(IdentityProblemHandler.class);

    /**
     * The retry budget in {@code IdempotencyGuard} is exhausted, so a command has held this owner's row
     * for the whole bound more than once. That is a server defect, not something the user can resolve:
     * commands are short and an external call inside the transaction is forbidden, so nothing should
     * hold that row for seconds. It is reported as {@code INTERNAL_ERROR}, not retryable - repeating a
     * request that already waited out the bound twice only lengthens the queue on the same row.
     *
     * <p>The log line is the operator signal: it names the route template and the request id, never the
     * owner and never the key, so a repeated line points at the slow command without identifying whose
     * session hit it.
     */
    @ExceptionHandler(CommandLockTimeoutException.class)
    public ResponseEntity<Problem> handleCommandLockTimeout(CommandLockTimeoutException exception,
            HttpServletRequest request) {
        log.error("owner command lock contention exhausted requestId={} route={}",
                RequestIdFilter.current(request), RouteTemplate.of(request));
        return ProblemResponses.build(request, ProblemCode.INTERNAL_ERROR,
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.", false, null, null);
    }
}
