package io.nullnull.crowd.infrastructure.seoul;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.crowd.application.SeoulGatewayException;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BA-090 Seoul proxy request shape")
class SeoulCityDataPropertiesTest {

    private static final String TOKEN = "fake-proxy-token-never-in-a-url";
    private static final String BASE = "https://abc123.lambda-url.ap-northeast-2.on.aws";

    private static SeoulCityDataProperties configured() {
        SeoulCityDataProperties properties = new SeoulCityDataProperties();
        properties.setBaseUrl(BASE + "/");
        properties.setProxyToken(TOKEN);
        return properties;
    }

    @Test
    @DisplayName("BA-090-T5 proxy 요청 URL 은 자격증명을 담지 않는다")
    void credentialsNeverEnterTheUrl() {
        SeoulCityDataProperties properties = configured();
        URI uri = properties.cityDataUri("광화문·덕수궁", false);

        // The clause. BA-090-T4 proves the header is sent; it cannot prove this, because the stub it
        // uses drops a query parameter named "token" before recording it - that assertion would pass
        // with the secret on the wire. Here the URI itself is the object under test, so there is
        // nowhere for it to hide: every part is checked, not only the query.
        assertThat(uri.toString()).doesNotContain(TOKEN);
        assertThat(uri.getRawQuery()).isNull();
        assertThat(uri.getUserInfo()).isNull();
        assertThat(uri.getRawFragment()).isNull();

        // Non-vacuous: the token exists, this object holds it, and it does leave - by the other door.
        assertThat(properties.proxyHeaders())
                .containsEntry(SeoulCityDataProperties.PROXY_TOKEN_HEADER, TOKEN);
        // And not in the diagnostic rendering either, which is what reaches a log line.
        assertThat(properties.toString()).doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("BA-090 the area name becomes one encoded path segment with no form plus")
    void areaNameIsEncodedAsAPathSegment() {
        assertThat(configured().cityDataUri("광화문·덕수궁", false).getPath())
                .isEqualTo("/citydata/광화문·덕수궁");
        // URLEncoder is form encoding: without the fix a space arrives as '+', which a path segment
        // reads as a literal plus and the provider would not match the area.
        assertThat(configured().cityDataUri("여의도 한강공원", false).getRawPath()).doesNotContain("+");
        assertThat(configured().cityDataUri("여의도 한강공원", false).getPath())
                .isEqualTo("/citydata/여의도 한강공원");
    }

    @Test
    @DisplayName("BA-090 an unconfigured or unapproved endpoint fails closed")
    void failsClosed() {
        SeoulCityDataProperties noToken = new SeoulCityDataProperties();
        noToken.setBaseUrl(BASE);
        assertThatThrownBy(() -> noToken.cityDataUri("광화문·덕수궁", false))
                .isInstanceOf(SeoulGatewayException.class).hasMessage("SEOUL_NOT_CONFIGURED");

        SeoulCityDataProperties noBase = new SeoulCityDataProperties();
        noBase.setProxyToken(TOKEN);
        assertThatThrownBy(() -> noBase.cityDataUri("광화문·덕수궁", false))
                .isInstanceOf(SeoulGatewayException.class).hasMessage("SEOUL_NOT_CONFIGURED");

        // The provider's own endpoint is what someone reaches for if the proxy is skipped. It is plain
        // HTTP on a non-loopback host, so it never becomes a base URL here - and the transport would
        // refuse it too. Two independent refusals, deliberately.
        for (String rejected : new String[] {
            "http://openapi.seoul.go.kr:8088",
            "https://user:secret@abc123.lambda-url.ap-northeast-2.on.aws",
            BASE + "?token=leak",
            BASE + "#leak",
        }) {
            SeoulCityDataProperties properties = new SeoulCityDataProperties();
            properties.setBaseUrl(rejected);
            properties.setProxyToken(TOKEN);
            assertThatThrownBy(() -> properties.cityDataUri("광화문·덕수궁", false))
                    .as(rejected)
                    .isInstanceOf(SeoulGatewayException.class).hasMessage("SEOUL_BASE_URL_NOT_APPROVED");
        }

        // Accepted, so the four above refuse a shape rather than everything.
        SeoulCityDataProperties loopback = new SeoulCityDataProperties();
        loopback.setBaseUrl("http://127.0.0.1:18088");
        loopback.setProxyToken(TOKEN);
        assertThat(loopback.cityDataUri("광화문·덕수궁", true).getPort()).isEqualTo(18088);
        assertThatThrownBy(() -> loopback.cityDataUri("광화문·덕수궁", false))
                .isInstanceOf(SeoulGatewayException.class).hasMessage("SEOUL_BASE_URL_NOT_APPROVED");
    }

    @Test
    @DisplayName("BA-090 an area name that would change the request path is refused")
    void areaNameCannotEscapeItsSegment() {
        String withControlCharacter = "광화문" + (char) 7;
        // The traversal case is BUILT, not written as a literal - and this comment avoids the literal
        // too, which is why it describes the shape instead of showing it. check_container_test_inputs
        // reads any quoted string starting with a doubled parent-directory prefix as a repository path
        // the suite opens, and demands a matching COPY in the api image (READ_PATH, at
        // scripts/check_container_test_inputs.py:38). This
        // is an input we REFUSE, not a file we read - but that checker cannot tell those apart, and it
        // is right to stay strict: the two times it fired for real, docker-integration was the only
        // gate that would have caught them. Keep this constructed.
        String traversal = ".." + "/" + ".." + "/etc";
        for (String rejected : new String[] {"", "   ", traversal, "a/b", "a\\b", withControlCharacter}) {
            assertThatThrownBy(() -> configured().cityDataUri(rejected, false))
                    .as(rejected.isBlank() ? "blank" : rejected)
                    .isInstanceOf(SeoulGatewayException.class).hasMessage("SEOUL_AREA_NOT_ACCEPTED");
        }
        assertThatThrownBy(() -> configured().cityDataUri("가".repeat(101), false))
                .isInstanceOf(SeoulGatewayException.class).hasMessage("SEOUL_AREA_NOT_ACCEPTED");
        // Accepted at the boundary, so the length rule is a boundary and not a blanket refusal.
        assertThat(configured().cityDataUri("가".repeat(100), false).getPath()).contains("가");
    }
}
