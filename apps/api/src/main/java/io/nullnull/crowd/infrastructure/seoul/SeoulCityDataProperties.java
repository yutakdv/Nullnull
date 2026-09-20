package io.nullnull.crowd.infrastructure.seoul;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the Seoul live-area collector sends its requests, and the token that gets it in.
 *
 * <p><strong>This type never holds the Seoul API key, and that is the design.</strong> The provider's
 * open API is plain HTTP on port 8088 with the key in the URL PATH (measured 2026-09-20: TLS fails on
 * 8088, 443 does not answer, and the data.go.kr listing for the same dataset is a LINK to the same
 * endpoint). {@code ProviderHttpClient.validateTarget} refuses anything that is not HTTPS or loopback,
 * so the call cannot leave this application at all. A proxy we run terminates HTTPS, holds the key and
 * appends it upstream; this application calls it with no credential in the URL.
 *
 * <p><strong>Do not "simplify" that into a pass-through proxy.</strong> If this side sent the key, it
 * would sit in the path of every request, and the path is the part access logs record - BA-070-T2 pins
 * only that the query string stays out of them. Key injection at the proxy is the whole point.
 *
 * <p>The shared token authenticates us to that proxy so it is not an open relay. It travels in a header
 * ({@link #proxyHeaders()}) for the same reason the key does not travel in the path.
 */
@ConfigurationProperties(prefix = "nullnull.seoul")
public class SeoulCityDataProperties {

    /** The header the proxy checks. Lower-cased spellings of it are dropped by StubProviderServer. */
    public static final String PROXY_TOKEN_HEADER = "X-Nullnull-Proxy-Token";

    private static final String CITYDATA_PATH = "/citydata/";
    private static final int MAX_AREA_NAME = 100;

    private String baseUrl = "";
    private String proxyToken = "";

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = stripTrailingSlash(normalized(baseUrl));
    }

    public void setProxyToken(String proxyToken) {
        this.proxyToken = normalized(proxyToken);
    }

    public boolean isConfigured() {
        return !baseUrl.isBlank() && !proxyToken.isBlank();
    }

    public void requireConfigured(boolean testEndpointAllowed) {
        if (proxyToken.isBlank()) {
            throw new SeoulGatewayException(SeoulGatewayException.Code.SEOUL_NOT_CONFIGURED);
        }
        baseUri(testEndpointAllowed);
    }

    /**
     * The one request shape this application makes. The area NAME is what the provider's path takes;
     * {@code AREA_CD} is our stable key for the row, but it is not what the endpoint accepts.
     */
    public URI cityDataUri(String areaName, boolean testEndpointAllowed) {
        requireConfigured(testEndpointAllowed);
        return URI.create(baseUri(testEndpointAllowed) + CITYDATA_PATH + encode(safeAreaName(areaName)));
    }

    public Map<String, String> proxyHeaders() {
        if (proxyToken.isBlank()) {
            throw new SeoulGatewayException(SeoulGatewayException.Code.SEOUL_NOT_CONFIGURED);
        }
        return Map.of(PROXY_TOKEN_HEADER, proxyToken);
    }

    @Override
    public String toString() {
        return "SeoulCityDataProperties[baseConfigured=" + !baseUrl.isBlank()
                + ", tokenConfigured=" + !proxyToken.isBlank() + "]";
    }

    private URI baseUri(boolean testEndpointAllowed) {
        if (baseUrl.isBlank()) {
            throw new SeoulGatewayException(SeoulGatewayException.Code.SEOUL_NOT_CONFIGURED);
        }
        URI candidate;
        try {
            candidate = URI.create(baseUrl);
        } catch (IllegalArgumentException failure) {
            throw new SeoulGatewayException(SeoulGatewayException.Code.SEOUL_BASE_URL_NOT_APPROVED);
        }
        // No official-host constant here, unlike the KTO properties: the approved host is OUR proxy and
        // its name is deployment configuration, not a reviewed provider endpoint. The allowlist that
        // pins it is nullnull.provider.sources.SEOUL_CITYDATA.allowed-hosts, which fails startup when
        // unset - so this checks the shape and that one checks the name.
        if (https(candidate) || testEndpointAllowed && loopbackFixture(candidate)) {
            return candidate;
        }
        throw new SeoulGatewayException(SeoulGatewayException.Code.SEOUL_BASE_URL_NOT_APPROVED);
    }

    private static boolean https(URI uri) {
        return "https".equals(uri.getScheme()) && uri.getHost() != null && noExtras(uri);
    }

    private static boolean loopbackFixture(URI uri) {
        return "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost())
                && uri.getPort() > 0 && noExtras(uri);
    }

    /** No credential, no query, no fragment: everything this URI carries is in its path. */
    private static boolean noExtras(URI uri) {
        return uri.getUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null;
    }

    /**
     * The provider's area names carry Korean text and a middle dot ({@code 광화문·덕수궁}), so this
     * cannot be an alphanumeric allowlist. It rejects the shapes that would make the request mean
     * something else: empty, over-long, control characters, or a path separator.
     */
    private static String safeAreaName(String areaName) {
        Objects.requireNonNull(areaName, "areaName");
        // Control characters are checked on the RAW value, before trimming, and that order is the
        // whole point: String.trim() removes every character <= U+0020, so a trailing BEL or NUL is
        // silently stripped and a check on the trimmed value never sees it. The test caught exactly
        // that. Stripping is worse than refusing here - it sends a different area name than the
        // caller asked for, and nothing downstream can tell that happened.
        if (areaName.chars().anyMatch(Character::isISOControl)) {
            throw new SeoulGatewayException(SeoulGatewayException.Code.SEOUL_AREA_NOT_ACCEPTED);
        }
        String value = areaName.trim();
        if (value.isEmpty() || value.length() > MAX_AREA_NAME
                || value.contains("/") || value.contains("\\") || value.contains("..")) {
            throw new SeoulGatewayException(SeoulGatewayException.Code.SEOUL_AREA_NOT_ACCEPTED);
        }
        return value;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String encode(String value) {
        // URLEncoder is form encoding: it turns a space into '+', which a path segment must not carry.
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
