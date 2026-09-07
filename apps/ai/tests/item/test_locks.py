"""REC-SLOT-01: the four locks are evaluated independently and every verdict is reported."""

from __future__ import annotations

import random
from datetime import date, time

from nullnull_ai.domain.types import EligibilityState
from nullnull_ai.item.filters import lock_checks
from nullnull_ai.item.types import DateLock, ItemLock, LockType, MustVisitLock, ReservationLock, TimeLock

D12 = date(2026, 9, 12)
D13 = date(2026, 9, 13)


def test_no_locks_passes_and_reports_nothing() -> None:
    result = lock_checks((), D13, time(10, 0), 60)
    assert result.eligibility.is_eligible
    assert result.passed == {}


def test_date_lock_blocks_other_dates_only() -> None:
    locks: tuple[ItemLock, ...] = (DateLock(D12),)
    assert lock_checks(locks, D13, None, 60).eligibility.state is EligibilityState.INELIGIBLE
    assert lock_checks(locks, D13, None, 60).eligibility.reasons[0].code == "DATE_LOCKED"
    assert lock_checks(locks, D12, time(15, 0), 60).eligibility.is_eligible


def test_time_lock_allows_within_tolerance_and_rejects_unknown_time() -> None:
    locks: tuple[ItemLock, ...] = (TimeLock(time(10, 0), 30),)
    assert lock_checks(locks, D13, time(10, 30), 60).eligibility.is_eligible
    assert lock_checks(locks, D13, time(10, 31), 60).eligibility.reasons[0].code == "TIME_LOCKED"
    assert lock_checks(locks, D13, None, 60).eligibility.reasons[0].code == "TIME_LOCKED"


def test_time_lock_tolerance_is_symmetric_around_the_locked_start() -> None:
    # 30m 01s in either direction truncates to 30 whole minutes, like Java's Duration.toMinutes().
    locks: tuple[ItemLock, ...] = (TimeLock(time(10, 0, 30), 30),)
    assert lock_checks(locks, D13, time(10, 30, 31), 60).eligibility.is_eligible
    assert lock_checks(locks, D13, time(9, 30, 29), 60).eligibility.is_eligible
    assert lock_checks(locks, D13, time(10, 31, 30), 60).eligibility.reasons[0].code == "TIME_LOCKED"
    assert lock_checks(locks, D13, time(9, 29, 30), 60).eligibility.reasons[0].code == "TIME_LOCKED"


def test_reservation_pins_date_and_start_time_and_the_stay_must_fit_the_window() -> None:
    locks: tuple[ItemLock, ...] = (ReservationLock(D12, time(18, 0), time(20, 0)),)
    assert lock_checks(locks, D12, time(18, 0), 90).eligibility.is_eligible
    # The reservation start is pinned (D-REC-8).
    assert lock_checks(locks, D12, time(19, 0), 30).eligibility.reasons[0].code == "RESERVATION_LOCKED"
    # The stay would end after the reservation window.
    assert lock_checks(locks, D12, time(18, 0), 150).eligibility.reasons[0].code == "RESERVATION_LOCKED"
    # An unknown duration is judged by the opening/duration filter, not by the lock.
    assert lock_checks(locks, D12, time(18, 0), None).eligibility.is_eligible
    assert lock_checks(locks, D13, time(18, 0), 30).eligibility.reasons[0].code == "RESERVATION_LOCKED"


def test_locks_are_independent_for_random_combinations() -> None:
    # REC-SLOT-01 property: every lock's verdict depends only on its own rule; removing another
    # lock never changes it.
    rng = random.Random(20260906)
    for iteration in range(1_000):
        locks: list[ItemLock] = []
        if rng.random() < 0.5:
            locks.append(MustVisitLock())
        if rng.random() < 0.5:
            locks.append(DateLock(D12 if rng.random() < 0.5 else D13))
        if rng.random() < 0.5:
            locks.append(TimeLock(time(10, 0), rng.randrange(181)))
        if rng.random() < 0.5:
            locks.append(ReservationLock(D12, time(10, 0), time(12, 0)))
        day = D12 if rng.random() < 0.5 else D13
        at = None if rng.randrange(4) == 0 else time(9 + rng.randrange(4), rng.randrange(2) * 30)
        duration = None if rng.randrange(3) == 0 else 30 + rng.randrange(4) * 30
        combined = lock_checks(tuple(locks), day, at, duration)
        assert set(combined.passed) == {lock.type for lock in locks}
        assert combined.eligibility.is_eligible == all(combined.passed.values())
        for lock in locks:
            alone = lock_checks((lock,), day, at, duration)
            assert alone.passed[lock.type] == combined.passed[lock.type], f"iteration {iteration} lock {lock.type}"


def test_locks_are_independent_and_all_reported() -> None:
    locks: tuple[ItemLock, ...] = (MustVisitLock(), DateLock(D12), TimeLock(time(10, 0), 0))
    result = lock_checks(locks, D12, time(10, 0), 60)
    assert result.eligibility.is_eligible
    assert result.passed == {LockType.MUST_VISIT: True, LockType.DATE: True, LockType.TIME: True}
    moved = lock_checks(locks, D13, time(10, 0), 60)
    assert moved.passed == {LockType.MUST_VISIT: True, LockType.DATE: False, LockType.TIME: True}
