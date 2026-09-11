package io.nullnull.shared.problem;

import io.nullnull.shared.cursor.CursorException;
import io.nullnull.shared.http.RequestBodyTooLargeException;
import io.nullnull.shared.http.RequestIdFilter;
import io.nullnull.shared.http.RouteTemplate;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingMatrixVariableException;
import org.springframework.web.bind.MissingRequestCookieException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.UnsatisfiedServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps exceptions to Problem responses. Logs carry request id, route template and code only; bodies,
 * query strings, cookies and user input are never logged (docs/security/THREAT_MODEL.md,
 * docs/security/PRIVACY_REQUIREMENTS.md §8).
 *
 * <p>Ordered LAST on purpose. This advice owns a catch-all {@code Exception} handler, and Spring picks
 * the first advice whose resolver has a match: without an explicit order, a module advice that maps
 * its own exception (for example {@code io.nullnull.identity.api.IdentityProblemHandler}) would win or
 * lose the race at random, and losing it turns every mapped module failure back into a 500.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Frames kept per level by {@link #framesOf}; see that method for how it was derived. */
    private static final int FRAMES_PER_LEVEL = 16;

    /** Cause links walked by {@link #framesOf}; see that method for how it was derived. */
    private static final int CAUSE_DEPTH = 5;

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

    /**
     * No representation this API can produce satisfies the caller's {@code Accept} header. Without
     * this mapping the negotiation failure reached the catch-all and answered 500 with a stack trace
     * in the log, for a request the caller can fix by asking for {@code application/json}.
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<Problem> handleNotAcceptable(HttpMediaTypeNotAcceptableException exception,
            HttpServletRequest request) {
        return ProblemResponses.build(request, ProblemCode.INVALID_REQUEST, HttpStatus.NOT_ACCEPTABLE,
                "The requested representation is not available.", false, null, null);
    }

    /**
     * A required header, cookie or matrix variable the route declares was not sent, or the request did
     * not satisfy a {@code params} condition. Invariant 6 makes this a live path rather than a
     * hypothetical one - every trip mutation requires {@code If-Match} and every retryable command an
     * {@code Idempotency-Key} - and an omitted header reaching the catch-all would answer 500 and log a
     * stack trace for an ordinary client mistake.
     *
     * <p><strong>Why the types are named and not their supertype.</strong> The obvious
     * declaration is {@code ServletRequestBindingException}, or its subtype
     * {@code MissingRequestValueException}. Both are too broad, because
     * {@code MissingPathVariableException} sits under them and is NOT a client mistake: Spring itself
     * answers it 500 ({@code MissingPathVariableException.getStatusCode()} returns
     * {@code INTERNAL_SERVER_ERROR} unless the value was lost after conversion), and it is raised by a
     * route whose {@code @PathVariable} name does not match its own URI template - the typo invariant
     * 6's nested trip routes invite. Catching it here would answer a server coding bug with 400 and no
     * log line at all. It is left to the catch-all, which answers 500 and writes the operator line.
     *
     * <p>The listed set is the complete client-caused half of that hierarchy, enumerated from
     * spring-web 7.0.9 rather than from memory: {@code ServletRequestBindingException} has exactly the
     * subtypes {@code MissingRequestValueException} (itself the parent of the matrix-variable,
     * path-variable, cookie, header and parameter cases) and
     * {@code UnsatisfiedServletRequestParameterException}. Every one of them answers
     * {@code BAD_REQUEST} from {@code getStatusCode()} except {@code MissingPathVariableException},
     * which is why that one - and only that one - is left out. {@code MissingServletRequestPartException}
     * is NOT in this hierarchy; it extends {@code jakarta.servlet.ServletException} directly, so the
     * first route taking a {@code @RequestPart} has to map it separately.
     *
     * <p>{@code MissingServletRequestParameterException} is a sibling and is deliberately not listed:
     * it is declared on {@link #handleMalformed} instead, and a type may be declared on only one
     * handler in an advice. Both answer 400 {@code INVALID_REQUEST} with the same fixed sentence, so
     * no caller can tell which one ran.
     */
    @ExceptionHandler({MissingRequestHeaderException.class, MissingRequestCookieException.class,
            MissingMatrixVariableException.class, UnsatisfiedServletRequestParameterException.class})
    public ResponseEntity<Problem> handleMissingRequestValue(ServletRequestBindingException exception,
            HttpServletRequest request) {
        // The fixed sentence again: the exception message names the missing header, and while a header
        // NAME is a contract constant rather than user input, every refusal of caller input reads the
        // same so no message can start leaking what was sent.
        return ProblemResponses.build(request, ProblemCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST,
                "The request is malformed or contains unsupported fields.", false, null, null);
    }

    /**
     * Malformed JSON, a missing or unparsable parameter, and - since
     * {@code spring.jackson.deserialization.fail-on-unknown-properties} is on - a body carrying a field
     * the schema does not declare. The detail is the same fixed sentence for all of them: Jackson's own
     * message names the offending field AND quotes the surrounding JSON, which is request content and
     * may not be echoed.
     */
    @ExceptionHandler({HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Problem> handleMalformed(Exception exception, HttpServletRequest request) {
        if (bodyTooLarge(exception)) {
            // The body passed the bound while it was streaming: a size refusal, not a parse failure.
            return tooLarge(request);
        }
        return ProblemResponses.build(request, ProblemCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST,
                "The request is malformed or contains unsupported fields.", false, null, null);
    }

    /** The declared-length half is refused in the filter; this is the streamed half. */
    @ExceptionHandler(RequestBodyTooLargeException.class)
    public ResponseEntity<Problem> handleBodyTooLarge(RequestBodyTooLargeException exception,
            HttpServletRequest request) {
        return tooLarge(request);
    }

    /**
     * The {@code @Valid @RequestBody} path, which every trip-mutation body will take.
     *
     * <p>Sorted for the same reason {@link #handleConstraintViolation} and
     * {@link #handleParameterValidation} are: Hibernate Validator reports violations out of a hash
     * set, so the same body posted twice produced the same fields in a different order and the
     * frontend rendered a list that reshuffled itself. All three producers now emit one order.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Problem> handleValidation(MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        List<FieldError> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(),
                        error.getCode() == null ? "INVALID" : error.getCode(),
                        error.getDefaultMessage() == null ? "invalid value" : error.getDefaultMessage()))
                .sorted(Comparator.comparing(FieldError::field).thenComparing(FieldError::code)
                        .thenComparing(FieldError::message))
                .toList();
        return ProblemResponses.build(request, ProblemCode.VALIDATION_FAILED,
                HttpStatus.UNPROCESSABLE_CONTENT, "One or more fields are invalid.", false, null,
                fieldErrors);
    }

    /**
     * Bean Validation on a {@code @Validated} application service, which reports the same class of
     * failure as {@link MethodArgumentNotValidException} and therefore gets the same 422 shape.
     *
     * <p>Two things are deliberate. The exposed field is the property path with only its LEADING
     * server-side nodes removed (see {@link #fieldOf}), so the caller sees {@code limit} and
     * {@code items[3].name} rather than the internal {@code countPlaces.limit} that names a server
     * method - and two violations on different rows stay two distinct fields. And the message is the
     * constraint's own text ("must be greater than or equal to 1"); the rejected VALUE is never read,
     * because it is request content.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Problem> handleConstraintViolation(ConstraintViolationException exception,
            HttpServletRequest request) {
        List<FieldError> fieldErrors = exception.getConstraintViolations().stream()
                .map(violation -> new FieldError(fieldOf(violation.getPropertyPath()),
                        constraintCodeOf(violation),
                        violation.getMessage() == null ? "invalid value" : violation.getMessage()))
                .sorted(Comparator.comparing(FieldError::field).thenComparing(FieldError::code)
                        .thenComparing(FieldError::message))
                .toList();
        return ProblemResponses.build(request, ProblemCode.VALIDATION_FAILED,
                HttpStatus.UNPROCESSABLE_CONTENT, "One or more fields are invalid.", false, null,
                fieldErrors);
    }

    /**
     * Bean Validation on a CONTROLLER parameter: {@code @RequestParam @Min(1) @Max(50) int limit} on a
     * controller with no class-level {@code @Validated}, which is how Spring's own built-in method
     * validation is written. It raises this instead of {@link ConstraintViolationException}, so
     * without this handler the contract's own declared bound (docs/api/openapi.yaml types
     * {@code limit} minimum 1, maximum 50) answered {@code ?limit=999} with 500 and a stack trace
     * while the {@code @Validated} shape answered 422. Both shapes are the same failure to a caller
     * and now return the same 422 body, field for field.
     *
     * <p>The two privacy rules of {@link #handleConstraintViolation} hold here too. The exposed field
     * is the parameter name the caller sent, never the server method that declared it; and the
     * rejected VALUE is never read - {@code ParameterValidationResult.getArgument()} is request
     * content, and so are the leading entries of each error's code list, which embed the object name.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Problem> handleParameterValidation(
            HandlerMethodValidationException exception, HttpServletRequest request) {
        List<FieldError> fieldErrors = new ArrayList<>();
        for (ParameterValidationResult result : exception.getParameterValidationResults()) {
            String parameter = result.getMethodParameter().getParameterName();
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                fieldErrors.add(new FieldError(parameter == null ? "request" : parameter,
                        constraintCodeOf(error),
                        error.getDefaultMessage() == null ? "invalid value"
                                : error.getDefaultMessage()));
            }
        }
        return ProblemResponses.build(request, ProblemCode.VALIDATION_FAILED,
                HttpStatus.UNPROCESSABLE_CONTENT, "One or more fields are invalid.", false, null,
                fieldErrors.stream()
                        .sorted(Comparator.comparing(FieldError::field)
                                .thenComparing(FieldError::code)
                                .thenComparing(FieldError::message))
                        .toList());
    }

    /**
     * The cursor already carries its own outcome: {@code CURSOR_INVALID} (400, tampered, foreign or
     * unreadable) or {@code CURSOR_EXPIRED} (410). Both mean the same thing to the client - start from
     * the first page - and neither response repeats the cursor it rejected.
     */
    @ExceptionHandler(CursorException.class)
    public ResponseEntity<Problem> handleCursor(CursorException exception,
            HttpServletRequest request) {
        ProblemCode code = exception.problem();
        String detail = code == ProblemCode.CURSOR_EXPIRED
                ? "The page cursor has expired; request the first page again."
                : "The page cursor is not valid; request the first page again.";
        return ProblemResponses.build(request, code, code.defaultStatus(), detail, false, null, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Problem> handleUnexpected(Exception exception, HttpServletRequest request) {
        // route, not request.getRequestURI(): the raw URI carries resource identifiers, which a log
        // may not keep (docs/security/PRIVACY_REQUIREMENTS.md §8). The Problem body still returns the
        // real URI as instance, to the caller that sent it.
        //
        // The exception object is NOT passed as the trailing argument, and the frames are passed as a
        // string this class built instead. See framesOf: the throwable argument would render every
        // message in the chain, the string renders no message at all.
        log.error("unhandled exception requestId={} route={} type={} at={}",
                RequestIdFilter.current(request), RouteTemplate.of(request),
                exception.getClass().getName(), framesOf(exception));
        return ProblemResponses.build(request, ProblemCode.INTERNAL_ERROR,
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.", false, null, null);
    }

    private static ResponseEntity<Problem> tooLarge(HttpServletRequest request) {
        return ProblemResponses.build(request, ProblemCode.INVALID_REQUEST,
                HttpStatus.CONTENT_TOO_LARGE, RequestBodyTooLargeException.DETAIL, false, null, null);
    }

    /**
     * The frames of a failure, rendered as a string this class builds itself, carrying NO message
     * anywhere in it.
     *
     * <p>{@code getMessage()}, {@code getLocalizedMessage()} and {@code toString()} are never called
     * on a throwable here, and the throwable is never handed to the logger as the trailing argument -
     * logback renders the message of the exception, of every cause and of every suppressed entry when
     * it is. 60 of the 145 {@code throw new} statements under {@code apps/api/src/main/java}
     * interpolate a value into their message, measured 2026-09-08 with
     * {@code perl -0777 -ne 'while(/throw\s+new\s[^;]*;/gs)}
     * {@code {$t++;$i++ if $&=~/"\s*\+|\+\s*"/} END{print "$i of $t\n"}'} over that tree
     * ({@code io.nullnull.shared.ids.UuidV7}: {@code "not a UUIDv7: " + uuid}). Any one of those
     * values can be caller input, so the message is the channel that leaks by default and that a test
     * reading only the formatted message cannot see.
     *
     * <p>A FRAME is not that channel. It is a declaring class, a method, a file and a line - facts
     * about this service and the libraries it runs on, never anything a caller sent - so keeping the
     * frames satisfies docs/security/PRIVACY_REQUIREMENTS.md §8 while a 500 stays diagnosable. Giving
     * them up leaves an operator a type name and nowhere to look, which is the wrong trade for a
     * service about to grow a job runtime and external providers.
     *
     * <p>Both bounds are measured on this service rather than chosen by feel (integrationTest run of
     * {@code HttpPolicyIT}, 2026-09-08; rendering everything produced a 29 KB line per 500).
     * {@code FRAMES_PER_LEVEL} is 16 because the frames from the throw site down to
     * {@code DispatcherServlet.doDispatch} - everything that says WHERE the request failed - measured
     * 10 on the controller path and 11 on the argument-resolution path, and below that every request
     * repeats the same filter chain and container frames. {@code CAUSE_DEPTH} is 5 because the
     * deepest chain this suite produces is 2, and 5 still renders a doubly-wrapped infrastructure
     * failure whole; the two together bring that same 29 KB line to 3.2 KB for the throwables this
     * suite produces - the bounds themselves permit about 8 KB. Both cuts are reported, a frame cut as
     * {@code |+N frames} and a chain cut as {@code <- +more causes}, so a truncated trace never reads
     * as a complete one.
     *
     * <p>The identity set guards the self-referencing chain: {@code Throwable.initCause} refuses one,
     * but an override of {@code getCause()} can still return {@code this}.
     */
    private static String framesOf(Throwable failure) {
        StringBuilder frames = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable level = failure;
        for (int depth = 0; level != null && depth < CAUSE_DEPTH && seen.add(level); depth++) {
            if (depth > 0) {
                frames.append(" <- ");
            }
            frames.append(level.getClass().getName());
            StackTraceElement[] trace = level.getStackTrace();
            int kept = Math.min(trace.length, FRAMES_PER_LEVEL);
            for (int at = 0; at < kept; at++) {
                StackTraceElement frame = trace[at];
                frames.append('|').append(frame.getClassName()).append('.')
                        .append(frame.getMethodName()).append('(').append(frame.getFileName())
                        .append(':').append(frame.getLineNumber()).append(')');
            }
            if (trace.length > kept) {
                frames.append("|+").append(trace.length - kept).append(" frames");
            }
            level = level.getCause();
        }
        // Both cuts are marked. Without this, a chain stopped at CAUSE_DEPTH renders exactly like one
        // that ended on its own, and an operator reads a truncated trace as a complete one. The two
        // cases are told apart by the identity set: the depth clause short-circuits before add() runs,
        // so a level that was never added is the unrendered rest of the chain rather than a cycle.
        if (level != null) {
            frames.append(seen.contains(level) ? " <- (cycle)" : " <- +more causes");
        }
        return frames.toString();
    }

    /** Walks the cause chain: the stream failure arrives wrapped, and by more than one wrapper type. */
    private static boolean bodyTooLarge(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof RequestBodyTooLargeException) {
                return true;
            }
            if (cause.getCause() == cause) {
                return false;
            }
        }
        return false;
    }

    /**
     * The caller-facing field for one constraint violation, in the SAME shape
     * {@link #handleValidation} produces for {@code @Valid @RequestBody}: {@code items[3].name}, not
     * {@code name}. Only the leading nodes that name the server side are dropped - the method node
     * ({@code countPlaces}), and the parameter node ({@code arg0}) when a nested property follows it,
     * because on a plain parameter constraint that node IS the field the caller sent.
     *
     * <p>Each remaining node is rendered by its own {@code toString()} rather than by
     * {@code getName()}: Bean Validation puts the collection index on the node AFTER the collection
     * property, so composing names by hand would turn {@code items[3].name} into
     * {@code items.name[3]}, while node rendering reproduces the canonical path exactly.
     */
    private static String fieldOf(Path propertyPath) {
        List<Path.Node> nodes = new ArrayList<>();
        propertyPath.forEach(nodes::add);
        int from = 0;
        if (from < nodes.size() && nodes.get(from).getKind() == ElementKind.METHOD) {
            from++;
        }
        if (from + 1 < nodes.size() && nodes.get(from).getKind() == ElementKind.PARAMETER) {
            from++;
        }
        StringBuilder field = new StringBuilder();
        for (int at = from; at < nodes.size(); at++) {
            String node = nodes.get(at).toString();
            if (node == null || node.isEmpty()) {
                continue;
            }
            if (!field.isEmpty()) {
                field.append('.');
            }
            field.append(node);
        }
        return field.isEmpty() ? "request" : field.toString();
    }

    /**
     * The constraint annotation's simple name, which Spring puts LAST in a method-validation error's
     * code list ({@code Min.<object>.<param>}, {@code Min.<param>}, {@code Min.<type>}, {@code Min}).
     * Only the last entry is read: the earlier ones embed the object name, which names the method.
     */
    private static String constraintCodeOf(MessageSourceResolvable error) {
        String[] codes = error.getCodes();
        return codes == null || codes.length == 0 ? "INVALID" : codes[codes.length - 1];
    }

    private static String constraintCodeOf(ConstraintViolation<?> violation) {
        if (violation.getConstraintDescriptor() == null
                || violation.getConstraintDescriptor().getAnnotation() == null) {
            return "INVALID";
        }
        return violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
    }
}
