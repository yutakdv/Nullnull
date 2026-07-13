"""분산 임팩트 — 노출 로그의 원지/대안 혼잡차 저장·분산 리프트 지표."""


def test_recommendation_log_stores_risk_delta(db, gyeongbok_id):
    from app import models
    log = models.RecommendationLog(spot_id=gyeongbok_id, origin_spot_id=gyeongbok_id,
                                   origin_risk=85.0, alt_risk=40.0, decrease_pct=53)
    db.add(log)
    db.commit()
    saved = db.query(models.RecommendationLog).filter_by(id=log.id).one()
    assert saved.decrease_pct == 53 and saved.alt_risk == 40.0
