"""외부 API 식별자 ↔ 스팟 매칭(집중률 tAtsNm / 서울 area명 / 연관 이름)."""
import re

from sqlalchemy import select
from sqlalchemy.orm import Session

from app import models
from app.geo import haversine_km

_PARENS = re.compile(r"[(\[（【].*?[)\]）】]")
_NONWORD = re.compile(r"[\s·,\-_/.]+")


def normalize_name(name: str) -> str:
    """괄호 내용 제거 → 공백·구두점 제거 → 소문자화. '경복궁(사적)' → '경복궁'."""
    if not name:
        return ""
    text = _PARENS.sub("", name)
    text = _NONWORD.sub("", text)
    return text.strip().lower()


def nearest_spot(db: Session, lat: float, lng: float, max_km: float = 0.3) -> int | None:
    best_id, best_km = None, max_km
    for sid, s_lat, s_lng in db.execute(
        select(models.TouristSpot.spot_id, models.TouristSpot.lat, models.TouristSpot.lng)
    ):
        if s_lat is None or s_lng is None:
            continue
        km = haversine_km(lat, lng, s_lat, s_lng)
        if km <= best_km:
            best_id, best_km = sid, km
    return best_id


def _upsert_ref(db: Session, source: str, key: str, spot_id: int, method: str) -> None:
    if db.scalar(select(models.SpotExternalRef).where(
            models.SpotExternalRef.source == source,
            models.SpotExternalRef.ext_key == key)):
        return
    db.add(models.SpotExternalRef(source=source, ext_key=key, spot_id=spot_id,
                                  method=method, confidence=1.0 if method == "name" else 0.7))
    db.commit()


def resolve_spot(db: Session, source: str, key: str, *,
                 lat: float | None = None, lng: float | None = None) -> int | None:
    """① ref(source, 정규화 key) ② 스팟명 정규화 완전일치 ③ 좌표 최근접. 해결 시 ref upsert."""
    norm = normalize_name(key)
    if not norm:
        return None
    ref = db.scalar(select(models.SpotExternalRef.spot_id).where(
        models.SpotExternalRef.source == source, models.SpotExternalRef.ext_key == norm))
    if ref:
        return ref
    for sid, name in db.execute(select(models.TouristSpot.spot_id, models.TouristSpot.name)):
        if normalize_name(name) == norm:
            _upsert_ref(db, source, norm, sid, "name")
            return sid
    if lat is not None and lng is not None:
        sid = nearest_spot(db, lat, lng)
        if sid:
            _upsert_ref(db, source, norm, sid, "coord")
            return sid
    return None
