"""The OpenAI adapter: the one place in this service that performs a network call (BA-084②).

It lives outside every decision package on purpose. `tests/test_purity.py` classifies `provider` in
`IMPURE_PACKAGES`, and the same file asserts `DECISION_PACKAGES <= PURE_PACKAGES`, so this code
cannot be moved into `explain` without turning one of those two red.

What it is handed is what `LlmExplanationPort` allows: the facts §9.1 approves and the finished
template. No owner, no session, no raw itinerary, no coordinate - `ExplanationFacts` has no field for
any of them. What it returns is a string the caller still puts through `explain.validator`, so a
model that invents a number changes nothing on screen.

**The monthly ceiling is not enforced here, and pretending otherwise would be the dangerous option.**
A monthly figure needs state that survives restarts, and this service has none (ADR-0006). A counter
held in memory would reset on every deploy while reporting that the budget was being kept - a guard
that is silent exactly when it fails. So the ceiling is enforced where the money is: the provider's
own hard limit, set by the owner on the OpenAI dashboard. What this module does is bound a single
call (`MAX_OUTPUT_TOKENS`, `TIMEOUT_SECONDS`) and turn the provider's own refusal into the
deterministic template, like every other provider failure.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from collections.abc import Callable
from decimal import Decimal
from typing import Any, Final

from nullnull_ai.explain.facts import ExplanationFacts
from nullnull_ai.explain.ports import (
    ProviderBudgetExceededError,
    ProviderError,
    ProviderMalformedOutputError,
    ProviderTimeoutError,
)

ENDPOINT: Final = "https://api.openai.com/v1/chat/completions"

TIMEOUT_SECONDS: Final = 2.0
"""One explanation is worth about this much waiting; past it the template is already the answer."""

MAX_OUTPUT_TOKENS: Final = 200
"""A rewrite is one sentence. `templates.MAX_LENGTH` is 500 characters and the validator refuses
anything longer, so a larger allowance would only buy tokens for answers that get thrown away."""

MONTHLY_BUDGET_USD: Final = Decimal("15")
"""The ceiling the owner approved on 2026-09-20. Recorded here so the number has one home and a test
pins it; the enforcement is the provider-side hard limit, for the reason in the module docstring."""

Post = Callable[[str, bytes, dict[str, str], float], bytes]
"""url, body, headers, timeout -> raw response bytes. Injected so the adapter is tested without a
network and without spending money; the default is the only implementation that does either."""

_SYSTEM = (
    "You rewrite one sentence for a travel app. Say only what the given sentence already says. "
    "Do not add a number, a date, a place, an identifier, a link, an opening time, a route, a "
    "distance or any claim about how crowded somewhere is. Answer with the sentence alone."
)


class OpenAiExplanationPort:
    """`AI_PROVIDER=OPENAI`. Every failure is raised as one of the named provider errors."""

    def __init__(
        self, api_key: str, model_id: str, *, post: Post | None = None, timeout: float = TIMEOUT_SECONDS
    ) -> None:
        self._api_key = api_key
        self._model_id = model_id
        self._post = post or _https_post
        self._timeout = timeout

    def rewrite(self, facts: ExplanationFacts, template: str) -> str | None:
        body = json.dumps(
            {
                "model": self._model_id,
                "max_completion_tokens": MAX_OUTPUT_TOKENS,
                "messages": [
                    {"role": "system", "content": _SYSTEM},
                    {"role": "user", "content": template},
                ],
            }
        ).encode("utf-8")
        headers = {
            "Authorization": f"Bearer {self._api_key}",
            "Content-Type": "application/json",
        }
        raw = self._post(ENDPOINT, body, headers, self._timeout)
        return _sentence(raw)


def _sentence(raw: bytes) -> str | None:
    """The one field this adapter reads. Anything else about the answer is not this module's business.

    A body that does not carry it is `ProviderMalformedOutputError` rather than a silent None: "the
    provider answered with something we cannot read" and "the provider declined to rewrite" are
    different events, and only the first is worth a name in a report.
    """
    try:
        document: Any = json.loads(raw.decode("utf-8"))
        content = document["choices"][0]["message"]["content"]
    except (UnicodeDecodeError, json.JSONDecodeError, KeyError, IndexError, TypeError) as error:
        raise ProviderMalformedOutputError(f"unreadable completion body: {type(error).__name__}") from error
    if content is None:
        return None
    if not isinstance(content, str):
        raise ProviderMalformedOutputError("completion content is not a string")
    return content.strip() or None


def _https_post(url: str, body: bytes, headers: dict[str, str], timeout: float) -> bytes:
    """The default transport.

    No error raised from here carries the request headers, because the key is in them. `HTTPError`
    names the url and the status, and the url is a constant with no secret in it - which is why the
    key is sent as a header rather than a query parameter.
    """
    request = urllib.request.Request(url, data=body, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return bytes(response.read())
    except urllib.error.HTTPError as error:
        payload = _safe_body(error)
        if error.code == 429 and "insufficient_quota" in payload:
            raise ProviderBudgetExceededError("provider refused the call: insufficient quota") from error
        if error.code in (402, 403) and "billing" in payload:
            raise ProviderBudgetExceededError(f"provider refused the call: billing ({error.code})") from error
        raise ProviderError(f"provider answered {error.code}") from error
    except TimeoutError as error:
        raise ProviderTimeoutError(f"no answer within {timeout}s") from error
    except urllib.error.URLError as error:
        # `reason` may itself be a socket timeout, which urllib wraps rather than surfaces.
        if isinstance(error.reason, TimeoutError):
            raise ProviderTimeoutError(f"no answer within {timeout}s") from error
        raise ProviderError("provider unreachable") from error


def _safe_body(error: urllib.error.HTTPError) -> str:
    """The error body, read only far enough to tell a quota refusal from any other rejection.

    Bounded because it is provider text: it is never returned to a caller and never logged, and a
    provider that answers an error with a megabyte should not turn a refused call into a memory
    spike.
    """
    try:
        return error.read(4096).decode("utf-8", "replace")
    except Exception:
        return ""
