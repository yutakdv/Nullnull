# Figma 정합성 수정 요청

- 감사일: 2026-09-05
- 상태: Open — 아래 P0 blocker와 major가 닫히기 전 Figma 전체를 구현 승인 상태로 보지 않음
- 대상: `02 UI Design`의 현재 구현 frame 52개와 `01 Components`의 최상위 component 49개
- 디자인 파일: [Nullnull UI Design](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=386-257&p=f)

이 문서는 공개 Figma의 실제 화면·layer를 제품 요구사항, OpenAPI, 기능 인벤토리와
대조한 change request다. 현재 frame 수 52와 component 수 49는 맞지만, 개수 일치는
구현 준비 완료를 뜻하지 않는다. Frontend 담당은 Figma를 수정하고 node/state 증거를
이 문서에 연결하며, Backend/AI 담당은 계약 의미를, 총괄 PM은 범위·문구·제출 주장을
확인한다.

2026-09-06 PR #6 검토에서 확인한 추가 gap은 FCR-010~015로 등록했다. 이 변경은
Frontend가 작업을 시작할 수 있도록 node·계약·완료 증거를 연결하는 문서 기준선이며
Figma 자체를 수정하지 않는다. 실제 frame/component 수정과 before/after 증거는
Frontend 담당자가 각 FCR을 닫을 때 제출한다.

## 수정 목록

| ID | Pri | 현재 Figma 증거 | 목표 상태 | 소유/검토 | 상태 |
| --- | --- | --- | --- | --- | --- |
| FCR-001 | P0 blocker | A-2 `388:277`의 English가 `영어 · 준비 중`으로 표시됨 | `한국어`와 `English`는 선택 가능, `日本語`·`中文`만 disabled `준비 중`; KO/EN 전환·복구 variant 추가 | FE / BE·AI·PM | Figma 수정 완료 · 검토 대기 (2026-09-07, [증거](#fcr-001-증거)) |
| FCR-002 | P0 blocker | feed `391:310`, `396:2926`과 post `398:611`에 `팔로잉`·`최신`, 검색, unread bell, 활성 `팔로우`가 보임 | P0에서는 제거가 기본. 꼭 남기면 disabled `준비 중`과 이유를 표시하고 route/API 호출 0건 | FE / PM | Figma 수정 완료 · 검토 대기 (2026-09-07, [증거](#fcr-002-증거)) |
| FCR-003 | P0 blocker | feed에 `혼잡도 낮은 순`, `지금 가기 좋아요`, `서울` chip이 활성 control처럼 보임 | `listFeed`에 filter 계약이 생기기 전 숨김. P1에서도 source·시점 비교 적격성 없는 혼합 순위 금지 | FE / BE·AI | Figma 수정 완료 · 검토 대기 (2026-09-07, [증거](#fcr-003-증거)) |
| FCR-004 | P0 blocker | F 흐름에 setup `415:2268`, loading `415:2413`, P1 DAY preview `439:3104`, applied `417:2412`만 있고 P0 ITEM READY preview가 없음 | P0 ITEM 전용 READY frame/variant 추가: before/after, provenance, lock validation, eligible metric, `적용`/`현재 일정 유지` | FE / BE·AI·PM | Figma 수정 완료 · 검토 대기 (2026-09-07, [증거](#fcr-004-증거)) |
| FCR-005 | P0 blocker | loading/preview에 `경로 계산`, `지도 provider 미정`; 여행 보기 `410:1738`에 `↓ 1.2km · 도보 15분`; Live `418:2523`에 `돌아가도 15분/30분`, `+8분/+5분`이 있으나 P0 route provider는 미결정 | ITEM copy를 혼잡·고정 조건 확인으로 변경. provider가 없으면 목록/timeline을 동등하게 제공하고 route 기반 시간·우회 수치·placeholder를 제거. 직선거리는 FCR-009 기준을 충족할 때만 표시 | FE / BE·AI | Figma 수정 완료 · 검토 대기 (2026-09-07, [증거](#fcr-005-증거)) |
| FCR-006 | P0 blocker | S14 `422:2925`가 `로그인하면 일정을 저장할 수 있어요`와 활성 login affordance를 노출 | `이 기기의 익명 세션에 저장돼요`처럼 실제 보존 방식을 설명하고 login은 disabled `준비 중`; 요청 0건 | FE / BE·AI·PM | Figma 수정 완료 · 검토 대기 (2026-09-07, [증거](#fcr-006-증거)) |
| FCR-007 | P0 blocker | S15 `423:2967`가 데이터 상태를 5개로 설명하고 `REPLAY`를 누락. `Data / StateLabel` component에는 이미 6개 variant가 있음 | component를 다시 만들지 않고 S15 설명을 `LIVE`, `FORECAST`, `REPLAY`, `QUALITATIVE`, `STALE`, `UNAVAILABLE` 6개와 관측/대상 시각 차이로 수정 | FE / BE·AI | Figma 수정 완료 · 검토 대기 (2026-09-07, [증거](#fcr-007-증거)) |
| FCR-008 | P0 major | S11 `418:2523`에 장소명 검색이 있으나 화면-API 연결이 명시되지 않음 | `searchPlaces` → canonical 선택 → `getLivePlace`; coverage가 없으면 `UNAVAILABLE`, loading/empty/error variant 제공 | FE / BE·AI | Figma 수정 완료 · 검토 대기 (2026-09-07, [증거](#fcr-008-증거)) |
| FCR-009 | P0 major | post/detail 거리값은 기준점·산식이 불명확해 보일 수 있음 | trip anchor/선택 장소 등 거리 기준과 source를 함께 표시. 기준이 없으면 거리값을 숨기고 unavailable reason 제공 | FE / BE·AI | Figma 수정 완료 · 검토 대기 (2026-09-07, [증거](#fcr-009-증거)) |
| FCR-010 | P0 blocker | 최적화 setup `415:2268`에 `전체 / Day1` scope chip이 노출되고 `경복궁 하나만`이라는 고정 설명만 있으며 `targetItemId`를 고르는 control이 없음 | P0에서는 ITEM만 활성화하고 대상 TripItem을 명시적으로 선택·확인해 `CreateItemOptimizationRequest.targetItemId`로 전송. DAY/TRIP은 숨기거나 disabled `준비 중`이며 요청 0건 | FE / BE·AI | Figma 수정 완료 · 검토 대기 (2026-09-07, [증거](#fcr-010-증거)) |
| FCR-011 | P0 blocker | feed `392:368`, post 장소 카드 `399:613`, Live `418:5199`의 `실시간 관측`에 `ⓒ한국관광공사`가 결합돼 서울 실시간 원천과 KTO 예측/관광정보가 뒤섞임 | `SEOUL_CITYDATA`는 API의 서울특별시 attribution·`officialUrl`·`licenseUrl`을 그대로 표시하고 KTO 장소 정보·예측 attribution과 시각적으로 분리 | FE / BE·AI·PM | Figma 수정 완료 · 검토 대기 (2026-09-07, [증거](#fcr-011-증거)) · **폭 blocker 1건** |
| FCR-012 | P0 blocker | Live `418:2523`은 `Map / Base`와 marker가 보이는 화면만 있고 map capability OFF의 목록-only variant가 없음 | map OFF를 P0 기본으로 하는 목록-only default/loading/empty/error/unavailable variant를 추가. map ON은 provider·license·attribution 승인 뒤에만 열고 동일 filter/selection을 유지 | FE / BE·AI | Figma 수정 완료 · 검토 대기 (2026-09-08, [증거](#fcr-012-증거)) |
| FCR-013 | P0 blocker | 여행 보기 `410:1738`에 계약 연결 없이 `더 여유로운 날짜가 있어요 · 비교하기`가 노출됨 | P0에서 제거하거나 `getPlaceCrowdForecast`와 temporal comparison eligibility, 표시 threshold, unavailable 상태를 기능 ID에 연결. 단순 예보 비교와 적용 가능한 최적화 제안을 구분 | FE / BE·AI·PM | Figma 수정 완료 · 검토 대기 (2026-09-08, [증거](#fcr-013-증거)) |
| FCR-014 | P0 major | 계산 중 `415:2413`의 `취소하고 My Trip으로`가 한국어 tab 명칭과 다르고, client 이탈/timeout이 server run 취소를 뜻하는 것처럼 보임 | 취소 operation이 없는 P0에서는 `내 여행으로 돌아가기`처럼 navigation만 표현하고 run은 URL로 다시 조회할 수 있음을 안내. 실제 취소는 별도 계약·상태 전이 뒤에만 노출 | FE / BE·AI | Figma 수정 완료 · 검토 대기 (2026-09-08, [증거](#fcr-014-증거)) |
| FCR-015 | P0 blocker | 적용 완료 `417:2412`의 되돌리기가 toast action뿐이고 적용 대상 revision·24시간 `revertUntil`·만료 상태를 지속적으로 확인할 수 없음 | `ApplyOptimizationDecision`의 전후 revision·`revertUntil`을 persistent UI로 표시하고 가능/진행/완료/`REVERT_WINDOW_EXPIRED` 상태를 제공. toast는 보조 피드백으로만 사용 | FE / BE·AI | Figma 수정 완료 · 검토 대기 (2026-09-08, [증거](#fcr-015-증거)) |

`CON-003` 계약은 PR #9의 merge commit `1b3931c`로 `main`에 반영됐다. 따라서
FCR-010/011/015의 Backend/AI 계약 선행조건은 충족됐다. 세 FCR의 `Open` 상태는
Frontend가 실제 Figma node/variant와 before/after 증거를 아직 제출하지 않았다는
뜻이며 계약 미확정을 의미하지 않는다.

## 추가 항목 착수 연결

| FCR | 기능 ID | 계약 기준 | Frontend 착수 조건 |
| --- | --- | --- | --- |
| FCR-010 | `FR-OPT-01` | `createOptimization`, `CreateItemOptimizationRequest.targetItemId` | OpenAPI 0.2.0 생성 union으로 ITEM 대상 선택과 DAY/TRIP disabled state 구현 |
| FCR-011 | `FR-FED-01/02`, `FR-PST-01`, `FR-PLC-01`, `FR-LIV-03` | `DataProvenance.source/sourceState/attribution/officialUrl/licenseUrl` | OpenAPI example의 attribution과 URL을 그대로 사용하고 provider 문구를 hard-code하지 않음 |
| FCR-012 | `FR-LIV-01` | `queryLiveAreas`, `listLiveAreaPlaces`, map capability | map OFF 목록을 기본 acceptance로 먼저 완성 |
| FCR-013 | `FR-TRP-01`, `FR-DAT-02`, `FR-DAT-05` | `getTrip`, `getPlaceCrowdForecast`, `comparisonEligible` | 계약 연결·비교 규칙이 없으면 banner를 구현하지 않음 |
| FCR-014 | `FR-OPT-03` | `getOptimization`; cancel operation 없음 | navigation과 server run 취소를 구분한 copy/state 승인 |
| FCR-015 | `FR-OPT-09` | `ApplyOptimizationDecision`, `RevertOptimizationDecision`, `REVERT_WINDOW_EXPIRED` | OpenAPI 0.2.0 생성 union으로 persistent applied/revert/expired state와 keyboard 접근성 구현 |

## 권장 문구와 시각 규칙

- A-2 helper: `한국어와 English를 지원해요. 日本語와 中文은 준비 중이에요.`
- S14 guest: `로그인 없이 시작했어요` / `여행은 이 기기의 익명 세션에 저장돼요.`
- S14 login: `로그인 · 준비 중`을 disabled control로 제공하거나 제출 profile에서 숨긴다.
- ITEM loading: `혼잡 정보와 고정한 조건을 확인하고 있어요.`
- ITEM loading 이탈: `내 여행으로 돌아가기` — server run 취소를 의미하지 않는다.
- ITEM decision: 긍정 단일 `확인` 대신 `이 변경 적용`과 `현재 일정 유지`를 같은
  decision bar에 둔다.
- Live P0: map 없이도 검색·목록·선택·상세 진입이 완결되어야 한다.
- mixed source: provider 이름을 화면에서 고정하지 않고 응답의 검토된 attribution을
  source state와 함께 표시한다.
- 수치·색만으로 상태를 전달하지 않고 label, 기준 시각, 출처와 unavailable reason을
  함께 둔다.
- 공모전 제출 profile에서는 구현되지 않은 P1 control을 보여 주는 것보다 제거하는 것을
  기본으로 한다.

## 종료 조건

각 FCR은 다음 증거가 모두 있을 때만 `Closed`로 바꾼다.

1. 수정된 Figma node URL과 변경 전/후 screenshot
2. [기능 인벤토리](../product/FUNCTIONAL_INVENTORY.md)의 기능 ID·operationId 연결
3. KO/EN 360px, keyboard, disabled/loading/error state 확인
4. Backend/AI의 계약 검토와 총괄 PM의 범위·문구 승인
5. 실제 구현 후 Storybook/Playwright test ID

FCR-004 등 새 상태를 top-level frame으로 만들면 현재 52개 수가 늘어난다. 기존
component variant로 만들면 52개를 유지할 수 있다. 어느 방식을 택하든 숫자를 맞추기
위해 상태를 숨기지 말고 [Figma 핸드오프](./FIGMA_HANDOFF.md)와 검증 스크립트의
inventory를 같은 change set에서 갱신한다.

## FCR-001 증거

- 수정일: 2026-09-07, 수정자: Frontend (Claude Code Figma MCP)
- 상태: Figma 수정 완료. 종료 조건 1~3은 아래 증거로 충족했고, 4(Backend/AI 계약 검토·PM 문구 승인)와 5(구현 후 Storybook/Playwright test ID)는 대기 중이다.

| 항목 | 값 |
| --- | --- |
| KO 선택 frame | [`388:277` A-2 / language · P0](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=388-277) |
| EN 선택 variant frame | [`643:4088` A-2 / language · P0 · EN selected](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=643-4088) (A-3 오른쪽, x=1359) |
| 변경 전 | ![FCR-001 변경 전 388:277](./evidence/fcr-001/before-388-277.png) |
| 변경 후 KO | ![FCR-001 변경 후 KO 388:277](./evidence/fcr-001/after-388-277-ko.png) |
| 변경 후 EN | ![FCR-001 변경 후 EN 643:4088](./evidence/fcr-001/after-643-4088-en.png) |

변경 내용:

1. `388:290` English 부제 `영어 · 준비 중` → `영어`. English row는 한국어 row와 같은 선택 가능 스타일이다.
2. `388:294` 日本語, `388:299` 中文의 lang-main fill을 semantic variable `color/text/disabled`(→ `wf/gray-300`)에 바인딩했다. 부제의 `준비 중` label은 유지해 색만으로 상태를 전달하지 않는다.
3. title 아래 `643:4040` helper text 추가: `한국어와 English를 지원해요. 日本語와 中文은 준비 중이에요.` (`color/text/secondary`, 13px). title과 helper는 `643:4041` `head` auto-layout(gap 8)으로 묶었다.
4. `643:4088` EN selected variant: English row가 `brand/blue-soft` 배경·`brand/blue` 텍스트·check, 한국어 row는 기본 스타일. UI copy는 English(`Korean`, `English`, `Japanese · Coming soon`, `Chinese · Coming soon`, helper 영문, CTA `Next`). title은 원본과 같은 이중 언어 그대로다.
5. 구현 acceptance: KO/EN은 `updatePreferences(locale)`로 저장 후 새로고침 복구, JA/ZH row는 focus 가능하지만 `aria-disabled`이며 선택·저장·API 호출 0건. 360px에서 helper 2줄 wrap을 확인했다.

top-level frame이 1개 늘어 `02 UI Design` 구현 frame은 53개다. [Figma 핸드오프](./FIGMA_HANDOFF.md)와 `scripts/validate_docs.py`의 inventory를 같은 change set에서 53으로 갱신했다.

## FCR-002 증거

- 수정일: 2026-09-07, 수정자: Frontend (Claude Code Figma MCP)
- 상태: Figma 수정 완료. P0 기본값대로 P1 control을 **제거**했으며 disabled `준비 중`으로 남기지 않았다. 종료 조건 4(PM 범위 승인)와 5(구현 후 test ID)는 대기 중이다.

| 항목 | 값 |
| --- | --- |
| feed 여행 없음 | [`391:310`](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=391-310) — [변경 전](./evidence/fcr-002/before-391-310.jpg) · [변경 후](./evidence/fcr-002/after-391-310.jpg) |
| feed 활성 여행 | [`396:2926`](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=396-2926) — [변경 전](./evidence/fcr-002/before-396-2926.jpg) · [변경 후](./evidence/fcr-002/after-396-2926.jpg) |
| 게시물 상세 | [`398:611`](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=398-611) — [변경 전](./evidence/fcr-002/before-398-611.jpg) · [변경 후](./evidence/fcr-002/after-398-611.jpg) |
| 후보 저장 - 여행 선택 | [`399:658` S03-C1 / choose-trip · P0](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=399-658) — 변경 전 스크린샷 없음(구조는 S03-F1과 동일) · [변경 후](./evidence/fcr-002/after-399-658.jpg) |
| 후보 저장 - 저장 완료 | [`399:843` S03-C2 / saved · P0](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=399-843) — 변경 전 스크린샷 없음 · [변경 후](./evidence/fcr-002/after-399-843.jpg) |
| 후보 저장 - 중복 | [`399:1011` S03-C3 / duplicate · P0](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=399-1011) — 변경 전 스크린샷 없음 · [변경 후](./evidence/fcr-002/after-399-1011.jpg) |
| 후보 저장 - 오류 | [`399:1179` S03-C4 / error · P0 ERROR](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=399-1179) — 변경 전 스크린샷 없음 · [변경 후](./evidence/fcr-002/after-399-1179.jpg) |

변경 내용:

1. feed 두 frame의 NavBar에서 `btn/search`(`391:314`, `396:2930`)와 unread badge가 있는 `btn/bell`(`391:317`, `396:2932`)을 제거했다. 남은 `btn/filter`는 `FCR-003` 범위이며 그때 함께 판단한다. `nav-actions`(`391:313`, `396:2929`)는 원래 오른쪽 끝(x+w=385)에 맞춰 재배치했다.
2. `추천`·`팔로잉`·`최신` `Top tabs` row(`391:325`, `396:2938`, 40px)를 통째로 제거했다. P0 feed는 단일 목록이므로 `추천` 단독 tab도 두지 않았다. 아래 요소(Filter chips, Empty banner/Trip context, FeedPost card 4장)를 40px 위로 올렸고 TabBar·StatusBar는 그대로다.
3. 게시물 상세 author row(`399:631`)의 활성 `팔로우` button(`432:2990`)을 제거했다. row는 auto-layout이라 좋아요 count가 오른쪽 끝을 유지한다.
4. 구현 acceptance: `/feed`와 `/posts/:postId`는 검색·알림·팔로우·정렬 tab 관련 route와 API 호출이 0건이다. 카드의 `+`, 게시물 저장(`savePost`), 좋아요 등 P0 action은 유지된다.
5. 후보 저장 흐름의 배경 frame 4개(`S03-C1 / choose-trip · P0` `399:658`, `S03-C2 / saved · P0` `399:843`, `S03-C3 / duplicate · P0` `399:1011`, `S03-C4 / error · P0 ERROR` `399:1179`)는 모두 `S03-F1` feed를 복제한 배경 위에 sheet/toast를 얹은 구조라 1·2와 같은 검색·알림·tab줄 문제를 그대로 갖고 있었다. 네 화면 모두 동일하게 `btn/search`·`btn/bell`·`Top tabs` row를 제거하고 하위 콘텐츠를 40px 올렸으며 `nav-actions`를 오른쪽 끝으로 재정렬했다. `Sheet / TripPicker`, `Feedback / Toast`, `dim` 오버레이는 이동하지 않았다(원래도 배경과 독립된 절대 좌표).
   - 이 4개는 변경 전 스크린샷을 별도로 찍지 않고 바로 수정했다. 변경 전 구조는 `S03-F0`/`S03-F1`(위 1·2 before 스크린샷)과 완전히 동일한 NavBar/tab 레이아웃이었음을 `get_metadata` 원본 덤프로 확인했으며, after 스크린샷만 증거로 남긴다.

## FCR-003 증거

- 수정일: 2026-09-07, 수정자: Frontend (Claude Code Figma MCP)
- 상태: Figma 수정 완료. `listFeed`에 filter 계약이 없어 목표대로 **숨김**(제거)을 택했다. 종료 조건 4(BE/AI·PM 승인)와 5(구현 후 test ID)는 대기 중이다.

| 화면 | Figma node | 변경 전 | 변경 후 |
| --- | --- | --- | --- |
| S03-F0 여행 없음 feed | `391:310` | [전](./evidence/fcr-003/before-391-310.jpg) | [후](./evidence/fcr-003/after-391-310.jpg) |
| S03-F1 활성 여행 feed | `396:2926` | [전](./evidence/fcr-003/before-396-2926.jpg) | [후](./evidence/fcr-003/after-396-2926.jpg) |
| S03-C1 여행 선택 배경 | `399:658` | [전](./evidence/fcr-003/before-399-658.jpg) | [후](./evidence/fcr-003/after-399-658.jpg) |
| S03-C2 저장 완료 배경 | `399:843` | [전](./evidence/fcr-003/before-399-843.jpg) | [후](./evidence/fcr-003/after-399-843.jpg) |
| S03-C3 중복 배경 | `399:1011` | [전](./evidence/fcr-003/before-399-1011.jpg) | [후](./evidence/fcr-003/after-399-1011.jpg) |
| S03-C4 저장 오류 배경 | `399:1179` | [전](./evidence/fcr-003/before-399-1179.jpg) | [후](./evidence/fcr-003/after-399-1179.jpg) |

변경 내용 (6개 frame 동일):

1. NavBar의 `btn/filter`(설정 아이콘) button을 제거했다. `FCR-002`에서 이미 검색·알림을 지웠으므로 NavBar에는 로고만 남는다. 빈 `nav-actions` 컨테이너도 함께 삭제했다.
2. `전체`·`혼잡도 낮은 순`·`지금 가기 좋아요`·`서울` 4개 chip이 있는 `Filter chips` row(51px) 전체를 제거했다. 계약이 생기기 전에는 활성 control처럼 보이는 어떤 chip도 남기지 않는다.
3. 아래 콘텐츠(Empty banner 또는 Trip context, FeedPost 카드 4장, sheet/toast가 없는 배경 요소)를 51px 위로 당겼다. `Sheet / TripPicker`, `Feedback / Toast`, `dim` 오버레이는 그대로 두었다.
4. 구현 acceptance: `/feed`와 관련 sheet route는 `listFeed` filter 파라미터 호출이 0건이다. filter 계약(`FR-FED-05`, P1)이 생기면 이 chip row를 다시 추가하고 `comparisonEligible`·source 혼합 순위 금지 규칙을 함께 검증한다.

## FCR-004 증거

- 수정일: 2026-09-07, 수정자: Frontend (Claude Code Figma MCP)
- 상태: Figma에 새 top-level frame `S09-2 / preview-item · P0`(`655:4067`)을 추가했다. 종료 조건 4(BE/AI 계약 검토·PM 승인)와 5(구현 후 test ID)는 대기 중이다.

| 항목 | 값 |
| --- | --- |
| 새 화면 | [`655:4067` S09-2 / preview-item · P0](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=655-4067) — 신규 frame이라 변경 전 화면 없음 |
| 뼈대로 삼은 화면 | `439:3104` S09-D1 / preview-day · P1 (DAY scope) |
| 화면 상단 | [스크린샷](./evidence/fcr-004/after-655-4067-top.jpg) — 제목, `아직 일정에 반영되지 않았어요` 상태 배지, 요약, no-map-item 안내, 변경 전/후 `Card / TripItem` |
| 화면 하단 | [스크린샷](./evidence/fcr-004/after-655-4067-bottom.jpg) — 바뀌는 것, MetricDelta 4개, provenance, `Action / DecisionBar` (852px 뷰포트라 스크롤해야 보이는 영역을 별도로 캡처) |

구성 내용:

1. `S09-D1`을 복제해 뼈대(제목·상태 배지·요약·`change-list`·`Data / MetricDelta` 4개·`Action / DecisionBar`)를 재사용했다. DAY scope 전용인 `Map / Optimization`(before/after 2장)과 `timeline` row는 제거했다.
2. 지도 자리에는 이미 컴포넌트에 정의돼 있던 `Map / Optimization` `view=no-map-item` variant를 넣었다("이 항목은 날짜·시간 비교로 확인해요 · 단일 장소 변경에는 지도를 만들지 않아요"). 새 컴포넌트를 만들지 않았다.
3. before(`Card / TripItem` `state=view`)·after(`state=optimized`) 카드 2장을 세로로 배치했다. 시나리오는 "경복궁을 혼잡한 day 1(4·혼잡)에서 여유로운 day 3(1·매우 여유)로 이동"이다. 날짜가 이동 대상이라 `날짜 고정` lock chip은 숨겼고(instance 자식은 삭제 불가해 `visible=false`), `시간 고정`만 남겨 잠금이 이동 후에도 보존됨을 보여준다.
4. `change-list`, MetricDelta 4개, provenance 문구를 ITEM 시나리오로 교체했다. `혼잡 단계`·`피크 시간대 겹침` 2개는 개선 값을 채웠고, `이동시간 변화`·`이동거리 변화` 2개는 `경로 provider 미정 · 확인 불가`로 남겨 `FCR-005`(route provider 미정) 규칙과 OpenAPI `comparisonEligible=false`/unavailable reason 계약을 그대로 반영했다. 숫자를 만들어내지 않았다.
5. `Action / DecisionBar`(`state=preview`)의 `primaryLabel`을 "이 변경 적용"으로, 보조 버튼은 원래 있던 "현재 일정 유지"를 그대로 썼다.
6. 구현 acceptance: `OptimizationRun.status=READY`일 때 `proposals[0]`의 `changes[]`(before/after `TripItemState`), `metrics`(`comparisonEligible`/`crowdComparison`), `validation`(`allConstraintsPreserved`/`checks[]`), `dataProvenance[]`를 이 frame의 각 영역에 매핑한다. `적용`은 `OptimizationDecisionRequest{decision: APPLY}`, `현재 일정 유지`는 `KEEP`을 호출하며 승인 전에는 trip을 변경하지 않는다.

top-level frame이 1개 늘어 `02 UI Design` 구현 frame은 54개다(FCR-001 이후 53 → 54). [Figma 핸드오프](./FIGMA_HANDOFF.md)와 `scripts/validate_docs.py`의 inventory를 같은 change set에서 갱신했다.

## FCR-005 증거

- 수정일: 2026-09-07, 수정자: Frontend (Claude Code Figma MCP)
- 상태: Figma 수정 완료. route provider가 없는 P0에서 route 기반 시간·거리·우회 수치를 전부 제거했다. 종료 조건 4(BE/AI·PM 승인)와 5(구현 후 test ID)는 대기 중이다.

| 화면 | Figma node | 변경 전 | 변경 후 |
| --- | --- | --- | --- |
| S09-1 계산 중 | `415:2413` | [전](./evidence/fcr-005/before-415-2413.jpg) | [후](./evidence/fcr-005/after-415-2413.jpg) |
| S07-1 여행 보기 | `410:1738` | [전](./evidence/fcr-005/before-410-1738.jpg) | [후](./evidence/fcr-005/after-410-1738.jpg) |
| S11-1 Live | `418:2523` | [전](./evidence/fcr-005/before-418-2523.jpg) | [후](./evidence/fcr-005/after-418-2523.jpg) |

변경 내용:

1. `415:2413` — 큰 제목 `이동 경로와 시간 조건을 확인하고 있어요` → [권장 문구](#권장-문구와-시각-규칙)의 `혼잡 정보와 고정한 조건을 확인하고 있어요`(2줄 유지, 원래 폭 198px 텍스트박스를 그대로 사용해 레이아웃 흔들림 없음). step 목록의 `경로 계산` → `고정 조건 재확인`. `취소하고 My Trip으로`는 `FCR-014` 범위라 이번엔 그대로 뒀다.
2. `410:1738` — `경복궁`과 `인사동` 카드 사이 `↓ 1.2km · 도보 15분`(route 기반 거리·도보시간) 텍스트를 제거했다. `Body`가 auto-layout이라 아래 콘텐츠가 자동으로 붙었다. 바로 위 `더 여유로운 날짜가 있어요 · 비교하기` 배너는 `FCR-013` 범위라 이번엔 그대로 뒀다.
3. `418:2523` — 시간 필터 칩 3개를 route 시간 필터(`돌아가도 15분`/`30분`/`시간 상관없이`)에서 provider 없이도 계산 가능한 값(`가까운 순`/`혼잡 낮은 순`/`전체`)으로 바꿨다. 후보 카드 2장의 `돌아가도 +8분`/`돌아가도 +5분`(우회 시간)을 제거하고 장소 카테고리·지역만 남겼다. `group-label`의 `인사동 가는 길`(경로 문구)과 `live-context`의 `다음 일정 인사동까지 1시간 20분`(route 기반 소요시간)도 route 표현을 제거했다.
4. 직선거리(`Data / Distance` 컴포넌트)는 `FCR-009`(거리 기준·산식 표시) 종료 전까지 이번 change set에서 추가하지 않았다.
5. 구현 acceptance: `queryLiveAreas`/`listLiveAreaPlaces`/`getOptimization` READY 응답 어디에도 route provider가 계산한 시간·거리·우회 값이 없는 한 화면에 관련 수치를 표시하지 않는다. 필터는 provider 독립적인 값(거리순은 좌표 기반 정렬 가능, 혼잡순은 crowd 계약 존재)만 노출한다.

## FCR-006 증거

- 수정일: 2026-09-07, 수정자: Frontend (Claude Code Figma MCP)
- 상태: Figma 수정 완료. [권장 문구](#권장-문구와-시각-규칙)의 S14 guest/login copy를 그대로 적용했다. 종료 조건 4(BE/AI·PM 승인)와 5(구현 후 test ID)는 대기 중이다.

| 항목 | 값 |
| --- | --- |
| 화면 | [`422:2925` S14 / profile · P0](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=422-2925) |
| 변경 전 | [스크린샷](./evidence/fcr-006/before-422-2925.jpg) |
| 변경 후 | [스크린샷](./evidence/fcr-006/after-422-2925.jpg) |

변경 내용:

1. guest 카드 부제 `로그인하면 일정을 저장할 수 있어요`(brand blue, 활성 링크처럼 보임) → `로그인 없이 시작했어요 · 여행은 이 기기의 익명 세션에 저장돼요`(`color/text/secondary`, 중립 톤). 실제로 이미 저장되고 있는 사실을 설명하며 로그인을 저장의 전제 조건처럼 보이게 하지 않는다.
2. 같은 카드 안에 `로그인` 라벨과 `Data / Badge`(`tone=외곽선`) `준비 중`을 새 row로 추가했다. row 전체를 `opacity 0.55`로 흐리게 표시해 비활성 상태를 시각적으로도 전달한다. 새 컴포넌트를 만들지 않고 기존 `Data / Badge`를 재사용했다.
3. 구현 acceptance: 이 row는 keyboard focus를 받을 수 있지만 클릭·Enter로도 route 이동이나 API 호출을 만들지 않는다(`FR-PRO-02`, `client capability accountAuth=false`). `getCurrentOwner` 응답에 로그인 상태 필드가 없어도 이 화면은 항상 guest로 렌더링된다.

## FCR-007 증거

- 수정일: 2026-09-07, 수정자: Frontend (Claude Code Figma MCP)
- 상태: Figma 수정 완료. `423:2967`을 `FR-DAT-01`이 이미 요구하던 6개 상태와 일치시켰다(기능 인벤토리는 처음부터 6개였고 Figma만 5개로 뒤처져 있었다). 종료 조건 4(BE/AI·PM 승인)와 5(구현 후 test ID)는 대기 중이다.

| 항목 | 값 |
| --- | --- |
| 화면 | [`423:2967` S15 / data-guide · P0](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=423-2967) |
| 변경 전 상단 | [스크린샷](./evidence/fcr-007/before-423-2967-top.jpg) |
| 변경 전 하단(4·5번째 상태 부근) | [스크린샷](./evidence/fcr-007/before-423-2967-bottom.jpg) |
| 변경 후 상단 | [스크린샷](./evidence/fcr-007/after-423-2967-top.jpg) |
| 변경 후 하단(추가된 REPLAY row 포함) | [스크린샷](./evidence/fcr-007/after-423-2967-bottom.jpg) |

변경 내용:

1. 섹션 제목 `혼잡도 데이터 상태 5가지` → `혼잡도 데이터 상태 6가지`.
2. 기존 `unavailable` row(`423:2991`)를 복제해 6번째 `state-row`를 만들고, `Data / StateLabel` instance를 새 컴포넌트를 만들지 않고 기존 `state=replay` variant(라벨 `과거 관측 재생 · 실시간 아님`)로 교체했다. 설명 문구는 `과거 관측을 다시 보여드리는 데모예요. 지금 실시간이 아니에요.`로 작성했다 — `FR-LIV-07`의 "REPLAY를 현재 실시간처럼 표현 금지" 규칙을 그대로 반영한다.
3. 나머지 5개 row(`live`/`forecast`/`qualitative`/`stale`/`unavailable`)는 이미 정확했으므로 그대로 뒀다. 아래 `일정과 AI는 이렇게 동작해요` 가이드 섹션은 auto-layout으로 자동으로 밀렸다.
4. 구현 acceptance: `getDemoReadiness` 응답의 6개 state 값과 이 화면의 6개 row가 1:1로 대응한다. `data_guide_opened` 이벤트는 상태 개수와 무관하게 화면 진입 시 1회 기록한다.

## FCR-008 증거

- 수정일: 2026-09-07, 수정자: Frontend (Claude Code Figma MCP)
- 상태: `418:2523`(Live) 검색창 진입 뒤 흐름을 담을 새 top-level frame 4개 `S11-1B / search-*`를 추가했다. `FR-LIV-11`은 이미 `searchPlaces`/`getLivePlace` 계약을 갖고 있었으므로 계약을 새로 만들지 않고 화면만 연결했다. 종료 조건 4(BE/AI·PM 승인)와 5(구현 후 test ID)는 대기 중이다.

| 화면 | Figma node | 참고 |
| --- | --- | --- |
| Live 현재 상태(수정 없음, 대조용) | `418:2523` | [스크린샷](./evidence/fcr-008/before-418-2523.jpg) |
| empty 관례 참고(수정 없음, 대조용) | `420:2950` | [스크린샷](./evidence/fcr-008/before-420-2950.jpg) |
| 검색 결과 | `684:4156` S11-1B / search-results · P0 | [스크린샷](./evidence/fcr-008/after-684-4156-results.jpg) |
| 검색 중 | `684:4330` S11-1B / search-results-loading · P0 LOADING | [스크린샷](./evidence/fcr-008/after-684-4330-loading.jpg) |
| 결과 없음 | `684:4366` S11-1B / search-results-empty · P0 EMPTY | [스크린샷](./evidence/fcr-008/after-684-4366-empty.jpg) |
| 요청 실패 | `684:4402` S11-1B / search-results-error · P0 ERROR | [스크린샷](./evidence/fcr-008/after-684-4402-error.jpg) |

구성 내용:

1. `418:2523`을 복제해 뼈대(검색창, 지도 marker, `Sheet`)를 재사용하고 `Nav / Segment`(현재 여행지/다른 관광지), `time-filter`, `group-label`처럼 검색 모드와 무관한 요소를 제거했다.
2. 검색 결과 목록은 기존 `candidate-row`(`Sheet / TripPicker`가 아니라 Live 화면 고유 컴포넌트)를 재사용하되, crowd/freshness/source 영역을 숨겼다. 검색 결과 단계는 `PlaceSummary`만 있고 아직 `getLivePlace`를 호출하기 전이라 live 데이터가 없어야 정확하다. `+ 담기` 대신 `선택`으로 CTA를 바꿔 이 화면이 후보 저장이 아니라 canonical 장소 확정 단계임을 표시했다.
3. loading은 스피너와 `"⟨검색어⟩" 검색 중이에요`, empty는 검색어·결과 0건 안내와 다른 검색어 유도 문구, error는 `검색을 확인하지 못했어요`와 `다시 시도` 버튼으로 만들었다. 세 상태 모두 새 컴포넌트를 만들지 않고 텍스트·아이콘만 추가했다.
4. 작업 중 컬러 이모지(🔍)에 semantic color 변수를 강제 바인딩하면 렌더링이 깨지는 문제가 있어 원래 기본 색으로 되돌렸다.
5. `getLivePlace` 응답의 `dataState=UNAVAILABLE`(coverage 없음)은 검색 결과 화면이 아니라 canonical 선택 뒤 진입하는 `419:2617` 장소 상세에서 처리한다 — 검색 자체는 항상 `PlaceSummary` 목록만 반환하므로 이 4개 frame에는 UNAVAILABLE variant를 별도로 만들지 않았다.
6. 구현 acceptance: 검색창 focus → `searchPlaces`(debounce) → 목록에서 canonical 선택 → `getLivePlace`로 이동한다. `PlaceSearchRequest.query`는 최대 100자이고 원문을 로그·analytics에 남기지 않는다(no-store, APM 로깅 제외).

top-level frame이 4개 늘어 `02 UI Design` 구현 frame은 58개다(FCR-007까지 54 → 58). [Figma 핸드오프](./FIGMA_HANDOFF.md), [기능 인벤토리](../product/FUNCTIONAL_INVENTORY.md)의 `FR-LIV-11` Figma column과 `scripts/validate_docs.py`의 inventory를 같은 change set에서 갱신했다.

## FCR-009 증거

- 수정일: 2026-09-07, 수정자: Frontend (Claude Code Figma MCP)
- 상태: Figma 수정 완료. OpenAPI 계약을 확인한 결과 거리를 표시할 근거가 없어 목표의 두 갈래 중 **숨김**을 택했다. 종료 조건 4(BE/AI·PM 승인)와 5(구현 후 test ID)는 대기 중이다.

### 계약 확인 결과

`docs/api/openapi.yaml`을 확인한 결과 피드·게시물 경로에는 거리 필드가 **존재하지 않는다**.

| 스키마 | 거리 필드 | 좌표 |
| --- | --- | --- |
| `FeedCard` | 없음 | 없음 |
| `PostSummary` | 없음 | 없음 |
| `PostDetail` | 없음 | 없음 |
| `PlaceSummary` | 없음 | 없음 (`PlaceDetail`에만 `location` 존재) |

즉 feed 카드와 게시물 상세는 거리를 계산할 좌표조차 받지 않는다. 기준점(trip anchor·사용자 선택 장소)도 계약에 없다. 따라서 화면의 `2.1km`·`4.8km` 같은 값은 근거 없는 표시이므로 제거했다. 표시하려면 먼저 BE/AI가 기준점·산식·provenance를 포함한 additive 계약을 제안해야 한다.

| 항목 | 값 |
| --- | --- |
| feed 여행 없음 | `391:310` — [변경 전](./evidence/fcr-009/before-391-310-feed.jpg) · [변경 후](./evidence/fcr-009/after-391-310-feed.jpg) |
| feed 활성 여행 | `396:2926` — [변경 후](./evidence/fcr-009/after-396-2926-feed.jpg) |
| 게시물 상세 | `398:611` — [변경 전](./evidence/fcr-009/before-398-611-post.jpg) · [변경 후](./evidence/fcr-009/after-398-611-post.jpg) |

변경 내용:

1. `Card / FeedPost` **컴포넌트 정의**(`01 Components`)의 두 variant(`lines=1` `390:406`, `lines=2` `396:496`)에서 `Data / Distance` instance를 `visible=false`로 숨겼다. 컴포넌트 레벨 수정이라 `02 UI Design`의 **28개 인스턴스**(feed 2개 + sheet 배경 4개 + 저장 sheet 1개 화면에 걸친 카드들)가 한 번에 반영됐다. 화면별로 28번 수정하지 않았다.
2. 게시물 상세 `398:611`의 장소 카드 `place-name`(`431:2990`)에 인라인으로 박혀 있던 거리를 제거했다: `경복궁 · 관광지 · 종로구 · 2.1km` → `경복궁 · 관광지 · 종로구`.
3. `Data / Distance` 컴포넌트 자체는 **삭제하지 않고 숨기기만** 했다. `COMPONENT_CATALOG.md`의 C43 계약(value, unit, mode, confidence, unavailable reason / route·haversine provenance)은 이미 올바르므로 그대로 두었고, 최상위 component 49개 수도 유지된다. 계약이 생기면 `visible=true`로 되살려 기준점·source와 함께 표시한다.
4. 구현 acceptance: 거리 필드가 없는 현재 계약에서 FE는 거리 UI를 렌더링하지 않는다. 좌표를 client에서 임의로 계산해 표시하지 않는다(P0은 정밀 위치를 다루지 않는다). 향후 계약이 추가되면 기준점 라벨과 산식(직선/route), source를 값과 함께 표시하고, 기준점이 없으면 unavailable reason을 제공한다.

## FCR-011 증거

- 수정일: 2026-09-07, 수정자: Frontend (Claude Code Figma MCP)
- 상태: Figma 수정 완료. 다만 아래 **미해결 blocker**가 있어 BE/AI 결정이 필요하다. 종료 조건 4(BE/AI·PM 승인)와 5(구현 후 test ID)는 대기 중이다.
- 계기: FCR-009 작업 중 사용자가 `Card / FeedPost`의 `공식 관광정보 기반` 배지와 `실시간 관측 · ⓒ한국관광공사` 문구가 중복돼 보인다고 지적했고, 확인 결과 FCR-011이 지목한 `392:368`과 동일한 노드였다.

### 계약 근거

| 문서 | 규칙 |
| --- | --- |
| `SOURCE_CATALOG.md` | "KTO 관광정보·집중률 예측과 서울 실시간 관측이 한 카드에 있으면 provenance primitive를 각각 렌더링하고 하나의 `ⓒ한국관광공사` 문구로 합치지 않는다" |
| `api/README.md` | `SEOUL_CITYDATA`의 `attribution`을 그대로 표시하고 "KTO attribution과 합치거나 provider별 문구를 client에서 다시 만들지 않는다" |
| `DataProvenance` example | `SEOUL_CITYDATA.attribution` = `출처: 서울특별시 「서울시 실시간 도시데이터」(2022년 공개, 공공누리 제1유형)` |

| 노드 | 변경 전 | 변경 후 |
| --- | --- | --- |
| feed 카드 `392:368` | [전](./evidence/fcr-011/before-392-368-feedcard.jpg) | [후](./evidence/fcr-011/after-392-368-feedcard.jpg) |
| post 장소 카드 `399:613` | [전](./evidence/fcr-011/before-399-613-postcard.jpg) | [후](./evidence/fcr-011/after-399-613-postcard.jpg) |
| Live 후보 행 `418:5199` | [전](./evidence/fcr-011/before-418-5199-liverow.jpg) | [후](./evidence/fcr-011/after-418-5199-liverow.jpg) |
| feed 전체 `391:310` | — | [후](./evidence/fcr-011/after-391-310-feed.jpg) |
| post 전체 `398:611` | — | [후](./evidence/fcr-011/after-398-611-post.jpg) |
| Live 전체 `418:2523` | — | [후](./evidence/fcr-011/after-418-2523-live.jpg) |

변경 내용:

1. **중복 제거** — `Card / FeedPost` 컴포넌트의 `Data / Badge`(`tone=공식Seed`, 문구 `공식 관광정보 기반`)를 제거하고 `Data / StateLabel`로 교체했다. 배지가 출처를, `source` 텍스트가 상태와 출처를 동시에 말하던 중복이 사라지고 **상태는 StateLabel, 출처는 source 텍스트**로 역할이 분리됐다. 새 컴포넌트를 만들지 않았다.
2. **출처 정정** — 28개 feed 카드 인스턴스를 데이터 종류별로 나눠 처리했다. `수문장 교대의식` 카드 7개는 `SEOUL_CITYDATA` 실시간 관측이므로 `StateLabel=live` + `출처: 서울특별시 「서울시 실시간 도시데이터」`, 나머지 21개는 KTO 예측이므로 `StateLabel=forecast` + `출처: ⓒ한국관광공사`로 설정했다.
3. **레이아웃 재배치** — 긴 서울시 attribution이 제목과 겹쳐서 카드 내부 순서를 `StateLabel → title → region → source`로 바꾸고 `source`를 고정폭 wrap(156px)으로 전환했다.
4. `399:613` post 장소 카드와 `418:5199`/`418:5219` Live 후보 행의 `ⓒ한국관광공사`도 서울시 출처로 정정했다. post 카드는 폭 329px wrap으로 전문이 들어간다.

### 미해결 blocker — attribution 길이 vs 카드 폭

`SEOUL_CITYDATA`의 attribution 전문은 **46자**(`출처: 서울특별시 「서울시 실시간 도시데이터」(2022년 공개, 공공누리 제1유형)`)다. 계약은 이를 **그대로** 표시하라고 요구하지만 세 화면 모두 폭이 부족해 **전부 축약 상태**이며 계약 위반이다.

| 위치 | 텍스트 폭 | 현재 표기 | 누락 | 계약 |
| --- | --- | --- | --- | --- |
| post 장소 카드 `399:625` | 329px (카드 361px), 11px, 2줄 | `… 출처: 서울특별시 「서울시 실시간 도시데이터」` | `(2022년 공개, 공공누리 제1유형)` | 위반 |
| feed 카드 `I392:368;396:498` | 156px (카드 172px), 10px, 2줄 | `출처: 서울특별시 「서울시 실시간 도시데이터」` | `(2022년 공개, 공공누리 제1유형)` | 위반 |
| Live 후보 행 `418:5215` | 190px, 10px, 2줄 | `출처: 서울특별시` | 나머지 전부 | 위반 |

11px 기준 46자 전문은 약 300px 이상을 차지하므로 172px·190px 카드에서는 3줄 이상이 되어 다른 정보를 밀어낸다. 세 곳 모두 축약 없이는 레이아웃이 성립하지 않는다.

`CON-004` A2에서 BE/AI가 `attributionShort`(optional nullable, 1~160자, 없으면 full 표시·FE 임의 절단 금지)를 이미 **추가하기로 결정**했으나, 해당 계약은 backend 작업 트리의 `0.2.1-rc.1`에만 있고 `origin/backend`·`main`은 아직 `0.2.0`이라 FE가 값을 확인할 수 없다. 따라서 현재 Figma 표기는 **잠정**이며 다음이 필요하다.

1. `0.2.1-rc.1`이 `backend → main`으로 병합되어 FE가 `SEOUL_CITYDATA`의 `attributionShort` 실제 값을 확인
2. 그 값에 맞춰 세 화면의 표기를 다시 맞추고 전문은 상세·데이터 안내(S15)에서 제공
3. 좁은 카드에서 전문을 접고 tap으로 펼치는 UI가 필요한지 `api/README.md`에 규칙 확정

이 항목이 닫히기 전에는 제출 profile의 출처 표시를 승인된 것으로 보지 않는다. 진행 상황은 issue #11(`CON-004`)에서 추적한다.

## FCR-010 증거

- 수정일: 2026-09-07, 수정자: Frontend (Claude Code Figma MCP)
- 상태: Figma 수정 완료. 종료 조건 4(BE/AI·PM 승인)와 5(구현 후 test ID)는 대기 중이다.

| 항목 | 값 |
| --- | --- |
| 화면 | [`415:2268` S09-0 / setup-item · P0](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=415-2268) |
| 변경 전 | [스크린샷](./evidence/fcr-010/before-415-2268.jpg) |
| 변경 후 | [스크린샷](./evidence/fcr-010/after-415-2268.jpg) |

변경 내용:

1. **scope segment 복원** — `Form / OptimizationScope` 인스턴스가 `scope=item` variant인데도 정작 `seg/item`(`이 장소`)이 삭제돼 있고 남은 두 세그먼트가 `전체`/`Day1`로 override돼 있었다. 인스턴스를 새로 만들어 컴포넌트 정의대로 `이 장소`·`이 날짜`·`전체 여행` 3개를 복원하고, P0에서 쓰지 않는 `이 날짜`·`전체 여행`은 `opacity 0.4`로 비활성 표시했다. 컴포넌트 자체는 수정하지 않았다.
2. **준비 중 명시** — 세그먼트 아래에 `이 날짜 · 전체 여행은 준비 중이에요` 안내를 추가했다. DAY/TRIP은 선택할 수 없으므로 `createOptimization` 요청이 발생하지 않는다.
3. **`targetItemId` 선택 control 추가** — 고정 문구 `경복궁 하나만 · 같은 장소를 더 여유로운 날짜로 옮겨요`를 `어떤 장소를 옮길까요?` 질문과 실제 선택 목록으로 교체했다. 각 row는 radio + 장소명 + `day/시간 · 혼잡도` 메타로 구성되며, 사용자가 고른 row가 `CreateItemOptimizationRequest.targetItemId`가 된다.
4. **잠금 규칙 일관성** — 배경 일정의 `경복궁`은 `날짜 고정`(user-locked) 상태라 날짜 이동 대상이 될 수 없다. 따라서 경복궁 row를 `opacity 0.55` 비활성으로 두고 `날짜 고정이라 옮길 수 없어요`를 표시했으며, 잠금이 없는 `인사동`을 선택 상태로 바꿨다. 하단 문구도 `장소는 그대로예요 · 날짜만 옮겨요`, `고정 조건 · 날짜 고정된 장소는 대상에서 빠져요`로 정정해 잠금과 대상 선택이 서로 모순되지 않게 했다.
5. 구현 acceptance: scope는 P0에서 `ITEM` 고정이며 `targetItemId`는 사용자가 명시적으로 선택한 item id다. `MUST_VISIT`·`DATE`·`TIME`·`RESERVATION` 잠금은 독립적이며 자동 해제하지 않으므로, 날짜 고정 item은 목록에서 비활성으로 노출하되 숨기지 않는다.

### FCR-005 누락분 정정

FCR-010 작업 중 배경 일정에 `↓ 1.2km · 도보 15분`이 남아 있는 것을 발견했다. FCR-005에서 `410:1738` 한 곳만 제거했으나 실제로는 같은 route 기반 텍스트가 11곳에 더 있었다. 이번 change set에서 전부 제거했다.

| Figma frame | node |
| --- | --- |
| `S07-2 / edit · P0` | `411:1868` |
| `S07-2 / move-date · P0` | `527:4107` |
| `S07-9 / discard-dialog · P0` | `413:2045` |
| `S07-6 / replace-compare · P0` | `414:2372`, `527:4559` |
| `S09-0 / setup-item · P0` | `415:2299` |
| `S07-4 / added · P0` | `479:3838`, `527:4402` |
| `S07-10 / move-date · P0` | `521:3998`, `527:4717` |
| `S07-10b / date-lock-confirm · P0` | `527:3898` |

route provider가 정해지기 전까지 이 값들을 다시 넣지 않는다.

## FCR-012 증거

- 수정일: 2026-09-08, 수정자: Frontend (Claude Code Figma MCP)
- 상태: map OFF를 P0 기본으로 하는 목록 전용 frame 5개를 추가했다. 종료 조건 4(BE/AI·PM 승인)와 5(구현 후 test ID)는 대기 중이다.

| 화면 | Figma node | 참고 |
| --- | --- | --- |
| map ON (capability, 기존) | `418:2523` → 이름을 `S11-1 / live · P0 · map ON (capability)`로 변경 | [스크린샷](./evidence/fcr-012/before-418-2523-mapon.jpg) |
| 목록 default | `716:4377` S11-1L / live-list · P0 | [스크린샷](./evidence/fcr-012/after-716-4377-default.jpg) |
| 목록 loading | `718:4521` S11-1L / live-list-loading · P0 LOADING | [스크린샷](./evidence/fcr-012/after-718-4521-loading.jpg) |
| 목록 empty | `718:4584` S11-1L / live-list-empty · P0 EMPTY | [스크린샷](./evidence/fcr-012/after-718-4584-empty.jpg) |
| 목록 error | `718:4647` S11-1L / live-list-error · P0 ERROR | [스크린샷](./evidence/fcr-012/after-718-4647-error.jpg) |
| 목록 unavailable | `718:4710` S11-1L / live-list-unavailable · P0 UNAVAILABLE | [스크린샷](./evidence/fcr-012/after-718-4710-unavailable.jpg) |

구성 내용:

1. **P0 기본은 목록** — `418:2523`을 복제해 `Map / Base`와 `Map / Marker` 4개를 제거하고, 그 자리에 `queryLiveAreas` 응답의 `LiveArea[]`를 그대로 보여주는 `area-list`를 넣었다. 헤더는 `지금 권역 혼잡` + `Data / StateLabel`(`state=live`)이고, 부제에 기준시각·출처·조작 안내를 둔다. 각 row는 `LiveArea.name` + `CrowdState / Live`(`00 Wireframes` 페이지의 기존 컴포넌트 `167:958`, `ordinal` variant) + 혼잡 label + chevron이다. 선택된 권역(경복궁)은 `color/action/subtle` 배경과 `color/action/primary` 테두리로 표시한다.
2. **권역 → 장소 흐름은 sheet가 그대로 이어받는다** — `Sheet`(segment, context, 정렬 chip, `candidate-row`)는 기존 `418:2523`의 것을 그대로 유지했다. 지도 없이도 권역 선택 → 장소 목록 → 상세 진입이 완결된다(`Live P0: map 없이도 검색·목록·선택·상세 진입이 완결` 규칙). 목록이 4 row라 `Sheet`를 y=368로 내리고 높이를 484로 맞춰 겹침을 없앴다.
3. **상태 4종** — loading은 스피너 + `권역 혼잡을 불러오고 있어요`, empty는 `StateLabel=unavailable` + `표시할 권역이 없어요`와 검색 유도, error는 `권역 혼잡을 확인하지 못했어요` + `다시 시도`, unavailable은 `StateLabel=unavailable` + `지금은 실시간 관측을 쓸 수 없어요 · 출처: 서울특별시 · 데이터 제공이 중단됐어요 · 값을 대신 만들지 않아요`. 네 화면 모두 sheet에는 권역 미선택 안내만 남기고 장소 목록을 비웠다.
4. **map ON은 capability variant로 격하** — 기존 `418:2523`은 삭제하지 않고 이름만 `map ON (capability)`로 바꿔 provider·license·attribution 승인 뒤에만 열리는 화면임을 표시했다. node id는 그대로라 기존 문서 링크가 깨지지 않는다.
5. 구현 acceptance: `/live` 기본 route는 `map` capability가 OFF일 때 `716:4377`을 렌더링하고 `queryLiveAreas`만 호출한다. `LiveAreaResult.mode`가 `UNAVAILABLE`이면 `718:4710`, `areas=[]`이면 `718:4584`, Problem이면 `718:4647`이다. map ON일 때도 같은 filter/selection state를 공유하며 목록 view가 항상 함께 존재한다(`map에는 같은 정보·필터의 list view가 있어야 한다`).
6. 새 컴포넌트를 만들지 않았다. `CrowdState / Live`가 `01 Components`가 아니라 `00 Wireframes`에 있는 점은 `COMPONENT_CATALOG` 검토 시 BE/AI·PM이 확인할 항목이다.

top-level frame이 5개 늘어 `02 UI Design` 구현 frame은 63개다(FCR-008까지 58 → 63). [Figma 핸드오프](./FIGMA_HANDOFF.md)와 `scripts/validate_docs.py`의 inventory를 같은 change set에서 갱신했다.

## FCR-013 증거

- 수정일: 2026-09-08, 수정자: Frontend (Claude Code Figma MCP)
- 상태: Figma 수정 완료. 목표의 첫 선택지인 **P0 제거**를 택했다. 종료 조건 4(BE/AI·PM 승인)와 5(구현 후 test ID)는 대기 중이다.

| 항목 | 값 |
| --- | --- |
| 화면 | [`410:1738` S07-1 / view · P0](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=410-1738) |
| 변경 전 | [스크린샷](./evidence/fcr-013/before-410-1738.jpg) |
| 변경 후 | [스크린샷](./evidence/fcr-013/after-410-1738.jpg) |

변경 내용:

1. `Body` 안의 `suggest` 프레임(`410:4431`, `더 여유로운 날짜가 있어요` + `비교하기 →`)을 제거했다. `Body`가 auto-layout이라 인사동 카드가 위로 붙었다. 이 배너는 `410:1738` 한 곳에만 있었다.
2. 제거 근거: 배너가 성립하려면 `getTrip`과 별개로 `getPlaceCrowdForecast`를 호출하고, `comparisonAxis=TEMPORAL` 적격성(`FR-DAT-02`)을 통과하며, "더 여유롭다"를 판정할 **표시 threshold**가 있어야 한다. 세 번째가 어느 문서에도 정의돼 있지 않고, 착수 조건이 "계약 연결·비교 규칙이 없으면 banner를 구현하지 않음"이다.
3. 단순 예보 비교와 적용 가능한 최적화 제안의 구분은 이미 `FCR-004`/`FCR-010`이 담당한다. 여행 보기에서 최적화로 가는 진입은 `AI로 일정 최적화` 버튼 하나로 충분하며 계약 없는 배너로 유도하지 않는다.
4. threshold 규칙이 `FR-DAT-02`/`FR-DAT-05`에 추가되면 그때 별도 FCR로 배너를 다시 설계한다.

## FCR-014 증거

- 수정일: 2026-09-08, 수정자: Frontend (Claude Code Figma MCP)
- 상태: Figma 수정 완료. [권장 문구](#권장-문구와-시각-규칙)의 `내 여행으로 돌아가기`를 그대로 적용했다. 종료 조건 4(BE/AI·PM 승인)와 5(구현 후 test ID)는 대기 중이다.

| 항목 | 값 |
| --- | --- |
| 화면 | [`415:2413` S09-1 / loading · P0 LOADING](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=415-2413) |
| 변경 전 | [스크린샷](./evidence/fcr-014/before-415-2413.jpg) |
| 변경 후 | [스크린샷](./evidence/fcr-014/after-415-2413.jpg) |

변경 내용:

1. `btn/cancel`(`415:2430`) → `btn/back-to-trip`, 라벨 `취소하고 My Trip으로` → `내 여행으로 돌아가기`. 탭 명칭(`내 여행`)과 일치하고 "취소"라는 단어를 쓰지 않는다.
2. 버튼 아래 안내(`415:2432`)를 `아직 일정은 그대로예요` 한 줄에서 3줄로 늘렸다: `아직 일정은 그대로예요 / 계산은 계속돼요 / 내 여행에서 다시 열 수 있어요`. 이 화면을 떠나도 server run이 취소되지 않고 `getOptimization`으로 같은 run URL을 다시 열 수 있음을 사용자에게 알린다. `Body` 열(200px)에 맞춰 12px 3줄로 배치했다.
3. P0에는 cancel operation이 없으므로 취소 버튼을 만들지 않았다. 실제 취소 UI는 계약과 상태 전이가 추가된 뒤 별도 FCR로 다룬다.
4. 구현 acceptance(`FR-OPT-03`): 뒤로가기·탭 이동·client timeout 어느 것도 run 상태를 바꾸지 않는다. `/trip/:tripId/optimizations/:runId`는 refresh·재진입 시 `getOptimization`으로 현재 상태를 복원한다.

## FCR-015 증거

- 수정일: 2026-09-08, 수정자: Frontend (Claude Code Figma MCP)
- 상태: `417:2412`의 되돌리기를 persistent 패널로 바꾸고 `revertAvailability` 상태별 frame 3개를 추가했다. 종료 조건 4(BE/AI·PM 승인)와 5(구현 후 test ID)는 대기 중이다.

| 상태 | Figma node | 참고 |
| --- | --- | --- |
| 변경 전 (toast만) | `417:2412` | [스크린샷](./evidence/fcr-015/before-417-2412.jpg) |
| `AVAILABLE` (기본, 수정) | `417:2412` S09-3 / applied · P0 | [스크린샷](./evidence/fcr-015/after-417-2412-available.jpg) |
| 되돌리는 중 | `724:4602` S09-3 / applied-reverting · P0 SUBMITTING | [스크린샷](./evidence/fcr-015/after-724-4602-reverting.jpg) |
| `REVERTED` | `724:4730` S09-3 / applied-reverted · P0 | [스크린샷](./evidence/fcr-015/after-724-4730-reverted.jpg) |
| `EXPIRED` | `724:4858` S09-3 / applied-expired · P0 REVERT_WINDOW_EXPIRED | [스크린샷](./evidence/fcr-015/after-724-4858-expired.jpg) |

구성 내용:

1. **persistent 패널** — `moved-note`(`484:3516`, 한 줄 텍스트)를 `applied-panel`(`724:4594`)로 교체했다. 결과 문구 + `revert-state` 배지, `revision-line`(`일정 v7 → v8 · 10/7 14:35 적용 · 10/8 14:35까지 되돌릴 수 있어요`), `btn/revert`(`이전 일정(v7)으로 되돌리기`)로 구성된다. `ApplyOptimizationDecision`의 `beforeRevisionId`/`afterRevisionId`는 사용자에게 version 번호로, `revertUntil`은 기한 시각으로 보여준다.
2. **toast는 보조** — `Feedback / Toast`(`일정을 업데이트했어요 · 되돌리기`)는 `417:2412`에 그대로 두되, 사라져도 패널이 남아 되돌리기 진입점이 유지된다. 상태 variant 3개에서는 toast를 제거했다(진입 직후에만 뜨는 요소).
3. **상태 4종** — 배지·revision 줄·버튼이 `revertAvailability`에 따라 바뀐다. `AVAILABLE`: `되돌리기 가능` + 활성 버튼. 되돌리는 중: `되돌리는 중` + 버튼 disabled(`opacity 0.5`, 중복 실행 차단). `REVERTED`: `되돌림` + 버튼 없음 + `일정 v8 → v9 · v7과 같은 일정이에요`, 경복궁 카드가 day 1로 복귀하고 `최적화 반영` 배지가 사라지며 day 3 header가 없어진다. `EXPIRED`: `기한 지남` + disabled 버튼에 `되돌릴 수 없어요 · 일정 편집에서 직접 바꿔요`로 대안 동선을 안내한다.
4. `NOT_APPLICABLE`(다른 곳에서 일정이 또 바뀐 경우)은 별도 frame을 만들지 않았다. `485:3517` stale reference의 `TRIP_CHANGED` 처리와 같은 화면이며, 패널 배지만 `기한 지남` 대신 `일정이 바뀌어 되돌릴 수 없어요`로 바꾼다.
5. 구현 acceptance(`FR-OPT-09`): 되돌리기 활성 여부는 server의 `revertAvailability`만 따르고 기기 시계로 계산하지 않는다(필드 부재 시 비활성). `revertOptimizationDecision`은 `Idempotency-Key`로 중복 실행을 막고 성공 시 새 revision(`v9`)을 만들며 decision log는 보존한다. 버튼·배지는 keyboard focus와 accessible name(`이전 일정 v7으로 되돌리기, 10/8 14:35까지`)을 갖는다.
6. 새 컴포넌트를 만들지 않았다. 배지·버튼은 이 화면 전용 auto-layout이며, 반복 사용이 확인되면 `COMPONENT_CATALOG` 검토 시 `Data / Badge` variant로 승격할지 결정한다.

top-level frame이 3개 늘어 `02 UI Design` 구현 frame은 66개다(FCR-012까지 63 → 66). [Figma 핸드오프](./FIGMA_HANDOFF.md)와 `scripts/validate_docs.py`의 inventory를 같은 change set에서 갱신했다.
