"""REC-LLM-01: model text is accepted only when it adds no fact (§9.1).

A rewrite may say the same thing differently. It may not introduce a number, an identifier, a link,
a second line, or a claim the facts cannot support - a visitor count, current quietness, a saving,
opening hours, a route or a distance. Anything else is refused and the caller keeps the template.

The three approved strings (place name - as the sentence may shorten it - metric label, attribution)
are the caller's own verified text, not model output: the source line
`출처: 서울특별시 「서울시 실시간 도시데이터」(2022년 공개, 공공누리 제1유형)` carries digits and a place may
really be called `63빌딩` or `청계천 물길 거리`, and none of that may make the service's own sentence
unacceptable. So those strings are located as spans and left in place; nothing is deleted. A claim
word or a number is exempt only when it lies entirely inside one span. Anything that touches or
crosses a span boundary is judged like any other model text: `광화문 열어요` still fails on `문 열`, and
a place called `1` does not turn `대기 11분` into a known number.
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

_FORBIDDEN_PATTERNS = tuple(re.compile(re.escape(word), re.IGNORECASE) for word in FORBIDDEN)
"""One pattern per claim, searched independently: a word inside a fact span never hides an overlapping
one that crosses the boundary. Matching on the raw text keeps the offsets aligned with the spans,
which `str.lower()` would not (some characters change length when lowercased)."""


def accepts(facts: ExplanationFacts, text: str | None) -> bool:
    """True when the sentence says only what these facts already say."""
    if not text or not text.strip() or len(text) > MAX_LENGTH or "\n" in text:
        return False
    if _UUID.search(text) or _URL.search(text):
        return False
    spans = _fact_spans(facts, text)
    for pattern in _FORBIDDEN_PATTERNS:
        for claim in pattern.finditer(text):
            if not _within_one_span(spans, claim.start(), claim.end()):
                return False
    allowed = _allowed_numbers(facts)
    for number in _NUMBER.finditer(text):
        if not _within_one_span(spans, number.start(), number.end()) and number.group() not in allowed:
            return False
    return True


def _fact_spans(facts: ExplanationFacts, text: str) -> tuple[tuple[int, int], ...]:
    """Where the caller's approved strings sit in the text: every exact occurrence, longest phrase
    first, and never two spans over the same characters. Case-sensitive, because an approved name is
    reproduced verbatim or it is not that name.
    """
    phrases = (facts.place_name, display_name(facts), facts.metric_label, facts.attribution)
    spans: list[tuple[int, int]] = []
    for phrase in sorted(dict.fromkeys(phrases), key=len, reverse=True):
        start = text.find(phrase)
        while start >= 0:
            end = start + len(phrase)
            if not any(taken_start < end and start < taken_end for taken_start, taken_end in spans):
                spans.append((start, end))
            start = text.find(phrase, start + 1)
    return tuple(sorted(spans))


def _within_one_span(spans: tuple[tuple[int, int], ...], start: int, end: int) -> bool:
    """A match is the caller's own text only if a single approved string contains all of it."""
    return any(span_start <= start and end <= span_end for span_start, span_end in spans)


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
