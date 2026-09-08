"""REC-SLOT-03: trip-local time is resolved with the trip zone only, never with the process zone."""

from __future__ import annotations

from datetime import UTC, date, datetime, time, timedelta
from zoneinfo import ZoneInfo

import pytest

from nullnull_ai.domain.time import Exact, Gap, Overlap, resolve


def test_seoul_noon_is_0300_utc() -> None:
    assert resolve(date(2026, 9, 12), time(12, 0), ZoneInfo("Asia/Seoul")) == Exact(
        datetime(2026, 9, 12, 3, 0, tzinfo=UTC)
    )


def test_spring_forward_gap_is_rejected() -> None:
    # America/New_York 2026-03-08 02:30 does not exist.
    assert isinstance(resolve(date(2026, 3, 8), time(2, 30), ZoneInfo("America/New_York")), Gap)


def test_fall_back_overlap_is_rejected_not_guessed() -> None:
    # America/New_York 2026-11-01 01:30 happens twice.
    assert isinstance(resolve(date(2026, 11, 1), time(1, 30), ZoneInfo("America/New_York")), Overlap)


def test_midnight_when_no_time() -> None:
    assert resolve(date(2026, 9, 12), None, ZoneInfo("Asia/Seoul")) == Exact(datetime(2026, 9, 11, 15, 0, tzinfo=UTC))


def test_result_does_not_depend_on_the_process_timezone(monkeypatch: pytest.MonkeyPatch) -> None:
    import random
    import time as clock

    rng = random.Random(20260906)
    zones = [ZoneInfo(name) for name in ("Asia/Seoul", "America/New_York", "Europe/London", "Australia/Lord_Howe")]
    cases: list[tuple[date, time | None, ZoneInfo]] = [
        (
            date(2026, 1, 1) + timedelta(days=rng.randrange(365)),
            time(rng.randrange(24), rng.choice((0, 30))),
            rng.choice(zones),
        )
        for _ in range(1_000)
    ]
    # Australia/Lord_Howe shifts by 30 minutes, so its gap/overlap also falls on whole/half hours.
    # The random draw hits a DST edge far too rarely to rely on, so the corpus pins the 2026 edges.
    cases += [
        (date(2026, 3, 8), time(2, 30), ZoneInfo("America/New_York")),
        (date(2026, 11, 1), time(1, 30), ZoneInfo("America/New_York")),
        (date(2026, 3, 29), time(1, 30), ZoneInfo("Europe/London")),
        (date(2026, 10, 25), time(1, 30), ZoneInfo("Europe/London")),
        (date(2026, 10, 4), time(2, 0), ZoneInfo("Australia/Lord_Howe")),
        (date(2026, 4, 5), time(1, 30), ZoneInfo("Australia/Lord_Howe")),
    ]
    expected = [resolve(*case) for case in cases]
    try:
        for process_tz in ("UTC", "Asia/Seoul", "America/Los_Angeles"):
            monkeypatch.setenv("TZ", process_tz)
            clock.tzset()
            assert [resolve(*case) for case in cases] == expected
    finally:
        monkeypatch.undo()
        clock.tzset()
    assert sum(isinstance(r, Gap) for r in expected) >= 3, "the corpus must hit DST gaps"
    assert sum(isinstance(r, Overlap) for r in expected) >= 3, "the corpus must hit DST overlaps"
    assert any(isinstance(r, Exact) for r in expected)
