"""REC-LLM-06: the model adapter bounds one call and never carries the key anywhere but the header.

Nothing here reaches the network. The transport is injected for the shape of the request and
monkeypatched at `urlopen` for the failure mapping, so the suite neither depends on OpenAI being up
nor spends money to stay green - the two ways an adapter test quietly stops being run.

The canary assertions are the point of the file. `AI_API_KEY` is the one secret this service holds,
and the places it could escape are the request it is not supposed to be in (url, body), the answer,
the logs, and - the one that is easy to miss - the text of an exception raised while holding it.
"""

from __future__ import annotations

import io
import json
import logging
import urllib.error
from dataclasses import replace
from datetime import date, time
from decimal import Decimal
from typing import Any

import pytest

from nullnull_ai.explain.facts import ExplanationFacts
from nullnull_ai.explain.ports import (
    ProviderBudgetExceededError,
    ProviderError,
    ProviderMalformedOutputError,
    ProviderTimeoutError,
)
from nullnull_ai.provider import openai as adapter
from nullnull_ai.provider.openai import MONTHLY_BUDGET_USD, OpenAiExplanationPort

CANARY_KEY = "sk-canary-1ecb0f2a-do-not-leak"
"""A value unique enough that finding it anywhere is proof rather than coincidence."""

FACT_ONLY_CANARY = "issue-canary-7d41-the-template-never-renders-this"
"""A facts field that reaches no template, so finding it in a request is proof rather than coincidence."""

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
TEMPLATE = "경복궁 방문을 옮기면 상대 집중률이 80에서 60으로 20포인트 낮아져요. 출처: ⓒ한국관광공사"


def completion(content: str | None) -> bytes:
    return json.dumps({"choices": [{"message": {"content": content}}]}).encode("utf-8")


class Recorder:
    """An injected transport that answers with `body` and keeps what it was asked to send."""

    def __init__(self, body: bytes = b"") -> None:
        self.body = body
        self.calls: list[tuple[str, bytes, dict[str, str], float]] = []

    def __call__(self, url: str, body: bytes, headers: dict[str, str], timeout: float) -> bytes:
        self.calls.append((url, body, headers, timeout))
        return self.body


def test_an_answer_comes_back_as_the_sentence() -> None:
    port = OpenAiExplanationPort(CANARY_KEY, "gpt-test", post=Recorder(completion("더 한산한 시간이에요")))
    assert port.rewrite(FACTS, TEMPLATE) == "더 한산한 시간이에요"


def test_the_model_is_handed_the_template_and_nothing_else() -> None:
    """The port's contract, asserted on the bytes that would go out rather than on the call.

    **The whole message list is pinned, not the user turn alone.** Measured on 2026-09-20: a facts
    field appended to the SYSTEM turn left the entire suite green (572 passed, red=0), while the
    same value appended to the user turn turned this test red on its own. A model is handed every
    turn in the list, so a rule that reads one role describes the request instead of bounding it.

    The canary asks the same question from the other end and does not depend on the shape of the
    body: `rewrite` is given the whole `ExplanationFacts` and ignores all of it today, so what
    survives a rewrite of this request is "no field the template did not render went out".
    """
    facts = replace(FACTS, forecast_issue_id=FACT_ONLY_CANARY)
    recorder = Recorder(completion("ok"))
    OpenAiExplanationPort(CANARY_KEY, "gpt-test", post=recorder).rewrite(facts, TEMPLATE)
    (_url, body, _headers, _timeout) = recorder.calls[0]
    sent: Any = json.loads(body)
    assert [message["role"] for message in sent["messages"]] == ["system", "user"]
    assert sent["messages"][1]["content"] == TEMPLATE
    assert FACT_ONLY_CANARY not in body.decode("utf-8")
    assert sent["model"] == "gpt-test"
    assert sent["max_completion_tokens"] == adapter.MAX_OUTPUT_TOKENS


def test_the_system_turn_is_the_same_whatever_the_request_carries() -> None:
    """The instruction is a constant; two different sets of facts must produce the same system turn.

    Asserted by comparison rather than against the module constant on purpose. Comparing to
    `_SYSTEM` would pass for any expression built from it - including `_SYSTEM + fact` if the
    constant were read back the same way - and would tie the test to a private name. Two requests
    that differ in every fact must still be handed the identical first turn; nothing per-request
    can survive that.
    """
    other = replace(FACTS, place_name="광화문", forecast_issue_id="issue-2", before_value=Decimal("70"))
    system_turns = []
    for facts in (FACTS, other):
        recorder = Recorder(completion("ok"))
        OpenAiExplanationPort(CANARY_KEY, "gpt-test", post=recorder).rewrite(facts, TEMPLATE)
        sent: Any = json.loads(recorder.calls[0][1])
        system_turns.append(sent["messages"][0])
    assert system_turns[0]["role"] == "system"
    assert system_turns[0] == system_turns[1]


def test_the_key_travels_in_the_header_and_nowhere_else() -> None:
    """The url and the body are the two places a key ends up in somebody's access log."""
    recorder = Recorder(completion("ok"))
    OpenAiExplanationPort(CANARY_KEY, "gpt-test", post=recorder).rewrite(FACTS, TEMPLATE)
    (url, body, headers, _timeout) = recorder.calls[0]
    assert headers["Authorization"] == f"Bearer {CANARY_KEY}"
    assert CANARY_KEY not in url
    assert CANARY_KEY not in body.decode("utf-8")


@pytest.mark.parametrize("content", [None, "", "   "], ids=["null", "empty", "blank"])
def test_an_answer_with_no_sentence_is_a_decline_not_a_failure(content: str | None) -> None:
    """None means "keep the template" and the service treats it as such; it is not an error."""
    port = OpenAiExplanationPort(CANARY_KEY, "gpt-test", post=Recorder(completion(content)))
    assert port.rewrite(FACTS, TEMPLATE) is None


@pytest.mark.parametrize(
    "body",
    [
        b"not json",
        b"{}",
        b'{"choices": []}',
        b'{"choices": [{"message": {}}]}',
        b'{"choices":[{"message":{"content":7}}]}',
    ],
    ids=["not json", "no choices", "empty choices", "no content", "content is a number"],
)
def test_a_body_this_adapter_cannot_read_is_named_as_such(body: bytes) -> None:
    port = OpenAiExplanationPort(CANARY_KEY, "gpt-test", post=Recorder(body))
    with pytest.raises(ProviderMalformedOutputError):
        port.rewrite(FACTS, TEMPLATE)


def http_error(code: int, payload: bytes) -> urllib.error.HTTPError:
    return urllib.error.HTTPError("https://api.openai.com/v1/chat/completions", code, "err", {}, io.BytesIO(payload))  # type: ignore[arg-type]


@pytest.mark.parametrize(
    ("raised", "expected"),
    [
        (TimeoutError("timed out"), ProviderTimeoutError),
        (urllib.error.URLError(TimeoutError("timed out")), ProviderTimeoutError),
        (urllib.error.URLError("no route"), ProviderError),
        (http_error(429, b'{"error":{"code":"insufficient_quota"}}'), ProviderBudgetExceededError),
        (http_error(402, b'{"error":{"message":"billing hard limit reached"}}'), ProviderBudgetExceededError),
        (http_error(429, b'{"error":{"code":"rate_limit_exceeded"}}'), ProviderError),
        (http_error(500, b"upstream"), ProviderError),
        (http_error(401, b"bad key"), ProviderError),
    ],
    ids=["timeout", "wrapped timeout", "unreachable", "quota", "billing", "rate limit", "500", "401"],
)
def test_every_transport_failure_arrives_as_a_named_provider_error(
    monkeypatch: pytest.MonkeyPatch, raised: BaseException, expected: type[Exception]
) -> None:
    """Through the real transport, because the mapping IS the code under test here."""

    def explode(*_args: object, **_kwargs: object) -> object:
        raise raised

    monkeypatch.setattr(adapter.urllib.request, "urlopen", explode)
    port = OpenAiExplanationPort(CANARY_KEY, "gpt-test")
    with pytest.raises(expected):
        port.rewrite(FACTS, TEMPLATE)


@pytest.mark.parametrize(
    "raised",
    [
        TimeoutError("timed out"),
        urllib.error.URLError("no route"),
        http_error(401, b"bad key"),
        http_error(429, b'{"error":{"code":"insufficient_quota"}}'),
    ],
    ids=["timeout", "unreachable", "401", "quota"],
)
def test_no_failure_puts_the_key_in_its_message_or_the_logs(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture, raised: BaseException
) -> None:
    """The path that is easy to miss: an exception raised while the adapter is holding the key.

    A 401 is the one a provider actually produces for a bad credential, and the obvious debugging
    instinct - putting the header in the message - is exactly what this refuses.

    The log half asserts an absence in code that logs nothing today. That is the point: it is a
    regression guard, and it fires the day somebody adds `logger.info(f"calling {headers}")`.
    """

    def explode(*_args: object, **_kwargs: object) -> object:
        raise raised

    monkeypatch.setattr(adapter.urllib.request, "urlopen", explode)
    port = OpenAiExplanationPort(CANARY_KEY, "gpt-test")
    with caplog.at_level(logging.DEBUG), pytest.raises(ProviderError) as caught:
        port.rewrite(FACTS, TEMPLATE)
    assert CANARY_KEY not in str(caught.value)
    assert CANARY_KEY not in repr(caught.value)
    assert CANARY_KEY not in str(caught.value.__cause__)
    assert all(CANARY_KEY not in record.getMessage() for record in caplog.records)


def test_the_approved_monthly_ceiling_is_pinned() -> None:
    """$15, approved by the owner on 2026-09-20. The number lives in one place so that moving it is
    an edit somebody reviews; the enforcement is the provider-side hard limit, not this constant."""
    assert MONTHLY_BUDGET_USD == Decimal("15")
