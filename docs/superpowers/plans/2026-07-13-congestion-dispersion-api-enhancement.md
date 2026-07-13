# 혼잡·분산·API 활용 보완 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 서울 실시간 데이터를 깊게 활용하고 스팟 변별력·매칭 신뢰도·오버투어리즘 지표·분산 측정을 더해, "널널도"가 실제 혼잡 분산을 일으킬 수 있는 수준으로 끌어올린다.

**Architecture:** 5개 워크스트림을 의존성 순(WS-B 매칭 → WS-A 서울 실시간 심화 ‖ WS-C 시군구 → WS-D 콘텐츠·기상 → WS-E 임팩트)으로 실행. 각 신규 신호는 결측 시 `renormalize`로 흡수해 기존 `congestion_risk` 산식·테스트를 건드리지 않는다(회귀 0).

**Tech Stack:** Python 3.14 · FastAPI · SQLAlchemy 2.0 · pytest · httpx / FE: React + Vite. DB: SQLite(고정), 마이그레이션은 `main.apply_column_migrations()` + 신규 `apply_index_migrations()`.

**설계 근거:** `docs/superpowers/specs/2026-07-13-congestion-dispersion-api-enhancement-design.md`

## Global Constraints

- **회귀 0**: `weights.yaml`의 `congestion_risk` 가중치 불변. 신규 신호는 결측 시 `renormalize`로 흡수. 기존 테스트 전량 통과 유지.
- **쿼터 1000/일**: 요청 경로 서울 실시간 HTTP는 원 관광지 + 대안 top-N(≤5)만. 배치에 서울 실시간 안 넣음.
- **demo/오프라인 폴백**: `DEMO_MODE=true`거나 키 없으면 실시간 None → 시드/휴리스틱으로 동작(심사 오프라인 대비).
- **정직성**: 실시간이 아닌 소스일 때 오버투어리즘/실시간 인원 필드는 전부 None.
- **마이그레이션 순서**: `create_all` → `apply_column_migrations` → `apply_index_migrations` → seed.
- **커밋 규칙**: 각 태스크 끝에서 커밋. 기능 브랜치에서 작업(현재 `main` 아님).

---

## Phase B — 스팟 식별·매칭 신뢰화 (WS-B, 선행)

### Task B1: 이름 정규화 `normalize_name`

**Files:**
- Create: `app/matching.py`
- Test: `tests/test_matching.py`

**Interfaces:**
- Produces: `normalize_name(name: str) -> str`

- [ ] **Step 1: 실패 테스트 작성** — `tests/test_matching.py`

```python
from app.matching import normalize_name


def test_normalize_name_strips_parens_space_punct():
    assert normalize_name("경복궁(사적 제117호)") == "경복궁"
    assert normalize_name(" 북촌 한옥마을 ") == "북촌한옥마을"
    assert normalize_name("N서울타워") == "n서울타워"
    assert normalize_name("덕수궁길·정동길") == "덕수궁길정동길"
    assert normalize_name("") == ""
```

- [ ] **Step 2: 실패 확인** — `pytest tests/test_matching.py::test_normalize_name_strips_parens_space_punct -v` → FAIL(ModuleNotFoundError).

- [ ] **Step 3: 구현** — `app/matching.py`

```python
"""외부 API 식별자 ↔ 스팟 매칭(집중률 tAtsNm / 서울 area명 / 연관 이름)."""
import re

_PARENS = re.compile(r"[(\[（【].*?[)\]）】]")
_NONWORD = re.compile(r"[\s·,\-_/.]+")


def normalize_name(name: str) -> str:
    """괄호 내용 제거 → 공백·구두점 제거 → 소문자화. '경복궁(사적)' → '경복궁'."""
    if not name:
        return ""
    text = _PARENS.sub("", name)
    text = _NONWORD.sub("", text)
    return text.strip().lower()
```

- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS.

- [ ] **Step 5: 커밋** — `git add app/matching.py tests/test_matching.py && git commit -m "feat(matching): 외부 식별자용 이름 정규화 추가"`

---

### Task B2: `SpotExternalRef` 모델

**Files:**
- Modify: `app/models.py` (신규 클래스 추가)
- Test: `tests/test_matching.py`

**Interfaces:**
- Produces: `models.SpotExternalRef(source, ext_key, spot_id, confidence, method)`, UniqueConstraint `uq_ext_ref(source, ext_key)`

- [ ] **Step 1: 실패 테스트 추가** — `tests/test_matching.py`

```python
def test_spot_external_ref_roundtrip(db, gyeongbok_id):
    from app import models
    db.add(models.SpotExternalRef(source="seoul", ext_key="경복궁", spot_id=gyeongbok_id))
    db.commit()
    row = db.query(models.SpotExternalRef).filter_by(source="seoul", ext_key="경복궁").one()
    assert row.spot_id == gyeongbok_id
    assert row.method == "seed"
```

- [ ] **Step 2: 실패 확인** — `pytest tests/test_matching.py::test_spot_external_ref_roundtrip -v` → FAIL(AttributeError: SpotExternalRef).

- [ ] **Step 3: 구현** — `app/models.py`에 추가(파일 상단 import에 이미 `UniqueConstraint` 있음):

```python
class SpotExternalRef(Base):
    """외부 API 식별자 ↔ 스팟 매핑(집중률 tAtsNm / 서울 area명 / 연관 이름)."""

    __tablename__ = "spot_external_ref"
    __table_args__ = (UniqueConstraint("source", "ext_key", name="uq_ext_ref"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    source: Mapped[str] = mapped_column(String(20), index=True)     # seoul|tats|related
    ext_key: Mapped[str] = mapped_column(String(120), index=True)   # 정규화 키
    spot_id: Mapped[int] = mapped_column(ForeignKey("tourist_spot.spot_id"), index=True)
    confidence: Mapped[float] = mapped_column(Float, default=1.0)
    method: Mapped[str] = mapped_column(String(10), default="seed")  # seed|name|coord
```

- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS(`create_all`이 테이블 생성). 전체 `pytest -q`도 회귀 없는지 확인.

- [ ] **Step 5: 커밋** — `git add app/models.py tests/test_matching.py && git commit -m "feat(models): SpotExternalRef 매핑 테이블 추가"`

---

### Task B3: `nearest_spot` + `resolve_spot`

**Files:**
- Modify: `app/matching.py`
- Test: `tests/test_matching.py`

**Interfaces:**
- Consumes: `normalize_name`, `models.SpotExternalRef`, `models.TouristSpot`, `app.geo.haversine_km`
- Produces:
  - `nearest_spot(db, lat: float, lng: float, max_km: float = 0.3) -> int | None`
  - `resolve_spot(db, source: str, key: str, *, lat: float|None = None, lng: float|None = None) -> int | None`

- [ ] **Step 1: 실패 테스트 추가** — `tests/test_matching.py`

```python
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


def test_resolve_spot_coord_fallback_and_miss(db, gyeongbok_id):
    from app import matching, models
    spot = db.get(models.TouristSpot, gyeongbok_id)
    # 이름은 안 맞지만 좌표가 스팟 근처 → 좌표 폴백
    assert matching.resolve_spot(db, "tats", "없는이름xyz",
                                 lat=spot.lat, lng=spot.lng) == gyeongbok_id
    # 이름·좌표 모두 실패 → None
    assert matching.resolve_spot(db, "tats", "없는이름xyz") is None
```

- [ ] **Step 2: 실패 확인** — `pytest tests/test_matching.py -k resolve_spot -v` → FAIL.

- [ ] **Step 3: 구현** — `app/matching.py`에 추가:

```python
from sqlalchemy import select
from sqlalchemy.orm import Session

from app import models
from app.geo import haversine_km


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
```
`ext_key`는 정규화 키로 저장(B2 시드도 정규화 키로 넣을 것 — Task B4에서 반영).

- [ ] **Step 4: 통과 확인** — `pytest tests/test_matching.py -v` → PASS.

- [ ] **Step 5: 커밋** — `git add app/matching.py tests/test_matching.py && git commit -m "feat(matching): ref·이름·좌표 폴백 resolve_spot 구현"`

---

### Task B4: 서울 area 매핑 시드 이관·확장

**Files:**
- Modify: `app/seed_data.py` (신규 `seed_external_refs`, 리셋 목록, `run` 호출)
- Modify: `app/external/seoul_api.py` (`SPOT_TO_AREA` → DB 조회 기반; Task A3에서 최종 정리하되 여기서 시드 데이터 확보)
- Test: `tests/test_matching.py`

**Interfaces:**
- Consumes: `normalize_name`, `models.SpotExternalRef`
- Produces: `seed_data.seed_external_refs(db, spots: dict[str, TouristSpot]) -> int`

- [ ] **Step 1: 실패 테스트 추가**

```python
def test_seed_external_refs_maps_seoul_areas(client, db):
    from app import models
    # 시드가 서울 area 매핑을 최소 10개 이상 넣는다(정규화 키로 저장)
    from app.matching import normalize_name
    count = db.query(models.SpotExternalRef).filter_by(source="seoul").count()
    assert count >= 10
    gy = db.query(models.SpotExternalRef).filter_by(
        source="seoul", ext_key=normalize_name("경복궁")).one()
    assert gy.spot_id
```

- [ ] **Step 2: 실패 확인** — `pytest tests/test_matching.py::test_seed_external_refs_maps_seoul_areas -v` → FAIL(0건).

- [ ] **Step 3: 구현** — `app/seed_data.py`에 추가하고 `run`에서 호출:

```python
# 서울 실시간도시데이터 지원 area명 ↔ 시드 스팟명(공식 목록 확보 시 확장, §11)
SEOUL_AREA_SEED = {
    "경복궁": "경복궁", "창덕궁·종묘": "창덕궁", "덕수궁길·정동길": "덕수궁",
    "북촌한옥마을": "북촌한옥마을", "명동 관광특구": "명동거리", "남산공원": "N서울타워",
    "홍대 관광특구": "홍대거리", "익선동": "익선동 골목", "혜화역": "낙산공원",
    "서울숲공원": "서울숲",
}


def seed_external_refs(db, spots) -> int:
    from app.matching import normalize_name
    count = 0
    for area_name, spot_name in SEOUL_AREA_SEED.items():
        spot = spots.get(spot_name)
        if not spot:
            continue
        key = normalize_name(area_name)
        if db.scalar(select(models.SpotExternalRef).where(
                models.SpotExternalRef.source == "seoul",
                models.SpotExternalRef.ext_key == key)):
            continue
        db.add(models.SpotExternalRef(source="seoul", ext_key=key,
                                      spot_id=spot.spot_id, method="seed"))
        count += 1
    db.commit()
    return count
```
- `run(db)`에서 `seed_spots` 반환 `spots`로 `seed_external_refs(db, spots)` 호출.
- 리셋 목록(파일 하단 delete 대상)에 `models.SpotExternalRef` 추가.

- [ ] **Step 4: 통과 확인** — 테스트 DB 재생성 후 `pytest tests/test_matching.py -v` → PASS.

- [ ] **Step 5: 커밋** — `git add app/seed_data.py tests/test_matching.py && git commit -m "feat(seed): 서울 area 매핑을 SpotExternalRef 시드로 이관"`

---

### Task B5: 배치 매칭을 resolve_spot으로 전환 + 매칭율 로깅

**Files:**
- Modify: `app/batch/daily.py` (`sync_concentration`, `sync_related`)
- Test: `tests/test_batch_matching.py` (신규)

**Interfaces:**
- Consumes: `matching.resolve_spot`

- [ ] **Step 1: 실패 테스트 작성** — `tests/test_batch_matching.py`

```python
"""배치가 스팟명 완전일치 대신 resolve_spot으로 매칭한다(이름 변형 흡수)."""
def test_resolve_spot_used_for_variant_names(db, gyeongbok_id):
    from app import matching
    # '경복궁 (조선)' 같은 변형도 경복궁으로 해결
    assert matching.resolve_spot(db, "tats", "경복궁 (조선)") == gyeongbok_id
```
(배치 실호출은 데모 모드에서 skip되므로, 매칭 유닛으로 계약을 고정한다.)

- [ ] **Step 2: 실패 확인** — 신규 파일이므로 먼저 실행 → 이미 B3 구현으로 PASS할 수 있음. PASS면 계약 회귀 가드로 유지, 다음 스텝은 배치 코드 수정.

- [ ] **Step 3: 구현** — `app/batch/daily.py`:
  - `sync_concentration`: `spot = spots_by_name.get(name or "")` → `sid = resolve_spot(db, "tats", name or ""); spot = db.get(models.TouristSpot, sid) if sid else None`.
  - `sync_related`: `_spot_by_name(db, base_name)` → `resolve_spot(db, "related", base_name, lat=..., lng=...)` (연관 응답이 좌표를 주면 전달, 없으면 이름만).
  - 각 함수 말미에 매칭율 관측: `received`/`matched` 카운트를 계산해 `log_ingest`의 `error_message`에 `f"matched {matched}/{received}"`로 성공 로그에 남긴다(수집 실패 아님).
  - 상단 import: `from app.matching import resolve_spot`.

- [ ] **Step 4: 통과 확인** — `pytest tests/test_batch_matching.py -v` PASS + `pytest -q` 전체 회귀 0.

- [ ] **Step 5: 커밋** — `git add app/batch/daily.py tests/test_batch_matching.py && git commit -m "feat(batch): 집중률·연관 수집을 resolve_spot 매칭으로 전환·매칭율 로깅"`

---

## Phase A — 서울 실시간 도시데이터 심화 (WS-A)

### Task A1: 서울 실시간 응답 필드 확장

**Files:**
- Modify: `app/external/seoul_api.py`
- Test: `tests/test_seoul_realtime.py` (신규)

**Interfaces:**
- Produces: `_parse_ppltn_row(row: dict) -> dict` (score/level_label/ppltn_min/ppltn_max/non_resident_rate/congest_msg/ppltn_time/forecast[])

- [ ] **Step 1: 실패 테스트 작성** — `tests/test_seoul_realtime.py`

```python
from app.external import seoul_api


SAMPLE_ROW = {
    "AREA_CONGEST_LVL": "붐빔", "AREA_CONGEST_MSG": "사람이 많아 붐빕니다",
    "AREA_PPLTN_MIN": "42000", "AREA_PPLTN_MAX": "44000",
    "NON_RESNT_PPLTN_RATE": "63.5", "PPLTN_TIME": "2026-07-13 15:00",
    "FCST_PPLTN": [
        {"FCST_TIME": "2026-07-13 16:00", "FCST_CONGEST_LVL": "약간 붐빔",
         "FCST_PPLTN_MIN": "30000", "FCST_PPLTN_MAX": "32000"},
    ],
}


def test_parse_ppltn_row_extracts_all_fields():
    out = seoul_api._parse_ppltn_row(SAMPLE_ROW)
    assert out["level_label"] == "붐빔"
    assert out["ppltn_min"] == 42000 and out["ppltn_max"] == 44000
    assert out["non_resident_rate"] == 63.5
    assert out["congest_msg"].startswith("사람이 많아")
    assert out["forecast"][0]["hour"] == "16"
    assert out["forecast"][0]["ppltn_max"] == 32000
```

- [ ] **Step 2: 실패 확인** — `pytest tests/test_seoul_realtime.py::test_parse_ppltn_row_extracts_all_fields -v` → FAIL.

- [ ] **Step 3: 구현** — `seoul_api.py`에 `_int/_float` 헬퍼 + `_parse_ppltn_row`. `get_realtime_congestion`이 `_parse_ppltn_row(row)`를 호출하도록 리팩터. (refined score는 A2에서 주입 — 이 단계는 원 label score 유지.)

```python
def _num(v, cast):
    try:
        return cast(v)
    except (TypeError, ValueError):
        return None


def _parse_ppltn_row(row: dict) -> dict:
    label = row.get("AREA_CONGEST_LVL")
    forecast = []
    for f in row.get("FCST_PPLTN", []) or []:
        forecast.append({
            "hour": _fcst_hour(str(f.get("FCST_TIME", ""))),
            "level_label": f.get("FCST_CONGEST_LVL"),
            "score": CONGEST_LEVEL_SCORE.get(f.get("FCST_CONGEST_LVL"), 45.0),
            "ppltn_min": _num(f.get("FCST_PPLTN_MIN"), int),
            "ppltn_max": _num(f.get("FCST_PPLTN_MAX"), int),
        })
    return {
        "score": CONGEST_LEVEL_SCORE.get(label, 45.0),
        "level_label": label,
        "ppltn_min": _num(row.get("AREA_PPLTN_MIN"), int),
        "ppltn_max": _num(row.get("AREA_PPLTN_MAX"), int),
        "non_resident_rate": _num(row.get("NON_RESNT_PPLTN_RATE"), float),
        "congest_msg": row.get("AREA_CONGEST_MSG"),
        "ppltn_time": row.get("PPLTN_TIME"),
        "forecast": forecast,
    }
```

- [ ] **Step 4: 통과 확인** — PASS.

- [ ] **Step 5: 커밋** — `git add app/external/seoul_api.py tests/test_seoul_realtime.py && git commit -m "feat(seoul): 실시간 인원·비상주율·예측인원·혼잡메시지 파싱"`

---

### Task A2: 연속 스코어 `refined_score` (Fork1=B)

**Files:**
- Modify: `app/external/seoul_api.py`
- Test: `tests/test_seoul_realtime.py`

**Interfaces:**
- Produces: `refined_score(level_label, ppltn_mid, area_lo, area_hi) -> float`; `_parse_ppltn_row`가 score/forecast[].score를 refined로 채움.

- [ ] **Step 1: 실패 테스트 추가**

```python
def test_refined_score_monotonic_and_clamped():
    s = seoul_api.refined_score
    center = 90.0  # '붐빔'
    lo, hi = 30000, 44000
    assert s("붐빔", 30000, lo, hi) < s("붐빔", 44000, lo, hi)     # 단조
    assert 0.0 <= s("붐빔", 44000, lo, hi) <= 100.0                # 클램프
    assert s("붐빔", None, lo, hi) == center                       # 인원 결측 → 라벨(회귀 0)
    assert s("붐빔", 40000, 40000, 40000) == center                # 범위 0 → 라벨


def test_parse_row_scores_are_refined():
    out = seoul_api._parse_ppltn_row(SAMPLE_ROW)
    # 현재('붐빔', mid=43000)가 당일 범위 상단이므로 밴드 중앙 90 이상
    assert out["score"] >= 90.0
```

- [ ] **Step 2: 실패 확인** — `pytest tests/test_seoul_realtime.py -k refined -v` → FAIL.

- [ ] **Step 3: 구현** — `seoul_api.py`:

```python
BAND_CENTER = {"여유": 20.0, "보통": 45.0, "약간 붐빔": 70.0, "붐빔": 90.0}
BAND_HALFWIDTH = 12.5


def refined_score(level_label, ppltn_mid, area_lo, area_hi) -> float:
    center = BAND_CENTER.get(level_label, 45.0)
    if ppltn_mid is None or area_hi <= area_lo:
        return center
    pos = (ppltn_mid - area_lo) / (area_hi - area_lo)
    return round(min(max(center + (pos - 0.5) * 2 * BAND_HALFWIDTH, 0.0), 100.0), 1)
```
`_parse_ppltn_row` 말미에서 당일 범위 산출 후 refined 주입:

```python
    mids = [ (e["ppltn_min"]+e["ppltn_max"])/2
             for e in forecast if e["ppltn_min"] and e["ppltn_max"] ]
    cur_mid = ((row_min + row_max)/2) if (row_min and row_max) else None  # row_min/max = AREA_PPLTN_*
    all_mids = [m for m in ([cur_mid] + mids) if m is not None]
    lo, hi = (min(all_mids), max(all_mids)) if all_mids else (0, 0)
    result["score"] = refined_score(label, cur_mid, lo, hi)
    for e in forecast:
        e_mid = (e["ppltn_min"]+e["ppltn_max"])/2 if (e["ppltn_min"] and e["ppltn_max"]) else None
        e["score"] = refined_score(e["level_label"], e_mid, lo, hi)
```

- [ ] **Step 4: 통과 확인** — PASS. `pytest tests/test_congestion_api.py tests/test_realtime_slot.py -q`도 회귀 0(demo에서 realtime None).

- [ ] **Step 5: 커밋** — `git add app/external/seoul_api.py tests/test_seoul_realtime.py && git commit -m "feat(seoul): 라벨 밴드 내 실인원 보간(refined_score)"`

---

### Task A3: area_key 기반 조회 + 커버리지 확대

**Files:**
- Modify: `app/external/seoul_api.py` (`get_realtime_by_area`, 이름 래퍼)
- Modify: `app/services/congestion_service.py` (`seoul_area_key`, 호출부)
- Test: `tests/test_seoul_realtime.py`, `tests/test_realtime_slot.py`

**Interfaces:**
- Produces: `seoul_api.get_realtime_by_area(area_key: str) -> dict | None`; `congestion_service.seoul_area_key(db, spot) -> str | None`

- [ ] **Step 1: 실패 테스트 추가** — `tests/test_seoul_realtime.py`

```python
def test_seoul_area_key_resolves_from_ref(db, gyeongbok_id):
    from app.services import congestion_service as cs
    from app import models
    spot = db.get(models.TouristSpot, gyeongbok_id)
    assert cs.seoul_area_key(db, spot)  # 시드 ref로 area명 반환
```

- [ ] **Step 2: 실패 확인** → FAIL(AttributeError).

- [ ] **Step 3: 구현**
  - `congestion_service.seoul_area_key(db, spot)`: `SpotExternalRef(source='seoul', spot_id=spot.spot_id)`의 `ext_key`(정규화 area) 반환. 서울 실시간 API에는 원 area명이 필요하므로, 매핑 저장 시 원문 area명을 별도로 보관하거나 `get_realtime_by_area`가 정규화 키로 조회 가능하도록 서버측에서 area명 목록을 상수로 유지. **결정:** `SEOUL_AREA_SEED`의 원 area명을 `seoul_api.AREA_KEYS`(정규화→원문) 맵으로 노출하고, `get_realtime_by_area(normalized_key)`가 원문 area명으로 호출.
  - `get_realtime_by_area(area_key)`: 기존 `get_realtime_congestion` 본문을 area 인자 버전으로 재사용, `_parse_ppltn_row` 사용.
  - `get_realtime_congestion(spot_name)`는 `AREA_KEYS`로 정규화 조회 후 위임(하위호환).
  - `compute_raw_risk`의 실시간 분기: `area_key = seoul_area_key(db, spot); realtime = seoul_api.get_realtime_by_area(area_key) if area_key else None`.

- [ ] **Step 4: 통과 확인** — `pytest tests/test_seoul_realtime.py tests/test_realtime_slot.py tests/test_congestion_api.py -q` → PASS(demo에서 None 경로 유지).

- [ ] **Step 5: 커밋** — `git add -A && git commit -m "feat(seoul): area_key 기반 조회로 실시간 커버리지 확대"`

---

### Task A4: 오버투어리즘 지수 + 스키마 노출 (Fork2=B)

**Files:**
- Modify: `app/services/congestion_service.py` (`compute_risk` 결과 부가 + `get_congestion_view`)
- Modify: `app/schemas.py` (`CongestionResponse`)
- Test: `tests/test_overtourism.py` (신규)

**Interfaces:**
- Produces: `congestion_service.overtourism_fields(realtime: dict|None) -> dict`(tourist_share_pct/tourist_pressure/live_ppltn_min/live_ppltn_max/congest_msg)

- [ ] **Step 1: 실패 테스트 작성** — `tests/test_overtourism.py`

```python
from app.services import congestion_service as cs


def test_overtourism_fields_from_realtime():
    rt = {"non_resident_rate": 63.5, "ppltn_min": 42000, "ppltn_max": 44000,
          "congest_msg": "붐빕니다"}
    out = cs.overtourism_fields(rt)
    assert out["tourist_share_pct"] == 64
    assert out["tourist_pressure"] == "관광객 쏠림"
    assert out["live_ppltn_min"] == 42000 and out["live_ppltn_max"] == 44000


def test_overtourism_fields_none_when_not_realtime():
    out = cs.overtourism_fields(None)
    assert out["tourist_share_pct"] is None and out["tourist_pressure"] is None
```

- [ ] **Step 2: 실패 확인** → FAIL.

- [ ] **Step 3: 구현** — `congestion_service.py`:

```python
def overtourism_fields(realtime: dict | None) -> dict:
    if not realtime:
        return {"tourist_share_pct": None, "tourist_pressure": None,
                "live_ppltn_min": None, "live_ppltn_max": None, "congest_msg": None}
    rate = realtime.get("non_resident_rate")
    pressure = None
    if rate is not None:
        pressure = ("관광객 쏠림" if rate >= 60 else
                    "관광·현지 혼재" if rate >= 40 else "현지 생활")
    return {
        "tourist_share_pct": round(rate) if rate is not None else None,
        "tourist_pressure": pressure,
        "live_ppltn_min": realtime.get("ppltn_min"),
        "live_ppltn_max": realtime.get("ppltn_max"),
        "congest_msg": realtime.get("congest_msg"),
    }
```
- `compute_risk`의 today-realtime 분기에서 얻은 `realtime`을 반환 dict에 실어 `get_congestion_view`가 `overtourism_fields`를 최종 응답에 병합(비실시간이면 None 필드).
- `schemas.CongestionResponse`에 `tourist_share_pct/tourist_pressure/live_ppltn_min/live_ppltn_max/congest_msg`(전부 `| None = None`) 추가.

- [ ] **Step 4: 통과 확인** — `pytest tests/test_overtourism.py tests/test_congestion_api.py -q` PASS(기존 응답은 필드가 None으로 채워짐).

- [ ] **Step 5: 커밋** — `git add -A && git commit -m "feat(congestion): 오버투어리즘(관광객 쏠림) 지수·실시간 인원 응답 노출"`

---

### Task A5: 대안 top-N 실시간 blend (T2-2)

**Files:**
- Modify: `app/services/recommend_service.py` (`get_alternatives`)
- Test: `tests/test_alternatives_api.py` (회귀) + `tests/test_alt_realtime.py`(신규 유닛)

**Interfaces:**
- Consumes: `seoul_api.get_realtime_by_area`, `congestion_service.seoul_area_key`, `congestion_service._realtime_slot_score`

- [ ] **Step 1: 실패 테스트 작성** — `tests/test_alt_realtime.py`

```python
"""당일이고 area_key가 있으면 top 후보 risk가 실시간으로 교체된다(단위 계약)."""
def test_realtime_slot_score_blend_contract():
    from app.services.congestion_service import _realtime_slot_score, current_time_slot
    from datetime import datetime
    rt = {"score": 88.0, "forecast": [{"hour": "19", "score": 55.0}]}
    now = datetime(2026, 7, 13, 15, 0)  # 오후
    assert _realtime_slot_score(rt, current_time_slot(now), now=now) == 88.0
```

- [ ] **Step 2: 실패 확인** — 이미 Task1 구현으로 PASS 가능 → 계약 가드로 유지. 다음 스텝은 recommend_service 반영.

- [ ] **Step 3: 구현** — `get_alternatives`의 top 선정 직후(카카오 길찾기 블록과 같은 위치)에 §5.5 코드 삽입:
  - `if d == date.today():` 각 `it`에 대해 `area_key = seoul_area_key(db, it["spot"])`, `rt = seoul_api.get_realtime_by_area(area_key)`, 성공 시 `it["risk"] = _realtime_slot_score(rt, time_slot)` 후 `it["decrease_pct"]`·`relief` 재계산(origin_risk 기준).
  - import 추가: `from app.external import seoul_api`, `from app.services.congestion_service import seoul_area_key, _realtime_slot_score`.

- [ ] **Step 4: 통과 확인** — `pytest tests/test_alt_realtime.py tests/test_alternatives_api.py -q` PASS(demo에서 realtime None → 기존 값 유지).

- [ ] **Step 5: 커밋** — `git add -A && git commit -m "feat(alternatives): 당일 top 후보 서울 실시간 반영"`

---

### Task A6: FE — 쏠림 배지·실시간 인원·혼잡 메시지

**Files:**
- Modify: `nullnull-travel-webapp/src/main.jsx` (널널도 카드)

- [ ] **Step 1:** 널널도 헤드라인/`congestion-card`에 `congestionView.tourist_pressure`가 있으면 배지(`관광객 X%`), `live_ppltn_min~max`가 있으면 "실시간 체류 인원 약 M~N명", `congest_msg`가 있으면 tip 대체. 모두 optional 렌더(값 없으면 미표시).
- [ ] **Step 2:** `npx vite build` → 빌드 성공 확인.
- [ ] **Step 3: 커밋** — `git add nullnull-travel-webapp/src/main.jsx && git commit -m "feat(fe): 관광객 쏠림 배지·실시간 인원·혼잡 메시지 표시"`

---

## Phase C — 지역통계 시군구 단위화 (WS-C, WS-A와 병렬 가능)

### Task C1: RegionStatDaily 시군구 컬럼 + 인덱스 마이그레이션

**Files:**
- Modify: `app/models.py` (`RegionStatDaily`)
- Modify: `app/main.py` (`_COLUMN_MIGRATIONS`, 신규 `apply_index_migrations`, lifespan 순서)
- Test: `tests/test_region_sigungu.py` (신규)

**Interfaces:**
- Produces: `RegionStatDaily.sigungu_code`, UniqueConstraint `(area_code, sigungu_code, date)`; `main.apply_index_migrations()`

- [ ] **Step 1: 실패 테스트 작성** — `tests/test_region_sigungu.py`

```python
def test_region_stat_supports_sigungu_rows(db):
    from app import models
    from datetime import date
    d = date.today()
    db.add(models.RegionStatDaily(area_code=1, sigungu_code=11110, date=d, visitor_index=80))
    db.add(models.RegionStatDaily(area_code=1, sigungu_code=11140, date=d, visitor_index=40))
    db.commit()
    rows = db.query(models.RegionStatDaily).filter_by(area_code=1, date=d).all()
    assert {r.sigungu_code for r in rows} >= {11110, 11140}
```

- [ ] **Step 2: 실패 확인** → FAIL(TypeError: sigungu_code).

- [ ] **Step 3: 구현**
  - `models.RegionStatDaily`: `sigungu_code: Mapped[int | None] = mapped_column(Integer, index=True)`; `__table_args__`를 `(area_code, sigungu_code, date)` unique로.
  - `main._COLUMN_MIGRATIONS`에 `"region_stat_daily": {"sigungu_code": "INTEGER"}`.
  - `main.apply_index_migrations()`(스펙 §9.2 코드) 추가, lifespan에서 `apply_column_migrations()` 다음에 호출.

- [ ] **Step 4: 통과 확인** — 테스트 DB 재생성 후 PASS. `pytest -q` 전체 회귀 0.

- [ ] **Step 5: 커밋** — `git add app/models.py app/main.py tests/test_region_sigungu.py && git commit -m "feat(region): RegionStatDaily 시군구 차원·인덱스 마이그레이션"`

---

### Task C2: 시군구 우선 조회 `region_for` + 변별력

**Files:**
- Modify: `app/services/congestion_service.py` (`compute_raw_risk`, `bulk_risks`)
- Test: `tests/test_region_sigungu.py`

**Interfaces:**
- Produces: `congestion_service.region_for(db, area_code, sigungu_code, d) -> RegionStatDaily | None`(시군구 우선, area 폴백)

- [ ] **Step 1: 실패 테스트 추가**

```python
def test_two_sigungu_spots_get_different_region_signal(db):
    """같은 area·다른 sigungu의 방문지수가 risk를 실제로 다르게 만든다(변별력)."""
    from app.services import congestion_service as cs
    from datetime import date
    d = date.today()
    a = cs.region_for(db, 1, 11110, d)
    b = cs.region_for(db, 1, 11140, d)
    assert a and b and a.visitor_index != b.visitor_index
```
(전제: C1 테스트가 11110/11140 행을 넣음 — 같은 세션 fixture 순서 보장 위해 이 테스트 내에서 upsert.)

- [ ] **Step 2: 실패 확인** → FAIL(AttributeError: region_for).

- [ ] **Step 3: 구현** — `region_for`(시군구 행 → 없으면 area_code+sigungu NULL 폴백). `compute_raw_risk`·`bulk_risks`가 `spot.sigungu_code`로 `region_for` 사용하도록 교체(`bulk_risks`는 `(area, sigungu)` 맵 + area 폴백 맵).

- [ ] **Step 4: 통과 확인** — `pytest tests/test_region_sigungu.py tests/test_congestion_api.py -q` PASS.

- [ ] **Step 5: 커밋** — `git add -A && git commit -m "feat(congestion): region/demand를 시군구 우선 조회로 변별력 확보"`

---

### Task C3: 배치 시군구 수집

**Files:**
- Modify: `app/batch/daily.py` (`sync_visitors`, `sync_demand`)
- Test: `tests/test_batch_matching.py`(계약) — 데모에서 API skip이므로 유닛 계약 중심

- [ ] **Step 1:** `sync_visitors`에 `datalab_api.local_visitors(start, end, signgu)`를 `SEOUL_SIGNGU_CODES` 순회로 추가해 `(area_code=1, sigungu_code=signgu, date)` 행 upsert. 기존 metro 폴백행(sigungu NULL) 유지.
- [ ] **Step 2:** `sync_demand`가 시군구 지표를 주면 시군구별 upsert, 아니면 폴백(현행).
- [ ] **Step 3:** `pytest -q` 회귀 0(데모 skip 유지).
- [ ] **Step 4: 커밋** — `git add app/batch/daily.py && git commit -m "feat(batch): DataLab 기초지자체·수요 시군구 수집"`

---

### Task C4: 시드 시군구 반영

**Files:**
- Modify: `app/seed_data.py` (`seed_region_stats`)

- [ ] **Step 1:** `seed_region_stats`가 `SEOUL_SIGNGU_CODES`별로 약간 다른 지수를 넣어 시연에서 변별력이 보이게 + area 폴백행 유지. 스팟은 `sigungu_code`가 이미 세팅됨.
- [ ] **Step 2:** `pytest tests/test_region_sigungu.py tests/test_congestion_api.py -q` PASS.
- [ ] **Step 3: 커밋** — `git add app/seed_data.py && git commit -m "feat(seed): 시군구별 지역통계 시드"`

---

## Phase D — 콘텐츠·기상 보강 (WS-D)

### Task D1: detailIntro2 운영정보 수집

**Files:**
- Modify: `app/external/tour_api.py` (`detail_intro`)
- Modify: `app/models.py` (`TouristSpot` 운영정보 컬럼)
- Modify: `app/main.py` (`_COLUMN_MIGRATIONS`)
- Test: `tests/test_detail_intro.py` (신규)

**Interfaces:**
- Produces: `tour_api.TourApiClient.detail_intro(content_id, content_type_id) -> list[dict] | None`; `batch.intro_fields(item, content_type_id) -> tuple[use_time, rest_date, parking]`

- [ ] **Step 1: 실패 테스트 작성** — `tests/test_detail_intro.py`

```python
from app.batch.daily import intro_fields


def test_intro_fields_resolve_by_content_type():
    # 음식점(39)
    ut, rd, pk = intro_fields({"opentimefood": "10:00~22:00", "restdatefood": "월요일",
                               "parkingfood": "가능"}, 39)
    assert ut.startswith("10:00") and rd == "월요일" and pk == "가능"
    # 관광지(12)
    ut2, _, _ = intro_fields({"usetime": "09:00~18:00"}, 12)
    assert ut2.startswith("09:00")
```

- [ ] **Step 2: 실패 확인** → FAIL.

- [ ] **Step 3: 구현**
  - `tour_api.detail_intro`: `self.get("detailIntro2", contentId=content_id, contentTypeId=content_type_id)`.
  - `batch.INTRO_FIELD_MAP`(§7.1 타입별) + `intro_fields(item, content_type_id)`가 맵으로 `_first_value` 추출.
  - `models.TouristSpot`에 `use_time`(String200)/`rest_date`(String120)/`parking`(String120)/`intro_synced`(Boolean default False).
  - `_COLUMN_MIGRATIONS["tourist_spot"]` = 위 4컬럼.

- [ ] **Step 4: 통과 확인** — PASS + `pytest -q` 회귀 0.

- [ ] **Step 5: 커밋** — `git add -A && git commit -m "feat(tourapi): detailIntro2 운영시간·휴무·주차 수집 필드"`

---

### Task D2: enrich 통합 + 휴무 플래그

**Files:**
- Modify: `app/batch/daily.py` (`enrich_spot_content`)
- Modify: `app/services/congestion_service.py` (`get_congestion_view`에 `is_closed`)
- Modify: `app/schemas.py` (`CongestionResponse.is_closed`)
- Test: `tests/test_detail_intro.py`

**Interfaces:**
- Produces: `batch.is_closed_on(rest_date: str|None, d: date) -> bool`

- [ ] **Step 1: 실패 테스트 추가**

```python
from datetime import date
from app.batch.daily import is_closed_on

def test_is_closed_on_weekly_rest():
    monday = date(2026, 7, 13)  # 월요일
    assert is_closed_on("매주 월요일 휴무", monday) is True
    assert is_closed_on("연중무휴", monday) is False
    assert is_closed_on(None, monday) is False
```

- [ ] **Step 2: 실패 확인** → FAIL.

- [ ] **Step 3: 구현**
  - `is_closed_on`: 요일 키워드(월~일) 매칭 + '연중무휴/없음'은 False.
  - `enrich_spot_content`: `intro_synced=False` 우선 순회로 `detail_intro` 호출(스팟당 3콜 예산 §7.1), 필드 저장 + `intro_synced=True`.
  - `get_congestion_view`: 대상 스팟 `rest_date`로 `is_closed_on(spot.rest_date, d)` → 응답 `is_closed`. 산식 미개입(회귀 0), FE는 '휴무' 표시.
  - `schemas.CongestionResponse.is_closed: bool = False`.

- [ ] **Step 4: 통과 확인** — `pytest tests/test_detail_intro.py tests/test_congestion_api.py -q` PASS.

- [ ] **Step 5: 커밋** — `git add -A && git commit -m "feat(content): 운영정보 보강·휴무일 플래그"`

---

### Task D3: 기상 다변수

**Files:**
- Modify: `app/external/kma_api.py` (`get_weather`)
- Modify: `app/scoring/alternative.py` (`weather_fit` 확장)
- Test: `tests/test_weather_multi.py` (신규)

**Interfaces:**
- Produces: `kma_api.get_weather(lat, lng, d, time_slot) -> dict`(pop/sky/tmp, 각 None 가능)

- [ ] **Step 1: 실패 테스트 작성** — `tests/test_weather_multi.py`

```python
from app.scoring.alternative import weather_fit


def test_weather_fit_uses_sky_and_temp_for_outdoor():
    # 맑고 쾌적(sky=1, tmp=24) 야외 → 높은 적합도
    hot = weather_fit(is_indoor=False, precip_prob=10, sky=1, tmp=36)   # 폭염
    mild = weather_fit(is_indoor=False, precip_prob=10, sky=1, tmp=24)
    assert mild > hot
    assert weather_fit(is_indoor=False, precip_prob=None) is None       # 결측 → None
```

- [ ] **Step 2: 실패 확인** → FAIL(unexpected kwarg sky).

- [ ] **Step 3: 구현**
  - `kma_api.get_weather`: 기존 `_forecast_items`에서 POP뿐 아니라 `SKY`,`TMP` 카테고리도 target 시각으로 추출해 dict 반환. `get_precip_prob`는 `get_weather()["pop"]` 위임(하위호환 유지).
  - `weather_fit(is_indoor, precip_prob, sky=None, tmp=None)`: 기존 POP 로직에 폭염(tmp≥33)·한파(tmp≤-9) 감점, 맑음(sky=1) 가점(야외). sky/tmp None이면 기존 동작 그대로.

- [ ] **Step 4: 통과 확인** — `pytest tests/test_weather_multi.py -q` PASS + 기존 대안 테스트 회귀 0.

- [ ] **Step 5: 커밋** — `git add -A && git commit -m "feat(weather): 기상 SKY·기온 다변수 반영"`

---

## Phase E — 분산 임팩트 측정 고도화 (WS-E)

### Task E1: 노출 로그에 원지/대안 혼잡차 저장

**Files:**
- Modify: `app/models.py` (`RecommendationLog`)
- Modify: `app/main.py` (`_COLUMN_MIGRATIONS`)
- Modify: `app/services/recommend_service.py` (`get_alternatives` 로그)
- Test: `tests/test_dispersion_lift.py` (신규)

**Interfaces:**
- Produces: `RecommendationLog.origin_risk/alt_risk/decrease_pct`

- [ ] **Step 1: 실패 테스트 작성** — `tests/test_dispersion_lift.py`

```python
def test_recommendation_log_stores_risk_delta(db, gyeongbok_id):
    from app import models
    log = models.RecommendationLog(spot_id=gyeongbok_id, origin_spot_id=gyeongbok_id,
                                   origin_risk=85.0, alt_risk=40.0, decrease_pct=53)
    db.add(log); db.commit()
    saved = db.query(models.RecommendationLog).filter_by(id=log.id).one()
    assert saved.decrease_pct == 53 and saved.alt_risk == 40.0
```

- [ ] **Step 2: 실패 확인** → FAIL(TypeError).

- [ ] **Step 3: 구현**
  - `RecommendationLog`에 `origin_risk`/`alt_risk`(Float None)/`decrease_pct`(Int None).
  - `_COLUMN_MIGRATIONS["recommendation_log"]` = 3컬럼.
  - `get_alternatives`의 `log_exposure` 블록에서 각 노출 대안에 `origin_risk=origin_risk["risk"]`, `alt_risk=item["risk"]`, `decrease_pct=item["decrease_pct"]` 저장.

- [ ] **Step 4: 통과 확인** — PASS + `pytest -q` 회귀 0.

- [ ] **Step 5: 커밋** — `git add -A && git commit -m "feat(impact): 추천 노출에 원지/대안 혼잡차 기록"`

---

### Task E2: 분산 리프트 지표

**Files:**
- Modify: `app/services/impact_service.py` (`weekly_summary`)
- Modify: `app/schemas.py` (impact 응답)
- Modify: `nullnull-travel-webapp/src/main.jsx` (#admin F8 화면)
- Test: `tests/test_dispersion_lift.py`

**Interfaces:**
- Produces: `weekly_summary(db)["dispersion_lift"] = {exposed, selected, conversion_pct, avg_realized_decrease_pct}`

- [ ] **Step 1: 실패 테스트 추가**

```python
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
```

- [ ] **Step 2: 실패 확인** → FAIL(KeyError: dispersion_lift).

- [ ] **Step 3: 구현**
  - `weekly_summary`에 `dispersion_lift` 집계(노출 수, 선택 수, 전환율, 선택된 로그 `decrease_pct` 평균). is_seed 제외 원칙 + 콜드스타트 폴백은 기존 `_aggregate` 패턴 재사용.
  - impact 응답 스키마에 `dispersion_lift` 추가.
  - FE #admin 화면에 전환율·실현 감소율 카드.

- [ ] **Step 4: 통과 확인** — `pytest tests/test_dispersion_lift.py -q` PASS + `npx vite build` 성공.

- [ ] **Step 5: 커밋** — `git add -A && git commit -m "feat(impact): 분산 리프트(전환율·실현 감소율) 지표"`

---

## Phase F — 지도: Leaflet → 정적 SVG 지도 (WS-F, FE 단독·언제든 병렬)

> 스펙 §13. 목표: **Safari에서 Leaflet 지도가 단색 블록으로 깨지는 합성 버그(2026-07-13 실측 확인)**를, Leaflet 제거 + GeoJSON 인라인 SVG 렌더로 원천 해결. props(`points`) 불변, 정적 지도(팬/줌 없음). **지도 렌더는 유닛테스트 비실용 → 검증은 빌드 + Safari(localhost:3000) 육안.**
>
> **선행 맥락**: 이미 커밋된 Leaflet 벡터 베이스(`addSeoulVectorBase`+`import L`/`leaflet.css`/`seoulDistricts`)는 이 Phase가 대체·제거한다.

### Task F1: 정적 SVG 지도 컴포넌트 `PointsMap` 신규

**Files:**
- Create: `nullnull-travel-webapp/src/PointsMap.jsx`

**Interfaces:**
- Consumes: `./assets/seoul-districts.geo.json`
- Produces: `export default PointsMap({ points }) -> JSX` (props는 기존 `LeafletPointsMap`과 동일: `points: [{lat,lng,pin,className,tooltip}]`)

- [ ] **Step 1: 컴포넌트 작성** — `src/PointsMap.jsx` (전체):

```jsx
import seoulDistricts from './assets/seoul-districts.geo.json';

const VIEW_W = 1000, VIEW_H = 640, PAD = 0.18;

function makeProjection(points) {
  const lats = points.map(p => p.lat), lngs = points.map(p => p.lng);
  const cLat = (Math.min(...lats) + Math.max(...lats)) / 2;
  const cLng = (Math.min(...lngs) + Math.max(...lngs)) / 2;
  const spanLat = Math.max(Math.max(...lats) - Math.min(...lats), 0.02) * (1 + PAD * 2);
  const spanLng = Math.max(Math.max(...lngs) - Math.min(...lngs), 0.02) * (1 + PAD * 2);
  const minLat = cLat - spanLat / 2, maxLat = cLat + spanLat / 2;
  const minLng = cLng - spanLng / 2, maxLng = cLng + spanLng / 2;
  return (lng, lat) => [
    ((lng - minLng) / (maxLng - minLng)) * VIEW_W,
    ((maxLat - lat) / (maxLat - minLat)) * VIEW_H,   // y 뒤집기(위도↑=위쪽)
  ];
}

function districtPath(feature, project) {
  return (feature.geometry.coordinates || []).map(ring => {
    const pts = ring.map(([lng, lat]) => project(lng, lat));
    if (pts.every(([x, y]) => x < 0 || x > VIEW_W || y < 0 || y > VIEW_H)) return '';  // viewBox 밖 스킵
    return 'M' + pts.map(([x, y]) => `${x.toFixed(1)} ${y.toFixed(1)}`).join('L') + 'Z';
  }).join(' ');
}

export default function PointsMap({ points }) {
  if (!points?.length) return <div className="route-map"><div className="svg-map-skeleton" /></div>;
  const project = makeProjection(points);
  const projected = points.map(p => { const [x, y] = project(p.lng, p.lat); return { ...p, x, y }; });
  const districts = seoulDistricts.features
    .map(f => ({ name: f.properties?.name, d: districtPath(f, project) }))
    .filter(d => d.d);
  const routePts = projected.map(p => `${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' ');
  return (
    <div className="route-map">
      <svg className="svg-map" viewBox={`0 0 ${VIEW_W} ${VIEW_H}`} preserveAspectRatio="xMidYMid slice">
        {districts.map(d => <path key={d.name} d={d.d} className="svg-district" />)}
        {projected.length > 1 && <polyline points={routePts} className="svg-route" />}
        {projected.map((p, i) => (
          <g key={i} transform={`translate(${p.x.toFixed(1)} ${p.y.toFixed(1)})`}
             className={`svg-pin ${p.className || ''}`}>
            {p.tooltip && <title>{p.tooltip}</title>}
            <circle r="16" />
            <text className="svg-pin-label" dy="5" textAnchor="middle">{p.pin}</text>
          </g>
        ))}
      </svg>
    </div>
  );
}
```

- [ ] **Step 2: 빌드 확인** — `cd nullnull-travel-webapp && npx vite build` → 성공.

- [ ] **Step 3: 커밋** — `git add nullnull-travel-webapp/src/PointsMap.jsx && git commit -m "feat(map): GeoJSON 인라인 SVG 지도 컴포넌트 PointsMap 추가"`

---

### Task F2: main.jsx에서 Leaflet 제거·PointsMap 연결

**Files:**
- Modify: `nullnull-travel-webapp/src/main.jsx`

- [ ] **Step 1: import 정리** — `import L from 'leaflet';`, `import 'leaflet/dist/leaflet.css';`, `import seoulDistricts ...`(있으면) 제거, `import PointsMap from './PointsMap';` 추가.
- [ ] **Step 2: 정의 삭제** — `LeafletPointsMap` 함수 + `addSeoulVectorBase` 헬퍼 제거.
- [ ] **Step 3: 호출부 교체** — 대안 화면(`map-card`)·코스 화면(`course-map-card`)의 `<LeafletPointsMap points={...} />` 2곳을 `<PointsMap points={...} />`로(props 동일).
- [ ] **Step 4: 빌드** — `npx vite build` 성공.
- [ ] **Step 5: 커밋** — `git add nullnull-travel-webapp/src/main.jsx && git commit -m "refactor(map): Leaflet 제거하고 PointsMap(SVG)으로 교체"`

---

### Task F3: SVG 지도 스타일 + 검증

**Files:**
- Modify: `nullnull-travel-webapp/src/styles.css`
- Modify: `nullnull-travel-webapp/package.json` (선택)

- [ ] **Step 1: 스타일 추가** — `styles.css`:

```css
.svg-map { width: 100%; height: 100%; min-height: 360px; display: block; background: #eaf4f1; }
.svg-district { fill: #e4efe7; stroke: #cdd9cf; stroke-width: 1; }
.svg-route { fill: none; stroke: #3d8567; stroke-width: 5; stroke-dasharray: 9 11; stroke-linecap: round; }
.svg-pin circle { fill: #3d8567; stroke: #fff; stroke-width: 2; }
.svg-pin.is-origin circle { fill: #12352a; }
.svg-pin.is-level-4 circle { fill: #e8892b; }
.svg-pin.is-level-5 circle { fill: #d0402e; }
.svg-pin-label { fill: #fff; font-size: 15px; font-weight: 800; }
```
(`.route-map`의 border-radius+overflow는 SVG엔 무해하므로 유지.)

- [ ] **Step 2: (선택) 정리** — 잔여 `.leaflet-*` 규칙·`package.json`의 `leaflet` 의존성 제거(다른 사용처 없을 때).
- [ ] **Step 3: 빌드 + 육안 검증** — `npx vite build` 성공 후 **Safari(localhost:3000)에서 코스/대안 지도가 자치구+마커+경로로 정상 표시**(단색 블록 없음) 확인.
- [ ] **Step 4: 커밋** — `git add nullnull-travel-webapp/src/styles.css nullnull-travel-webapp/package.json && git commit -m "style(map): SVG 지도 스타일 + leaflet 잔여 정리"`

---

## 최종 검증(전 Phase 완료 후)
- [ ] `python -m pytest -q` — 신규 포함 전량 통과, 사전 존재 OpenAI 3건 외 회귀 0.
- [ ] `cd nullnull-travel-webapp && npx vite build` — 성공.
- [ ] demo 모드 스모크: `/api/spots/{id}/congestion?date=today` — 오버투어리즘 필드 None·기존 응답 유지.
- [ ] (키 보유 환경) 배치 1회 실행 → `api_ingest_log` 매칭율·`RegionStatDaily` 시군구 행·서울 실시간 커버리지 확인.
- [ ] WS-F: Safari(localhost:3000)에서 코스/대안 지도가 자치구+마커+경로 SVG로 정상 표시(단색 블록 없음).

## Self-Review 결과
- **스펙 커버리지**: §4→PhaseB, §5→PhaseA, §6→PhaseC, §7→PhaseD, §8→PhaseE, §13→PhaseF, §9 마이그레이션→C1/D1/E1 + main. 전 항목 태스크 매핑됨.
- **플레이스홀더**: 핵심 로직(normalize_name/resolve_spot/refined_score/overtourism_fields/region_for/dispersion_lift)은 완전 코드. 배치·FE 배선 태스크는 정확한 편집 지점·계약·테스트명 명시(외부 API 필드 미확정분은 기존 `_first_value` 폴백 패턴 준수 — §11).
- **타입 일관성**: `seoul_area_key`(A3)·`_realtime_slot_score`(Task1)·`get_realtime_by_area`(A3)·`overtourism_fields`(A4)·`region_for`(C2) 시그니처가 사용처와 일치.