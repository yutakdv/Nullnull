package io.nullnull.trip.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shared stay arithmetic behind the reservation window, the opening window and the neighbour
 * overlap. The two queries answer midnight differently on purpose — the window checks reject a stay
 * that leaves its date, the overlap check clamps it to the rest of that date — so both answers are
 * pinned here, mirroring {@code apps/ai/src/nullnull_ai/item/filters.py::_add} and {@code ::_stay}.
 */
@DisplayName("shared stay arithmetic")
class StayIntervalTest {

    /** The fixed date both queries measure on; a caller never sees it, only the wrap it causes. */
    static final LocalDate EPOCH = LocalDate.of(2000, 1, 1);

    static final LocalDateTime DAY_END = LocalDateTime.of(EPOCH.plusDays(1), LocalTime.MIDNIGHT);

    @Test
    void endStaysInsideTheDayForAnOrdinaryStay() {
        StayInterval.End end = StayInterval.endOf(LocalTime.of(9, 0), 90);

        assertThat(end.time()).isEqualTo(LocalTime.of(10, 30));
        assertThat(end.wrappedPastMidnight()).isFalse();
    }

    @Test
    void endOfAZeroLengthStayIsItsOwnStart() {
        StayInterval.End end = StayInterval.endOf(LocalTime.of(10, 0), 0);

        assertThat(end.time()).isEqualTo(LocalTime.of(10, 0));
        assertThat(end.wrappedPastMidnight()).isFalse();
    }

    @Test
    void endAtExactlyMidnightIsReportedAsWrapped() {
        StayInterval.End end = StayInterval.endOf(LocalTime.of(23, 0), 60);

        // 00:00 alone would read as the morning of the same date, which is why the wrap travels with it.
        assertThat(end.time()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(end.wrappedPastMidnight()).isTrue();
    }

    @Test
    void endOneMinutePastMidnightIsWrappedAndNeverFoldsBackIntoTheMorning() {
        StayInterval.End end = StayInterval.endOf(LocalTime.of(23, 0), 61);

        assertThat(end.time()).isEqualTo(LocalTime.of(0, 1));
        assertThat(end.wrappedPastMidnight()).isTrue();
    }

    @Test
    void endOfAFullDayStayIsWrapped() {
        StayInterval.End end = StayInterval.endOf(LocalTime.MIDNIGHT, 1440);

        assertThat(end.time()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(end.wrappedPastMidnight()).isTrue();
    }

    @Test
    void endOfTheLastMinuteOfTheDayIsNotWrapped() {
        StayInterval.End end = StayInterval.endOf(LocalTime.of(23, 58), 1);

        assertThat(end.time()).isEqualTo(LocalTime.of(23, 59));
        assertThat(end.wrappedPastMidnight()).isFalse();
    }

    @Test
    void stayIsAHalfOpenIntervalOnItsOwnDate() {
        StayInterval.Stay stay = StayInterval.of(LocalTime.of(9, 0), 90);

        assertThat(stay.beginsAt()).isEqualTo(LocalDateTime.of(EPOCH, LocalTime.of(9, 0)));
        assertThat(stay.endsAt()).isEqualTo(LocalDateTime.of(EPOCH, LocalTime.of(10, 30)));
    }

    @Test
    void aZeroLengthStayIsAnEmptyInterval() {
        StayInterval.Stay stay = StayInterval.of(LocalTime.of(10, 0), 0);

        assertThat(stay.endsAt()).isEqualTo(stay.beginsAt());
    }

    @Test
    void aStayEndingExactlyAtMidnightIsKeptWholeRatherThanClamped() {
        StayInterval.Stay stay = StayInterval.of(LocalTime.of(23, 0), 60);

        assertThat(stay.endsAt()).isEqualTo(DAY_END);
    }

    @Test
    void aStayRunningPastMidnightOccupiesTheRestOfItsOwnDate() {
        StayInterval.Stay stay = StayInterval.of(LocalTime.of(23, 0), 61);

        // Clamped, not rejected: for overlap purposes the stay holds [23:00, 24:00) of its own date.
        assertThat(stay.beginsAt()).isEqualTo(LocalDateTime.of(EPOCH, LocalTime.of(23, 0)));
        assertThat(stay.endsAt()).isEqualTo(DAY_END);
    }

    @Test
    void bothQueriesMeasureTheSameStayOnTheSameDate() {
        LocalTime start = LocalTime.of(17, 45);

        StayInterval.End end = StayInterval.endOf(start, 30);
        StayInterval.Stay stay = StayInterval.of(start, 30);

        assertThat(end.wrappedPastMidnight()).isFalse();
        assertThat(stay.endsAt().toLocalTime()).isEqualTo(end.time());
        assertThat(stay.endsAt().toLocalDate()).isEqualTo(EPOCH);
    }

    @Test
    void aMissingStartTimeIsNeverGuessed() {
        // The message is the whole point: LocalDateTime.of would refuse the same call on its own, but
        // as "time", naming its own parameter. Only the guard here says which argument of which query
        // was never hydrated, so pin the message rather than the JDK behaviour underneath it.
        assertThatThrownBy(() -> StayInterval.endOf(null, 30)).isInstanceOf(NullPointerException.class)
                .hasMessage("start");
        assertThatThrownBy(() -> StayInterval.of(null, 30)).isInstanceOf(NullPointerException.class)
                .hasMessage("start");
    }
}
