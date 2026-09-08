"""Trip-local date/time → UTC instant using the trip zone only.

A local time that does not exist (DST gap) or exists twice (DST overlap) is reported, never
resolved by guessing (RECOMMENDATION_ALGORITHM.md §5.3). A missing time means the start of the day.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, date, datetime, time
from zoneinfo import ZoneInfo


@dataclass(frozen=True, slots=True)
class Exact:
    instant: datetime


@dataclass(frozen=True, slots=True)
class Gap:
    pass


@dataclass(frozen=True, slots=True)
class Overlap:
    pass


Resolution = Exact | Gap | Overlap


def resolve(day: date, at: time | None, zone: ZoneInfo) -> Resolution:
    local = datetime.combine(day, at or time.min)
    first = local.replace(tzinfo=zone, fold=0).astimezone(UTC)
    # A non-existent local time never round-trips; check it BEFORE the fold comparison, because a
    # gap also yields two different fold instants and would otherwise be misreported as Overlap.
    if first.astimezone(zone).replace(tzinfo=None) != local:
        return Gap()
    second = local.replace(tzinfo=zone, fold=1).astimezone(UTC)
    if first != second:
        return Overlap()
    return Exact(first)
