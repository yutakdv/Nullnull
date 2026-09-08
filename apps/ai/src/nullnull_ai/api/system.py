"""Liveness, readiness and policy descriptor."""

from __future__ import annotations

from datetime import UTC, datetime

from fastapi import APIRouter, Request

from nullnull_ai.api.schemas import CapabilityStatus, HealthStatus, PolicyDescriptor, ReadinessStatus
from nullnull_ai.domain.policy import RecommendationPolicy

router = APIRouter(tags=["System"])


@router.get("/health/live", response_model=HealthStatus, response_model_by_alias=True)
async def liveness() -> HealthStatus:
    return HealthStatus(status="UP", time=datetime.now(UTC))


@router.get("/health/ready", response_model=ReadinessStatus, response_model_by_alias=True)
async def readiness(request: Request) -> ReadinessStatus:
    policy: RecommendationPolicy = request.app.state.policy
    now = datetime.now(UTC)
    checks = [CapabilityStatus(name="policy", status="READY", checked_at=now, detail=policy.version)]
    return ReadinessStatus(status="READY", checks=checks)


@router.get("/policy", response_model=PolicyDescriptor, response_model_by_alias=True)
async def policy(request: Request) -> PolicyDescriptor:
    current: RecommendationPolicy = request.app.state.policy
    return PolicyDescriptor(
        policy_version=current.version,
        policy_hash=current.hash,
        pipeline_version=current.pipeline_version,
        service_version=request.app.state.service_version,
    )
