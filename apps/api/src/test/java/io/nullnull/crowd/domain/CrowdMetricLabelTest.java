package io.nullnull.crowd.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The label is quoted from the source catalog, so the catalog is what this checks it against.
 *
 * <p>Pinning it to the document rather than to a literal is the point: this string reaches a
 * traveller inside a sentence, and the document is where the project decided what the metric may be
 * called. Someone shortening it to 혼잡도 has to change the catalog too, which is where the reason
 * not to lives.
 */
@DisplayName("crowd metric labels")
class CrowdMetricLabelTest {

    @Test
    @DisplayName("the Korean label is the name the source catalog gives the metric")
    void theLabelComesFromTheCatalog() {
        String catalog = sourceCatalog();

        // Anchored to the line that says what the metric IS, not to the document as a whole. A plain
        // contains() passed when the label was changed to 혼잡도 - because the catalog does contain
        // that word, in the sentence forbidding it ("값 80은 … 서울 혼잡도 4단계의 특정 단계가
        // 아니다"). A pin that accepts a word BECAUSE the document warns against it is backwards, and
        // it is the shape a whole-file search will always have.
        assertThat(catalog).contains("value = 원본 "
                + CrowdMetricLabel.of(CrowdMetricLabel.CONCENTRATION_INDEX, "ko"));
        // And the metric code the catalog publishes as the unit is the key used here.
        assertThat(catalog).contains("unit = " + CrowdMetricLabel.CONCENTRATION_INDEX);
    }

    @Test
    @DisplayName("the names the catalog warns against are not what we publish")
    void theForbiddenReadingsAreNotUsed() {
        // SOURCE_CATALOG §3: "인원·수용률·시간대 예측이 아니다". These are the readings that sentence
        // exists to prevent, and metricLabel is substituted into the explanation verbatim - so using
        // one of them would not merely be loose wording, it would publish the misreading.
        assertThat(CrowdMetricLabel.published()).allSatisfy(metric -> {
            assertThat(CrowdMetricLabel.of(metric, "ko")).isNotEqualTo("혼잡도").doesNotContain("방문자");
            assertThat(CrowdMetricLabel.of(metric, "en")).isNotEqualToIgnoringCase("crowd level");
        });
    }

    @Test
    @DisplayName("both languages exist for every published metric, and nothing else resolves")
    void everyPublishedMetricHasBothLanguages() {
        assertThat(CrowdMetricLabel.published()).isNotEmpty();
        assertThat(CrowdMetricLabel.published()).allSatisfy(metric -> {
            assertThat(CrowdMetricLabel.of(metric, "ko")).isNotBlank();
            assertThat(CrowdMetricLabel.of(metric, "en")).isNotBlank();
        });

        // A metric with no approved name fails rather than defaulting: a sentence naming a metric by
        // a name nobody approved is worse than no sentence.
        assertThatThrownBy(() -> CrowdMetricLabel.of("SOME_FUTURE_METRIC", "ko"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CrowdMetricLabel.of(CrowdMetricLabel.CONCENTRATION_INDEX, "ja"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String sourceCatalog() {
        try {
            return Files.readString(Path.of("../../docs/data/SOURCE_CATALOG.md"), StandardCharsets.UTF_8);
        } catch (java.io.IOException missing) {
            throw new IllegalStateException("cannot read the source catalog", missing);
        }
    }
}
