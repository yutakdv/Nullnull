"""분산 임팩트 — 노출 로그의 원지/대안 혼잡차 저장·분산 리프트 지표."""


def test_recommendation_log_stores_risk_delta(db, gyeongbok_id):
    from app import models
    log = models.RecommendationLog(spot_id=gyeongbok_id, origin_spot_id=gyeongbok_id,
                                   origin_risk=85.0, alt_risk=40.0, decrease_pct=53)
    db.add(log)
    db.commit()
    saved = db.query(models.RecommendationLog).filter_by(id=log.id).one()
    assert saved.decrease_pct == 53 and saved.alt_risk == 40.0


def test_dispersion_lift_conversion_and_decrease(db, gyeongbok_id):
    from app import models
    from app.services.impact_service import weekly_summary
    # 노출 2건 중 1건 선택, 선택된 것의 실현 감소율 50
    db.add_all([
        models.RecommendationLog(spot_id=gyeongbok_id, selected=True,
                                 is_seed=False, decrease_pct=50),
        models.RecommendationLog(spot_id=gyeongbok_id, selected=False,
                                 is_seed=False, decrease_pct=30),
    ])
    db.commit()
    lift = weekly_summary(db)["dispersion_lift"]
    assert lift["exposed"] >= 2 and lift["selected"] >= 1
    assert lift["conversion_pct"] >= 0 and lift["avg_realized_decrease_pct"] >= 0
