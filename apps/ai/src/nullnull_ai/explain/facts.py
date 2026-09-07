"""The only values an explanation may mention (RECOMMENDATION_ALGORITHM.md §9.1 allowlist).

`place_name` is the approved catalog name, `metric_label` the published label of the compared metric
and `attribution` the source registry's own line. No raw itinerary, note, coordinate, owner or
session value is part of an explanation, so none of them can reach a template or a model.

The facts describe one verified improvement. A change that does not lower the metric is not an
explanation this service will write, so it is refused here rather than turned into a sentence that
claims an improvement of zero.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date as date_
from datetime import time as time_
from decimal import Decimal
from typing import Literal

Locale = Literal["ko", "en"]
"""P0 explanations exist in Korean and English only; ja/zh are `준비 중` and never rendered."""

MAX_PLACE_NAME = 200
MAX_METRIC_LABEL = 64
MAX_ATTRIBUTION = 200
MAX_FORECAST_ISSUE_ID = 64


@dataclass(frozen=True, slots=True)
class ExplanationFacts:
    """One before/after pair Spring already judged comparable, plus the strings the sentence may use."""

    locale: Locale
    place_name: str
    before_date: date_
    before_time: time_ | None
    after_date: date_
    after_time: time_ | None
    before_value: Decimal
    after_value: Decimal
    metric_label: str
    attribution: str
    forecast_issue_id: str | None = None

    def __post_init__(self) -> None:
        if self.locale not in ("ko", "en"):
            raise ValueError("P0 explanations exist for ko and en only")
        _bounded("place_name", self.place_name, MAX_PLACE_NAME)
        _bounded("metric_label", self.metric_label, MAX_METRIC_LABEL)
        _bounded("attribution", self.attribution, MAX_ATTRIBUTION)
        if self.forecast_issue_id is not None:
            _bounded("forecast_issue_id", self.forecast_issue_id, MAX_FORECAST_ISSUE_ID)
        for name in ("before_value", "after_value"):
            if not getattr(self, name).is_finite():
                raise ValueError(f"{name} must be a finite decimal")
        if self.point_delta() <= 0:
            raise ValueError("an explanation states a verified improvement: the metric must be lower after")

    def point_delta(self) -> Decimal:
        """How many points the metric is lower after the change; §9.1 allows no other derived number."""
        return self.before_value - self.after_value


def _bounded(name: str, value: str, limit: int) -> None:
    if not value.strip():
        raise ValueError(f"{name} must not be blank")
    if len(value) > limit:
        raise ValueError(f"{name} must be at most {limit} characters")
