"""POST /internal/v1/explanations/render

Hydration is Spring's job, so every inconsistency in the body is a caller bug: it becomes a 422
`VALIDATION_FAILED`, never a 5xx. A 5xx from this route means the service itself failed.

The body carries only what §9.1 allows an explanation to mention - the approved place name, the two
compared values with their slots, the metric label and the source line. It carries no owner, session,
coordinate or raw itinerary text, and the answer says which writer produced the sentence.
"""

from __future__ import annotations

from fastapi import APIRouter, Request

from nullnull_ai.api.problems import ApiProblemError
from nullnull_ai.api.schemas import ExplanationRenderRequest, ExplanationRenderResponse, ExplanationSourceName
from nullnull_ai.domain.policy import RecommendationPolicy
from nullnull_ai.explain.facts import ExplanationFacts
from nullnull_ai.explain.service import ExplanationService, ExplanationSource

router = APIRouter(tags=["Explanation"])

# Written out rather than derived from the domain literal so the contract value and the domain value
# cannot drift apart unnoticed: a new source fails type checking here.
_SOURCES: dict[ExplanationSource, ExplanationSourceName] = {"TEMPLATE": "TEMPLATE", "LLM": "LLM"}


@router.post("/explanations/render", response_model=ExplanationRenderResponse, response_model_by_alias=True)
async def render_explanation(request: Request, body: ExplanationRenderRequest) -> ExplanationRenderResponse:
    policy: RecommendationPolicy = request.app.state.policy
    service: ExplanationService = request.app.state.explanation_service
    try:
        facts = ExplanationFacts(
            locale=body.locale,
            place_name=body.place_name,
            before_date=body.before_date,
            before_time=body.before_time,
            after_date=body.after_date,
            after_time=body.after_time,
            before_value=body.before_value,
            after_value=body.after_value,
            metric_label=body.metric_label,
            attribution=body.attribution,
            forecast_issue_id=body.forecast_issue_id,
        )
        summary, source = service.summary(facts)
    except ValueError as error:
        raise ApiProblemError("VALIDATION_FAILED", 422, "The request violates a domain rule.") from error
    return ExplanationRenderResponse(
        policy_version=policy.version,
        policy_hash=policy.hash,
        pipeline_version=policy.pipeline_version,
        summary=summary,
        source=_SOURCES[source],
    )
