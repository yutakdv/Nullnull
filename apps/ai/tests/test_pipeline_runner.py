from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from decimal import Decimal

import pytest

from nullnull_ai.domain.types import Eligibility, Reason, ScoreBreakdown
from nullnull_ai.pipeline.runner import CandidatePipeline
from nullnull_ai.pipeline.stages import Scored


@dataclass(frozen=True)
class Item:
    key: str
    value: int


class ListSource:
    name = "list"

    def __init__(self, items: Sequence[Item]) -> None:
        self.items = tuple(items)

    def fetch(self, query: None) -> Sequence[Item]:
        return self.items


class Positive:
    name = "positive"

    def evaluate(self, query: None, candidate: Item) -> Eligibility:
        if candidate.value < 0:
            return Eligibility.ineligible(Reason("NEGATIVE", "value below zero"))
        if candidate.value == 0:
            return Eligibility.unknown(Reason("ZERO", "value unknown"))
        return Eligibility.eligible()


class ValueScorer:
    name = "value"

    def score(self, query: None, candidate: Item) -> ScoreBreakdown:
        return ScoreBreakdown(Decimal(candidate.value), (("raw", Decimal(candidate.value)),))


class TopTwo:
    name = "top_two"

    def select(self, query: None, scored: Sequence[Scored[Item]]) -> Sequence[Scored[Item]]:
        return sorted(scored, key=lambda item: (-item.breakdown.score, item.candidate.key))[:2]


def build(items: Sequence[Item]) -> CandidatePipeline[None, Item, str]:
    return CandidatePipeline(
        name="test",
        key=lambda item: item.key,
        sources=[ListSource(items)],
        filters=[Positive()],
        scorers=[ValueScorer()],
        selector=TopTwo(),
    )


def test_dedup_filter_score_select_and_counts() -> None:
    items = [Item("b", 5), Item("a", 9), Item("a", 9), Item("c", -1), Item("d", 0), Item("e", 5)]
    result = build(items).run(None)
    assert [scored.candidate.key for scored in result.selected] == ["a", "b"]
    assert result.evaluated == 5
    assert result.rejected_by_reason == {"NEGATIVE": 1}
    assert result.unknown_by_reason == {"ZERO": 1}
    assert result.selected[0].breakdown.contributions == (("value", Decimal(9)), ("value.raw", Decimal(9)))
    stages = [count.stage for count in result.stage_counts]
    assert stages == ["source:list", "dedup", "filters", "scorers", "selector:top_two", "post_filters"]


def test_arrival_order_does_not_change_the_result() -> None:
    import random

    base = [Item(f"k{i:03d}", (i * 37) % 101 - 10) for i in range(120)]
    expected = build(base).run(None)
    rng = random.Random(20260906)
    for _ in range(1000):
        shuffled = list(base)
        rng.shuffle(shuffled)
        actual = build(shuffled).run(None)
        assert [s.candidate.key for s in actual.selected] == [s.candidate.key for s in expected.selected]
        assert actual.rejected_by_reason == expected.rejected_by_reason


def test_conflicting_duplicates_are_quarantined_regardless_of_arrival_order() -> None:
    for order in ([Item("a", 1), Item("a", 2)], [Item("a", 2), Item("a", 1)]):
        result = build(order).run(None)
        assert result.selected == ()
        assert result.rejected_by_reason == {"DUPLICATE_CONFLICT": 1}
        assert result.evaluated == 0


def test_pipeline_requires_sources_and_scorers() -> None:
    with pytest.raises(ValueError):
        CandidatePipeline(name="x", key=lambda i: i.key, sources=[], scorers=[ValueScorer()], selector=TopTwo())
    with pytest.raises(ValueError):
        CandidatePipeline(name="x", key=lambda i: i.key, sources=[ListSource([])], scorers=[], selector=TopTwo())
