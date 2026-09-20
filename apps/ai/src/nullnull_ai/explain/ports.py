"""The optional model rewrite, as a port (§9.1, THREAT_MODEL "Provider text/prompt injection").

An implementation is handed the facts and the finished template - never raw itinerary text, notes,
coordinates, owner or session values - and returns a sentence or nothing. It is given no tool, no
secret, no network handle and no way to change a trip by this package: whatever it returns is text
that still has to pass the validator, and a caller renders it as text.

`NoopLlmExplanationPort` is the P0 default behind AI_PROVIDER=NONE. A real provider adapter performs
I/O and therefore lives outside this package, with its own model id, timeout and kill switch.
"""

from __future__ import annotations

from typing import Protocol

from nullnull_ai.explain.facts import ExplanationFacts


class ProviderError(Exception):
    """A rewrite that did not arrive. The three named below are the failures BA-084 owes a fallback.

    An adapter raises one of these instead of letting a provider-shaped error escape, so the reason a
    sentence came back as TEMPLATE is a word this service chose rather than whatever type a client
    library happens to throw. `ExplanationService` catches every `Exception`, not only these, because
    a failure nobody named is still not an explanation failure - the names are a vocabulary for the
    adapter, never a list of the only survivable errors.
    """


class ProviderTimeoutError(ProviderError):
    """The provider did not answer inside the adapter's own deadline."""


class ProviderMalformedOutputError(ProviderError):
    """The answer arrived but could not be read as the bounded shape the adapter asked for.

    Invalid JSON is the common case. Text that parses but says something the facts cannot support is
    NOT this: that is a well-formed answer the output validator refuses, and the two are kept apart
    so a parsing bug cannot be read as a hallucination or the other way round.
    """


class ProviderBudgetExceededError(ProviderError):
    """The call was refused by the adapter's own spend or token ceiling.

    The ceiling itself is not defined here: a budget needs a model id, a price and a counter, all of
    which live with the adapter (AI_PROVIDER != NONE). What BA-084 fixes now is the consequence - a
    request that hits it ends in the deterministic template, never in a 5xx and never in silence.
    """


class LlmExplanationPort(Protocol):
    def rewrite(self, facts: ExplanationFacts, template: str) -> str | None:
        """A rewrite of `template` built only from `facts`, or None to keep the template."""


class NoopLlmExplanationPort:
    """AI_PROVIDER=NONE (P0 default): there is no model, so the template is always the answer."""

    def rewrite(self, facts: ExplanationFacts, template: str) -> str | None:
        return None
