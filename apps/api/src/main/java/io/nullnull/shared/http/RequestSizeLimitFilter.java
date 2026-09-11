package io.nullnull.shared.http;

import io.nullnull.shared.problem.ProblemCode;
import io.nullnull.shared.problem.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Bounds the request body (docs/api/README.md §14 "Spring request limit",
 * docs/operations/ENVIRONMENT.md {@code APP_MAX_REQUEST_BODY_BYTES}). Two different requests have to
 * be refused and neither one covers the other:
 * <ul>
 * <li>a declared {@code Content-Length} above the bound, which is refused before the body is read at
 *     all, so an oversized upload never reaches a buffer or a parser;</li>
 * <li>a chunked body with no declared length, which can only be caught while it streams - the
 *     wrapper below counts what a converter actually reads and fails the read past the bound.</li>
 * </ul>
 *
 * <p><strong>Why this filter writes the Problem itself</strong> instead of throwing
 * {@code ApiException} or calling {@code sendError}. A servlet filter runs outside the
 * DispatcherServlet, so {@code @RestControllerAdvice} never sees an exception thrown here: an
 * {@code ApiException} would escape to the container and come back as a 500 through the error
 * dispatch. {@code sendError(413)} would reach {@code ProblemErrorController}, which does map
 * {@code PAYLOAD_TOO_LARGE} to {@code INVALID_REQUEST}, but that path depends on container error
 * dispatch being wired in whatever runtime is executing (it is not, in a MockMvc call) and it cannot
 * carry a detail of its own. Writing the response here keeps the refusal identical in every runtime
 * and identical to the streamed case, which {@code GlobalExceptionHandler} answers with the same code
 * and the same sentence. The container mapping stays where it is for a body Tomcat itself refuses.
 *
 * <p>Ordered just inside {@link RequestIdFilter} so the refusal carries a request id, and inside
 * {@link AccessLogFilter} so it is logged like any other response.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    /**
     * Below this a normal request could not be sent at all, so a mistyped bound would look like a
     * broken API rather than a configuration error. Startup fails instead.
     */
    static final long MINIMUM_LIMIT_BYTES = 4096;

    private final long limitBytes;
    private final ObjectMapper json;

    public RequestSizeLimitFilter(@Value("${nullnull.http.max-request-body-bytes}") long limitBytes,
            ObjectMapper json) {
        if (limitBytes < MINIMUM_LIMIT_BYTES) {
            throw new IllegalArgumentException("nullnull.http.max-request-body-bytes must be at least "
                    + MINIMUM_LIMIT_BYTES + " but was " + limitBytes);
        }
        this.limitBytes = limitBytes;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (request.getContentLengthLong() > limitBytes) {
            // Nothing is read: the caller told us the size and it is over the bound.
            ProblemResponses.write(request, response, json, ProblemCode.INVALID_REQUEST,
                    HttpStatus.CONTENT_TOO_LARGE, RequestBodyTooLargeException.DETAIL);
            return;
        }
        chain.doFilter(new BoundedBodyRequest(request, limitBytes), response);
    }

    /** Hands out a body stream that stops at the bound instead of one that reads whatever arrives. */
    private static final class BoundedBodyRequest extends HttpServletRequestWrapper {

        private final long limitBytes;
        private ServletInputStream stream;
        private BufferedReader reader;

        private BoundedBodyRequest(HttpServletRequest request, long limitBytes) {
            super(request);
            this.limitBytes = limitBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new BoundedInputStream(super.getInputStream(), limitBytes);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (reader == null) {
                String encoding = getCharacterEncoding();
                Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
                reader = new BufferedReader(new InputStreamReader(getInputStream(), charset));
            }
            return reader;
        }
    }

    /** Counts bytes handed to the caller and fails the read once the bound is passed. */
    private static final class BoundedInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long limitBytes;
        private long read;

        private BoundedInputStream(ServletInputStream delegate, long limitBytes) {
            this.delegate = delegate;
            this.limitBytes = limitBytes;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = delegate.read(buffer, offset, length);
            if (count > 0) {
                count(count);
            }
            return count;
        }

        private void count(int bytes) throws RequestBodyTooLargeException {
            read += bytes;
            if (read > limitBytes) {
                throw new RequestBodyTooLargeException(limitBytes);
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
