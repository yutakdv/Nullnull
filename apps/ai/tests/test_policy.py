from __future__ import annotations

from decimal import ROUND_HALF_EVEN, Decimal

import pytest

from nullnull_ai.domain import policy as policy_module
from nullnull_ai.domain.policy import PolicyError, load, load_default, sha256_hex

MINIMAL = """
policyVersion: policy-test
pipelineVersion: pipeline-test
numeric: { scale: 6, roundingMode: HALF_EVEN }
candidateCaps:
  feedSnapshot: 300
  relatedPerChannel: 100
  relatedMerged: 300
  slotDates: 90
  slotDetailed: 100
  itemDetailed: 100
  itemProposals: 3
itemObjective:
  reliefWeight: "0.80"
  changeCostWeight: "0.20"
  changeCostSaturationMinutes: 240
  admit: { requireComparisonEligible: true, requireScorePositive: true }
  tieBreak: [score DESC]
metrics: {}
relatedOrdering: [relationTier]
feedOrdering: [publishedAt DESC, postId ASC]
feedCursorTtlMinutes: 15
feedSnapshotMaxPerOwner: 5
"""


def test_default_policy_matches_documented_constants() -> None:
    policy = load_default()
    assert policy.version == "policy-v1"
    assert policy.pipeline_version == "nullnull-ai-pipeline-v1"
    assert len(policy.hash) == 64
    assert policy.numeric.scale == 6
    assert policy.item_objective.relief_weight == Decimal("0.80")
    assert policy.item_objective.change_cost_weight == Decimal("0.20")
    assert policy.item_objective.change_cost_saturation_minutes == 240
    assert policy.item_objective.tie_break == ("score DESC", "changeCost ASC", "date ASC", "time ASC", "placeId ASC")
    assert policy.candidate_caps.item_proposals == 3
    assert policy.candidate_caps.feed_snapshot == 300
    assert set(policy.metrics) == {"KTO_RELATIVE_CONCENTRATION_INDEX"}
    assert policy.metrics["KTO_RELATIVE_CONCENTRATION_INDEX"].metric_scale == Decimal(100)
    assert policy.metrics["KTO_RELATIVE_CONCENTRATION_INDEX"].minimum_improvement == Decimal(5)
    assert policy.feed_ordering == ("publishedAt DESC", "postId ASC")


def test_hash_is_the_sha256_of_the_bytes() -> None:
    data = MINIMAL.encode()
    assert load(data).hash == sha256_hex(data)
    assert load(data).hash != load(MINIMAL.replace("policy-test", "policy-other").encode()).hash


def test_quantize_uses_policy_scale_and_rounding() -> None:
    policy = load(MINIMAL.encode())
    assert policy.numeric.rounding == "HALF_EVEN"
    assert policy.quantize(Decimal("0.1234565")) == Decimal("0.123456")
    assert policy.quantize(Decimal("0.1234575")) == Decimal("0.123458")
    assert policy_module._ROUNDING["HALF_EVEN"] is ROUND_HALF_EVEN


@pytest.mark.parametrize(
    ("mutation", "message"),
    [
        (lambda s: s.replace("  changeCostSaturationMinutes: 240\n", ""), "itemObjective.changeCostSaturationMinutes"),
        (lambda s: s.replace('reliefWeight: "0.80"', "reliefWeight: 0.8"), "no floats"),
        (lambda s: s.replace("roundingMode: HALF_EVEN", "roundingMode: HALF_UP"), "rounding"),
        (lambda s: s.replace("feedOrdering: [publishedAt DESC, postId ASC]", "feedOrdering: []"), "feedOrdering"),
    ],
)
def test_missing_or_malformed_values_fail_loudly(mutation, message) -> None:  # type: ignore[no-untyped-def]
    with pytest.raises(PolicyError, match=message):
        load(mutation(MINIMAL).encode())
