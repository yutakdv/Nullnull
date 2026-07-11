"""방문 후 1탭 피드백 API(F7) — 예측 보정(9-4)의 데이터 소스."""
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app import models, schemas
from app.database import get_db

router = APIRouter(prefix="/api/feedback", tags=["feedback"])


@router.post("", response_model=schemas.OkResponse, status_code=201)
def create_feedback(body: schemas.FeedbackRequest, db: Session = Depends(get_db)):
    if not db.get(models.TouristSpot, body.spot_id):
        raise HTTPException(status_code=404, detail="관광지를 찾을 수 없어요.")
    if body.course_id and not db.get(models.Course, body.course_id):
        raise HTTPException(status_code=404, detail="코스를 찾을 수 없어요.")
    db.add(models.VisitFeedback(
        spot_id=body.spot_id, course_id=body.course_id,
        perceived=body.perceived, is_seed=False,
    ))
    db.commit()
    return {"ok": True, "message": "피드백이 반영됐어요"}
