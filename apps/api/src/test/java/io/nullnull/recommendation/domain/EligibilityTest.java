package io.nullnull.recommendation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class EligibilityTest {

    private static final Reason LOCKED = Reason.of("DATE_LOCKED", "date lock present");
    private static final Reason NO_HOURS = Reason.of("OPENING_HOURS_UNKNOWN", "opening hours unverified");

    @Test
    void ineligibleAndUnknownRequireReasons() {
        assertThatThrownBy(() -> new Eligibility(EligibilityState.INELIGIBLE, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Eligibility(EligibilityState.UNKNOWN, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Eligibility.eligible().reasons()).isEmpty();
    }

    @Test
    void unknownIsNeverPromotedToEligible() {
        Eligibility combined = Eligibility.eligible().and(Eligibility.unknown(NO_HOURS));
        assertThat(combined.state()).isEqualTo(EligibilityState.UNKNOWN);
        assertThat(combined.isEligible()).isFalse();
    }

    @Test
    void ineligibleDominatesUnknown() {
        Eligibility combined = Eligibility.unknown(NO_HOURS).and(Eligibility.ineligible(LOCKED));
        assertThat(combined.state()).isEqualTo(EligibilityState.INELIGIBLE);
        assertThat(combined.reasons()).containsExactly(NO_HOURS, LOCKED);
    }

    @Test
    void reasonCodesAreStableUpperSnakeCase() {
        assertThatThrownBy(() -> Reason.of("dateLocked", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Reason.of(" ", "x")).isInstanceOf(IllegalArgumentException.class);
    }
}
