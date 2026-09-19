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

        String output = KtoSmokeMain.redactedEvidence(snapshot, Instant.parse("2026-09-10T00:00:00Z"));

        assertThat(output).contains("KTO_SMOKE_OK", "contentId=126508", "contentTypeId=12",
                "collectorRunId=018f3f8e-9b67-7a21-8d31-31d315b94001", "payloadHash=")
                .doesNotContain("title-must-not-appear", "address-must-not-appear", "serviceKey", "overview");
    }

    @Test
    void aSnapshotFetchedByThisRunIsTheOnlyOneThatReadsAsACall() {
        // fetchedAt is the instant the gateway's call started, on the same clock the run read before calling.
        Instant callStartedAt = Instant.parse("2026-09-19T05:29:00Z");
        KtoPlaceSnapshot fetchedNow = snapshotFetchedAt(callStartedAt);
        KtoPlaceSnapshot fetchedLater = snapshotFetchedAt(callStartedAt.plusMillis(1));

        assertThat(KtoSmokeMain.redactedEvidence(fetchedNow, callStartedAt))
                .startsWith("KTO_SMOKE_OK ").endsWith(" called=true");
        assertThat(KtoSmokeMain.redactedEvidence(fetchedLater, callStartedAt))
                .startsWith("KTO_SMOKE_OK ").endsWith(" called=true");
    }

    @Test
    void aSnapshotStoredBeforeThisRunIsReportedAsCachedAndNeverAsAnOkLine() {
        // A snapshot another run stored an hour earlier: the operator must not turn it into this release's call.
        Instant callStartedAt = Instant.parse("2026-09-19T05:29:00Z");
        KtoPlaceSnapshot stored = snapshotFetchedAt(callStartedAt.minus(Duration.ofHours(1)));

        String output = KtoSmokeMain.redactedEvidence(stored, callStartedAt);

        assertThat(output).startsWith("KTO_SMOKE_CACHED ").endsWith(" called=false")
                .doesNotContain("KTO_SMOKE_OK", "called=true");
        assertThat(KtoSmokeMain.calledByThisRun(stored, callStartedAt)).isFalse();
    }

    @Test
    void aSnapshotThatOutlivesTheAskedInstantIsNotThisRunsCallEvenIfFetchedAfterItBegan() {
        // Only a stored row can outlive the instant the smoke asks for; a call of this run is stamped start + P7D.
        Instant callStartedAt = Instant.parse("2026-09-19T05:29:00Z");
        KtoPlaceSnapshot outlives = KtoPlaceSnapshot.accepted(4, UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b94001"),
                "126508", "12", "title", "A0101", "1", "1", "address", new BigDecimal("37.579617"),
                new BigDecimal("126.977041"), callStartedAt, KtoSmokeMain.FORCE_HORIZON.plusDays(1));

        assertThat(KtoSmokeMain.calledByThisRun(outlives, callStartedAt)).isFalse();
        assertThat(KtoSmokeMain.redactedEvidence(outlives, callStartedAt)).startsWith("KTO_SMOKE_CACHED ");
    }

    @Test
    void aCachedRunEndsWithItsOwnCodeRatherThanTheCatchAll() {
        Instant callStartedAt = Instant.parse("2026-09-19T05:29:00Z");
        KtoSmokeMain.Call cached = new KtoSmokeMain.Call(snapshotFetchedAt(callStartedAt.minusSeconds(1)), callStartedAt);
        KtoSmokeMain.Call called = new KtoSmokeMain.Call(snapshotFetchedAt(callStartedAt), callStartedAt);

        assertThatIllegalStateException().isThrownBy(() -> KtoSmokeMain.refuseUnlessCalled(cached))
                .withMessage("KTO smoke failed: CACHED_SNAPSHOT");
        KtoSmokeMain.refuseUnlessCalled(called);
    }

    @Test
    void theForcedHorizonOutlivesEverySnapshotTheRegistryKeeps() {
        // KorService2's stale_after is P7D (V007); a horizon inside it would let a stored snapshot answer.
        assertThat(KtoSmokeMain.FORCE_HORIZON).isGreaterThan(Duration.ofDays(7));
    }

    private static KtoPlaceSnapshot snapshotFetchedAt(Instant fetchedAt) {
        return KtoPlaceSnapshot.accepted(4, UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b94001"),
                "126508", "12", "title", "A0101", "1", "1", "address",
                new BigDecimal("37.579617"), new BigDecimal("126.977041"), fetchedAt, Duration.ofDays(7));
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
