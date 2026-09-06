from __future__ import annotations

import random
from datetime import UTC, datetime, timedelta
from uuid import UUID

from nullnull_ai.domain.policy import load_default
from nullnull_ai.domain.types import RecommendationContext
from nullnull_ai.feed.pipeline import SORT_VERSION, FeedCandidate, FeedQuery, build_feed_pipeline

POLICY = load_default()
NOW = datetime(2026, 9, 6, 0, 0, tzinfo=UTC)
PLACE = UUID("018f3f8e-9b67-7a21-8d31-31d315b93a01")


def context() -> RecommendationContext:
    return RecommendationContext(NOW, POLICY.version, POLICY.hash, "catalog-test-1")


def post(i: int, *, published: datetime | None, status: str = "PUBLISHED", place: UUID | None = PLACE) -> FeedCandidate:
    return FeedCandidate(UUID(int=i), published, status, place)


def test_fixed_order_is_published_at_desc_then_post_id_asc_and_filters_apply() -> None:
    t = NOW - timedelta(days=1)
    candidates = (
        post(2, published=t),
        post(1, published=t),
        post(9, published=t + timedelta(minutes=1)),
        post(3, published=None),
        post(4, published=t, status="DRAFT"),
        post(5, published=NOW + timedelta(seconds=1)),
        post(6, published=t, place=None),
    )
    result = build_feed_pipeline(POLICY).run(FeedQuery(context(), SORT_VERSION, candidates))
    assert [s.candidate.post_id for s in result.selected] == [UUID(int=9), UUID(int=1), UUID(int=2)]
    assert result.rejected_by_reason == {"NOT_PUBLISHED": 2, "NO_CANONICAL_PLACE": 1, "PUBLISHED_IN_FUTURE": 1}


def test_snapshot_cap_and_shuffle_invariance() -> None:
    base = [
        post(i + 1, published=NOW - timedelta(seconds=i // 7)) for i in range(POLICY.candidate_caps.feed_snapshot + 25)
    ]
    expected = build_feed_pipeline(POLICY).run(FeedQuery(context(), SORT_VERSION, tuple(base)))
    assert len(expected.selected) == POLICY.candidate_caps.feed_snapshot
    rng = random.Random(20260906)
    for _ in range(1000):
        shuffled = list(base)
        rng.shuffle(shuffled)
        actual = build_feed_pipeline(POLICY).run(FeedQuery(context(), SORT_VERSION, tuple(shuffled)))
        assert [s.candidate.post_id for s in actual.selected] == [s.candidate.post_id for s in expected.selected]


def test_duplicate_post_ids_collapse_to_one_entry() -> None:
    t = NOW - timedelta(days=1)
    result = build_feed_pipeline(POLICY).run(
        FeedQuery(context(), SORT_VERSION, (post(1, published=t), post(1, published=t)))
    )
    assert [s.candidate.post_id for s in result.selected] == [UUID(int=1)]
