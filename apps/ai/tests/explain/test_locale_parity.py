"""REC-LOC-01: the KO and EN sentences for one set of facts state the same numbers and the same slots.

`test_templates.py` checks each locale on its own, so nothing today would notice if one of them
started stating a different number, a different day or a different month. That is not a theoretical
gap: `templates.py` assembles dates per locale by hand (`9월 12일` against `Sep 12`, the month as a
digit on one side and a name on the other) precisely so a sentence reads the same on every machine,
and hand-assembled halves are what drift apart.

So both sentences are rendered from ONE `ExplanationFacts` and compared to each other. Asserting
each against its own expected string would restore exactly the blind spot this closes.

Two things are deliberately not shared with the production module:

* The month table below is the test's own. Importing `templates._MONTHS_EN` would make a reordering
  of that tuple invisible here - the test would follow the mutation and stay green.
* The place name is stripped per locale using `display_name`, not once for both. The two sentences
  have different lengths, so the length cap can shorten the name further in one locale than in the
  other; that difference is intended, and a parity test that did not account for it would fail on
  correct code.
"""

from __future__ import annotations

import re
from dataclasses import replace
from datetime import date, time
from decimal import Decimal

import pytest

from nullnull_ai.explain.facts import ExplanationFacts, Locale
from nullnull_ai.explain.templates import display_name, render

MONTHS = {
    "Jan": 1,
    "Feb": 2,
    "Mar": 3,
    "Apr": 4,
    "May": 5,
    "Jun": 6,
    "Jul": 7,
    "Aug": 8,
    "Sep": 9,
    "Oct": 10,
    "Nov": 11,
    "Dec": 12,
}

_KO_SLOT = re.compile(r"(\d{1,2})월 (\d{1,2})일(?: (\d{2}):(\d{2}))?")
_EN_SLOT = re.compile(rf"({'|'.join(MONTHS)}) (\d{{1,2}})(?: (\d{{2}}):(\d{{2}}))?")
_NUMBER = re.compile(r"\d+(?:\.\d+)?")

BASE = ExplanationFacts(
    locale="ko",
    place_name="경복궁",
    before_date=date(2026, 9, 12),
    before_time=time(10, 0),
    after_date=date(2026, 9, 12),
    after_time=time(12, 0),
    before_value=Decimal("80"),
    after_value=Decimal("60"),
    metric_label="상대 집중률",
    attribution="출처: ⓒ한국관광공사",
    forecast_issue_id="issue-1",
)

CASES = {
    "same day, two hours": BASE,
    "date-only slots": replace(BASE, before_time=None, after_date=date(2026, 9, 13), after_time=None),
    "across a month boundary": replace(
        BASE, before_date=date(2026, 9, 30), after_date=date(2026, 10, 1), after_time=time(9, 30)
    ),
    "the last month of the table": replace(
        BASE, before_date=date(2026, 12, 31), after_date=date(2026, 12, 31), after_time=time(23, 45)
    ),
    "the first month of the table": replace(
        BASE, before_date=date(2026, 1, 1), after_date=date(2026, 1, 2), after_time=None
    ),
    "trailing zeros and a fractional delta": replace(BASE, before_value=Decimal("80.50"), after_value=Decimal("60.25")),
    "digits inside the place name": replace(BASE, place_name="63빌딩"),
}


def _slots(text: str, locale: Locale) -> tuple[tuple[int, int, str | None], ...]:
    """Every slot the sentence states, as (month, day, HH:MM) - the form both locales must agree on."""
    pattern = _KO_SLOT if locale == "ko" else _EN_SLOT
    found: list[tuple[int, int, str | None]] = []
    for match in pattern.finditer(text):
        raw_month, day, hour, minute = match.groups()
        month = int(raw_month) if locale == "ko" else MONTHS[raw_month]
        found.append((month, int(day), None if hour is None else f"{hour}:{minute}"))
    return tuple(found)


def _values(facts: ExplanationFacts, text: str) -> tuple[str, ...]:
    """The metric numbers, in the order stated, once the slots and the approved strings are gone.

    The place name and the source line are the caller's own text and may carry digits of their own
    (`63빌딩`, a source line naming a year), so they are removed before anything is counted.
    """
    stripped = text.replace(display_name(facts), " ").replace(facts.attribution, " ")
    pattern = _KO_SLOT if facts.locale == "ko" else _EN_SLOT
    return tuple(_NUMBER.findall(pattern.sub(" ", stripped)))


@pytest.mark.parametrize("facts", CASES.values(), ids=CASES.keys())
def test_both_locales_state_the_same_slots(facts: ExplanationFacts) -> None:
    korean, english = replace(facts, locale="ko"), replace(facts, locale="en")
    assert _slots(render(korean), "ko") == _slots(render(english), "en")


@pytest.mark.parametrize("facts", CASES.values(), ids=CASES.keys())
def test_both_locales_state_the_same_values(facts: ExplanationFacts) -> None:
    korean, english = replace(facts, locale="ko"), replace(facts, locale="en")
    assert _values(korean, render(korean)) == _values(english, render(english))


@pytest.mark.parametrize("facts", CASES.values(), ids=CASES.keys())
def test_the_compared_sentences_actually_state_something(facts: ExplanationFacts) -> None:
    """The guard: two sentences that state no slot and no number agree with each other vacuously.

    A parsing helper that quietly matched nothing - a renamed month, a reshaped time - would make
    both comparisons above pass on empty tuples, which is the one way they could be green while
    measuring nothing.
    """
    korean, english = replace(facts, locale="ko"), replace(facts, locale="en")
    assert len(_slots(render(korean), "ko")) == 2, "both slots must be found in the Korean sentence"
    assert len(_slots(render(english), "en")) == 2, "both slots must be found in the English sentence"
    assert len(_values(korean, render(korean))) == 3, "before, after and the delta"
    assert len(_values(english, render(english))) == 3, "before, after and the delta"
