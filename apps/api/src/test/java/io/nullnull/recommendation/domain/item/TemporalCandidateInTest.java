package io.nullnull.recommendation.domain.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.crowd.domain.ComparisonReasonCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The compared metric travels as its published code, and the service declares that code as
 * {@code minLength: 1, maxLength: 64}. A code outside those bounds is a hydration bug on this side,
 * so it fails here rather than coming back from the service as an opaque 422.
 */
@DisplayName("TemporalCandidateIn metric code bounds")
class TemporalCandidateInTest {

    static final UUID ID = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93a01");
    static final LocalDate D12 = LocalDate.of(2026, 9, 12);

    @Test
    void aMetricCodeOfExactlyTheContractLengthIsAccepted() {
        TemporalCandidateIn candidate = candidate("m".repeat(TemporalCandidateIn.MAX_METRIC_CODE));

        assertThat(candidate.metricCode()).hasSize(TemporalCandidateIn.MAX_METRIC_CODE);
    }

    @Test
    void aMetricCodeOneCharacterOverTheContractLengthIsRefused() {
        assertThatThrownBy(() -> candidate("m".repeat(TemporalCandidateIn.MAX_METRIC_CODE + 1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("metricCode must be at most");
    }

    @Test
    void aMetricCodeThatNamesNoMetricIsRefused() {
        // minLength 1 on the service side. An empty or whitespace-only code names no published metric,
        // and a comparison whose metric cannot be named is exactly what §9 forbids.
        assertThatThrownBy(() -> candidate("")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metricCode must not be blank");
        assertThatThrownBy(() -> candidate("   ")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metricCode must not be blank");
    }

    private static TemporalCandidateIn candidate(String metricCode) {
        return new TemporalCandidateIn(ID, D12, LocalTime.of(9, 0), TemporalCandidateIn.ForecastResolution.HOUR,
                new BigDecimal("80"), new BigDecimal("40"), metricCode, true,
                ComparisonReasonCode.SAME_METRIC_AND_ISSUE, ID, ID);
    }
}
