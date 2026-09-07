"""REC-LLM-01: model text is accepted only when it adds no fact (§9.1).

A rewrite may say the same thing differently. It may not introduce a number, an identifier, a link,
a second line, or a claim the facts cannot support - a visitor count, current quietness, a saving,
opening hours, a route or a distance. Anything else is refused and the caller keeps the template.

The three approved strings (place name, metric label, attribution) are masked out before the
vocabulary and number checks. They are the caller's own verified text, not model output: the source
line `출처: 서울특별시 「서울시 실시간 도시데이터」(2022년 공개, 공공누리 제1유형)` carries digits and a
place may really be called `63빌딩` or `청계천 물길 거리`, and none of that may make the service's own
sentence - or an otherwise faithful rewrite - unacceptable. Nothing outside those strings is masked,
so the same words one character to the left are still refused.
"""

from __future__ import annotations

import re

from nullnull_ai.explain.facts import ExplanationFacts
from nullnull_ai.explain.templates import MAX_LENGTH, display_name, plain

_NUMBER = re.compile(r"\d+(?:\.\d+)?")
_UUID = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
_URL = re.compile(r"https?://|www\.")

FORBIDDEN = (
    "방문자",
    "한산",
    "여유로",
    "절약",
    "%",
    "영업",
    "문 열",
    "문을 열",
    "열려",
    "닫",
    "휴무",
    "경로",
    "가까",
    "거리",
    "이동 시간",
    "visitor",
    "quiet",
    "empty",
    "save ",
    "open",
    "close",
    "route",
    "closer",
    "distance",
    "walk",
    "drive",
    "km",
)
"""Claims that need evidence the facts do not carry. Compared case-insensitively (§9.1)."""


def accepts(facts: ExplanationFacts, text: str | None) -> bool:
    """True when the sentence says only what these facts already say."""
    if not text or not text.strip() or len(text) > MAX_LENGTH or "\n" in text:
        return False
    if _UUID.search(text) or _URL.search(text):
        return False
    written = _without_approved_strings(facts, text)
    lowered = written.lower()
    if any(word in lowered for word in FORBIDDEN):
        return False
    allowed = _allowed_numbers(facts)
    return all(match.group() in allowed for match in _NUMBER.finditer(written))


def _without_approved_strings(facts: ExplanationFacts, text: str) -> str:
    """Blank out the place name (as the sentence may shorten it), the metric label and the source line.

    Longest first, in a fixed order, so the same text always masks to the same string. What disappears
    is only the caller's own approved text - a place really called `63빌딩`, a source line that names a
    year - so nothing the model invented can hide behind it. The cost is a little vocabulary coverage
    where an approved string ends inside a forbidden phrase.
    """
    phrases = (facts.place_name, display_name(facts), facts.metric_label, facts.attribution)
    for phrase in sorted(dict.fromkeys(phrases), key=len, reverse=True):
        text = text.replace(phrase, " ")
    return text


def _allowed_numbers(facts: ExplanationFacts) -> frozenset[str]:
    """Every number token the facts spell out: the two values, the delta, and their dates and times."""
    allowed: set[str] = set()
    for value in (facts.before_value, facts.after_value, facts.point_delta()):
        allowed.update(_NUMBER.findall(plain(value)))
    for day in (facts.before_date, facts.after_date):
        allowed.update((str(day.year), str(day.month), str(day.day)))
    for at in (facts.before_time, facts.after_time):
        if at is not None:
            allowed.update((str(at.hour), f"{at.hour:02d}", f"{at.minute:02d}"))
    return frozenset(allowed)
