"""detailIntro2 운영정보 — 콘텐츠 타입별 필드 해석."""
from app.batch.daily import intro_fields


def test_intro_fields_resolve_by_content_type():
    # 음식점(39)
    ut, rd, pk = intro_fields({"opentimefood": "10:00~22:00", "restdatefood": "월요일",
                               "parkingfood": "가능"}, 39)
    assert ut.startswith("10:00") and rd == "월요일" and pk == "가능"
    # 관광지(12)
    ut2, _, _ = intro_fields({"usetime": "09:00~18:00"}, 12)
    assert ut2.startswith("09:00")
