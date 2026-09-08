package io.nullnull.shared.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * One line per request with exactly the fields docs/security/PRIVACY_REQUIREMENTS.md §8 allows:
 * method, route template, status, duration and request id. Never the query string, never a header,
 * never a cookie, never a body - not even truncated, because a truncated secret is still a secret.
 *
 * <p>The line is written after the chain returns, which is also the only moment the route template
 * exists: {@code HandlerMapping} sets it during the dispatch, so a filter that read it on the way in
 * would log {@link RouteTemplate#UNMATCHED} for every request. A request that genuinely matched no
 * handler keeps that placeholder instead of falling back to the raw URI (see {@link RouteTemplate}).
 *
 * <p>{@code APP_ACCESS_LOG_INCLUDE_QUERY} exists because a developer chasing a paging or filter bug
 * locally wants the query string. It is {@code false} by default and this constructor refuses to
 * start a production service with it on: docs/operations/ENVIRONMENT.md §9 requires that
 * "application/CDN/APM access log이 query string과 검색어를 기록하지 않음", and a flag that can quietly
 * disable a privacy rule in production is exactly the OFF switch the BA-003 card forbids.
 *
 * <p>The environment check is an ALLOW-list, not a "not production" test. {@code nullnull.env} has
 * exactly one consumer - this constructor - so nothing else would notice a near-miss value, and a
 * deny-list fails open: {@code NULLNULL_ENV=prod} is not the literal {@code production} and would
 * have started with the query string in the log. Only the four documented values
 * (docs/operations/ENVIRONMENT.md §1, §3) are accepted at all, and only the three explicitly
 * non-production ones may include the query.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AccessLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AccessLogFilter.class);

    /** The whole documented {@code NULLNULL_ENV} vocabulary (docs/operations/ENVIRONMENT.md §1, §3). */
    static final Set<String> ENVIRONMENTS = Set.of("local", "test", "staging", "production");

    /** The subset that is explicitly not production, and the only one that may log a query string. */
    static final Set<String> NON_PRODUCTION = Set.of("local", "test", "staging");

    private final boolean includeQuery;

    public AccessLogFilter(@Value("${nullnull.http.access-log.include-query}") boolean includeQuery,
            @Value("${nullnull.env}") String environment) {
        if (!ENVIRONMENTS.contains(environment)) {
            throw new IllegalStateException("NULLNULL_ENV must be one of "
                    + new TreeSet<>(ENVIRONMENTS) + " but was '" + environment + "'; an unrecognised"
                    + " environment cannot be classified as non-production, and this filter is the"
                    + " only consumer of the value (docs/operations/ENVIRONMENT.md §1, §3)");
        }
        if (includeQuery && !NON_PRODUCTION.contains(environment)) {
            throw new IllegalStateException("APP_ACCESS_LOG_INCLUDE_QUERY must be false when "
                    + "NULLNULL_ENV=" + environment + ": a production access log may not carry query "
                    + "strings (docs/security/PRIVACY_REQUIREMENTS.md §8)");
        }
        this.includeQuery = includeQuery;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        long startedAt = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            if (includeQuery) {
                // Non-production only; the constructor already refused this combination in production.
                log.info("request method={} route={} status={} durationMs={} requestId={} query={}",
                        request.getMethod(), RouteTemplate.of(request), response.getStatus(),
                        durationMs, RequestIdFilter.current(request), request.getQueryString());
            } else {
                log.info("request method={} route={} status={} durationMs={} requestId={}",
                        request.getMethod(), RouteTemplate.of(request), response.getStatus(),
                        durationMs, RequestIdFilter.current(request));
            }
        }
    }
}
