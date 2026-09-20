package io.nullnull.live.infrastructure.curation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class LiveMappingImportMainTest {

    private static final UUID PLACE = UUID.fromString("00000000-0000-4000-8000-000000000001");

    @Test
    void approvedJsonKeepsEachReviewedFieldTogether() {
        var plan = LiveMappingImportMain.parse("""
                {"mappings":[{"placeId":"00000000-0000-4000-8000-000000000001",
                  "areaName":"서울숲","mappingType":"AREA_FALLBACK","confidence":0.75,
                  "fallbackUsed":true,"verifiedAt":"2026-09-20T06:00:00Z",
                  "evidenceUrl":"https://data.seoul.go.kr/dataList/OA-21285/F/1/datasetView.do"}]}
                """);

        assertThat(plan.mappings()).hasSize(1);
        assertThat(plan.mappings().get(0).placeId()).isEqualTo(PLACE);
        assertThat(plan.mappings().get(0).areaName()).isEqualTo("서울숲");
        assertThat(plan.mappings().get(0).fallbackUsed()).isTrue();
    }

    @Test
    void mismatchedFallbackCannotBeImported() {
        assertThatThrownBy(() -> LiveMappingImportMain.parse("""
                {"mappings":[{"placeId":"00000000-0000-4000-8000-000000000001",
                  "areaName":"서울숲","mappingType":"AREA","confidence":0.75,
                  "fallbackUsed":true,"verifiedAt":"2026-09-20T06:00:00Z",
                  "evidenceUrl":"https://data.seoul.go.kr/example"}]}
                """)).isInstanceOf(IllegalArgumentException.class);
    }
}
