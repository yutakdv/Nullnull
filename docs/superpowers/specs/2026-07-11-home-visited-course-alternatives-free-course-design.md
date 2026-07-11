# 홈 방문 기록 · 코스 대안 추천 · 자유여행(카테고리 혼합) 코스 — 설계

날짜: 2026-07-11
요청: "홈 화면에 방문 장소 뜨는거, 코스 대안 추천해줄 수 있는 방안, 카테고리 혼합(여행장소→미식→포토스팟) 자유여행까지 모든 경우의 수를 반영"

## 1. 홈 화면 방문 장소 (방문 기록)

**문제**: 방문 후 피드백(F7)·후기를 남겨도 홈에서 "내가 다녀온 곳"을 볼 수 없다.

**설계**: `GET /api/spots/visited?limit=6`
- 소스: `visit_feedback` + `visit_review` 중 `is_seed=False`(실사용 기록)를 스팟별로 합산,
  최근 활동 순 정렬. 시드 데이터는 제외해 데이터 정직성 원칙 유지.
- 항목: SpotSummary + 현재 널널도(배치 캐시, 실시간 호출 없음) + `visited_text`("오늘 오후"/"어제"/"N일 전")
  + `visit_count` + `last_rating`(후기 별점, 없으면 null) + `last_perceived_label`(한산/예상대로/붐빔, 없으면 null).
- FE: 홈 하단에 "최근 방문한 장소" 캐러셀. 기록이 없으면 섹션 숨김(콜드스타트 안전).
  피드백/후기 등록 직후 갱신. 카드 탭 → 상세 화면.
- 부수 수정: 홈에서 이미 fetch하지만 렌더링하지 않던 `인기 널널 코스`(`/api/courses/popular`)
  캐러셀을 실제로 표시. 카드 탭 → 해당 코스 상세.

## 2. 코스 대안 추천

**문제**: 코스 생성 후 구성 장소가 마음에 안 들어도 대안이 없다.

**설계 A**: `GET /api/courses/{course_id}/alternatives?limit=2`
- 코스의 각 순서(slot)별로, 해당 장소를 기준으로 한 대안 후보를 기존 AlternativeScore(9-2)로 랭킹.
- 후보에서 현재 코스 구성 장소·기준 장소 제외. 노출은 recommendation_log에 기록(F8 로테이션 유지).
- 이동 편의(mobility)는 코스 내 이전 지점 기준으로 계산해 "동선을 해치지 않는 대안"을 우선.

**설계 B**: `POST /api/courses/{course_id}/swap` `{order_no, new_spot_id}`
- 해당 슬롯만 교체한 **새 코스**를 생성(원본 보존, 익명 MVP라 소유권 개념 없음).
- 기존 코스의 저장된 순서를 유지(greedy 재정렬 없음), 이동시간·근거·요약 수치 재산출.
- 응답: CourseDetail(201). 교체 선택은 selected=True 로그.

FE: 일정(코스) 화면에 "이 코스의 대안" 섹션 — 슬롯별 교체 후보 카드, 탭하면 swap 호출 후 새 코스 표시.

## 3. 카테고리 혼합 자유여행 코스 (모든 경우의 수)

**문제**: 현재 코스는 원 관광지와 같은 테마 유지형만 생성됨. 여행장소→미식→포토스팟처럼
카테고리를 섞는 자유여행 수요를 못 받는다.

**설계**: `POST /api/courses/recommend`
```json
{
  "origin_spot_id": 1,
  "date": "2026-07-12",
  "time_slot": "afternoon",
  "theme_sequence": ["여행지", "미식", "포토스팟"],
  "title": null
}
```
- `theme_sequence`: 길이 1~4, 허용 값 `여행지 | 자연 | 역사 | 미식 | 포토스팟`.
  중복 허용(예: [미식, 미식]) — 어떤 조합·순서든 가능("모든 경우의 수").
  생략 시 자유여행 기본값 `[여행지, 미식, 포토스팟]`.
  - `여행지` = 일반 관광 명소(cat1 A01/A02/A03 또는 자연·역사 태그) — 요청의 "여행장소".
  - 나머지는 홈 테마 필터와 동일한 태그+cat1 매핑(THEME_CAT1_CODES) 재사용.
- 슬롯별 선정: 테마 매칭 후보(혼잡 데이터 보유 스팟만 — 근거 없는 추천 방지) 중
  AlternativeScore 변형으로 argmax:
  - theme 항 = **슬롯 테마 적합도**(태그 일치 1.0 / cat1 유래 0.75) — 원 관광지 유사도가 아님.
  - relief 항 = 원 관광지 대비 혼잡 완화(기존과 동일 기준).
  - mobility 항 = 직전 슬롯 위치 기준 이동시간(순서 보존 체인).
  - hidden/weather/load 항 기존 그대로.
- 코스 저장: **시퀀스 순서 보존**(greedy 재정렬 안 함 — 여행장소→미식→포토스팟 순서가 의도).
  `theme_keep_pct`는 자유여행에선 "슬롯 테마 일치율" 의미로 저장(평균 슬롯 적합도×100).
- 스키마 변경 없음(마이그레이션 리스크 회피). 자유여행 여부는 제목·설명으로 표현.
- 기존 단일 테마 흐름(F4 대안→코스 생성)은 그대로 유지 — 테마 유지형/자유여행형 둘 다 지원.

FE: 홈 검색 카드에 "코스 스타일" 선택(테마 유지 / 자유여행). 자유여행 선택 시 슬롯 빌더
(기본 여행지→미식→포토스팟, 슬롯 2~4개 추가/삭제, 슬롯별 카테고리 선택) 노출.
CTA가 "자유여행 코스 만들기"로 바뀌고 recommend API 호출 → 일정 화면 이동.

## 구현 구조

- `app/services/recommend_service.py`: 테마 매핑(THEME_CAT1_CODES·spot_theme_tags·theme_filter)을
  spots 라우터에서 이동(라우터→서비스 의존 방향 정리), `slot_candidates`/`slot_fit` 추가.
- `app/services/course_service.py`: `create_course`의 코스 조립부를 `_build_course`로 추출해
  기존 생성(그리디 정렬)·자유여행(시퀀스 정렬)·swap(저장 순서) 세 경로가 공유.
  `recommend_course`, `course_alternatives`, `swap_course_item`, `visited_spots` 추가.
- `app/routers/spots.py`: `/api/spots/visited` (단, `/{spot_id}` 라우트보다 먼저 선언).
- `app/routers/courses.py`: `/recommend`, `/{id}/alternatives`, `/{id}/swap`.
- `app/schemas.py`: VisitedSpot(sResponse), CourseRecommendRequest, CourseSwapRequest,
  CourseAlternativesResponse 추가.
- 테스트: `tests/test_visited_api.py`, `tests/test_course_recommend.py`(recommend·alternatives·swap).

## 에러 처리
- recommend: 알 수 없는 테마 → 422(pydantic pattern), 슬롯 후보 없음 → 404 + 안내 문구,
  날짜는 기존 30일 창 검증 재사용.
- swap: 코스/order_no/새 스팟 없음 → 404, 새 스팟이 이미 코스에 있으면 409.
