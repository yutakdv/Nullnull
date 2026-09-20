package io.nullnull.optimization.infrastructure.kakao;

import io.nullnull.optimization.application.RouteMatrixGateway.RouteWaypoint;
import java.net.URI;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime-only Kakao Mobility settings. {@code KAKAO_REST_API_KEY} is never exposed through an
 * accessor or {@link #toString()}; it leaves this class only inside the request header this class
 * builds.
 *
 * <p><b>The credential is a header, not a query parameter.</b> Kakao authenticates with
 * {@code Authorization: KakaoAK <key>}, so unlike the KTO adapters - whose key has to ride in the
 * URI - nothing here can put a secret where an access log would record it. {@code BA-070-T2} pins
 * that query strings stay out of logs; this endpoint does not need that protection because the URI
 * carries coordinates only.
 *
 * <p><b>Two products, one console.</b> A-051 fixed the provider as Kakao Mobility, whose host is
 * {@code apis-navi.kakaomobility.com} - not Kakao Map's {@code dapi.kakao.com}, which has no
 * directions API at all. The decision record warns that calling both "Kakao" is how one product's
 * free tier gets cited as evidence for the other's, so the host is pinned here rather than left to
 * configuration, and the source code names the product and the operation.
 */
@ConfigurationProperties(prefix = "nullnull.kakao-route")
public class KakaoRouteProperties {

    /**
     * Confirmed by a live call on 2026-09-20, not only by reading the guide: this host answered
     * {@code GET /v1/directions} with HTTP 200. What that call did not establish is that it is the
     * only host - the provider's domain notice page was a 404 - so this is an allowlist of one
     * measured entry, not a proof that no other entry is needed.
     */
    static final String OFFICIAL_HOST = "apis-navi.kakaomobility.com";

    /** The one reviewed operation. Multi-destination and multi-origin are POST and are not wired. */
    private static final String DIRECTIONS_PATH = "/v1/directions";

    private String restApiKey = "";
    private String baseUrl = "https://" + OFFICIAL_HOST;

    public void setRestApiKey(String restApiKey) {
        this.restApiKey = normalized(restApiKey);
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = stripTrailingSlash(normalized(baseUrl));
    }

    /**
     * Fails loudly when the credential is absent, rather than letting every leg come back as "no
     * answer". Both outcomes stop the optimizer, but only one of them tells an operator which.
     */
    public void requireConfigured(boolean testEndpointAllowed) {
        if (restApiKey.isBlank()) {
            throw new IllegalStateException(
                    "KAKAO_REST_API_KEY is required before a route can be requested");
        }
        approvedBaseUri(testEndpointAllowed);
    }

    /**
     * The request for one ordered pair. Kakao takes {@code x,y} - longitude first - and
     * {@code radius} belongs to the multi-destination and multi-origin operations, not to this one:
     * the 2026-09-20 call omitted it and was answered {@code result_code: 0}.
     */
    public URI directionsUri(RouteWaypoint from, RouteWaypoint to, boolean testEndpointAllowed) {
        requireConfigured(testEndpointAllowed);
        return URI.create(approvedBaseUri(testEndpointAllowed) + DIRECTIONS_PATH
                + "?origin=" + point(from) + "&destination=" + point(to));
    }

    /** The credential's only way out of this class. */
    public Map<String, String> authorizationHeader(boolean testEndpointAllowed) {
        requireConfigured(testEndpointAllowed);
        return Map.of("Authorization", "KakaoAK " + restApiKey);
    }

    @Override
    public String toString() {
        return "KakaoRouteProperties[configured=" + !restApiKey.isBlank()
                + ", baseConfigured=" + !baseUrl.isBlank() + "]";
    }

    /**
     * {@code toPlainString} rather than {@code toString}: a BigDecimal read from a numeric column can
     * carry an exponent, and {@code 1.269770E+2} is not a coordinate to this provider.
     */
    private static String point(RouteWaypoint waypoint) {
        return waypoint.longitude().toPlainString() + "," + waypoint.latitude().toPlainString();
    }

    private URI approvedBaseUri(boolean testEndpointAllowed) {
        URI candidate;
        try {
            candidate = URI.create(baseUrl);
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("nullnull.kakao-route.base-url is not a URI");
        }
        if (official(candidate) || testEndpointAllowed && loopbackFixture(candidate)) {
            return candidate;
        }
        throw new IllegalStateException("nullnull.kakao-route.base-url is not the approved endpoint");
    }

    private static boolean official(URI uri) {
        return "https".equals(uri.getScheme()) && OFFICIAL_HOST.equalsIgnoreCase(uri.getHost())
                && uri.getPort() == -1 && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                && noExtras(uri);
    }

    private static boolean loopbackFixture(URI uri) {
        return "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost())
                && uri.getPort() > 0 && noExtras(uri);
    }

    private static boolean noExtras(URI uri) {
        return uri.getUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
