package io.nullnull.catalog.infrastructure.kto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.catalog.application.KtoGatewayException;
import io.nullnull.catalog.application.KtoPlaceRequest;
import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BA-021 KTO runtime settings")
class KtoKorServicePropertiesTest {

    @Test
    @DisplayName("BA-021-T1 accepts only the exact official KorService2 base outside tests")
    void productionBaseIsExactAndSecretStaysOutOfToString() {
        String canary = "fake-secret-key-never-retain";
        KtoKorServiceProperties properties = configured(canary,
                "https://apis.data.go.kr/B551011/KorService2");

        URI uri = properties.detailCommonUri(new KtoPlaceRequest("126508", "12"), false);

        assertThat(uri.getScheme()).isEqualTo("https");
        assertThat(uri.getHost()).isEqualTo("apis.data.go.kr");
        assertThat(uri.getRawPath()).isEqualTo("/B551011/KorService2/detailCommon2");
        assertThat(queryParameterNames(uri)).containsExactlyInAnyOrder(
                "serviceKey", "MobileOS", "MobileApp", "contentId", "_type");
        assertThat(queryParameterNames(uri)).doesNotContain(
                "contentTypeId", "defaultYN", "firstImageYN", "areacodeYN", "catcodeYN", "addrinfoYN", "mapinfoYN",
                "overviewYN");
        assertThat(properties).hasToString("KtoKorServiceProperties[configured=true, baseConfigured=true, "
                + "forecastBaseConfigured=false, contestProfile=NONE]");
        assertThat(properties.toString()).doesNotContain(canary);
    }

    @Test
    @DisplayName("BA-023-T1 pins the concentration service to its own exact official path")
    void forecastBaseAndQueryAreReviewedSeparatelyFromKorService2() {
        KtoKorServiceProperties properties = configured("fake-secret-key-never-retain",
                "https://apis.data.go.kr/B551011/KorService2");
        properties.setForecastBaseUrl("https://apis.data.go.kr/B551011/TatsCnctrRateService");

        URI uri = properties.concentrationForecastUri("11", "11110", "경복궁", false);

        assertThat(uri.getRawPath()).isEqualTo("/B551011/TatsCnctrRateService/tatsCnctrRatedList");
        assertThat(queryParameterNames(uri)).containsExactlyInAnyOrder(
                "serviceKey", "pageNo", "numOfRows", "MobileOS", "MobileApp", "areaCd", "signguCd", "tAtsNm",
                "_type");
        // #109: this builder is handed an already-joined signguCd and passes it through unchanged.
        // Whether the CALLER joined it is asserted in KtoCrowdForecastGatewayIT, against what the
        // adapter actually sent - this layer cannot tell "110" from a joined code.
        assertThat(uri.getRawQuery()).contains("areaCd=11").contains("signguCd=11110");
        KtoKorServiceProperties wrongPath = configured("fake-secret-key-never-retain",
                "https://apis.data.go.kr/B551011/KorService2");
        wrongPath.setForecastBaseUrl("https://apis.data.go.kr/B551011/KorService2");
        assertThatThrownBy(() -> wrongPath.requireForecastConfigured(false))
                .isInstanceOf(KtoGatewayException.class)
                .hasMessage("KTO_BASE_URL_NOT_APPROVED");
    }

    @Test
    @DisplayName("BA-021-T1 custom hosts and a missing key fail closed with no secret echo")
    void rejectsNonOfficialRuntimeConfiguration() {
        KtoKorServiceProperties host = configured("fake-secret-key-never-retain", "https://example.test/KorService2");
        KtoKorServiceProperties query = configured("fake-secret-key-never-retain",
                "https://apis.data.go.kr/B551011/KorService2?unexpected=true");
        KtoKorServiceProperties missingKey = configured("", "https://apis.data.go.kr/B551011/KorService2");

        assertThatThrownBy(() -> host.requireConfigured(false))
                .isInstanceOf(KtoGatewayException.class)
                .hasMessage("KTO_BASE_URL_NOT_APPROVED");
        assertThatThrownBy(() -> query.requireConfigured(false))
                .isInstanceOf(KtoGatewayException.class)
                .hasMessage("KTO_BASE_URL_NOT_APPROVED");
        assertThatThrownBy(() -> missingKey.requireConfigured(false))
                .isInstanceOf(KtoGatewayException.class)
                .hasMessage("KTO_NOT_CONFIGURED");
    }

    private static KtoKorServiceProperties configured(String key, String baseUrl) {
        KtoKorServiceProperties properties = new KtoKorServiceProperties();
        properties.setServiceKey(key);
        properties.setBaseUrl(baseUrl);
        properties.setMobileApp("Nullnull");
        properties.setMobileOs("ETC");
        properties.setReleaseVersion("test-release");
        return properties;
    }

    private static Set<String> queryParameterNames(URI uri) {
        return Arrays.stream(uri.getRawQuery().split("&"))
                .map(parameter -> parameter.substring(0, parameter.indexOf('=')))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
