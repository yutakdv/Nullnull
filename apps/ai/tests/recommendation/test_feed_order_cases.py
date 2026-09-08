"""Python side of the cross-language feed-order corpus (`fixtures/feed-order-cases.json`).

The same file drives the Spring `FeedFallbackTest`; both languages must produce the identical order,
so this test asserts `expectedOrder` verbatim instead of re-deriving it. The checksum in the
manifest is verified first, so an edited fixture cannot quietly re-baseline either side.
"""

from __future__ import annotations

import json
from dataclasses import replace
from datetime import datetime
from pathlib import Path
from typing import Any
from uuid import UUID

import pytest

from nullnull_ai.domain.policy import RecommendationPolicy, load_default
from nullnull_ai.domain.types import RecommendationContext
from nullnull_ai.evaluation.fixtures import of_kind, read_entries, read_verified
from nullnull_ai.feed.pipeline import SORT_VERSION, FeedCandidate, FeedQuery, build_feed_pipeline

APP_ROOT = Path(__file__).resolve().parents[2]
MANIFEST: dict[str, Any] = json.loads(Path(__file__).with_name("manifest.json").read_text(encoding="utf-8"))
ENTRIES = of_kind(read_entries(MANIFEST), "FEED")
POLICY = load_default()
CATALOG_VERSION = "catalog-fixture-1"

CASES = [
    (entry.id, document, case)
    for entry in ENTRIES
    for document in [read_verified(APP_ROOT, entry)]
    for case in document["cases"]
]
if not CASES:
    raise RuntimeError("the FEED corpus is empty; a zero denominator is a configuration error")


@pytest.mark.parametrize(
    ("fixture_id", "document", "case"), CASES, ids=[f"{fixture_id}:{case['id']}" for fixture_id, _, case in CASES]
)
def test_feed_order_case_matches_the_shared_expectation(
    fixture_id: str, document: dict[str, Any], case: dict[str, Any]
) -> None:
    assert document["dataOrigin"] == "SYNTHETIC", fixture_id
    assert document["sortVersion"] == SORT_VERSION
    context = RecommendationContext(
        datetime.fromisoformat(case["evaluatedAt"]), POLICY.version, POLICY.hash, CATALOG_VERSION
    )
    candidates = tuple(
        FeedCandidate(
            post_id=UUID(candidate["postId"]),
            published_at=None if candidate["publishedAt"] is None else datetime.fromisoformat(candidate["publishedAt"]),
            status=candidate["status"],
            primary_place_id=None if candidate["primaryPlaceId"] is None else UUID(candidate["primaryPlaceId"]),
        )
        for candidate in case["candidates"]
    )
    result = build_feed_pipeline(_with_cap(case["cap"])).run(FeedQuery(context, SORT_VERSION, candidates))
    assert [str(item.candidate.post_id) for item in result.selected] == case["expectedOrder"]


def _with_cap(cap: int) -> RecommendationPolicy:
    """The corpus pins the cap per case; the policy value stays the production default."""
    return replace(POLICY, candidate_caps=replace(POLICY.candidate_caps, feed_snapshot=cap))
