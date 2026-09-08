"""Strict loader for the versioned recommendation policy (single source: policy/policy-v1.yaml).

Scoring constants are read as quoted strings or integers and converted to Decimal; floats are
rejected. Every key is required. The SHA-256 of the file bytes is the policyHash recorded in
every response and in the Spring run fingerprint.
"""

from __future__ import annotations

import hashlib
from collections.abc import Mapping
from dataclasses import dataclass
from decimal import ROUND_HALF_EVEN, Decimal
from importlib import resources
from types import MappingProxyType
from typing import Any

import yaml

DEFAULT_RESOURCE = "policy-v1.yaml"

_ROUNDING = {"HALF_EVEN": ROUND_HALF_EVEN}


class PolicyError(ValueError):
    """Raised when the policy file is missing a key or has a malformed value."""


@dataclass(frozen=True, slots=True)
class Numeric:
    scale: int
    rounding: str


@dataclass(frozen=True, slots=True)
class CandidateCaps:
    feed_snapshot: int
    related_per_channel: int
    related_merged: int
    slot_dates: int
    slot_detailed: int
    item_detailed: int
    item_proposals: int


@dataclass(frozen=True, slots=True)
class ItemObjective:
    relief_weight: Decimal
    change_cost_weight: Decimal
    change_cost_saturation_minutes: int
    require_comparison_eligible: bool
    require_score_positive: bool
    tie_break: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class MetricPolicy:
    metric_scale: Decimal
    minimum_improvement: Decimal
    note: str


@dataclass(frozen=True, slots=True)
class RecommendationPolicy:
    version: str
    pipeline_version: str
    hash: str
    numeric: Numeric
    candidate_caps: CandidateCaps
    item_objective: ItemObjective
    metrics: Mapping[str, MetricPolicy]
    related_ordering: tuple[str, ...]
    feed_ordering: tuple[str, ...]
    feed_cursor_ttl_minutes: int
    feed_snapshot_max_per_owner: int

    def quantize(self, value: Decimal) -> Decimal:
        exponent = Decimal(1).scaleb(-self.numeric.scale)
        return value.quantize(exponent, rounding=_ROUNDING[self.numeric.rounding])


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def load_default() -> RecommendationPolicy:
    package = resources.files("nullnull_ai.policy")
    return load(package.joinpath(DEFAULT_RESOURCE).read_bytes())


def load(data: bytes) -> RecommendationPolicy:
    document = yaml.safe_load(data.decode("utf-8"))
    root = _section(document, "<root>")
    numeric = _section(root.get("numeric"), "numeric")
    caps = _section(root.get("candidateCaps"), "candidateCaps")
    objective = _section(root.get("itemObjective"), "itemObjective")
    admit = _section(objective.get("admit"), "itemObjective.admit")
    metrics_section = _section(root.get("metrics"), "metrics")
    metrics = {
        code: MetricPolicy(
            metric_scale=_decimal(_section(value, f"metrics.{code}"), "metricScale", f"metrics.{code}"),
            minimum_improvement=_decimal(_section(value, f"metrics.{code}"), "minimumImprovement", f"metrics.{code}"),
            note=_string(_section(value, f"metrics.{code}"), "note", f"metrics.{code}"),
        )
        for code, value in metrics_section.items()
    }
    rounding = _string(numeric, "roundingMode", "numeric")
    if rounding not in _ROUNDING:
        raise PolicyError(f"unsupported rounding mode {rounding!r}")
    return RecommendationPolicy(
        version=_string(root, "policyVersion", "<root>"),
        pipeline_version=_string(root, "pipelineVersion", "<root>"),
        hash=sha256_hex(data),
        numeric=Numeric(scale=_int(numeric, "scale", "numeric"), rounding=rounding),
        candidate_caps=CandidateCaps(
            feed_snapshot=_int(caps, "feedSnapshot", "candidateCaps"),
            related_per_channel=_int(caps, "relatedPerChannel", "candidateCaps"),
            related_merged=_int(caps, "relatedMerged", "candidateCaps"),
            slot_dates=_int(caps, "slotDates", "candidateCaps"),
            slot_detailed=_int(caps, "slotDetailed", "candidateCaps"),
            item_detailed=_int(caps, "itemDetailed", "candidateCaps"),
            item_proposals=_int(caps, "itemProposals", "candidateCaps"),
        ),
        item_objective=ItemObjective(
            relief_weight=_decimal(objective, "reliefWeight", "itemObjective"),
            change_cost_weight=_decimal(objective, "changeCostWeight", "itemObjective"),
            change_cost_saturation_minutes=_int(objective, "changeCostSaturationMinutes", "itemObjective"),
            require_comparison_eligible=_bool(admit, "requireComparisonEligible", "itemObjective.admit"),
            require_score_positive=_bool(admit, "requireScorePositive", "itemObjective.admit"),
            tie_break=_strings(objective, "tieBreak", "itemObjective"),
        ),
        metrics=MappingProxyType(metrics),
        related_ordering=_strings(root, "relatedOrdering", "<root>"),
        feed_ordering=_strings(root, "feedOrdering", "<root>"),
        feed_cursor_ttl_minutes=_int(root, "feedCursorTtlMinutes", "<root>"),
        feed_snapshot_max_per_owner=_int(root, "feedSnapshotMaxPerOwner", "<root>"),
    )


def _section(value: Any, path: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise PolicyError(f"policy section missing or not a mapping: {path}")
    return value


def _required(section: dict[str, Any], key: str, path: str) -> Any:
    if key not in section or section[key] is None:
        raise PolicyError(f"policy value missing: {path}.{key}")
    return section[key]


def _string(section: dict[str, Any], key: str, path: str) -> str:
    value = _required(section, key, path)
    if not isinstance(value, str) or not value.strip():
        raise PolicyError(f"policy value must be a non-blank string: {path}.{key}")
    return value


def _int(section: dict[str, Any], key: str, path: str) -> int:
    value = _required(section, key, path)
    if isinstance(value, bool) or not isinstance(value, int):
        raise PolicyError(f"policy value must be an integer: {path}.{key}")
    return value


def _bool(section: dict[str, Any], key: str, path: str) -> bool:
    value = _required(section, key, path)
    if not isinstance(value, bool):
        raise PolicyError(f"policy value must be a boolean: {path}.{key}")
    return value


def _decimal(section: dict[str, Any], key: str, path: str) -> Decimal:
    value = _required(section, key, path)
    if isinstance(value, bool):
        raise PolicyError(f"policy decimal must not be a boolean: {path}.{key}")
    if isinstance(value, int):
        return Decimal(value)
    if isinstance(value, str):
        try:
            parsed = Decimal(value.strip())
        except ArithmeticError as error:
            raise PolicyError(f"policy decimal is malformed: {path}.{key}") from error
        if not parsed.is_finite():
            raise PolicyError(f"policy decimal must be finite: {path}.{key}")
        return parsed
    raise PolicyError(f"policy decimal must be an integer or a quoted decimal string (no floats): {path}.{key}")


def _strings(section: dict[str, Any], key: str, path: str) -> tuple[str, ...]:
    value = _required(section, key, path)
    if not isinstance(value, list) or not value or not all(isinstance(item, str) for item in value):
        raise PolicyError(f"policy value must be a non-empty string list: {path}.{key}")
    return tuple(value)
