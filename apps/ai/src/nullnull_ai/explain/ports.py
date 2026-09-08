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


class LlmExplanationPort(Protocol):
    def rewrite(self, facts: ExplanationFacts, template: str) -> str | None:
        """A rewrite of `template` built only from `facts`, or None to keep the template."""


class NoopLlmExplanationPort:
    """AI_PROVIDER=NONE (P0 default): there is no model, so the template is always the answer."""

    def rewrite(self, facts: ExplanationFacts, template: str) -> str | None:
        return None
