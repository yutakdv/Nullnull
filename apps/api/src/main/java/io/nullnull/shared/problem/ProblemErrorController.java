package io.nullnull.shared.problem;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Errors raised outside controller handling (filters, container) still end as Problem JSON
 * instead of the default error body. No exception message is echoed.
 */
@RestController
public class ProblemErrorController implements ErrorController {

    @RequestMapping("${server.error.path:${error.path:/error}}")
    public ResponseEntity<Problem> error(HttpServletRequest request) {
        Object statusAttribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int statusCode = statusAttribute instanceof Integer value ? value : 500;
        HttpStatus status = HttpStatus.resolve(statusCode);
        if (status == null || !status.isError()) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        ProblemCode code = switch (status) {
            case NOT_FOUND -> ProblemCode.NOT_FOUND;
            case UNAUTHORIZED -> ProblemCode.UNAUTHORIZED;
            case FORBIDDEN -> ProblemCode.FORBIDDEN;
            case BAD_REQUEST, METHOD_NOT_ALLOWED, UNSUPPORTED_MEDIA_TYPE, PAYLOAD_TOO_LARGE
                    -> ProblemCode.INVALID_REQUEST;
            case SERVICE_UNAVAILABLE -> ProblemCode.SOURCE_UNAVAILABLE;
            default -> ProblemCode.INTERNAL_ERROR;
        };
        Object originalUri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String instance = originalUri instanceof String uri ? uri : request.getRequestURI();
        Problem problem = Problem.of(code, status.value(), "The request could not be completed.",
                instance, io.nullnull.shared.http.RequestIdFilter.current(request),
                code.defaultRetryable());
        return ResponseEntity.status(status)
                .contentType(org.springframework.http.MediaType.parseMediaType(Problem.MEDIA_TYPE))
                .body(problem);
    }
}
