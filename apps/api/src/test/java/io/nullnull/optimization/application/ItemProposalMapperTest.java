package io.nullnull.optimization.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.optimization.domain.OptimizationChangeOperation;
import io.nullnull.optimization.domain.OptimizationProposal;
import io.nullnull.optimization.domain.OptimizationRun;
import io.nullnull.optimization.domain.OptimizationScope;
import io.nullnull.optimization.domain.OptimizationStatus;
import io.nullnull.recommendation.domain.item.ItemProposalOut;
import io.nullnull.recommendation.domain.item.TemporalCandidateIn;
import io.nullnull.trip.domain.LockType;
import io.nullnull.trip.domain.TripItem;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Turning one answer into the rows a traveller approves. */
@DisplayName("item proposal mapping")
class ItemProposalMapperTest {

    private static final UUID TRIP = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final UUID PLACE = UUID.randomUUID();
    private static final LocalDate CURRENT = LocalDate.of(2026, 10, 6);
    private static final LocalDate PROPOSED = LocalDate.of(2026, 10, 8);
    private static final Instant AT = Instant.parse("2026-10-01T00:00:00Z");

    private final ItemProposalMapper mapper = new ItemProposalMapper(new ObjectMapper());

    @Test
    @DisplayName("an eligible proposal carries the delta the pair supports")
    void anEligibleProposalCarriesItsDelta() {
        OptimizationProposal proposal = map(candidate(true, null, "80", "20"), "덜 붐비는 날입니다").get(0);

        assertThat(proposal.comparisonEligible()).isTrue();
        assertThat(proposal.comparisonReasonCode()).isNull();
        // after minus before: moving from 80 to 20 is a fall of 60, and the sign is the direction the
        // traveller experiences rather than an improvement score.
        assertThat(proposal.crowdDelta()).isEqualByComparingTo(new BigDecimal("-60"));
        // No route provider exists, so a travel-time difference would be a measurement nobody made.
        assertThat(proposal.travelMinutesDelta()).isNull();
        assertThat(proposal.summary()).isEqualTo("덜 붐비는 날입니다");
    }

    @Test
    @DisplayName("an ineligible pair yields a reason and no number, which the record enforces")
    void anIneligiblePairCarriesNoDelta() {
        OptimizationProposal proposal =
                map(candidate(false, "DIFFERENT_SOURCE", "80", "20"), "비교할 수 없습니다").get(0);

        assertThat(proposal.comparisonEligible()).isFalse();
        assertThat(proposal.comparisonReasonCode()).isEqualTo("DIFFERENT_SOURCE");
        assertThat(proposal.crowdDelta()).isNull();
    }

    @Test
    @DisplayName("the change is one MOVE from where the item is to the day proposed")
    void theChangeIsTheMoveItself() {
        OptimizationProposal proposal = map(candidate(true, null, "80", "20"), "요약").get(0);

        assertThat(proposal.changes()).hasSize(1);
        var change = proposal.changes().get(0);
        assertThat(change.operation()).isEqualTo(OptimizationChangeOperation.MOVE);
        assertThat(change.tripItemId()).isEqualTo(TARGET);
        assertThat(change.beforeValue()).contains("\"date\":\"2026-10-06\"").contains("\"startTime\":\"09:00\"");
        assertThat(change.afterValue()).contains("\"date\":\"2026-10-08\"");
        // A DAY candidate keeps the time the item already has: the evidence is per day, so proposing
        // a different hour would be a claim the forecast cannot support.
        assertThat(change.afterValue()).contains("\"startTime\":\"09:00\"");
    }

    @Test
    @DisplayName("explanations are paired by position, and a mismatch is refused rather than shuffled")
    void everyProposalNeedsItsOwnExplanation() {
        assertThatThrownBy(() -> mapper.toProposals(run(), target(),
                List.of(answer(1, PROPOSED), answer(2, LocalDate.of(2026, 10, 7))),
                Map.of(PROPOSED, candidate(true, null, "80", "20")), AT, List.of("하나뿐")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("its own explanation");
    }

    @Test
    @DisplayName("a proposal for a date nobody offered is a contradiction, not a surprise")
    void aProposalMustNameAnOfferedDate() {
        assertThatThrownBy(() -> mapper.toProposals(run(), target(), List.of(answer(1, PROPOSED)),
                Map.of(), AT, List.of("요약")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not offer");
    }

    @Test
    @DisplayName("a stored change reads back as the state it was written from, time included")
    void aStoredChangeReadsBackAsItsState() {
        var change = map(candidate(true, null, "80", "20"), "요약").get(0).changes().get(0);

        // The writer stores 09:00 without seconds; the reader returns the time itself, and the
        // response renders it (the contract's pattern needs the seconds).
        assertThat(mapper.readState(change.beforeValue()))
                .isEqualTo(new ItemProposalMapper.ItemState(PLACE, CURRENT, 0, LocalTime.of(9, 0)));
        assertThat(mapper.readState(change.afterValue()))
                .isEqualTo(new ItemProposalMapper.ItemState(PLACE, PROPOSED, 0, LocalTime.of(9, 0)));
        assertThat(mapper.readState(null)).isNull();
    }

    @Test
    @DisplayName("#242 the stored lock checks read back as the contract's list, derived verdict included")
    void storedLockChecksReadBackAsTheContractsList() {
        String written = map(candidate(true, null, "80", "20"), "요약").get(0).validationSummary();

        assertThat(mapper.readValidation(written)).isEqualTo(new ItemProposalMapper.Validation(true,
                List.of(new ItemProposalMapper.Check(LockType.MUST_VISIT, true))));
    }

    @Test
    @DisplayName("#242 checks come back in lock order, whatever order the object holds them in")
    void checksAreOrderedByLockType() {
        var read = mapper.readValidation(
                "{\"checks\":{\"RESERVATION\":true,\"TIME\":true,\"MUST_VISIT\":true,\"DATE\":true}}");

        assertThat(read.checks()).extracting(ItemProposalMapper.Check::constraintType)
                .containsExactly(LockType.MUST_VISIT, LockType.DATE, LockType.TIME, LockType.RESERVATION);
    }

    @Test
    @DisplayName("#242 allConstraintsPreserved is false when any stored check failed")
    void oneFailedCheckIsNotAllPreserved() {
        var read = mapper.readValidation("{\"checks\":{\"DATE\":true,\"TIME\":false}}");

        assertThat(read.allConstraintsPreserved()).isFalse();
        assertThat(read.checks()).extracting(ItemProposalMapper.Check::passed).containsExactly(true, false);
    }

    @Test
    @DisplayName("#242 an item with no lock has no constraint to break, which is preserved rather than unknown")
    void noLockIsPreserved() {
        var read = mapper.readValidation("{\"checks\":{}}");

        assertThat(read.allConstraintsPreserved()).isTrue();
        assertThat(read.checks()).isEmpty();
    }

    @Test
    @DisplayName("#242 a check that names no lock type is refused, not dropped from the list")
    void anUnknownCheckIsRefused() {
        assertThatThrownBy(() -> mapper.readValidation("{\"checks\":{\"DATE\":true,\"SOMETHING\":true}}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SOMETHING");
    }

    private List<OptimizationProposal> map(TemporalCandidateIn candidate, String summary) {
        return mapper.toProposals(run(), target(), List.of(answer(1, PROPOSED)),
                Map.of(PROPOSED, candidate), AT, List.of(summary));
    }

    private static TemporalCandidateIn candidate(boolean eligible, String reason, String before,
            String after) {
        return new TemporalCandidateIn(PLACE, PROPOSED, null,
                TemporalCandidateIn.ForecastResolution.DAY, new BigDecimal(before),
                new BigDecimal(after), "KTO_RELATIVE_CONCENTRATION_INDEX", eligible,
                reason == null ? "ELIGIBLE" : reason, UUID.randomUUID(), UUID.randomUUID());
    }

    private static ItemProposalOut answer(int rank, LocalDate date) {
        return new ItemProposalOut(rank, date, null, AT, AT, new BigDecimal("1"), new BigDecimal("1"),
                new BigDecimal("1"), new BigDecimal("0"), UUID.randomUUID(), UUID.randomUUID(),
                Map.of("MUST_VISIT", true));
    }

    private static TripItem target() {
        return new TripItem(TARGET, PLACE, CURRENT, 0, LocalTime.of(9, 0), 60, "메모", List.of());
    }

    private static OptimizationRun run() {
        return new OptimizationRun(UUID.randomUUID(), TRIP, UUID.randomUUID(), OptimizationScope.ITEM,
                // V032 widened the record: policyVersion, policyHash and catalogVersion sit after
                // algorithmVersion. A RUNNING run has frozen nothing yet, so all three are null here.
                TARGET, null, false, OptimizationStatus.RUNNING, 3L, null, null, null, null, null, null,
                null, null, AT, AT, null, null, List.of());
    }
}
