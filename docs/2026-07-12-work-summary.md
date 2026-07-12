# 널널(Nullnull) 작업 내역 정리

작성일: 2026-07-12
브랜치: `feature/frontend`

## 프로젝트 개요

혼잡도(널널도) 데이터 기반 서울 여행지·코스 추천 웹앱.

- **백엔드** — `app/` : FastAPI + SQLAlchemy. TourAPI·기상청·서울시 등 외부 API 수집(`app/external/`), 혼잡도/대안 스코어링(`app/scoring/`), 추천·코스·임팩트 서비스(`app/services/`), 일일 배치(`app/batch/`).
- **프론트엔드** — `nullnull-travel-webapp/` : React + Vite 단일 페이지 앱(`src/main.jsx`, `src/styles.css`).
- **문서** — 설계 명세는 `docs/superpowers/specs/`, 백테스트 기록은 `docs/backtest/`.

---

## 1차. 데모 버전 (`b57f697` Demo Ver.)

기획서(`과제2_널널_웹앱구현_기획서_Final.md`) 기반 초기 구현.

- FastAPI 백엔드 골격: 관광지/코스/리뷰/피드백/임팩트/관리자 라우터, 시드 데이터, Docker 구성.
- React 데모 프론트: 홈 화면, 코스 추천, 널널도 표시.

## 2차. 추천 알고리즘 수정 및 웹 페이지 개선 1차 (`cc6bd57`)

설계: [2026-07-11 홈 방문 기록·코스 대안·자유여행 코스 설계](superpowers/specs/2026-07-11-home-visited-course-alternatives-free-course-design.md)

- **홈 방문 장소(방문 기록)** — 홈에서 기준 장소를 잡고 추천을 받는 흐름.
- **코스 대안 추천** — 생성된 코스의 각 장소에 대해 덜 붐비는 대안을 제시.
- **자유여행(카테고리 혼합) 코스** — 슬롯별 테마(`slot_themes`)를 조합한 코스 모드 추가.
- 인프라 정비: CI 워크플로우(`.github/workflows/ci.yml`, `daily-batch.yml`), `.env.example`, `.gitignore`(pyc 제거), TourAPI 클라이언트 수정.

## 3차. 알고리즘 개선 및 UX 개선 (`8d8b156`)

설계: [2026-07-12 웹앱 UX 개선 설계](superpowers/specs/2026-07-12-nullnull-webapp-ux-improvements-design.md) — 사용자 지적 6개 이슈 + VisitKorea 참고 친화화.

**알고리즘**
- `app/scoring/alternative.py` 대안 스코어링 개선(+46줄), `recommend_service.py` 반영, `weights.yaml` 가중치 항목 추가.
- 테스트 추가: `tests/test_alternatives_api.py`, `tests/test_scoring.py`.

**UX (내부 용어 → 사용자 혜택 언어)**
- **지도** — OSM 기본 타일을 CARTO Voyager로 교체, 드래그·줌 조작 활성화("정적 스크린샷" 인상 제거).
- **검색** — 매칭 대상을 name·category·addr·tags·overview로 확장, 공백 정규화, 유사 장소 보완 추천.
- **홈** — "방문 장소" → **"기준 장소"** 라벨 변경, 상단 지표를 사용자 혜택 언어로 재구성.
- **동행** — 하드 필터가 아닌 소프트 우선정렬로 반영.
- 이미지·가독성 개선, 상세 제안칩 하이라이트 박스.

## 4차. 현재 진행 중 (미커밋 — working tree)

프론트 대규모 개편(main.jsx +1,143줄, styles.css +1,823줄) + 백엔드 지원 API.

**백엔드**
- **F9 코스 공유** — `Course.is_shared` 컬럼 추가([models.py](../app/models.py)), `POST /api/courses/{id}/share` 엔드포인트([courses.py](../app/routers/courses.py)). `popular_courses`는 이제 **공유된 코스 + 시드 코스만** 홈 캐러셀에 노출(비공개 개인 코스 미노출).
- **지역(구) 필터** — `GET /api/spots/home`에 `district` 파라미터 추가(주소 기준 매칭), `limit` 상한 6→50([spots.py](../app/routers/spots.py)).
- **시드 이미지 정비** — tong URL http→https, 관광공사 이미지가 없는 5개 장소(낙산구간·성균관 명륜당·국립민속박물관·낙산공원·통인시장)는 로컬 자산으로 매핑. 기존 DB도 갱신하는 `sync_seed_images` 를 앱 기동 시 실행([seed_data.py](../app/seed_data.py), [main.py](../app/main.py)).

**프론트엔드**
- **하단 탭 네비게이션** 구조로 개편: 홈 / 지역 / AI 코스 / 마이페이지.
- **홈 히어로** — 전체화면 히어로(`HeroScene`), 스크롤 시 네비 노출. "인기 널널 코스" 캐러셀(공유 코스 기반).
- **지역 탭**(`RegionScreen`) — 서울 25개 자치구 선택, 혼잡도 낮은 순 관광지 리스트.
- **AI 코스 탭**(`AiCourseScreen`) — 지역·코스 길이·동행·날짜만 골라 널널한 일정 생성(가장 널널한 장소를 출발점으로 선정).
- **마이페이지**(`MyPageScreen`) — 저장한 관광지·북마크한 공유 코스(localStorage 유지).
- **코스 공유 UI** — 결과 화면에서 공유 버튼 → 홈 인기 코스 노출.
- **이미지 폴백**(`SmartImage`) — 로드 실패 시 위키 이미지 → 브랜드 플레이스홀더 순 폴백.
- dev 서버 host `127.0.0.1` → `0.0.0.0`(외부 기기 접속용).

**신규 자산** — `nullnull-travel-webapp/public/assets/` 에 장소 대표 이미지 5종(jpg/png).

---

## 관련 문서

- [기획서 Final](../과제2_널널_웹앱구현_기획서_Final.md)
- [백엔드 초기 계획 (07-10)](superpowers/plans/2026-07-10-nullnull-backend.md)
- [홈·대안·자유코스 설계 (07-11)](superpowers/specs/2026-07-11-home-visited-course-alternatives-free-course-design.md)
- [UX 개선 설계 (07-12)](superpowers/specs/2026-07-12-nullnull-webapp-ux-improvements-design.md)
- 백테스트 기록: [07-11](backtest/2026-07-11.md), [07-12](backtest/2026-07-12.md)
