"""스팟 식별·매칭(app/matching.py) — 이름 정규화·외부 식별자 매핑."""
from app.matching import normalize_name


def test_normalize_name_strips_parens_space_punct():
    assert normalize_name("경복궁(사적 제117호)") == "경복궁"
    assert normalize_name(" 북촌 한옥마을 ") == "북촌한옥마을"
    assert normalize_name("N서울타워") == "n서울타워"
    assert normalize_name("덕수궁길·정동길") == "덕수궁길정동길"
    assert normalize_name("") == ""


def test_spot_external_ref_roundtrip(db, gyeongbok_id):
    # source="seoul"은 B4 시드가 같은 키를 넣어 UNIQUE 충돌 → 별도 source로 왕복 검증
    from app import models
    db.add(models.SpotExternalRef(source="test", ext_key="경복궁", spot_id=gyeongbok_id))
    db.commit()
    row = db.query(models.SpotExternalRef).filter_by(source="test", ext_key="경복궁").one()
    assert row.spot_id == gyeongbok_id
    assert row.method == "seed"


def test_resolve_spot_by_ref_then_name(db, gyeongbok_id):
    from app import matching, models
    # ref 히트
    db.add(models.SpotExternalRef(source="tats", ext_key="경복궁", spot_id=gyeongbok_id))
    db.commit()
    assert matching.resolve_spot(db, "tats", "경복궁(사적)") == gyeongbok_id
    # ref 미존재 → 이름 정규화 완전일치로 해결되고 ref가 upsert된다
    assert matching.resolve_spot(db, "related", "경복궁 ") == gyeongbok_id
    saved = db.query(models.SpotExternalRef).filter_by(source="related").one()
    assert saved.spot_id == gyeongbok_id and saved.method == "name"


def test_seed_external_refs_maps_seoul_areas(client, db):
    from app import models
    # 시드가 서울 area 매핑을 최소 10개 이상 넣는다(정규화 키로 저장)
    from app.matching import normalize_name
    count = db.query(models.SpotExternalRef).filter_by(source="seoul").count()
    assert count >= 10
    gy = db.query(models.SpotExternalRef).filter_by(
        source="seoul", ext_key=normalize_name("경복궁")).one()
    assert gy.spot_id


def test_seed_run_backfills_external_refs_on_existing_db(db, client):
    """이미 시드된 DB(조기 반환 경로)에서도 run()이 area 매핑을 backfill한다."""
    from app import models, seed_data
    db.query(models.SpotExternalRef).filter_by(source="seoul").delete()
    db.commit()
    seed_data.run(db)   # 데이터가 있어 스팟 시드는 건너뛰지만 refs는 채워야 함
    count = db.query(models.SpotExternalRef).filter_by(source="seoul").count()
    assert count >= 10


def test_resolve_spot_coord_fallback_and_miss(db, gyeongbok_id):
    from app import matching, models
    spot = db.get(models.TouristSpot, gyeongbok_id)
    # 이름·좌표 모두 실패 → None (coord 폴백이 ref를 upsert하므로 miss를 먼저 확인)
    assert matching.resolve_spot(db, "tats", "없는이름xyz") is None
    # 이름은 안 맞지만 좌표가 스팟 근처 → 좌표 폴백 + ref upsert
    assert matching.resolve_spot(db, "tats", "없는이름xyz",
                                 lat=spot.lat, lng=spot.lng) == gyeongbok_id
    saved = db.query(models.SpotExternalRef).filter_by(
        source="tats", ext_key="없는이름xyz").one()
    assert saved.method == "coord" and saved.spot_id == gyeongbok_id
