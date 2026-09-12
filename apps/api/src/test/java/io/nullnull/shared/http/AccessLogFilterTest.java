package io.nullnull.shared.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BA-003 step 3: {@code APP_ACCESS_LOG_INCLUDE_QUERY} is a developer aid, not a switch that can turn
 * off a privacy rule in production (docs/operations/ENVIRONMENT.md §9,
 * docs/security/PRIVACY_REQUIREMENTS.md §8).
 */
@DisplayName("BA-003 access log query option")
class AccessLogFilterTest {

    @Test
    @DisplayName("BA-003-T2 including the query string in a production access log fails startup")
    void includingTheQueryInProductionIsRefused() {
        assertThatThrownBy(() -> new AccessLogFilter(true, "production"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_ACCESS_LOG_INCLUDE_QUERY")
                .hasMessageContaining("production");
    }

    @Test
    @DisplayName("BA-003-T2 a near-miss environment value cannot quietly enable query logging")
    void anEnvironmentOutsideTheVocabularyFailsStartup() {
        // The guard used to be a deny-list on the exact literal "production", so NULLNULL_ENV=prod
        // started normally and logged query=searchQuery=... . nullnull.env has exactly one consumer -
        // this constructor - so nothing else in the service would have noticed the typo.
        assertThatThrownBy(() -> new AccessLogFilter(true, "prod"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NULLNULL_ENV")
                .hasMessageContaining("prod");
        // Unrecognised is refused whatever the flag says: an environment nothing can classify is a
        // configuration error, not a non-production environment.
        assertThatThrownBy(() -> new AccessLogFilter(false, "prod"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NULLNULL_ENV");
        assertThatThrownBy(() -> new AccessLogFilter(false, "Production"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AccessLogFilter(false, ""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the documented default is off, and a developer may still turn it on outside production")
    void theOptionIsUsableEverywhereElse() {
        assertThatCode(() -> new AccessLogFilter(false, "production")).doesNotThrowAnyException();
        assertThatCode(() -> new AccessLogFilter(true, "local")).doesNotThrowAnyException();
        // compose.integration.yml runs services with NULLNULL_ENV=test and =staging.
        assertThatCode(() -> new AccessLogFilter(true, "test")).doesNotThrowAnyException();
        assertThatCode(() -> new AccessLogFilter(true, "staging")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the accepted vocabulary is exactly the documented one, and production is not in the allow-list")
    void theVocabularyIsTheDocumentedOne() {
        // docs/operations/ENVIRONMENT.md §1 (환경) and §3 (NULLNULL_ENV) name these four and no others.
        org.assertj.core.api.Assertions.assertThat(AccessLogFilter.ENVIRONMENTS)
                .containsExactlyInAnyOrder("local", "test", "staging", "production");
        org.assertj.core.api.Assertions.assertThat(AccessLogFilter.NON_PRODUCTION)
                .containsExactlyInAnyOrder("local", "test", "staging");
    }
    @Test
    @DisplayName("BA-003-T2 the access log carries no header value, whatever the request sent")
    void theLogLineNeverCarriesAHeader() throws Exception {
        // The filter's own javadoc says "never a header", and until now that was prose. Every
        // credential this product has travels in one - the session cookie, the CSRF token, and the
        // deletion status token (PM-018, whose receipt is otherwise kept out of the database and
        // the job payload by name). A line added to the log statement would leak all three and no
        // test would notice.
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AccessLogFilter.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            var request = new org.springframework.mock.web.MockHttpServletRequest("GET",
                    "/api/v1/deletion-requests/018f4d00-1111-7222-8333-444455556666");
            request.addHeader("X-Deletion-Status-Token", "dst-canary-must-not-be-logged");
            request.addHeader("X-CSRF-Token", "csrf-canary-must-not-be-logged");
            request.addHeader("Cookie", "__Host-nullnull_session=cookie-canary-must-not-be-logged");
            request.addHeader("Authorization", "Bearer authz-canary-must-not-be-logged");
            request.setQueryString("q=query-canary-must-not-be-logged");
            var response = new org.springframework.mock.web.MockHttpServletResponse();

            new AccessLogFilter(false, "production")
                    .doFilter(request, response, new org.springframework.mock.web.MockFilterChain());

            assertThat(appender.list).as("the filter logged exactly one line").hasSize(1);
            String line = appender.list.get(0).getFormattedMessage();
            assertThat(line).as("a canary reached the access log")
                    .doesNotContain("canary-must-not-be-logged");
            // And positively: only the five documented values, so a new field has to be added here
            // deliberately rather than arriving unnoticed.
            assertThat(line.replaceAll("=[^ ]*", "="))
                    .isEqualTo("request method= route= status= durationMs= requestId=");
        } finally {
            logger.detachAppender(appender);
        }
    }

}
