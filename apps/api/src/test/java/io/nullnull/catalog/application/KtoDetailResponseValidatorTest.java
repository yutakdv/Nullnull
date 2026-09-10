package io.nullnull.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.shared.provider.ProviderResponseValidator;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BA-021 KTO detailCommon2 response validation")
class KtoDetailResponseValidatorTest {

    private final KtoDetailResponseValidator validator = new KtoDetailResponseValidator();
    private final KtoPlaceRequest request = new KtoPlaceRequest("126508", "12");

    @Test
    @DisplayName("BA-021-T1 retains only the normalized fields from one matching KTO detail response")
    void acceptsMatchingDetailAndExcludesOverview() {
        KtoDetailResponseValidator.Validation result = validate(valid("126508", "12", "37.566535", "126.978001"));

        assertThat(result.accepted()).isTrue();
        assertThat(result.snapshot().title()).isEqualTo("서울 테스트 관광지");
        assertThat(result.snapshot().latitude().toPlainString()).isEqualTo("37.566535");
        assertThat(result.snapshot().longitude().toPlainString()).isEqualTo("126.978001");
        assertThat(result.snapshot().payloadHash()).matches("[0-9a-f]{64}");
        assertThat(result.snapshot().staleAt()).isEqualTo(Instant.parse("2026-09-17T00:00:00Z"));
    }

    @Test
    @DisplayName("BA-021-T1 accepts the current one-item array envelope before normalizing it")
    void acceptsOneItemArray() {
        KtoDetailResponseValidator.Validation result = validate(validArray("126508", "12", "37.566535", "126.978001"));

        assertThat(result.accepted()).isTrue();
        assertThat(result.responseCount()).isOne();
        assertThat(result.snapshot().contentId()).isEqualTo("126508");
    }

    @Test
    @DisplayName("BA-021-T1 provider errors and mismatched identifiers are quarantined before persistence")
    void rejectsProviderErrorAndMismatchedItem() {
        KtoDetailResponseValidator.Validation providerError = validate("""
                {"response":{"header":{"resultCode":"22"},"body":{}}}
                """);
        KtoDetailResponseValidator.Validation gatewayError = validate("""
                {"responseTime":"2026-09-10T00:00:00Z","resultCode":"20","resultMsg":"redacted"}
                """);
        KtoDetailResponseValidator.Validation mismatch = validate(valid("999999", "12", "37.5", "126.9"));

        assertThat(providerError.verdict().outcome()).isEqualTo(ProviderResponseValidator.Outcome.PROVIDER_ERROR);
        assertThat(providerError.accepted()).isFalse();
        assertThat(gatewayError.verdict().outcome()).isEqualTo(ProviderResponseValidator.Outcome.PROVIDER_ERROR);
        assertThat(gatewayError.responseCount()).isZero();
        assertThat(mismatch.verdict().outcome()).isEqualTo(ProviderResponseValidator.Outcome.SCHEMA_DRIFT);
        assertThat(mismatch.snapshot()).isNull();
    }

    @Test
    @DisplayName("BA-021-T1 incomplete or out-of-range map coordinates cannot become a snapshot")
    void rejectsUnsafeCoordinates() {
        KtoDetailResponseValidator.Validation incomplete = validate(valid("126508", "12", "", "126.9"));
        KtoDetailResponseValidator.Validation outOfRange = validate(valid("126508", "12", "91", "126.9"));
        KtoDetailResponseValidator.Validation roundingOverflow =
                validate(valid("126508", "12", "90.0000004", "126.9"));

        assertThat(incomplete.verdict().outcome()).isEqualTo(ProviderResponseValidator.Outcome.RANGE);
        assertThat(outOfRange.verdict().outcome()).isEqualTo(ProviderResponseValidator.Outcome.RANGE);
        assertThat(roundingOverflow.verdict().outcome()).isEqualTo(ProviderResponseValidator.Outcome.RANGE);
    }

    private KtoDetailResponseValidator.Validation validate(String body) {
        return validator.validate(body.getBytes(java.nio.charset.StandardCharsets.UTF_8), request, 2, UUID.randomUUID(),
                Instant.parse("2026-09-10T00:00:00Z"), Duration.ofDays(7));
    }

    private static String valid(String contentId, String contentTypeId, String latitude, String longitude) {
        return validEnvelope(contentId, contentTypeId, latitude, longitude, "{", "}");
    }

    private static String validArray(String contentId, String contentTypeId, String latitude, String longitude) {
        return validEnvelope(contentId, contentTypeId, latitude, longitude, "[{", "}]");
    }

    private static String validEnvelope(String contentId, String contentTypeId, String latitude, String longitude,
            String itemStart, String itemEnd) {
        return """
                {"response":{"header":{"resultCode":"0000","resultMsg":"OK"},"body":{
                  "items":{"item":%s"contentid":"%s","contenttypeid":"%s","title":"서울 테스트 관광지",
                  "cat1":"A0101","areacode":"1","sigungucode":"1","addr1":"서울특별시 종로구",
                  "mapy":"%s","mapx":"%s","overview":"raw-provider-body-must-not-persist"%s},
                  "numOfRows":1,"pageNo":1,"totalCount":1}}}
                """.formatted(itemStart, contentId, contentTypeId, latitude, longitude, itemEnd);
    }
}
