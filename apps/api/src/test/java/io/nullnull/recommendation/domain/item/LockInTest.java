package io.nullnull.recommendation.domain.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.trip.domain.ItemLock;
import io.nullnull.trip.domain.LockType;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A wire lock becomes the trip module's lock, or it is refused — never a weaker lock than it names. */
@DisplayName("LockIn to trip lock")
class LockInTest {

    static final LocalDate D12 = LocalDate.of(2026, 9, 12);
    static final LocalTime TEN = LocalTime.of(10, 0);

    @Test
    void eachTypeBuildsItsOwnLock() {
        assertThat(LockIn.mustVisit().toItemLock()).isEqualTo(new ItemLock.MustVisit());
        assertThat(LockIn.date(D12).toItemLock()).isEqualTo(new ItemLock.Date(D12));
        assertThat(LockIn.time(TEN, 30).toItemLock()).isEqualTo(new ItemLock.Time(TEN, 30));
        assertThat(LockIn.reservation(D12, TEN, LocalTime.of(11, 30)).toItemLock())
                .isEqualTo(new ItemLock.Reservation(D12, TEN, LocalTime.of(11, 30)));
        assertThat(LockIn.reservation(D12, TEN, null).toItemLock())
                .as("an open-ended booking still pins the date and the start time")
                .isEqualTo(new ItemLock.Reservation(D12, TEN, null));
    }

    @Test
    void aTimeLockToleranceStaysInsideTheContractRange() {
        // minimum 0 / maximum 180 on the service side, enforced by io.nullnull.trip.domain.ItemLock.Time:
        // both ends are legal values, and a tolerance outside them would move an item the user pinned.
        assertThat(LockIn.time(TEN, 0).toItemLock()).isEqualTo(new ItemLock.Time(TEN, 0));
        assertThat(LockIn.time(TEN, 180).toItemLock()).isEqualTo(new ItemLock.Time(TEN, 180));
        assertThatThrownBy(() -> LockIn.time(TEN, -1).toItemLock()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toleranceMinutes must be 0..180");
        assertThatThrownBy(() -> LockIn.time(TEN, 181).toItemLock()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toleranceMinutes must be 0..180");
    }

    @Test
    void aLockCarryingAnotherTypesFieldIsRefused() {
        assertThatThrownBy(() -> new LockIn(LockType.MUST_VISIT, D12, null, null, null).toItemLock())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LockIn(LockType.DATE, D12, TEN, null, null).toItemLock())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LockIn(LockType.TIME, D12, TEN, null, 30).toItemLock())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LockIn(LockType.RESERVATION, D12, TEN, null, 30).toItemLock())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aLockMissingItsOwnFieldIsRefused() {
        assertThatThrownBy(() -> new LockIn(LockType.DATE, null, null, null, null).toItemLock())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LockIn(LockType.TIME, null, TEN, null, null).toItemLock())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LockIn(LockType.RESERVATION, D12, null, null, null).toItemLock())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
