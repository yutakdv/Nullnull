"""Deterministic KO/EN sentences (§9.1): the metric is N points lower, and here is the source.

Only the point difference may be stated. A visitor count, "quieter now", opening hours, a shorter
route or a saved minute needs evidence these facts do not carry, so no template mentions them.

Dates and times are spelled out here rather than by a locale library: the sentence must read the same
on every machine and in every run, and the recommendation packages carry no clock or environment.

Korean particles follow the sound of the word before them, so they are chosen per sentence rather
than fixed in the template: "상대 집중률이", "60으로", "13:00로". An ending whose sound this module cannot
tell (a Latin letter, a bracket) gets the written-out pair "이(가)" / "(으)로" instead of a guess.

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

_KO = (
    "{place} 방문을 {before}에서 {after}{after_ro} 옮기면 {metric}{metric_ga} {b}에서 {a}{a_ro} "
    "{delta}포인트 낮아져요. {attribution}"
)
_EN = "Moving {place} from {before} to {after} lowers {metric} from {b} to {a} ({delta} points). {attribution}"


_NO_FINAL = 0
_RIEUL = 8
"""Final-consonant (jongseong) indices that matter here: none takes 가/로, and ㄹ takes 로 as well."""

_DIGIT_FINAL = {"0": 21, "1": 8, "2": 0, "3": 16, "4": 0, "5": 0, "6": 1, "7": 8, "8": 8, "9": 0}
"""How each digit ends when read in Korean: 영 일 이 삼 사 오 육 칠 팔 구."""


def _final(word: str) -> int | None:
    """The final consonant the spoken word ends in (0 for none), or None when it cannot be told."""
    last = word[-1:]
    if "가" <= last <= "힣":
        return (ord(last) - ord("가")) % 28
    if last not in _DIGIT_FINAL:
        return None
    if last != "0":
        return _DIGIT_FINAL[last]
    # A trailing zero reads 영 or a place unit - 십 백 천 만 억 - and all end in a consonant other than ㄹ.
    # 조 (twelve zeros) ends in none, so from there on the sound is not claimed.
    zeros = len(word) - len(word.rstrip("0"))
    return _DIGIT_FINAL["0"] if zeros < 12 else None


def _i_ga(word: str) -> str:
    final = _final(word)
    return "이(가)" if final is None else ("가" if final == _NO_FINAL else "이")


def _euro(word: str) -> str:
    final = _final(word)
    return "(으)로" if final is None else ("로" if final in (_NO_FINAL, _RIEUL) else "으로")


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
    after_value = plain(facts.after_value)
    return template.format(
        place=place_name,
        before=_slot(facts.locale, facts.before_date, facts.before_time),
        after=_slot(facts.locale, facts.after_date, facts.after_time),
        after_ro=_euro(_slot_sound(facts.after_time)),
        metric=facts.metric_label,
        metric_ga=_i_ga(facts.metric_label),
        b=plain(facts.before_value),
        a=after_value,
        a_ro=_euro(after_value),
        delta=plain(facts.point_delta()),
        attribution=facts.attribution,
    )


def _slot(locale: Locale, day: date_, at: time_ | None) -> str:
    """A date, and the hour only when one was verified: P0 never invents a time for a date-only slot."""
    day_text = f"{day.month}월 {day.day}일" if locale == "ko" else f"{_MONTHS_EN[day.month - 1]} {day.day}"
    return day_text if at is None else f"{day_text} {at.hour:02d}:{at.minute:02d}"


def _slot_sound(at: time_ | None) -> str:
    """The word a slot is read ending in: "9월 13일" ends in 일, "12:00" is 열두 시, "12:30" ends in 분."""
    if at is None:
        return "일"
    return "시" if at.minute == 0 else "분"
