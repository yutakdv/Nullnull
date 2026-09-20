package io.nullnull.live.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.crowd.application.CrowdProvenanceProjection.CrowdMetric;
import io.nullnull.crowd.application.CrowdProvenanceProjection.DataProvenance;
import io.nullnull.crowd.domain.SourceState;
import io.nullnull.live.domain.LiveCoverage;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What changes, and what must not, when an area's reading is shown under a place. */
@DisplayName("BA-091 an area reading carried by a place")
class LivePlaceProjectionTest {

    private static final UUID AREA = UUID.randomUUID();
    private static final Instant OBSERVED = Instant.parse("2026-09-20T12:00:00Z");

    private final LivePlaceProjection projection = new LivePlaceProjection();

    @Test
    @DisplayName("BA-091-T11 장소에 붙인 구역 값은 그 mapping 의 mappingType 과 fallbackUsed 로 나간다")
    void theMappingDecidesHowThePlaceCarriesTheReading() {
        // The stored row says DIRECT because the reading is directly about the AREA - which is what
        // JdbcSeoulLiveSnapshotStore writes and why. Under a place that value would claim this place
        // was measured.
        CrowdMetric areaReading = reading("DIRECT", false, null);
        LiveCoverage fallback = new LiveCoverage("AREA_FALLBACK", true, new BigDecimal("0.4200"), AREA);

        DataProvenance attached = projection.attachedTo(areaReading, fallback).provenance();

        assertThat(attached.mappingType()).isEqualTo("AREA_FALLBACK");
        assertThat(attached.fallbackUsed()).isTrue();
    }

    @Test
    @DisplayName("BA-091-T12 장소에 붙인 구역 값의 confidence 는 coverage 의 것이고 source 의 것이 아니다")
    void theConfidenceIsTheMappingsRatherThanTheMeasurements() {
        // A SEPARATE ID because it has a separate producer, which the mutation measurement showed:
        // removing the mapping rewrite reddens T11 and leaves this green, and passing the source's
        // confidence through does the reverse. SOURCE_CATALOG §5 names all three in one sentence,
        // but one sentence is not one mechanism.
        //
        // The source carries a confidence here ON PURPOSE. A null would let a projection that simply
        // forgot the field pass: null and null are equal. This one has to be overwritten.
        CrowdMetric areaReading = reading("DIRECT", false, new BigDecimal("0.1000"));
        LiveCoverage coverage = new LiveCoverage("AREA", false, new BigDecimal("0.9000"), AREA);

        DataProvenance attached = projection.attachedTo(areaReading, coverage).provenance();

        // The provider publishes a stage and no interval, so the uncertainty that actually stands
        // between this number and this place is how sure the review is that the place sits in that
        // area. That is the number §5 asks for beside mappingType.
        assertThat(attached.confidence()).isEqualByComparingTo("0.9000");
    }

    @Test
    @DisplayName("BA-091 구역 관측 자신을 설명하는 필드는 장소 밑에서도 그대로다")
    void theFieldsThatDescribeTheMeasurementAreNotRewritten() {
        // Rewriting scope would erase the single field that says this number is about a region,
        // which is the fact mappingType sits beside rather than replaces.
        DataProvenance attached = projection.attachedTo(reading("DIRECT", false, null),
                new LiveCoverage("AREA", false, new BigDecimal("0.9000"), AREA)).provenance();

        assertThat(attached.scope()).isEqualTo("LIVE_AREA");
        assertThat(attached.scopeLabel()).isEqualTo("광화문·덕수궁");
        assertThat(attached.observedAt()).isEqualTo(OBSERVED);
        assertThat(attached.freshness()).isEqualTo("FRESH");
        assertThat(attached.qualityFlags()).containsExactly("PROVIDER_INCIDENT");
    }

    @Test
    @DisplayName("BA-091 AREA 매핑은 fallback 없이 그대로 AREA 로 나간다")
    void aDirectAreaMappingIsNotReportedAsAFallback() {
        // The companion case, and it is what makes the one above non-vacuous: a projection that
        // hard-coded AREA_FALLBACK would satisfy that test and be wrong here.
        LiveCoverage exact = new LiveCoverage("AREA", false, new BigDecimal("0.9000"), AREA);

        DataProvenance attached = projection.attachedTo(reading("DIRECT", false, null), exact).provenance();

        assertThat(attached.mappingType()).isEqualTo("AREA");
        assertThat(attached.fallbackUsed()).isFalse();
    }

    @Test
    @DisplayName("BA-091 구역 값을 든 장소는 비교 가능으로 나가지 않는다")
    void anAreaReadingUnderAPlaceIsNeverComparable() {
        // Invariant 8. Today the stored row cannot be eligible - an area snapshot has place_id NULL
        // and CrowdProvenanceProjection's completeness check refuses it on that - but that is a
        // three-class chain, and this is the class that would ship the result of it moving. Driving
        // the guard directly is the only way to see it fire: no fixture can make the chain produce
        // an eligible area reading.
        CrowdMetric eligible = reading("DIRECT", true, null);

        assertThatThrownBy(() -> projection.attachedTo(eligible,
                new LiveCoverage("AREA", false, BigDecimal.ONE, AREA)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never comparable");
    }

    @Test
    @DisplayName("BA-091 매핑이 없는 장소에는 붙일 값 자체가 없다")
    void anUncoveredPlaceIsNotGivenAValueAnyway() {
        // NONE is a decision, not a gap, so "attach it anyway" is not a fallback this class offers.
        assertThatThrownBy(() -> projection.attachedTo(reading("DIRECT", false, null), LiveCoverage.none()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uncovered");
    }

    private static CrowdMetric reading(String mappingType, boolean comparisonEligible, BigDecimal confidence) {
        DataProvenance provenance = new DataProvenance("SEOUL_CITYDATA", "서울시 실시간 도시데이터", 2L,
                SourceState.LIVE, OBSERVED, null, "FRESH", OBSERVED.plusSeconds(5),
                OBSERVED.plusSeconds(305), confidence, "출처 표시", "https://data.seoul.go.kr",
                "https://data.seoul.go.kr/license", "서울열린데이터광장", null, "실시간 인구 혼잡도 단계",
                "seoul-citydata-v8.5", List.of("PROVIDER_INCIDENT"), null, null, comparisonEligible, null, null,
                UUID.randomUUID(), UUID.randomUUID(), null, "LIVE_AREA", "광화문·덕수궁", mappingType, false,
                UUID.randomUUID());
        return new CrowdMetric(SourceState.LIVE, null, null, "3", "실시간 인구 혼잡도 단계", provenance);
    }
}
