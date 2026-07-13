"""detailIntro2 운영정보 — 콘텐츠 타입별 필드 해석·휴무일 판정."""
from datetime import date

from app.batch.daily import intro_fields, is_closed_on


def test_intro_fields_resolve_by_content_type():
    # 음식점(39)
    ut, rd, pk = intro_fields({"opentimefood": "10:00~22:00", "restdatefood": "월요일",
                               "parkingfood": "가능"}, 39)
    assert ut.startswith("10:00") and rd == "월요일" and pk == "가능"
    # 관광지(12)
    ut2, _, _ = intro_fields({"usetime": "09:00~18:00"}, 12)
    assert ut2.startswith("09:00")


def test_is_closed_on_weekly_rest():
    monday = date(2026, 7, 13)  # 월요일
    assert is_closed_on("매주 월요일 휴무", monday) is True
    assert is_closed_on("연중무휴", monday) is False
    assert is_closed_on(None, monday) is False
