"""REC-LLM-03: every way a provider can fail ends in the deterministic template (BA-084).

`test_service.py` already showed that *an* exception falls back. This module pins the three failures
the card names - timeout, unreadable output, budget refusal - as named types, so the adapter that
arrives with AI_PROVIDER != NONE has a vocabulary to raise and a fallback it cannot opt out of.

The last two cases are the ones that make the first three more than a restatement. A failure nobody
named must fall back as well, or the vocabulary quietly becomes a list of the only survivable
errors; and a cancellation must NOT be swallowed, or a shutting-down process would answer with a
sentence it never tried to improve.
"""

from __future__ import annotations

from datetime import date, time
from decimal import Decimal

import pytest

from nullnull_ai.explain.facts import ExplanationFacts
from nullnull_ai.explain.ports import (
    ProviderBudgetExceededError,
    ProviderError,
    ProviderMalformedOutputError,
    ProviderTimeoutError,
)
from nullnull_ai.explain.service import ExplanationService
from nullnull_ai.explain.templates import render

FACTS = ExplanationFacts(
    locale="ko",
    place_name="경복궁",
    before_date=date(2026, 9, 12),
    before_time=time(10, 0),
    after_date=date(2026, 9, 12),
    after_time=time(12, 0),
    before_value=Decimal("80"),
    after_value=Decimal("60"),
    metric_label="상대 집중률",
    attribution="출처: ⓒ한국관광공사",
    forecast_issue_id="issue-1",
)


class FailingPort:
    def __init__(self, failure: BaseException) -> None:
        self.failure = failure

    def rewrite(self, facts: ExplanationFacts, template: str) -> str | None:
        raise self.failure


@pytest.mark.parametrize(
    "failure",
    [
        ProviderTimeoutError("no answer in 2s"),
        ProviderMalformedOutputError("expected JSON object, got '{\"summary\": '"),
        ProviderBudgetExceededError("monthly ceiling reached"),
    ],
    ids=lambda failure: type(failure).__name__,
)
def test_each_named_provider_failure_answers_with_the_template(failure: ProviderError) -> None:
    assert ExplanationService(FailingPort(failure)).summary(FACTS) == (render(FACTS), "TEMPLATE")


def test_a_failure_the_vocabulary_does_not_name_also_answers_with_the_template() -> None:
    """The three names are for the adapter to raise, not a whitelist of what the service survives."""
    assert ExplanationService(FailingPort(ConnectionResetError("socket"))).summary(FACTS) == (
        render(FACTS),
        "TEMPLATE",
    )


def test_a_cancellation_is_not_turned_into_an_answer() -> None:
    """BaseException is left alone: a shutting-down process must not report a sentence as produced."""
    with pytest.raises(KeyboardInterrupt):
        ExplanationService(FailingPort(KeyboardInterrupt())).summary(FACTS)


def test_the_named_failures_are_one_family_the_adapter_can_catch() -> None:
    for failure in (ProviderTimeoutError, ProviderMalformedOutputError, ProviderBudgetExceededError):
        assert issubclass(failure, ProviderError)
