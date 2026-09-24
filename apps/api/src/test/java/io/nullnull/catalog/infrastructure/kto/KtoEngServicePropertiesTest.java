package io.nullnull.catalog.infrastructure.kto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.catalog.application.KtoGatewayException;
import io.nullnull.catalog.application.KtoPlaceRequest;
import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BA-086 KTO EngService2 runtime settings")
class KtoEngServicePropertiesTest {

    private static final String CANARY = "fake-secret-key-never-retain";

    @Test
    @DisplayName("the English detail call goes only to the exact official EngService2 path")
    void englishDetailUsesItsOwnOfficialPath() {
        KtoKorServiceProperties properties = configured("https://apis.data.go.kr/B551011/EngService2");

        URI uri = properties.engDetailCommonUri(new KtoPlaceRequest("264329", "76"), false);

        assertThat(uri.getScheme()).isEqualTo("https");
        assertThat(uri.getHost()).isEqualTo("apis.data.go.kr");
        assertThat(uri.getRawPath()).isEqualTo("/B551011/EngService2/detailCommon2");
        assertThat(queryParameterNames(uri)).containsExactlyInAnyOrder(
                "serviceKey", "MobileOS", "MobileApp", "contentId", "_type");
        assertThat(uri.getRawQuery()).contains("contentId=264329");
    }

    @Test
    @DisplayName("the Korean base, a foreign host or a blank base is refused without echoing the key")
    void englishBaseFailsClosed() {
        KtoKorServiceProperties korean = configured("https://apis.data.go.kr/B551011/KorService2");
        KtoKorServiceProperties foreign = configured("https://example.test/B551011/EngService2");
        KtoKorServiceProperties blank = configured("");
        KtoKorServiceProperties loopback = configured("http://127.0.0.1:18080/eng");

        assertThatThrownBy(() -> korean.requireEngConfigured(false))
                .isInstanceOf(KtoGatewayException.class).hasMessage("KTO_BASE_URL_NOT_APPROVED");
        assertThatThrownBy(() -> foreign.requireEngConfigured(false))
                .isInstanceOf(KtoGatewayException.class).hasMessage("KTO_BASE_URL_NOT_APPROVED");
        assertThatThrownBy(() -> blank.requireEngConfigured(false))
                .isInstanceOf(KtoGatewayException.class).hasMessage("KTO_NOT_CONFIGURED");
        assertThatThrownBy(() -> loopback.requireEngConfigured(false))
                .isInstanceOf(KtoGatewayException.class).hasMessage("KTO_BASE_URL_NOT_APPROVED");
        assertThat(loopback.engDetailCommonUri(new KtoPlaceRequest("264329", "76"), true).getHost())
                .isEqualTo("127.0.0.1");
        assertThat(korean.toString()).doesNotContain(CANARY);
    }

    private static KtoKorServiceProperties configured(String engBaseUrl) {
        KtoKorServiceProperties properties = new KtoKorServiceProperties();
        properties.setServiceKey(CANARY);
        properties.setBaseUrl("https://apis.data.go.kr/B551011/KorService2");
        properties.setEngBaseUrl(engBaseUrl);
        properties.setMobileApp("Nullnull");
        properties.setMobileOs("ETC");
        properties.setReleaseVersion("test-release");
        return properties;
    }

    private static Set<String> queryParameterNames(URI uri) {
        return Arrays.stream(uri.getRawQuery().split("&"))
                .map(parameter -> parameter.substring(0, parameter.indexOf('=')))
                .collect(Collectors.toSet());
    }
}
