package nullnull.testsupport.http;

import io.nullnull.identity.application.CommandLockTimeoutException;
import io.nullnull.shared.cursor.CursorException;
import io.nullnull.shared.problem.ProblemCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The routes {@link HttpPolicyTestEndpoints} registers. See that class for why this one lives outside
 * {@code io.nullnull} and outside the configuration class.
 */
@RestController
@RequestMapping("/test-support")
public class TestSupportController {

    private final ValidatedTestService validatedTestService;

    TestSupportController(ValidatedTestService validatedTestService) {
        this.validatedTestService = validatedTestService;
    }

    /** A closed request schema: any other field is refused, like every contract request schema. */
    public record EchoRequest(String value) {
    }

    public record EchoResponse(int length) {
    }

    @PostMapping("/echo")
    public EchoResponse echo(@RequestBody EchoRequest request) {
        // Returns a length, never the text: an echo of the body would defeat the canary assertion.
        return new EchoResponse(request.value() == null ? 0 : request.value().length());
    }

    @GetMapping("/cursor")
    public String cursor(@RequestParam String state) {
        throw new CursorException("expired".equals(state)
                ? ProblemCode.CURSOR_EXPIRED
                : ProblemCode.CURSOR_INVALID);
    }

    @GetMapping("/validated")
    public int validated(@RequestParam int limit) {
        return validatedTestService.countPlaces(limit);
    }

    @PostMapping("/contended")
    public String contended() {
        throw new CommandLockTimeoutException("Timed out waiting for a row lock; another command for "
                + "this owner is still in flight.", null);
    }

    /**
     * An exception nothing maps, carrying caller input in its message, in its CAUSE's message and in
     * a SUPPRESSED entry's message - the three channels logback renders when a throwable is passed as
     * the trailing log argument. That is not a contrived shape: 60 of the 145 {@code throw new}
     * statements in {@code apps/api/src/main/java} interpolate a value into their message (for example
     * {@code io.nullnull.shared.ids.UuidV7}, {@code "not a UUIDv7: " + uuid}), and the catch-all is
     * where every one of them lands. The route exists because {@code handleUnexpected} is the only
     * handler for an unmapped failure and no other test reaches it.
     *
     * <p>The {@code throw} is in this method on purpose: the top stack frame is then
     * {@code TestSupportController.unexpected}, which is what
     * {@code HttpPolicyIT.theCatchAllLogsTheFramesOfTheFailure} reads back out of the log line.
     */
    @GetMapping("/unexpected")
    public String unexpected(@RequestParam String value) {
        RuntimeException failure = new RuntimeException("unexpected failure while handling value: "
                + value, new IllegalStateException("cause carrying value: " + value));
        failure.addSuppressed(new IllegalArgumentException("suppressed carrying value: " + value));
        throw failure;
    }

    /**
     * A route whose {@code @PathVariable} name does not match its own URI template - the typo
     * invariant 6's nested trip routes invite ({@code /trips/{tripId}/items/{itemId}}). It raises
     * {@code MissingPathVariableException}, which Spring itself classifies as 500, and it must reach
     * the catch-all rather than any 400 handler: answering a server coding bug with
     * {@code INVALID_REQUEST} and no log line hides it from the operator and blames the caller.
     */
    @GetMapping("/path-variable/{tripId}")
    public String pathVariable(@PathVariable("itemId") String itemId) {
        return itemId;
    }

    /**
     * The bound the contract declares for {@code limit} (minimum 1, maximum 50 in
     * docs/api/openapi.yaml), written the idiomatic Spring way: constraints on the controller
     * parameter, with NO class-level {@code @Validated}. Spring's built-in method validation raises
     * {@code HandlerMethodValidationException} here, which is a different exception from the one
     * {@link #validated} produces through the {@code @Validated} service - and the same failure to a
     * caller, so both must answer 422 with the same body.
     */
    @GetMapping("/bounded")
    public int bounded(@RequestParam @Min(1) @Max(50) int limit) {
        return limit;
    }

    /**
     * A required header, as invariant 6 requires on every trip mutation ({@code If-Match}) and every
     * retryable command ({@code Idempotency-Key}). Omitting it must be a 400, not a 500.
     */
    @PostMapping("/if-match")
    public EchoResponse ifMatch(@RequestHeader("If-Match") String etag) {
        // The header value is never returned: only its presence matters to this route.
        return new EchoResponse(0);
    }

    /** Produces JSON only, so a caller that accepts something else gets 406 rather than 500. */
    @GetMapping(value = "/json-only", produces = MediaType.APPLICATION_JSON_VALUE)
    public EchoResponse jsonOnly() {
        return new EchoResponse(0);
    }

    /** The nested/indexed violation as the {@code @Validated} service reports it. */
    @PostMapping("/nested-service")
    public int nestedService(@RequestBody ValidatedTestService.Places request) {
        return validatedTestService.save(request);
    }

    /** The same nested/indexed violation as {@code @Valid @RequestBody} reports it. */
    @PostMapping("/nested-body")
    public int nestedBody(@Valid @RequestBody ValidatedTestService.Places request) {
        return request.items().size();
    }

    /**
     * Two violations that agree on BOTH field and code, so only the message tells them apart. Sorting
     * on (field, code) alone leaves their order to Hibernate Validator's hash-set iteration, which is
     * not stable across runs; this is the shape that proves the comparator is a total order.
     */
    @PostMapping("/tied-body")
    public int tiedBody(@Valid @RequestBody TiedRequest request) {
        return request.label().length();
    }

    public record TiedRequest(
            @Size(min = 3, message = "must be at least three characters")
            @Size(min = 5, message = "must be at least five characters") String label) {
    }

    /**
     * A {@code params} condition, which raises {@code UnsatisfiedServletRequestParameterException} when
     * it is not met. That type is the one {@code ServletRequestBindingException} subtype outside
     * {@code MissingRequestValueException}; Spring classifies it 400, so it must not reach the
     * catch-all. Nothing in {@code apps/api/src/main} declares {@code params} yet, so this route is the
     * only thing that keeps the mapping honest until one does.
     */
    @GetMapping(value = "/params-condition", params = "mode=fast")
    public EchoResponse paramsCondition() {
        return new EchoResponse(0);
    }

    /**
     * A cause chain deeper than {@code GlobalExceptionHandler.CAUSE_DEPTH}, so the renderer has to cut
     * it. A cut that is not marked reads exactly like a chain that ended on its own.
     */
    @GetMapping("/deep-cause")
    public String deepCause() {
        RuntimeException failure = new RuntimeException("level 0");
        for (int level = 1; level <= 9; level++) {
            failure = new RuntimeException("level " + level, failure);
        }
        throw failure;
    }
}
