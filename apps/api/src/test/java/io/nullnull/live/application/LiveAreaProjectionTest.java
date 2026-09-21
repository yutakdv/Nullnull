package io.nullnull.live.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.crowd.application.CrowdProvenanceProjection.CrowdMetric;
import io.nullnull.crowd.application.CrowdProvenanceProjection.DataProvenance;
import io.nullnull.crowd.domain.SourceState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BA-091 live area projection")
class LiveAreaProjectionTest {

    private static final Instant NOW = Instant.parse("2026-09-20T06:20:00Z");
    private final LiveAreaProjection projection = new LiveAreaProjection();

    private static CrowdMetric metric(SourceState state) {
        return CrowdMetric.of(state, null, null, null, "서울 실시간 인구 혼잡도",
                new DataProvenance("SEOUL_CITYDATA", "서울시 실시간 도시데이터", 1L, state,
                        Instant.parse("2026-09-20T06:15:00Z"), null, "FRESH",
                        Instant.parse("2026-09-20T06:15:05Z"), Instant.parse("2026-09-20T06:25:00Z"),
                        null, "공공누리 제1유형", "https://data.seoul.go.kr/x", "https://www.kogl.or.kr/x",
                        "출처: 서울특별시", "출처: 서울특별시", "서울 주요 장소의 실시간 인구 혼잡도 수준",
                        "seoul-citydata-v1", List.of(), null, null, false, "QUALITATIVE_ONLY", null,
                        null, null, 5, "LIVE_AREA", "광화문·덕수궁", "AREA", false, null));
    }

    private static LiveAreaProjection.AreaRow area(String name, SourceState state,
            String latitude, String longitude) {
        return new LiveAreaProjection.AreaRow(UUID.randomUUID(), name,
                latitude == null ? null : new BigDecimal(latitude),
                longitude == null ? null : new BigDecimal(longitude),
                metric(state));
    }

    @Test
    @DisplayName("BA-091-T7 좌표를 모르는 구역은 centroid 를 null 로 내보낸다")
    void anAreaWithNoKnownCentroidShipsNull() {
        LiveAreaProjection.LiveAreaResultResponse result =
                projection.project(List.of(area("광화문·덕수궁", SourceState.LIVE, null, null)), NOW);

        // The server produces the null, rather than a fixture asserting one. Seoul publishes no
        // coordinate for an area - not in the response, not on the dataset page, not in the manual -
        // so the contract types this GeoPoint | null and the honest answer is the absence itself.
        // Inventing a plausible point here is what an earlier draft of the examples did, and ajv
        // could not tell: the schema only wanted numbers.
        assertThat(result.areas()).hasSize(1);
        assertThat(result.areas().get(0).centroid()).isNull();
        assertThat(result.areas().get(0).boundaryGeoJson()).isNull();
        // And the rest of the area is still there, so "null centroid" is not "empty area".
        assertThat(result.areas().get(0).name()).isEqualTo("광화문·덕수궁");
        assertThat(result.areas().get(0).crowd().state()).isEqualTo(SourceState.LIVE);
        assertThat(result.generatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("BA-091 a known centroid is passed through, so null is a value and not the only answer")
    void aKnownCentroidIsProjected() {
        // Without this the test above is satisfied by a projection that drops every centroid. Nothing
        // in this build supplies one yet; the path exists because the column will.
        LiveAreaProjection.LiveAreaResultResponse result =
                projection.project(List.of(area("어딘가", SourceState.LIVE, "37.571", "126.977")), NOW);
        assertThat(result.areas().get(0).centroid()).isNotNull();
        assertThat(result.areas().get(0).centroid().latitude()).isEqualByComparingTo("37.571");
        assertThat(result.areas().get(0).centroid().longitude()).isEqualByComparingTo("126.977");
    }

    @Test
    @DisplayName("BA-091 half a centroid is refused rather than shipped as a broken point")
    void oneCoordinateIsNotAPoint() {
        assertThatThrownBy(() -> area("반쪽", SourceState.LIVE, "37.571", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> area("반쪽", SourceState.LIVE, null, "126.977"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("BA-091 the page carries the weakest state on it, and an empty page is UNAVAILABLE")
    void thePageStateIsFoldedInOnePlace() {
        assertThat(projection.project(List.of(area("a", SourceState.LIVE, null, null),
                area("b", SourceState.STALE, null, null)), NOW).mode()).isEqualTo("STALE");
        assertThat(projection.project(List.of(), NOW).mode()).isEqualTo("UNAVAILABLE");
        assertThat(projection.project(null, NOW).mode()).isEqualTo("UNAVAILABLE");
        assertThat(projection.project(List.of(), NOW).areas()).isEmpty();
    }
}
