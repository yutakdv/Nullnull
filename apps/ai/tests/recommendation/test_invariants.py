"""The independent checker only protects the corpus if it actually detects a violation.

Each case plants exactly one broken proposal and asserts the checker names it; the first test pins
the other direction, that a proposal satisfying every rule reports nothing.
"""

from __future__ import annotations

import ast
from dataclasses import dataclass, replace
from datetime import date, time
from decimal import Decimal
from pathlib import Path
from uuid import UUID
from zoneinfo import ZoneInfo

import pytest

from nullnull_ai.domain.types import CandidateKey, ScoreBreakdown
from nullnull_ai.evaluation import invariants
from nullnull_ai.item.types import (
    Closed,
    ComparisonVerdict,
    DateLock,
    ForecastResolution,
    ItemLock,
    ItemOptimizationInput,
    MustVisitLock,
    NeighbourItem,
    OpeningWindow,
    OpenWindow,
    ReservationLock,
    RouteEvidence,
    TargetItem,
    TemporalCandidate,
    TimeLock,
    UnknownHours,
)

PLACE = UUID("018f3f8e-9b67-7a21-8d31-31d315b93a01")
OTHER_PLACE = UUID("018f3f8e-9b67-7a21-8d31-31d315b93a02")
ITEM = UUID("018f3f8e-9b67-7a21-8d31-31d315b93b01")
D12 = date(2026, 9, 12)
D13 = date(2026, 9, 13)
D20 = date(2026, 9, 20)
METRIC = invariants.KTO_METRIC
OK = ComparisonVerdict(True, "SAME_METRIC_AND_ISSUE")
OPEN = OpenWindow(time(9), time(18))
BEFORE_SNAPSHOT = UUID(int=11)
AFTER_SNAPSHOT = UUID(int=12)


@dataclass(frozen=True, slots=True)
class Admission:
    score: ScoreBreakdown


@dataclass(frozen=True, slots=True)
class Proposal:
    rank: int
    candidate: TemporalCandidate
    proposed_start_time: time | None
    admission: Admission


def candidate(
    day: date = D12,
    at: time | None = time(12),
    before: str = "80",
    after: str = "50",
    verdict: ComparisonVerdict = OK,
    place: UUID = PLACE,
    metric: str = METRIC,
    before_snapshot: UUID = BEFORE_SNAPSHOT,
    after_snapshot: UUID = AFTER_SNAPSHOT,
) -> TemporalCandidate:
    return TemporalCandidate(
        key=CandidateKey(place, day, at),
        resolution=ForecastResolution.HOUR if at is not None else ForecastResolution.DAY,
        before_value=Decimal(before),
        after_value=Decimal(after),
        metric_code=metric,
        verdict=verdict,
        before_snapshot_id=before_snapshot,
        after_snapshot_id=after_snapshot,
    )


def proposal(rank: int = 1, score: str = "0.140000", **kwargs: object) -> Proposal:
    item = candidate(**kwargs)  # type: ignore[arg-type]
    return Proposal(rank, item, item.key.time, Admission(ScoreBreakdown(Decimal(score))))


def optimization_input(
    locks: tuple[ItemLock, ...] = (),
    hours: dict[date, OpeningWindow] | None = None,
    duration: int | None = 90,
    neighbours: tuple[NeighbourItem, ...] = (),
    evidence: RouteEvidence = RouteEvidence.NONE,
) -> ItemOptimizationInput:
    return ItemOptimizationInput(
        trip_id=UUID("018f3f8e-9b67-7a21-8d31-31d315b93c01"),
        trip_version=7,
        trip_start=D12,
        trip_end=D13,
        trip_zone=ZoneInfo("Asia/Seoul"),
        target=TargetItem(ITEM, PLACE, D12, time(10), duration, 1),
        locks=locks,
        neighbours=neighbours,
        opening_hours={D12: OPEN, D13: OPEN} if hours is None else hours,
        route_evidence=evidence,
        candidates=(),
    )


def test_a_rule_abiding_proposal_reports_nothing() -> None:
    report = invariants.check(optimization_input(), [proposal()])
    assert report.violations == ()
    assert report.unsupported_comparisons == 0
    assert (report.provenance_satisfied, report.provenance_checked) == (1, 1)


def test_an_empty_result_reports_an_empty_provenance_denominator() -> None:
    report = invariants.check(optimization_input(), [])
    assert report == invariants.InvariantReport((), 0, 0, 0)


@pytest.mark.parametrize(
    ("name", "inp", "item", "message"),
    [
        ("outside the trip", optimization_input(), proposal(day=D20), "outside the trip range"),
        ("another place", optimization_input(), proposal(place=OTHER_PLACE), "not the target place"),
        ("DATE lock", optimization_input(locks=(DateLock(D13),)), proposal(), "DATE lock pins"),
        ("TIME lock", optimization_input(locks=(TimeLock(time(10), 30),)), proposal(), "TIME lock pins"),
        (
            "RESERVATION date",
            optimization_input(locks=(ReservationLock(D13, time(12), None),)),
            proposal(),
            "RESERVATION lock pins",
        ),
        (
            "RESERVATION end",
            optimization_input(locks=(ReservationLock(D12, time(12), time(13)),)),
            proposal(),
            "does not end by the reservation end",
        ),
        ("closed day", optimization_input(hours={D12: Closed()}), proposal(), "closed on that date"),
        ("unknown hours", optimization_input(hours={D12: UnknownHours()}), proposal(), "unverified"),
        ("missing day", optimization_input(hours={}), proposal(), "unverified"),
        (
            "after closing",
            optimization_input(hours={D12: OpenWindow(time(9), time(13))}),
            proposal(at=time(12, 30)),
            "not inside the opening window",
        ),
        (
            "before opening",
            optimization_input(hours={D12: OpenWindow(time(13), time(18))}),
            proposal(),
            "not inside the opening window",
        ),
        (
            "stay wraps past midnight",
            optimization_input(hours={D12: OpenWindow(time(9), time(23, 59))}, duration=120),
            proposal(at=time(23, 30)),
            "not inside the opening window",
        ),
        ("unknown stay length", optimization_input(duration=None), proposal(), "without a verified stay length"),
        (
            "ineligible comparison",
            optimization_input(),
            proposal(verdict=ComparisonVerdict(False, "STALE_INPUT")),
            "comparison is not eligible",
        ),
        ("improvement below 5", optimization_input(), proposal(after="76"), "below the policy minimum"),
        ("unknown metric", optimization_input(), proposal(metric="SEOUL_LIVE_LEVEL"), "no independently known minimum"),
        ("zero score", optimization_input(), proposal(score="0.000000"), "score is not positive"),
        ("negative score", optimization_input(), proposal(score="-0.002000"), "score is not positive"),
        ("rank does not start at 1", optimization_input(), proposal(rank=2), "breaks 1..n contiguity"),
        (
            "nil snapshot id",
            optimization_input(),
            proposal(before_snapshot=UUID(int=0)),
            None,
        ),
    ],
)
def test_a_planted_violation_is_detected(
    name: str, inp: ItemOptimizationInput, item: Proposal, message: str | None
) -> None:
    report = invariants.check(inp, [item])
    if message is None:
        assert report.violations == ()
        assert (report.provenance_satisfied, report.provenance_checked) == (0, 1)
    else:
        assert any(message in violation for violation in report.violations), report.violations


NEIGHBOUR = NeighbourItem(UUID("018f3f8e-9b67-7a21-8d31-31d315b93b02"), D12, 2, time(12, 30), 60)


def test_an_overlapping_neighbour_is_reported() -> None:
    inp = optimization_input(neighbours=(NEIGHBOUR,), evidence=RouteEvidence.VERIFIED)
    report = invariants.check(inp, [proposal()])
    assert any("overlaps the item at 12:30:00" in violation for violation in report.violations)


def test_a_neighbour_without_a_verified_length_is_reported() -> None:
    inp = optimization_input(
        neighbours=(replace(NEIGHBOUR, start_time=time(16), duration_minutes=None),),
        evidence=RouteEvidence.VERIFIED,
    )
    report = invariants.check(inp, [proposal()])
    assert any("has no verified length" in violation for violation in report.violations)


def test_a_confirmed_overlap_is_reported_whichever_neighbour_comes_first() -> None:
    """Mirrors `filters.neighbour_overlap`: a known overlap outranks an unmeasured neighbour (§6)."""
    unmeasured = NeighbourItem(UUID("018f3f8e-9b67-7a21-8d31-31d315b93b03"), D12, 3, time(9, 30), None)
    for neighbours in ((unmeasured, NEIGHBOUR), (NEIGHBOUR, unmeasured)):
        report = invariants.check(
            optimization_input(neighbours=neighbours, evidence=RouteEvidence.VERIFIED), [proposal()]
        )
        assert any("overlaps the item at 12:30:00" in violation for violation in report.violations), report.violations


def test_changed_travel_legs_without_route_evidence_are_reported() -> None:
    inp = optimization_input(neighbours=(replace(NEIGHBOUR, start_time=time(16)),))
    report = invariants.check(inp, [proposal()])
    assert any("without verified route evidence" in violation for violation in report.violations)


def test_a_verified_route_with_a_non_overlapping_neighbour_reports_nothing() -> None:
    inp = optimization_input(neighbours=(replace(NEIGHBOUR, start_time=time(16)),), evidence=RouteEvidence.VERIFIED)
    assert invariants.check(inp, [proposal()]).violations == ()


def test_a_must_visit_lock_never_blocks_a_temporal_move() -> None:
    assert invariants.check(optimization_input(locks=(MustVisitLock(),)), [proposal()]).violations == ()


def test_a_reservation_lock_that_is_satisfied_reports_nothing() -> None:
    inp = optimization_input(locks=(ReservationLock(D12, time(12), time(13, 30)),))
    assert invariants.check(inp, [proposal()]).violations == ()


def test_duplicate_slots_and_rank_gaps_are_reported() -> None:
    first = proposal()
    second = replace(first, rank=3)
    report = invariants.check(optimization_input(), [first, second])
    assert any("duplicate slot" in violation for violation in report.violations)
    assert any("breaks 1..n contiguity" in violation for violation in report.violations)


def test_an_ineligible_proposal_counts_as_an_unsupported_comparison() -> None:
    report = invariants.check(optimization_input(), [proposal(verdict=ComparisonVerdict(False, "REPLAY_INPUT"))])
    assert report.unsupported_comparisons == 1


def test_identical_snapshot_ids_do_not_count_as_provenance() -> None:
    report = invariants.check(optimization_input(), [proposal(before_snapshot=UUID(int=7), after_snapshot=UUID(int=7))])
    assert (report.provenance_satisfied, report.provenance_checked) == (0, 1)


def test_the_checker_does_not_import_the_evaluator_or_its_filters() -> None:
    """Independence is the point of this module, so it is enforced, not just documented."""
    tree = ast.parse(Path(invariants.__file__).read_text(encoding="utf-8"))
    imported: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            imported.update(alias.name for alias in node.names)
        elif isinstance(node, ast.ImportFrom):
            imported.add(node.module or "")
    assert "nullnull_ai.item.filters" not in imported
    assert "nullnull_ai.item.evaluator" not in imported
