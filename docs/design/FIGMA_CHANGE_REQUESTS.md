---
aliases:
  - "Figma 정합성 수정 요청"
doc_type: reference
status: conditional
area: design
tags:
  - nullnull/reference
  - nullnull/design
---

# Figma 정합성 수정 요청

- 최초 감사일: 2026-09-05; 후속 canvas 대조: 2026-09-06
- 상태: Open — 아래 P0 blocker가 닫히기 전 Figma 전체를 구현 승인 상태로 보지 않음
- 대상: `02 UI Design`의 확인한 화면·참조 상태 frame 52개와 `01 Components`의 최상위 component 49개
- 디자인 파일: [Nullnull UI Design](https://www.figma.com/design/C3tTNClo9JH8tb4qpQgP61/Nullnull-UI-Design?node-id=386-257&p=f)

이 문서는 공개 Figma의 실제 화면·layer를 제품 요구사항, OpenAPI, 기능 인벤토리와
대조한 change request다. 현재 frame 수 52와 component 수 49는 맞지만, 개수 일치는
구현 준비 완료를 뜻하지 않는다. Frontend 담당은 Figma를 수정하고 node/state 증거를
이 문서에 연결하며, Backend/AI 담당은 계약 의미를, 총괄 PM은 범위·문구·제출 주장을
확인한다.

## 수정 목록

| ID | Pri | 현재 Figma 증거 | 목표 상태 | 소유/검토 | 상태 |
| --- | --- | --- | --- | --- | --- |
| FCR-001 | P0 blocker | A-2 `388:277`의 English가 `영어 · 준비 중`으로 표시됨 | `한국어`와 `English`는 선택 가능, `日本語`·`中文`만 disabled `준비 중`; KO/EN 전환·복구 variant 추가 | FE / BE·AI·PM | Open |
| FCR-002 | P0 blocker | feed `391:310`, `396:2926`과 post `398:611`에 `팔로잉`·`최신`, 검색, unread bell, 활성 `팔로우`가 보임 | P0에서는 제거가 기본. 꼭 남기면 disabled `준비 중`과 이유를 표시하고 route/API 호출 0건 | FE / PM | Open |
| FCR-003 | P0 blocker | feed에 `혼잡도 낮은 순`, `지금 가기 좋아요`, `서울` chip이 활성 control처럼 보임 | `listFeed`에 filter 계약이 생기기 전 숨김. P1에서도 source·시점 비교 적격성 없는 혼합 순위 금지 | FE / BE·AI | Open |
| FCR-004 | P0 blocker | F 흐름에 setup `415:2268`, loading `415:2413`, P1 DAY preview `439:3104`, applied `417:2412`만 있고 P0 ITEM READY preview가 없음 | P0 ITEM 전용 READY frame/variant 추가: before/after, provenance, lock validation, eligible metric, `적용`/`현재 일정 유지` | FE / BE·AI·PM | Open |
| FCR-005 | P0 blocker | loading/preview에 `경로 계산`, `지도 provider 미정` 표현이 있으나 P0 route provider는 미결정 | ITEM copy를 혼잡·고정 조건 확인으로 변경. provider가 없으면 목록/timeline을 동등하게 제공하고 placeholder·추정 이동값 제거 | FE / BE·AI | Open |
| FCR-006 | P0 blocker | S14 `422:2925`가 `로그인하면 일정을 저장할 수 있어요`와 활성 login affordance를 노출 | `이 기기의 익명 세션에 저장돼요`처럼 실제 보존 방식을 설명하고 login은 disabled `준비 중`; 요청 0건 | FE / BE·AI·PM | Open |
| FCR-007 | P0 blocker | S15 `423:2967`가 데이터 상태를 5개로 설명하고 `REPLAY`를 누락 | `LIVE`, `FORECAST`, `REPLAY`, `QUALITATIVE`, `STALE`, `UNAVAILABLE` 6개와 관측/대상 시각 차이를 설명 | FE / BE·AI | Open |
| FCR-008 | P0 major | S11 `418:2523`에 장소명 검색이 있으나 화면-API 연결이 명시되지 않음 | `searchPlaces` → canonical 선택 → `getLivePlace`; coverage가 없으면 `UNAVAILABLE`, loading/empty/error variant 제공 | FE / BE·AI | Open |
| FCR-009 | P0 major | post/detail 거리값은 기준점·산식이 불명확해 보일 수 있음 | trip anchor/선택 장소 등 거리 기준과 source를 함께 표시. 기준이 없으면 거리값을 숨기고 unavailable reason 제공 | FE / BE·AI | Open |
| FCR-010 | P0 contract sync | 기존 최적화 setup; API 0.2.0에 scope 판별 union 반영, 실제 디자인 반영 증거 미확인 | ITEM targetItemId/DAY targetDate/TRIP target 없음의 입력·disabled state와 생성 client 일치 | FE / BE·AI | Open — 계약 반영, UI 확인 필요 |
| FCR-011 | P0 contract sync | 서울 source의 출처 계약은 source catalog에 반영, Live 디자인 반영 증거 미확인 | 서울특별시 정확한 attribution·공공누리 제1유형·officialUrl/licenseUrl·scope 표기를 B10 Live에 적용 | FE / BE·AI | Open — 계약 반영, UI 확인 필요 |
| FCR-015 | P0 contract sync | API 0.2.0 decision union 반영, applied/undo 디자인 반영 증거 미확인 | APPLY revision/revertUntil=decidedAt+24h, KEEP 필드 부재, REVERT 참조 decision·410 REVERT_WINDOW_EXPIRED 상태 제공 | FE / BE·AI | Open — 계약 반영, UI 확인 필요 |
| FCR-016 | P0 blocker | S14 언어/여행 삭제/전체 데이터 삭제 보조 흐름과 시간/예약 입력 node 미확인 | 진입·확인·처리중·부분 실패·완료·복구 variant와 API 연결; PM-002 | FE / BE·AI·PM | Open |
| FCR-017 | P0 blocker | 527:4085는 날짜 이동 결과, 527:4695는 후보 신규 일정화 날짜 선택 | node 의미 정정·별도 시간 편집·선택 Day 일치·삽입 위치 정책; PM-003/008 | FE / BE·AI·PM | Open |
| FCR-018 | P0 blocker | 438:3158 untimed 필수 장소→384:5673 초안, 수동 시작/확인 단계 불일치 | 확인 전 초안 계약·세 분기·수정/만료·확정 경계; PM-004 | FE / BE·AI·PM | Open |
| FCR-019 | P0 blocker | 401:1221 원문·438:3259 confirm에서 연도/제목/제거 수정 부족 | 날짜 기준·기본 제목·item 제거/remap·전송 안내·IMPORT 잠금; PM-005 | FE / BE·AI·PM | Open |
| FCR-020 | P0 blocker | 438:3108 동행인/스타일 chip에 canonical code/weight 없음 | 지원 dictionary·선택 규칙·KOEN label·중복/빈 선택; PM-006 | FE / BE·AI·PM | Open |
| FCR-021 | P0 blocker | 411:1837/413:2020 buffer 저장/취소, 해제하고 이동·교체 | 복합 commit 또는 승인된 저장 UX·원자 해제+변경·실패0변경; PM-007 | FE / BE·AI·PM | Open |
| FCR-022 | P0 blocker | 412:1912 SCHEDULED 제거 action, 저장 결과는 toast 중심 | 후보 전이·교체 linkage·독립 후보 보존·지속 success/duplicate/error; PM-009 | FE / BE·AI·PM | Open |
| FCR-023 | P0 blocker | 검색/feed/trip의 KTO 콘텐츠·이미지 출처 전달 공백 | list/detail content provenance·media license·권리 철회/placeholder; PM-010 | FE / BE·AI·PM | Open |
| FCR-024 | P0 major | feed/post 작성자·하트 수·반응 상태와 read schema 불일치 | P0 field-by-field 범위·피드백 토글/재조회/숨김 복구; PM-011 | FE / BE·AI·PM | Open |
| FCR-025 | P0 blocker | 418:2523 현재 여행지·우회 시간·정렬·날씨·map에 미지원 기능 | P0 제거 또는 입력/근거/provider 계약, map OFF 목록; PM-012 | FE / BE·AI·PM | Open |
| FCR-026 | P0 blocker | Live/stale/replay 공통 단계·시간별 그래프, KTO는 상대 날짜 예측 | source별 단위/범례/시간 해상도·6-state·비교불가 표시; PM-013 | FE / BE·AI·PM | Open |
| FCR-027 | P0 blocker | ITEM 대상 선택·취소 copy·refresh/만료/이력/undo 상태 불완전 | read 복구·back≠cancel·실패 복귀≠KEEP·서버 undo 가능성; PM-015 | FE / BE·AI·PM | Open |
| FCR-028 | P0 major | 417:2567 NO_IMPROVEMENT가 전역 최적성을 주장 | 확인한 후보 범위 문구·UNKNOWN/CHECKING/NONE·유효 CTA; PM-020 | FE / BE·AI·PM | Open |

FCR 번호는 기존 참조를 보존하며 012~014는 예약된 번호로 유지한다. FCR-010/011/015는 계약 동기화에서 시작했고, 016~028은 09-06 직접 canvas 대조와 API 검토로 추가했다. 디자인 수정 완료를 뜻하지 않는다.

## 권장 문구와 시각 규칙

- A-2 helper: `한국어와 English를 지원해요. 日本語와 中文은 준비 중이에요.`
- S14 guest: `로그인 없이 시작했어요` / `여행은 이 기기의 익명 세션에 저장돼요.`
- S14 login: `로그인 · 준비 중`을 disabled control로 제공하거나 제출 profile에서 숨긴다.
- ITEM loading: `혼잡 정보와 고정한 조건을 확인하고 있어요.`
- ITEM decision: 긍정 단일 `확인` 대신 `이 변경 적용`과 `현재 일정 유지`를 같은
  decision bar에 둔다.
- 수치·색만으로 상태를 전달하지 않고 label, 기준 시각, 출처와 unavailable reason을
  함께 둔다.
- 공모전 제출 profile에서는 구현되지 않은 P1 control을 보여 주는 것보다 제거하는 것을
  기본으로 한다.

## 종료 조건

상태는 `Open → Ready for implementation → Closed`다. 1~4의 디자인·계약 검토가 끝나면 영향 UI를 착수할 수 있다. 실제 구현 후 5까지 통과해야 Closed다. 구현 test가 있어야 구현을 시작할 수 있는 순환 조건을 만들지 않는다.

1. 수정된 Figma node URL과 변경 전/후 screenshot
2. [기능 인벤토리](../product/FUNCTIONAL_INVENTORY.md)의 기능 ID·operationId 연결
3. KO/EN 360px, keyboard, disabled/loading/error state 확인
4. Backend/AI의 계약 검토와 총괄 PM의 범위·문구 승인
5. 실제 구현 후 Storybook/Playwright test ID

FCR-004를 새 top-level frame으로 만들면 현재 52개 수는 53개가 된다. 기존 component
variant로 만들면 52개를 유지할 수 있다. 어느 방식을 택하든 숫자를 맞추기 위해 상태를
숨기지 말고 [Figma 핸드오프](./FIGMA_HANDOFF.md)와 검증 스크립트의 inventory를 같은
change set에서 갱신한다.

## #11 계약 packet 추가 검토

FCR-010/011/015의 [추가 계약 제안](../contracts/review-2026-09-06/README.md)은 KTO 출처 예시, 카드용 짧은 credit, 서버 undo 가능 상태, 외부 링크 host, 최초 APPLY/KEEP 응답 union을 포함한다. OpenAPI 0.2.1-rc.1·example·TS strict 검사는 준비했으며 FE fixture/화면 검토는 아직 없다. 기존 FCR 상태를 Ready/Closed로 올리지 않는다. 예전 이슈의 node 번호는 [현재 화면 확인표](SCREEN_REVIEW_2026-09-06.md)로 다시 연결한다.
