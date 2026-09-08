package io.nullnull.recommendation.domain.explanation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REC-LLM-01, request side: an explanation request that breaks §9.1 never reaches the service. The
 * same rules run there, so this only keeps a hydration bug from travelling as a 422.
 */
@DisplayName("REC-LLM-01 explanation request")
class ExplanationRenderRequestTest {

    static final LocalDate D12 = LocalDate.of(2026, 9, 12);

    static ExplanationRenderRequest request(String locale, BigDecimal before, BigDecimal after) {
        return new ExplanationRenderRequest(locale, "Gyeongbokgung", D12, LocalTime.of(10, 0), D12, LocalTime.of(12, 0),
                before, after, "relative concentration index", "Source: Korea Tourism Organization", "issue-1");
    }

    @Test
    void theDeltaIsTheOnlyDerivedNumberAnExplanationMayCarry() {
        assertThat(request("en", new BigDecimal("80.5"), new BigDecimal("60")).pointDelta())
                .isEqualByComparingTo("20.5");
    }

    @Test
    void aChangeThatDoesNotLowerTheMetricIsNeverExplained() {
        assertThatThrownBy(() -> request("ko", new BigDecimal("60"), new BigDecimal("60")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("verified improvement");
        assertThatThrownBy(() -> request("ko", new BigDecimal("60"), new BigDecimal("80")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("verified improvement");
    }

    @Test
    void onlyTheTwoShippedLocalesAreExplained() {
        assertThatThrownBy(() -> request("ja", new BigDecimal("80"), new BigDecimal("60")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ko and en");
    }

    @Test
    void anApprovedStringThatWouldBreakTheSentenceInTwoIsRefused() {
        assertThatThrownBy(() -> new ExplanationRenderRequest("ko", "\uacbd\ubcf5\n\uad81", D12, null, D12, null,
                new BigDecimal("80"), new BigDecimal("60"), "index", "Source", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("placeName must not contain a control");
        assertThatThrownBy(() -> new ExplanationRenderRequest("en", "Gyeongbokgung", D12, null, D12, null,
                new BigDecimal("80"), new BigDecimal("60"), "index\tv2", "Source", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("metricLabel must not contain a control");
        assertThatThrownBy(() -> new ExplanationRenderRequest("en", "Gyeongbokgung", D12, null, D12, null,
                new BigDecimal("80"), new BigDecimal("60"), "index", "Source\r", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("attribution must not contain a control");
        assertThatThrownBy(() -> new ExplanationRenderRequest("en", "Gyeongbokgung", D12, null, D12, null,
                new BigDecimal("80"), new BigDecimal("60"), "index", "Source", "issue\n1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forecastIssueId must not contain a control");
    }

    @Test
    void theApprovedStringsStayInsideTheirBounds() {
        assertThatThrownBy(() -> new ExplanationRenderRequest("en", " ", D12, null, D12, null, new BigDecimal("80"),
                new BigDecimal("60"), "index", "Source", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("placeName must not be blank");
        assertThatThrownBy(() -> new ExplanationRenderRequest("en", "Gyeongbokgung", D12, null, D12, null,
                new BigDecimal("80"), new BigDecimal("60"), "i".repeat(ExplanationRenderRequest.MAX_METRIC_LABEL + 1),
                "Source", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("metricLabel must be at most");
        assertThatThrownBy(() -> new ExplanationRenderRequest("en", "Gyeongbokgung", D12, null, D12, null,
                new BigDecimal("80"), new BigDecimal("60"), "index",
                "S".repeat(ExplanationRenderRequest.MAX_ATTRIBUTION + 1), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("attribution must be at most");
        assertThatThrownBy(() -> new ExplanationRenderRequest("en", "Gyeongbokgung", D12, null, D12, null,
                new BigDecimal("80"), new BigDecimal("60"), "index", "Source",
                "i".repeat(ExplanationRenderRequest.MAX_FORECAST_ISSUE_ID + 1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("forecastIssueId must be at most");
    }
}
