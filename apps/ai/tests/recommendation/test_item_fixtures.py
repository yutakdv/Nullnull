"""REC-OPT-01 / REC-CI-4 section 4.2: the RULES_P0 ITEM corpus gate.

Each fixture is evaluated once, then re-evaluated under 25 seeded permutations of the candidate,
neighbour and lock order; the returned proposals are re-checked against an independent restatement
of the hard rules (`evaluation.invariants`, which never imports the evaluator or its filters). The
counters are recorded BEFORE the assertions so `evaluation.json` still describes a red run.

An empty ITEM fixture list is a configuration error, not a pass: the module fails at import.
"""

from __future__ import annotations

import json
import random
from dataclasses import replace
from datetime import date, time
from decimal import Decimal
from pathlib import Path
from typing import Any

import pytest

from nullnull_ai.domain.policy import load_default
from nullnull_ai.domain.types import RecommendationContext
from nullnull_ai.evaluation import invariants
from nullnull_ai.evaluation.fixtures import FixtureEntry, ItemFixture, load_item_fixture, of_kind, read_entries
from nullnull_ai.evaluation.report import REPORT
from nullnull_ai.item.evaluator import ItemProposalEvaluator, ItemProposalResult
from nullnull_ai.item.types import ItemOptimizationInput

APP_ROOT = Path(__file__).resolve().parents[2]
MANIFEST = Path(__file__).with_name("manifest.json")
MANIFEST_DOCUMENT: dict[str, Any] = json.loads(MANIFEST.read_text(encoding="utf-8"))
CATALOG_VERSION = "catalog-fixture-1"
SHUFFLES = 25

# REC-CI-4 section 4.1 names these ITEM scenarios; dropping one would quietly shrink the gate.
REQUIRED_ITEM_FIXTURES = frozenset(
    {
        "temporal-same-issue",
        "temporal-mixed-issue",
        "locked-reservation-and-time",
        "locked-date-and-reservation",
        "unknown-hours-and-route",
        "deterministic-score-boundaries",
        "stale-incident-missing",
        "neighbour-overlap-and-unknown",
        "merged-slot-day-and-hour",
    }
)

POLICY = load_default()
EVALUATOR = ItemProposalEvaluator(POLICY)
ITEM_ENTRIES = of_kind(read_entries(MANIFEST_DOCUMENT), "ITEM")
if not ITEM_ENTRIES:
    raise RuntimeError("RULES_P0 needs at least one ITEM fixture; a zero denominator is a configuration error")


def test_the_corpus_covers_every_item_scenario_of_this_slice() -> None:
    assert {entry.id for entry in ITEM_ENTRIES} == REQUIRED_ITEM_FIXTURES


@pytest.mark.parametrize("entry", ITEM_ENTRIES, ids=lambda entry: entry.id)
def test_fixture_matches_its_hand_calculation_and_the_independent_invariants(entry: FixtureEntry) -> None:
    fixture = load_item_fixture(APP_ROOT, entry)
    context = RecommendationContext(fixture.fixed_clock, POLICY.version, POLICY.hash, CATALOG_VERSION)
    result = EVALUATOR.evaluate(context, fixture.input)
    checked = invariants.check(fixture.input, result.proposals)
    mismatches = _determinism_mismatches(context, fixture.input, result)
    # Recorded first: a red assertion below must still leave its counters in evaluation.json.
    REPORT.record_fixture(
        fixture_id=fixture.id,
        outcome=result.outcome.value,
        expect_proposals=fixture.expected.expect_proposals,
        got_proposals=bool(result.proposals),
        violations=checked.violations,
        unsupported=checked.unsupported_comparisons,
        mismatches=mismatches,
        provenance_satisfied=checked.provenance_satisfied,
        provenance_checked=checked.provenance_checked,
    )
    assert checked.violations == (), f"{fixture.id}: {fixture.derivation}"
    assert result.outcome.value == fixture.expected.outcome
    assert _returned(result) == _expected(fixture)
    assert dict(result.summary.rejected_by_reason) == dict(fixture.expected.rejected_by_reason)
    assert mismatches == 0
    if fixture.expected.expect_proposals:
        assert result.proposals, "a positive fixture must return at least one proposal"


def _returned(result: ItemProposalResult) -> list[tuple[date | None, time | None, str, str, str]]:
    return [
        (
            proposal.candidate.key.date,
            proposal.proposed_start_time,
            _plain(proposal.admission.score.score),
            _plain(proposal.admission.improvement),
            _plain(proposal.admission.change_cost),
        )
        for proposal in result.proposals
    ]


def _expected(fixture: ItemFixture) -> list[tuple[date | None, time | None, str, str, str]]:
    return [
        (proposal.date, proposal.start_time, proposal.score, proposal.improvement, proposal.change_cost)
        for proposal in fixture.expected.proposals
    ]


def _plain(value: Decimal) -> str:
    return format(value, "f")


def _determinism_mismatches(
    context: RecommendationContext, inp: ItemOptimizationInput, expected: ItemProposalResult
) -> int:
    """Arrival order must never change the result (section 6); the seed comes from the manifest."""
    rng = random.Random(MANIFEST_DOCUMENT["randomSeeds"][0])
    mismatches = 0
    for _ in range(SHUFFLES):
        permuted = replace(
            inp,
            candidates=tuple(_shuffled(rng, inp.candidates)),
            neighbours=tuple(_shuffled(rng, inp.neighbours)),
            locks=tuple(_shuffled(rng, inp.locks)),
        )
        again = EVALUATOR.evaluate(context, permuted)
        if again.outcome is not expected.outcome or _returned(again) != _returned(expected):
            mismatches += 1
        elif dict(again.summary.rejected_by_reason) != dict(expected.summary.rejected_by_reason):
            mismatches += 1
    return mismatches


def _shuffled[T](rng: random.Random, values: tuple[T, ...]) -> list[T]:
    items = list(values)
    rng.shuffle(items)
    return items
