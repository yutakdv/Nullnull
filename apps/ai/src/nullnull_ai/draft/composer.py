"""REC-CON-04 / FR-TRC-10: place a pool of places on trip dates without inventing anything.

Pure: Spring hydrates the pool and the verified opening windows. Places are walked in `place_id`
order, so the answer does not depend on the order the pool arrived in. A place may go on a date whose
window is not CLOSED and that still holds fewer than `max_stops_per_day` stops; among those dates the
one with the fewest stops wins, the earliest on a tie. Its position is the number of stops already on
that date.

What this module deliberately does not decide: a time of day (no stop carries one), crowd levels,
interests or must-visit places. A date without a verified window is a candidate labelled UNKNOWN,
never OPEN - the label only repeats what Spring verified. A place with no candidate date is skipped
and counted; nothing is padded to fill a day.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from datetime import date, timedelta
from enum import Enum
from uuid import UUID
from zoneinfo import ZoneInfo

from nullnull_ai.item.types import Closed, OpeningWindow, OpenWindow

MAX_TRIP_DATES = 30
"""A trip spans at most 30 dates (spec §4.1); the same number bounds `openingHours` on every route."""

HOURS_CLOSED_ALL_DATES = "HOURS_CLOSED_ALL_DATES"
DAY_CAP_FULL = "DAY_CAP_FULL"
NO_ELIGIBLE_PLACES = "NO_ELIGIBLE_PLACES"
ALL_DATES_FULL = "ALL_DATES_FULL"


class DraftState(Enum):
    READY = "READY"
    EMPTY = "EMPTY"


class HoursState(Enum):
    """OPEN only when Spring sent a verified OPEN window for that date; anything else is UNKNOWN."""

    OPEN = "OPEN"
    UNKNOWN = "UNKNOWN"


@dataclass(frozen=True, slots=True)
class DraftPlace:
    place_id: UUID
    opening_hours: Mapping[date, OpeningWindow]


@dataclass(frozen=True, slots=True)
class DraftInput:
    trip_start: date
    trip_end: date
    trip_zone: ZoneInfo
    max_stops_per_day: int
    pool: tuple[DraftPlace, ...]

    def __post_init__(self) -> None:
        if self.trip_end < self.trip_start:
            raise ValueError("tripEnd before tripStart")
        if (self.trip_end - self.trip_start).days + 1 > MAX_TRIP_DATES:
            raise ValueError(f"a trip spans at most {MAX_TRIP_DATES} dates")
        if self.max_stops_per_day < 1:
            raise ValueError("maxStopsPerDay must be >= 1")
        ids = [place.place_id for place in self.pool]
        if len(set(ids)) != len(ids):
            # Two entries for one place would let it be placed twice; that is a hydration bug.
            raise ValueError("pool carries a placeId more than once")
        for place in self.pool:
            # A window outside the trip can only be a hydration bug: it would silently decide nothing.
            if any(day < self.trip_start or day > self.trip_end for day in place.opening_hours):
                raise ValueError("openingHours must lie inside the trip range")


@dataclass(frozen=True, slots=True)
class DraftStop:
    """One placed place. There is no time field: P0 proposes a date and never invents a time."""

    place_id: UUID
    date: date
    position: int
    hours_state: HoursState


@dataclass(frozen=True, slots=True)
class DraftResult:
    state: DraftState
    stops: tuple[DraftStop, ...]
    reasons: tuple[str, ...]
    evaluated: int
    rejected_by_reason: Mapping[str, int]


def compose(inp: DraftInput) -> DraftResult:
    dates = [inp.trip_start + timedelta(days=offset) for offset in range((inp.trip_end - inp.trip_start).days + 1)]
    placed: dict[date, list[DraftStop]] = {day: [] for day in dates}
    rejected: dict[str, int] = {}
    for place in sorted(inp.pool, key=lambda p: p.place_id):
        open_dates = [day for day in dates if not isinstance(place.opening_hours.get(day), Closed)]
        candidates = [day for day in open_dates if len(placed[day]) < inp.max_stops_per_day]
        if not candidates:
            reason = HOURS_CLOSED_ALL_DATES if not open_dates else DAY_CAP_FULL
            rejected[reason] = rejected.get(reason, 0) + 1
            continue
        # `dates` is ascending and min() keeps the first minimum, so a tie goes to the earliest date.
        day = min(candidates, key=lambda d: len(placed[d]))
        hours = HoursState.OPEN if isinstance(place.opening_hours.get(day), OpenWindow) else HoursState.UNKNOWN
        placed[day].append(DraftStop(place.place_id, day, len(placed[day]), hours))
    stops = tuple(stop for day in dates for stop in placed[day])
    reasons: list[str] = []
    if not stops:
        reasons.append(NO_ELIGIBLE_PLACES)
    if rejected.get(DAY_CAP_FULL, 0) > 0:
        reasons.append(ALL_DATES_FULL)
    return DraftResult(
        state=DraftState.READY if stops else DraftState.EMPTY,
        stops=stops,
        reasons=tuple(reasons),
        evaluated=len(inp.pool),
        rejected_by_reason=dict(sorted(rejected.items())),
    )
