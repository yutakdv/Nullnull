package io.nullnull.identity.api;

import io.nullnull.identity.application.OwnerContext;
import io.nullnull.identity.application.SessionProperties;
import io.nullnull.identity.application.SessionService;
import io.nullnull.shared.http.NullnullOperation;
import io.nullnull.shared.http.NullnullOperation.Security;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SessionHttpConfiguration implements WebMvcConfigurer {
    private static final String CONTEXT = OwnerContext.class.getName();
    private final SessionService sessions;
    private final SessionProperties properties;
    public SessionHttpConfiguration(SessionService sessions, SessionProperties properties) {
        this.sessions = sessions; this.properties = properties;
    }
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SessionInterceptor());
    }
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new OwnerContextArgumentResolver());
    }
    public String cookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) { return null; }
        List<Cookie> matching = Arrays.stream(cookies)
                .filter(c -> properties.cookieName().equals(c.getName())).toList();
        if (matching.size() > 1) { throw SessionService.unauthorized(); }
        return matching.isEmpty() ? null : matching.getFirst().getValue();
    }
    private static NullnullOperation operation(Object handler) {
        return handler instanceof HandlerMethod method ? method.getMethodAnnotation(NullnullOperation.class) : null;
    }
    /**
     * One preHandle, because the order is the security property (#240 A-1). A non-safe request is origin-checked
     * before any session work, so a cross-origin caller gets the same 403 whether or not the browser holds a cookie.
     * Only a same-origin request, or a safe one, can learn that no session cookie was sent; every other session
     * failure stays a plain UNAUTHORIZED.
     */
    private final class SessionInterceptor implements HandlerInterceptor {
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            NullnullOperation op = operation(handler);
            if (op == null) { return true; }
            if (!Set.of("GET", "HEAD", "OPTIONS", "TRACE").contains(request.getMethod())) {
                String origin = singleHeader(request, "Origin");
                boolean referer = origin == null;
                if (referer) { origin = singleHeader(request, "Referer"); }
                if (!sameOrigin(origin, referer)) { throw csrfInvalid(); }
            }
            if (!Arrays.asList(op.security()).contains(Security.SESSION)) { return true; }
            // Before any session work, so every answer from here on - success, the missing-cookie 401 and every
            // other 401 - carries the same header; a header set only after resolve would tell them apart.
            response.setHeader("Cache-Control", "private, no-store");
            if (!sessionCookieSent(request)) {
                throw ApiException.missingSessionCookie("No session cookie was sent.");
            }
            OwnerContext context = sessions.resolve(cookie(request), op.id().equals("deleteCurrentSession"));
            request.setAttribute(CONTEXT, context);
            boolean csrf = Arrays.asList(op.security()).contains(Security.CSRF);
            sessions.authorize(context, csrf ? singleHeader(request, "X-CSRF-Token") : null, csrf);
            return true;
        }
    }
    /**
     * Whether the request named the session cookie at all: Tomcat's parse OR the raw header. Measured on Tomcat
     * 11.0.24, the two disagree in both directions. It drops a cookie whose value holds a backslash, comma or space,
     * so getCookies() alone would call a garbage cookie missing; and it reads {@code name =v} as ours, so a raw scan
     * that did not trim the name would call a working cookie missing. Pairs are split on ',' too, because an
     * intermediary that folds Cookie headers joins them with ", " and Tomcat then drops the whole folded pair. A
     * cookie the client sent must never get the missing-cookie answer, so either parser finding it is enough.
     */
    private boolean sessionCookieSent(HttpServletRequest request) {
        String name = properties.cookieName();
        Cookie[] cookies = request.getCookies();
        if (cookies != null && Arrays.stream(cookies).anyMatch(c -> name.equals(c.getName()))) { return true; }
        for (String header : Collections.list(request.getHeaders("Cookie"))) {
            for (String pair : header.split("[;,]")) {
                int equals = pair.indexOf('=');
                if (name.equals((equals < 0 ? pair : pair.substring(0, equals)).strip())) { return true; }
            }
        }
        return false;
    }
    private boolean sameOrigin(String value, boolean referer) {
        if (value == null) { return false; }
        try {
            URI uri = URI.create(value);
            return uri.getHost() != null && uri.getRawUserInfo() == null && uri.getRawFragment() == null
                    && (referer || (uri.getRawPath().isEmpty() && uri.getRawQuery() == null))
                    && properties.origin.getScheme().equalsIgnoreCase(uri.getScheme())
                    && properties.origin.getHost().equalsIgnoreCase(uri.getHost())
                    && port(properties.origin) == port(uri);
        } catch (IllegalArgumentException e) { return false; }
    }
    private static int port(URI uri) {
        return uri.getPort() == -1 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
    }
    private static String singleHeader(HttpServletRequest request, String name) {
        List<String> values = Collections.list(request.getHeaders(name));
        if (values.size() > 1) { throw csrfInvalid(); }
        return values.isEmpty() ? null : values.getFirst();
    }
    private static ApiException csrfInvalid() {
        return new ApiException(ProblemCode.CSRF_INVALID, "Same-origin credentials are required.");
    }
    private static final class OwnerContextArgumentResolver implements HandlerMethodArgumentResolver {
        @Override public boolean supportsParameter(MethodParameter parameter) {
            return parameter.getParameterType() == OwnerContext.class;
        }
        @Override public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                NativeWebRequest request, WebDataBinderFactory binder) {
            Object context = request.getAttribute(CONTEXT, NativeWebRequest.SCOPE_REQUEST);
            if (context == null) { throw SessionService.unauthorized(); }
            return context;
        }
    }
}
