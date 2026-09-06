"""§5.5 objective for same-POI temporal candidates. Fixed-point Decimal at policy scale."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, time
from decimal import Decimal

from nullnull_ai.domain.policy import RecommendationPolicy
from nullnull_ai.domain.types import CandidateKey, Reason, ScoreBreakdown
from nullnull_ai.item.types import ComparisonVerdict

COMPARISON_INELIGIBLE = "COMPARISON_INELIGIBLE"
IMPROVEMENT_BELOW_MINIMUM = "IMPROVEMENT_BELOW_MINIMUM"
SCORE_NOT_POSITIVE = "SCORE_NOT_POSITIVE"
METRIC_POLICY_MISSING = "METRIC_POLICY_MISSING"


@dataclass(frozen=True, slots=True)
class TemporalShift:
    before_value: Decimal
    after_value: Decimal
    before_instant: datetime
    after_instant: datetime

    def shift_minutes(self) -> int:
        return abs(int((self.after_instant - self.before_instant).total_seconds()) // 60)


@dataclass(frozen=True, slots=True)
class Admitted:
    score: ScoreBreakdown
    improvement: Decimal
    relief: Decimal
    change_cost: Decimal


@dataclass(frozen=True, slots=True)
class Rejected:
    reason: Reason


Admission = Admitted | Rejected


class ItemScorePolicy:
    def __init__(self, policy: RecommendationPolicy) -> None:
        self._policy = policy

    def evaluate(self, metric_code: str, verdict: ComparisonVerdict, shift: TemporalShift) -> Admission:
        policy = self._policy
        metric = policy.metrics.get(metric_code)
        if metric is None:
            return Rejected(Reason(METRIC_POLICY_MISSING, f"no scale/minimum for metric {metric_code}"))
        objective = policy.item_objective
        if objective.require_comparison_eligible and not verdict.eligible:
            return Rejected(Reason(COMPARISON_INELIGIBLE, verdict.reason_code))
        improvement = shift.before_value - shift.after_value
        if improvement < metric.minimum_improvement:
            return Rejected(Reason(IMPROVEMENT_BELOW_MINIMUM, f"improvement below policy minimum for {metric_code}"))
        relief = policy.quantize(improvement / metric.metric_scale)
        change_cost = policy.quantize(
            min(Decimal(1), Decimal(shift.shift_minutes()) / Decimal(objective.change_cost_saturation_minutes))
        )
        relief_term = policy.quantize(objective.relief_weight * relief)
        change_cost_term = policy.quantize(objective.change_cost_weight * change_cost)
        score = policy.quantize(relief_term - change_cost_term)
        if objective.require_score_positive and score <= 0:
            return Rejected(Reason(SCORE_NOT_POSITIVE, "score is not positive"))
        breakdown = ScoreBreakdown(
            score,
            (
                ("relief", relief),
                ("changeCost", change_cost),
                ("reliefTerm", relief_term),
                ("changeCostTerm", change_cost_term),
            ),
        )
        return Admitted(breakdown, improvement, relief, change_cost)


@dataclass(frozen=True, slots=True)
class ScoredCandidate:
    key: CandidateKey
    proposed_start_time: time | None
    admission: Admitted


def proposal_sort_key(scored: ScoredCandidate) -> tuple[Decimal, Decimal, object, int, object, str]:
    """score DESC → changeCost ASC → date ASC → time ASC (date-only first) → placeId ASC (§5.5)."""
    time_value = scored.proposed_start_time
    return (
        -scored.admission.score.score,
        scored.admission.change_cost,
        scored.key.date,
        0 if time_value is None else 1,
        time_value if time_value is not None else 0,
        str(scored.key.place_id),
    )
