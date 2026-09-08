package io.nullnull.shared.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter =
            new RequestIdFilter(Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void generatesServerIdWhenHeaderMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/health/live");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());

        String header = response.getHeader(RequestIdFilter.HEADER);
        assertThat(header).isNotBlank();
        assertThat(request.getAttribute(RequestIdFilter.ATTRIBUTE)).isEqualTo(header);
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).as("MDC cleared after request").isNull();
    }

    @Test
    void echoesSafeClientId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/health/live");
        request.addHeader(RequestIdFilter.HEADER, "req_client-0001");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("req_client-0001");
    }

    @Test
    void replacesUnsafeClientId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/health/live");
        request.addHeader(RequestIdFilter.HEADER, "<script>alert(1)</script>");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getHeader(RequestIdFilter.HEADER))
                .isNotEqualTo("<script>alert(1)</script>")
                .matches("^[A-Za-z0-9._-]{8,64}$");
    }
}
