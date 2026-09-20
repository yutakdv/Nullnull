"""REC-LLM-02: the registered corpus of model output the validator must refuse (BA-084).

The refusal rules already had cases, but they lived inline in `tests/explain/test_validator.py`,
where nothing registers them: no fixture id, no checksum, no manifest row. A corpus that the gate
counts has to be a file the manifest names and hashes, so this module reads
`tests/recommendation/fixtures/llm-output-corpus.json` and replays every case through
`explain.validator.accepts`.

Two things keep the replay from passing vacuously. A validator that refuses everything would satisfy
"every REFUSE case is refused", so the corpus carries ACCEPT cases and the guard below requires
them. And an empty bucket would make any claim about it true, so every family, every verdict and
both locales are required to be represented rather than merely allowed.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

from nullnull_ai.evaluation.fixtures import (
    LlmCase,
    LlmFixture,
    load_llm_fixture,
    of_kind,
    read_entries,
)
from nullnull_ai.explain.validator import accepts

APP_ROOT = Path(__file__).resolve().parents[2]
MANIFEST = Path(__file__).with_name("manifest.json")
manifest: dict[str, Any] = json.loads(MANIFEST.read_text(encoding="utf-8"))
LLM_ENTRIES = of_kind(read_entries(manifest), "LLM")
FIXTURES = tuple(load_llm_fixture(APP_ROOT, entry) for entry in LLM_ENTRIES)
CASES = tuple((fixture, case) for fixture in FIXTURES for case in fixture.cases)


def test_the_manifest_declares_a_model_output_corpus() -> None:
    """A zero denominator is a configuration error, not a pass: every claim below needs cases."""
    assert LLM_ENTRIES, "manifest.fixtures must declare at least one LLM corpus"
    assert CASES, "the declared corpus must contain cases"


@pytest.mark.parametrize("fixture", FIXTURES, ids=lambda fixture: fixture.id)
def test_the_corpus_agrees_with_the_manifest_it_is_registered_in(fixture: LlmFixture) -> None:
    assert fixture.policy_version == manifest["policyVersion"]
    assert fixture.timezone == manifest["timezone"]
    assert fixture.fixed_clock.isoformat() == manifest["fixedClock"].replace("Z", "+00:00")
    assert fixture.derivation.strip(), "a corpus states why each verdict is the verdict it is"


@pytest.mark.parametrize("fixture", FIXTURES, ids=lambda fixture: fixture.id)
def test_every_family_verdict_and_locale_is_represented(fixture: LlmFixture) -> None:
    """The guard that makes the replay mean something: no bucket may be empty.

    Without it, deleting every ACCEPT case would leave a green suite that proves only that a
    validator refusing all text refuses all text, and deleting the English cases would leave a
    Korean-only rule set looking complete.
    """
    families = {case.family for case in fixture.cases}
    verdicts = {case.verdict for case in fixture.cases}
    locales = {fixture.facts[case.profile].locale for case in fixture.cases}
    assert families == {"FAITHFUL", "HALLUCINATION", "INJECTION"}, f"missing families: {families}"
    assert verdicts == {"ACCEPT", "REFUSE"}, f"missing verdicts: {verdicts}"
    assert locales == {"ko", "en"}, f"missing locales: {locales}"
    for family in families:
        assert sum(1 for case in fixture.cases if case.family == family) >= 2, f"{family} needs more than one case"


@pytest.mark.parametrize(("fixture", "case"), CASES, ids=lambda value: value.id if isinstance(value, LlmCase) else None)
def test_the_validator_returns_the_verdict_the_corpus_records(fixture: LlmFixture, case: LlmCase) -> None:
    expected = case.verdict == "ACCEPT"
    assert accepts(fixture.facts[case.profile], case.text) is expected, f"{case.id}: {case.why}"
