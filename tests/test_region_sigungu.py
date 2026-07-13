"""지역통계 시군구 단위화 — 같은 area 안에서 스팟(시군구)별 변별력."""


def test_region_stat_supports_sigungu_rows(db):
    from app import models
    from datetime import date
    d = date.today()
    # 세션 공유 DB — 시드(C4)가 이미 넣었을 수 있어 upsert 방식으로 준비
    for code, idx in ((11110, 80), (11140, 40)):
        row = db.query(models.RegionStatDaily).filter_by(
            area_code=1, sigungu_code=code, date=d).one_or_none()
        if row:
            row.visitor_index = idx
        else:
            db.add(models.RegionStatDaily(
                area_code=1, sigungu_code=code, date=d, visitor_index=idx))
    db.commit()
    rows = db.query(models.RegionStatDaily).filter_by(area_code=1, date=d).all()
    assert {r.sigungu_code for r in rows} >= {11110, 11140}


def _upsert_region(db, sigungu_code, visitor_index, d):
    from app import models
    row = db.query(models.RegionStatDaily).filter_by(
        area_code=1, sigungu_code=sigungu_code, date=d).one_or_none()
    if row:
        row.visitor_index = visitor_index
    else:
        db.add(models.RegionStatDaily(
            area_code=1, sigungu_code=sigungu_code, date=d, visitor_index=visitor_index))


def test_two_sigungu_spots_get_different_region_signal(db):
    """같은 area·다른 sigungu의 방문지수가 risk를 실제로 다르게 만든다(변별력)."""
    from app.services import congestion_service as cs
    from datetime import date
    d = date.today()
    _upsert_region(db, 11110, 80, d)
    _upsert_region(db, 11140, 40, d)
    db.commit()
    a = cs.region_for(db, 1, 11110, d)
    b = cs.region_for(db, 1, 11140, d)
    assert a and b and a.visitor_index != b.visitor_index


def test_region_for_falls_back_to_area_row(db):
    """시군구 행이 없으면 서울 전체(sigungu NULL) 폴백 행을 쓴다."""
    from app.services import congestion_service as cs
    from datetime import date
    d = date.today()
    row = cs.region_for(db, 1, 99999, d)   # 존재하지 않는 시군구
    assert row is not None and row.sigungu_code is None
