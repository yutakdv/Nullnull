"""관리자(내부용) API — 합성 시드 주입·수집 상태·추천 부하 분포(기획서 12장·F8 시연)."""
from datetime import datetime, timedelta

from fastapi import APIRouter, Depends, Header, HTTPException
from sqlalchemy import case, func, select
from sqlalchemy.orm import Session

from app import models, schemas
from app.config import get_settings
from app.database import get_db
from app.scoring.weights import load_weights

router = APIRouter(prefix="/api/admin", tags=["admin"])


def require_admin(x_admin_token: str = Header(default="")) -> None:
    if x_admin_token != get_settings().admin_token:
        raise HTTPException(status_code=401, detail="관리자 토큰이 올바르지 않아요.")


@router.post("/seed", response_model=schemas.OkResponse,
             dependencies=[Depends(require_admin)])
def inject_seed(body: schemas.AdminSeedRequest, db: Session = Depends(get_db)):
    """합성 시드 로그 주입 배치(9-2 보조 시드) — 전부 is_seed=true로 구분 저장."""
    spots = db.scalars(select(models.TouristSpot)).all()
    if not spots:
        raise HTTPException(status_code=409, detail="스팟 시드가 먼저 필요해요.")
    alternatives = [s for s in spots if s.base_popularity <= 55] or spots
    populars = [s for s in spots if s.base_popularity > 55] or spots
    now = datetime.now()

    for i in range(body.exposures):
        spot = alternatives[i % len(alternatives)]
        db.add(models.RecommendationLog(
            spot_id=spot.spot_id, origin_spot_id=populars[i % len(populars)].spot_id,
            exposed_at=now - timedelta(hours=(i * 7) % (7 * 24)),
            selected=False, is_seed=True,
        ))
    for i in range(body.selections):
        spot = alternatives[i % len(alternatives)]
        db.add(models.RecommendationLog(
            spot_id=spot.spot_id, origin_spot_id=populars[i % len(populars)].spot_id,
            exposed_at=now - timedelta(hours=(i * 13) % (7 * 24)),
            selected=True, is_seed=True,
        ))
    for i in range(body.feedbacks):
        spot = populars[i % len(populars)]
        db.add(models.VisitFeedback(
            spot_id=spot.spot_id, perceived=[1, 0, 1, -1][i % 4], is_seed=True,
            created_at=now - timedelta(hours=i * 3),
        ))
    db.commit()
    return {
        "ok": True,
        "message": (f"합성 시드 주입 완료(노출 {body.exposures}, 선택 {body.selections}, "
                    f"피드백 {body.feedbacks}) — is_seed=true로 저장돼 임팩트 집계에서 제외돼요."),
    }


@router.get("/ingest-log", response_model=schemas.IngestLogResponse,
            dependencies=[Depends(require_admin)])
def ingest_log(db: Session = Depends(get_db)):
    """공사 API 수집 상태(구동 안정성 근거) + 대안지 추천 부하 분포(F8 관리자 화면)."""
    logs = db.scalars(
        select(models.ApiIngestLog)
        .order_by(models.ApiIngestLog.last_synced_at.desc()).limit(30)
    ).all()

    lw = load_weights()["recommendation_load"]
    since = datetime.now() - timedelta(days=lw["window_days"])
    rows = db.execute(
        select(
            models.RecommendationLog.spot_id,
            func.count(),
            func.sum(case((models.RecommendationLog.selected.is_(True), 1), else_=0)),
        )
        .where(models.RecommendationLog.exposed_at >= since)
        .group_by(models.RecommendationLog.spot_id)
    ).all()
    raws = {sid: int(total) + lw["select_weight"] * int(sel or 0)
            for sid, total, sel in rows}
    max_raw = max(raws.values()) if raws else 0
    distribution = []
    for sid, total, sel in rows:
        spot = db.get(models.TouristSpot, sid)
        distribution.append({
            "spot_id": sid, "name": spot.name if spot else str(sid),
            "exposures": int(total), "selections": int(sel or 0),
            "load": round(raws[sid] / max_raw, 3) if max_raw else 0.0,
        })
    distribution.sort(key=lambda x: -x["load"])
    return {"ingest": logs, "load_distribution": distribution}
