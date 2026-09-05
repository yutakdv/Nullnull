# Figma Component Catalog

- 상태: Accepted for P0 implementation
- 확인일: 2026-09-05
- 디자인 정본: Figma `01 Components`
- 구현 대상: 최상위 component node 49개

이 catalog는 Figma component와 코드의 1:1 추적성을 위한다. `Figma 이름` 열은
공백과 `icon/` prefix까지 2026-09-05 실제 layer 이름을 그대로 기록한다. 화면 조합과
API 흐름은 [Figma 개발 핸드오프](./FIGMA_HANDOFF.md), 도메인 의미는
[제품 요구사항](../product/PRODUCT_SPEC.md), response shape은
[OpenAPI](../api/openapi.yaml)를 따른다.

## 1. 구현 규칙

- Catalog ID는 문서 추적 용도다. React export는 영문 PascalCase를 쓰고 Figma 원본 이름을 component JSDoc/Storybook tag에 남긴다.
- 시각 variant와 서버 상태를 같은 타입으로 섞지 않는다. 예를 들어 `loading`/선택 상태는 UI state이고 `REPLAY`/`STALE`은 API data state다.
- 페이지가 직접 저수준 icon·card를 복제하지 않고 `shared/ui` 또는 해당 feature public API를 통해 사용한다.
- server entity를 component local state에 영구 복제하지 않는다. 편집 draft와 submit state만 local state로 둔다.
- 모든 interactive component는 최소 44×44px target, visible focus, disabled reason, keyboard 동작을 갖는다.
- `loading`, `empty`, `error`, `offline`, `stale`, 긴 KO/EN, 360px, 200% zoom story를 데이터 의존 component에 제공한다.
- KTO 데이터를 표현하는 component는 공용 `DataAttribution` primitive를 조합한다. 이는 새 Figma 최상위 node 수에 포함하지 않는 구현 primitive이며 문구·link·source state를 중앙 관리한다.

## 2. 49개 최상위 node

### 2.1 Domain, action, form, navigation

| ID | Figma 이름 | 필수 props/variant·세부 기능 | Frontend 책임 | Backend/AI 계약·금지 |
| --- | --- | --- | --- | --- |
| C01 | `Card / Candidate` | `relation`, `dataState`, `selected`, `actionState`, place, reason | 후보/일정화 상태와 CTA를 text로 표현 | candidate ID/status/provenance; 저장만으로 TripItem 생성 금지 |
| C02 | `Sheet / TripPicker` | `loading`, `empty`, `saving`, `saved`, `duplicate`, `error`, trips | focus trap, 여행 선택, trigger focus 복귀 | `listTrips`, `addTripCandidate`; save 중 중복 mutation 금지 |
| C03 | `Action / DecisionBar` | `APPLY`, `KEEP`, `loading`, `disabled`, expiry | 두 결정의 결과와 disabled reason 표시 | `decideOptimization`; 모호한 단일 `확인` 응답 금지 |
| C04 | `Action / TripAddButton` | `idle`, `saving`, `saved`, `duplicate`, `error`, trip context | `+`의 후보 저장 의미와 재시도 제공 | `addTripCandidate`; 일정을 즉시 변경하지 않음 |
| C05 | `Form / OptimizationScope` | `ITEM`, `DAY`, `TRIP`, `available`, `reason` | P0에는 ITEM만 활성, 미지원 설명 | capability/readiness; P0에 DAY/TRIP 요청 금지 |
| C06 | `Data / MetricDelta` | before, after, unit, direction, `eligible`, reason | 비교 불가 이유를 표시하고 숫자를 숨김 | comparable metric; 비교 불가를 `0`으로 변환 금지 |
| C07 | `Data / StateLabel` | `LIVE`, `FORECAST`, `REPLAY`, `QUALITATIVE`, `STALE`, `UNAVAILABLE`, timestamps | state, 시각, stale reason을 text/icon으로 표시 | complete provenance; replay/forecast 숨김 금지 |
| C08 | `Form / LockControl` | `MUST_VISIT`, `DATE`, `TIME`, `RESERVATION`, checked, disabled, reason | 잠금별 독립 control과 해제 confirm 연결 | set/remove constraint; 하나의 boolean lock으로 합치기 금지 |
| C09 | `Nav / Segment` | options, selected, disabled | roving tabindex/arrow key, 선택 label | enum/capability; clickable `div` 금지 |
| C10 | `Form / Search` | query, debounce, `loading`, `empty`, `error`, results | IME-safe input, clear, result keyboard navigation | `searchPlaces`; raw query의 불필요한 장기 로그 금지 |
| C11 | `Action / Bottom CTA` | label, `loading`, `disabled`, reason, safe-area | keyboard/safe-area에 가려지지 않게 배치 | mutation state/error; duplicate submit 금지 |
| C12 | `Nav / TabBar` | active tab, badge, safe-area, destination | P0 4-tab 라우팅과 active-trip 분기 | owner activeTripId/unread; overlay 뒤 focus 금지 |
| C32 | `Compare / Place` | current, alternative, relation, metrics, eligibility, source | 동일 기준 양측 비교와 선택/취소 | `listRelatedPlaces`; 비교 불가 데이터 ranking 금지 |
| C33 | `Sheet / SaveCandidate` | place, trips, result state, duplicate, retry | TripPicker를 포함한 저장 flow 조합, 일정 미변경 copy | candidate idempotency/Problem; sheet 닫기로 mutation 취소 오인 금지 |
| C34 | `Card / FeedPost` | post, place, save/post-save/candidate state, provenance | detail link, 게시물 저장과 여행 후보 저장 분리 | feed projection; SavedPost·TripCandidate 혼용 금지 |
| C35 | `Feedback / Toast` | tone, message, action, duration, persistent companion | 보조 피드백만 제공, live-region 과도 방지 | request/error ID 선택 제공; 중요 결과를 toast만으로 전달 금지 |
| C37 | `Map / Optimization` | before/after route, moved item, locks, data state | 지도 외 동등한 text/list 비교 제공 | validated geometry/proposal; client 직선으로 경로 확정 금지 |
| C38 | `Card / TripItem` | item, time, duration, position, constraints, editing | view/edit/reorder/action variant, 잠금 요약 | TripItem/version/constraints; optimistic 성공 확정 금지 |
| C39 | `Form / Pick` | label, options, selected, `loading`, `empty`, validation | single/multi pick을 명시적으로 구분 | allowed values; 표시 label만으로 ID 전송 금지 |
| C40 | `Data / Must Visit` | locked, source, reason | 필수 방문과 해제 결과 표시 | MUST_VISIT constraint; 예약 lock과 혼용 금지 |
| C42 | `Action / 장소 추가` | source, target trip/day, `idle/submitting/success/error` | 직접 일정 추가 action을 후보 `+`와 구분 | `addTripItem`; TripAddButton과 도메인 의미 혼용 금지 |
| C43 | `Data / Distance` | value, unit, mode, confidence, unavailable reason | locale 단위와 정밀도 표시 | route/haversine provenance; 미확인 이동 가능성 단정 금지 |
| C44 | `Sheet / Grab` | decorative/interactive handle, label when interactive | sheet 드래그 보조; 닫기 button을 별도 제공 | API 의존 없음; handle만으로 닫기 금지 |
| C46 | `Data / Badge` | tone, icon, label, compact | state를 text+icon으로 표시 | enum을 표시 label로 mapping; color-only 금지 |
| C47 | `Data / Tag` | category, label, removable, selected | 비대화형과 action tag 구분 | canonical taxonomy; 자유 문자열로 ID 대체 금지 |
| C48 | `Form / Chip` | selected, disabled, removable, count | keyboard/touch selection, pressed state | interest/category enum; disabled reason 누락 금지 |
| C49 | `Nav / NavBar` | title, back, leading/trailing actions, safe-area | page top app bar와 back/action focus order | route metadata; browser history를 맹목적으로 `-1` 호출 금지 |

### 2.2 Map

| ID | Figma 이름 | 필수 props/variant·세부 기능 | Frontend 책임 | Backend/AI 계약·금지 |
| --- | --- | --- | --- | --- |
| C41 | `Map / Marker` | id, coordinate, state, selected, label, cluster count | map/list 선택 동기화, keyboard는 list로 보장 | canonical coordinate/data state; marker만으로 정보 제공 금지 |
| C45 | `Map / Base` | center, bounds, markers, `loading/error/unavailable`, attribution | provider adapter, list fallback, attribution 상시 표시 | bounded place payload/license; 위치를 무단 서버 전송 금지 |

### 2.3 Icon registry

| ID | Figma 이름 | 의미/사용처 | Frontend 책임 | Backend/AI 의존 |
| --- | --- | --- | --- | --- |
| C13 | `icon/heart` | 기본 좋아요/저장 glyph | currentColor, size 토큰, 장식용 `aria-hidden` | 없음 |
| C14 | `icon/location` | 장소/위치 | button의 accessible name을 대체하지 않음 | 없음 |
| C15 | `icon/arrow-right` | 다음/방향 | RTL 정책을 icon registry에서 일괄 적용 | 없음 |
| C16 | `icon/chevron-down` | 펼침/선택 | expanded state는 control의 `aria-expanded`로 표현 | 없음 |
| C17 | `icon/plus` | 추가/후보 저장 glyph | 문맥별 button label 필수 | 없음 |
| C18 | `icon/check` | 완료/선택 | color 외 label/state 병행 | 없음 |
| C19 | `icon/close` | sheet/dialog 닫기 | `닫기` accessible name, focus 복귀 | 없음 |
| C20 | `icon/chevron-right` | row/deep-link 이동 | 행의 destination label 유지 | 없음 |
| C21 | `icon/back` | 이전 화면 | safe fallback route, `뒤로` label | 없음 |
| C22 | `icon/settings` | 설정 | button label 필수 | 없음 |
| C23 | `icon/bell` | 알림 | badge와 unread text 병행 | notification unreadCount |
| C24 | `icon/search` | 검색 | submit/field 문맥 label 필수 | 없음 |
| C25 | `icon/heart-like-filled` | 좋아요 selected | pressed state를 icon만으로 표시 금지 | feed feedback state |
| C26 | `icon/heart-like` | 좋아요 unselected | `aria-pressed=false`는 소유 control에 설정 | feed feedback state |
| C27 | `icon/pin-visit-filled` | 필수 방문 selected | MUST_VISIT label과 병행 | constraint state |
| C28 | `icon/pin-visit` | 필수 방문 unselected | button의 예상 결과 제공 | constraint state |
| C29 | `icon/reservation` | 예약 잠금 | 예약 문구/메모와 병행 | RESERVATION constraint |
| C30 | `icon/time-lock` | 시간 잠금 | 시간값과 병행 | TIME constraint |
| C31 | `icon/date-lock` | 날짜 잠금 | 날짜값과 병행 | DATE constraint |
| C36 | `icon/bookmark` | 게시물 저장 | TripCandidate `+`와 다른 label/상태 유지 | SavedPost state |

## 3. 조합 경계

| 사용자 의도 | 조합 root | 핵심 하위 component | 서버 변경 |
| --- | --- | --- | --- |
| 게시물 보관 | `Card/FeedPost` | `bookmark` | SavedPost create/delete |
| 여행 후보 저장 | `Sheet/SaveCandidate` | `Sheet/TripPicker`, `Action/TripAddButton`, `Sheet/Grab` | TripCandidate create, TripItem/여행 version 미변경 |
| 일정에 장소 추가 | `Card/TripItem` flow | `Action/장소 추가`, `Form/Pick` | TripItem create, trip version +1 |
| 일정 잠금 | `Form/LockControl` | `Data/Must Visit`, constraint icon | 해당 Constraint만 create/delete |
| 일정 최적화 | `Map/Optimization`/preview | `Compare/Place`, `Data/MetricDelta`, `Action/DecisionBar` | apply 승인 전 0; apply 시 transaction |
| Live 탐색 | `Map/Base` | `Map/Marker`, `Data/StateLabel`, `Data/Distance` | read-only; 후보 저장은 별도 action |

## 4. Storybook·테스트 완료 조건

- 49개 모두가 barrel export와 component inventory test에 등록된다.
- interactive node는 pointer, keyboard, disabled, loading 상태를 검증한다.
- sheet/dialog/map은 focus 시작·트랩·복귀와 list fallback을 검증한다.
- data component는 6개 `SourceState`, comparison eligible/ineligible, 누락·stale fixture를 검증한다.
- screenshot/visual regression은 360px KO/EN과 200% text zoom을 포함한다.
- Figma node가 추가·이름 변경되면 이 catalog의 ID를 유지하고 mapping만 갱신한다. 의미가 변하면 별도 마이그레이션 PR을 사용한다.
- KTO fixture를 사용하는 card/detail/compare/map story는 `출처: ⓒ한국관광공사`를 DOM에 포함한다. `TourAPI` 단독 표기와 CI·BI image 사용은 snapshot/license 검사에서 실패시킨다.
- 공모전 profile story는 로그인·JA/ZH·P1·위치 control이 disabled/OFF이고 geolocation 호출이 없음을 검증한다.
