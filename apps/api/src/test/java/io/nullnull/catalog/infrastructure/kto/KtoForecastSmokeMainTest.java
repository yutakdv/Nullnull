package io.nullnull.catalog.infrastructure.kto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.nullnull.crowd.application.KtoCrowdForecastGateway;
import io.nullnull.crowd.application.KtoForecastSnapshotSet;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KtoForecastSmokeMainTest {

    @Test
    void requiresItsOwnExplicitApprovalBeforeTheSecondActualKtoOperation() {
        assertThatIllegalArgumentException().isThrownBy(() -> KtoForecastSmokeMain.requireApproval(Map.of()))
                .withMessage("NULLNULL_KTO_FORECAST_SMOKE_APPROVED must be true for an actual KTO forecast request");
    }

    @Test
    void printsOnlySafeForecastEvidence() {
        UUID placeId = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b94001");
        KtoForecastSnapshotSet set = new KtoForecastSnapshotSet(
                UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b94002"),
                UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b94003"), 2,
                "kto-tats-0123456789abcdef0123456789abcdef", "kto-tats-0123456789abcdef0123456789abcdef",
                "kto-tats-cnctr-rate-v4.1", "a".repeat(64), Instant.parse("2026-09-10T00:00:00Z"),
                Instant.parse("2026-09-11T00:00:00Z"), List.of(new KtoForecastSnapshotSet.ForecastPoint(
                        UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b94004"),
                        Instant.parse("2026-09-10T15:00:00Z"), BigDecimal.valueOf(42))));

        String output = KtoForecastSmokeMain.redactedEvidence(placeId,
                new KtoCrowdForecastGateway.RefreshResult(Optional.of(set)));

        assertThat(output).contains("KTO_FORECAST_SMOKE_OK", "source=KTO_CONCENTRATION_FORECAST", "coverage=1",
                "snapshotSetId=018f3f8e-9b67-7a21-8d31-31d315b94002", "payloadHash=")
                .doesNotContain("serviceKey", "tAtsNm", "rawBody");
    }
}
