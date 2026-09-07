"""POST /internal/v1/related/rank

Hydration is Spring's job, so every inconsistency in the body is a caller bug: it becomes a 422
`VALIDATION_FAILED`, never a 5xx. A 5xx from this route means the service itself failed.

The three body rules this route adds on top of the schema all describe a broken hydration rather than
a user input: a category row that does not describe the source place, a relation row that starts at a
different place, and a repeated category. Answering any of them would rank evidence for a place the
caller never asked about.
"""

from __future__ import annotations

from uuid import UUID

from fastapi import APIRouter, Request

from nullnull_ai.api.problems import ApiProblemError
from nullnull_ai.api.schemas import (
    PlaceCategoryIn,
    RelatedItemOut,
    RelatedRankRequest,
    RelatedRankResponse,
    RelationCandidateIn,
    RelationMatchState,
    RelationTierName,
)
from nullnull_ai.domain.policy import RecommendationPolicy
from nullnull_ai.domain.types import RecommendationContext
from nullnull_ai.related.ranker import (
    LookupOutcome,
    MappingCertainty,
    PlaceCategory,
    RelatedPlaceRanker,
    RelationCandidate,
    RelationState,
    RelationTier,
)

router = APIRouter(tags=["Related"])

# Written out rather than derived from the enum values so the contract literal and the domain enum
# cannot drift apart unnoticed: a new tier or state fails type checking here.
_TIERS: dict[RelationTier, RelationTierName] = {
    RelationTier.EXACT: "EXACT",
    RelationTier.SIMILAR: "SIMILAR",
}

_STATES: dict[RelationState, RelationMatchState] = {
    RelationState.EXACT: "EXACT",
    RelationState.SIMILAR: "SIMILAR",
    RelationState.NONE: "NONE",
    RelationState.CHECKING: "CHECKING",
    RelationState.UNKNOWN: "UNKNOWN",
}


def _category(row: PlaceCategoryIn) -> PlaceCategory:
    return PlaceCategory(
        place_id=row.place_id,
        category_code=row.category_code,
        parent_category_code=row.parent_category_code,
        taxonomy_version=row.taxonomy_version,
    )


def _categories(rows: list[PlaceCategoryIn]) -> dict[UUID, PlaceCategory]:
    by_place: dict[UUID, PlaceCategory] = {}
    for row in rows:
        if row.place_id in by_place:
            raise ValueError("categories must not carry two rows for the same place")
        by_place[row.place_id] = _category(row)
    return by_place


def _candidate(row: RelationCandidateIn, source_place_id: UUID) -> RelationCandidate:
    if row.source_place_id != source_place_id:
        raise ValueError("every candidate must start at the request's source place")
    return RelationCandidate(
        source_place_id=row.source_place_id,
        target_place_id=row.target_place_id,
        tier=RelationTier(row.tier),
        source_code=row.source_code,
        channel=row.channel,
        confidence=row.confidence,
        effective_at=row.effective_at,
        expires_at=row.expires_at,
        mapping=MappingCertainty(row.mapping),
    )


@router.post("/related/rank", response_model=RelatedRankResponse, response_model_by_alias=True)
async def rank_related(request: Request, body: RelatedRankRequest) -> RelatedRankResponse:
    policy: RecommendationPolicy = request.app.state.policy
    try:
        context = RecommendationContext(
            evaluated_at=body.evaluated_at,
            policy_version=policy.version,
            policy_hash=policy.hash,
            catalog_version=request.app.state.settings.effective_catalog_version,
            request_id=getattr(request.state, "request_id", "unassigned"),
        )
        if body.source_category.place_id != body.source_place_id:
            raise ValueError("sourceCategory must describe the source place")
        source_category = _category(body.source_category)
        candidates = [_candidate(row, body.source_place_id) for row in body.candidates]
        categories = _categories(body.categories)
    except ValueError as error:
        raise ApiProblemError("VALIDATION_FAILED", 422, "The request violates a domain rule.") from error
    result = RelatedPlaceRanker(policy).rank(
        context, body.source_place_id, source_category, candidates, categories, LookupOutcome(body.lookup_outcome)
    )
    return RelatedRankResponse(
        policy_version=policy.version,
        policy_hash=policy.hash,
        pipeline_version=policy.pipeline_version,
        state=_STATES[result.state],
        items=[
            RelatedItemOut(
                place_id=item.place_id,
                tier=_TIERS[item.tier],
                category_match=item.category_match,
                evidence_count=len(item.evidence),
                channels=sorted({row.channel for row in item.evidence}),
            )
            for item in result.items
        ],
        reasons=[reason.code for reason in result.reasons],
    )
