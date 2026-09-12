package io.nullnull.catalog.infrastructure.kto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.nullnull.catalog.domain.CatalogPlace;
import io.nullnull.catalog.domain.CatalogPlaceStatus;
import io.nullnull.catalog.domain.KtoPlaceSnapshot;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KtoCanonicalIngestMainTest {

    private static final Instant FETCHED_AT = Instant.parse("2026-09-10T00:00:00Z");

    @Test
    void acceptsOnlyStableNumericKtoIdentifiers() {
        assertThatIllegalArgumentException().isThrownBy(() -> KtoCanonicalIngestMain.request(Map.of(
                "NULLNULL_KTO_INGEST_CONTENT_ID", "경복궁",
                "NULLNULL_KTO_INGEST_CONTENT_TYPE_ID", "12")))
                .withMessage("NULLNULL_KTO_INGEST_CONTENT_ID must be a stable numeric KTO identifier");
    }

    @Test
    void refusesToRunWithoutBeingToldWhichSnapshotToMap() {
        assertThatIllegalArgumentException().isThrownBy(() -> KtoCanonicalIngestMain.request(Map.of()))
                .withMessage("NULLNULL_KTO_INGEST_CONTENT_ID must be a stable numeric KTO identifier");
    }

    @Test
    void refusesProduction() {
        assertThatIllegalStateException()
                .isThrownBy(() -> KtoCanonicalIngestMain.requirePermittedEnvironment("production"))
                .withMessage("KTO canonical ingest is permitted only in local or staging");
    }

    /**
     * The place ID is the whole point of the output - ktoForecastSmoke reads it as
     * NULLNULL_KTO_FORECAST_SMOKE_PLACE_ID - but the provider's title and address travelled through
     * this mapping too, and CLAUDE.md keeps provider text out of logs and artifacts.
     */
    @Test
    void printsThePlaceIdAndNoProviderText() {
        UUID placeId = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b94002");
        KtoPlaceSnapshot snapshot = KtoPlaceSnapshot.accepted(4,
                UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b94001"), "126508", "12",
                "title-must-not-appear", "AC01", "11", "11110", "address-must-not-appear",
                new BigDecimal("37.579617"), new BigDecimal("126.977041"), FETCHED_AT, Duration.ofDays(7));
        CatalogPlace place = new CatalogPlace(placeId, null, "title-must-not-appear", "AC01",
                new BigDecimal("37.579617"), new BigDecimal("126.977041"), "11", CatalogPlaceStatus.ACTIVE,
                FETCHED_AT, FETCHED_AT);

        String output = KtoCanonicalIngestMain.redactedEvidence(place, snapshot);

        assertThat(output).contains("KTO_CANONICAL_INGEST_OK", "placeId=" + placeId, "contentId=126508",
                "contentTypeId=12", "sourceRegistryVersion=4");
        assertThat(output).doesNotContain("title-must-not-appear", "address-must-not-appear",
                "37.579617", "126.977041");
    }
}
