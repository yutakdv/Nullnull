"""P0 feed pipeline (RECOMMENDATION_ALGORITHM.md §5.1).

Fixed order is a special case of the general pipeline: a scorer whose score is the publish
instant, a selector that sorts by (score DESC, postId ASC) and caps the snapshot. HIDE, saved
state and the selected trip stay on the Spring side; they never change the order.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from uuid import UUID

from nullnull_ai.domain.policy import RecommendationPolicy
from nullnull_ai.domain.types import Eligibility, Reason, RecommendationContext, ScoreBreakdown
from nullnull_ai.pipeline.runner import CandidatePipeline
from nullnull_ai.pipeline.stages import Scored

SORT_VERSION = 1
EPOCH = datetime(1970, 1, 1, tzinfo=UTC)


@dataclass(frozen=True, slots=True)
class FeedCandidate:
    post_id: UUID
    published_at: datetime | None
    status: str
    primary_place_id: UUID | None


@dataclass(frozen=True, slots=True)
class FeedQuery:
    context: RecommendationContext
    sort_version: int
    candidates: tuple[FeedCandidate, ...]


class RequestSource:
    """The only P0 source: candidates hydrated by Spring in the request."""

    name = "request"

    def fetch(self, query: FeedQuery) -> Sequence[FeedCandidate]:
        return query.candidates


class PublishedFilter:
    name = "published"

    def evaluate(self, query: FeedQuery, candidate: FeedCandidate) -> Eligibility:
        if candidate.status != "PUBLISHED" or candidate.published_at is None:
            return Eligibility.ineligible(Reason("NOT_PUBLISHED", "post is not publicly published"))
        if candidate.published_at > query.context.evaluated_at:
            return Eligibility.ineligible(Reason("PUBLISHED_IN_FUTURE", "publish instant is after evaluatedAt"))
        return Eligibility.eligible()


class CanonicalPlaceFilter:
    name = "canonical_place"

    def evaluate(self, query: FeedQuery, candidate: FeedCandidate) -> Eligibility:
        if candidate.primary_place_id is None:
            return Eligibility.ineligible(Reason("NO_CANONICAL_PLACE", "post has no canonical primary place"))
        return Eligibility.eligible()


class FixedOrderScorer:
    """Score = publish instant in whole microseconds (timestamptz precision): score DESC == publishedAt DESC (§5.1)."""

    name = "fixed_order"

    def score(self, query: FeedQuery, candidate: FeedCandidate) -> ScoreBreakdown:
        if candidate.published_at is None:
            raise ValueError("scorer received an unpublished candidate; filters must run first")
        micros = Decimal((candidate.published_at - EPOCH) // timedelta(microseconds=1))
        return ScoreBreakdown(score=micros, contributions=(("publishedAtEpochMicros", micros),))


class TopKSelector:
    name = "top_k"

    def __init__(self, cap: int) -> None:
        if cap < 1:
            raise ValueError("cap must be >= 1")
        self.cap = cap

    def select(self, query: FeedQuery, scored: Sequence[Scored[FeedCandidate]]) -> Sequence[Scored[FeedCandidate]]:
        ordered = sorted(scored, key=lambda item: (-item.breakdown.score, str(item.candidate.post_id)))
        return ordered[: self.cap]


def build_feed_pipeline(policy: RecommendationPolicy) -> CandidatePipeline[FeedQuery, FeedCandidate, str]:
    if tuple(policy.feed_ordering) != ("publishedAt DESC", "postId ASC"):
        raise ValueError("policy feedOrdering does not match the fixed-order pipeline")
    return CandidatePipeline(
        name="feed-fixed-order",
        key=lambda candidate: str(candidate.post_id),
        sources=[RequestSource()],
        filters=[PublishedFilter(), CanonicalPlaceFilter()],
        scorers=[FixedOrderScorer()],
        selector=TopKSelector(policy.candidate_caps.feed_snapshot),
    )
