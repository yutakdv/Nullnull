"""Core value types shared by every recommendation pipeline (RECOMMENDATION_ALGORITHM.md §3.2).

Everything here is immutable and free of clocks, randomness, I/O and framework imports.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import UTC, date, datetime, time
from decimal import Decimal
from enum import Enum
from uuid import UUID

_REASON_CODE = re.compile(r"^[A-Z][A-Z0-9_]{0,63}$")


class EligibilityState(Enum):
    """Hard eligibility outcome. UNKNOWN is never promoted to ELIGIBLE (§3.1)."""

    ELIGIBLE = "ELIGIBLE"
    INELIGIBLE = "INELIGIBLE"
    UNKNOWN = "UNKNOWN"


@dataclass(frozen=True, slots=True)
class Reason:
    """Internal rejection/uncertainty reason; distinct from public failure and comparison codes."""

    code: str
    detail: str

    def __post_init__(self) -> None:
        if not _REASON_CODE.match(self.code):
            raise ValueError("reason code must be UPPER_SNAKE_CASE up to 64 characters")
        if not self.detail:
            raise ValueError("reason detail must not be empty")


@dataclass(frozen=True, slots=True)
class Eligibility:
    state: EligibilityState
    reasons: tuple[Reason, ...] = ()

    def __post_init__(self) -> None:
        if self.state is not EligibilityState.ELIGIBLE and not self.reasons:
            raise ValueError(f"{self.state.value} requires at least one reason")

    @classmethod
    def eligible(cls) -> Eligibility:
        return cls(EligibilityState.ELIGIBLE)

    @classmethod
    def ineligible(cls, first: Reason, *more: Reason) -> Eligibility:
        return cls(EligibilityState.INELIGIBLE, (first, *more))

    @classmethod
    def unknown(cls, first: Reason, *more: Reason) -> Eligibility:
        return cls(EligibilityState.UNKNOWN, (first, *more))

    @property
    def is_eligible(self) -> bool:
        return self.state is EligibilityState.ELIGIBLE

    def and_(self, other: Eligibility) -> Eligibility:
        """INELIGIBLE dominates UNKNOWN, which dominates ELIGIBLE; reasons keep evaluation order."""
        states = (self.state, other.state)
        if EligibilityState.INELIGIBLE in states:
            combined = EligibilityState.INELIGIBLE
        elif EligibilityState.UNKNOWN in states:
            combined = EligibilityState.UNKNOWN
        else:
            combined = EligibilityState.ELIGIBLE
        return Eligibility(combined, self.reasons + other.reasons)


@dataclass(frozen=True, slots=True)
class ScoreBreakdown:
    """Fixed-point score with named contributions; Decimal rules out NaN/Infinity by construction."""

    score: Decimal
    contributions: tuple[tuple[str, Decimal], ...] = ()

    def __post_init__(self) -> None:
        if not self.score.is_finite():
            raise ValueError("score must be finite")
        for name, value in self.contributions:
            if not name or not value.is_finite():
                raise ValueError(f"contribution {name!r} must be a finite Decimal")


@dataclass(frozen=True, slots=True)
class CandidateKey:
    """Identity of a place-based candidate; a time without a date is invalid (§3.2)."""

    place_id: UUID
    date: date | None = None
    time: time | None = None

    def __post_init__(self) -> None:
        if self.time is not None and self.date is None:
            raise ValueError("time requires a date")


@dataclass(frozen=True, slots=True)
class RecommendationContext:
    """Fixed inputs of one computation: the single 'now', policy identity and catalog version (§6)."""

    evaluated_at: datetime
    policy_version: str
    policy_hash: str
    catalog_version: str
    request_id: str = field(default="unassigned")

    def __post_init__(self) -> None:
        if self.evaluated_at.tzinfo is None or self.evaluated_at.utcoffset() is None:
            raise ValueError("evaluated_at must be timezone-aware")
        if self.evaluated_at.utcoffset() != UTC.utcoffset(self.evaluated_at):
            object.__setattr__(self, "evaluated_at", self.evaluated_at.astimezone(UTC))
        for name in ("policy_version", "policy_hash", "catalog_version"):
            if not getattr(self, name):
                raise ValueError(f"{name} must not be blank")
