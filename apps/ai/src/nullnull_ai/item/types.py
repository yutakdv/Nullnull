"""ITEM optimization input/output types (RECOMMENDATION_ALGORITHM.md §5.4)."""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from datetime import date, time
from decimal import Decimal
from enum import Enum
from uuid import UUID
from zoneinfo import ZoneInfo

from nullnull_ai.domain.types import CandidateKey


class LockType(Enum):
    MUST_VISIT = "MUST_VISIT"
    DATE = "DATE"
    TIME = "TIME"
    RESERVATION = "RESERVATION"


@dataclass(frozen=True, slots=True)
class MustVisitLock:
    type: LockType = LockType.MUST_VISIT


@dataclass(frozen=True, slots=True)
class DateLock:
    date: date
    type: LockType = LockType.DATE


@dataclass(frozen=True, slots=True)
class TimeLock:
    start_time: time
    tolerance_minutes: int
    type: LockType = LockType.TIME

    def __post_init__(self) -> None:
        if not 0 <= self.tolerance_minutes <= 180:
            raise ValueError("toleranceMinutes must be 0..180")


@dataclass(frozen=True, slots=True)
class ReservationLock:
    date: date
    start_time: time
    end_time: time | None
    type: LockType = LockType.RESERVATION

    def __post_init__(self) -> None:
        if self.end_time is not None and self.end_time < self.start_time:
            raise ValueError("endTime before startTime")


ItemLock = MustVisitLock | DateLock | TimeLock | ReservationLock


@dataclass(frozen=True, slots=True)
class TargetItem:
    item_id: UUID
    place_id: UUID
    date: date
    start_time: time | None
    duration_minutes: int | None
    position: int

    def __post_init__(self) -> None:
        if self.duration_minutes is not None and self.duration_minutes <= 0:
            raise ValueError("durationMinutes must be positive when present")


@dataclass(frozen=True, slots=True)
class NeighbourItem:
    item_id: UUID
    date: date
    position: int
    start_time: time | None
    duration_minutes: int | None


@dataclass(frozen=True, slots=True)
class OpenWindow:
    opens_at: time
    closes_at: time

    def __post_init__(self) -> None:
        if self.closes_at <= self.opens_at:
            raise ValueError("closesAt must be after opensAt")


@dataclass(frozen=True, slots=True)
class Closed:
    pass


@dataclass(frozen=True, slots=True)
class UnknownHours:
    pass


OpeningWindow = OpenWindow | Closed | UnknownHours


class RouteEvidence(Enum):
    NONE = "NONE"
    VERIFIED = "VERIFIED"


class ForecastResolution(Enum):
    DAY = "DAY"
    HOUR = "HOUR"


@dataclass(frozen=True, slots=True)
class ComparisonVerdict:
    """Computed by Spring's TemporalComparisonPolicy; reason_code is one of SOURCE_CATALOG.md §9."""

    eligible: bool
    reason_code: str


@dataclass(frozen=True, slots=True)
class TemporalCandidate:
    key: CandidateKey
    resolution: ForecastResolution
    before_value: Decimal
    after_value: Decimal
    metric_code: str
    verdict: ComparisonVerdict
    before_snapshot_id: UUID
    after_snapshot_id: UUID

    def __post_init__(self) -> None:
        if self.key.date is None:
            raise ValueError("temporal candidate needs a date")
        if self.resolution is ForecastResolution.DAY and self.key.time is not None:
            raise ValueError("DAY resolution candidates must not carry a time (§5.4)")


@dataclass(frozen=True, slots=True)
class ItemOptimizationInput:
    trip_id: UUID
    trip_version: int
    trip_start: date
    trip_end: date
    trip_zone: ZoneInfo
    target: TargetItem
    locks: tuple[ItemLock, ...]
    neighbours: tuple[NeighbourItem, ...]
    opening_hours: Mapping[date, OpeningWindow]
    route_evidence: RouteEvidence
    candidates: tuple[TemporalCandidate, ...]

    def __post_init__(self) -> None:
        if self.trip_end < self.trip_start:
            raise ValueError("tripEnd before tripStart")
        if len({lock.type for lock in self.locks}) != len(self.locks):
            raise ValueError("at most one lock per type")
