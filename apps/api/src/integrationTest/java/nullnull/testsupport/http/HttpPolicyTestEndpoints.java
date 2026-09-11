package nullnull.testsupport.http;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Test-only routes for the cross-cutting HTTP policy of BA-003. The policy is a property of every
 * request - unknown fields, body bounds, Problem mapping, the access log - and B01 ships no endpoint
 * that takes a request body, so there is nothing real to send one to yet. These routes exist only in
 * the integration suite: no production controller is invented for them, and
 * {@code ImplementedOperationsRegistry} still lists only the operations the contract declares.
 *
 * <p>Two placement rules make that "only where imported" true, and each one alone is not enough.
 * The package is OUTSIDE {@code io.nullnull}, the application's component scan root, so the
 * {@code @RestController} is not picked up by the scan and does not appear in every other integration
 * context. And {@link TestSupportController} is a top-level class rather than a member of this one,
 * because Spring also processes the nested classes of a configuration class as configuration
 * candidates: a nested {@code @RestController} would be registered by the scan of members AND by the
 * {@code @Bean} below, which is an ambiguous mapping.
 */
@TestConfiguration(proxyBeanMethods = false)
public class HttpPolicyTestEndpoints {

    public static final String ECHO = "/api/v1/test-support/echo";
    public static final String CURSOR = "/api/v1/test-support/cursor";
    public static final String VALIDATED = "/api/v1/test-support/validated";
    public static final String CONTENDED = "/api/v1/test-support/contended";
    public static final String UNEXPECTED = "/api/v1/test-support/unexpected";
    public static final String BOUNDED = "/api/v1/test-support/bounded";
    public static final String IF_MATCH = "/api/v1/test-support/if-match";
    public static final String JSON_ONLY = "/api/v1/test-support/json-only";
    public static final String NESTED_SERVICE = "/api/v1/test-support/nested-service";
    public static final String NESTED_BODY = "/api/v1/test-support/nested-body";
    public static final String TIED_BODY = "/api/v1/test-support/tied-body";
    public static final String PARAMS_CONDITION = "/api/v1/test-support/params-condition";
    public static final String DEEP_CAUSE = "/api/v1/test-support/deep-cause";
    public static final String PATH_VARIABLE = "/api/v1/test-support/path-variable/t-1";

    /** The route templates these routes are logged under, never the concrete URI. */
    public static final String ECHO_TEMPLATE = "/test-support/echo";
    public static final String UNEXPECTED_TEMPLATE = "/test-support/unexpected";
    public static final String PATH_VARIABLE_TEMPLATE = "/test-support/path-variable/{tripId}";

    @Bean
    ValidatedTestService validatedTestService() {
        return new ValidatedTestService();
    }

    @Bean
    TestSupportController testSupportController(ValidatedTestService validatedTestService) {
        return new TestSupportController(validatedTestService);
    }
}
