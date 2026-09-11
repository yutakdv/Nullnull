package io.nullnull.shared.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * BA-003 step 1: the declared-length half of the body bound, and the configuration floor.
 *
 * <p>The early return matters for something the response cannot show. Both refusals answer 413 with
 * the same body, so an end-to-end test stays green even if the declared length is ignored and the
 * streaming counter catches the body instead - the difference is whether a single byte was read at
 * all. That is what this test pins.
 */
@DisplayName("BA-003 request body bound")
class RequestSizeLimitFilterTest {

    @Test
    @DisplayName("a declared Content-Length over the bound is refused without invoking the chain")
    void anOversizedDeclaredLengthNeverReachesTheChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/anything");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent("x".repeat(9000).getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean();

        new RequestSizeLimitFilter(8192, new ObjectMapper())
                .doFilter(request, response, recordingChain(chainInvoked));

        assertThat(chainInvoked).as("nothing downstream may read a body already known to be too large")
                .isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).contains("\"code\":\"INVALID_REQUEST\"");
    }

    @Test
    @DisplayName("a body within the bound reaches the chain")
    void anAcceptableBodyReachesTheChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/anything");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent("x".repeat(100).getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean();

        new RequestSizeLimitFilter(8192, new ObjectMapper())
                .doFilter(request, response, recordingChain(chainInvoked));

        assertThat(chainInvoked).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private static FilterChain recordingChain(AtomicBoolean invoked) {
        return new FilterChain() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response) {
                invoked.set(true);
            }
        };
    }

    @Test
    void aBoundBelowTheFloorIsRejectedAtStartup() {
        assertThatThrownBy(() -> new RequestSizeLimitFilter(1024, new ObjectMapper()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nullnull.http.max-request-body-bytes")
                .hasMessageContaining("1024");
        assertThatThrownBy(() -> new RequestSizeLimitFilter(0, new ObjectMapper()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theShippedProposalIsAccepted() {
        assertThatCode(() -> new RequestSizeLimitFilter(262144, new ObjectMapper()))
                .doesNotThrowAnyException();
        assertThatCode(() -> new RequestSizeLimitFilter(RequestSizeLimitFilter.MINIMUM_LIMIT_BYTES,
                new ObjectMapper())).doesNotThrowAnyException();
    }
}
