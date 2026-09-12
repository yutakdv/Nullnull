package io.nullnull.operations;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import io.nullnull.shared.http.RequestBodyTooLargeException;
import io.nullnull.shared.http.RequestIdFilter;
import io.nullnull.shared.http.RouteTemplate;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import nullnull.testsupport.http.HttpPolicyTestEndpoints;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * BA-003 steps 1 and 3: the HTTP policy every request shares - unknown fields refused, the body
 * bounded, one safe Problem shape with a request id, and a log line that carries the route template
 * and nothing a privacy rule forbids.
 *
 * <p>The body bound is set to a non-default value here on purpose. A test that asserts the shipped
 * 262144 cannot tell a wired property from a hardcoded constant.
 */
@SpringBootTest(properties = {
        "nullnull.http.max-request-body-bytes=8192",
        // Out of reach on purpose: the optional recommendation probe must not add latency or log noise.
        "nullnull.ai.base-url=http://127.0.0.1:1"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class,
        HttpPolicyTestEndpoints.class})
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("BA-003 shared HTTP policy")
class HttpPolicyIT {

    private static final int LIMIT_BYTES = 8192;

    /** One distinctive token, planted in every channel a request can carry a secret through. */
    private static final String CANARY = "canary-8f2b1d6c4a09e7b5";

    /** Two blank rows among four, so a collapsed path would report the same field twice. */
    private static final String NESTED_BODY_WITH_TWO_BLANK_ROWS =
            "{\"items\":[{\"name\":\"ok\"},{\"name\":\"  \"},{\"name\":\"ok\"},{\"name\":\"\"}]}";

    /**
     * Eight blank rows, so eight violations come back at once. Bean Validation reports them out of a
     * hash set whose iteration order follows the identity hash of freshly allocated violation objects,
     * so an unsorted handler answers a byte-identical request with a different field order on every
     * request - eight of them makes an accidental agreement across repeats vanishingly unlikely.
     */
    private static final String NESTED_BODY_WITH_EIGHT_BLANK_ROWS =
            "{\"items\":[" + String.join(",", Collections.nCopies(8, "{\"name\":\"\"}")) + "]}";

    /** Repeats of the identical request the stable-order assertion compares. */
    private static final int ORDER_REPEATS = 8;

    @Autowired
    MockMvcTester mvc;

    private ListAppender<ILoggingEvent> everyLogLine;

    @BeforeEach
    void captureEveryLogLine() {
        everyLogLine = new ListAppender<>();
        everyLogLine.start();
        rootLogger().addAppender(everyLogLine);
    }

    @AfterEach
    void detachTheLogAppender() {
        rootLogger().detachAppender(everyLogLine);
        everyLogLine.stop();
    }

    @Test
    @DisplayName("BA-003-T2 a body field outside the schema is refused without repeating the field")
    void anUnknownBodyFieldIsRefused() {
        MvcTestResult result = mvc.post().uri(HttpPolicyTestEndpoints.ECHO)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"seoul\",\"unknownField\":\"x\"}")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).hasContentTypeCompatibleWith("application/problem+json");
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INVALID_REQUEST");
        assertThat(result).bodyJson().extractingPath("$.detail")
                .isEqualTo("The request is malformed or contains unsupported fields.");
        // Jackson's own message names the field and quotes the surrounding JSON; neither may be echoed.
        assertThat(body(result)).doesNotContain("unknownField").doesNotContain("seoul");
    }

    @Test
    @DisplayName("BA-003-T2 a declared Content-Length over the bound is refused before the body is read")
    void aDeclaredContentLengthOverTheBoundIsRefused() {
        String oversized = "{\"value\":\"" + "x".repeat(LIMIT_BYTES) + "\"}";
        assertThat(oversized.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(LIMIT_BYTES);

        MvcTestResult result = mvc.post().uri(HttpPolicyTestEndpoints.ECHO)
                .contentType(MediaType.APPLICATION_JSON).content(oversized).exchange();

        assertThat(result).hasStatus(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(result).hasContentTypeCompatibleWith("application/problem+json");
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INVALID_REQUEST");
        assertThat(result).bodyJson().extractingPath("$.detail")
                .isEqualTo(RequestBodyTooLargeException.DETAIL);
        assertThat(result).bodyJson().extractingPath("$.requestId").asString().isNotBlank();
        assertThat(result.getResponse().getHeader(RequestIdFilter.HEADER)).isNotBlank();
    }

    @Test
    @DisplayName("a body just under the bound is accepted, so the bound refuses size and not JSON")
    void aBodyUnderTheBoundIsAccepted() {
        String accepted = "{\"value\":\"" + "x".repeat(LIMIT_BYTES - 100) + "\"}";
        assertThat(accepted.getBytes(StandardCharsets.UTF_8).length).isLessThan(LIMIT_BYTES);

        MvcTestResult result = mvc.post().uri(HttpPolicyTestEndpoints.ECHO)
                .contentType(MediaType.APPLICATION_JSON).content(accepted).exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.length").isEqualTo(LIMIT_BYTES - 100);
    }

    @Test
    @DisplayName("BA-003-T3 every error path answers with a stable code and a non-blank requestId")
    void everyErrorPathCarriesACodeAndARequestId() {
        List<MvcTestResult> failures = List.of(
                mvc.get().uri("/api/v1/does-not-exist").exchange(),
                mvc.post().uri("/api/v1/health/live").exchange(),
                mvc.post().uri(HttpPolicyTestEndpoints.ECHO).contentType(MediaType.TEXT_PLAIN)
                        .content("plain").exchange(),
                mvc.post().uri(HttpPolicyTestEndpoints.ECHO).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"a\",\"unknownField\":1}").exchange(),
                mvc.post().uri(HttpPolicyTestEndpoints.ECHO).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"" + "x".repeat(LIMIT_BYTES) + "\"}").exchange(),
                mvc.get().uri(HttpPolicyTestEndpoints.CURSOR + "?state=invalid").exchange(),
                mvc.get().uri(HttpPolicyTestEndpoints.CURSOR + "?state=expired").exchange(),
                mvc.get().uri(HttpPolicyTestEndpoints.VALIDATED + "?limit=0").exchange(),
                mvc.get().uri(HttpPolicyTestEndpoints.BOUNDED + "?limit=999").exchange(),
                mvc.post().uri(HttpPolicyTestEndpoints.IF_MATCH).exchange(),
                mvc.get().uri(HttpPolicyTestEndpoints.JSON_ONLY)
                        .accept(MediaType.APPLICATION_XML).exchange(),
                mvc.get().uri(HttpPolicyTestEndpoints.UNEXPECTED + "?value=x").exchange(),
                mvc.post().uri(HttpPolicyTestEndpoints.CONTENDED).exchange());

        assertThat(failures).allSatisfy(failure -> {
            assertThat(failure.getResponse().getStatus()).isGreaterThanOrEqualTo(400);
            assertThat(failure).hasContentTypeCompatibleWith("application/problem+json");
            assertThat(failure).bodyJson().extractingPath("$.code").asString().isNotBlank();
            assertThat(failure).bodyJson().extractingPath("$.requestId").asString().isNotBlank();
            assertThat(failure.getResponse().getHeader(RequestIdFilter.HEADER)).isNotBlank();
            // No stack trace, SQL or exception class ever reaches the caller (docs/api/README.md §9).
            assertThat(body(failure)).doesNotContain("Exception").doesNotContain("io.nullnull");
        });
    }

    @Test
    @DisplayName("BA-003-T3 a canary secret in a header, cookie, query and body reaches no log or body")
    void noCanaryReachesALogLineOrAResponseBody() {
        MvcTestResult refused = mvc.post()
                .uri(HttpPolicyTestEndpoints.ECHO + "?searchQuery=" + CANARY)
                .header("X-Canary", CANARY)
                .cookie(new Cookie("canary", CANARY))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"" + CANARY + "\",\"unknownField\":\"" + CANARY + "\"}")
                .exchange();
        // The same four channels on a path that ends in an ERROR log line, not only a 400.
        MvcTestResult failed = mvc.post()
                .uri(HttpPolicyTestEndpoints.CONTENDED + "?searchQuery=" + CANARY)
                .header("X-Canary", CANARY)
                .cookie(new Cookie("canary", CANARY))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"" + CANARY + "\"}")
                .exchange();

        assertThat(refused).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(failed).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(body(refused)).doesNotContain(CANARY);
        assertThat(body(failed)).doesNotContain(CANARY);
        assertThat(everyLogLine.list).isNotEmpty();
        assertNoCanaryInAnyLogLine();
    }

    @Test
    @DisplayName("BA-003-T3 an unmapped exception is INTERNAL_ERROR logged by route template only")
    void anUnmappedExceptionIsInternalErrorAndLeaksNothing() {
        // The canary travels in as a query value and comes back out in a plain RuntimeException's
        // message, in its cause's message and in a suppressed entry's message - the three channels
        // logback renders from a throwable argument, and the shape of 60 of the 145 throw statements
        // under apps/api/src/main/java (GlobalExceptionHandler.framesOf carries the measurement).
        MvcTestResult result = mvc.get()
                .uri(HttpPolicyTestEndpoints.UNEXPECTED + "?value=" + CANARY)
                .header("X-Canary", CANARY)
                .cookie(new Cookie("canary", CANARY))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(result).hasContentTypeCompatibleWith("application/problem+json");
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INTERNAL_ERROR");
        assertThat(result).bodyJson().extractingPath("$.retryable").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.requestId").asString().isNotBlank();
        assertThat(result.getResponse().getHeader(RequestIdFilter.HEADER)).isNotBlank();
        assertThat(body(result)).doesNotContain(CANARY).doesNotContain("RuntimeException");

        assertThat(everyLogLine.list)
                .filteredOn(event -> event.getFormattedMessage().startsWith("unhandled exception"))
                .as("the catch-all writes exactly one operator line")
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getFormattedMessage())
                            .contains("route=" + HttpPolicyTestEndpoints.UNEXPECTED_TEMPLATE)
                            .contains("type=java.lang.RuntimeException");
                    // The template, never the raw URI this slice replaced it with.
                    assertThat(event.getFormattedMessage()).doesNotContain("/api/v1");
                });
        assertNoCanaryInAnyLogLine();
    }

    @Test
    @DisplayName("an invalid cursor is 400 and an expired one is 410, and neither repeats the cursor")
    void cursorFailuresKeepTheirOwnCodes() {
        MvcTestResult invalid = mvc.get().uri(HttpPolicyTestEndpoints.CURSOR + "?state=invalid")
                .exchange();
        MvcTestResult expired = mvc.get().uri(HttpPolicyTestEndpoints.CURSOR + "?state=expired")
                .exchange();

        assertThat(invalid).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(invalid).bodyJson().extractingPath("$.code").isEqualTo("CURSOR_INVALID");
        assertThat(invalid).bodyJson().extractingPath("$.retryable").isEqualTo(false);
        assertThat(expired).hasStatus(HttpStatus.GONE);
        assertThat(expired).bodyJson().extractingPath("$.code").isEqualTo("CURSOR_EXPIRED");
    }

    @Test
    @DisplayName("BA-003-T2 a service constraint violation is 422 with the parameter name and no value")
    void aServiceConstraintViolationIsUnprocessable() {
        MvcTestResult result = mvc.get().uri(HttpPolicyTestEndpoints.VALIDATED + "?limit=0").exchange();

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.fieldErrors[0].field").isEqualTo("limit");
        assertThat(result).bodyJson().extractingPath("$.fieldErrors[0].code").isEqualTo("Min");
        // Not "countPlaces.limit": the caller may not learn the server method that validated it.
        assertThat(body(result)).doesNotContain("countPlaces");
    }

    @Test
    @DisplayName("BA-003-T2 the contract's own limit bound is 422 with or without @Validated")
    void aControllerParameterConstraintIsUnprocessableToo() {
        // The idiomatic Spring shape - constraints on the controller parameter, no class-level
        // @Validated - raises HandlerMethodValidationException, which used to reach the catch-all and
        // answer the contract's declared maximum with a 500 and a stack trace.
        MvcTestResult overTheMaximum = mvc.get().uri(HttpPolicyTestEndpoints.BOUNDED + "?limit=999")
                .exchange();

        assertThat(overTheMaximum).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(overTheMaximum).hasContentTypeCompatibleWith("application/problem+json");
        assertThat(overTheMaximum).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(overTheMaximum).bodyJson().extractingPath("$.fieldErrors[0].field")
                .isEqualTo("limit");
        assertThat(overTheMaximum).bodyJson().extractingPath("$.fieldErrors[0].code").isEqualTo("Max");
        // The rejected value is request content and is never read back out of the violation, and the
        // leading message codes ("Max.testSupportController#bounded.limit") name the server class.
        // instance legitimately repeats the URI the caller just requested; only the codes are cut.
        assertThat(body(overTheMaximum)).doesNotContain("999")
                .doesNotContain("TestSupportController").doesNotContain("testSupportController");

        MvcTestResult methodValidated = mvc.get().uri(HttpPolicyTestEndpoints.BOUNDED + "?limit=0")
                .exchange();
        MvcTestResult serviceValidated = mvc.get().uri(HttpPolicyTestEndpoints.VALIDATED + "?limit=0")
                .exchange();

        assertThat(methodValidated.getResponse().getStatus())
                .isEqualTo(serviceValidated.getResponse().getStatus());
        assertThat(fieldErrorsOf(methodValidated))
                .as("the same violation must answer with the same body whichever mechanism ran")
                .isEqualTo(fieldErrorsOf(serviceValidated));
    }

    @Test
    @DisplayName("BA-003-T2 a nested indexed violation keeps its row in both 422 producers")
    void aNestedViolationKeepsItsRow() {
        MvcTestResult service = mvc.post().uri(HttpPolicyTestEndpoints.NESTED_SERVICE)
                .contentType(MediaType.APPLICATION_JSON).content(NESTED_BODY_WITH_TWO_BLANK_ROWS)
                .exchange();
        MvcTestResult requestBody = mvc.post().uri(HttpPolicyTestEndpoints.NESTED_BODY)
                .contentType(MediaType.APPLICATION_JSON).content(NESTED_BODY_WITH_TWO_BLANK_ROWS)
                .exchange();

        assertThat(service).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(requestBody).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        // Two different rows are two different fields: a path collapsed to its last node would say
        // "name" twice and the caller could not tell which row to fix.
        assertThat(fieldErrorsOf(service)).extracting(error -> error.get("field"))
                .containsExactly("items[1].name", "items[3].name");
        assertThat(fieldErrorsOf(service))
                .as("both 422 producers must name the same fields for the same failure")
                .isEqualTo(fieldErrorsOf(requestBody));
        // The server method that validated it is still never named.
        assertThat(body(service)).doesNotContain("save").doesNotContain("request.items");
    }

    @Test
    @DisplayName("BA-003-T2 a missing required header is 400, not an unhandled 500")
    void aMissingRequiredHeaderIsRefusedAsABadRequest() {
        // Invariant 6 requires If-Match on every trip mutation and Idempotency-Key on every retryable
        // command, so the first mutation endpoint would otherwise answer an omitted header with a 500.
        MvcTestResult result = mvc.post().uri(HttpPolicyTestEndpoints.IF_MATCH).exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).hasContentTypeCompatibleWith("application/problem+json");
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INVALID_REQUEST");
        assertThat(result).bodyJson().extractingPath("$.detail")
                .isEqualTo("The request is malformed or contains unsupported fields.");
        assertThat(everyLogLine.list)
                .as("a client mistake is not an unhandled server failure")
                .noneMatch(event -> event.getFormattedMessage().startsWith("unhandled exception"));
    }

    /**
     * The other member of the client-caused half of {@code ServletRequestBindingException}. It sits
     * outside {@code MissingRequestValueException}, so a handler declared on that subtype alone misses
     * it and Spring's 400 becomes our 500.
     */
    @Test
    @DisplayName("BA-003-T2 an unmet params condition is 400, not an unhandled 500")
    void anUnmetParamsConditionIsRefusedAsABadRequest() {
        MvcTestResult result = mvc.get().uri(HttpPolicyTestEndpoints.PARAMS_CONDITION).exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INVALID_REQUEST");
        assertThat(everyLogLine.list)
                .as("a client mistake is not an unhandled server failure")
                .noneMatch(event -> event.getFormattedMessage().startsWith("unhandled exception"));
    }

    /**
     * A chain longer than the renderer walks. An unmarked cut is worse than a short trace: the operator
     * reads a truncated chain as the whole failure and stops looking for the real cause.
     */
    @Test
    @DisplayName("BA-003-T3 a cause chain cut short is marked as cut")
    void aTruncatedCauseChainSaysThatItWasTruncated() {
        MvcTestResult result = mvc.get().uri(HttpPolicyTestEndpoints.DEEP_CAUSE).exchange();

        assertThat(result).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(catchAllLine())
                .as("a chain stopped at the depth bound must not read like one that ended")
                .endsWith(" <- +more causes");
    }

    @Test
    @DisplayName("BA-003-T3 a route whose @PathVariable name is a typo is a logged 500, not a 400")
    void aPathVariableNameMismatchStaysAnUnhandledServerFailure() {
        // MissingPathVariableException extends ServletRequestBindingException, so a handler declared on
        // that supertype swallowed this - a route whose @PathVariable name does not match its own URI
        // template - as 400 INVALID_REQUEST with no log line at all. Spring itself classifies it 500.
        MvcTestResult result = mvc.get().uri(HttpPolicyTestEndpoints.PATH_VARIABLE).exchange();

        assertThat(result).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(result).hasContentTypeCompatibleWith("application/problem+json");
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INTERNAL_ERROR");
        assertThat(everyLogLine.list)
                .filteredOn(event -> event.getFormattedMessage().startsWith("unhandled exception"))
                .as("a server coding bug must reach the operator, not be blamed on the caller")
                .singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage())
                        .contains("type=org.springframework.web.bind.MissingPathVariableException")
                        .contains("route=" + HttpPolicyTestEndpoints.PATH_VARIABLE_TEMPLATE));
    }

    @Test
    @DisplayName("BA-003-T3 the catch-all keeps the frames of the failure and of its cause")
    void theCatchAllLogsTheFramesOfTheFailure() {
        // The frames are the other half of the canary rule. Dropping the throwable argument is what
        // keeps every message out of the log; rendering the frames as a string this service builds is
        // what keeps the line diagnosable. A frame is a class, a method, a file and a line - none of
        // them caller input - so PRIVACY_REQUIREMENTS.md §8 holds while the trace survives.
        MvcTestResult result = mvc.get().uri(HttpPolicyTestEndpoints.UNEXPECTED + "?value=x").exchange();

        assertThat(result).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(catchAllLine())
                .as("the throwing class, method, file and line of the route must survive")
                .contains("at=java.lang.RuntimeException|"
                        + "nullnull.testsupport.http.TestSupportController.unexpected"
                        + "(TestSupportController.java:")
                .as("and so must the cause level, which is where a wrapped failure hides")
                .contains(" <- java.lang.IllegalStateException|"
                        + "nullnull.testsupport.http.TestSupportController.unexpected"
                        + "(TestSupportController.java:");
    }

    @Test
    @DisplayName("BA-003-T2 the same multi-violation body answers with the same field order every time")
    void theFieldOrderOfAValidationFailureIsStable() {
        List<List<String>> orders = new ArrayList<>();
        for (int repeat = 0; repeat < ORDER_REPEATS; repeat++) {
            MvcTestResult result = mvc.post().uri(HttpPolicyTestEndpoints.NESTED_BODY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(NESTED_BODY_WITH_EIGHT_BLANK_ROWS).exchange();
            assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            orders.add(fieldsInResponseOrder(result));
        }

        assertThat(orders.getFirst()).hasSize(8);
        assertThat(orders)
                .as("a byte-identical request must render the same list every time")
                .allSatisfy(order -> assertThat(order).containsExactlyElementsOf(orders.getFirst()));

        MvcTestResult service = mvc.post().uri(HttpPolicyTestEndpoints.NESTED_SERVICE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(NESTED_BODY_WITH_EIGHT_BLANK_ROWS).exchange();

        assertThat(service).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(fieldsInResponseOrder(service))
                .as("and the other 422 producer must render it in that same order")
                .isEqualTo(orders.getFirst());
    }

    /**
     * The tie the previous test cannot see. Sorting on (field, code) leaves two violations that agree
     * on both to {@code Stream.sorted}'s stability over Hibernate Validator's hash-set order, which
     * varies between runs, so the comparator has to be a total order over the whole FieldError.
     */
    @Test
    @DisplayName("BA-003-T2 two violations that differ only in message still render in a fixed order")
    void theFieldOrderIsStableEvenWhenFieldAndCodeTie() {
        List<List<String>> orders = new ArrayList<>();
        for (int repeat = 0; repeat < ORDER_REPEATS; repeat++) {
            MvcTestResult result = mvc.post().uri(HttpPolicyTestEndpoints.TIED_BODY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"label\":\"x\"}").exchange();
            assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            orders.add(fieldErrorsInResponseOrder(result));
        }

        // Both rows are field "label", code "Size"; only the message differs.
        assertThat(orders.getFirst()).hasSize(2)
                .allSatisfy(row -> assertThat(row).startsWith("label|Size|"));
        assertThat(orders)
                .as("a (field, code) tie must not leave the order to the validator's iteration")
                .allSatisfy(order -> assertThat(order).containsExactlyElementsOf(orders.getFirst()));
    }

    @Test
    @DisplayName("an Accept header this API cannot satisfy is 406, not an unhandled 500")
    void anUnacceptableAcceptHeaderIsRefusedAsNotAcceptable() {
        MvcTestResult result = mvc.get().uri(HttpPolicyTestEndpoints.JSON_ONLY)
                .accept(MediaType.APPLICATION_XML).exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_ACCEPTABLE);
        assertThat(result).hasContentTypeCompatibleWith("application/problem+json");
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INVALID_REQUEST");
        assertThat(result).bodyJson().extractingPath("$.requestId").asString().isNotBlank();
        assertThat(everyLogLine.list)
                .noneMatch(event -> event.getFormattedMessage().startsWith("unhandled exception"));
    }

    @Test
    @DisplayName("owner command lock contention that survives the retry budget is INTERNAL_ERROR")
    void exhaustedOwnerCommandContentionIsInternalError() {
        MvcTestResult result = mvc.post().uri(HttpPolicyTestEndpoints.CONTENDED).exchange();

        assertThat(result).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INTERNAL_ERROR");
        assertThat(result).bodyJson().extractingPath("$.retryable").isEqualTo(false);
        // The module advice, not the catch-all: the operator signal names the contention.
        assertThat(everyLogLine.list)
                .filteredOn(event -> event.getFormattedMessage()
                        .contains("owner command lock contention exhausted"))
                .singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage())
                        .contains("route=/test-support/contended"));
    }

    @Test
    @DisplayName("the access log carries method, route template, status, duration and requestId only")
    void theAccessLogLineIsTheAllowedFieldsOnly(CapturedOutput console) {
        MvcTestResult result = mvc.post().uri(HttpPolicyTestEndpoints.ECHO + "?searchQuery=" + CANARY)
                .header(RequestIdFilter.HEADER, "req_policy-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"seoul\"}")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        String line = accessLogLines().getLast();
        assertThat(line).isEqualTo("request method=POST route=" + HttpPolicyTestEndpoints.ECHO_TEMPLATE
                + " status=200 durationMs=" + durationOf(line) + " requestId=req_policy-0001");
        // The template, never the URI; and never the query string while include-query is false.
        assertThat(line).doesNotContain("/api/v1").doesNotContain("searchQuery").doesNotContain(CANARY);
        // The MDC pattern is what puts the id on lines that do not name it themselves.
        assertThat(console.getAll()).contains("[nullnull-api,req_policy-0001]");
    }

    @Test
    @DisplayName("a request that matched no handler is logged without its raw URI")
    void anUnmatchedRouteIsLoggedWithoutItsUri() {
        // A collection the contract does not define. It used to be /trips/{id}, which BA-030 now
        // serves - and a route that exists answers 401 without a session, not 404, so it stopped
        // testing the unmatched path at all.
        MvcTestResult result = mvc.get().uri("/api/v1/no-such-collection/11111111-2222-3333-4444-555555555555")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        // instance still returns the real URI to the caller that sent it; only the log is restricted.
        assertThat(result).bodyJson().extractingPath("$.instance")
                .isEqualTo("/api/v1/no-such-collection/11111111-2222-3333-4444-555555555555");
        String line = accessLogLines().getLast();
        assertThat(line).contains("route=" + RouteTemplate.UNMATCHED);
        assertThat(line).doesNotContain("11111111-2222-3333-4444-555555555555");
    }

    /**
     * The canary assertion, over EVERY channel a log event can carry text through.
     *
     * <p>{@code getFormattedMessage()} alone is not enough and was the hole this suite had:
     * {@code Logger.error(format, args..., throwable)} puts the throwable outside the formatted
     * message by contract, so an exception whose message interpolates caller input - the shape of 60
     * of the 145 throw statements in {@code apps/api/src/main/java} - was logged in full while the
     * assertion stayed green.
     */
    private void assertNoCanaryInAnyLogLine() {
        assertThat(everyLogLine.list)
                .as("no log line may carry a header, cookie, query value or body, in its message or"
                        + " in the throwable, cause chain and suppressed entries it logs")
                .allSatisfy(event -> assertThat(everyTextIn(event))
                        .noneMatch(text -> text.contains(CANARY)));
    }

    private static List<String> everyTextIn(ILoggingEvent event) {
        List<String> texts = new ArrayList<>();
        texts.add(event.getFormattedMessage());
        collectThrowableText(event.getThrowableProxy(), texts,
                Collections.newSetFromMap(new IdentityHashMap<>()));
        return texts.stream().filter(java.util.Objects::nonNull).toList();
    }

    /** Walks cause AND suppressed; the identity set stops a self-referencing chain from looping. */
    private static void collectThrowableText(IThrowableProxy throwable, List<String> texts,
            Set<IThrowableProxy> seen) {
        if (throwable == null || !seen.add(throwable)) {
            return;
        }
        texts.add(throwable.getClassName());
        texts.add(throwable.getMessage());
        collectThrowableText(throwable.getCause(), texts, seen);
        IThrowableProxy[] suppressed = throwable.getSuppressed();
        if (suppressed != null) {
            for (IThrowableProxy entry : suppressed) {
                collectThrowableText(entry, texts, seen);
            }
        }
    }

    /** The single catch-all line this request wrote, as the operator reads it. */
    private String catchAllLine() {
        List<String> lines = everyLogLine.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("unhandled exception"))
                .toList();
        assertThat(lines).as("the catch-all writes exactly one operator line").hasSize(1);
        return lines.getFirst();
    }

    /** The fieldErrors in the order the RESPONSE lists them - never re-sorted by the test. */
    private static List<String> fieldsInResponseOrder(MvcTestResult result) {
        return JsonPath.read(body(result), "$.fieldErrors[*].field");
    }

    /**
     * The whole row, not only its field: a comparator that ties on (field, code) is invisible to a
     * comparison of field names alone.
     */
    private static List<String> fieldErrorsInResponseOrder(MvcTestResult result) {
        String json = body(result);
        List<String> fields = JsonPath.read(json, "$.fieldErrors[*].field");
        List<String> codes = JsonPath.read(json, "$.fieldErrors[*].code");
        List<String> messages = JsonPath.read(json, "$.fieldErrors[*].message");
        List<String> rows = new ArrayList<>();
        for (int at = 0; at < fields.size(); at++) {
            rows.add(fields.get(at) + "|" + codes.get(at) + "|" + messages.get(at));
        }
        return rows;
    }

    /** The response's fieldErrors, sorted, so two different producers compare field for field. */
    private static List<Map<String, Object>> fieldErrorsOf(MvcTestResult result) {
        List<Map<String, Object>> errors = JsonPath.read(body(result), "$.fieldErrors");
        return errors.stream()
                .sorted(Comparator
                        .comparing((Map<String, Object> error) -> String.valueOf(error.get("field")))
                        .thenComparing(error -> String.valueOf(error.get("code"))))
                .toList();
    }

    private List<String> accessLogLines() {
        List<String> lines = everyLogLine.list.stream()
                .filter(event -> event.getLoggerName().endsWith("AccessLogFilter"))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        // The appender is cleared per test and every caller of this helper sends exactly one request,
        // so "exactly one" is what the description promises: a duplicate filter registration or an
        // async dispatch writing a second line would otherwise pass while getLast() read the wrong one.
        assertThat(lines).as("every request writes exactly one access log line").hasSize(1);
        return lines;
    }

    /** The measured duration is the only field a test cannot predict; everything else is asserted. */
    private static String durationOf(String line) {
        String marker = "durationMs=";
        int from = line.indexOf(marker) + marker.length();
        return line.substring(from, line.indexOf(' ', from));
    }

    private static ch.qos.logback.classic.Logger rootLogger() {
        return ((LoggerContext) LoggerFactory.getILoggerFactory())
                .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }

    private static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (java.io.UnsupportedEncodingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
