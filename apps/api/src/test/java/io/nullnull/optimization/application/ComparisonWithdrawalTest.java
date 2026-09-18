package io.nullnull.optimization.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BA-052-T14, the half the pipeline cannot reach.
 *
 * <p>A source incident refuses an APPLY only when it withdraws a comparison the preview made. An
 * incident the preview already saw left the comparison ineligible and the proposal with no crowd
 * claim, so refusing it now would report a change that did not happen. P0 cannot produce that
 * proposal - ProposalRevalidator refuses an ineligible candidate verdict and the run FAILS - so the
 * verdict is proven here, on the function decideOptimization calls, and OptimizeDecisionIT proves
 * the reachable half through the pipeline.
 */
@DisplayName("BA-052 a comparison withdrawn by a source incident")
class ComparisonWithdrawalTest {

    private static final LocalDate LEAVES = LocalDate.parse("2026-10-04");
    private static final LocalDate ARRIVES = LocalDate.parse("2026-10-05");
    private static final LocalDate ELSEWHERE = LocalDate.parse("2026-10-06");

    @Test
    @DisplayName("BA-052-T14 an incident on a comparison the preview judged ineligible withdraws nothing")
    void anIneligibleComparisonIsNotWithdrawn() {
        assertThat(OptimizationService.comparisonWithdrawn(false, Set.of(LEAVES, ARRIVES),
                Set.of(LEAVES, ARRIVES))).isFalse();
        // The control: the same days under an eligible comparison are withdrawn, so the answer above is
        // about eligibility and not about the days.
        assertThat(OptimizationService.comparisonWithdrawn(true, Set.of(LEAVES, ARRIVES),
                Set.of(LEAVES, ARRIVES))).isTrue();
    }

    @Test
    @DisplayName("BA-052-T14 an incident on a day the comparison did not use withdraws nothing")
    void anIncidentElsewhereWithdrawsNothing() {
        assertThat(OptimizationService.comparisonWithdrawn(true, Set.of(LEAVES, ARRIVES),
                Set.of(ELSEWHERE))).isFalse();
        assertThat(OptimizationService.comparisonWithdrawn(true, Set.of(LEAVES, ARRIVES),
                Set.of(ARRIVES))).isTrue();
    }
}
