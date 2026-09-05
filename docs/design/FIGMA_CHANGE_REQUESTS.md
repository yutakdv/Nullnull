# Figma 정합성 수정 요청

- 감사일: 2026-09-05
- 상태: Open — 아래 P0 blocker가 닫히기 전 Figma 전체를 구현 승인 상태로 보지 않음
- 대상: `02 UI Design`의 현재 구현 frame 52개와 `01 Components`의 최상위 component 49개
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

각 FCR은 다음 증거가 모두 있을 때만 `Closed`로 바꾼다.

1. 수정된 Figma node URL과 변경 전/후 screenshot
2. [기능 인벤토리](../product/FUNCTIONAL_INVENTORY.md)의 기능 ID·operationId 연결
3. KO/EN 360px, keyboard, disabled/loading/error state 확인
4. Backend/AI의 계약 검토와 총괄 PM의 범위·문구 승인
5. 실제 구현 후 Storybook/Playwright test ID

FCR-004를 새 top-level frame으로 만들면 현재 52개 수는 53개가 된다. 기존 component
variant로 만들면 52개를 유지할 수 있다. 어느 방식을 택하든 숫자를 맞추기 위해 상태를
숨기지 말고 [Figma 핸드오프](./FIGMA_HANDOFF.md)와 검증 스크립트의 inventory를 같은
change set에서 갱신한다.
