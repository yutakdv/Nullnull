"""Pydantic models of the internal contract v1. Unknown fields are rejected everywhere."""

from __future__ import annotations

from typing import Literal
from uuid import UUID

from pydantic import AwareDatetime, BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


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
