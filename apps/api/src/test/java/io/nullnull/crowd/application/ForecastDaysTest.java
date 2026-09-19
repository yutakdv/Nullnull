package io.nullnull.crowd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.crowd.domain.ComparisonScope;
import io.nullnull.crowd.domain.SourceState;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("a forecast point's date is the KST date KTO filed it under")
class ForecastDaysTest {

    private static final LocalDate DAY = LocalDate.parse("2026-10-04");

    @Test
    @DisplayName("a date's point is at its Seoul midnight, and the date's last instant is one microsecond before the next")
    void aDateIsItsSeoulMidnightUpToTheNextOne() {
        assertThat(ForecastDays.startOf(DAY)).isEqualTo(Instant.parse("2026-10-03T15:00:00Z"));
        assertThat(ForecastDays.endOf(DAY)).isEqualTo(Instant.parse("2026-10-04T14:59:59.999999Z"));
    }

    @Test
    @DisplayName("the date read back from a point is the date it was filed for, whatever zone the reader is in")
    void aPointReadsBackAsItsOwnDate() {
        assertThat(ForecastDays.dayOf(point(KtoForecastSnapshotSet.SOURCE_CODE, ForecastDays.startOf(DAY))))
                .isEqualTo(DAY);
    }

    @Test
    @DisplayName("a point from any other forecast source is refused rather than dated as KST")
    void anotherSourcesPointIsRefused() {
        assertThatThrownBy(() -> ForecastDays.dayOf(point("SOME_OTHER_FORECAST", ForecastDays.startOf(DAY))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SOME_OTHER_FORECAST");
    }

    private static CrowdForecastQuery.Snapshot point(String sourceCode, Instant targetAt) {
        return new CrowdForecastQuery.Snapshot(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(),
                new CrowdForecastQuery.SourceDescriptor(sourceCode, "KTO", 1L, "OGL", "https://kto",
                        "https://kto/license", "한국관광공사", "relative concentration"),
                SourceState.FORECAST, null, targetAt, targetAt, targetAt.plusSeconds(86_400),
                KtoForecastSnapshotSet.METRIC_CODE, new BigDecimal("50"), "relative-index", null, null, Set.of(),
                "issue", "group", "v1", null, ComparisonScope.PLACE, "place", "DIRECT", false, false);
    }
}
