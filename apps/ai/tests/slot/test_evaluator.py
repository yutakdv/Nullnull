"""BA-042, REC-SLOT-02: which trip dates could hold one candidate, without inventing a time.

The cases are the Java-era Task 5 corpus. Every expectation is fixed by policy-v1
(`candidateCaps.slotDates` = 30, the trip maximum of spec §4.1), so a policy change fails here
instead of silently re-baselining, and `suggestedTime` is asserted absent everywhere: P0 proposes a
date, never a time.
"""

from __future__ import annotations

import random
from collections.abc import Iterable, Mapping
from datetime import UTC, date, datetime, time, timedelta
from uuid import UUID
from zoneinfo import ZoneInfo

import pytest

from nullnull_ai.domain.policy import load_default
from nullnull_ai.domain.types import RecommendationContext
from nullnull_ai.item.types import Closed, NeighbourItem, OpeningWindow, OpenWindow, RouteEvidence, UnknownHours
from nullnull_ai.slot.evaluator import (
    DATE_CAP_EXCEEDED,
    CandidateSlotInput,
    Slot,
    SlotEvaluator,
    SlotResult,
    SlotState,
)

TRIP = UUID("018f3f8e-9b67-7a21-8d31-31d315b93c01")
CANDIDATE = UUID("018f3f8e-9b67-7a21-8d31-31d315b93d01")
PLACE = UUID("018f3f8e-9b67-7a21-8d31-31d315b93a01")
NEIGHBOUR = UUID("018f3f8e-9b67-7a21-8d31-31d315b93b02")
D12 = date(2026, 9, 12)
D13 = date(2026, 9, 13)
D14 = date(2026, 9, 14)
SEOUL = ZoneInfo("Asia/Seoul")
OPEN = OpenWindow(time(9, 0), time(18, 0))
SEED = 20260906  # tests/recommendation/manifest.json randomSeeds[0]

POLICY = load_default()
CONTEXT = RecommendationContext(datetime(2026, 9, 6, tzinfo=UTC), POLICY.version, POLICY.hash, "catalog-test-1")
evaluator = SlotEvaluator(POLICY)

EMITTED = frozenset({"DUPLICATE_PLACE", "DAY_ITEM_LIMIT", "CLOSED", "OPENING_HOURS_UNKNOWN", "ROUTE_EVIDENCE_MISSING"})
"""The only reason codes this evaluator can produce; `api/slots.py` projects them onto the public allowlist."""


def hours(d12: OpeningWindow, d13: OpeningWindow, d14: OpeningWindow) -> dict[date, OpeningWindow]:
    return {D12: d12, D13: d13, D14: d14}


def evaluate(
    *,
    items: Iterable[NeighbourItem] = (),
    opening: Mapping[date, OpeningWindow] | None = None,
    duplicates: Iterable[date] = (),
    route: RouteEvidence = RouteEvidence.NONE,
    checking: bool = False,
    trip_end: date = D14,
    max_items_per_day: int = 20,
) -> SlotResult:
    return evaluator.evaluate(
        CONTEXT,
        CandidateSlotInput(
            trip_id=TRIP,
            candidate_id=CANDIDATE,
            place_id=PLACE,
            trip_start=D12,
            trip_end=trip_end,
            trip_zone=SEOUL,
            duration_minutes=60,
            items=tuple(items),
            opening_hours=hours(OPEN, OPEN, OPEN) if opening is None else opening,
            dates_with_same_place=frozenset(duplicates),
            route_evidence=route,
            max_items_per_day=max_items_per_day,
            checking=checking,
        ),
    )


def test_open_days_without_legs_are_exact_and_never_carry_an_invented_time() -> None:
    result = evaluate(opening=hours(OPEN, Closed(), OPEN))
    assert result.state is SlotState.EXACT
    assert [slot.date for slot in result.slots] == [D12, D13, D14]
    assert [slot.eligible for slot in result.slots] == [True, False, True]
    assert result.slots[1].reason_code == "CLOSED"
    assert all(slot.suggested_time is None for slot in result.slots)
    assert result.reasons == ()


def test_unknown_hours_or_missing_route_evidence_is_unknown_not_eligible() -> None:
    unknown = evaluate(opening=hours(UnknownHours(), UnknownHours(), Closed()))
    assert unknown.state is SlotState.UNKNOWN
    assert [slot.reason_code for slot in unknown.slots] == ["OPENING_HOURS_UNKNOWN", "OPENING_HOURS_UNKNOWN", "CLOSED"]

    # The only day whose travel legs change is the one that already holds an item (§5.4).
    no_route = evaluate(items=(NeighbourItem(NEIGHBOUR, D13, 1, time(10, 0), 60),))
    assert [(slot.date, slot.eligible, slot.reason_code) for slot in no_route.slots] == [
        (D12, True, None),
        (D13, False, "ROUTE_EVIDENCE_MISSING"),
        (D14, True, None),
    ]
    assert no_route.state is SlotState.EXACT


def test_duplicate_place_day_limit_checking_and_none() -> None:
    duplicate = evaluate(duplicates={D12})
    assert duplicate.slots[0].reason_code == "DUPLICATE_PLACE"

    full = tuple(NeighbourItem(UUID(int=index + 1), D12, index, None, None) for index in range(20))
    day_full = evaluate(items=full, route=RouteEvidence.VERIFIED)
    assert day_full.slots[0].reason_code == "DAY_ITEM_LIMIT"

    # A running verification job wins over every date verdict: the answer is not settled yet.
    running = evaluate(checking=True)
    assert running.state is SlotState.CHECKING
    assert all(slot.eligible for slot in running.slots)

    assert evaluate(opening=hours(Closed(), Closed(), Closed())).state is SlotState.NONE


def test_the_date_cap_truncates_long_trips_deterministically() -> None:
    cap = POLICY.candidate_caps.slot_dates
    assert cap == 30, "policy-v1 candidateCaps.slotDates matches the 30-date trip maximum of spec §4.1"

    exactly_capped = evaluate(opening={}, trip_end=D12 + timedelta(days=cap - 1))
    assert len(exactly_capped.slots) == cap and exactly_capped.reasons == ()

    result = evaluate(opening={}, trip_end=D12 + timedelta(days=200))
    assert len(result.slots) == cap
    assert result.slots[0].date == D12 and result.slots[-1].date == D12 + timedelta(days=cap - 1)
    assert [reason.code for reason in result.reasons] == [DATE_CAP_EXCEEDED]
    # Without any hydrated window every truncated date is unverified, never quietly eligible.
    assert result.state is SlotState.UNKNOWN


def test_a_slot_carries_a_reason_code_exactly_when_it_is_not_eligible() -> None:
    assert Slot(D12, None, True, None).eligible
    with pytest.raises(ValueError, match="reasonCode"):
        Slot(D12, None, True, "CLOSED")
    with pytest.raises(ValueError, match="reasonCode"):
        Slot(D12, None, False, None)


@pytest.mark.parametrize(
    ("overrides", "note"),
    [
        ({"trip_end": D12 - timedelta(days=1)}, "tripEnd before tripStart"),
        ({"max_items_per_day": 0}, "a day that can hold nothing"),
        ({"dates_with_same_place": frozenset({D12 - timedelta(days=1)})}, "a duplicate date before the trip"),
        ({"dates_with_same_place": frozenset({D14 + timedelta(days=1)})}, "a duplicate date after the trip"),
    ],
)
def test_inconsistent_facts_are_rejected_by_the_domain(overrides: dict[str, object], note: str) -> None:
    fields: dict[str, object] = {
        "trip_id": TRIP,
        "candidate_id": CANDIDATE,
        "place_id": PLACE,
        "trip_start": D12,
        "trip_end": D14,
        "trip_zone": SEOUL,
        "duration_minutes": 60,
        "items": (),
        "opening_hours": {},
        "dates_with_same_place": frozenset(),
        "route_evidence": RouteEvidence.NONE,
        "max_items_per_day": 20,
        "checking": False,
    }
    fields.update(overrides)
    with pytest.raises(ValueError):
        CandidateSlotInput(**fields)  # type: ignore[arg-type]


def test_every_emitted_reason_code_and_state_stays_inside_the_published_set() -> None:
    """1,000 seeded inputs: no sixth reason code, and the state always matches the slots it summarises."""
    rng = random.Random(SEED)
    windows: tuple[OpeningWindow, ...] = (OPEN, Closed(), UnknownHours())
    for iteration in range(1_000):
        span = rng.randrange(1, 6)
        days = [D12 + timedelta(days=offset) for offset in range(span)]
        result = evaluate(
            items=tuple(
                NeighbourItem(UUID(int=index + 1), rng.choice(days), index, None, None)
                for index in range(rng.randrange(0, 5))
            ),
            opening={day: rng.choice(windows) for day in days if rng.random() < 0.8},
            duplicates={day for day in days if rng.random() < 0.2},
            route=rng.choice((RouteEvidence.NONE, RouteEvidence.VERIFIED)),
            checking=rng.random() < 0.2,
            trip_end=days[-1],
            max_items_per_day=rng.randrange(1, 4),
        )
        assert [slot.date for slot in result.slots] == days, iteration
        for slot in result.slots:
            assert slot.suggested_time is None, iteration
            assert slot.eligible == (slot.reason_code is None), iteration
            assert slot.reason_code is None or slot.reason_code in EMITTED, iteration
        eligible = any(slot.eligible for slot in result.slots)
        if result.state is SlotState.EXACT:
            assert eligible, iteration
        elif result.state in (SlotState.NONE, SlotState.UNKNOWN):
            assert not eligible, iteration
