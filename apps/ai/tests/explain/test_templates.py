"""KO/EN sentence templates (§9.1): the point difference and the source, nothing else.

The sentence may say that the metric is N points lower and name where the numbers come from. A
visitor count, "quieter now", opening hours or a route need evidence the facts do not carry, so the
template never mentions them. The attribution is the one part that is never cut: an over-long
sentence shortens the place name instead, and if even that cannot fit, rendering fails loudly.
"""

from __future__ import annotations

from dataclasses import replace
from datetime import date, time
from decimal import Decimal

import pytest

from nullnull_ai.explain.facts import ExplanationFacts
from nullnull_ai.explain.templates import MAX_LENGTH, display_name, render
from nullnull_ai.explain.validator import accepts

SEOUL_ATTRIBUTION = "출처: 서울특별시 「서울시 실시간 도시데이터」(2022년 공개, 공공누리 제1유형)"
"""The real source registry line of SOURCE_CATALOG.md §FCR-011: approved text that carries digits."""

KO = ExplanationFacts(
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

EN = replace(
    KO,
    locale="en",
    place_name="Gyeongbokgung",
    metric_label="relative concentration index",
    attribution="Source: ⓒ Korea Tourism Organization",
)


def test_the_korean_sentence_states_only_the_point_difference_and_the_source() -> None:
    text = render(KO)
    assert text == (
        "경복궁 방문을 9월 12일 10:00에서 9월 12일 12:00로 옮기면 "
        "상대 집중률가 80에서 60로 20포인트 낮아져요. 출처: ⓒ한국관광공사"
    )
    for claim in ("방문자", "한산", "여유로", "%", "영업", "경로", "가까"):
        assert claim not in text


def test_the_english_sentence_states_only_the_point_difference_and_the_source() -> None:
    assert render(EN) == (
        "Moving Gyeongbokgung from Sep 12 10:00 to Sep 12 12:00 lowers "
        "relative concentration index from 80 to 60 (20 points). Source: ⓒ Korea Tourism Organization"
    )


def test_a_slot_without_a_time_renders_the_date_alone() -> None:
    """P0 candidates carry no time; a missing time is left out, never filled with a default hour."""
    dates_only = replace(KO, before_time=None, after_date=date(2026, 9, 13), after_time=None)
    assert render(dates_only).startswith("경복궁 방문을 9월 12일에서 9월 13일로 옮기면")
    assert render(replace(EN, before_time=None, after_date=date(2026, 9, 13), after_time=None)).startswith(
        "Moving Gyeongbokgung from Sep 12 to Sep 13 lowers"
    )


def test_trailing_zeros_are_dropped_so_the_numbers_read_as_the_facts_do() -> None:
    scaled = replace(KO, before_value=Decimal("80.500"), after_value=Decimal("60.00"))
    assert " 80.5에서 60로 20.5포인트 " in render(scaled)


#: The longest sentence the contract can ask for: place name 200, metric label 64, source line 200.
AT_BOUNDS = replace(KO, place_name="장" * 200, metric_label="지" * 64, attribution="출" * 200)


@pytest.mark.parametrize("facts", [AT_BOUNDS, replace(AT_BOUNDS, locale="en", place_name="J" * 200)])
def test_a_place_name_at_the_bounds_is_shortened_and_the_attribution_survives_intact(
    facts: ExplanationFacts,
) -> None:
    rendered = render(facts)
    assert len(rendered) == MAX_LENGTH
    assert rendered.endswith(facts.attribution)
    assert "…" in rendered
    assert display_name(facts).endswith("…")
    assert display_name(facts)[:-1] == facts.place_name[: len(display_name(facts)) - 1]


def test_a_sentence_that_fits_keeps_the_whole_place_name() -> None:
    assert display_name(EN) == "Gyeongbokgung"
    assert len(render(EN)) < MAX_LENGTH


def test_a_sentence_that_cannot_keep_the_attribution_fails_loudly() -> None:
    """The source line is never truncated: when nothing else can give way, rendering refuses."""
    huge = replace(KO, before_value=Decimal("1" + "0" * 300))
    with pytest.raises(ValueError, match="attribution"):
        render(huge)


@pytest.mark.parametrize(
    "facts",
    [
        KO,
        EN,
        replace(KO, before_time=None, after_time=None),
        replace(KO, place_name="63빌딩"),
        replace(KO, place_name="청계천 물길 거리"),
        replace(EN, place_name="Open Air Km Distance Walk"),
        AT_BOUNDS,
        replace(AT_BOUNDS, locale="en", place_name="J" * 200),
        replace(KO, before_value=Decimal("80.500"), after_value=Decimal("60.00")),
        replace(KO, before_date=date(2026, 9, 5), before_time=time(9, 5)),
        replace(KO, attribution=SEOUL_ATTRIBUTION),
        replace(KO, metric_label="혼잡 지수 v2"),
        replace(EN, metric_label="concentration index v2", attribution=SEOUL_ATTRIBUTION),
    ],
    ids=lambda f: f"{f.locale}-{f.place_name[:12]}",
)
def test_every_rendered_sentence_passes_the_output_validator(facts: ExplanationFacts) -> None:
    """The validator judges model text, so the service's own sentence must always satisfy it."""
    assert accepts(facts, render(facts))


@pytest.mark.parametrize(
    "changes",
    [
        {"place_name": "경복\n궁"},
        {"metric_label": "상대\t집중률"},
        {"attribution": "출처:\r ⓒ한국관광공사"},
        {"forecast_issue_id": "issue\n1"},
    ],
    ids=lambda changes: next(iter(changes)),
)
def test_an_approved_string_that_would_break_the_sentence_in_two_is_refused(changes: dict[str, str]) -> None:
    """A control character in approved text renders a summary no card can hold and the validator
    refuses; it is a hydration bug, so it fails here rather than travelling as a 200."""
    with pytest.raises(ValueError, match="control character"):
        replace(KO, **changes)
