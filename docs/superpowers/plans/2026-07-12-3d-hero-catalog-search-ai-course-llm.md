# 3D 프리미엄 히어로 · 검색 카탈로그 · AI 코스 LLM 재구성 — 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 검색 탭이 tourAPI 관광지 전체를 구·카테고리 필터로 탐색하게 하고, AI 코스를 OpenAI 기반 다중 추천으로 재구성하며, 홈 히어로 배경을 three.js 프리미엄 3D 서울 씬으로 교체한다.

**Architecture:** 백엔드(FastAPI+SQLAlchemy)는 기존 `/api/spots` 리스트에 필터를 확장하고, `app/external/openai_api.py`와 `course_service.ai_recommend_courses`로 "알고리즘 후보 큐레이션 → LLM 코스 구성 → 검증 → `_build_course` 실체화 → 실패 시 알고리즘 폴백" 파이프라인을 추가한다. 프론트(단일 `main.jsx` + `styles.css`)는 RegionScreen을 카탈로그로, AiCourseScreen을 다중 코스 결과로 재설계하고, 신규 `SeoulMap3D.jsx`(three.js)로 히어로 배경만 교체한다.

**Tech Stack:** Python 3.12 · FastAPI · SQLAlchemy 2 · pytest · React 19 · Vite 7 · three.js · openai SDK

## Global Constraints

- 색상·타이포·간격·기존 컴포넌트(Card/Button/CrowdBadge/SmartImage/RegionSpotCard 등)와 `styles.css` 토큰을 **변경하지 않는다.** 신규 UI도 기존 클래스·패턴을 재사용한다.
- 브랜드 카피 "Null crowd, Full trip" 및 히어로 스크롤 collapse·검색 오버레이·CTA는 유지한다.
- 오프라인 심사 대비 폴백은 **필수**: 3D는 WebGL 실패 시 기존 `HeroScene`으로, AI 코스는 키/네트워크 부재 시 알고리즘 다중 코스로 폴백한다.
- 백엔드 테스트는 `pytest`(데모 모드 강제, 시드 로드된 `client`/`db` fixture). 프론트는 테스트 러너가 없으므로 `npm run build` 통과 + 수동 검증으로 확인한다.
- `OPENAI_API_KEY`는 사용자가 직접 `.env`에 넣는다(코드/키 삽입 금지). 기본 모델은 `gpt-4o-mini`, `OPENAI_MODEL` env로 상향 가능.
- 신규 의존성: `openai>=1.0`(BE, `requirements.txt`), `three`(FE, `package.json`).
- 커밋 메시지는 한국어, 각 태스크 끝에서 커밋. 브랜치는 `feature/frontend` 유지.

---

## Phase A — Task 2: 검색 카탈로그

### Task A1: `GET /api/spots` — 구·카테고리 필터 + 페이지 혼잡 배지

**Files:**
- Modify: `app/routers/spots.py` (list_spots, 48–91행)
- Test: `tests/test_search_catalog.py` (create)

**Interfaces:**
- Produces: `GET /api/spots?region=&district=&category=&keyword=&page=&size=` → `{items:[SpotSummary(+level?)], total, page, size}`. `category` 값: `볼거리`(기본군 A01+A02+A03), `문화·역사`(A02), `자연·공원`(A01), `미식`(A05), `쇼핑`(A04). `district`는 addr 부분일치. `items[i].level`은 스냅샷 보유 스팟만 존재.

- [ ] **Step 1: 카테고리 매핑 상수 + 실패 테스트 작성** (`tests/test_search_catalog.py`)

```python
def test_district_filter_narrows_to_addr(client):
    resp = client.get("/api/spots", params={"district": "종로구", "size": 100})
    assert resp.status_code == 200
    items = resp.json()["items"]
    assert items and all("종로구" in (s["addr"] or "") for s in items)

def test_category_bolgeori_excludes_food_and_shopping(client):
    resp = client.get("/api/spots", params={"category": "볼거리", "size": 100})
    assert resp.status_code == 200
    cats = {s["category_name"] for s in resp.json()["items"]}
    # 볼거리군(A01/A02/A03)만 — 미식/쇼핑 대표 카테고리명이 섞이지 않는다
    assert "미식" not in cats

def test_category_food_returns_food(client):
    resp = client.get("/api/spots", params={"category": "미식", "size": 20})
    assert resp.json()["total"] > 0

def test_page_level_badge_present_for_snapshot_spots(client, visit_date):
    resp = client.get("/api/spots", params={"category": "볼거리", "size": 100})
    items = resp.json()["items"]
    # 스냅샷 보유 스팟이 페이지에 포함되면 level 키가 채워진다(없는 곳은 None)
    assert any(s.get("level") is not None for s in items)
```

- [ ] **Step 2: 실패 확인** — Run: `pytest tests/test_search_catalog.py -v` → FAIL (`district`/`category` 미지원, `level` 키 없음).

- [ ] **Step 3: list_spots에 카테고리군·구 필터 + 페이지 배지 구현**

`app/routers/spots.py` 상단에 매핑 추가:

```python
CATEGORY_CAT1_GROUPS: dict[str, tuple[str, ...]] = {
    "볼거리": ("A01", "A02", "A03"),
    "자연·공원": ("A01",),
    "문화·역사": ("A02",),
    "쇼핑": ("A04",),
    "미식": ("A05",),
}
```

`list_spots` 시그니처에 `district: str | None = Query(None)` 추가. `region`/`keyword` 처리 뒤, `category` 분기를 카테고리군 우선으로 교체:

```python
    if district:
        query = query.where(models.TouristSpot.addr.contains(district))
    if category:
        group = CATEGORY_CAT1_GROUPS.get(category)
        if group:
            query = query.where(models.TouristSpot.cat1.in_(group))
        else:
            query = query.where(or_(
                models.TouristSpot.category_name.contains(category),
                models.TouristSpot.cat1 == category,
                models.TouristSpot.cat2 == category,
                models.TouristSpot.cat3 == category,
            ))
```

`items` 산출 뒤, 페이지 스팟에만 혼잡 level을 덧붙여 dict로 반환:

```python
    from app.services.congestion_service import bulk_risks, default_visit_date
    from app.scoring.congestion import level_of
    snap_ids = set(db.scalars(select(models.CongestionSnapshot.spot_id).distinct()).all())
    page_snap = [s for s in items if s.spot_id in snap_ids]
    risks = bulk_risks(db, page_snap, default_visit_date(), "afternoon") if page_snap else {}
    payload = []
    for s in items:
        row = schemas.SpotSummary.model_validate(s).model_dump()
        row["level"] = level_of(risks[s.spot_id]) if s.spot_id in risks else None
        payload.append(row)
    return {"items": payload, "total": total, "page": page, "size": size}
```

`schemas.SpotListResponse`의 items 항목이 `level: int | None = None`을 허용하는지 확인하고, 없으면 `SpotSummary`(또는 리스트 응답용 서브스키마)에 `level: int | None = None`을 추가한다.

- [ ] **Step 4: 통과 확인** — Run: `pytest tests/test_search_catalog.py -v` → PASS. 이어 `pytest -q`로 회귀 확인(기존 `/api/spots` 테스트 깨지지 않음).

- [ ] **Step 5: 커밋**

```bash
git add app/routers/spots.py app/schemas.py tests/test_search_catalog.py
git commit -m "feat(search): /api/spots 구·카테고리 필터와 페이지 혼잡 배지 추가"
```

### Task A2: RegionScreen 카탈로그 개편(필터 + 무한스크롤)

**Files:**
- Modify: `nullnull-travel-webapp/src/main.jsx` (App의 loadRegionSpots 470–490행, RegionScreen 1752–1797행)
- Modify: `nullnull-travel-webapp/src/styles.css` (검색 필터 칩 규칙 최소 추가 — 기존 `.district-select` 재사용)

**Interfaces:**
- Consumes: `GET /api/spots?region=서울&district=&category=&page=&size=`(Task A1).
- Produces: RegionScreen이 category 상태·page 상태·무한스크롤을 갖고 누적 렌더.

- [ ] **Step 1: 카테고리 상수 + App 상태 추가**
  `SEOUL_DISTRICTS` 근처에 `const SEARCH_CATEGORIES = ['볼거리', '문화·역사', '자연·공원', '미식', '쇼핑'];` 추가.
  App에 `selectedCategory`(기본 `'볼거리'`), `regionPage`, `regionTotal`, `regionHasMore` 상태 추가.

- [ ] **Step 2: loadRegionSpots를 카탈로그 페치로 교체**

```jsx
const loadRegionSpots = async (district, category, page = 1) => {
  setRegionLoading(true);
  try {
    const params = new URLSearchParams({ region: '서울', page: String(page), size: '24' });
    if (district) params.set('district', district);
    if (category && category !== '볼거리') params.set('category', category);
    else params.set('category', '볼거리');
    const res = await apiFetch(`/api/spots?${params}`);
    setRegionSpots((prev) => (page === 1 ? res.items : [...prev, ...res.items]));
    setRegionTotal(res.total);
    setRegionPage(page);
    setRegionHasMore(page * res.size < res.total);
  } catch (e) {
    console.warn(e);
    showToast('지역 관광지를 불러오지 못했어요');
  } finally {
    setRegionLoading(false);
  }
};
```

  useEffect 의존성을 `[screen, selectedDistrict, selectedCategory]`로 바꾸고 진입/필터 변경 시 `loadRegionSpots(selectedDistrict, selectedCategory, 1)` 호출.

- [ ] **Step 3: RegionScreen에 카테고리 칩 + 더 보기 렌더**
  `district-select` 아래에 카테고리 칩 행 추가(기존 버튼 스타일 재사용):

```jsx
<div className="category-chips">
  {SEARCH_CATEGORIES.map((c) => (
    <button key={c}
      className={`category-chip ${selectedCategory === c ? 'is-active' : ''}`}
      onClick={() => onSelectCategory(c)}>{c}</button>
  ))}
</div>
```

  결과 목록 하단에 `hasMore`면 "더 보기" 버튼(→ `loadRegionSpots(district, category, page+1)`), `총 {regionTotal}곳` 표기.
  RegionScreen props에 `selectedCategory, onSelectCategory, hasMore, total, onLoadMore` 추가하고 App 렌더부(990행 부근)에서 전달.

- [ ] **Step 4: 스타일 최소 추가** — `styles.css`에 `.category-chips`(flex, gap, 가로 스크롤), `.category-chip`(기존 pill 톤·색 토큰 재사용, `.is-active`는 `--primary-deep`)만 추가. 새 색상 정의 금지.

- [ ] **Step 5: 빌드·수동 검증** — Run: `cd nullnull-travel-webapp && npm run build` → 성공. 개발 서버(`npm run dev`)에서 검색 탭: 서울 전체/구 선택/카테고리 전환/더 보기 누적/혼잡 배지 표시 확인.

- [ ] **Step 6: 커밋**

```bash
git add nullnull-travel-webapp/src/main.jsx nullnull-travel-webapp/src/styles.css
git commit -m "feat(search): 검색 탭을 카탈로그(구·카테고리 필터·무한스크롤)로 개편"
```

---

## Phase B — Task 3: AI 코스 LLM 재구성

### Task B1: OpenAI 설정 + 클라이언트 래퍼

**Files:**
- Modify: `app/config.py` (Settings), `.env.example`, `requirements.txt`
- Create: `app/external/openai_api.py`
- Test: `tests/test_openai_wrapper.py` (create)

**Interfaces:**
- Produces: `openai_api.is_llm_enabled() -> bool`; `openai_api.complete_courses(prompt_payload: dict) -> dict | None`(실패·비활성 시 None). Settings에 `openai_api_key: str = ""`, `openai_model: str = "gpt-4o-mini"`.

- [ ] **Step 1: 실패 테스트 작성**(데모/무키 환경에서 비활성·폴백을 보장)

```python
def test_llm_disabled_without_key():
    from app.external import openai_api
    # 테스트 환경엔 OPENAI_API_KEY가 없다 → 비활성
    assert openai_api.is_llm_enabled() is False

def test_complete_courses_returns_none_when_disabled():
    from app.external import openai_api
    assert openai_api.complete_courses({"conditions": {}, "candidates": []}) is None
```

- [ ] **Step 2: 실패 확인** — Run: `pytest tests/test_openai_wrapper.py -v` → FAIL(모듈 없음).

- [ ] **Step 3: Settings·의존성·래퍼 구현**
  `app/config.py` Settings에 필드 추가:

```python
    openai_api_key: str = ""
    openai_model: str = "gpt-4o-mini"
```

  `requirements.txt`에 `openai>=1.0` 추가. `.env.example`에 OpenAI 섹션 추가(키 비움).
  `app/external/openai_api.py`:

```python
"""OpenAI 기반 코스 구성 — 키/네트워크 없으면 None을 돌려 알고리즘 폴백을 트리거한다."""
import json
from app.config import get_settings

_SYSTEM = (
    "너는 '널널' 여행 큐레이터다. 반드시 주어진 후보(candidates)의 spot_id만 사용해 "
    "붐빔(risk)이 낮고 이동이 짧으며 날씨·실내외 조건에 맞는 코스를 구성한다. "
    "지어내지 말고, 응답은 지정한 JSON 스키마만 출력한다."
)

def is_llm_enabled() -> bool:
    return bool(get_settings().openai_api_key)

def complete_courses(prompt_payload: dict) -> dict | None:
    settings = get_settings()
    if not settings.openai_api_key:
        return None
    try:
        from openai import OpenAI
        client = OpenAI(api_key=settings.openai_api_key, timeout=25)
        resp = client.chat.completions.create(
            model=settings.openai_model,
            response_format={"type": "json_object"},
            temperature=0.7,
            messages=[
                {"role": "system", "content": _SYSTEM},
                {"role": "user", "content": json.dumps(prompt_payload, ensure_ascii=False)},
            ],
        )
        return json.loads(resp.choices[0].message.content)
    except Exception:
        return None
```

- [ ] **Step 4: 통과 확인** — Run: `pytest tests/test_openai_wrapper.py -v` → PASS.

- [ ] **Step 5: 커밋**

```bash
git add app/config.py app/external/openai_api.py .env.example requirements.txt tests/test_openai_wrapper.py
git commit -m "feat(ai): OpenAI 설정·클라이언트 래퍼 추가(무키 시 폴백)"
```

### Task B2: 후보 큐레이션 + `ai_recommend_courses` + 알고리즘 폴백

**Files:**
- Modify: `app/services/course_service.py`
- Test: `tests/test_ai_course.py` (create)

**Interfaces:**
- Consumes: `openai_api.complete_courses`(B1), 기존 `recommend_course`/`_build_course`/`spots_with_congestion_data`/`slot_theme_fit`/`bulk_risks`.
- Produces: `ai_recommend_courses(db, *, district, stops, companion, visit_date, time_slot, themes, pace, indoor_pref, count=3) -> tuple[list[models.Course], str]` — (코스 리스트, source) source ∈ {"llm","algorithm"}.

- [ ] **Step 1: 실패 테스트 작성**(데모 환경 → 폴백 경로가 유효 코스를 낸다)

```python
def test_ai_recommend_falls_back_to_algorithm_in_demo(client, db):
    from datetime import date, timedelta
    from app.services import course_service
    d = date.today() + timedelta(days=(5 - date.today().weekday()) % 7)
    courses, source = course_service.ai_recommend_courses(
        db, district="종로구", stops=3, companion=None, visit_date=d,
        time_slot="afternoon", themes=["역사"], pace="여유", indoor_pref="상관없음", count=3)
    assert source == "algorithm"          # 데모=무키
    assert 1 <= len(courses) <= 3
    for c in courses:
        assert len(c.items) >= 2

def test_ai_validates_llm_spot_ids(db, monkeypatch):
    # LLM이 후보에 없는 spot_id(999999)를 섞어 반환해도 검증에서 걸러진다
    from app.services import course_service
    from app.external import openai_api
    ...
```

  (두 번째 테스트는 monkeypatch로 `openai_api.complete_courses`를 후보 1개 + 가짜 id가 섞인 응답으로 대체하고, 결과 코스가 후보 id만 포함하는지 확인. count·stops 보정도 assert.)

- [ ] **Step 2: 실패 확인** — Run: `pytest tests/test_ai_course.py -v` → FAIL(함수 없음).

- [ ] **Step 3: 큐레이션·검증·폴백 구현**
  `course_service.py`에 추가(핵심 골격):

```python
def _curate_candidates(db, *, district, themes, visit_date, time_slot, limit=24):
    q = select(models.TouristSpot).where(models.TouristSpot.region.contains("서울"))
    if district:
        q = q.where(models.TouristSpot.addr.contains(district))
    pool = [s for s in db.scalars(q).all() if s.spot_id in spots_with_congestion_data(db)]
    if themes:
        pool = [s for s in pool if any(slot_theme_fit(s, t) > 0 for t in themes)] or pool
    if len(pool) < 8:  # 후보 부족 시 볼거리 전체로 완화
        pool = [s for s in db.scalars(q).all()]
    risks = bulk_risks(db, pool, visit_date, time_slot)
    pool.sort(key=lambda s: risks.get(s.spot_id, 1.0))   # 널널한 순
    return pool[:limit], risks

def ai_recommend_courses(db, *, district, stops, companion, visit_date, time_slot,
                         themes, pace, indoor_pref, count=3):
    pool, risks = _curate_candidates(db, district=district, themes=themes,
                                     visit_date=visit_date, time_slot=time_slot)
    if not pool:
        raise NoSlotCandidateError("이 조건에서 추천할 장소를 찾지 못했어요.")
    payload = _build_llm_payload(db, pool, risks, district=district, stops=stops,
                                 companion=companion, visit_date=visit_date,
                                 themes=themes, pace=pace, indoor_pref=indoor_pref, count=count)
    raw = openai_api.complete_courses(payload)
    courses = _materialize_llm_courses(db, raw, pool, visit_date, time_slot,
                                       companion, stops) if raw else []
    if courses:
        return courses, "llm"
    return _algorithmic_multi_courses(db, pool, stops, companion, visit_date,
                                      time_slot, themes, count), "algorithm"
```

  - `_build_llm_payload`: 후보를 `{spot_id,name,category,lat,lng,risk,is_indoor,tags}`로 축약 + 조건 + `count`·`stops`를 dict로.
  - `_materialize_llm_courses`: `raw["courses"]`를 순회, 각 `stops`의 `spot_id`가 pool에 있는 것만 취해 순서 유지, 중복 제거, `stops`개로 절단(부족하면 pool 상위로 보충), 첫 스팟을 origin·나머지를 ordered로 `_build_course(mode="free", slot_themes=...)` 호출. title/description에 LLM `title`/`concept`/`reason` 반영. 유효 코스만 반환.
  - `_algorithmic_multi_courses`: pool 상위 스팟들을 origin 후보로 삼아 `recommend_course`를 `variation_seed=f"ai-{i}"`로 count개 생성(테마 시퀀스는 themes→기본 시퀀스 매핑).

- [ ] **Step 4: 통과 확인** — Run: `pytest tests/test_ai_course.py -v` → PASS. `pytest -q` 회귀 확인.

- [ ] **Step 5: 커밋**

```bash
git add app/services/course_service.py tests/test_ai_course.py
git commit -m "feat(ai): 후보 큐레이션·LLM 코스 검증·알고리즘 폴백 구현"
```

### Task B3: `POST /api/courses/ai-recommend` 엔드포인트 + 스키마

**Files:**
- Modify: `app/routers/courses.py`, `app/schemas.py`
- Test: `tests/test_ai_course.py` (append)

**Interfaces:**
- Consumes: `course_service.ai_recommend_courses`(B2), `course_service.course_detail`.
- Produces: `POST /api/courses/ai-recommend` body `AiCourseRequest{district, stops, companion?, date?, time_slot, themes:[], pace, indoor_pref}` → `AiCourseResponse{source: str, courses: [CourseDetail]}`.

- [ ] **Step 1: 실패 테스트 작성**

```python
def test_ai_recommend_endpoint_returns_multiple_courses(client, visit_date):
    body = {"district": "종로구", "stops": 3, "date": visit_date,
            "time_slot": "afternoon", "themes": ["역사"], "pace": "여유",
            "indoor_pref": "상관없음"}
    resp = client.post("/api/courses/ai-recommend", json=body)
    assert resp.status_code == 201
    data = resp.json()
    assert data["source"] in ("llm", "algorithm")
    assert 1 <= len(data["courses"]) <= 3
    assert data["courses"][0]["course_id"]
```

- [ ] **Step 2: 실패 확인** — Run: `pytest tests/test_ai_course.py::test_ai_recommend_endpoint_returns_multiple_courses -v` → FAIL(404).

- [ ] **Step 3: 스키마·라우트 구현**
  `app/schemas.py`에 `AiCourseRequest`(필드·기본값·`Companion`/`SlotTheme` 재사용, `stops: int = Field(3, ge=2, le=5)`, `themes: list[str] = []`, `pace: Literal["여유","보통"]="여유"`, `indoor_pref: Literal["상관없음","실내","실외"]="상관없음"`)와 `AiCourseResponse{source: str, courses: list[CourseDetail]}` 추가.
  `app/routers/courses.py`:

```python
@router.post("/ai-recommend", response_model=schemas.AiCourseResponse, status_code=201)
def ai_recommend(body: schemas.AiCourseRequest, db: Session = Depends(get_db)):
    visit_date = body.date or default_visit_date()
    validate_visit_date(visit_date)
    courses, source = course_service.ai_recommend_courses(
        db, district=body.district, stops=body.stops, companion=body.companion,
        visit_date=visit_date, time_slot=body.time_slot, themes=body.themes,
        pace=body.pace, indoor_pref=body.indoor_pref, count=3)
    return {"source": source,
            "courses": [course_service.course_detail(db, c) for c in courses]}
```

  (`default_visit_date`/`validate_visit_date` import 추가.)

- [ ] **Step 4: 통과 확인** — Run: `pytest tests/test_ai_course.py -v` → PASS. `pytest -q` 회귀 확인.

- [ ] **Step 5: 커밋**

```bash
git add app/routers/courses.py app/schemas.py tests/test_ai_course.py
git commit -m "feat(ai): POST /api/courses/ai-recommend 다중 코스 엔드포인트 추가"
```

### Task B4: AiCourseScreen 재설계(풍부한 조건 + 다중 코스 결과)

**Files:**
- Modify: `nullnull-travel-webapp/src/main.jsx` (AiCourseScreen 1806–1929행, createAiCourse 804–850행 부근, App 렌더 1000행 부근)
- Modify: `nullnull-travel-webapp/src/styles.css` (조건 폼·코스 카드 최소 규칙 — 기존 `.ai-*` 재사용)

**Interfaces:**
- Consumes: `POST /api/courses/ai-recommend`(B3), 기존 `CourseScreen`·`GET /api/courses/{id}`.

- [ ] **Step 1: 조건 상수 추가**
  `AI_DURATIONS` 옆에 `AI_THEMES=['역사','자연','미식','포토스팟','쇼핑','힐링']`, `AI_TIMESLOTS=[{k:'morning',l:'오전'},{k:'afternoon',l:'오후'},{k:'evening',l:'저녁'}]`, `AI_PACE=['여유','보통']`, `AI_INDOOR=['상관없음','실내','실외']` 추가.

- [ ] **Step 2: createAiCourse를 신규 엔드포인트로 교체**

```jsx
const createAiCourse = async (cond) => {
  setCourseCreating(true);
  try {
    const res = await apiFetch('/api/courses/ai-recommend', {
      method: 'POST',
      body: JSON.stringify({
        district: cond.district, stops: cond.stops, companion: cond.companion || null,
        date: cond.date, time_slot: cond.timeSlot, themes: cond.themes,
        pace: cond.pace, indoor_pref: cond.indoor,
      }),
    });
    setAiResults(res);              // {source, courses:[CourseDetail]}
    res.courses.forEach(rememberCourse);
    refreshImpact();
  } catch (e) {
    console.warn(e);
    showToast(e.message ?? 'AI 코스를 만들지 못했어요');
  } finally { setCourseCreating(false); }
};
```

  App에 `aiResults` 상태 추가, AiCourseScreen에 `results`·`onOpenCourse` 전달.

- [ ] **Step 3: 조건 폼 확장 렌더**
  기존 `ai-form-card`에 필드 추가(모두 기존 `.ai-field`/`.ai-duration`/`.district-select` 스타일 재사용): 시작 시간대(칩), 관심 테마(다중 토글 칩), 페이스(칩), 실내외(칩). 제출 시 `stops = AI_DURATIONS.find(...).stops`.

- [ ] **Step 4: 다중 코스 결과 렌더**
  `results`가 있으면 조건 카드 아래에 코스 카드 리스트: 각 코스 `title`·설명·스팟 미리보기·`onClick={() => onOpenCourse(course.course_id)}`(기존 `region-spot-card`/`PopularCourseCard` 톤 재사용). `results.source === 'algorithm'`이면 "널널 알고리즘 추천" 배지, `'llm'`이면 "AI 추천" 배지.

- [ ] **Step 5: 빌드·수동 검증** — Run: `cd nullnull-travel-webapp && npm run build` → 성공. 개발 서버에서 AI 코스 탭: 조건 다중 선택 → 추천 → 코스 3개 카드 → 카드 탭 시 코스 상세 진입. 무키 환경이라 "널널 알고리즘 추천" 배지 확인.

- [ ] **Step 6: 커밋**

```bash
git add nullnull-travel-webapp/src/main.jsx nullnull-travel-webapp/src/styles.css
git commit -m "feat(ai): AI 코스 탭을 풍부한 조건·다중 코스 결과로 재설계"
```

---

## Phase C — Task 1: 3D 프리미엄 히어로

### Task C1: three 의존성 + 서울 자치구 GeoJSON 에셋

**Files:**
- Modify: `nullnull-travel-webapp/package.json`
- Create: `nullnull-travel-webapp/src/assets/seoul-districts.geo.json`

- [ ] **Step 1: three 설치** — Run: `cd nullnull-travel-webapp && npm install three` → `package.json` dependencies에 `three` 추가.

- [ ] **Step 2: 서울 자치구 경계 GeoJSON 확보·경량화**
  공개 서울 자치구(시군구) 경계 GeoJSON을 받아 좌표를 단순화(자치구 25개, 목표 <50KB)해 `src/assets/seoul-districts.geo.json`에 저장한다. 각 feature에 구 이름(`SIG_KOR_NM` 또는 `name`) 속성 유지. 확보 난항 시 대체안: 자치구 중심점 25개로 추상 배치(플랜 리스크 절 참조).
  검증: Node로 파싱해 `features.length === 25`, 파일 크기 확인.

- [ ] **Step 3: 커밋**

```bash
git add nullnull-travel-webapp/package.json nullnull-travel-webapp/package-lock.json nullnull-travel-webapp/src/assets/seoul-districts.geo.json
git commit -m "chore(hero): three 의존성·서울 자치구 GeoJSON 에셋 추가"
```

### Task C2: `SeoulMap3D` 컴포넌트(three.js 씬 + 폴백)

**Files:**
- Create: `nullnull-travel-webapp/src/SeoulMap3D.jsx`

**Interfaces:**
- Consumes: `seoul-districts.geo.json`, props `{ spots: [{lat,lng,level,name}] }`.
- Produces: `export default function SeoulMap3D({ spots })` — WebGL 캔버스. WebGL 미지원/초기화 실패 시 `null` 반환(상위에서 HeroScene 폴백).

- [ ] **Step 1: 컴포넌트 골격 작성**(핵심 구조)

```jsx
import { useEffect, useRef, useState } from 'react';
import * as THREE from 'three';
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';
import { EffectComposer } from 'three/examples/jsm/postprocessing/EffectComposer.js';
import { RenderPass } from 'three/examples/jsm/postprocessing/RenderPass.js';
import { UnrealBloomPass } from 'three/examples/jsm/postprocessing/UnrealBloomPass.js';
import districts from './assets/seoul-districts.geo.json';

const LEVEL_COLOR = { 0: 0x6fd08c, 1: 0xd8c65a, 2: 0xe08b4c, 3: 0xd85c5c };

function webglOK() {
  try { return !!document.createElement('canvas').getContext('webgl'); }
  catch { return false; }
}

export default function SeoulMap3D({ spots = [] }) {
  const mountRef = useRef(null);
  const [supported] = useState(webglOK);
  useEffect(() => {
    if (!supported || !mountRef.current) return;
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    const mobile = window.innerWidth < 640;
    // 1) scene/camera/renderer(ACESFilmicToneMapping, dpr 상한), fog
    // 2) districts → 경위도를 평면 좌표로 정규화 → ExtrudeGeometry(얕은 두께),
    //    발광 그린 재질(MeshStandardMaterial emissive)
    // 3) lights: ambient + directional(key) + point
    // 4) spots → 발광 핀(SphereGeometry), LEVEL_COLOR[level]
    // 5) OrbitControls autoRotate(reduce면 정지), enableZoom=false
    // 6) EffectComposer + UnrealBloom(mobile이면 비활성)
    // 7) resize 핸들러, cleanup에서 renderer.dispose()·mount 비우기
    return () => {/* dispose */};
  }, [supported, spots]);
  if (!supported) return null;              // → 상위에서 HeroScene 폴백
  return <div ref={mountRef} className="seoul-map-3d" aria-hidden="true" />;
}
```

  주석 1~7의 실제 three 코드를 채운다(경위도 정규화: features의 min/max lon·lat로 [-1,1] 스케일, ExtrudeGeometry로 각 폴리곤 압출).

- [ ] **Step 2: 빌드 확인** — Run: `cd nullnull-travel-webapp && npm run build` → 성공(트리 셰이킹·jsm import 경로 정상).

- [ ] **Step 3: 커밋**

```bash
git add nullnull-travel-webapp/src/SeoulMap3D.jsx
git commit -m "feat(hero): three.js 프리미엄 서울 3D 씬 컴포넌트 추가"
```

### Task C3: 히어로 통합 + 폴백 + 스타일

**Files:**
- Modify: `nullnull-travel-webapp/src/main.jsx` (HomeScreen 히어로 렌더부, HeroScene 사용처)
- Modify: `nullnull-travel-webapp/src/styles.css` (`.seoul-map-3d` 캔버스 배치 규칙)

**Interfaces:**
- Consumes: `SeoulMap3D`(C2), 기존 `homeSpots`(lat/lng/level 포함).

- [ ] **Step 1: 히어로 배경 교체 + 폴백 래퍼**
  HomeScreen에서 `HeroScene` 배경 자리에 `SeoulMap3D`를 렌더하되, WebGL 미지원(`SeoulMap3D`가 null)일 때를 위해 래핑:

```jsx
function HeroBackground({ spots }) {
  const scene = <SeoulMap3D spots={spots} />;
  return scene ?? <HeroScene />;   // SeoulMap3D는 미지원 시 null 반환
}
```

  (컴포넌트가 항상 엘리먼트를 반환하므로, 실제로는 `SeoulMap3D` 내부 `supported` 상태로 폴백을 렌더하도록 조정 — `supported`가 false면 `<HeroScene/>`를 반환하게 하거나, HomeScreen에서 `webglOK()`를 한 번 체크해 분기. 스크롤 collapse·오버레이·CTA·카피는 기존 마크업 그대로 유지.)
  `homeSpots`(lat/lng/level 포함)를 spots로 전달.

- [ ] **Step 2: 스타일 추가** — `styles.css`에 `.seoul-map-3d`(히어로 내부 절대배치, `inset:0`, `z-index`는 기존 히어로 콘텐츠보다 아래, `pointer-events:none`)만 추가. 기존 히어로 규칙·색 토큰 유지.

- [ ] **Step 3: 빌드·수동 검증** — Run: `cd nullnull-travel-webapp && npm run build` → 성공. 개발 서버에서 홈: 3D 서울 씬 배경 + 스팟 핀(혼잡 색) + 느린 회전, 검색바·CTA·카피 정상. reduce-motion에서 정지, 모바일 폭에서 블룸 off·정상 렌더, (임시로 WebGL 비활성 시) HeroScene 폴백 확인.

- [ ] **Step 4: 커밋**

```bash
git add nullnull-travel-webapp/src/main.jsx nullnull-travel-webapp/src/styles.css
git commit -m "feat(hero): 홈 히어로 배경을 3D 서울 씬으로 교체(폴백 유지)"
```

---

## Self-Review 결과(스펙 대비)

- **스펙 커버리지:** Task 2(A1 백엔드·A2 프론트), Task 3(B1 설정/래퍼·B2 큐레이션/폴백·B3 엔드포인트·B4 프론트), Task 1(C1 의존성/에셋·C2 컴포넌트·C3 통합) — 스펙 §2~§4 전부 대응. 오프라인 폴백(§5)은 B1/B2·C2/C3에 명시.
- **타입 일관성:** `ai_recommend_courses`(B2)의 시그니처를 B3 라우트가 그대로 호출, `AiCourseRequest`/`AiCourseResponse`(B3) 필드를 B4 프론트가 동일 키로 전송. `SeoulMap3D({spots})`(C2)를 C3가 동일 prop으로 소비. `level` 배지 키(A1)를 A2가 소비.
- **열린 항목(구현 중 확정):** facet 카운트(스펙에서 1차 선택), LLM 스팟별 note 저장 필드 유무(있으면 course_item, 없으면 description 흡수), 서울 GeoJSON 최종 소스.
