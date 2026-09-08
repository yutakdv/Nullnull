package io.nullnull.shared.problem;

import io.nullnull.shared.http.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps exceptions to Problem responses. Logs carry request id, route and code only; bodies,
 * query strings, cookies and user input are never logged (docs/security/THREAT_MODEL.md).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Problem> handleApi(ApiException exception, HttpServletRequest request) {
        return ProblemResponses.of(request, exception);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Problem> handleNotFound(NoResourceFoundException exception,
            HttpServletRequest request) {
        return ProblemResponses.build(request, ProblemCode.NOT_FOUND, HttpStatus.NOT_FOUND,
                "The requested resource does not exist.", false, null, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Problem> handleMethod(HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request) {
        return ProblemResponses.build(request, ProblemCode.INVALID_REQUEST,
                HttpStatus.METHOD_NOT_ALLOWED, "The HTTP method is not supported for this resource.",
                false, null, null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Problem> handleMediaType(HttpMediaTypeNotSupportedException exception,
            HttpServletRequest request) {
        return ProblemResponses.build(request, ProblemCode.INVALID_REQUEST,
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, "The request media type is not supported.",
                false, null, null);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Problem> handleMalformed(Exception exception, HttpServletRequest request) {
        return ProblemResponses.build(request, ProblemCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST,
                "The request is malformed or contains unsupported fields.", false, null, null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Problem> handleValidation(MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        List<FieldError> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(),
                        error.getCode() == null ? "INVALID" : error.getCode(),
                        error.getDefaultMessage() == null ? "invalid value" : error.getDefaultMessage()))
                .toList();
        return ProblemResponses.build(request, ProblemCode.VALIDATION_FAILED,
                HttpStatus.UNPROCESSABLE_CONTENT, "One or more fields are invalid.", false, null,
                fieldErrors);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Problem> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("unhandled exception requestId={} route={} type={}",
                RequestIdFilter.current(request), request.getRequestURI(),
                exception.getClass().getName(), exception);
        return ProblemResponses.build(request, ProblemCode.INTERNAL_ERROR,
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.", false, null, null);
    }
}
