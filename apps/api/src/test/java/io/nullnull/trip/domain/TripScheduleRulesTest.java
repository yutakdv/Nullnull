package io.nullnull.trip.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BA-031-T2: a date-range shrink is refused entirely while anything would fall outside it.
 *
 * <p>These are the rules a trip update has to apply BEFORE it writes anything. Proving them here
 * rather than only through HTTP is what lets the integration test be about atomicity - that a
 * refusal left no row and no version change - instead of re-deriving which shrinks are legal.
 */
@DisplayName("BA-031-T2 trip schedule rules")
class TripScheduleRulesTest {

    private static final UUID PLACE = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93911");

    private static TripDateRange range(String start, String end) {
        return TripDateRange.of(LocalDate.parse(start), LocalDate.parse(end), "Asia/Seoul");
    }

    private static TripItem item(String date, int position, TripConstraint... constraints) {
        return new TripItem(UUID.randomUUID(), PLACE, LocalDate.parse(date), position, null, null, null,
                List.of(constraints));
    }

    private static TripConstraint dateLock(String date) {
        return new TripConstraint(new ItemLock.Date(LocalDate.parse(date)), ConstraintSource.USER);
    }

    private static TripConstraint reservation(String date) {
        return new TripConstraint(new ItemLock.Reservation(LocalDate.parse(date),
                LocalTime.of(13, 0), null), ConstraintSource.USER);
    }

    @Test
    void aShrinkThatKeepsEverythingInsideIsAllowed() {
        assertThat(TripScheduleRules.shrinkConflicts(range("2026-10-04", "2026-10-05"),
                List.of(item("2026-10-04", 0), item("2026-10-05", 0)))).isEmpty();
    }

    @Test
    @DisplayName("an item outside the new range blocks the whole shrink")
    void anItemOutsideTheRangeBlocksIt() {
        var conflicts = TripScheduleRules.shrinkConflicts(range("2026-10-04", "2026-10-05"),
                List.of(item("2026-10-04", 0), item("2026-10-07", 0)));
        assertThat(conflicts).singleElement()
                .extracting(TripValidationException.FieldViolation::code)
                .isEqualTo("ItemOutsideRange");
    }

    @Test
    @DisplayName("a DATE or RESERVATION lock outside the range is reported on its own")
    void aPinnedDateOutsideTheRangeIsItsOwnReason() {
        // The item itself sits inside, so only its lock is the problem. Reporting that as
        // "item outside range" would send the user looking at the wrong thing: an unlocked item can
        // be moved, a booking cannot.
        var conflicts = TripScheduleRules.shrinkConflicts(range("2026-10-04", "2026-10-05"),
                List.of(item("2026-10-04", 0, dateLock("2026-10-09"))));
        assertThat(conflicts).singleElement()
                .extracting(TripValidationException.FieldViolation::code)
                .isEqualTo("LockedDateOutsideRange");
        assertThat(TripScheduleRules.shrinkConflicts(range("2026-10-04", "2026-10-05"),
                List.of(item("2026-10-04", 0, reservation("2026-10-09"))))).hasSize(1);
    }

    @Test
    @DisplayName("MUST_VISIT and TIME do not pin a date, so they never block a shrink on their own")
    void locksThatDoNotPinADateDoNotBlockAShrink() {
        // MUST_VISIT says the place stays in the trip and TIME pins a clock time; neither says which
        // day. Treating them as date pins would refuse shrinks the user is entitled to make.
        TripConstraint mustVisit = new TripConstraint(new ItemLock.MustVisit(), ConstraintSource.USER);
        TripConstraint time = new TripConstraint(new ItemLock.Time(LocalTime.of(9, 30), 30),
                ConstraintSource.USER);
        assertThat(TripScheduleRules.shrinkConflicts(range("2026-10-04", "2026-10-05"),
                List.of(item("2026-10-04", 0, mustVisit, time)))).isEmpty();
    }

    @Test
    void everyConflictIsReportedNotJustTheFirst() {
        var conflicts = TripScheduleRules.shrinkConflicts(range("2026-10-04", "2026-10-05"),
                List.of(item("2026-10-08", 0), item("2026-10-04", 1, dateLock("2026-10-09"))));
        assertThat(conflicts).hasSize(2)
                .extracting(TripValidationException.FieldViolation::code)
                .containsExactlyInAnyOrder("ItemOutsideRange", "LockedDateOutsideRange");
    }

    @Test
    void capsAreEnforcedPerDayAndPerTrip() {
        List<TripItem> oneDay = new ArrayList<>();
        for (int position = 0; position <= TripItem.MAX_PER_DAY; position++) {
            oneDay.add(item("2026-10-04", position));
        }
        assertThatThrownBy(() -> TripScheduleRules.requireWithinCaps(oneDay))
                .isInstanceOf(TripValidationException.class);
        assertThatCode(() -> TripScheduleRules.requireWithinCaps(oneDay.subList(0, TripItem.MAX_PER_DAY)))
                .doesNotThrowAnyException();

        List<TripItem> wholeTrip = new ArrayList<>();
        for (int index = 0; index <= TripItem.MAX_PER_TRIP; index++) {
            wholeTrip.add(item("2026-10-0" + (index % 7 + 1), index));
        }
        assertThatThrownBy(() -> TripScheduleRules.requireWithinCaps(wholeTrip))
                .isInstanceOf(TripValidationException.class);
    }

    @Test
    void twoItemsCannotClaimOneSlot() {
        assertThatThrownBy(() -> TripScheduleRules.requireDistinctPositions(
                List.of(item("2026-10-04", 0), item("2026-10-04", 0))))
                .isInstanceOf(TripValidationException.class);
        // The same position on a different day is a different slot.
        assertThatCode(() -> TripScheduleRules.requireDistinctPositions(
                List.of(item("2026-10-04", 0), item("2026-10-05", 0)))).doesNotThrowAnyException();
        // Gaps are fine: position is an ordinal, not an array index.
        assertThatCode(() -> TripScheduleRules.requireDistinctPositions(
                List.of(item("2026-10-04", 0), item("2026-10-04", 7)))).doesNotThrowAnyException();
    }

    @Test
    void aSeededItemOutsideTheRangeIsRefused() {
        assertThatThrownBy(() -> TripScheduleRules.requireInsideRange(range("2026-10-04", "2026-10-05"),
                List.of(item("2026-10-06", 0)))).isInstanceOf(TripValidationException.class);
    }

    @Test
    void anItemCarriesAtMostOneConstraintOfEachType() {
        // Invariant 7: the four are independent, so two of the same type is not "the later wins".
        assertThatThrownBy(() -> TripConstraint.validated(
                List.of(dateLock("2026-10-04"), dateLock("2026-10-05"))))
                .isInstanceOf(TripValidationException.class);
        assertThatCode(() -> TripConstraint.validated(List.of(dateLock("2026-10-04"),
                new TripConstraint(new ItemLock.MustVisit(), ConstraintSource.USER))))
                .doesNotThrowAnyException();
    }
}
