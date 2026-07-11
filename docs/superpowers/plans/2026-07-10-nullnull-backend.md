# 널널(Nullnull) 백엔드 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> 이 세션은 자율(goal) 모드로, 같은 세션에서 인라인 실행한다. git 저장소 루트가 사용자 홈 디렉터리이므로 커밋은 생략한다(사용자 요청 시 별도 진행).

**Goal:** 기획서(과제2_널널_웹앱구현_기획서_Final.md)와 FE 초안(nullnull-travel-webapp)의 데이터 계약을 완전히 충족하는 FastAPI 백엔드를 구현한다.

**Architecture:** FastAPI 단일 API 서버 + SQLAlchemy(개발/데모는 SQLite, 운영은 `DATABASE_URL`로 PostgreSQL) + 규칙 기반 스코어링 3종(널널도·대안지·코스) + EWMA 피드백 보정 + 일배치(CLI). 관광공사 OpenAPI 5종은 키가 있으면 배치로 수집하고, 키가 없으면 시드 스냅샷 기반 데모 모드로 완전 동작(기획서 10-1 `?demo=true`·17장 콜드스타트 대응).

**Tech Stack:** Python 3.14, FastAPI, SQLAlchemy 2.0, Pydantic v2, httpx, PyYAML, pytest

## Global Constraints (기획서 원문 준수)

- 한국관광공사 OpenAPI 5종 필수 활용: 국문 관광정보(TourAPI KorService2), 관광지 집중률 방문자 추이 예측, 빅데이터 지역별 방문자수, 관광지별 연관 관광지(TarRlteTarService), 지역별 관광 수요 강도 — 클라이언트 전부 구현, 키 없으면 graceful skip
- 보조 API는 전부 무료: 기상청 단기예보(공공데이터포털), 서울 실시간 도시데이터, 카카오모빌리티 길찾기(무료 쿼터) — 전부 선택적(키 없으면 폴백)
- 날짜 선택은 **오늘 ~ +30일**만 허용, 초과·과거는 **400 + "예측 데이터는 향후 30일까지 제공됩니다."** (8-1)
- 널널도 산식(9-1): `0.55×집중률예측 + 0.20×지역방문자상대지수 + 0.15×수요강도 + 0.10×요일/공휴일/날씨보정`, 결측 항은 제외 후 **가중치 재정규화(합=1.0)**
- 대안지 산식(9-2): `0.30×테마유사도 + 0.25×혼잡완화 + 0.15×이동편의 + 0.10×숨은명소성 + 0.10×날씨적합 − 0.10×추천부하`
  - 테마유사도 = `0.6×Jaccard(카테고리코드집합) + 0.4×연관API similarity(정규화)`, 둘 다 없으면 키워드 매칭 폴백
  - 숨은명소성 = 방문자수 하위 분위 × 콘텐츠 풍부도(이미지 수 + overview 길이 + 연관 목록 등장 횟수 정규화 합)
  - 추천부하 = 최근 7일 `노출 + 선택×2` 0~1 정규화, **로그 없으면 0(콜드스타트 안전)**
- 코스 산식(9-3): `평균 AlternativeScore − 이동시간 패널티 − 동일 카테고리 반복 패널티 + 지역 분산 보너스`
- 피드백 보정(9-4): `adjusted = risk × (1 + 0.2 × EWMA(최근 30건 perceived))`, **30건 미만 장소는 미적용**, 일배치로 `spot_score_daily` 반영
- 가중치는 코드가 아닌 **`weights.yaml`로 외부화** (9-5)
- 합성 시드는 **`is_seed=true`로 구분 저장**, 임팩트 집계에서 제외 (9-2·11장)
- 널널도 5단계: 0~20 매우 널널(초록) / 21~40 널널 / 41~60 보통 / 61~80 붐빔 / 81~100 매우 붐빔
- 시연 지역: 서울 집중 MVP(기본안 권장)

## FE 데이터 계약 (nullnull-travel-webapp/src/main.jsx 기준)

FE 4개 화면이 소비할 필드(현재 하드코딩)를 응답에 그대로 담는다:

| 화면 | 필요 데이터 | 엔드포인트 |
|---|---|---|
| 홈 | 임팩트 카운터(평균 혼잡 회피율 %, 숨은 명소 추천 수), 인기 코스 3개(title, location, image, rate, time, tag, level) | `GET /api/impact/summary`, `GET /api/courses/popular` |
| 상세 | 스팟 정보, 널널도(level 1~5·label·팁), 요일별 오전/오후/저녁 차트(7일), 시간대 카드 3개, 리뷰 proof(평점·건수) | `GET /api/spots/{id}`, `GET /api/spots/{id}/congestion` |
| 대안 코스 | 원 관광지(이름·혼잡%), 대안 카드(title, image, decrease%, move분, similarity%, level, reason), 경로 요약(km·차량분·도보km) | `GET /api/spots/{id}/alternatives` |
| 코스 상세 | 타임라인(place, meta, move, note), 요약(혼잡감소%·테마유지%·이동분), 임팩트 카드, 1탭 피드백, 별점+태그+텍스트 후기, 최근 후기 | `GET /api/courses/{id}`, `POST /api/feedback`, `POST/GET /api/reviews` |

## 파일 구조

```
app/
  main.py            # 앱 팩토리, CORS(Vite 5173), 라우터 등록, 시작 시 테이블 생성+자동 시드
  config.py          # pydantic-settings: DATABASE_URL, 각 API 키(전부 옵션), DEMO_MODE, ADMIN_TOKEN, KR 공휴일
  database.py        # engine/SessionLocal/Base, get_db
  models.py          # ORM 13개 테이블 (기획서 11장 + region_stat_daily + visit_review)
  schemas.py         # Pydantic 요청/응답 (FE 계약)
  routers/spots.py courses.py feedback.py reviews.py impact.py admin.py
  scoring/weights.py congestion.py alternative.py course.py feedback_adjust.py
  services/congestion_service.py recommend_service.py course_service.py impact_service.py
  external/base.py tour_api.py tats_api.py datalab_api.py related_api.py demand_api.py kma_api.py seoul_api.py kakao_api.py
  batch/daily.py     # python -m app.batch.daily (①수집 ②피드백보정 ③spot_score_daily 재계산, api_ingest_log 기록)
  batch/seed.py      # python -m app.batch.seed (서울 MVP 시드)
  seed_data.py       # 서울 대표 명소 18곳+연관 엣지+스냅샷 생성기(결정적)
tests/conftest.py test_scoring.py test_congestion_api.py test_alternatives_api.py test_courses_api.py test_feedback_reviews.py test_impact_admin.py
weights.yaml requirements.txt .env.example README.md
```

## DB 테이블 (기획서 11장 + 보강 2개)

`tourist_spot`(spot_id PK, content_id, name, region, area_code, sigungu_code, cat1, cat2, cat3, category_name, tags JSON, addr, lat, lng, image_url, image_count, overview, overview_len, is_indoor, base_popularity), `congestion_snapshot`(id, spot_id FK, date, time_slot, congestion_score, visitor_count, source, collected_at), `related_spot`(spot_id, related_spot_id, similarity_score), `course`(course_id, title, description, region, base_spot_id, date, level, relief_pct, theme_keep_pct, total_move_min, total_distance_km, is_seed, created_at), `course_item`(id, course_id, spot_id, order_no, stay_min, move_min, move_mode, reason_text), `spot_score_daily`(id, spot_id, date, time_slot, congestion_risk, adjusted_risk, updated_at), `recommendation_evidence`(id, course_id, spot_id, theme_sim, relief_effect, travel_time, hidden_score), `recommendation_log`(id, spot_id, origin_spot_id, exposed_at, selected, is_seed), `visit_feedback`(id, course_id, spot_id, perceived, is_seed, created_at), `visit_review`(id, course_id, spot_id, nickname, rating, tags JSON, text, created_at) ※FE 후기 UI 지원, `api_ingest_log`(id, api_name, status, records, last_synced_at, error_message), `region_stat_daily`(id, area_code, date, visitor_count, visitor_index, demand_intensity, source) ※산식 20%·15% 항 데이터 소스, `app_user`(user_id, nickname) ※MVP 익명(선택 테이블, 미사용)

## REST API (기획서 12장 + FE 필요분)

| Method | Endpoint | 비고 |
|---|---|---|
| GET | `/api/health` | {status, demo_mode} |
| GET | `/api/spots?region=&category=&keyword=&page=&size=` | 캐시된 TourAPI 데이터 |
| GET | `/api/spots/{id}` | 상세+리뷰 proof 집계+hidden_gem |
| GET | `/api/spots/{id}/congestion?date=&time_slot=` | 널널도+±5일 요일 비교(11개)+시간대 3개, 창 초과 400 |
| GET | `/api/spots/{id}/alternatives?date=&time_slot=&themes=&limit=` | AlternativeScore 정렬, 노출 로그 기록, route_summary 포함 |
| POST | `/api/courses` | {origin_spot_id, spot_ids[], date, time_slot?, title?} 선택 로그 기록 |
| GET | `/api/courses/{id}` | 타임라인+근거+임팩트 카드+후기 |
| GET | `/api/courses/popular?limit=` | FE 홈 캐러셀 |
| POST | `/api/feedback` | {spot_id, course_id?, perceived∈{-1,0,1}} |
| POST | `/api/reviews` / GET `/api/reviews?spot_id=|course_id=` | 별점+태그+텍스트 |
| GET | `/api/impact/summary` | 주간 집계, is_seed 제외 |
| POST | `/api/admin/seed` | X-Admin-Token, 합성 로그 주입(is_seed=true) |
| GET | `/api/admin/ingest-log` | X-Admin-Token, 수집 상태+추천 부하 분포 |

## 시드 데이터 (서울 MVP, 데모 시나리오 6장 재현)

- 과밀 명소: 경복궁(base 88), 명동거리(85), N서울타워(80), 북촌한옥마을(78), 홍대거리(76), 창덕궁(66), 덕수궁(58)
- 대안지(역사): 서울한양도성 낙산구간(30), 백인제가옥(22), 운현궁(26), 경희궁(28), 성균관 명륜당(24), 국립민속박물관(45)
- 대안지(자연/포토/미식): 길상사(25), 낙산공원(32), 문래창작촌(29), 서촌 통인시장(38), 익선동 골목(48), 서울숲(52)
- 스냅샷 생성: 오늘~+30일 × 3시간대, `score = clamp(base × weekday_factor(월0.75~토1.35) × slot_factor(오전0.78/오후1.0/저녁0.86) × holiday(1.25) + jitter(spot,date 해시 결정적 ±4))`
- region_stat_daily: 서울(areaCode=1) 31일치, related_spot 엣지(경복궁↔낙산구간 0.82 등), 합성 로그·피드백·리뷰 시드(is_seed=true)

---

### Task 1: 스캐폴드 + 설정 + health
**Files:** requirements.txt, .env.example, weights.yaml, app/{__init__,config,database,main}.py, tests/{conftest.py,test_health.py}
- [x] venv 생성, 의존성 설치(fastapi, uvicorn, sqlalchemy, pydantic-settings, httpx, PyYAML, pytest)
- [x] `weights.yaml`: congestion_risk{concentration:0.55, region_visitor:0.20, demand:0.15, calendar_weather:0.10}, alternative{theme:0.30, relief:0.25, mobility:0.15, hidden:0.10, weather:0.10, load_penalty:0.10}, course{move_penalty_per_10min:0.02, category_repeat_penalty:0.05, dispersion_bonus:0.05}, feedback{alpha:0.2, min_count:30, ewma_span:10}, load{window_days:7, select_weight:2}
- [x] `GET /api/health` → 200 {status:"ok", demo_mode:true} 테스트 통과

### Task 2: 모델 13종 + 시드
**Files:** app/models.py, app/seed_data.py, app/batch/seed.py, tests/test_seed.py
- [x] 위 스키마대로 ORM 작성, `python -m app.batch.seed`로 스팟 ≥18, 스냅샷 = 스팟수×31일×3슬롯, related ≥20엣지, 31일 region_stat, 합성 로그(is_seed) 적재 검증

### Task 3: 스코어링 패키지 (TDD)
**Files:** app/scoring/*.py, tests/test_scoring.py
- [x] `level_of(risk)` 경계값(0,20,21,40,41,60,61,80,81,100) 테스트 → 구현
- [x] `renormalize(weights, available_keys)` 합=1.0 테스트 → 구현
- [x] `congestion_risk(concentration, region_visitor_idx, demand, calendar_weather)` — None 항 재정규화 테스트 → 구현
- [x] `ewma_bias(perceived_list, span)`·`adjusted_risk(risk, bias, alpha)`·30건 미만 미적용 테스트 → 구현
- [x] `theme_similarity(cats_a, cats_b, related_sim)` Jaccard 결합·폴백 테스트 → 구현
- [x] `hidden_gem_score(visitor_pctl, content_richness)`·`recommendation_load(exposures, selections, max)` 콜드스타트 0 테스트 → 구현
- [x] `alternative_score(...)` 가중 합산·날씨 결측 재정규화 테스트 → 구현
- [x] `course_score(alt_scores, total_move_min, category_repeats, distinct_areas)` 테스트 → 구현

### Task 4: 널널도 서비스 + 스팟 API
**Files:** app/services/congestion_service.py, app/routers/spots.py, app/schemas.py, tests/test_congestion_api.py
- [x] date 검증(과거·+30 초과 → 400 메시지), spot_score_daily 캐시 우선 → 스냅샷 산출 폴백, 피드백 보정 적용 플래그, ±5일 비교 11개, 시간대 3개, 팁 문구 생성
- [x] `GET /api/spots`(region/category/keyword 필터), `GET /api/spots/{id}`(review proof 포함)

### Task 5: 대안지 추천 + 노출 로그
**Files:** app/services/recommend_service.py, app/routers/spots.py(추가), tests/test_alternatives_api.py
- [x] 후보 = related_spot ∪ 동일 cat2 스팟(자기 제외), 각 항 산출→정렬→상위 N, 하버사인 거리→이동시간 추정(카카오 키 있으면 실경로), reason 템플릿(기획서 6장 문구 스타일), recommendation_log(exposed) 기록, route_summary 계산
- [x] 검증: 경복궁 대안에 역사 테마 대안 포함, decrease>0, 노출 로그 증가, 추천부하 페널티로 반복 노출 시 순위 변동

### Task 6: 코스 생성/조회/인기
**Files:** app/services/course_service.py, app/routers/courses.py, tests/test_courses_api.py
- [x] POST: nearest-neighbor 동선 정렬, stay_min(카테고리별 기본), move_min(하버사인/카카오), evidence 저장, relief/theme_keep/total_move 계산, 선택 로그(selected=true), 제목·설명·노트 템플릿
- [x] GET 상세: 타임라인+근거+임팩트 카드 문구, popular: relief 상위 비시드 코스(없으면 시드 코스)

### Task 7: 피드백 + 후기
**Files:** app/routers/feedback.py, app/routers/reviews.py, tests/test_feedback_reviews.py
- [x] perceived 검증, 저장, 30건+배치 후 adjusted_risk 변화 검증, 리뷰 CRUD(작성·최근목록)와 proof 집계

### Task 8: 임팩트 + 관리자
**Files:** app/services/impact_service.py, app/routers/impact.py, app/routers/admin.py, tests/test_impact_admin.py
- [x] 주간 집계(비시드만): avg relief, 숨은 명소 선택 수, 생성 코스 수; admin seed 주입(is_seed=true), ingest-log+부하 분포, X-Admin-Token 401 검증

### Task 9: 외부 API 클라이언트 + 일배치
**Files:** app/external/*.py, app/batch/daily.py
- [x] 공공데이터포털 공통 클라이언트(serviceKey·_type=json·타임아웃·에러핸들), 5종+기상청+서울+카카오 클라이언트(키 없으면 None 반환), daily 배치: 수집→api_ingest_log(성공/실패/skipped)→피드백 보정→spot_score_daily 재계산. 키 없이 실행해도 배치가 성공(전부 skipped)하고 점수 재계산되는지 검증

### Task 10: 통합 검증 + 문서
**Files:** README.md, .env.example
- [x] 전체 pytest 통과, uvicorn 기동 후 데모 시나리오 curl 흐름(경복궁 혼잡 조회→400 방어→대안→코스 생성→피드백→임팩트) 검증, README(실행법·API 명세·산식·환경변수·FE 연동 가이드) 작성

## Self-Review 체크
- 기획서 12장 엔드포인트 10종 전부 매핑 ✓ (+FE용 popular/reviews/health 추가)
- 9-1~9-4 산식 원문 계수 그대로, weights.yaml 외부화 ✓
- 8-1 30일 창 400 방어 ✓, F8 콜드스타트 load=0 ✓, is_seed 구분·집계 제외 ✓
- FE 4개 화면 하드코딩 필드 전부 대응 필드 존재 ✓ (rate/decrease/similarity/move/level/reason/timeline/summary/proof)
