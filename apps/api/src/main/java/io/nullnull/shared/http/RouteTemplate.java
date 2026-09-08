package io.nullnull.shared.http;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.pattern.PathPattern;

/**
 * The route template of the current request, for LOGS ONLY.
 *
 * <p>docs/security/PRIVACY_REQUIREMENTS.md §8 allows a log line to carry the route template, method,
 * status and duration. It does not allow the request URI: {@code /trips/9f13.../items/6d2b...} names
 * real resources, and a log aggregator keeps it far longer than the request. The template
 * {@code /trips/{tripId}/items/{itemId}} answers every operational question (which route is slow,
 * which route fails) without naming a single row.
 *
 * <p>The Problem BODY keeps the real URI in {@code instance}; the two have different audiences. The
 * caller already knows the URL it just requested and the contract types {@code instance} as a
 * uri-reference, so returning it to that one caller reveals nothing new. See
 * {@link io.nullnull.shared.problem.ProblemResponses}.
 *
 * <p>The attribute is set by {@code HandlerMapping} during the dispatch, so it exists only AFTER the
 * handler has been matched. A filter must therefore read it after {@code chain.doFilter}, and a
 * request that matched no handler - an unknown path, a rejected body, a method that reached no
 * mapping - has none. Those return {@link #UNMATCHED} rather than falling back to the raw URI, which
 * is exactly the case where the URI would be attacker-controlled text.
 */
public final class RouteTemplate {

    /** No handler was matched, so there is no template. Never the raw URI. */
    public static final String UNMATCHED = "(no-route)";

    /**
     * The static resource handler's catch-all. An unknown path reaches it before the 404, so the
     * attribute is set even though no application route matched, and no application route is ever
     * mapped to it. Reporting it as {@link #UNMATCHED} keeps "which route is failing" answerable.
     */
    private static final String CATCH_ALL = "/**";

    private RouteTemplate() {
    }

    public static String of(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String template = null;
        if (pattern instanceof String value) {
            template = value;
        } else if (pattern instanceof PathPattern parsed) {
            // Parsed patterns are stored as PathPattern by the parsing handler mappings.
            template = parsed.getPatternString();
        }
        return template == null || template.isBlank() || CATCH_ALL.equals(template)
                ? UNMATCHED
                : template;
    }
}
