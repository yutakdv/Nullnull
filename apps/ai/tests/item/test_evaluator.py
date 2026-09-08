"""REC-OPT-01/04, REC-SLOT-02: candidate → filter → comparability → score → select → classify.

Every expected number is hand-calculated from policy-v1 (reliefWeight 0.80, changeCostWeight 0.20,
saturation 240 min, metricScale 100, minimumImprovement 5) and written out here, so a policy change
fails this file instead of silently re-baselining.
"""

from __future__ import annotations

import itertools
import random
from datetime import UTC, date, datetime, time, timedelta
from decimal import Decimal
from uuid import UUID
from zoneinfo import ZoneInfo

import pytest

from nullnull_ai.domain.policy import load_default
from nullnull_ai.domain.types import CandidateKey, RecommendationContext
from nullnull_ai.item.evaluator import ItemProposalEvaluator, ItemProposalResult, Outcome
from nullnull_ai.item.types import (
    Closed,
    ComparisonVerdict,
    DateLock,
    ForecastResolution,
    ItemLock,
    ItemOptimizationInput,
    LockType,
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

TRIP = UUID("018f3f8e-9b67-7a21-8d31-31d315b93c01")
PLACE = UUID("018f3f8e-9b67-7a21-8d31-31d315b93a01")
OTHER_PLACE = UUID("018f3f8e-9b67-7a21-8d31-31d315b93a02")
ITEM = UUID("018f3f8e-9b67-7a21-8d31-31d315b93b01")
NEIGHBOUR = UUID("018f3f8e-9b67-7a21-8d31-31d315b93b02")
D12 = date(2026, 9, 12)
D13 = date(2026, 9, 13)
D14 = date(2026, 9, 14)
D15 = date(2026, 9, 15)
SEOUL = ZoneInfo("Asia/Seoul")
METRIC = "KTO_RELATIVE_CONCENTRATION_INDEX"
SEED = 20260906  # tests/recommendation/manifest.json randomSeeds[0]
OK = ComparisonVerdict(True, "SAME_METRIC_AND_ISSUE")

POLICY = load_default()
CONTEXT = RecommendationContext(datetime(2026, 9, 6, tzinfo=UTC), POLICY.version, POLICY.hash, "catalog-test-1")
evaluator = ItemProposalEvaluator(POLICY)

# Snapshot ids only have to be distinct; a counter keeps a failure reproducible where uuid4 would not.
_snapshots = itertools.count(1)


def candidate(
    day: date,
    at: time | None,
    before: int,
    after: int,
    verdict: ComparisonVerdict = OK,
    place: UUID = PLACE,
) -> TemporalCandidate:
    return TemporalCandidate(
        key=CandidateKey(place, day, at),
        resolution=ForecastResolution.HOUR if at is not None else ForecastResolution.DAY,
        before_value=Decimal(before),
        after_value=Decimal(after),
        metric_code=METRIC,
        verdict=verdict,
        before_snapshot_id=UUID(int=next(_snapshots)),
        after_snapshot_id=UUID(int=next(_snapshots)),
    )


def open_all_days() -> dict[date, OpeningWindow]:
    window = OpenWindow(time(9, 0), time(18, 0))
    return {D12: window, D13: window, D14: window}


def evaluate(
    candidates: list[TemporalCandidate],
    *,
    locks: tuple[ItemLock, ...] = (),
    neighbours: tuple[NeighbourItem, ...] = (),
    hours: dict[date, OpeningWindow] | None = None,
    route: RouteEvidence = RouteEvidence.NONE,
    target_start: time | None = time(10, 0),
) -> ItemProposalResult:
    """The target is always the same item: D12 10:00, 90 minutes, in a D12-D14 Seoul trip."""
    optimization_input = ItemOptimizationInput(
        trip_id=TRIP,
        trip_version=7,
        trip_start=D12,
        trip_end=D14,
        trip_zone=SEOUL,
        target=TargetItem(ITEM, PLACE, D12, target_start, 90, 1),
        locks=locks,
        neighbours=neighbours,
        opening_hours=open_all_days() if hours is None else hours,
        route_evidence=route,
        candidates=tuple(candidates),
    )
    return evaluator.evaluate(CONTEXT, optimization_input)


@pytest.mark.parametrize(
    ("day", "at", "before", "after", "score", "note"),
    [
        (D12, time(12, 0), 80, 50, "0.140000", "relief .30 → .24, 120 min cost .50 → .10"),
        (D13, time(10, 0), 80, 40, "0.120000", "relief .40 → .32, 24 h saturates the cost at 1 → .20"),
        (D12, time(11, 0), 80, 60, "0.110000", "relief .20 → .16, 60 min cost .25 → .05"),
        (D12, time(13, 0), 80, 55, "0.050000", "relief .25 → .20, 180 min cost .75 → .15"),
    ],
)
def test_hand_calculated_scores(day: date, at: time, before: int, after: int, score: str, note: str) -> None:
    result = evaluate([candidate(day, at, before, after)])
    assert result.outcome is Outcome.PROPOSALS, note
    assert result.proposals[0].admission.score.score == Decimal(score), note


def test_ranks_admitted_candidates_and_caps_at_three() -> None:
    candidates = [
        candidate(D12, time(11, 0), 80, 60),  # 0.110000
        candidate(D12, time(12, 0), 80, 50),  # 0.140000
        candidate(D12, time(10, 15), 80, 77),  # improvement 3 < policy minimum 5
        candidate(D13, time(10, 0), 80, 40),  # 0.120000
        candidate(D12, time(13, 0), 80, 55),  # 0.050000, cut by the proposal cap of 3
    ]
    result = evaluate(candidates)
    assert result.outcome is Outcome.PROPOSALS
    assert [proposal.candidate.key.time for proposal in result.proposals] == [time(12, 0), time(10, 0), time(11, 0)]
    assert [proposal.rank for proposal in result.proposals] == [1, 2, 3]
    assert result.reasons == ()
    assert result.summary.evaluated == 5
    assert result.summary.rejected_by_reason == {"IMPROVEMENT_BELOW_MINIMUM": 1}
    assert result.proposals[0].before_instant == datetime(2026, 9, 12, 1, 0, tzinfo=UTC)
    assert result.proposals[0].after_instant == datetime(2026, 9, 12, 3, 0, tzinfo=UTC)
    assert result.proposals[0].proposed_start_time == time(12, 0)


def test_candidate_order_and_duplicates_do_not_change_the_result() -> None:
    base = [
        candidate(D12, time(11, 0), 80, 60),
        candidate(D12, time(12, 0), 80, 50),
        candidate(D13, time(10, 0), 80, 40),
        candidate(D12, time(13, 0), 80, 55),
    ]
    expected = evaluate(base)
    assert expected.outcome is Outcome.PROPOSALS
    fingerprint = [(p.candidate.key, p.admission.score.score) for p in expected.proposals]

    rng = random.Random(SEED)
    for iteration in range(1_000):
        shuffled = list(base)
        rng.shuffle(shuffled)
        shuffled.append(shuffled[rng.randrange(len(shuffled))])  # a repeated slot must merge, not double-rank
        actual = evaluate(shuffled)
        assert actual.outcome is Outcome.PROPOSALS, iteration
        assert [(p.candidate.key, p.admission.score.score) for p in actual.proposals] == fingerprint, iteration


def test_two_candidates_in_the_same_slot_collapse_to_the_better_one() -> None:
    weaker = candidate(D12, time(12, 0), 80, 50)  # 0.140000
    stronger = candidate(D12, time(12, 0), 80, 45)  # relief .35 → .28, 120 min cost .50 → .10 = 0.180000
    other = candidate(D12, time(11, 0), 80, 60)  # 0.110000
    result = evaluate([weaker, stronger, other])
    assert result.outcome is Outcome.PROPOSALS
    assert result.summary.evaluated == 3 and result.summary.rejected_by_reason == {}
    assert [p.admission.score.score for p in result.proposals] == [Decimal("0.180000"), Decimal("0.110000")]
    assert [p.candidate.before_snapshot_id for p in result.proposals] == [
        stronger.before_snapshot_id,
        other.before_snapshot_id,
    ]


def test_high_score_never_overrides_a_lock() -> None:
    candidates = [candidate(D13, time(10, 0), 80, 10), candidate(D12, time(11, 0), 80, 60)]
    result = evaluate(candidates, locks=(DateLock(D12),))
    assert result.outcome is Outcome.PROPOSALS
    assert len(result.proposals) == 1
    assert result.proposals[0].candidate.key.date == D12
    assert result.proposals[0].lock_checks == {LockType.DATE: True}
    assert result.summary.rejected_by_reason == {"DATE_LOCKED": 1}

    # A reservation pins both the date and the start time, so no temporal move survives it.
    pinned = evaluate(candidates, locks=(ReservationLock(D12, time(10, 0), time(11, 30)),))
    assert pinned.outcome is Outcome.LOCK_CONFLICT
    assert pinned.summary.rejected_by_reason == {"RESERVATION_LOCKED": 2}
    assert [reason.code for reason in pinned.reasons] == ["RESERVATION_LOCKED"]
    assert pinned.proposals == ()


def test_high_score_never_overrides_a_closed_day() -> None:
    hours = {D12: open_all_days()[D12], D13: Closed(), D14: open_all_days()[D14]}
    result = evaluate([candidate(D13, time(10, 0), 80, 10)], hours=hours)
    assert result.outcome is Outcome.NO_IMPROVEMENT
    assert result.summary.rejected_by_reason == {"CLOSED": 1}
    assert result.proposals == ()


def test_terminal_planes_are_distinguished() -> None:
    good = [candidate(D13, time(10, 0), 80, 40)]

    assert evaluate(good, locks=(DateLock(D12),)).outcome is Outcome.LOCK_CONFLICT

    neighbour = NeighbourItem(NEIGHBOUR, D13, 2, time(14, 0), 60)
    unrouted = evaluate(good, neighbours=(neighbour,))
    assert unrouted.outcome is Outcome.ROUTE_UNAVAILABLE
    assert unrouted.summary.rejected_by_reason == {"ROUTE_EVIDENCE_MISSING": 1}

    unknown_hours = {D12: open_all_days()[D12], D13: UnknownHours(), D14: open_all_days()[D14]}
    assert evaluate(good, hours=unknown_hours).outcome is Outcome.DATA_INSUFFICIENT

    stale = [candidate(D13, time(10, 0), 80, 40, ComparisonVerdict(False, "STALE_INPUT"))]
    assert evaluate(stale).outcome is Outcome.DATA_INSUFFICIENT

    assert evaluate([candidate(D13, time(10, 0), 80, 78)]).outcome is Outcome.NO_IMPROVEMENT

    empty = evaluate([])
    assert empty.outcome is Outcome.DATA_INSUFFICIENT
    assert [reason.code for reason in empty.reasons] == ["NO_CANDIDATES"]
    assert empty.summary.evaluated == 0 and empty.summary.rejected_by_reason == {}


def test_the_terminal_plane_follows_the_rejection_classes_not_the_first_candidate() -> None:
    """LOCK_CONFLICT only when every rejection is a lock; ROUTE outranks a data gap or a weak score."""
    hours = {D12: UnknownHours(), D13: open_all_days()[D13], D14: open_all_days()[D14]}
    mixed = evaluate(
        [candidate(D12, time(11, 0), 80, 60), candidate(D13, time(10, 0), 80, 40)],
        locks=(DateLock(D12),),
        hours=hours,
    )
    assert mixed.outcome is Outcome.DATA_INSUFFICIENT
    assert mixed.summary.rejected_by_reason == {"DATE_LOCKED": 1, "OPENING_HOURS_UNKNOWN": 1}

    neighbour = NeighbourItem(NEIGHBOUR, D13, 2, time(14, 0), 60)
    routed = evaluate(
        [candidate(D13, time(10, 0), 80, 40), candidate(D12, time(11, 0), 80, 77)],
        neighbours=(neighbour,),
    )
    assert routed.outcome is Outcome.ROUTE_UNAVAILABLE
    assert routed.summary.rejected_by_reason == {"IMPROVEMENT_BELOW_MINIMUM": 1, "ROUTE_EVIDENCE_MISSING": 1}


def test_a_structural_rejection_never_decides_the_terminal_plane() -> None:
    """D-REC-17: NO_CHANGE, OUTSIDE_TRIP_RANGE and PLACE_MISMATCH say nothing about the itinerary.

    They mean the caller offered something that was never a move of this item, so they are counted
    in the summary but must not turn a pure lock conflict into `NO_IMPROVEMENT`.
    """
    locked = evaluate(
        [candidate(D13, time(10, 0), 80, 40), candidate(D12, time(10, 0), 80, 40)],
        locks=(DateLock(D12),),
    )
    assert locked.outcome is Outcome.LOCK_CONFLICT
    assert locked.summary.rejected_by_reason == {"DATE_LOCKED": 1, "NO_CHANGE": 1}
    assert [reason.code for reason in locked.reasons] == ["DATE_LOCKED", "NO_CHANGE"]


def test_only_structurally_rejected_candidates_are_a_data_gap_not_a_missing_improvement() -> None:
    result = evaluate(
        [
            candidate(D12, time(10, 0), 80, 40),  # the current slot
            candidate(D15, time(11, 0), 80, 40),  # outside the trip
            candidate(D12, time(11, 0), 80, 40, place=OTHER_PLACE),  # another place
        ]
    )
    assert result.outcome is Outcome.DATA_INSUFFICIENT
    assert result.summary.rejected_by_reason == {"NO_CHANGE": 1, "OUTSIDE_TRIP_RANGE": 1, "PLACE_MISMATCH": 1}
    assert [reason.code for reason in result.reasons] == ["NO_CHANGE", "OUTSIDE_TRIP_RANGE", "PLACE_MISMATCH"]
    assert result.proposals == ()


def test_day_resolution_keeps_the_existing_start_time() -> None:
    result = evaluate([candidate(D13, None, 80, 40)])
    assert result.outcome is Outcome.PROPOSALS
    proposal = result.proposals[0]
    assert proposal.candidate.key.time is None
    assert proposal.proposed_start_time == time(10, 0)
    assert proposal.after_instant == datetime(2026, 9, 13, 1, 0, tzinfo=UTC)
    assert proposal.admission.score.score == Decimal("0.120000")


@pytest.mark.parametrize(("after", "score"), [(50, "0.040000"), (40, "0.120000")])
def test_an_untimed_target_pays_a_full_change_cost_for_any_time(after: int, score: str) -> None:
    """D-REC-16: an item with no start time sits at local midnight, so giving it a time is a full move.

    The convention is deliberately conservative - a cheaper date-level cost would assert that
    assigning a time is a small change, and no product decision says that yet.
    """
    result = evaluate([candidate(D12, time(11, 0), 80, after)], target_start=None)
    assert result.outcome is Outcome.PROPOSALS
    proposal = result.proposals[0]
    assert proposal.proposed_start_time == time(11, 0)
    assert proposal.before_instant == datetime(2026, 9, 11, 15, 0, tzinfo=UTC)  # D12 00:00 Seoul
    assert proposal.after_instant == datetime(2026, 9, 12, 2, 0, tzinfo=UTC)
    # 11 h apart, far past the 240 minute saturation, so the cost term is the full 0.20.
    assert proposal.admission.change_cost == Decimal("1.000000")
    assert proposal.admission.score.score == Decimal(score)


def test_an_untimed_target_with_a_day_candidate_also_pays_the_full_cost() -> None:
    result = evaluate([candidate(D13, None, 80, 40)], target_start=None)
    assert result.outcome is Outcome.PROPOSALS
    proposal = result.proposals[0]
    assert proposal.proposed_start_time is None
    assert proposal.before_instant == datetime(2026, 9, 11, 15, 0, tzinfo=UTC)
    assert proposal.after_instant == datetime(2026, 9, 12, 15, 0, tzinfo=UTC)
    assert proposal.admission.change_cost == Decimal("1.000000")
    assert proposal.admission.score.score == Decimal("0.120000")


def _cap_block(after: int) -> list[TemporalCandidate]:
    """One candidate per 5 minutes from 09:00 on D13 — exactly the detailed cap — plus 5 later-dated ones."""
    cap = POLICY.candidate_caps.item_detailed
    start = datetime.combine(D13, time(9, 0))
    inside = [candidate(D13, (start + timedelta(minutes=5 * i)).time(), 80, after) for i in range(cap)]
    # Huge improvements, but D14 sorts after every D13 slot, so the fixed cap key has to drop them.
    beyond = [candidate(D14, (start + timedelta(minutes=5 * i)).time(), 80, 10) for i in range(5)]
    return inside + beyond


def test_detailed_candidate_cap_uses_a_fixed_key_not_arrival_order() -> None:
    # after=50: relief .30 → .24, and a move of a whole day saturates the cost at .20, so every
    # surviving D13 slot scores exactly 0.040000 and only `time ASC` separates them.
    many = _cap_block(50)
    expected = evaluate(many)
    assert expected.outcome is Outcome.PROPOSALS
    assert expected.summary.evaluated == POLICY.candidate_caps.item_detailed
    assert expected.summary.rejected_by_reason["CANDIDATE_CAP_EXCEEDED"] == 5
    # 09:00 + 5·i fits a 90 minute stay in 09:00-18:00 only while the start is ≤ 16:30 (i ≤ 90).
    assert expected.summary.rejected_by_reason["OUTSIDE_OPENING_HOURS"] == 9
    assert all(proposal.candidate.after_value == Decimal(50) for proposal in expected.proposals)
    assert [proposal.candidate.key.time for proposal in expected.proposals] == [time(9, 0), time(9, 5), time(9, 10)]
    assert {proposal.admission.score.score for proposal in expected.proposals} == {Decimal("0.040000")}

    keys = [proposal.candidate.key for proposal in expected.proposals]
    rng = random.Random(SEED)
    for iteration in range(1_000):
        shuffled = list(many)
        rng.shuffle(shuffled)
        actual = evaluate(shuffled)
        assert actual.outcome is Outcome.PROPOSALS, iteration
        assert [proposal.candidate.key for proposal in actual.proposals] == keys, iteration
        assert actual.summary.rejected_by_reason["CANDIDATE_CAP_EXCEEDED"] == 5, iteration


def test_exact_duplicates_are_cut_at_the_detailed_cap_in_a_fixed_order() -> None:
    """Two candidates identical in every spec key are still separated by their snapshot pair (§6).

    The D13 block fills the cap minus one and D14 sorts after every D13 slot, so the cut falls
    exactly between the twins: a merely stable sort would keep whichever one arrived first.
    """
    cap = POLICY.candidate_caps.item_detailed
    start = datetime.combine(D13, time(9, 0))
    filler = [candidate(D13, (start + timedelta(minutes=5 * i)).time(), 80, 77) for i in range(cap - 1)]
    twins = [candidate(D14, time(10, 0), 80, 40), candidate(D14, time(10, 0), 80, 40)]
    kept = min(twins, key=lambda twin: (str(twin.before_snapshot_id), str(twin.after_snapshot_id)))

    rng = random.Random(SEED)
    for iteration in range(200):
        shuffled = filler + twins
        rng.shuffle(shuffled)
        result = evaluate(shuffled)
        assert result.summary.evaluated == cap, iteration
        assert result.summary.rejected_by_reason["CANDIDATE_CAP_EXCEEDED"] == 1, iteration
        assert [p.candidate.before_snapshot_id for p in result.proposals] == [kept.before_snapshot_id], iteration


def test_a_full_day_move_that_only_clears_the_minimum_is_not_an_improvement() -> None:
    """The same block with after=60: relief .20 → .16 minus the saturated cost .20 = -0.04.

    Its own case because it pins the cost horizon — a whole-day move has to buy more than 25 index
    points of relief before it can be proposed at all.
    """
    result = evaluate(_cap_block(60))
    assert result.outcome is Outcome.NO_IMPROVEMENT
    assert result.summary.evaluated == POLICY.candidate_caps.item_detailed
    assert result.summary.rejected_by_reason == {
        "CANDIDATE_CAP_EXCEEDED": 5,
        "OUTSIDE_OPENING_HOURS": 9,
        "SCORE_NOT_POSITIVE": 91,
    }


def test_random_hard_constraint_violations_are_never_proposed_regardless_of_score() -> None:
    """REC-OPT-04: 1,000 candidates with a huge improvement, each violating exactly one hard rule."""
    hours = {D12: open_all_days()[D12], D13: Closed(), D14: UnknownHours()}
    locks = (TimeLock(time(10, 0), 60),)
    neighbours = (NeighbourItem(NEIGHBOUR, D12, 2, time(11, 0), 60),)
    violations = [
        lambda: candidate(D13, time(10, 30), 80, 5),  # closed day
        lambda: candidate(D14, time(10, 30), 80, 5),  # opening hours unknown
        lambda: candidate(D12, time(12, 0), 80, 5),  # TIME lock tolerance 60 exceeded
        lambda: candidate(D12, time(9, 30), 80, 5, ComparisonVerdict(False, "STALE_INPUT")),  # stale pair
        lambda: candidate(D15, time(10, 30), 80, 5),  # outside the trip range
        lambda: candidate(D12, time(10, 30), 80, 5),  # overlaps the 11:00-12:00 neighbour
        lambda: candidate(D12, time(10, 30), 80, 5, place=OTHER_PLACE),  # a different place
        lambda: candidate(D12, time(10, 0), 80, 5),  # the current slot: not a change
    ]
    rng = random.Random(SEED)
    for iteration in range(1_000):
        one = violations[rng.randrange(len(violations))]()
        result = evaluate([one], locks=locks, neighbours=neighbours, hours=hours, route=RouteEvidence.VERIFIED)
        assert result.outcome is not Outcome.PROPOSALS, iteration
        assert result.proposals == (), iteration
