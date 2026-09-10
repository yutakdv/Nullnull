package io.nullnull.catalog.infrastructure.kto;

import io.nullnull.catalog.application.KtoGatewayException;
import io.nullnull.catalog.application.KtoPlaceRequest;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime-only KTO settings. {@code KTO_SERVICE_KEY} must be the data.go.kr decoding key; this type
 * never exposes it through an accessor or {@link #toString()}.
 */
@ConfigurationProperties(prefix = "nullnull.kto")
public class KtoKorServiceProperties {

    private static final String OFFICIAL_HOST = "apis.data.go.kr";
    private static final String OFFICIAL_PATH = "/B551011/KorService2";

    private String serviceKey = "";
    private String baseUrl = "";
    private String mobileApp = "Nullnull";
    private String mobileOs = "ETC";
    private String releaseVersion = "local-unreleased";
    private String contestProfile = "NONE";

    public void setServiceKey(String serviceKey) {
        this.serviceKey = normalized(serviceKey);
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = stripTrailingSlash(normalized(baseUrl));
    }

    public void setMobileApp(String mobileApp) {
        this.mobileApp = normalized(mobileApp);
    }

    public void setMobileOs(String mobileOs) {
        this.mobileOs = normalized(mobileOs);
    }

    public void setReleaseVersion(String releaseVersion) {
        this.releaseVersion = normalized(releaseVersion);
    }

    public void setContestProfile(String contestProfile) {
        this.contestProfile = normalized(contestProfile);
    }

    public void requireConfigured(boolean testEndpointAllowed) {
        if (serviceKey.isBlank()) {
            throw new KtoGatewayException(KtoGatewayException.Code.KTO_NOT_CONFIGURED);
        }
        baseUri(testEndpointAllowed);
        safeValue(mobileApp, 50);
        safeValue(mobileOs, 20);
        safeValue(releaseVersion, 100);
    }

    public URI detailCommonUri(KtoPlaceRequest request, boolean testEndpointAllowed) {
        Objects.requireNonNull(request, "request");
        requireConfigured(testEndpointAllowed);
        URI base = baseUri(testEndpointAllowed);
        String query = "serviceKey=" + encode(serviceKey)
                + "&MobileOS=" + encode(mobileOs)
                + "&MobileApp=" + encode(mobileApp)
                + "&contentId=" + encode(request.contentId())
                + "&contentTypeId=" + encode(request.contentTypeId())
                + "&defaultYN=Y&firstImageYN=N&areacodeYN=Y&catcodeYN=Y&addrinfoYN=Y&mapinfoYN=Y"
                + "&overviewYN=N&_type=json";
        return URI.create(base + "/detailCommon2?" + query);
    }

    public String releaseVersion() {
        return safeValue(releaseVersion, 100);
    }

    public boolean isContestProfile() {
        return "2026_KTO_WEBAPP".equals(contestProfile);
    }

    @Override
    public String toString() {
        return "KtoKorServiceProperties[configured=" + !serviceKey.isBlank()
                + ", baseConfigured=" + !baseUrl.isBlank() + ", contestProfile=" + contestProfile + "]";
    }

    private URI baseUri(boolean testEndpointAllowed) {
        if (baseUrl.isBlank()) {
            throw new KtoGatewayException(KtoGatewayException.Code.KTO_NOT_CONFIGURED);
        }
        URI candidate;
        try {
            candidate = URI.create(baseUrl);
        } catch (IllegalArgumentException failure) {
            throw new KtoGatewayException(KtoGatewayException.Code.KTO_BASE_URL_NOT_APPROVED);
        }
        if (official(candidate) || testEndpointAllowed && loopbackFixture(candidate)) {
            return candidate;
        }
        throw new KtoGatewayException(KtoGatewayException.Code.KTO_BASE_URL_NOT_APPROVED);
    }

    private static boolean official(URI uri) {
        return "https".equals(uri.getScheme()) && OFFICIAL_HOST.equalsIgnoreCase(uri.getHost())
                && uri.getPort() == -1 && OFFICIAL_PATH.equals(uri.getRawPath()) && noExtras(uri);
    }

    private static boolean loopbackFixture(URI uri) {
        return "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost())
                && uri.getPort() > 0 && uri.getRawPath() != null && !uri.getRawPath().isBlank() && noExtras(uri);
    }

    private static boolean noExtras(URI uri) {
        return uri.getUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null;
    }

    private static String safeValue(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new KtoGatewayException(KtoGatewayException.Code.KTO_NOT_CONFIGURED);
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
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
