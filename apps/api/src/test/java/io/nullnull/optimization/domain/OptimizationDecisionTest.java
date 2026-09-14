package io.nullnull.optimization.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The record refuses what V030 refuses.
 *
 * <p>Same pairing as the proposals: the CHECK is the guarantee and this is the half that keeps a
 * refusable decision from being built and carried halfway down a write before the database says no.
 * Both are pinned because they can drift, and a relaxation on one side alone leaves the other
 * believing something it no longer enforces.
 */
@DisplayName("optimization decision domain rules")
class OptimizationDecisionTest {

    private static final Instant AT = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    @DisplayName("a KEEP changes nothing, so it carries no version, no revisions and no window")
    void aKeepCarriesNoTripEffect() {
        assertThatCode(() -> decision(OptimizationDecisionKind.KEEP, null, null, null, null, null))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> decision(OptimizationDecisionKind.KEEP, 7L, id(), id(), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decision(OptimizationDecisionKind.KEEP, null, null, null, null, AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an APPLY moves between two revisions and can still be taken back")
    void anApplyCarriesItsWindow() {
        assertThatCode(() -> decision(OptimizationDecisionKind.APPLY, 8L, id(), id(), null, AT))
                .doesNotThrowAnyException();

        // Half the move is not a smaller move: without both revisions there is no before-state for a
        // REVERT to restore.
        assertThatThrownBy(() -> decision(OptimizationDecisionKind.APPLY, 8L, id(), null, null, AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decision(OptimizationDecisionKind.APPLY, 8L, id(), id(), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a REVERT names what it undoes and has no window of its own")
    void aRevertCannotItselfBeReverted() {
        assertThatCode(() -> decision(OptimizationDecisionKind.REVERT, 9L, id(), id(), id(), null))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> decision(OptimizationDecisionKind.REVERT, 9L, id(), id(), id(), AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decision(OptimizationDecisionKind.REVERT, 9L, id(), id(), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        // And only a REVERT may name one.
        assertThatThrownBy(() -> decision(OptimizationDecisionKind.APPLY, 8L, id(), id(), id(), AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("every decision says which trip version it answered, KEEP included")
    void theAnsweredVersionIsAlwaysRecorded() {
        assertThatThrownBy(() -> new OptimizationDecision(id(), id(), id(), id(),
                OptimizationDecisionKind.KEEP, 0L, null, null, null, null, null, AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static UUID id() {
        return UUID.randomUUID();
    }

    private static OptimizationDecision decision(OptimizationDecisionKind kind, Long resulting,
            UUID before, UUID after, UUID reverted, Instant window) {
        return new OptimizationDecision(id(), id(), id(), id(), kind, 7L, resulting, before, after,
                reverted, window, AT);
    }
}
