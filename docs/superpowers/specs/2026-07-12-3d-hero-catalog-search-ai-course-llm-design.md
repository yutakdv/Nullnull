# 널널(Nullnull) — 3D 프리미엄 히어로 · 검색 카탈로그 · AI 코스 LLM 재구성 설계

> 작성일 2026-07-12 · 브랜치 `feature/frontend` · 마감 2026-07-21(접수)
> 색상·템플릿·기존 컴포넌트(Card/Button/CrowdBadge/SmartImage 등)는 **변경하지 않는다.** 신규 화면·기능도 기존 디자인 시스템을 그대로 따른다.

## 1. 개요 / 목표

세 가지 독립 기능을 한 스펙으로 묶되, 각 기능은 별도로 구현·검증 가능하다.

- **Task 1 — 홈 3D 프리미엄 히어로:** 현재 CSS/SVG 레이어드 씬(`HeroScene`)의 배경을, three.js로 만든 **시네마틱한 서울 3D 씬**으로 대체한다. 정지 렌더 이미지 같은 고급스러움을 내되 살아있고, 서비스 컨셉(혼잡 회피)에 맞춰 추천 스팟을 혼잡도 색상 핀으로 얹는다.
- **Task 2 — 검색 카탈로그 개편:** DB에 이미 있는 tourAPI 관광지 2,133곳(25개 구 전부 분포)이 실제로 탐색되도록, 검색 탭을 큐레이션용 `/api/spots/home`에서 카탈로그용 `/api/spots`로 전환하고 **구·카테고리 필터 + 무한스크롤**을 붙인다. 볼거리 중심.
- **Task 3 — AI 코스 LLM 재구성:** 조건 선택을 풍부하게 하고, 알고리즘이 혼잡·날씨로 후보 스팟을 큐레이션한 뒤 **OpenAI가 동선·조건을 고려해 여러(기본 3개) 최적 코스를 구성**하도록 재설계한다. 키 없음/오프라인 시 알고리즘 다중 코스로 폴백.

### 비목표(Non-goals)
- 지도 타일 공급자 교체(Kakao 등)는 별개 과제로 유지 — 상세 화면 `LeafletPointsMap`은 손대지 않는다.
- tourAPI 재수집(배치 확장)은 하지 않는다. 기존 2,133곳으로 충분.
- 인증·결제·다국어는 범위 밖.

## 2. Task 1 — 홈 3D 프리미엄 히어로 (`SeoulMap3D`)

### 2.1 구성
- **신규 파일** `nullnull-travel-webapp/src/SeoulMap3D.jsx` — three.js 씬을 캡슐화한 단일 React 컴포넌트. main.jsx(3,180줄)를 더 키우지 않고 격리한다. R3F는 쓰지 않는다(React 19 호환·번들 경량).
- **의존성:** `three` 를 `package.json`에 추가. 포스트프로세싱은 three 내장 `examples/jsm`(UnrealBloomPass, EffectComposer) 사용.
- **데이터(지오메트리):** 서울 25개 자치구 경계 GeoJSON 간소화본을 `src/assets/seoul-districts.geo.json`으로 번들(목표 <50KB, 좌표 단순화). 공개 데이터에서 파생.
- **데이터(핀):** 기존 `/api/spots/home?region=서울&limit=…`(lat/lng·level·label 포함)을 재사용. `level`로 색상 매핑(널널=그린 / 보통=옐로 / 붐빔=오렌지·레드).

### 2.2 렌더링(프리미엄 룩)
- 자치구 폴리곤을 `ExtrudeGeometry`로 입체화(얕은 두께), 브랜드 그린(#3d8567) 계열 + 발광/글래스 재질.
- ACES 필름 톤매핑, 앰비언트 + 디렉셔널(키라이트) + 포인트 라이트, 은은한 **블룸(UnrealBloomPass)**, 소프트 **안개(fog)**로 깊이감. 천천히 도는 카메라(오토로테이트).
- 추천 스팟은 발광 핀(작은 구/막대)으로, 혼잡도 색상. 널널한 곳이 은은히 더 빛나 컨셉을 전달.

### 2.3 성능·폴백(오프라인 안전)
- `prefers-reduced-motion` → 회전 정지(정적 프레임 1장).
- 모바일/저사양 → `devicePixelRatio` 상한, 블룸 off, 핀 수 축소.
- **WebGL 미지원/초기화 실패 → 기존 `HeroScene`로 폴백**(HeroScene는 삭제하지 않고 fallback으로 보존).
- 핀 데이터 로드 실패 → 지도만 렌더(씬 자체는 유지).

### 2.4 통합
- `HomeScreen` 히어로의 배경 레이어만 `SeoulMap3D`로 교체. **스크롤 collapse 로직·검색 오버레이·CTA·"Null crowd, Full trip" 카피 등 나머지 히어로 UX는 그대로 유지**한다.
- 스타일은 `styles.css`의 히어로 관련 규칙을 재사용하고, 3D 캔버스용 규칙만 최소 추가.

## 3. Task 2 — 검색 카탈로그 개편

### 3.1 백엔드 `GET /api/spots` 확장 ([app/routers/spots.py](../../../app/routers/spots.py))
- 추가 쿼리 파라미터:
  - `district`: 주소(addr) 포함 매칭(예: `종로구`). 데이터상 구 없는 스팟은 1곳뿐.
  - `category`: 카테고리 그룹 필터. cat1 매핑
    - `볼거리`(기본) = A01(공원·자연) + A02(문화·역사) + A03(관광지)
    - `문화·역사` = A02, `자연·공원` = A01, `미식` = A05, `쇼핑` = A04
  - 미지정 시 기존 동작 유지(하위호환). `category=볼거리`가 검색 탭 기본.
- **정렬:** 콘텐츠 품질(이미지·개요 보유) → base_popularity → id. (리스트는 2,000행 대상이라 per-item 혼잡 계산은 하지 않음.)
- **혼잡 배지:** 반환된 페이지(size≤100)에 한해 스냅샷 보유 스팟의 level을 벌크 조회로 덧붙여, 있는 곳만 배지 표기(전수 계산 회피).
- **facet 카운트(선택):** 현재 필터 기준 카테고리별 개수를 응답에 포함해 칩에 숫자 노출. 구현 부담 크면 1차에서 생략 가능.

### 3.2 프론트 `RegionScreen` 개편 ([main.jsx](../../../nullnull-travel-webapp/src/main.jsx))
- 데이터 소스: `/api/spots/home` → **`/api/spots`**(페이지네이션)로 전환.
- 컨트롤: 구 select(기존 `SEOUL_DISTRICTS`) + **카테고리 칩(볼거리 기본, 문화·역사/자연·공원/미식/쇼핑)** + 키워드(기존 `SpotSearch`).
- **무한스크롤**(또는 "더 보기") — page 증가로 누적. 총 개수 표기.
- 카드: 기존 `RegionSpotCard` 재사용, 혼잡 배지는 있는 곳만.

## 4. Task 3 — AI 코스 LLM 재구성

### 4.1 조건 확장(프론트 `AiCourseScreen`)
기존(지역·길이·동행·날짜)에 추가:
- **시작 시간대**(오전/오후/저녁)
- **관심 테마(다중)**: 역사 · 자연 · 미식 · 포토스팟 · 쇼핑 · 힐링
- **페이스**: 여유 / 보통
- **실내외 선호**: 상관없음 / 실내 선호 / 실외 선호(날씨 연동)

### 4.2 백엔드 파이프라인
- **신규** `app/external/openai_api.py` — OpenAI 클라이언트 래퍼.
  - 설정: `openai_api_key`, `openai_model`(기본 `gpt-4o-mini`, env로 상향 가능).
  - Chat Completions + `response_format={"type":"json_object"}`, 타임아웃·예외 처리. 실패/키 없음 → `None` 반환(폴백 트리거). `is_llm_enabled` 프로퍼티.
- **신규** `course_service.ai_recommend_courses(db, conditions) -> list[Course]`:
  1. **후보 큐레이션:** 구 + 관심 테마(합집합) + 혼잡 실측 보유(`spots_with_congestion_data`) 우선. 후보가 너무 적으면(<8) 해당 구 볼거리로 넓히고 휴리스틱 혼잡 병행. 후보별 신호 산출: 혼잡 risk(벌크), is_indoor, 태그, 카테고리, 히든젬(저방문 백분위), 날씨 강수확률.
  2. **프롬프트 구성:** system(역할=널널 여행 큐레이터 / 규칙=주어진 spot_id만 사용·붐빔 회피·날씨·실내외 반영·이동 최소화·N개 코스 각 M스팟) + user(사용자 조건 + 후보 JSON + 날씨 + 날짜 + N·M).
  3. **OpenAI 호출 → JSON:** `{courses:[{title, concept, reason, stops:[{spot_id, note}]}]}`.
  4. **검증(환각 방지):** 각 spot_id가 후보 풀에 존재하는지 확인, 중복 제거, 스팟 수 보정. 유효 코스 0개면 폴백.
  5. **실체화:** 각 코스를 [`_build_course`](../../../app/services/course_service.py)로 저장(동선·시간·근거·임팩트 재사용). LLM 서술(title/concept/reason)은 코스 title/description에 반영, 스팟별 note는 저장 필드가 있으면 기록·없으면 description에 흡수.
  6. 실체화된 Course 리스트 반환.
- **폴백** `_algorithmic_multi_courses(...)`: 기존 `recommend_course`를 `variation_seed`·테마 시퀀스를 달리해 N개 생성.
- **신규 엔드포인트** `POST /api/courses/ai-recommend`: 요청 스키마(district, duration/stops, companion, date, start_time_slot, themes[], pace, indoor_pref) → 응답: `CourseDetail` 리스트(각 course_id 포함, 기존 `GET /api/courses/{id}`로 열람).

### 4.3 설정·의존성
- `app/config.py`에 `openai_api_key`·`openai_model` 추가, `.env.example` 갱신. **`OPENAI_API_KEY`는 사용자가 직접 `.env`에 추가**(없으면 폴백).
- `requirements.txt`에 `openai>=1.0` 추가.

### 4.4 프론트 결과 화면
- 조건 폼 제출 → `POST /api/courses/ai-recommend` → **여러 코스 카드**(제목·컨셉·추천 이유·스팟 미리보기) → 카드 탭 시 기존 `CourseScreen`(course_id) 재사용.
- 로딩·에러 처리(에러 시 토스트, 폴백은 서버측). **추천 출처 배지**(AI 추천 / 널널 알고리즘)로 폴백 여부 투명 고지.

## 5. 횡단 관심사

- **디자인:** 색상·타이포·컴포넌트·간격 등 기존 시스템 준수. 신규 UI도 기존 클래스·패턴 재사용.
- **신규 의존성:** `three`(FE), `openai`(BE).
- **오프라인 심사 대비(프로젝트 제약):** 3D는 WebGL 실패 시 `HeroScene` 폴백, AI 코스는 키/네트워크 없을 때 알고리즘 폴백 — 둘 다 **필수**.
- **테스트:** 백엔드 pytest(카테고리·구 필터, AI 후보 검증, 폴백 경로, 스키마). 프론트는 수동 검증(홈 3D·검색·AI 코스 플로우).
- **구현 순서(권장):** Task 2(가장 빠른 가치) → Task 3(핵심) → Task 1(가장 무거움).

## 6. 리스크 / 완화

- **three.js 번들·성능** → 트리셰이킹, dpr 상한, 모바일 감지, 블룸 토글, HeroScene 폴백.
- **서울 GeoJSON 확보·경량화** → 공개 데이터 파생 후 좌표 단순화. 확보 난항 시 대체안: 구 중심점 기반 추상 지형.
- **OpenAI 비용·지연·JSON 파싱** → 저비용 기본 모델, 타임아웃, 엄격한 스키마 검증, 파싱 실패 시 폴백.
- **프롬프트 인젝션/환각** → LLM은 후보 id만 사용, 반환 id를 서버가 검증. 자유 텍스트는 서술에만.
- **검색 정렬 부하(2,000행)** → per-item 혼잡 계산 배제, 페이지 단위 배지 조회만.
