"""Template first; a model rewrite is used only when the validator accepts it (§9.1).

A provider failure, a timeout, a refusal and an unacceptable sentence are the same outcome here: the
deterministic template, reported as TEMPLATE. The failure never propagates, because an explanation
that cannot be improved is not an error - and never silently disappears, because the caller is told
which source the text came from.
"""

from __future__ import annotations

from typing import Literal

from nullnull_ai.explain.facts import ExplanationFacts
from nullnull_ai.explain.ports import LlmExplanationPort
from nullnull_ai.explain.templates import render
from nullnull_ai.explain.validator import accepts

ExplanationSource = Literal["TEMPLATE", "LLM"]
"""Which writer produced the sentence. The FE and the audit trail never have to guess."""


class ExplanationService:
    def __init__(self, port: LlmExplanationPort) -> None:
        self._port = port

    def summary(self, facts: ExplanationFacts) -> tuple[str, ExplanationSource]:
        """The sentence for one verified improvement, and where it came from."""
        template = render(facts)
        try:
            rewritten = self._port.rewrite(facts, template)
        except Exception:
            # A provider outage is not an explanation failure: the template already says everything
            # the facts support. BaseException (cancellation, interrupt) is left alone.
            return template, "TEMPLATE"
        if rewritten is None or not accepts(facts, rewritten):
            return template, "TEMPLATE"
        return rewritten, "LLM"
