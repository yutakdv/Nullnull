"""REC-DRAFT-01..04: placing a pool of places on trip dates without inventing a time or a verdict.

Each REC-DRAFT test states one clause (AGENTS.md CI 등록 규칙 3). The composer works on a trip of at
most 30 dates and never sees an owner, an interest, a must-visit place, a crowd value or a time.
"""

from __future__ import annotations

import random
from collections.abc import Mapping
from dataclasses import fields
from datetime import date, time, timedelta
from typing import Any
from uuid import UUID
from zoneinfo import ZoneInfo

import pytest

from nullnull_ai.draft.composer import (
    ALL_DATES_FULL,
    DAY_CAP_FULL,
    HOURS_CLOSED_ALL_DATES,
    NO_ELIGIBLE_PLACES,
    DraftInput,
    DraftPlace,
    DraftResult,
    DraftState,
    DraftStop,
    HoursState,
    compose,
)
from nullnull_ai.item.types import Closed, OpeningWindow, OpenWindow, UnknownHours

D12 = date(2026, 9, 12)
D13 = date(2026, 9, 13)
D14 = date(2026, 9, 14)
SEOUL = ZoneInfo("Asia/Seoul")
OPEN = OpenWindow(time(9, 0), time(18, 0))
SEED = 20260906  # tests/recommendation/manifest.json randomSeeds[0]


def place(n: int, hours: Mapping[date, OpeningWindow] | None = None) -> DraftPlace:
    return DraftPlace(UUID(int=n), dict(hours or {}))


def draft(pool: list[DraftPlace], *, cap: int = 3, trip_start: date = D12, trip_end: date = D14) -> DraftResult:
    return compose(DraftInput(trip_start, trip_end, SEOUL, cap, tuple(pool)))


def layout(result: DraftResult) -> list[tuple[int, date, int]]:
    return [(stop.place_id.int, stop.date, stop.position) for stop in result.stops]


def test_rec_draft_01_the_draft_does_not_depend_on_the_order_the_pool_arrived_in() -> None:
    """REC-DRAFT-01: every shuffle of one pool yields the identical draft (200 seeded pools x 5 shuffles)."""
    rng = random.Random(SEED)
    windows: tuple[OpeningWindow | None, ...] = (OPEN, Closed(), UnknownHours(), None)
    days = [D12 + timedelta(days=offset) for offset in range(4)]
    differing_orders = 0
    for _ in range(200):
        pool = [
            place(n, {day: window for day in days if (window := rng.choice(windows)) is not None})
            for n in rng.sample(range(1, 50), rng.randrange(2, 14))
        ]
        cap = rng.randrange(1, 4)
        expected = draft(pool, cap=cap, trip_end=days[-1])
        for _ in range(5):
            shuffled = pool[:]
            rng.shuffle(shuffled)
            differing_orders += shuffled != pool
            assert draft(shuffled, cap=cap, trip_end=days[-1]) == expected
    # The property is vacuous if every shuffle happened to return the original order.
    assert differing_orders > 900


def test_rec_draft_01_a_reversed_pool_places_the_lowest_place_id_first() -> None:
    """REC-DRAFT-01: the walk is place_id ascending, so a reversed pool fills the same dates."""
    forward = draft([place(1), place(2), place(3), place(4)], cap=1)
    backward = draft([place(4), place(3), place(2), place(1)], cap=1)
    assert layout(forward) == layout(backward) == [(1, D12, 0), (2, D13, 0), (3, D14, 0)]
    assert forward.rejected_by_reason == backward.rejected_by_reason == {DAY_CAP_FULL: 1}


def test_rec_draft_02_each_place_goes_to_the_date_with_fewest_stops_earliest_on_a_tie() -> None:
    """REC-DRAFT-02: seven places over three dates, cap 3 - round-robin by fewest stops, ties to the earliest date."""
    result = draft([place(n) for n in range(1, 8)], cap=3)
    assert layout(result) == [
        (1, D12, 0),
        (4, D12, 1),
        (7, D12, 2),
        (2, D13, 0),
        (5, D13, 1),
        (3, D14, 0),
        (6, D14, 1),
    ]
    assert result.state is DraftState.READY and result.reasons == ()


def test_rec_draft_02_no_date_holds_more_than_the_cap_and_the_overflow_is_counted() -> None:
    """REC-DRAFT-02: cap 2 over three dates holds six; the four left over are DAY_CAP_FULL and ALL_DATES_FULL is set."""
    result = draft([place(n) for n in range(1, 11)], cap=2)
    per_day = {day: [stop for stop in result.stops if stop.date == day] for day in (D12, D13, D14)}
    assert [len(stops) for stops in per_day.values()] == [2, 2, 2]
    assert all([stop.position for stop in stops] == [0, 1] for stops in per_day.values())
    assert result.rejected_by_reason == {DAY_CAP_FULL: 4}
    assert result.reasons == (ALL_DATES_FULL,)
    assert result.evaluated == 10


def test_rec_draft_03_a_closed_date_never_holds_the_place() -> None:
    """REC-DRAFT-03: a place CLOSED on the emptiest dates still goes only to a date that is not CLOSED."""
    first = place(1, {D12: Closed(), D13: Closed()})
    result = draft([first], cap=3)
    assert layout(result) == [(1, D14, 0)]
    everything_closed = place(2, {D12: Closed(), D13: Closed(), D14: Closed()})
    rejected = draft([everything_closed, place(3)], cap=3)
    assert layout(rejected) == [(3, D12, 0)]
    assert rejected.rejected_by_reason == {HOURS_CLOSED_ALL_DATES: 1}
    assert ALL_DATES_FULL not in rejected.reasons


def test_rec_draft_03_only_a_verified_open_window_is_labelled_open() -> None:
    """REC-DRAFT-03: OPEN only from an OPEN window; an UNKNOWN window or no window at all is UNKNOWN."""
    result = draft([place(1, {D12: OPEN}), place(2, {D13: UnknownHours()}), place(3)], cap=3)
    assert [(stop.place_id.int, stop.date, stop.hours_state) for stop in result.stops] == [
        (1, D12, HoursState.OPEN),
        (2, D13, HoursState.UNKNOWN),
        (3, D14, HoursState.UNKNOWN),
    ]


def test_rec_draft_03_a_stop_carries_no_time_field() -> None:
    """REC-DRAFT-03: the stop type has exactly place, date, position and hours - no time of day to invent."""
    assert {field.name for field in fields(DraftStop)} == {"place_id", "date", "position", "hours_state"}


def test_rec_draft_04_an_empty_pool_is_empty_and_places_nothing() -> None:
    """REC-DRAFT-04: no place in the pool gives EMPTY with no stop and NO_ELIGIBLE_PLACES."""
    result = draft([], cap=3)
    assert result.state is DraftState.EMPTY
    assert result.stops == ()
    assert result.reasons == (NO_ELIGIBLE_PLACES,)
    assert result.evaluated == 0 and result.rejected_by_reason == {}


def test_rec_draft_04_a_pool_closed_on_every_date_is_empty_and_places_nothing() -> None:
    """REC-DRAFT-04: every place CLOSED on every date gives EMPTY with no stop, each counted as closed."""
    closed = {D12: Closed(), D13: Closed(), D14: Closed()}
    result = draft([place(1, closed), place(2, closed)], cap=3)
    assert result.state is DraftState.EMPTY
    assert result.stops == ()
    assert result.reasons == (NO_ELIGIBLE_PLACES,)
    assert result.rejected_by_reason == {HOURS_CLOSED_ALL_DATES: 2}


@pytest.mark.parametrize(
    ("overrides", "note"),
    [
        ({"trip_end": D12 - timedelta(days=1)}, "tripEnd before tripStart"),
        ({"trip_end": D12 + timedelta(days=30)}, "31 dates"),
        ({"max_stops_per_day": 0}, "a zero day cap"),
        ({"pool": (place(1), place(1))}, "one place twice"),
        ({"pool": (place(1, {D14 + timedelta(days=1): OPEN}),)}, "a window after the trip"),
        ({"pool": (place(1, {D12 - timedelta(days=1): OPEN}),)}, "a window before the trip"),
    ],
)
def test_inconsistent_facts_are_rejected_by_the_domain(overrides: dict[str, Any], note: str) -> None:
    arguments: dict[str, Any] = {
        "trip_start": D12,
        "trip_end": D14,
        "trip_zone": SEOUL,
        "max_stops_per_day": 3,
        "pool": (),
    }
    arguments.update(overrides)
    with pytest.raises(ValueError):
        DraftInput(**arguments)


def test_a_thirty_date_trip_is_accepted() -> None:
    """The bound is inclusive: 30 dates is the spec §4.1 maximum, not one past it."""
    result = draft([place(1)], cap=1, trip_end=D12 + timedelta(days=29))
    assert layout(result) == [(1, D12, 0)]
