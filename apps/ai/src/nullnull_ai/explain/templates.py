"""Deterministic KO/EN sentences (§9.1): the metric is N points lower, and here is the source.

Only the point difference may be stated. A visitor count, "quieter now", opening hours, a shorter
route or a saved minute needs evidence these facts do not carry, so no template mentions them.

Dates and times are spelled out here rather than by a locale library: the sentence must read the same
on every machine and in every run, and the recommendation packages carry no clock or environment.

The attribution is never cut. When the full sentence would pass `MAX_LENGTH`, the place name is
shortened with an ellipsis; when even that cannot make room, rendering fails loudly instead of
returning a sentence whose source has been trimmed away.
"""

from __future__ import annotations

from datetime import date as date_
from datetime import time as time_
from decimal import Decimal

from nullnull_ai.explain.facts import ExplanationFacts, Locale

MAX_LENGTH = 500
"""Longest sentence the FE renders in one explanation card; the validator holds model text to it too."""

ELLIPSIS = "…"

_MONTHS_EN = ("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

_KO = "{place} 방문을 {before}에서 {after}로 옮기면 {metric}가 {b}에서 {a}로 {delta}포인트 낮아져요. {attribution}"
_EN = "Moving {place} from {before} to {after} lowers {metric} from {b} to {a} ({delta} points). {attribution}"


def plain(value: Decimal) -> str:
    """The number as the sentence spells it: no exponent and no trailing zeros, so 80.00 reads as 80."""
    return format(value.normalize(), "f")


def display_name(facts: ExplanationFacts) -> str:
    """The place name as the sentence shows it - shortened only as far as the length cap forces."""
    overflow = len(_sentence(facts, facts.place_name)) - MAX_LENGTH
    if overflow <= 0:
        return facts.place_name
    keep = len(facts.place_name) - overflow - len(ELLIPSIS)
    if keep < 1:
        raise ValueError(f"explanation cannot keep its attribution within {MAX_LENGTH} characters")
    return facts.place_name[:keep] + ELLIPSIS


def render(facts: ExplanationFacts) -> str:
    """The KO/EN sentence for one verified improvement, source line included."""
    return _sentence(facts, display_name(facts))


def _sentence(facts: ExplanationFacts, place_name: str) -> str:
    template = _KO if facts.locale == "ko" else _EN
    return template.format(
        place=place_name,
        before=_slot(facts.locale, facts.before_date, facts.before_time),
        after=_slot(facts.locale, facts.after_date, facts.after_time),
        metric=facts.metric_label,
        b=plain(facts.before_value),
        a=plain(facts.after_value),
        delta=plain(facts.point_delta()),
        attribution=facts.attribution,
    )


def _slot(locale: Locale, day: date_, at: time_ | None) -> str:
    """A date, and the hour only when one was verified: P0 never invents a time for a date-only slot."""
    day_text = f"{day.month}월 {day.day}일" if locale == "ko" else f"{_MONTHS_EN[day.month - 1]} {day.day}"
    return day_text if at is None else f"{day_text} {at.hour:02d}:{at.minute:02d}"
