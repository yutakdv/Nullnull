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
