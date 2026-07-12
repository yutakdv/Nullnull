# 혼잡·분산·API 활용 보완 — 설계 스펙

- 작성일: 2026-07-13
- 대상 시점: 개발단계(7월말~9월). 07-21 제출 크런치가 아니라 근본 개선.
- 근거 진단: `memory/congestion-dispersion-assessment.md` (Task2 분산 능력 / Task3 API 활용).
- 확정 설계 선택: **Fork1=B, Fork2=B, Fork3=B, Fork4=A** (아래 §2).

---

## 1. 배경·목표

### 1.1 진단 요약(무엇이 문제인가)
1. **스팟 변별력이 집중률 단일축에 의존.** `congestion_risk` 4항 중 `region_visitor(0.20)`·`demand(0.15)`는 `RegionStatDaily.area_code=1`(서울 전체) 단일값 → **모든 서울 스팟에 동일하게 들어가 순위를 못 가름**. `demand`는 공급처 미적재로 상시 결측. 실제 스팟을 가르는 건 `concentration(0.55)` 하나뿐.
2. **가장 정확한 소스(서울 실시간)가 10곳만.** `seoul_api.SPOT_TO_AREA` 10개 매핑. 나머지는 KT 집중률 예측 or `base_popularity` 휴리스틱 + 고정 시간곡선(`SLOT_FACTOR` 0.62/1.0/0.85).
3. **서울 실시간 응답의 약 1/15만 사용.** `AREA_CONGEST_LVL`(+`FCST_CONGEST_LVL`)만 파싱. `AREA_PPLTN_MIN/MAX`(연속 인원), `NON_RESNT_PPLTN_RATE`(비상주=관광객 비율, 오버투어리즘 핵심), `FCST_PPLTN_MIN/MAX`, `AREA_CONGEST_MSG` 미사용.
4. **집중률·연관·방문자 API가 스팟명 문자열 매칭 의존** → DB 스팟명 불일치 시 0건.
5. **분산의 증거가 앱 내 자기신고** — 실제 이동/혼잡차의 검증 지표 부재.

### 1.2 성공 기준(측정 가능)
- 서울 실시간 커버리지: **10 → 100+ 스팟**(공식 지원지역 전량 매핑).
- region/demand가 시군구 단위 → **동일 area 스팟들의 risk 표준편차 > 0**(변별력 발생).
- 집중률/연관/방문자 API **매칭율(수신 대비 DB 반영)**을 `api_ingest_log`에 기록·관측.
- **오버투어리즘 지수**(비상주 관광객 비율)를 F3 응답·UI에 노출.
- **분산 리프트 지표**: 대안 노출→선택 전환율 + 선택된 대안의 실제 혼잡차(decrease_pct) 산출.

### 1.3 관통 원칙·제약(전 워크스트림 공통)
- **회귀 0**: 신규 신호는 결측 시 `renormalize`로 흡수. `congestion_risk` 가중치·기존 테스트 불변.
- **쿼터 1000/일 예산 준수**: 요청경로 서울 실시간 HTTP는 원 관광지 + 대안 top-N(≤5)만. 배치에는 서울 실시간 안 넣음.
- **demo/오프라인 폴백 유지**: 키 없으면 시드·휴리스틱으로 그대로 동작(심사 오프라인 대비).
- **마이그레이션**: `main.apply_column_migrations()`(idempotent `ALTER TABLE ADD COLUMN`) 확장 + 인덱스 재작성 헬퍼 신규. 신규 테이블은 `create_all`이 처리. 팀 공유 DB(`nullnull.db` 커밋)와 시드 재생성 양쪽 지원.

---

## 2. 확정된 설계 선택 (Fork)

| Fork | 질문 | 선택 | 요지 |
|------|------|------|------|
| 1 | 연속 인구지표를 산식에 어떻게? | **B** 라벨 앵커 + 밴드 내 보간 | 라벨(여유20~붐빔90)을 밴드 중앙으로, 실인원의 당일·당지역 범위 내 위치로 ±보간. 지역 절대규모에 안 휘둘림, 서울 미지원 스팟 회귀 0. |
| 2 | 비상주율(관광객 쏠림)을 어디에? | **B** 별도 '관광객 쏠림 지수' | `congestion_risk` 불변. "이 붐빔의 X%가 외지 관광객" 배지로 오버투어리즘 서사 강화. 회귀 위험 0. |
| 3 | 스팟↔외부식별자 매칭 방식? | **B** 신규 `SpotExternalRef` 매핑 테이블 | 소스 3종+(서울 area / 집중률 tAtsNm / 연관 이름)을 확장형 테이블로. 마스터 스키마 오염 없음. |
| 4 | `RegionStatDaily` 시군구 확장? | **A** 기존 키에 sigungu_code 추가 | 서울전체(sigungu=NULL) 행은 폴백 유지. 조회는 sigungu→폴백. 최소 변경. |
| 5 | 지도 표시 문제 해법? | **벡터 베이스맵** 로컬 GeoJSON | 외부 래스터 타일 의존 제거 → 오프라인·CSP·타일 깨짐 해소. 이미 번들된 `seoul-districts.geo.json` 재활용, 마커/경로 인터페이스 불변. (§13 WS-F) |

---

## 3. 구현 순서(의존성)

```
WS-B(매칭)  →  WS-A(서울 실시간 심화)
                    ‖ (병렬 가능)
                WS-C(시군구)
                    ↓
             WS-D(콘텐츠·기상)  →  WS-E(임팩트 측정)
```

WS-B가 WS-A의 커버리지 확대와 WS-C·WS-D의 API 매칭 신뢰도를 떠받치므로 **먼저**. WS-A/WS-C는 상호 독립이라 병렬. WS-D·WS-E는 마지막. **WS-F(지도 GeoJSON)는 FE 단독·백엔드 무관이라 언제든 병렬 진행 가능**(§13).

---

## 4. WS-B — 스팟 식별·매칭 신뢰화 (Fork3=B)

### 4.1 목표
집중률(`tAtsNm`)·서울(area명)·연관(이름) API의 식별자를 스팟에 신뢰성 있게 연결. 스팟명 문자열 완전일치 실패로 인한 0건 수집 제거.

### 4.2 데이터모델 — 신규 테이블 `SpotExternalRef`
`app/models.py`:
```python
class SpotExternalRef(Base):
    """외부 API 식별자 ↔ 스팟 매핑(집중률 tAtsNm / 서울 area명 / 연관 이름)."""
    __tablename__ = "spot_external_ref"
    __table_args__ = (UniqueConstraint("source", "ext_key", name="uq_ext_ref"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    source: Mapped[str] = mapped_column(String(20), index=True)   # seoul|tats|related
    ext_key: Mapped[str] = mapped_column(String(120), index=True) # 정규화 키(area명 또는 normalize_name)
    spot_id: Mapped[int] = mapped_column(ForeignKey("tourist_spot.spot_id"), index=True)
    confidence: Mapped[float] = mapped_column(Float, default=1.0)
    method: Mapped[str] = mapped_column(String(10), default="seed")  # seed|name|coord
```
- `create_all`이 자동 생성(마이그레이션 불필요).

### 4.3 신규 모듈 `app/matching.py`
```python
def normalize_name(name: str) -> str:
    """공백·괄호·구두점 제거, 별칭 정규화. '경복궁(사적)' '경복궁 ' → '경복궁'."""
    # 1) 괄호/대괄호 내용 제거 2) 공백··,·- 제거 3) 소문자화(영문) 4) 접미 별칭 테이블 적용

def resolve_spot(db, source: str, key: str, *, lat=None, lng=None) -> int | None:
    """① SpotExternalRef(source, normalize_name(key)) 조회
       ② 실패 시 TouristSpot.name 정규화 완전일치
       ③ 그래도 실패 & 좌표 있으면 nearest_spot(lat,lng, max_km=0.3)
       ④ 해결되면 (source, key)로 ref upsert(method 기록) 후 spot_id 반환, 아니면 None"""

def nearest_spot(db, lat: float, lng: float, max_km: float = 0.3) -> int | None:
    """반경 내 최근접 스팟(haversine). 후보 없으면 None."""
```
- 좌표 폴백은 좌표를 주는 소스에만 유효. 서울 area·집중률 `tAtsNm`은 좌표 미제공 → 이름 경로. 연관 API가 좌표를 주면 좌표 폴백 활용.

### 4.4 서울 area 매핑 시드
- 기존 `seoul_api.SPOT_TO_AREA`(10행)를 **`SpotExternalRef(source='seoul')` 시드로 이관·확장**.
- `app/seed_data.py`에 `seed_external_refs(db, spots)`: 서울 공식 실시간도시데이터 지원지역(약 116곳) 중 시드/수집 스팟과 겹치는 항목을 `ext_key=area명`으로 등록.
- 지원지역 전체 목록은 §10 리스크의 액션 아이템(공식 목록 확보). 최소 착수분은 현 10곳 + 관광특구·고궁 확장.

### 4.5 배치 통합
`app/batch/daily.py`:
- `sync_concentration`: `spots_by_name.get(name)` → `resolve_spot(db, "tats", name)`.
- `sync_related`: `_spot_by_name` → `resolve_spot(db, "related", base_name/rel_name, lat, lng)`.
- 각 job에 `matched/received` 카운트를 `log_ingest`의 records와 별도로 `error_message`(성공 시에도 관측 메모)나 신규 `unmatched` 상태로 기록 → **매칭율 관측**.

### 4.6 파일별 변경
| 파일 | 변경 |
|------|------|
| `app/models.py` | +`SpotExternalRef` |
| `app/matching.py` | 신규: `normalize_name`, `resolve_spot`, `nearest_spot` |
| `app/external/seoul_api.py` | `SPOT_TO_AREA` 상수 → `resolve` 기반(§5.4) |
| `app/batch/daily.py` | `sync_concentration`/`sync_related` resolve 사용 + 매칭율 로깅 |
| `app/seed_data.py` | +`seed_external_refs` |

### 4.7 테스트 — `tests/test_matching.py`
- `normalize_name`: 괄호/공백/별칭 케이스.
- `resolve_spot`: ref 히트 / 이름 정규화 히트 / 좌표 폴백 히트 / 전부 실패 None.
- 배치 매칭율: 이름 불일치 스팟이 좌표로 해결되는지.

---

## 5. WS-A — 서울 실시간 도시데이터 심화 (Fork1=B, Fork2=B)

### 5.1 목표
서울 실시간 응답 전체 필드 활용 + 커버리지 확대 + 대안 실시간 반영 + 오버투어리즘 지수. 커버하는 보완: T3-1·T2-1·T2-2 + Fork2 오버투어리즘.

### 5.2 `seoul_api.get_realtime_congestion` 반환 확장
현재 `{"score", "forecast":[{"hour","score"}]}` → 확장:
```python
{
  "score": float,                 # 현재 refined 스코어(Fork1-B, 아래 5.3)
  "level_label": str,             # 원 라벨(여유/보통/약간 붐빔/붐빔)
  "ppltn_min": int|None, "ppltn_max": int|None,
  "non_resident_rate": float|None,  # NON_RESNT_PPLTN_RATE, 0~100
  "congest_msg": str|None,          # AREA_CONGEST_MSG
  "ppltn_time": str|None,           # PPLTN_TIME(기준시각)
  "forecast": [
    {"hour": "HH", "score": float, "level_label": str,
     "ppltn_min": int|None, "ppltn_max": int|None}
  ]
}
```
파싱 추가 필드: `AREA_PPLTN_MIN`, `AREA_PPLTN_MAX`, `NON_RESNT_PPLTN_RATE`, `AREA_CONGEST_MSG`, `PPLTN_TIME`, `FCST_PPLTN[].FCST_PPLTN_MIN/MAX`.

### 5.3 연속 스코어 `refined_score` (Fork1=B)
라벨을 밴드 앵커로 두고 **당일·당지역 인원 범위 내 위치**로 보간(교차지역 정규화 회피):
```python
BAND_CENTER = {"여유":20.0, "보통":45.0, "약간 붐빔":70.0, "붐빔":90.0}
BAND_HALFWIDTH = 12.5   # 인접 밴드와 겹치지 않는 폭(밴드 간격 25의 절반)

def refined_score(level_label, ppltn_mid, area_lo, area_hi):
    center = BAND_CENTER.get(level_label, 45.0)
    if area_hi <= area_lo or ppltn_mid is None:
        return center                      # 인원 정보 없으면 라벨 그대로(회귀 0)
    pos = (ppltn_mid - area_lo) / (area_hi - area_lo)   # 0~1
    return round(min(max(center + (pos - 0.5) * 2 * BAND_HALFWIDTH, 0.0), 100.0), 1)
```
- `ppltn_mid = (ppltn_min+ppltn_max)/2`. `area_lo/hi` = 현재+forecast의 ppltn_mid 최소/최대(당지역 당일 범위).
- **seoul_api 파싱 시점에 계산**해 `score`/`forecast[].score`에 refined 값을 그대로 담는다 → Task1의 `realtime["score"]` 경로가 자동으로 refined화(congestion_service 변경 최소).
- 단조성: `pos` 증가 시 score 증가. 클램프 [0,100]. 밴드 폭 제한으로 라벨 경계 역전 없음.

### 5.4 커버리지 확대 (T2-1)
- `SPOT_TO_AREA` 상수 제거 → `get_realtime_congestion`에 **area_key를 인자로** 받도록 변경:
  - 신규 시그니처 `get_realtime_by_area(area_key: str) -> dict | None`.
  - 호출부(`congestion_service.compute_raw_risk`)가 `resolve` 결과로 area_key를 얻어 전달:
    `area_key = seoul_area_key(db, spot)` — `SpotExternalRef(source='seoul', spot_id=spot.spot_id)` 조회.
  - 하위호환: 기존 `get_realtime_congestion(spot_name)`은 이름→area 폴백 래퍼로 유지(테스트 안정).
- 커버리지는 §4.4 시드가 결정 → 매핑 늘리면 실시간 닿는 스팟 자동 증가.

### 5.5 대안 top-N 실시간 (T2-2)
`app/services/recommend_service.get_alternatives`:
- 현재: 원 관광지만 실시간, 후보는 `bulk_risks`(배치 캐시). 유지하되, **선정된 `top`(≤limit≤5)에만** 카카오 길찾기와 동일 패턴으로 서울 실시간 조회:
  ```python
  if d == date.today():
      for it in top:
          area_key = seoul_area_key(db, it["spot"])
          rt = seoul_api.get_realtime_by_area(area_key) if area_key else None
          if rt:
              # Task1의 _realtime_slot_score(rt, time_slot) 재사용 — 현재 슬롯이면 rt["score"](refined),
              # 그 외 슬롯은 forecast 대표시각 값. 원지와 동일 기준으로 비교.
              it["risk"] = _realtime_slot_score(rt, time_slot)
              # relief_norm·decrease_pct를 갱신된 risk로 재계산(카드 표시 정합)
  ```
- 60초 캐시(`seoul_api._cache`)가 중복 조회 흡수. 실패 시 배치 캐시 값 유지(폴백).
- **당일이 아니면 미적용**(예측 창 미래 날짜는 실시간 개념 없음).

### 5.6 오버투어리즘 지수 (Fork2=B)
- `congestion_risk` 산식 **불변**. 별도 파생 지표를 F3 응답에 추가:
  ```python
  # congestion_service.compute_risk 결과에 부가(realtime일 때만 값)
  tourist_share_pct: int | None      # round(non_resident_rate)
  tourist_pressure: str | None       # '관광객 쏠림'(≥60) | '관광·현지 혼재'(40~60) | '현지 생활'(<40) | None
  live_ppltn_min: int | None         # 응답에는 §5.7처럼 평탄화(min/max 별도 필드)해 노출
  live_ppltn_max: int | None
  ```
- 값 산출은 realtime dict의 `non_resident_rate`, `ppltn_min/max`에서. 비실시간 소스면 전부 None(정직성).

### 5.7 schemas 변경 (`app/schemas.py`)
`CongestionResponse`에 추가(모두 Optional, 기본 None → 기존 응답 호환):
```python
tourist_share_pct: int | None = None
tourist_pressure: str | None = None
live_ppltn_min: int | None = None
live_ppltn_max: int | None = None
congest_msg: str | None = None
```

### 5.8 FE 변경 (`nullnull-travel-webapp/src/main.jsx`)
- 널널도 카드(현 `congestion-card`/헤드라인)에:
  - `tourist_pressure` 배지(관광객 쏠림) + `tourist_share_pct` 수치.
  - `live_ppltn_min~max` "실시간 체류 인원" 표기(있을 때만).
  - `congest_msg`를 tip 보조 문구로(있을 때만, 없으면 기존 tip).
- 대안 카드 risk가 top에서 실시간화되므로 라벨/감소율 표시는 그대로(값만 정확해짐).

### 5.9 파일별 변경
| 파일 | 변경 |
|------|------|
| `app/external/seoul_api.py` | 필드 파싱 확장, `refined_score`, `get_realtime_by_area`, 이름 래퍼 |
| `app/services/congestion_service.py` | `seoul_area_key`, realtime 부가지표(tourist_*), Task1 경로 유지 |
| `app/services/recommend_service.py` | 대안 top-N 실시간 blend |
| `app/schemas.py` | `CongestionResponse` 부가 필드 |
| `nullnull-travel-webapp/src/main.jsx` | 쏠림 배지·실시간 인원·congest_msg |

### 5.10 테스트
- `tests/test_seoul_realtime_fields.py`: 확장 필드 파싱(모의 응답), None 안전.
- `tests/test_refined_score.py`: 단조성, 클램프, 인원 결측 시 라벨 반환(회귀 0).
- `tests/test_overtourism_index.py`: 비상주율→배지/share 매핑, 비실시간 None.
- demo 모드에서 realtime None → 기존 congestion 테스트 전량 통과 확인.

---

## 6. WS-C — 지역통계 시군구 단위화 (Fork4=A)

### 6.1 목표
`region_visitor`·`demand` 항이 스팟(시군구)별로 달라지게 → 변별력 확보. 커버: T3-3.

### 6.2 데이터모델 변경 (`app/models.py`)
`RegionStatDaily`:
```python
sigungu_code: Mapped[int | None] = mapped_column(Integer, index=True)  # 신규(NULL=서울전체 폴백)
__table_args__ = (UniqueConstraint("area_code", "sigungu_code", "date", name="uq_region_stat"),)
```
- 마이그레이션(§9): `ADD COLUMN sigungu_code` + `uq_region_stat` 인덱스 재작성(drop→create).

### 6.3 수집 변경 (`app/batch/daily.py`)
- `sync_visitors`:
  - 기존 `metro_visitors`(서울 11 전체) → `area_code=1, sigungu=NULL` 폴백 행 유지.
  - **추가**: `datalab_api.local_visitors(start, end, signgu)` 를 `SEOUL_SIGNGU_CODES` 순회 → 시군구별 요일 패턴 투영 → `(area_code=1, sigungu_code=signgu, date)` 행.
- `sync_demand`:
  - `stay_intensity(base_ym, area_code=11)` 이 시군구 필드를 주면 시군구별 행 분해. 미적재(현 0건)면 기존처럼 폴백 없음(재정규화 흡수).
- `RegionStatDaily` 조회 upsert 키에 sigungu_code 반영.

### 6.4 조회 변경 (`app/services/congestion_service.py`)
- `compute_raw_risk`·`bulk_risks`의 RegionStatDaily 조회:
  ```python
  region = region_for(db, spot.area_code, spot.sigungu_code, d)  # 신규 헬퍼
  # (area, sigungu, date) 우선 → 없으면 (area, NULL, date) 폴백
  ```
- `bulk_risks`는 `region_map`을 `(area_code, sigungu_code)` 키로 구성 + 폴백 맵 병행.

### 6.5 시드 변경 (`app/seed_data.py`)
- `seed_region_stats`가 시군구별로 살짝 다른 지수를 넣어(시연에서 변별력 시각화) `sigungu_code` 채움 + 서울전체 폴백행.

### 6.6 테스트 — `tests/test_region_sigungu.py`
- 시군구 행 존재 시 그 값, 없으면 폴백행.
- **서로 다른 시군구의 두 스팟이 다른 region_visitor → risk가 달라짐**(변별력 회귀 테스트).

---

## 7. WS-D — 콘텐츠·기상 보강

### 7.1 detailIntro2(운영시간·휴무·주차) — T3-4
- `app/external/tour_api.py`: `detail_intro(content_id, content_type_id)` → `detailIntro2`.
- `app/models.py` `TouristSpot` 신규 컬럼(마이그레이션 §9): `use_time`(String200), `rest_date`(String120), `parking`(String120), `intro_synced`(Boolean default False).
- 콘텐츠 타입별 필드 해석 맵(기존 `_first_value` 패턴):
  - 12 관광지: `usetime`/`restdate`/`parking`
  - 14 문화시설: `usetimeculture`/`restdateculture`/`parkingculture`
  - 15 행사: `playtime`/`-`/`parkingplace`
  - 28 레포츠: `usetimeleports`/`restdateleports`/`parkingleports`
  - 38 쇼핑: `opentime`/`restdateshopping`/`parkingshopping`
  - 39 음식점: `opentimefood`/`restdatefood`/`parkingfood`
- `app/batch/daily.py` `enrich_spot_content`: `detailIntro2` 추가 호출(스팟당 콜 2→3). 예산: `ENRICH_BUDGET=150`×3=450콜 + `sync_spots`(타입6×페이지) ≈ 최대 ~600콜/일 < 1000. 예산 초과 방지 위해 intro는 `intro_synced=False` 우선 순회로 분리 처리.
- **휴무일 반영**: `rest_date` 파싱 → 해당 날짜가 휴무면 F3 응답에 `is_closed: bool` + 붐빔 표시를 '휴무'로 대체(risk 계산에서 제외하거나 별도 플래그). 최소 구현은 플래그만, 산식 미개입(회귀 0).

### 7.2 기상 다변수 — T3-5
- `app/external/kma_api.py`: `get_weather(lat,lng,d,slot) -> {"pop":..,"sky":..,"tmp":..}` (기존 `_forecast_items` 응답에 SKY·TMP 카테고리 파싱 추가, 호출 1회 그대로).
- `app/scoring/alternative.py` `weather_fit`: POP 단독 → (POP + 폭염/한파 TMP + SKY) 결합. 시그니처 하위호환(기존 POP-only 인자 유지, sky/tmp Optional). 예보 범위 밖이면 None(재정규화).

### 7.3 테스트
- `tests/test_detail_intro.py`: 타입별 필드 해석, `rest_date` 휴무 판정.
- `tests/test_weather_multi.py`: SKY/TMP 파싱, `weather_fit` 다변수 단조성, 결측 None.

---

## 8. WS-E — 분산 임팩트 측정 고도화 — T2-3

### 8.1 데이터모델 (`app/models.py`)
`RecommendationLog` 신규 컬럼(마이그레이션 §9):
```python
origin_risk: Mapped[float | None]   # 노출 시 원 관광지 risk
alt_risk: Mapped[float | None]      # 노출된 대안 risk
decrease_pct: Mapped[int | None]    # 예상 혼잡 감소율(표시값과 동일)
```

### 8.2 기록 (`app/services/recommend_service.py`)
- `get_alternatives`의 `log_exposure` 블록에서 노출 대안마다 `origin_risk`/`alt_risk`/`decrease_pct` 저장.
- 선택(selected=True) 기록 경로 확인: 기존 선택 반영 지점(로그 selected 갱신)에서 그대로 사용 — 선택 시 별도 컬럼 갱신 불필요(노출행에 이미 저장).

### 8.3 지표 (`app/services/impact_service.py`)
- 기존 `weekly_summary`에 **분산 리프트** 추가:
  ```python
  "dispersion_lift": {
    "exposed": int, "selected": int,
    "conversion_pct": round(selected/exposed*100),
    "avg_realized_decrease_pct": round(avg(decrease_pct where selected)),
  }
  ```
- is_seed 제외 원칙 유지, 콜드스타트 시 시드 포함 폴백 + `includes_seed` 고지(기존 패턴).

### 8.4 schemas/FE
- `impact/summary` 응답에 `dispersion_lift` 추가. 관리자(#admin F8) 화면에 전환율·실현 감소율 표시.

### 8.5 테스트 — `tests/test_dispersion_lift.py`
- 노출/선택 로그로 conversion·avg decrease 산출, 시드 제외/폴백.

---

## 9. 마이그레이션 종합

### 9.1 컬럼 추가 (`main._COLUMN_MIGRATIONS` 확장)
```python
_COLUMN_MIGRATIONS = {
    "course": { ... 기존 ... },
    "region_stat_daily": {"sigungu_code": "INTEGER"},
    "tourist_spot": {
        "use_time": "VARCHAR(200)", "rest_date": "VARCHAR(120)",
        "parking": "VARCHAR(120)", "intro_synced": "BOOLEAN DEFAULT 0",
    },
    "recommendation_log": {
        "origin_risk": "FLOAT", "alt_risk": "FLOAT", "decrease_pct": "INTEGER",
    },
}
```

### 9.2 인덱스 재작성 (신규 `apply_index_migrations()`)
`uq_region_stat`가 `(area_code, date)` → `(area_code, sigungu_code, date)`로 바뀌므로:
```python
def apply_index_migrations() -> None:
    with engine.begin() as conn:
        idx = {r[1] for r in conn.execute(text("PRAGMA index_list('region_stat_daily')"))}
        # 기존 인덱스가 sigungu_code를 포함하지 않으면 재작성
        cols = _index_columns(conn, "uq_region_stat")
        if "uq_region_stat" in idx and "sigungu_code" not in cols:
            conn.execute(text("DROP INDEX uq_region_stat"))
            conn.execute(text(
                "CREATE UNIQUE INDEX uq_region_stat "
                "ON region_stat_daily(area_code, sigungu_code, date)"))
```
- SQLite 한정 로직. Postgres 전환 시 Alembic 도입 전제(현재 SQLite 고정).
- 순서: `create_all` → `apply_column_migrations` → `apply_index_migrations` → seed.

### 9.3 신규 테이블
- `SpotExternalRef`: `create_all` 자동.

### 9.4 시드 재생성 경로
- `seed_data`의 리셋 대상 목록에 `SpotExternalRef` 포함, `seed_external_refs`/`seed_region_stats`(sigungu) 반영.

---

## 10. weights.yaml 영향
- `congestion_risk`: **불변**(회귀 0). 오버투어리즘·연속 인원은 산식 밖(표시/보간)이라 가중치 추가 없음.
- `alternative_score.weather`: 항 유지(내부만 다변수화).
- 향후 8월 백테스트에서 재조정 여지는 기존 주석대로 열어둠(이 스펙 범위 밖).

---

## 11. 리스크·미결 사항
1. **서울 실시간 지원지역 공식 목록 확보** — `seed_external_refs`를 100+행으로 채우려면 서울 열린데이터광장 "실시간도시데이터" 지원지역 목록 필요. 미확보 구간은 현 10곳 + 관광특구/고궁 확장으로 착수, 목록 확보 시 시드 보강.
2. **공사 API 필드명 미확정**(tats/demand/related) — 승인 후 Swagger 확정 필요. 현 코드의 후보키 폴백(`_first_value`/`_first_numeric`)을 그대로 유지해 방어.
3. **대안 top-N 실시간 지연** — `timeout=10s × N(≤5)` + 60초 캐시로 상한 관리. 실패 시 배치 캐시 폴백(응답 보장).
4. **demand API 미적재(0건)** — WS-C의 demand 시군구화는 데이터 적재 시 자동 활성. 그 전까지 재정규화로 흡수(현 상태 유지).
5. **인덱스 재작성 안전성** — 배포 DB(`nullnull.db`)에 대해 idempotent(이미 sigungu 포함이면 skip). 실행 전 백업 권장(팀 공유 DB).

---

## 12. 검증 계획(각 WS 완료 기준)
- 전 WS: `python -m pytest` 전량 통과(신규 회귀 0), demo 모드 기존 동작 유지.
- WS-B: 매칭율 로깅 확인, 이름 불일치 스팟 좌표 해결.
- WS-A: 확장 필드 파싱·refined 단조성·오버투어리즘 배지, 대안 top 실시간화(당일).
- WS-C: 동일 area·다른 sigungu 스팟의 risk 표준편차 > 0.
- WS-D: 타입별 운영정보 수집·휴무 플래그, 기상 다변수.
- WS-E: 전환율·실현 감소율 산출.
- WS-F: `vite build` 성공 + 네트워크 차단(오프라인) 상태에서 지도 정상 표시·마커·경로 유지.
- 관측 대시보드(#admin): 커버리지 수·매칭율·분산 리프트.

---

## 13. WS-F — 지도 GeoJSON 벡터 베이스맵 (FE 단독, 독립)

### 13.1 문제 진단
- 현재 `LeafletPointsMap`([main.jsx:3247](../../../nullnull-travel-webapp/src/main.jsx))의 base layer가 **외부 CARTO/OSM 래스터 타일**(`https://{s}.basemaps.cartocdn.com/rastertiles/voyager/...`). 네트워크·CSP·레이트리밋·**오프라인 심사**에서 타일이 회색/깨진 블록으로 표시됨. 코드에도 타일 합성 아티팩트 패치 흔적(둥근 클립 분리 3306–3307, 0크기 컨테이너 `invalidateSize` 3291–3296)이 남아 있어 표시 신뢰성이 근본 약점.
- **가정한 증상**: "지도 표시 문제 = 타일 로드 실패/깨짐/오프라인 회색 지도". 만약 실제 증상이 **마커 위치 오류**라면 이는 좌표(lat/lng) 데이터 문제로 GeoJSON 베이스맵과 별개다(§13.6 참고).

### 13.2 해법 개요
- 외부 래스터 타일 base layer를 **로컬 GeoJSON 벡터 베이스맵**으로 대체.
- 재활용 에셋: `nullnull-travel-webapp/src/assets/seoul-districts.geo.json`(서울 25개 자치구 폴리곤, 28KB) — 이미 번들되어 `SeoulMap3D`가 사용 중. **추가 의존성·에셋 0**.
- Leaflet이 GeoJSON(WGS84)을 Web Mercator로 자동 투영 → 기존 마커 `[lat, lng]`·polyline·`fitBounds` 로직 **그대로 유지**. base layer만 교체.
- 결과: 외부 요청 없이 항상 렌더 → 오프라인·CSP·타일 깨짐 전부 해소, 앱 파스텔 톤과 일관.

### 13.3 설계
- 신규 헬퍼 `addSeoulVectorBase(map)`:
  ```javascript
  import districts from './assets/seoul-districts.geo.json';

  function addSeoulVectorBase(map) {
    return L.geoJSON(districts, {
      style: {
        color: '#cdd9cf', weight: 1,           // 자치구 경계(얇게)
        fillColor: '#eef4ef', fillOpacity: 1,  // 파스텔 면
      },
      interactive: false,                       // 배경이라 클릭 비활성
    }).addTo(map);
  }
  ```
- `LeafletPointsMap`의 `L.tileLayer(...).addTo(map)` 블록을 `addSeoulVectorBase(map)`로 교체. 마커/divIcon/폴리라인/`fitBounds`/`invalidateSize`·resize 타이머는 불변.
- 지도 배경이 흰 여백이 되지 않도록 `.leaflet-container` 배경색을 물색 톤(#f2f6f4)으로(styles.css). 서울 밖 영역은 은은한 단색.
- **선택(YAGNI 기본 제외)**: 온라인일 때만 래스터 타일을 얇은 오버레이로 얹는 opt-in 토글. 기본 구현은 벡터-only(오프라인 안전). 도로/지명 디테일이 꼭 필요해지면 그때 추가.
- **선택**: 스팟이 속한 자치구를 옅게 하이라이트(방문 지역 강조). 자치구 라벨은 과밀 방지 위해 기본 생략.

### 13.4 백엔드 영향
- 없음. 순수 FE.

### 13.5 파일별 변경
| 파일 | 변경 |
|------|------|
| `nullnull-travel-webapp/src/main.jsx` | `LeafletPointsMap` base layer를 `addSeoulVectorBase`로 교체, GeoJSON import |
| `nullnull-travel-webapp/src/styles.css` | `.leaflet-container`/`.leaflet-host` 배경 톤, (선택) 하이라이트 스타일 |

### 13.6 검증·리스크
- 검증: Leaflet DOM 렌더는 유닛테스트가 비실용적 → `npx vite build` 성공 + **네트워크 차단 상태에서 지도·마커·경로 표시 확인**(스크린샷). 마커 좌표·polyline·`fitBounds` 회귀 없음.
- 리스크: 벡터 베이스는 도로/상호 디테일이 없어 정보량↓ → 스팟 마커·경로 중심 지도엔 무방(필요 시 §13.3 래스터 오버레이 옵션).
- **증상 불일치 시**: 실제 문제가 마커 위치 오류였다면 별도 데이터 수정 필요 — TourAPI는 `mapx=경도/mapy=위도`이고 DB는 `lat=mapy, lng=mapx`로 저장(현행 정상). 스팟 좌표가 0/누락이면 그 스팟만 마커 제외 처리 권장. 이 항목은 WS-F 범위 밖.