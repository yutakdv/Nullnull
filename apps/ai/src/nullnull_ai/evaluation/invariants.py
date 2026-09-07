"""REC-CI-4 hard-rule checker, re-derived from the raw input.

This module deliberately does NOT import `nullnull_ai.item.filters` or `nullnull_ai.item.evaluator`,
and the proposals arrive through a structural view rather than the evaluator's own class. How much
independence that buys differs per rule, so be precise about it:

- Independent re-statements, written from the rule text rather than from the filter code: the four
  locks, the trip range, the opening window (including the stay length and the past-midnight wrap),
  `verdict.eligible`, the minimum improvement, `score > 0`, same place, rank contiguity and
  duplicate slots. A bug that the evaluator and `filters.py` share still surfaces here as a hard
  violation. `MINIMUM_IMPROVEMENT` is likewise policy-v1 restated by hand, so relaxing the policy
  cannot silently re-baseline the corpus - the constant and the fixtures must change together.
- Re-statements of the *same algorithm* as `filters.py`: neighbour overlap, route evidence and the
  `_stay`/`_minutes` helpers. They exist so the counters cover the whole REC-CI-4 section 4.2
  definition of a hard violation ("권한·잠금·영업·route"), but their logic mirrors the filters closely
  enough that a bug shared with them would NOT be detected here. Treat those three as coverage, not
  as an independent check.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from decimal import Decimal
from typing import Protocol
from uuid import UUID

from nullnull_ai.domain.types import ScoreBreakdown
from nullnull_ai.item.types import (
    Closed,
    DateLock,
    ItemOptimizationInput,
    MustVisitLock,
    OpeningWindow,
    OpenWindow,
    ReservationLock,
    RouteEvidence,
    TemporalCandidate,
    TimeLock,
    UnknownHours,
)

KTO_METRIC = "KTO_RELATIVE_CONCENTRATION_INDEX"
# policy-v1 metrics.KTO_RELATIVE_CONCENTRATION_INDEX.minimumImprovement, restated independently.
MINIMUM_IMPROVEMENT: dict[str, Decimal] = {KTO_METRIC: Decimal(5)}

_EPOCH = date(2000, 1, 1)
_DAY_END = datetime.combine(_EPOCH, time.min) + timedelta(days=1)
_NIL = UUID(int=0)


class AdmissionView(Protocol):
    @property
    def score(self) -> ScoreBreakdown: ...


class ProposalView(Protocol):
    @property
    def rank(self) -> int: ...

    @property
    def candidate(self) -> TemporalCandidate: ...

    @property
    def proposed_start_time(self) -> time | None: ...

    @property
    def admission(self) -> AdmissionView: ...


@dataclass(frozen=True, slots=True)
class InvariantReport:
    """Counters REC-CI-4 §4.2 asks for, computed over one evaluation's returned proposals."""

    violations: tuple[str, ...]
    unsupported_comparisons: int
    provenance_satisfied: int
    provenance_checked: int


def check(inp: ItemOptimizationInput, proposals: Sequence[ProposalView]) -> InvariantReport:
    found: list[str] = []
    unsupported = 0
    provenance = 0
    seen: set[tuple[date, time | None]] = set()
    for index, proposal in enumerate(proposals):
        label = f"proposal {index + 1}"
        if proposal.rank != index + 1:
            found.append(f"{label}: rank {proposal.rank} breaks 1..n contiguity")
        slot = (_day(proposal), proposal.proposed_start_time)
        if slot in seen:
            found.append(f"{label}: duplicate slot {slot[0]} {slot[1]}")
        seen.add(slot)
        found.extend(f"{label}: {violation}" for violation in _violations(inp, proposal))
        if not proposal.candidate.verdict.eligible:
            unsupported += 1
        if _provenance_complete(proposal.candidate):
            provenance += 1
    return InvariantReport(tuple(found), unsupported, provenance, len(proposals))


def _violations(inp: ItemOptimizationInput, proposal: ProposalView) -> list[str]:
    day = _day(proposal)
    at = proposal.proposed_start_time
    duration = inp.target.duration_minutes
    found: list[str] = []
    if day < inp.trip_start or day > inp.trip_end:
        found.append(f"date {day} is outside the trip range {inp.trip_start}..{inp.trip_end}")
    if proposal.candidate.key.place_id != inp.target.place_id:
        found.append("the proposed place is not the target place")
    found.extend(_lock_violations(inp, day, at, duration))
    found.extend(_opening_violations(inp.opening_hours.get(day, UnknownHours()), at, duration))
    found.extend(_neighbour_violations(inp, day, at, duration))
    found.extend(_route_violations(inp, day))
    if not proposal.candidate.verdict.eligible:
        found.append(f"comparison is not eligible ({proposal.candidate.verdict.reason_code})")
    minimum = MINIMUM_IMPROVEMENT.get(proposal.candidate.metric_code)
    if minimum is None:
        found.append(f"no independently known minimum improvement for metric {proposal.candidate.metric_code}")
    elif proposal.candidate.before_value - proposal.candidate.after_value < minimum:
        found.append("improvement is below the policy minimum")
    if proposal.admission.score.score <= 0:
        found.append("score is not positive")
    return found


def _lock_violations(inp: ItemOptimizationInput, day: date, at: time | None, duration: int | None) -> list[str]:
    found: list[str] = []
    for lock in inp.locks:
        match lock:
            case MustVisitLock():
                continue  # a temporal move keeps the visit; the place itself is checked separately
            case DateLock(date=locked):
                if locked != day:
                    found.append(f"DATE lock pins {locked}")
            case TimeLock(start_time=start, tolerance_minutes=tolerance):
                if at is None or _minutes(start, at) > tolerance:
                    found.append(f"TIME lock pins {start} +/- {tolerance} minutes")
            case ReservationLock(date=locked, start_time=start, end_time=end):
                if locked != day or at != start:
                    found.append(f"RESERVATION lock pins {locked} {start}")
                elif end is not None and duration is not None and not _fits_before(at, duration, end):
                    found.append(f"the stay does not end by the reservation end {end}")
    return found


def _opening_violations(window: OpeningWindow, at: time | None, duration: int | None) -> list[str]:
    match window:
        case UnknownHours():
            return ["the opening hours of that date are unverified"]
        case Closed():
            return ["the place is closed on that date"]
        case OpenWindow(opens_at=opens, closes_at=closes):
            if at is None:
                return []
            if duration is None:
                return ["a timed proposal without a verified stay length"]
            if at < opens or not _fits_before(at, duration, closes):
                return [f"the stay is not inside the opening window {opens}-{closes}"]
            return []


def _neighbour_violations(inp: ItemOptimizationInput, day: date, at: time | None, duration: int | None) -> list[str]:
    """A returned stay never overlaps another item of that date, and never guesses a missing length."""
    if at is None:
        return []
    if duration is None:
        return ["a timed proposal without a verified stay length"]
    begins, ends = _stay(at, duration)
    for neighbour in inp.neighbours:
        if neighbour.item_id == inp.target.item_id or neighbour.date != day or neighbour.start_time is None:
            continue
        if neighbour.duration_minutes is None:
            return [f"a neighbouring stay at {neighbour.start_time} has no verified length"]
        other_begins, other_ends = _stay(neighbour.start_time, neighbour.duration_minutes)
        if (begins < other_ends and other_begins < ends) or at == neighbour.start_time:
            return [f"the stay overlaps the item at {neighbour.start_time} on {day}"]
    return []


def _route_violations(inp: ItemOptimizationInput, day: date) -> list[str]:
    """Moving between days whose travel legs change needs verified route evidence (section 5.4)."""
    legs_change = any(
        neighbour.item_id != inp.target.item_id and neighbour.date in (inp.target.date, day)
        for neighbour in inp.neighbours
    )
    if legs_change and inp.route_evidence is not RouteEvidence.VERIFIED:
        return ["travel legs change without verified route evidence"]
    return []


def _stay(start: time, duration: int) -> tuple[datetime, datetime]:
    """A stay running past midnight occupies the rest of its own date, never the next morning."""
    begins = datetime.combine(_EPOCH, start)
    return begins, min(begins + timedelta(minutes=duration), _DAY_END)


def _fits_before(start: time, duration: int, limit: time) -> bool:
    """True when start + duration lands on the same date and no later than `limit`."""
    end = datetime.combine(_EPOCH, start) + timedelta(minutes=duration)
    return end.date() == _EPOCH and end.time() <= limit


def _minutes(a: time, b: time) -> int:
    return abs(int((datetime.combine(_EPOCH, b) - datetime.combine(_EPOCH, a)).total_seconds())) // 60


def _provenance_complete(candidate: TemporalCandidate) -> bool:
    """REC-CI-4 required provenance coverage: a proposed pair carries two distinct real snapshots."""
    return (
        candidate.before_snapshot_id != _NIL
        and candidate.after_snapshot_id != _NIL
        and candidate.before_snapshot_id != candidate.after_snapshot_id
        and bool(candidate.metric_code.strip())
    )


def _day(proposal: ProposalView) -> date:
    day = proposal.candidate.key.date
    if day is None:
        raise ValueError("a temporal proposal always carries a date")
    return day
