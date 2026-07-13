"""통합 일배치 — `python -m app.batch.daily` (기획서 10-1: GitHub Actions cron 등에서 실행).

① 공사 OpenAPI 수집(키 등록된 것만, 나머지는 skipped 기록)
② 피드백 보정 계수 산출(9-4)
③ spot_score_daily 재계산(오늘~+30일 × 3시간대) — 조회 시 추가 연산 제거

모든 수집 결과는 api_ingest_log에 남겨 구동 안정성 근거(15-1)로 쓴다.
"""
from datetime import date, timedelta

from sqlalchemy import delete, func, select
from sqlalchemy.orm import Session

from app import models
from app.database import Base, SessionLocal, engine
from app.external import datalab_api, demand_api, related_api, tats_api, tour_api
from app.external.base import ExternalApiError
from app.matching import resolve_spot
from app.scoring.feedback_adjust import adjusted_risk
from app.scoring.weights import load_weights
from app.seed_data import SLOT_FACTOR, TIME_SLOTS
from app.services.congestion_service import compute_raw_risk, feedback_bias

WINDOW_DAYS = 30
# 관광지, 문화시설, 행사·축제, 레포츠, 쇼핑, 음식점 — 자유여행 슬롯(여행지/미식/포토스팟)
# 전 카테고리를 커버하도록 확대. 페이지네이션 수집으로 타입당 최대 4천 건.
TOURAPI_CONTENT_TYPES = (12, 14, 15, 28, 38, 39)
TOURAPI_CATEGORY_META = {
    "A01": ("자연", ["자연", "포토스팟"], False),
    "A02": ("역사·문화", ["역사", "포토스팟"], False),
    "A03": ("레포츠", ["자연", "포토스팟"], False),
    "A04": ("시장·골목", ["미식", "포토스팟"], True),
    "A05": ("미식", ["미식"], True),
}
# cat1이 비어 있을 때 콘텐츠 타입으로 보정하는 폴백 메타
CONTENT_TYPE_META = {
    12: ("관광지", ["자연"], False),
    14: ("문화시설", ["역사"], True),
    15: ("행사·축제", ["포토스팟"], False),
    28: ("레포츠", ["자연"], False),
    38: ("쇼핑", ["미식"], True),
    39: ("미식", ["미식"], True),
}


def log_ingest(db: Session, api_name: str, status: str,
               records: int = 0, error: str | None = None) -> None:
    db.add(models.ApiIngestLog(api_name=api_name, status=status,
                               records=records, error_message=error))
    db.commit()


def _first_value(item: dict, *candidates: str) -> str | None:
    """후보 키 우선, 없으면 None — 승인 직후 필드명 확인용."""
    for key in candidates:
        if key in item and item[key] not in (None, ""):
            return str(item[key])
    return None


def _first_numeric(item: dict) -> float | None:
    """이름/코드/날짜성 키를 제외한 첫 숫자 컬럼 — 필드명 미확정 API의 지표 추출."""
    skip = ("ymd", "ym", "cd", "nm", "code", "name", "rank", "no")
    for key, value in item.items():
        if any(s in key.lower() for s in skip):
            continue
        try:
            return float(value)
        except (TypeError, ValueError):
            continue
    return None


def _mapping_failure(api_label: str, items: list[dict]) -> ExternalApiError:
    """수신은 됐지만 필드 매핑이 0건일 때 — 실제 응답 키를 로그로 남겨 즉시 교정 가능하게."""
    keys = sorted(items[0].keys()) if items else []
    return ExternalApiError(f"{api_label}: 필드 매핑 실패 — 응답 키 {keys}")


def sync_spots(db: Session) -> int | None:
    """TourAPI 관광지·문화시설·음식점 목록 → tourist_spot 업서트."""
    client = tour_api.get_client()
    if not client.enabled:
        return None
    count = 0
    for content_type_id in TOURAPI_CONTENT_TYPES:
        items = client.area_based_list(area_code=1, content_type_id=content_type_id) or []
        for it in items:
            content_id = str(it.get("contentid", ""))
            if not content_id or not it.get("mapy"):
                continue
            spot = db.scalar(select(models.TouristSpot)
                             .where(models.TouristSpot.content_id == content_id))
            if not spot:
                # 시드 스팟과 이름이 같으면 병합(중복 생성 방지) — 큐레이션 필드(태그·개요·
                # base_popularity)는 유지하고 실데이터(좌표·카테고리·이미지)만 덮어쓴다
                spot = db.scalar(select(models.TouristSpot).where(
                    models.TouristSpot.name == it.get("title", ""),
                    models.TouristSpot.content_id.like("seed-%"),
                ))
                if spot:
                    spot.content_id = content_id
            if not spot:
                spot = models.TouristSpot(content_id=content_id, lat=0, lng=0, name="")
                db.add(spot)
            spot.name = it.get("title", spot.name)
            spot.addr = it.get("addr1") or spot.addr
            spot.lat = float(it.get("mapy", spot.lat))
            spot.lng = float(it.get("mapx", spot.lng))
            spot.cat1, spot.cat2, spot.cat3 = it.get("cat1"), it.get("cat2"), it.get("cat3")
            spot.area_code = int(it.get("areacode", 1) or 1)
            spot.sigungu_code = int(it["sigungucode"]) if it.get("sigungucode") else None
            category_name, default_tags, is_indoor = (
                TOURAPI_CATEGORY_META.get(spot.cat1)
                or CONTENT_TYPE_META.get(content_type_id, ("관광지", [], False))
            )
            if spot.category_name == "관광지":
                spot.category_name = category_name
            if not spot.tags:
                spot.tags = default_tags
            spot.is_indoor = spot.is_indoor or is_indoor
            if it.get("firstimage"):
                spot.image_url = it["firstimage"]
                spot.image_count = max(spot.image_count or 0, 1)
            count += 1
    db.commit()
    return count


def sync_concentration(db: Session) -> int | None:
    """집중률 예측(향후 30일) → congestion_snapshot. 일 단위 예측을 시간대 계수로 분배.

    실측 스펙: areaCd=11(법정동 서울) + signguCd 필수, 구당 3천여 건 → 시군구 순회.
    응답 필드: baseYmd, tAtsNm, cnctrRate (2026-07 실응답 확인).
    """
    client = tats_api.get_client()
    if not client.enabled:
        return None

    existing = {
        (s.spot_id, s.date, s.time_slot): s
        for s in db.scalars(select(models.CongestionSnapshot)).all()
    }
    count, sample = 0, []
    received, matched = 0, 0
    for signgu in SEOUL_SIGNGU_CODES:
        items = client.concentration_forecast("11", signgu) or []
        sample = sample or items
        for it in items:
            name = _first_value(it, "tAtsNm", "tatsNm")
            raw_date = _first_value(it, "baseYmd", "predictYmd") or ""
            rate = _first_value(it, "cnctrRate", "cnctrRt")
            if rate is None:
                rate = _first_numeric(it)
            if not name or len(raw_date) != 8 or rate is None:
                continue
            received += 1
            sid = resolve_spot(db, "tats", name)   # 이름 변형 흡수(ref→정규화 일치)
            if not sid:
                continue
            matched += 1
            d = date(int(raw_date[:4]), int(raw_date[4:6]), int(raw_date[6:]))
            for slot in TIME_SLOTS:
                score = round(min(float(rate) * SLOT_FACTOR[slot], 100.0), 1)
                snap = existing.get((sid, d, slot))
                if snap:
                    snap.congestion_score, snap.source = score, "tats"
                else:
                    snap = models.CongestionSnapshot(
                        spot_id=sid, date=d, time_slot=slot,
                        congestion_score=score, source="tats")
                    db.add(snap)
                    existing[(sid, d, slot)] = snap
                count += 1
    if sample and count == 0:
        raise _mapping_failure("집중률 예측(스팟명 매칭 0건)", sample)
    db.commit()
    # 매칭율 관측 — 수집 실패가 아니라 성공 로그의 메모로 남긴다
    log_ingest(db, "집중률 예측(매칭율)", "success", records=matched,
               error=f"matched {matched}/{received}")
    return count


def _weekday_visitor_index(items: list[dict]) -> dict[int, float]:
    """방문자 행들 → 요일별 평균 → peak=100 상대지수(방문자 구분별 행은 일자 합산)."""
    per_date: dict[date, float] = {}
    for it in items:
        raw_date = _first_value(it, "baseYmd", "statsYmd") or ""
        cnt = _first_value(it, "touNum", "visitrNum", "touristNum")
        if cnt is None:
            cnt = _first_numeric(it)
        if len(raw_date) != 8 or cnt is None:
            continue
        d = date(int(raw_date[:4]), int(raw_date[4:6]), int(raw_date[6:]))
        per_date[d] = per_date.get(d, 0.0) + float(cnt)
    if not per_date:
        return {}
    by_weekday: dict[int, list[float]] = {}
    for d, total in per_date.items():
        by_weekday.setdefault(d.weekday(), []).append(total)
    avg = {wd: sum(v) / len(v) for wd, v in by_weekday.items()}
    peak = max(avg.values()) or 1.0
    return {wd: round(v / peak * 100, 1) for wd, v in avg.items()}


def _upsert_region_stat(db: Session, d: date, sigungu_code: int | None,
                        visitor_index: float | None = None,
                        demand_intensity: float | None = None,
                        source: str = "datalab") -> None:
    """(area=1, sigungu, date) 행 upsert — sigungu NULL은 서울 전체 폴백 행."""
    sigungu_cond = (models.RegionStatDaily.sigungu_code == sigungu_code
                    if sigungu_code is not None
                    else models.RegionStatDaily.sigungu_code.is_(None))
    row = db.scalar(select(models.RegionStatDaily).where(
        models.RegionStatDaily.area_code == 1, sigungu_cond,
        models.RegionStatDaily.date == d))
    if row:
        if visitor_index is not None:
            row.visitor_index = visitor_index
        if demand_intensity is not None:
            row.demand_intensity = demand_intensity
        row.source = source
    else:
        db.add(models.RegionStatDaily(
            area_code=1, sigungu_code=sigungu_code, date=d,
            visitor_index=visitor_index, demand_intensity=demand_intensity,
            source=source))


def sync_visitors(db: Session) -> int | None:
    """빅데이터 방문자수(최근 실적) → 요일 패턴으로 향후 30일 상대지수 투영.

    ① 광역(서울 전체) → sigungu NULL 폴백 행  ② 기초지자체 → 시군구별 행(변별력).
    """
    client = datalab_api.get_client()
    if not client.enabled:
        return None
    today = date.today()
    # 집계가 약 한 달 지연되므로 과거 구간(42~7일 전)으로 요일 패턴을 학습한다
    start, end = today - timedelta(days=42), today - timedelta(days=7)
    items = client.metro_visitors(start, end) or []
    # 전 시도가 반환되므로 서울(11)만 필터
    metro_idx = _weekday_visitor_index(
        [it for it in items if str(it.get("areaCode", "")) == "11"])
    if items and not metro_idx:
        raise _mapping_failure("지역별 방문자수", items)

    # 시군구별 요일 패턴 — 시군구 자체 peak 기준 상대지수(구별 변별력)
    sigungu_idx: dict[int, dict[int, float]] = {}
    for signgu in SEOUL_SIGNGU_CODES:
        s_items = client.local_visitors(start, end, signgu) or []
        s_idx = _weekday_visitor_index(s_items)
        if s_idx:
            sigungu_idx[int(signgu)] = s_idx

    if not metro_idx and not sigungu_idx:
        return 0
    count = 0
    for offset in range(WINDOW_DAYS + 1):
        d = today + timedelta(days=offset)
        if d.weekday() in metro_idx:
            _upsert_region_stat(db, d, None, visitor_index=metro_idx[d.weekday()])
            count += 1
        for code, idx in sigungu_idx.items():
            if d.weekday() in idx:
                _upsert_region_stat(db, d, code, visitor_index=idx[d.weekday()])
                count += 1
    db.commit()
    return count


# 시드 스팟이 위치한 서울 시군구(법정동 코드) — 연관 API가 '시군구 기반' 조회라 순회한다
SEOUL_SIGNGU_CODES = ["11110", "11140", "11170", "11200", "11290", "11440", "11560"]


def sync_related(db: Session) -> int | None:
    """연관 관광지(월 집계·시군구 기반) → related_spot 캐시 갱신."""
    client = related_api.get_client()
    if not client.enabled:
        return None
    today = date.today()
    prev_month = (today.replace(day=1) - timedelta(days=1)).strftime("%Y%m")
    count, sample_items = 0, []
    received, matched = 0, 0
    # 당월 집계가 아직 없을 수 있어 당월 → 전월 순으로 시도
    for base_ym in (today.strftime("%Y%m"), prev_month):
        for signgu in SEOUL_SIGNGU_CODES:
            items = client.related_spots(base_ym=base_ym, area_code="11",
                                         signgu_code=signgu) or []
            sample_items = sample_items or items
            for it in items:
                base_name = _first_value(it, "tAtsNm", "baseTatsNm", "tatsNm")
                rel_name = _first_value(it, "rlteTatsNm", "rlteAtsNm")
                rank = _first_value(it, "rlteRank", "rank")
                if not base_name or not rel_name:
                    continue
                received += 1
                # 응답이 좌표를 주면 폴백에 활용(현재 스펙상 없을 수 있음 → 이름만)
                try:
                    b_lat = float(_first_value(it, "mapY", "latitude") or "")
                    b_lng = float(_first_value(it, "mapX", "longitude") or "")
                except ValueError:
                    b_lat = b_lng = None
                base_id = resolve_spot(db, "related", base_name, lat=b_lat, lng=b_lng)
                rel_id = resolve_spot(db, "related", rel_name)
                if not base_id or not rel_id:
                    continue
                matched += 1
                sim = round(max(1.0 - (int(float(rank or 10)) - 1) * 0.08, 0.2), 2)
                edge = db.scalar(select(models.RelatedSpot).where(
                    models.RelatedSpot.spot_id == base_id,
                    models.RelatedSpot.related_spot_id == rel_id))
                if edge:
                    edge.similarity_score = sim
                else:
                    db.add(models.RelatedSpot(spot_id=base_id,
                                              related_spot_id=rel_id,
                                              similarity_score=sim))
                count += 1
        if count:
            break
    if sample_items and count == 0:
        # 스팟명 불일치는 정상일 수 있어 실패 대신 0건 성공 처리하되 키를 로그에 남긴다
        raise _mapping_failure("연관 관광지(스팟명 매칭 0건 — DB 스팟명과 대조 필요)", sample_items)
    db.commit()
    # 매칭율 관측 — 수집 실패가 아니라 성공 로그의 메모로 남긴다
    log_ingest(db, "연관 관광지(매칭율)", "success", records=matched,
               error=f"matched {matched}/{received}")
    return count


def _recent_months(count: int = 6) -> list[str]:
    """이번 달부터 과거로 YYYYMM 목록 — 집계 지연 대응."""
    months, cursor = [], date.today().replace(day=1)
    for _ in range(count):
        months.append(cursor.strftime("%Y%m"))
        cursor = (cursor - timedelta(days=1)).replace(day=1)
    return months


def sync_demand(db: Session) -> int | None:
    """지역별 관광 수요 강도(체류 강도 주지표, 소비 강도 보조) → region_stat_daily.

    월 집계 지표(areaCd+baseYm 필수)라 최근 월부터 거슬러 조회하고,
    값을 정규화(최대=100)해 향후 30일에 투영한다.
    ※ 2026-07 기준 공급처 데이터 미적재(전 지역 0건) — 적재되면 자동 수집된다.
    """
    client = demand_api.get_client()
    if not client.enabled:
        return None
    today = date.today()

    items: list[dict] = []
    for base_ym in _recent_months():
        items = (client.stay_intensity(base_ym) or
                 client.expenditure_intensity(base_ym) or [])
        if items:
            break
    if not items:
        return 0    # 서비스 정상 응답·데이터 미적재 — 산식은 재정규화로 흡수

    # 시군구 필드가 있으면 시군구별 분해, 없으면 서울 전체 폴백(현행) — 스펙 §6.3
    by_sigungu: dict[int | None, list[float]] = {}
    for it in items:
        v = _first_numeric(it)
        if v is None:
            continue
        raw_sg = _first_value(it, "signguCd", "sigunguCd")
        try:
            sg = int(raw_sg) if raw_sg else None
        except ValueError:
            sg = None
        by_sigungu.setdefault(sg, []).append(v)
    values = [v for vs in by_sigungu.values() for v in vs]
    if not values:
        raise _mapping_failure("관광 수요 강도(체류/소비)", items)
    peak = max(values) or 1.0
    intensity_by_sg = {
        sg: round(sum(vs) / len(vs) / peak * 100, 1) for sg, vs in by_sigungu.items()
    }
    # 시군구 행만 있으면 전체 평균을 폴백 행으로도 유지(조회 폴백 경로 보장)
    if None not in intensity_by_sg:
        intensity_by_sg[None] = round(sum(values) / len(values) / peak * 100, 1)

    count = 0
    for offset in range(WINDOW_DAYS + 1):
        d = today + timedelta(days=offset)
        for sg, intensity in intensity_by_sg.items():
            _upsert_region_stat(db, d, sg, demand_intensity=intensity, source="demand")
            count += 1
    db.commit()
    return count


# detailIntro2 필드명은 콘텐츠 타입별로 다르다(스펙 §7.1) — (usetime, restdate, parking) 후보
INTRO_FIELD_MAP = {
    12: (("usetime",), ("restdate",), ("parking",)),
    14: (("usetimeculture",), ("restdateculture",), ("parkingculture",)),
    15: (("playtime",), (), ("parkingplace",)),
    28: (("usetimeleports",), ("restdateleports",), ("parkingleports",)),
    38: (("opentime",), ("restdateshopping",), ("parkingshopping",)),
    39: (("opentimefood",), ("restdatefood",), ("parkingfood",)),
}


def intro_fields(item: dict, content_type_id: int) -> tuple[str | None, str | None, str | None]:
    """detailIntro2 한 행 → (use_time, rest_date, parking). 미지 타입은 관광지(12) 기준."""
    use_keys, rest_keys, park_keys = INTRO_FIELD_MAP.get(
        content_type_id, INTRO_FIELD_MAP[12])
    return (
        _first_value(item, *use_keys) if use_keys else None,
        _first_value(item, *rest_keys) if rest_keys else None,
        _first_value(item, *park_keys) if park_keys else None,
    )


ENRICH_BUDGET = 150   # 일일 보강 스팟 수(detailCommon2+detailImage2 = 스팟당 2콜, 쿼터 1000/일 보호)


def enrich_spot_content(db: Session) -> int | None:
    """스팟 콘텐츠 보강 — detailCommon2(개요) + detailImage2(실측 이미지 수).

    개요 길이·이미지 수는 콘텐츠 풍부도(숨은 명소성 9-2)의 실데이터 항이다.
    미보강 스팟이 사실상 풍부도 0으로 깔려 추천 폭이 좁아지는 것을 막기 위해
    ① KT 혼잡 실측 보유 스팟 → ② 이미지 보유 스팟 순으로 매일 ENRICH_BUDGET건씩 채운다.
    """
    client = tour_api.get_client()
    if not client.enabled:
        return None
    has_snapshot = models.TouristSpot.spot_id.in_(
        select(models.CongestionSnapshot.spot_id).distinct()
    )
    targets = db.scalars(
        select(models.TouristSpot)
        .where(
            models.TouristSpot.overview.is_(None),
            ~models.TouristSpot.content_id.like("seed-%"),
        )
        .order_by(has_snapshot.desc(),
                  models.TouristSpot.image_url.is_not(None).desc(),
                  models.TouristSpot.spot_id.asc())
        .limit(ENRICH_BUDGET)
    ).all()
    count = 0
    for spot in targets:
        rows = client.detail_common(spot.content_id) or []
        if rows:
            it = rows[0]
            overview = (it.get("overview") or "").strip()
            if overview:
                spot.overview = overview
                spot.overview_len = len(overview)
            if it.get("firstimage") and not spot.image_url:
                spot.image_url = it["firstimage"]
        images = client.detail_images(spot.content_id) or []
        spot.image_count = max(spot.image_count or 0, len(images),
                               1 if spot.image_url else 0)
        if spot.overview is None:
            # 개요가 없는 스팟도 재조회 대상에서 빠지도록 빈 문자열로 마킹
            spot.overview = ""
            spot.overview_len = 0
        count += 1
    db.commit()
    return count


def refresh_popularity(db: Session) -> int:
    """방문 규모 프록시(base_popularity)를 실측 집중률 평균으로 갱신.

    숨은 명소성의 '방문자수 하위 분위'와 휴리스틱 폴백이 큐레이션 추정값 대신
    KT 실데이터 기반이 된다(9-2 프록시의 실데이터화).
    """
    rows = db.execute(
        select(
            models.CongestionSnapshot.spot_id,
            func.avg(models.CongestionSnapshot.congestion_score),
        )
        .where(models.CongestionSnapshot.source == "tats")
        .group_by(models.CongestionSnapshot.spot_id)
    ).all()
    for spot_id, avg_score in rows:
        spot = db.get(models.TouristSpot, spot_id)
        if spot and avg_score is not None:
            spot.base_popularity = round(float(avg_score), 1)
    db.commit()
    return len(rows)


INGEST_JOBS = [
    ("TourAPI 국문관광정보(KorService2)", sync_spots),
    ("관광지 집중률 예측(TatsCnctrRate)", sync_concentration),
    ("TourAPI 상세 보강(detailCommon2)", enrich_spot_content),
    ("빅데이터 지역별 방문자수(DataLab)", sync_visitors),
    ("연관 관광지(TarRlteTar)", sync_related),
    ("지역별 관광 수요 강도", sync_demand),
]


def ingest_all(db: Session) -> dict[str, str]:
    results = {}
    for api_name, job in INGEST_JOBS:
        try:
            records = job(db)
        except ExternalApiError as e:
            db.rollback()
            log_ingest(db, api_name, "failed", error=str(e))
            results[api_name] = "failed"
            continue
        if records is None:
            log_ingest(db, api_name, "skipped",
                       error="API 키 미등록(데모 모드) — 시드 스냅샷으로 동작")
            results[api_name] = "skipped"
        else:
            log_ingest(db, api_name, "success", records=records)
            results[api_name] = f"success({records})"
    return results


def recompute_scores(db: Session) -> int:
    """② 피드백 보정 + ③ 점수 캐시 재계산 — 오늘~+30일 × 3시간대 전 스팟."""
    fw = load_weights()["feedback"]
    today = date.today()
    db.execute(delete(models.SpotScoreDaily))
    # 혼잡 스냅샷이 있는 스팟만 캐시 산출 — 근거 없는 휴리스틱 값이 'cache'로 둔갑하지 않게
    eligible = set(db.scalars(
        select(models.CongestionSnapshot.spot_id).distinct()).all())
    spots = [s for s in db.scalars(select(models.TouristSpot)).all()
             if s.spot_id in eligible]
    count = 0
    for spot in spots:
        bias, applied = feedback_bias(db, spot.spot_id)
        for offset in range(WINDOW_DAYS + 1):
            d = today + timedelta(days=offset)
            for slot in TIME_SLOTS:
                # 날씨 항은 배치에서만 반영(격자 단위 캐시라 호출 수 적음) —
                # 요청 경로는 이 캐시를 읽기만 하므로 기상청 지연이 사용자에게 안 간다
                raw, _source = compute_raw_risk(
                    db, spot, d, slot, use_realtime=False, use_weather=True
                )
                db.add(models.SpotScoreDaily(
                    spot_id=spot.spot_id, date=d, time_slot=slot,
                    congestion_risk=raw,
                    adjusted_risk=adjusted_risk(raw, bias, fw["alpha"]) if applied else None,
                ))
                count += 1
    db.commit()
    return count


def run() -> None:
    Base.metadata.create_all(engine)
    with SessionLocal() as db:
        print("① 공사 OpenAPI 수집")
        for api_name, result in ingest_all(db).items():
            print(f"   - {api_name}: {result}")
        print("② 방문 규모 프록시를 실측 집중률 평균으로 갱신")
        updated = refresh_popularity(db)
        print(f"   - base_popularity {updated}건 갱신(숨은 명소성 실데이터화)")
        print("③ 피드백 보정 + ④ 점수 캐시 재계산")
        count = recompute_scores(db)
        log_ingest(db, "daily_batch(score_recompute)", "success", records=count)
        print(f"   - spot_score_daily {count}건 갱신 완료")


if __name__ == "__main__":
    run()
