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

SlotMatchState = Literal["EXACT", "CHECKING", "UNKNOWN", "NONE"]
"""State of one candidate's date slots. P0 never answers SIMILAR, the fifth value of the public enum."""


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
    """Another item on a day the move touches.

    A stay is either unknown or a positive number of minutes: zero or a negative length would shrink a
    neighbouring interval to nothing and let an overlapping proposal through (§5.4).
    """

    item_id: UUID
    date: date_
    position: int
    start_time: time_ | None
    duration_minutes: int | None = Field(gt=0)


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


class SlotEvaluateRequest(ContractModel):
    """Facts for one ACTIVE candidate: the trip dates, what already sits on them, and this place's duplicates.

    A trip spans at most 30 dates (§4.1), so `openingHours` and `datesWithSamePlace` are bounded by
    the same number; a longer trip is answered for its first `candidateCaps.slotDates` dates.
    """

    evaluated_at: AwareDatetime
    trip_id: UUID
    candidate_id: UUID
    place_id: UUID
    trip_start: date_
    trip_end: date_
    trip_zone: str = Field(min_length=1, max_length=64)
    duration_minutes: int | None = Field(gt=0)
    items: list[NeighbourItemIn] = Field(max_length=100)
    opening_hours: dict[date_, OpeningWindowIn] = Field(max_length=30)
    dates_with_same_place: list[date_] = Field(max_length=30)
    route_evidence: Literal["NONE", "VERIFIED"]
    max_items_per_day: int = Field(ge=1)
    checking: bool


class SlotOut(ContractModel):
    """One trip date. `suggestedTime` is always null: P0 offers a date and never invents a time."""

    date: date_
    suggested_time: None
    eligible: bool
    reason_code: str | None


class SlotEvaluateResponse(ContractModel):
    policy_version: str
    policy_hash: str
    pipeline_version: str
    state: SlotMatchState
    slots: list[SlotOut]
    reasons: list[str]


RelationTierName = Literal["EXACT", "SIMILAR"]
"""How the catalog mapping policy classified one relation. Never synthesized from a confidence value."""

RelationMatchState = Literal["EXACT", "SIMILAR", "NONE", "CHECKING", "UNKNOWN"]
"""State of one place's related list. CHECKING and UNKNOWN describe the lookup, not the places found."""


class PlaceCategoryIn(ContractModel):
    """Canonical category of one place at a fixed taxonomy version; `categoryCode` may be missing."""

    place_id: UUID
    category_code: str | None = Field(max_length=64)
    parent_category_code: str | None = Field(max_length=64)
    taxonomy_version: str = Field(min_length=1, max_length=64)


class RelationCandidateIn(ContractModel):
    """One relation evidence row after canonical mapping, with the window it is valid in.

    `mapping` is the canonical-mapping certainty of the row itself: an UNCERTAIN row is quarantined and
    never ranked, because a wrong canonical target would attach evidence to the wrong place.
    """

    source_place_id: UUID
    target_place_id: UUID
    tier: RelationTierName
    source_code: str = Field(min_length=1, max_length=64)
    channel: str = Field(min_length=1, max_length=64)
    confidence: Decimal | None
    effective_at: AwareDatetime
    expires_at: AwareDatetime | None
    mapping: Literal["CERTAIN", "UNCERTAIN"]


class RelatedRankRequest(ContractModel):
    """Every relation row Spring found for one source place, plus the categories to compare against.

    `lookupOutcome` is how that lookup ended: a failed source or a running verification job is answered
    as UNKNOWN/CHECKING rather than as "no related places". The body carries no owner or session id.
    """

    evaluated_at: AwareDatetime
    source_place_id: UUID
    source_category: PlaceCategoryIn
    candidates: list[RelationCandidateIn] = Field(max_length=2000)
    categories: list[PlaceCategoryIn] = Field(max_length=2000)
    lookup_outcome: Literal["COMPLETE", "SOURCE_FAILED", "JOB_RUNNING"]


class RelatedItemOut(ContractModel):
    """One canonical place with the evidence rows that survived.

    `categoryMatch` is null when it is unknown, never 0: "no comparable category" and "a different
    category" are different answers.
    """

    place_id: UUID
    tier: RelationTierName
    category_match: Decimal | None
    evidence_count: int = Field(ge=1)
    channels: list[str]

    @field_serializer("category_match", when_used="always")
    def _decimal_as_string(self, value: Decimal | None) -> str | None:
        return None if value is None else str(value)


class RelatedRankResponse(ContractModel):
    policy_version: str
    policy_hash: str
    pipeline_version: str
    state: RelationMatchState
    items: list[RelatedItemOut]
    reasons: list[str]


ExplanationSourceName = Literal["TEMPLATE", "LLM"]
"""Which writer produced the sentence: the deterministic template or an accepted model rewrite."""


class ExplanationRenderRequest(ContractModel):
    """The verified facts one explanation may mention (§9.1 allowlist).

    `placeName`, `metricLabel` and `attribution` are approved catalog and source registry text, and
    the two values are a pair Spring already judged comparable. A time is null when the slot carries
    none; nothing here is an owner, a session, a coordinate or a line of the user's own itinerary.
    """

    locale: Literal["ko", "en"]
    place_name: str = Field(min_length=1, max_length=200)
    before_date: date_
    before_time: time_ | None
    after_date: date_
    after_time: time_ | None
    before_value: Decimal
    after_value: Decimal
    metric_label: str = Field(min_length=1, max_length=64)
    attribution: str = Field(min_length=1, max_length=200)
    forecast_issue_id: str | None = Field(max_length=64)


class ExplanationRenderResponse(ContractModel):
    """One sentence and its writer. `summary` is text: no caller may turn it into a command (§9.1)."""

    policy_version: str
    policy_hash: str
    pipeline_version: str
    summary: str = Field(min_length=1, max_length=500)
    source: ExplanationSourceName


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
