from __future__ import annotations

from datetime import UTC, datetime, timedelta
from decimal import Decimal

import pytest

from nullnull_ai.domain.policy import load_default
from nullnull_ai.item.score import Admitted, ItemScorePolicy, Rejected, TemporalShift
from nullnull_ai.item.types import ComparisonVerdict

METRIC = "KTO_RELATIVE_CONCENTRATION_INDEX"
BEFORE = datetime(2026, 9, 12, 1, 0, tzinfo=UTC)
OK = ComparisonVerdict(True, "SAME_METRIC_AND_ISSUE")
policy = ItemScorePolicy(load_default())


def shift(before: int, after: int, minutes: int) -> TemporalShift:
    return TemporalShift(Decimal(before), Decimal(after), BEFORE, BEFORE + timedelta(minutes=minutes))


@pytest.mark.parametrize(
    ("before", "after", "minutes", "score", "relief", "cost"),
    [
        (80, 60, 60, "0.110000", "0.200000", "0.250000"),
        (80, 50, 120, "0.140000", "0.300000", "0.500000"),
        (80, 30, 24 * 60, "0.200000", "0.500000", "1.000000"),
    ],
)
def test_golden_examples(before: int, after: int, minutes: int, score: str, relief: str, cost: str) -> None:
    admission = policy.evaluate(METRIC, OK, shift(before, after, minutes))
    assert isinstance(admission, Admitted)
    assert admission.score.score == Decimal(score)
    assert admission.relief == Decimal(relief) and admission.change_cost == Decimal(cost)
    assert [name for name, _ in admission.score.contributions] == [
        "relief",
        "changeCost",
        "reliefTerm",
        "changeCostTerm",
    ]


@pytest.mark.parametrize(
    ("verdict", "before", "after", "minutes", "code"),
    [
        (OK, 80, 77, 15, "IMPROVEMENT_BELOW_MINIMUM"),
        (ComparisonVerdict(False, "DIFFERENT_FORECAST_ISSUE"), 80, 20, 60, "COMPARISON_INELIGIBLE"),
        (OK, 80, 75, 24 * 60, "SCORE_NOT_POSITIVE"),
        (OK, 80, 76, 0, "IMPROVEMENT_BELOW_MINIMUM"),
    ],
)
def test_rejections(verdict: ComparisonVerdict, before: int, after: int, minutes: int, code: str) -> None:
    admission = policy.evaluate(METRIC, verdict, shift(before, after, minutes))
    assert isinstance(admission, Rejected) and admission.reason.code == code


def test_boundary_minimum_improvement_exactly_five_is_admitted() -> None:
    assert isinstance(policy.evaluate(METRIC, OK, shift(80, 75, 0)), Admitted)


def test_unknown_metric_is_rejected() -> None:
    admission = policy.evaluate("SEOUL_LIVE_LEVEL", OK, shift(80, 60, 60))
    assert isinstance(admission, Rejected) and admission.reason.code == "METRIC_POLICY_MISSING"
