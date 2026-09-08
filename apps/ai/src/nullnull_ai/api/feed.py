"""POST /internal/v1/feed/rank"""

from __future__ import annotations

from fastapi import APIRouter, Request

from nullnull_ai.api.problems import ApiProblemError
from nullnull_ai.api.schemas import FeedRankRequest, FeedRankResponse, StageCountOut
from nullnull_ai.domain.policy import RecommendationPolicy
from nullnull_ai.domain.types import RecommendationContext
from nullnull_ai.feed.pipeline import SORT_VERSION, FeedCandidate, FeedQuery, build_feed_pipeline

router = APIRouter(tags=["Feed"])


@router.post("/feed/rank", response_model=FeedRankResponse, response_model_by_alias=True)
async def rank_feed(request: Request, body: FeedRankRequest) -> FeedRankResponse:
    policy: RecommendationPolicy = request.app.state.policy
    if body.sort_version != SORT_VERSION:
        detail = f"sortVersion {body.sort_version} is not served by this policy"
        raise ApiProblemError("SORT_VERSION_UNSUPPORTED", 422, detail)
    try:
        context = RecommendationContext(
            evaluated_at=body.evaluated_at,
            policy_version=policy.version,
            policy_hash=policy.hash,
            catalog_version=request.app.state.settings.effective_catalog_version,
            request_id=getattr(request.state, "request_id", "unassigned"),
        )
        candidates = tuple(
            FeedCandidate(
                post_id=candidate.post_id,
                published_at=candidate.published_at,
                status=candidate.status,
                primary_place_id=candidate.primary_place_id,
            )
            for candidate in body.candidates
        )
        query = FeedQuery(context=context, sort_version=body.sort_version, candidates=candidates)
    except ValueError as error:
        # Domain constructors reject inconsistent input; that is a caller bug (422), never a retryable outage.
        raise ApiProblemError("VALIDATION_FAILED", 422, "The request violates a domain rule.") from error
    result = build_feed_pipeline(policy).run(query)
    return FeedRankResponse(
        policy_version=policy.version,
        policy_hash=policy.hash,
        pipeline_version=policy.pipeline_version,
        sort_version=SORT_VERSION,
        evaluated=result.evaluated,
        ordered_post_ids=[item.candidate.post_id for item in result.selected],
        rejected_by_reason=result.rejected_by_reason,
        stage_counts=[
            StageCountOut(stage=c.stage, input_count=c.input_count, output_count=c.output_count)
            for c in result.stage_counts
        ],
    )
