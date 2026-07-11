"""분산 임팩트 집계 API — 메인 카운터용(기획서 12장, 시드 제외)."""
from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app import schemas
from app.database import get_db
from app.services.impact_service import weekly_summary

router = APIRouter(prefix="/api/impact", tags=["impact"])


@router.get("/summary", response_model=schemas.ImpactSummary)
def impact_summary(db: Session = Depends(get_db)):
    return weekly_summary(db)
