package io.nullnull.optimization.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The record refuses what the table refuses.
 *
 * <p>V029 carries these rules as CHECKs and {@code OptimizationProposalSchemaIT} proves the table
 * enforces them. This is the other half: a proposal that the database would reject must not be
 * constructible in memory either, or the rejection arrives at the write with the call path already
 * half-run and a stack trace instead of an argument error.
 *
 * <p>The two can drift - that is the reason to pin both. A CHECK relaxed without the record, or a
 * record relaxed without the CHECK, leaves one layer believing something the other does not.
 */
@DisplayName("optimization proposal domain rules")
class OptimizationProposalTest {

    private static final String STATE = "{\"position\":0}";

    @Test
    @DisplayName("a crowd delta needs an eligible comparison, and an ineligible one needs a reason")
    void invariantEightHoldsInMemoryToo() {
        assertThatThrownBy(() -> proposal(false, "SOURCE_MISMATCH", new BigDecimal("1.25")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("comparable");
        assertThatThrownBy(() -> proposal(false, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
        assertThatThrownBy(() -> proposal(true, "SOURCE_MISMATCH", null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatCode(() -> proposal(false, "SOURCE_MISMATCH", null)).doesNotThrowAnyException();
        assertThatCode(() -> proposal(true, null, new BigDecimal("1.25"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a change carries exactly the halves its operation has")
    void aChangeCannotBeHalfDescribed() {
        assertThatThrownBy(() -> change(OptimizationChangeOperation.MOVE, null, STATE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> change(OptimizationChangeOperation.ADD, STATE, STATE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> change(OptimizationChangeOperation.REMOVE, STATE, STATE))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatCode(() -> change(OptimizationChangeOperation.MOVE, STATE, STATE))
                .doesNotThrowAnyException();
        assertThatCode(() -> change(OptimizationChangeOperation.ADD, null, STATE))
                .doesNotThrowAnyException();
        assertThatCode(() -> change(OptimizationChangeOperation.REMOVE, STATE, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a proposal that changes nothing is refused, which the table cannot see")
    void anEmptyProposalIsNotAProposal() {
        // The one rule here with no CHECK behind it: "at least one change" is a property of the
        // proposal as a whole, and a per-row constraint cannot count rows that were never inserted.
        // The contract states it as minItems 1; this is where that is enforced.
        assertThatThrownBy(() -> new OptimizationProposal(UUID.randomUUID(), UUID.randomUUID(), 1,
                "a proposal", true, null, null, null, "{}", Instant.now(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("changes nothing");
    }

    @Test
    @DisplayName("rank starts at 1 and a summary fits what the contract publishes")
    void boundsMatchTheContract() {
        assertThatThrownBy(() -> new OptimizationProposal(UUID.randomUUID(), UUID.randomUUID(), 0,
                "a proposal", true, null, null, null, "{}", Instant.now(), List.of(anyChange())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OptimizationProposal(UUID.randomUUID(), UUID.randomUUID(), 1,
                "   ", true, null, null, null, "{}", Instant.now(), List.of(anyChange())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OptimizationProposal(UUID.randomUUID(), UUID.randomUUID(), 1,
                "x".repeat(OptimizationProposal.MAX_SUMMARY_LENGTH + 1), true, null, null, null, "{}",
                Instant.now(), List.of(anyChange())))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatCode(() -> new OptimizationProposal(UUID.randomUUID(), UUID.randomUUID(), 1,
                "x".repeat(OptimizationProposal.MAX_SUMMARY_LENGTH), true, null, null, null, "{}",
                Instant.now(), List.of(anyChange())))
                .doesNotThrowAnyException();
    }

    private static OptimizationChange anyChange() {
        return change(OptimizationChangeOperation.MOVE, STATE, STATE);
    }

    private static OptimizationChange change(OptimizationChangeOperation operation, String before,
            String after) {
        return new OptimizationChange(UUID.randomUUID(), UUID.randomUUID(), operation, before, after, 0);
    }

    private static OptimizationProposal proposal(boolean eligible, String reasonCode, BigDecimal delta) {
        return new OptimizationProposal(UUID.randomUUID(), UUID.randomUUID(), 1, "a proposal", eligible,
                reasonCode, delta, null, "{}", Instant.now(), List.of(anyChange()));
    }
}
