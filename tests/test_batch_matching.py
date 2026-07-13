"""배치가 스팟명 완전일치 대신 resolve_spot으로 매칭한다(이름 변형 흡수).

주의: 세션 공유 DB라 test_matching.py가 쓰는 ("tats", "경복궁") 키와
겹치지 않게 다른 스팟(창덕궁)으로 계약을 고정한다.
"""


def test_resolve_spot_used_for_variant_names(db, client):
    from app import matching, models
    # '창덕궁 (조선)' 같은 변형도 창덕궁으로 해결
    sid = matching.resolve_spot(db, "tats", "창덕궁 (조선)")
    assert sid is not None
    assert db.get(models.TouristSpot, sid).name == "창덕궁"
