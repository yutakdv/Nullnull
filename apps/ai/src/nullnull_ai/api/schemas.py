"""Pydantic models of the internal contract v1. Unknown fields are rejected everywhere."""

from __future__ import annotations

from datetime import date as date_
from datetime import time as time_
from decimal import Decimal
from typing import Literal
from uuid import UUID

from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, field_serializer
from pydantic.alias_generators import to_camel

ComparisonReasonCode = Literal[
    "SAME_METRIC_AND_ISSUE",
    "SAME_SOURCE_SCOPE_SET",
    "DIFFERENT_SOURCE",
    "DIFFERENT_SCOPE",
    "DIFFERENT_FORECAST_ISSUE",
    "STALE_INPUT",
    "REPLAY_INPUT",
    "QUALITATIVE_ONLY",
    "MAPPING_UNCERTAIN",
    "PROVIDER_INCIDENT",
    "MISSING_PROVENANCE",
]
"""The eleven pair-comparison reason codes of docs/data/SOURCE_CATALOG.md §9. No other value is accepted."""

ItemOutcome = Literal["PROPOSALS", "LOCK_CONFLICT", "ROUTE_UNAVAILABLE", "DATA_INSUFFICIENT", "NO_IMPROVEMENT"]
"""Terminal outcome of one ITEM optimization; each value maps to one async failure plane."""


class ContractModel(BaseModel):
    model_config = ConfigDict(extra="forbid", alias_generator=to_camel, populate_by_name=True, frozen=True)


class HealthStatus(ContractModel):
    status: Literal["UP"]
    time: AwareDatetime


class CapabilityStatus(ContractModel):
    name: str
    status: Literal["READY", "DEGRADED", "UNAVAILABLE"]
    checked_at: AwareDatetime
    detail: str | None = None


class ReadinessStatus(ContractModel):
    status: Literal["READY", "DEGRADED", "NOT_READY"]
    checks: list[CapabilityStatus]


class PolicyDescriptor(ContractModel):
    policy_version: str
    policy_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    pipeline_version: str
    service_version: str


class FeedCandidateIn(ContractModel):
    """One curated post as Spring sees it. Owner-specific state (hidden, saved) never reaches this service."""

    post_id: UUID
    published_at: AwareDatetime | None
    status: Literal["PUBLISHED", "DRAFT", "WITHDRAWN", "DELETED"]
    primary_place_id: UUID | None


class FeedRankRequest(ContractModel):
    evaluated_at: AwareDatetime
    locale: Literal["ko", "en"]
    sort_version: int = Field(ge=1)
    candidates: list[FeedCandidateIn] = Field(max_length=2000)


class StageCountOut(ContractModel):
    stage: str
    input_count: int
    output_count: int


class FeedRankResponse(ContractModel):
    policy_version: str
    policy_hash: str
    pipeline_version: str
    sort_version: int
    evaluated: int
    ordered_post_ids: list[UUID]
    rejected_by_reason: dict[str, int]
    stage_counts: list[StageCountOut]


class LockIn(ContractModel):
    """One itinerary lock. The four types are independent and are never auto-released (§5.4)."""

    type: Literal["MUST_VISIT", "DATE", "TIME", "RESERVATION"]
    date: date_ | None = None
    start_time: time_ | None = None
    end_time: time_ | None = None
    tolerance_minutes: int | None = Field(default=None, ge=0, le=180)


class TargetItemIn(ContractModel):
    """The item being moved. `startTime`/`durationMinutes` may be unknown; they are never guessed."""

    item_id: UUID
    place_id: UUID
    date: date_
    start_time: time_ | None
    duration_minutes: int | None = Field(default=None, gt=0)
    position: int


class NeighbourItemIn(ContractModel):
    item_id: UUID
    date: date_
    position: int
    start_time: time_ | None
    duration_minutes: int | None


class OpeningWindowIn(ContractModel):
    """A verified window, a verified closure, or an explicit unknown - never an assumed default."""

    state: Literal["OPEN", "CLOSED", "UNKNOWN"]
    opens_at: time_ | None = None
    closes_at: time_ | None = None


class TemporalCandidateIn(ContractModel):
    """A before/after snapshot pair for the same place, already judged comparable by Spring."""

    place_id: UUID
    date: date_
    time: time_ | None
    resolution: Literal["DAY", "HOUR"]
    before_value: Decimal
    after_value: Decimal
    metric_code: str = Field(min_length=1, max_length=64)
    verdict_eligible: bool
    verdict_reason_code: ComparisonReasonCode
    before_snapshot_id: UUID
    after_snapshot_id: UUID


class ItemProposeRequest(ContractModel):
    evaluated_at: AwareDatetime
    trip_id: UUID
    trip_version: int = Field(ge=1)
    trip_start: date_
    trip_end: date_
    trip_zone: str = Field(min_length=1, max_length=64)
    target: TargetItemIn
    locks: list[LockIn] = Field(max_length=4)
    neighbours: list[NeighbourItemIn] = Field(max_length=100)
    opening_hours: dict[date_, OpeningWindowIn] = Field(max_length=30)  # one entry per trip date (max 30)
    route_evidence: Literal["NONE", "VERIFIED"]
    candidates: list[TemporalCandidateIn] = Field(max_length=2000)


class ItemProposalOut(ContractModel):
    """One ranked preview. Decimals are serialized as strings so Spring reads them as BigDecimal."""

    rank: int = Field(ge=1)
    date: date_
    start_time: time_ | None
    before_instant: AwareDatetime
    after_instant: AwareDatetime
    score: Decimal
    improvement: Decimal
    relief: Decimal
    change_cost: Decimal
    before_snapshot_id: UUID
    after_snapshot_id: UUID
    lock_checks: dict[str, bool]

    @field_serializer("score", "improvement", "relief", "change_cost", when_used="always")
    def _decimal_as_string(self, value: Decimal) -> str:
        return str(value)


class ItemProposeResponse(ContractModel):
    policy_version: str
    policy_hash: str
    pipeline_version: str
    outcome: ItemOutcome
    proposals: list[ItemProposalOut]
    reasons: list[str]
    evaluated: int
    rejected_by_reason: dict[str, int]


class Problem(ContractModel):
    """Minimal RFC 9457 problem for the internal API; codes are stable and never carry inputs."""

    type: str
    title: str
    status: int
    code: Literal["INVALID_REQUEST", "VALIDATION_FAILED", "NOT_FOUND", "INTERNAL_ERROR", "SORT_VERSION_UNSUPPORTED"]
    detail: str
    instance: str
    request_id: str
    retryable: bool
