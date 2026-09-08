"""§5.2 verified relations only; no crowd, popularity or exposure in the ordering.

Per-channel caps are applied in channel-name order, evidence is merged per canonical target, the tier
is EXACT when any valid EXACT evidence survives, and the ordering is
(tier, categoryMatch DESC with missing last, placeId ASC) - the `relatedOrdering` of policy-v1.

Pure: the caller supplies `context.evaluated_at`, so the effective window is judged against one fixed
instant and a rerun of the same input gives the same answer.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal
from enum import Enum
from uuid import UUID

from nullnull_ai.domain.policy import RecommendationPolicy
from nullnull_ai.domain.types import Reason, RecommendationContext

EVIDENCE_EXPIRED = "EVIDENCE_EXPIRED"
MAPPING_UNCERTAIN = "MAPPING_UNCERTAIN"
SELF_REFERENCE = "SELF_REFERENCE"
CHANNEL_CAP_EXCEEDED = "CHANNEL_CAP_EXCEEDED"
MERGED_CAP_EXCEEDED = "MERGED_CAP_EXCEEDED"
TAXONOMY_MISMATCH = "TAXONOMY_MISMATCH"


class RelationTier(Enum):
    """EXACT before SIMILAR. Assigned by the catalog mapping policy, never synthesized from confidence."""

    EXACT = "EXACT"
    SIMILAR = "SIMILAR"


class MappingCertainty(Enum):
    CERTAIN = "CERTAIN"
    UNCERTAIN = "UNCERTAIN"


class LookupOutcome(Enum):
    """How the caller's relation lookup ended. JOB_RUNNING is only for a real verification job."""

    COMPLETE = "COMPLETE"
    SOURCE_FAILED = "SOURCE_FAILED"
    JOB_RUNNING = "JOB_RUNNING"


class RelationState(Enum):
    EXACT = "EXACT"
    SIMILAR = "SIMILAR"
    NONE = "NONE"
    CHECKING = "CHECKING"
    UNKNOWN = "UNKNOWN"


@dataclass(frozen=True, slots=True)
class RelationCandidate:
    """One relation evidence row from catalog.place_relations after canonical mapping."""

    source_place_id: UUID
    target_place_id: UUID
    tier: RelationTier
    source_code: str
    channel: str
    confidence: Decimal | None
    effective_at: datetime
    expires_at: datetime | None
    mapping: MappingCertainty


@dataclass(frozen=True, slots=True)
class PlaceCategory:
    """Canonical category at a fixed taxonomy version; `category_code` may be None (missing)."""

    place_id: UUID
    category_code: str | None
    parent_category_code: str | None
    taxonomy_version: str


@dataclass(frozen=True, slots=True)
class RankedRelated:
    place_id: UUID
    tier: RelationTier
    category_match: Decimal | None
    evidence: tuple[RelationCandidate, ...]

    def __post_init__(self) -> None:
        if not self.evidence:
            raise ValueError("a ranked place keeps at least one evidence row")


@dataclass(frozen=True, slots=True)
class RelatedResult:
    state: RelationState
    items: tuple[RankedRelated, ...]
    reasons: tuple[Reason, ...]


def _add_once(reasons: list[Reason], code: str, detail: str) -> None:
    if all(reason.code != code for reason in reasons):
        reasons.append(Reason(code, detail))


def _row_key(candidate: RelationCandidate) -> tuple[str, str]:
    """Deterministic row order. EXACT sorts before SIMILAR because the enum values do, alphabetically."""
    return candidate.tier.value, str(candidate.target_place_id)


def _category_match(source: PlaceCategory, target: PlaceCategory | None, reasons: list[Reason]) -> Decimal | None:
    """1 same canonical category, 0.5 same reviewed parent, 0 different, None when a fact is missing."""
    if target is None or target.category_code is None or source.category_code is None:
        return None
    if source.taxonomy_version != target.taxonomy_version:
        _add_once(reasons, TAXONOMY_MISMATCH, "categories come from different taxonomy versions")
        return None
    if source.category_code == target.category_code:
        return Decimal(1)
    if source.parent_category_code is not None and source.parent_category_code == target.parent_category_code:
        return Decimal("0.5")
    return Decimal(0)


class RelatedPlaceRanker:
    def __init__(self, policy: RecommendationPolicy) -> None:
        self._policy = policy

    def rank(
        self,
        context: RecommendationContext,
        source_place_id: UUID,
        source_category: PlaceCategory,
        candidates: Sequence[RelationCandidate],
        categories: Mapping[UUID, PlaceCategory],
        outcome: LookupOutcome,
    ) -> RelatedResult:
        reasons: list[Reason] = []
        uncertain = False

        # 1. drop invalid rows deterministically, before any cap
        by_channel: dict[str, list[RelationCandidate]] = {}
        for candidate in candidates:
            if candidate.target_place_id == source_place_id:
                _add_once(reasons, SELF_REFERENCE, "relation points at the source place")
                continue
            expired = candidate.expires_at is not None and candidate.expires_at <= context.evaluated_at
            if expired or candidate.effective_at > context.evaluated_at:
                _add_once(reasons, EVIDENCE_EXPIRED, "relation evidence outside its effective window")
                continue
            if candidate.mapping is MappingCertainty.UNCERTAIN:
                uncertain = True
                _add_once(reasons, MAPPING_UNCERTAIN, "canonical mapping uncertain; quarantined")
                continue
            by_channel.setdefault(candidate.channel, []).append(candidate)

        # 2. per-channel cap in channel-name order, rows ordered by (tier, targetPlaceId)
        per_channel = self._policy.candidate_caps.related_per_channel
        kept: list[RelationCandidate] = []
        for channel in sorted(by_channel):
            rows = sorted(by_channel[channel], key=_row_key)
            if len(rows) > per_channel:
                _add_once(reasons, CHANNEL_CAP_EXCEEDED, "channel exceeded its candidate cap")
            kept.extend(rows[:per_channel])

        # 3. canonical merge
        merged: dict[UUID, list[RelationCandidate]] = {}
        for candidate in kept:
            merged.setdefault(candidate.target_place_id, []).append(candidate)
        ranked: list[RankedRelated] = []
        for place_id, evidence in merged.items():
            rows = sorted(evidence, key=lambda row: (*_row_key(row), row.channel))
            tier = RelationTier.EXACT if any(row.tier is RelationTier.EXACT for row in rows) else RelationTier.SIMILAR
            match = _category_match(source_category, categories.get(place_id), reasons)
            ranked.append(RankedRelated(place_id, tier, match, tuple(rows)))

        # 4. deterministic ordering and merged cap
        ranked.sort(key=_rank_key)
        merged_cap = self._policy.candidate_caps.related_merged
        if len(ranked) > merged_cap:
            _add_once(reasons, MERGED_CAP_EXCEEDED, "merged candidates exceeded the cap")
            ranked = ranked[:merged_cap]

        if outcome is LookupOutcome.JOB_RUNNING:
            state = RelationState.CHECKING
        elif outcome is LookupOutcome.SOURCE_FAILED:
            state = RelationState.UNKNOWN
        elif not ranked:
            # A quarantined mapping is the one case where "nothing to show" is not the same as "none".
            state = RelationState.UNKNOWN if uncertain else RelationState.NONE
        else:
            state = RelationState.EXACT if ranked[0].tier is RelationTier.EXACT else RelationState.SIMILAR
        return RelatedResult(state, tuple(ranked), tuple(reasons))


def _rank_key(item: RankedRelated) -> tuple[str, int, Decimal, str]:
    """`relatedOrdering`: tier, then categoryMatch DESC with a missing match last, then placeId ASC."""
    known = item.category_match is not None
    return (
        item.tier.value,
        0 if known else 1,
        -(item.category_match if item.category_match is not None else Decimal(0)),
        str(item.place_id),
    )
