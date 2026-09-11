package io.nullnull.catalog.infrastructure.kto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.nullnull.catalog.domain.KtoPlaceSnapshot;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KtoSmokeMainTest {

    @TempDir Path temporaryDirectory;

    @Test
    void requiresExplicitApprovalBeforeItCanConstructAProviderRequest() {
        assertThatIllegalArgumentException().isThrownBy(() -> KtoSmokeMain.SmokeRequest.from(Map.of(
                "NULLNULL_KTO_SMOKE_CONTENT_ID", "126508",
                "NULLNULL_KTO_SMOKE_CONTENT_TYPE_ID", "12")))
                .withMessage("NULLNULL_KTO_SMOKE_APPROVED must be true for an actual KTO request");
    }

    @Test
    void acceptsOnlyStableNumericKtoIdentifiers() {
        assertThatIllegalArgumentException().isThrownBy(() -> KtoSmokeMain.SmokeRequest.from(Map.of(
                "NULLNULL_KTO_SMOKE_APPROVED", "true",
                "NULLNULL_KTO_SMOKE_CONTENT_ID", "search query",
                "NULLNULL_KTO_SMOKE_CONTENT_TYPE_ID", "12")))
                .withMessage("NULLNULL_KTO_SMOKE_CONTENT_ID must be a stable numeric KTO identifier");
    }

    @Test
    void refusesProductionEvenWhenTheOperatorFlagIsPresent() {
        KtoSmokeMain.SmokeRequest request = KtoSmokeMain.SmokeRequest.from(Map.of(
                "NULLNULL_KTO_SMOKE_APPROVED", "true",
                "NULLNULL_KTO_SMOKE_CONTENT_ID", "126508",
                "NULLNULL_KTO_SMOKE_CONTENT_TYPE_ID", "12"));

        assertThatIllegalStateException().isThrownBy(() -> request.requirePermittedEnvironment("production"))
                .withMessage("KTO smoke is permitted only in local or staging");
    }

    @Test
    void printsOnlySafeEvidenceFields() {
        KtoPlaceSnapshot snapshot = KtoPlaceSnapshot.accepted(2, UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b94001"),
                "126508", "12", "title-must-not-appear", "A0101", "1", "1", "address-must-not-appear",
                new BigDecimal("37.579617"), new BigDecimal("126.977041"), Instant.parse("2026-09-10T00:00:00Z"),
                Duration.ofDays(7));

        String output = KtoSmokeMain.redactedEvidence(snapshot);

        assertThat(output).contains("KTO_SMOKE_OK", "contentId=126508", "contentTypeId=12",
                "collectorRunId=018f3f8e-9b67-7a21-8d31-31d315b94001", "payloadHash=")
                .doesNotContain("title-must-not-appear", "address-must-not-appear", "serviceKey", "overview");
    }

    @Test
    void localDotenvContributesOnlyNonblankAllowlistedSettings() throws Exception {
        Path dotenv = temporaryDirectory.resolve(".env.local");
        Files.writeString(dotenv, """
                KTO_SERVICE_KEY='test-decoding-key'
                KTO_BASE_URL=https://apis.data.go.kr/B551011/KorService2
                KTO_FORECAST_BASE_URL=https://apis.data.go.kr/B551011/TatsCnctrRateService
                APP_COOKIE_SECURE=
                SPRING_DATASOURCE_PASSWORD=
                UNRELATED_VALUE=must-not-load
                """);

        Map<String, String> values = KtoSmokeEnvironment.load(Map.of(), dotenv);
        Map<String, Object> gateway = KtoSmokeEnvironment.gatewayProperties(values);
        Map<String, Object> runtime = KtoSmokeEnvironment.runtimeProperties(values);

        assertThat(gateway).containsKeys("nullnull.kto.service-key", "nullnull.kto.base-url",
                "nullnull.kto.forecast-base-url")
                .doesNotContainKeys("APP_COOKIE_SECURE", "unrelated.value", "spring.datasource.password");
        assertThat(runtime).doesNotContainKey("spring.datasource.password");
    }

    @Test
    void normalizesTheDocumentedEnvironmentVocabularyForTheOperatorCommand() {
        Map<String, String> values = Map.of("NULLNULL_ENV", " STAGING ");

        assertThat(KtoSmokeEnvironment.environment(values)).isEqualTo("staging");
        assertThat(KtoSmokeEnvironment.gatewayProperties(values))
                .containsEntry("nullnull.env", "staging");
    }
}
