package io.nullnull.optimization.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.optimization.domain.OptimizationDecision;
import io.nullnull.optimization.domain.OptimizationDecisionKind;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The order getOptimization lists a run's decisions in.
 *
 * <p>A unit test because the case that matters cannot be produced on purpose through HTTP: an APPLY
 * and its REVERT stamped at the same instant come back from {@code ORDER BY decided_at, id} in the
 * order of their ids' random bits, so an HTTP case would catch a lost ordering half of the time.
 */
@DisplayName("run decisions in contract order")
class DecisionOrderTest {

    private static final UUID RUN = UUID.randomUUID();
    private static final UUID PROPOSAL = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final Instant AT = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    @DisplayName("BA-054-T11 a REVERT that arrives first is still listed after the APPLY it undoes")
    void theRevertComesAfterItsApply() {
        OptimizationDecision apply = apply();
        OptimizationDecision revert = revertOf(apply);

        assertThat(OptimizationService.inContractOrder(List.of(revert, apply)))
                .extracting(OptimizationDecision::decision)
                .containsExactly(OptimizationDecisionKind.APPLY, OptimizationDecisionKind.REVERT);
        assertThat(OptimizationService.inContractOrder(List.of(apply, revert)))
                .extracting(OptimizationDecision::decision)
                .containsExactly(OptimizationDecisionKind.APPLY, OptimizationDecisionKind.REVERT);
    }

    @Test
    @DisplayName("a KEEP alone is listed as it is")
    void aKeepAloneIsItself() {
        OptimizationDecision keep = new OptimizationDecision(UUID.randomUUID(), RUN, PROPOSAL, OWNER,
                OptimizationDecisionKind.KEEP, 1L, null, null, null, null, null, AT);

        assertThat(OptimizationService.inContractOrder(List.of(keep))).containsExactly(keep);
        assertThat(OptimizationService.inContractOrder(List.of())).isEmpty();
    }

    private static OptimizationDecision apply() {
        return new OptimizationDecision(UUID.randomUUID(), RUN, PROPOSAL, OWNER, OptimizationDecisionKind.APPLY,
                1L, 2L, UUID.randomUUID(), UUID.randomUUID(), null, AT.plusSeconds(86_400), AT);
    }

    private static OptimizationDecision revertOf(OptimizationDecision applied) {
        return new OptimizationDecision(UUID.randomUUID(), RUN, PROPOSAL, OWNER, OptimizationDecisionKind.REVERT,
                2L, 3L, UUID.randomUUID(), UUID.randomUUID(), applied.id(), null, AT);
    }
}
