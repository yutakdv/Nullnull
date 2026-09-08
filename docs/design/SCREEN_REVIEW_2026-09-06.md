---
aliases:
  - "2026-09-06 52개 Figma 화면 확인표"
doc_type: reference
status: conditional
area: design
tags:
  - nullnull/reference
  - nullnull/design
---

# 52개 Figma 화면 확인표

09-06 현재 Figma 02 UI Design과 기존 52개 node 목록을 대조했다. 화면 또는 인접 화면을 직접 관찰한 기록이며, 버튼을 실제 서비스에서 실행하거나 prototype 연결선·키보드 동작을 검증했다는 뜻은 아니다. 모든 행의 구현/인수 상태는 미검증이다.

[PM 발견 사항/우선순위](../project/PM_REVIEW_2026-09-06.md) · [기능 ID 정본](../product/FUNCTIONAL_INVENTORY.md) · [API/상태 연결](FIGMA_HANDOFF.md) · [FCR](FIGMA_CHANGE_REQUESTS.md)

BA 링크에서 기능 ID→operationId→entity→예정 test ID→담당/검토자를 따라간다. Figma에 보이는 숫자·영업 정보·시간·거리·이미지를 실제 검증 데이터로 쓰지 않는다.

## 화면별 관찰과 인수 조건

| Node | 범위 | 실제 관찰 | 구현 전에 닫을 동작/검증 | PM 항목·담당 작업 |
| --- | --- | --- | --- | --- |
| [388:257](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=388-257) | P0 | bootstrap 대기 화면 | 새 세션·재방문·timeout·401 복구 | PM-017; [BA-010](../roles/BACKEND_AI_PLAYBOOK.md#ba-010) |
| [388:277](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=388-277) | P0 | English 준비 중 오표기 | KO/EN 저장·복구, JA/ZH 요청0 | PM-001; [BA-011](../roles/BACKEND_AI_PLAYBOOK.md#ba-011) |
| [388:321](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=388-321) | P0 | 관심사 기반 소개·계속·건너뛰기 | 고정 feed/P1 약속 정정, 완료 상태 복구 | PM-001; [BA-011](../roles/BACKEND_AI_PLAYBOOK.md#ba-011) |
| [391:310](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=391-310) | P0 | 여행 없음 CTA, P1 탐색 control/혼잡 정렬 노출 | 여행 없음과 feed 오류 분리·P1 요청0 | PM-001/010/011; [BA-032](../roles/BACKEND_AI_PLAYBOOK.md#ba-032) |
| [396:2926](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=396-2926) | P0 | 여행 context·후보 저장·활성 필터 | tripId는 저장 상태만 변경·고정 순서 | PM-001/010/011; [BA-032](../roles/BACKEND_AI_PLAYBOOK.md#ba-032) |
| [398:611](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=398-611) | P0 | 장소·하트/작성자·follow·거리·저장 | projection·출처·404·저장/후보 분리 | PM-001/010/011; [BA-032](../roles/BACKEND_AI_PLAYBOOK.md#ba-032) |
| [399:658](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=399-658) | P0 | 대상 여행 선택·새 여행 만들기 | 새 여행 왕복 시 장소 복구·다시 명시적 저장 | PM-004/009; [BA-034](../roles/BACKEND_AI_PLAYBOOK.md#ba-034) |
| [399:843](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=399-843) | P0 | 저장 완료 toast | persistent 결과와 후보 보기·item/version 미변경 | PM-009; [BA-034](../roles/BACKEND_AI_PLAYBOOK.md#ba-034) |
| [399:1011](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=399-1011) | P0 | 중복 안내 toast | 기존 후보1개·재저장/일정화 상태 구분 | PM-009; [BA-034](../roles/BACKEND_AI_PLAYBOOK.md#ba-034) |
| [399:1179](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=399-1179) | P0 | 저장 실패·재시도 toast | 일정 미변경 문구·지속 오류·같은 key 재시도 | PM-009; [BA-034](../roles/BACKEND_AI_PLAYBOOK.md#ba-034) |
| [409:1595](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=409-1595) | P0 | 날짜/시간은 나중에, 담아두기 | 여행 선택 확정·pending·닫기≠서버 취소 | PM-009/010; [BA-034](../roles/BACKEND_AI_PLAYBOOK.md#ba-034) |
| [438:3012](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=438-3012) | P0 | 여행 날짜 범위 | 1~30일·역전·locale/timezone·뒤로 복구 | PM-004/005/008; [BA-030](../roles/BACKEND_AI_PLAYBOOK.md#ba-030) |
| [438:3108](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=438-3108) | P0 | 동행인과 스타일 chip | taxonomy·단일/복수·weight·0개/중복 | PM-006; [BA-030](../roles/BACKEND_AI_PLAYBOOK.md#ba-030) |
| [438:3134](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=438-3134) | P0 | 계획 수준 3개 분기 | 각 경로의 입력 보존·완료 조건 | PM-004/005; [BA-030](../roles/BACKEND_AI_PLAYBOOK.md#ba-030) |
| [438:3158](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=438-3158) | P0 | 날짜 없는 필수 장소 목록·이대로 채우기 | canonical POI→초안·MUST_VISIT 보존 | PM-004/006; [BA-022](../roles/BACKEND_AI_PLAYBOOK.md#ba-022) |
| [400:1201](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=400-1201) | P0 | 붙여넣기/직접 입력 분기 | 방법 변경·원문 비복구 안내·P0 file 제외 | PM-005; [BA-030](../roles/BACKEND_AI_PLAYBOOK.md#ba-030) |
| [401:1221](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=401-1221) | P0 | 연도 없는 날짜/오전·오후 원문 | server 전송 안내·연도 기준·unresolved 수정/제거 | PM-005/008; [BA-060](../roles/BACKEND_AI_PLAYBOOK.md#ba-060) |
| [438:3199](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=438-3199) | P0 | 날짜별 수동 목록·바로 시작 CTA | 확인 단계 경유 합의·위치/시간/순서 상한 | PM-004/005; [BA-030](../roles/BACKEND_AI_PLAYBOOK.md#ba-030) |
| [438:3259](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=438-3259) | P0 | 구조화 확인 목록·수정 후 시작 | title/date/timezone/lock·READY/만료·생성 멱등 | PM-004/005; [BA-030](../roles/BACKEND_AI_PLAYBOOK.md#ba-030) |
| [384:5673](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=384-5673) | P0 | 추천 초안·시작/직접 수정 | 확인 전 trip0·초안 read·재계산/만료 | PM-004/014; [BA-030](../roles/BACKEND_AI_PLAYBOOK.md#ba-030) |
| [440:3244](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=440-3244) | P1 | P1 AI draft | P0 OFF·LLM 사실 생성 금지·명시적 승인 | PM-004; [BA-084](../roles/BACKEND_AI_PLAYBOOK.md#ba-084) |
| [410:1738](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=410-1738) | P0 | 일정 보기·Day·후보 수 | complete aggregate·empty day·refresh·출처 | PM-009/010; [BA-030](../roles/BACKEND_AI_PLAYBOOK.md#ba-030) |
| [411:1837](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=411-1837) | P0 | 편집·저장/취소 | 복합 commit 원자성·stale·dirty-exit | PM-007; [BA-031](../roles/BACKEND_AI_PLAYBOOK.md#ba-031), [BA-040](../roles/BACKEND_AI_PLAYBOOK.md#ba-040) |
| [527:4085](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=527-4085) | P0 | 날짜 이동 뒤 편집 화면, 시간 입력 아님 | 선택 Day/본문 Day·이동 후 시간 유지 | PM-003/007; [BA-040](../roles/BACKEND_AI_PLAYBOOK.md#ba-040), [BA-041](../roles/BACKEND_AI_PLAYBOOK.md#ba-041) |
| [412:1912](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=412-1912) | P0 | 후보 panel, SCHEDULED에도 제거 action | ACTIVE/SCHEDULED/DISMISSED·일정화/교체 | PM-009; [BA-034](../roles/BACKEND_AI_PLAYBOOK.md#ba-034), [BA-040](../roles/BACKEND_AI_PLAYBOOK.md#ba-040), [BA-042](../roles/BACKEND_AI_PLAYBOOK.md#ba-042) |
| [413:2020](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=413-2020) | P0 | 미저장 변경 폐기 확인 | 폐기0변경·계속 편집·focus 복귀 | PM-007; [BA-040](../roles/BACKEND_AI_PLAYBOOK.md#ba-040) |
| [413:2081](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=413-2081) | P0 | 필수 방문 잠금 해제 확인 | 해제+교체 원자성·다른 잠금 보존 | PM-007; [BA-041](../roles/BACKEND_AI_PLAYBOOK.md#ba-041) |
| [414:2347](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=414-2347) | P0 | 교체 비교 | 관계/비교 자격·확정 전 미변경·linkage | PM-007/009/010; [BA-042](../roles/BACKEND_AI_PLAYBOOK.md#ba-042) |
| [527:4537](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=527-4537) | P0 | 교체 비교 variant | 두 variant의 동일 command·source/잠금 | PM-007/009/010; [BA-042](../roles/BACKEND_AI_PLAYBOOK.md#ba-042) |
| [476:3409](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=476-3409) | P0 | 장소 검색·혼잡값 | search projection·IME·empty/race·출처 | PM-010/013; [BA-040](../roles/BACKEND_AI_PLAYBOOK.md#ba-040) |
| [479:3497](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=479-3497) | P0 | 기존 교체 대상 선택 | itemId·잠금·잘못된 대상 거절 | PM-007/009; [BA-042](../roles/BACKEND_AI_PLAYBOOK.md#ba-042) |
| [479:3816](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=479-3816) | P0 | 추가 결과 variant | head/tail 삽입 정책·하루20/총100·재조회 | PM-003/007; [BA-040](../roles/BACKEND_AI_PLAYBOOK.md#ba-040) |
| [527:4380](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=527-4380) | P0 | 추가 결과 다른 순서 variant | 다른 variant와 position 정책 일치 | PM-003/007; 共通 acceptance / FE |
| [521:3976](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=521-3976) | P0 | 기존 일정 날짜 이동 선택 | 유효 date·position·date lock | PM-003/007; [BA-040](../roles/BACKEND_AI_PLAYBOOK.md#ba-040) |
| [527:4695](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=527-4695) | P0 | 후보 신규 추가 날짜 선택 | addTripItem+candidateId, 기존 item 이동과 구분 | PM-003/009; [BA-040](../roles/BACKEND_AI_PLAYBOOK.md#ba-040) |
| [527:3876](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=527-3876) | P0 | DATE 해제하고 이동·시간 유지 안내 | 원자 해제+이동·TIME/예약 유지 | PM-007; [BA-041](../roles/BACKEND_AI_PLAYBOOK.md#ba-041) |
| [415:2268](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=415-2268) | P0 | ITEM 제목, 전체/Day 범위 chip | 실제 targetItemId 선택·P1 OFF | PM-015; [BA-050](../roles/BACKEND_AI_PLAYBOOK.md#ba-050) |
| [415:2413](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=415-2413) | P0 | 계산·경로 문구·취소하고 복귀 | 돌아가기와 run 취소 구분·polling 복구 | PM-014/015; [BA-050](../roles/BACKEND_AI_PLAYBOOK.md#ba-050) |
| [439:3104](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=439-3104) | P1 | DAY preview P1 | P0 ITEM READY 증거로 사용 금지 | PM-013/015; [BA-083](../roles/BACKEND_AI_PLAYBOOK.md#ba-083) |
| [417:2412](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=417-2412) | P0 | 적용 결과·undo toast | 지속 undo·24시간·서버 availability·이력 재진입 | PM-009/015; [BA-053](../roles/BACKEND_AI_PLAYBOOK.md#ba-053) |
| [417:2567](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=417-2567) | P0 ref | 오류 6종 개발 참조 | 오류별 미변경·제안 없는 KEEP 분리·최적성 문구 | PM-015/020; [BA-051](../roles/BACKEND_AI_PLAYBOOK.md#ba-051), [BA-052](../roles/BACKEND_AI_PLAYBOOK.md#ba-052) |
| [485:3517](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=485-3517) | P0 ref | stale 비교·재계산 | stale APPLY0·최신 결과·단계 수치 과장 금지 | PM-013/015; [BA-052](../roles/BACKEND_AI_PLAYBOOK.md#ba-052) |
| [418:2523](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=418-2523) | P0 | Live 지도/여행 context/필터/날씨 | 목록 우선·지원 필터만·위치0·coverage | PM-012/013; [BA-091](../roles/BACKEND_AI_PLAYBOOK.md#ba-091) |
| [419:2617](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=419-2617) | P0 | 장소 상세·시간대 그래프 | 시간 해상도·source별 단위·후보 저장 | PM-010/012/013; [BA-091](../roles/BACKEND_AI_PLAYBOOK.md#ba-091) |
| [420:2821](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=420-2821) | P0 | 대안 목록·여유순·거리 | relation과 crowd 비교 분리·기준점/근거 | PM-010/012/013; [BA-091](../roles/BACKEND_AI_PLAYBOOK.md#ba-091) |
| [420:2950](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=420-2950) | P0 | 대안 없음 | NONE/UNKNOWN/CHECKING·유효한 복구 action | PM-012/020; [BA-091](../roles/BACKEND_AI_PLAYBOOK.md#ba-091) |
| [421:2850](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=421-2850) | P0 | 지속 과거 재생 배너·과거 시각 | REPLAY namespace/manifest·현재값 혼합 금지 | PM-013; [BA-092](../roles/BACKEND_AI_PLAYBOOK.md#ba-092) |
| [501:3750](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=501-3750) | P1 | 다음 일정 대체 제안 P1 | OFF·동의·승인 전 일정 미변경 | PM-012; [BA-093](../roles/BACKEND_AI_PLAYBOOK.md#ba-093) |
| [422:2925](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=422-2925) | P0 | guest·여행·이력·관심사·데이터 안내 | 언어/삭제 보조 상태·이력 링크·세션 보존 안내 | PM-001/002/016/017; [BA-011](../roles/BACKEND_AI_PLAYBOOK.md#ba-011), [BA-030](../roles/BACKEND_AI_PLAYBOOK.md#ba-030), [BA-031](../roles/BACKEND_AI_PLAYBOOK.md#ba-031), [BA-053](../roles/BACKEND_AI_PLAYBOOK.md#ba-053), [BA-081](../roles/BACKEND_AI_PLAYBOOK.md#ba-081) |
| [423:2967](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=423-2967) | P0 | 데이터 상태5개, REPLAY 누락 | 6-state·관측/대상 시각·source와 권리 | PM-001/013; [BA-023](../roles/BACKEND_AI_PLAYBOOK.md#ba-023) |
| [442:3344](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=442-3344) | P1 | 알림 목록·모두 읽음 P1 | P0 OFF·read-all 기준시점·내부 deep link | PM-016; [BA-085](../roles/BACKEND_AI_PLAYBOOK.md#ba-085) |
| [442:3370](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=442-3370) | P1 | 주변 장소·담기 P1 | P0 OFF·미확인 영업/거리/무료 사실 생성 금지 | PM-012; [BA-093](../roles/BACKEND_AI_PLAYBOOK.md#ba-093) |

## 화면 수에 포함되지 않은 필수 상태

- P0 ITEM READY before/after·APPLY/KEEP, target item 선택, preview 만료, 적용 후24시간 undo/만료/후속 편집 불가.
- 시간/duration 편집, 독립 TIME/RESERVATION 입력·해제, 기간 축소 실패, 혼합 편집 저장 실패/충돌.
- profile 언어 변경, 여행 삭제, 세션 데이터 삭제 확인/접수/진행/부분 실패/완료/receipt 분실/만료.
- import server 전송 안내, ambiguous 장소 remap/제거, 연도/날짜 보정, draft 만료·stale·뒤로/refresh.
- 목록형 Live map OFF, 미지원 POI/지역, UNKNOWN/CHECKING/NONE, 단위별 범례/날짜 해상도.
- 공통 bootstrap 실패, offline/background refresh, empty/429/503, 긴 KO/EN, 360px/200% text, keyboard/focus/뒤로가기.
- 후보 저장 success/duplicate/error의 persistent 상태. toast 단독으로 성공/실패·다음 행동을 전달하지 않는다.

이 상태는 반드시 새 top-level frame일 필요는 없다. 재현 가능한 component variant/node와 API fixture로 연결하되 누락을 화면 수로 숨기지 않는다.

## 컴포넌트 검토

01 Components의 최상위49개 이름은 [catalog](COMPONENT_CATALOG.md)와 일치했다. 이는 각 component에 catalog의 모든 props/state가 실제 디자인됐거나 구현됐다는 뜻이 아니다.

| 조합 | Backend/AI 확인 | FE 인수 조건 |
| --- | --- | --- |
| Candidate/TripPicker/SaveCandidate/TripAddButton | 후보와 일정 분리·SCHEDULED/중복·멱등 응답 | 지속 결과·시트 닫기/복귀·원래 장소 의도 복구 |
| DecisionBar/OptimizationScope/MetricDelta | target/decision union·서버 비교 자격·undo 가능성 | ITEM만 P0·before/after·disabled 사유·만료 |
| StateLabel/Badge/Distance/Map | 6-state·범위/해상도·출처·거리 근거 | color-only 금지·list fallback·미확인 값 숨김 |
| TripItem/LockControl/MustVisit/Search/Pick/Chip | 복합 commit·독립 잠금·canonical taxonomy·검색 projection | 시간 편집 node·키보드 순서 변경·단일/복수 선택 |
| FeedPost/Toast/Tag/Icon | SavedPost/feedback/후보 상태·media license | 하트/북마크 의미 구분·표시 가능한 field만 소비 |

기타 navigation/action/form/icon은 catalog의 접근성·size·safe-area·상태 계약을 상속한다. 픽셀/토큰/export·전체 variant 검수는 FE-002의 실제 디자인 산출물로 완료한다.
