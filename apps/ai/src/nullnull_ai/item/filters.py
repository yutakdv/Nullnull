"""§5.4 hard checks. Each returns ELIGIBLE, INELIGIBLE (fact known, violated) or UNKNOWN (fact missing).

Locks are evaluated one by one and every verdict is reported, so a validation summary can list all
constraint checks. They are evaluated in the fixed order MUST_VISIT -> DATE -> TIME -> RESERVATION
regardless of the order the caller supplied, because the caller reads `reasons[0]` as *the* reason a
candidate was rejected: with input order, one candidate breaking two locks would report a different
code depending on how the trip happened to store them. MUST_VISIT always passes here because a
temporal move keeps the place (the place itself is checked by `same_place`). RESERVATION pins date
and start time (conservative reading of "예약 날짜·시간 범위 유지", D-REC-8); a known stay must also
end inside the reservation window.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from uuid import UUID

from nullnull_ai.domain.types import Eligibility, EligibilityState, Reason
from nullnull_ai.item.types import (
    Closed,
    DateLock,
    ItemLock,
    LockType,
    MustVisitLock,
    NeighbourItem,
    OpeningWindow,
    OpenWindow,
    ReservationLock,
    RouteEvidence,
    TargetItem,
    TimeLock,
    UnknownHours,
)

PLACE_MISMATCH = "PLACE_MISMATCH"
NO_CHANGE = "NO_CHANGE"
OUTSIDE_TRIP_RANGE = "OUTSIDE_TRIP_RANGE"
CLOSED = "CLOSED"
OUTSIDE_OPENING_HOURS = "OUTSIDE_OPENING_HOURS"
OPENING_HOURS_UNKNOWN = "OPENING_HOURS_UNKNOWN"
DURATION_UNKNOWN = "DURATION_UNKNOWN"
NEIGHBOUR_DURATION_UNKNOWN = "NEIGHBOUR_DURATION_UNKNOWN"
OVERLAPS_NEIGHBOUR = "OVERLAPS_NEIGHBOUR"
ROUTE_EVIDENCE_MISSING = "ROUTE_EVIDENCE_MISSING"
DATE_LOCKED = "DATE_LOCKED"
TIME_LOCKED = "TIME_LOCKED"
RESERVATION_LOCKED = "RESERVATION_LOCKED"

_EPOCH = date(2000, 1, 1)
_DAY_END = datetime.combine(_EPOCH, time.min) + timedelta(days=1)
_LOCK_REASON_CODES = {
    LockType.DATE: DATE_LOCKED,
    LockType.TIME: TIME_LOCKED,
    LockType.RESERVATION: RESERVATION_LOCKED,
}
# Evaluation order of the four locks; never the caller's order (see the module docstring).
_LOCK_ORDER = (LockType.MUST_VISIT, LockType.DATE, LockType.TIME, LockType.RESERVATION)


def _minutes_between(a: time, b: time) -> int:
    """Whole minutes apart, truncated toward zero so the tolerance stays symmetric around the lock."""
    return abs(int((datetime.combine(_EPOCH, b) - datetime.combine(_EPOCH, a)).total_seconds())) // 60


def _add(start: time, minutes: int) -> tuple[time, bool]:
    """Returns (end, wrapped_past_midnight)."""
    end = datetime.combine(_EPOCH, start) + timedelta(minutes=minutes)
    return end.time(), end.date() != _EPOCH


def _stay(start: time, minutes: int) -> tuple[datetime, datetime]:
    """The stay as a half-open interval on its own date.

    Comparing datetimes instead of times keeps a stay that runs past midnight from folding back into
    the morning; for overlap purposes such a stay occupies [start, 24:00) of the date it starts on.
    """
    begins_at = datetime.combine(_EPOCH, start)
    return begins_at, min(begins_at + timedelta(minutes=minutes), _DAY_END)


@dataclass(frozen=True, slots=True)
class LockResult:
    passed: Mapping[LockType, bool]
    eligibility: Eligibility


def lock_checks(
    locks: Sequence[ItemLock],
    proposed_date: date,
    proposed_time: time | None,
    duration_minutes: int | None,
) -> LockResult:
    passed: dict[LockType, bool] = {}
    reasons: list[Reason] = []
    for lock in sorted(locks, key=lambda lock: _LOCK_ORDER.index(lock.type)):
        match lock:
            case MustVisitLock():
                ok = True
            case DateLock(date=locked):
                ok = locked == proposed_date
            case TimeLock(start_time=start, tolerance_minutes=tolerance):
                ok = proposed_time is not None and _minutes_between(start, proposed_time) <= tolerance
            case ReservationLock(date=locked, start_time=start, end_time=end):
                fits = True
                if end is not None and duration_minutes is not None and proposed_time is not None:
                    stay_end, wrapped = _add(proposed_time, duration_minutes)
                    fits = not wrapped and stay_end <= end
                ok = locked == proposed_date and proposed_time == start and fits
        passed[lock.type] = ok
        if not ok:
            reasons.append(Reason(_LOCK_REASON_CODES[lock.type], f"{lock.type.value} lock is not satisfied"))
    eligibility = Eligibility.eligible() if not reasons else Eligibility(EligibilityState.INELIGIBLE, tuple(reasons))
    return LockResult(passed, eligibility)


def same_place(target: TargetItem, candidate_place_id: UUID) -> Eligibility:
    if target.place_id == candidate_place_id:
        return Eligibility.eligible()
    return Eligibility.ineligible(Reason(PLACE_MISMATCH, "P0 ITEM proposals keep the same place"))


def not_unchanged(target: TargetItem, day: date, at: time | None) -> Eligibility:
    if target.date == day and target.start_time == at:
        return Eligibility.ineligible(Reason(NO_CHANGE, "candidate equals the current slot"))
    return Eligibility.eligible()


def within_trip_range(trip_start: date, trip_end: date, day: date) -> Eligibility:
    if trip_start <= day <= trip_end:
        return Eligibility.eligible()
    return Eligibility.ineligible(Reason(OUTSIDE_TRIP_RANGE, "date outside trip"))


def opening_hours(window: OpeningWindow, start: time | None, duration_minutes: int | None) -> Eligibility:
    """The whole stay must sit inside a verified window (§5.4 "체류 전체").

    An unknown window is UNKNOWN; a timed proposal with an unknown duration is UNKNOWN too, never
    verified from the start time alone (REC-SLOT-02). A date-only proposal only needs an open day.
    """
    match window:
        case UnknownHours():
            return Eligibility.unknown(Reason(OPENING_HOURS_UNKNOWN, "opening hours unverified"))
        case Closed():
            return Eligibility.ineligible(Reason(CLOSED, "closed on that date"))
        case OpenWindow(opens_at=opens, closes_at=closes):
            if start is None:
                return Eligibility.eligible()
            if duration_minutes is None:
                return Eligibility.unknown(Reason(DURATION_UNKNOWN, "stay length unverified"))
            end, wrapped = _add(start, duration_minutes)
            if not wrapped and opens <= start and end <= closes:
                return Eligibility.eligible()
            return Eligibility.ineligible(Reason(OUTSIDE_OPENING_HOURS, "stay exceeds the opening window"))


def neighbour_overlap(
    neighbours: Sequence[NeighbourItem],
    target_item_id: UUID,
    day: date,
    start: time | None,
    duration_minutes: int | None,
) -> Eligibility:
    """Overlap needs both intervals fully known.

    An unknown duration on either side is UNKNOWN, never treated as zero minutes. Neighbours without
    a start time impose no interval; the target itself is skipped. A stay that runs past midnight
    occupies the rest of its own date, so a wrapped end is never mistaken for an early finish.
    """
    if start is None:
        return Eligibility.eligible()
    if duration_minutes is None:
        return Eligibility.unknown(Reason(DURATION_UNKNOWN, "stay length unverified"))
    begins_at, ends_at = _stay(start, duration_minutes)
    for neighbour in neighbours:
        if neighbour.item_id == target_item_id or neighbour.date != day or neighbour.start_time is None:
            continue
        if neighbour.duration_minutes is None:
            return Eligibility.unknown(Reason(NEIGHBOUR_DURATION_UNKNOWN, "a neighbouring stay has no verified length"))
        n_begins_at, n_ends_at = _stay(neighbour.start_time, neighbour.duration_minutes)
        if (begins_at < n_ends_at and n_begins_at < ends_at) or start == neighbour.start_time:
            return Eligibility.ineligible(Reason(OVERLAPS_NEIGHBOUR, "overlaps another item on that date"))
    return Eligibility.eligible()


def route_evidence(
    neighbours: Sequence[NeighbourItem],
    target_item_id: UUID | None,
    from_date: date,
    to_date: date,
    evidence: RouteEvidence,
) -> Eligibility:
    """Travel legs change when either the source day or the destination day has other items.

    `target_item_id` is the item being moved, which is not a neighbour of itself. It is None when the
    subject is not on the itinerary at all (a saved candidate): every item on those days is then a
    neighbour. Never pass a placeholder id - one would silently excuse a real item from the check.
    """
    legs_affected = any(
        (target_item_id is None or n.item_id != target_item_id) and n.date in (from_date, to_date) for n in neighbours
    )
    if legs_affected and evidence is not RouteEvidence.VERIFIED:
        return Eligibility.unknown(Reason(ROUTE_EVIDENCE_MISSING, "travel legs change without route evidence"))
    return Eligibility.eligible()
