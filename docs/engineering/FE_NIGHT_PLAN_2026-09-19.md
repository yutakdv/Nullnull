---
aliases:
  - "FE 밤 작업 계획 2026-09-19"
doc_type: reference
status: draft
area: engineering
tags:
  - nullnull/reference
  - nullnull/engineering
---

# FE 밤 작업 계획 (2026-09-19)

FE 세션 넷이 각자 조사를 끝냈는데 **그 결과가 세션 메시지에만 있다.** 세션이 끊기면
사라지고, 같은 측정을 다음 사람이 다시 한다. 이 문서는 그 결과를 한자리에 모아
**완주 초록이 나온 순간 셋이 동시에 착수할 수 있게** 한다.

읽는 사람: `/Users/youngjun/Documents/Nullnull`에서 일하는 FE 세션.

세션 표기는 조율자가 쓰는 이름을 그대로 쓴다(`48`, `00`, `dd`). 조율자는 `9e`다.

## 지금 상태 — 해제됨 (PR #267 머지)

**동결은 끝났다.** PR [#267](https://github.com/yutakdv/Nullnull/pull/267)이
`1b35283`으로 머지됐고 두 required check가 초록이었다. 아래 §1·§5의 동결 전제
(*"게이트 완주 초록 전에는 아무도 시작하지 않는다"*)는 충족됐다.

세션 표기가 바뀌었다 — `48`·`00`·`dd`는 종료됐고 현재는 `51`·`d2`다. 조율자는 `9e`다.

### 마지막 빨간불의 원인은 FE 코드가 아니었다

`docs-contract`가 *"stale oasdiff exception"* 넷으로 빨갰다. `#261`의 계약이
`main`에 들어가면서 base가 따라 움직여, base와 revision이 **둘 다** 그 enum을 담게
되어 oasdiff가 더는 breaking으로 보고하지 않았다 — 그러면 ignore 줄은 아무 finding에도
대응하지 않는 면제이고, 검사는 **예외가 자기 수정보다 오래 사는 것**을 막는다.
회귀가 아니라 그 장치의 목적이다. `877951c`가 만료 처리했다.

`#225`에서 같은 일이 이미 났고 그 기록이 경고까지 적어뒀는데 재발했다. 이유 둘을
`docs/api/BREAKING_CHANGE_EXCEPTIONS.md`에 적었다 — 경고가 *만료된 예외* 절에 있어
등록하는 사람이 읽는 곳에 없었고, 그 처방(*"같은 PR에서 비우라"*)이 **실행 불가**였다
(그 시점엔 finding이 실재해서 비우면 oasdiff가 rc=1로 그 PR을 막는다. 재현해 쟀다).

### 로컬 34건 실패의 원인도 밝혀졌다 — 이 문서의 §4가 낡았다

`apps/web/e2e/overflow.ts`의 주인 없는 미커밋 편집이었다. 반환 프로퍼티를
`documentWidth` → `docWidth`로 바꾸고 **호출부 4곳**(`responsive.spec.ts`·
`sheet-responsive.spec.ts`)을 고치지 않은 상태였다 — 즉 어느 쪽으로도 작동하지 않았다.
조율자가 되돌렸고(`cp -p`로 트리 밖에 사본을 뜬 뒤) 그 두 spec이 **34 실패 → 90
passed**가 됐다. **§4의 *"아무도 못 고친다"* 와 §5의 *"#266은 처음부터 빨갛다"* 는
더 이상 참이 아니다.**

이 편집은 `#266`이 잡으려는 결함의 **실사례**였다: `tsc`는 `e2e/`를 보지 않으므로
exit 0이었고, 브라우저에서만 죽었다.

## 0. BE 요청 — 먼저 처리한다

오너가 전달한 BE 요청 둘이다. §1 의 `#233` 계열보다 **먼저** 처리한다.

### 0-1. [#16](https://github.com/yutakdv/Nullnull/issues/16) 의 새 503 — **이미 끝났다**

BE 가 *"화면에서 이 503 을 어떤 CTA/상태로 보일지 확인 부탁"* 이라고 요청했는데,
그 작업이 이미 커밋돼 있다(`5c6edd0`).

```text
OptimizationRunScreen.tsx   sourceDown 분기 + run.sourceUnavailable + run.unchanged
OptimizeSetupScreen.tsx     optimize.sourceUnavailable
session.ts                  useOptimization 의 retry 에서 제외 (총 3회 → 1회)
```

`terminal` 에 넣지 **않았다** — 403 `FORBIDDEN` 은 영구라 terminal 이지만 503 은
일시적이라 재시도 수단이 남아야 한다. 그 구분의 근거는 `OptimizeSetupScreen` 의
기존 FORBIDDEN 주석이 적고 있다: *"the server chose 403 over 503 precisely so a
client would stop asking."*

**할 일: BE 에 답변만 하면 된다.** 무엇을 어떻게 보이는지와 변이 측정 결과
(session.ts 의 그 줄을 지우면 closed-catalog 4건이 타임아웃으로 빨개진다)를
적는다. 코드 작업은 없다.

### 0-2. [#261](https://github.com/yutakdv/Nullnull/issues/261) 의 실패 코드 둘 — **문구 하나**

BE 가 승인을 요청한 제안이다. dead-letter 된 job 의 run 이 `RUNNING` 으로
남는 것을 `FAILED` 로 끝내려면 코드가 필요한데, 기존 여섯은 전부 *여행이나
근거* 에 대한 원인이라 서비스 장애를 말할 수 없다.

| code | retryable | 원인 | FE |
| --- | --- | --- | --- |
| `RECOMMENDATION_UNAVAILABLE` | true | apps/ai 무응답 | **새 문구 필요** |
| `INTERNAL_ERROR` | false | 계약 밖 답·예외·lease 소진 | 기존 `run.failure.unknown` 으로 접힘 |

**계약에 아직 없다** — `grep RECOMMENDATION_UNAVAILABLE packages/api-client/src/generated/openapi.ts` 가 0건이다.
BE 가 FE 승인을 기다리는 단계이고, 승인하면 BE 가 계약에 넣는다.

**FE 가 할 일은 문구 하나다.** 지금 구조가 그것을 가능하게 한다 —
`OptimizationRunScreen` 의 `failureMessage` 가 **모르는 코드를 generic 으로
접는다**. 그 주석이 이유를 적고 있다: 코드로 키를 만들면 배포 뒤 추가된 코드가
없는 키를 가리키고 `t()` 가 `"[undefined]"` 를 화면에 그린다(측정된 것이다).
그래서 **BE 가 먼저 나가도 화면이 깨지지 않는다.**

`INTERNAL_ERROR` 는 그 접힘으로 이미 처리된다. `RECOMMENDATION_UNAVAILABLE`
만 전용 문구를 더하면 된다.

**BE 의 대안(한 코드로 넷을 덮기)에 대한 판단도 답해야 한다.** BE 가 스스로
적었듯 그러면 ②③④ 에서 소용없는 *"다시 계산"* 을 권한다 — `retryable` 이
true 이므로 FE 의 CTA 가 그것을 따른다. 둘로 나누는 쪽이 낫다는 것이 FE
입장이고, 그 이유를 이슈에 적는다.

**누가**: 코드 작업이 작아서 `#233` 을 맡지 않은 세션이 맡거나, 조율자가 직접
한다. 계약이 오기 전까지는 **문구를 미리 넣어도 도달 불가**이므로(그 코드가
서버에서 오지 않는다) 순서는 BE 승인 뒤다.

---

## 1. 세션별 확정 범위

### `dd` — [#233](https://github.com/yutakdv/Nullnull/issues/233)의 `BA-070-T5` (핵심 흐름 keyboard/focus)

| 할 것 | 내용 |
| --- | --- |
| ① tab bar 실제 순회 | `focus()`를 `keyboard.press('Tab')`로 교체. 네 목적지 순서 + Enter로 실제 이동 |
| ② `/start`를 키보드로 끝까지 | 날짜 → 관심사 → … → 생성까지 Tab/Enter만으로. 단계 전이마다 focus 위치 단언 |
| ③ `TripWizardScreen` focus 이동 | 단계 전이 시 `h1`으로. 오너 승인된 `(a)` 경로 |

**흐름을 `/start`로 고른 이유**: 제출 데모의 핵심 경로이고, 단계가 여럿이라 화면 전이
후 focus를 잴 수 있고, **실제로 고칠 것이 있다**(아래 측정 기록 참조). 이미 접근
가능한 흐름을 test로 덮는 것보다 값있다.

`48`의 `BA-040-T4`와 화면이 다르다 — `/start` wizard와 `/trip/:id`다.

### `48` — [#233](https://github.com/yutakdv/Nullnull/issues/233)의 `BA-040-T4` (일정 편집 keyboard/focus)

| gap | 내용 | acceptance ID |
| --- | --- | --- |
| 1 | Tab 도달 | `BA-040-T4`. `focus()` 금지 |
| 2 | 네이티브 dialog 사용 고정 | ID 없음 — 절이 달라서 |
| 3 | 이동 후 focus 유지 | `BA-040-T4` + 코드 수정 |
| 4 | Enter 확인 | `BA-040-T4` |

**gap 3의 진단이 두 번 바뀌었다. 최신은 이것이다**(측정):

```text
A_sheet_open    focus = 시트의 Cancel
B_confirm_open  focus = confirm 의 Cancel   ← ConfirmDialog 가 restoreTo 에 시트 버튼을 캡처
C_after         focus = BODY
TRIGGER_STILL_IN_DOM 1 (visible)            ← trigger 는 살아 있다
```

즉 고칠 곳이 `ItemMoveControls`가 아니라 **`ConfirmDialog`의 캡처 시점**일 수 있다.
`shared/ui/components/`라 범위가 넓어져 `48`이 판단을 보류했다 — 아래 §4 참조.

### `00` — [#163](https://github.com/yutakdv/Nullnull/issues/163) + [#266](https://github.com/yutakdv/Nullnull/issues/266)

**#163 (feed 반응 기능 제외 확정)**: `FCR-024`가 이미 결정을 기록했다. 남은 것은 FE
회귀 방지 test 하나다.

- 단언: `POST /feed/feedback` 요청 자체가 **0건**(FE가 계측을 아예 안 보냄 — 조율자 확인)
- `FeedScreen`에 `FCR-024` 포인터 한 줄, `PRODUCT_SPEC`에 한 줄

**#266 (e2e가 typecheck 프로그램 밖)**: `tsconfig.e2e.json`(`types: ["node"]`만)을
만들고 `verify:ci`에 단계를 추가한다.

> **경고**: `apps/web/e2e/overflow.ts`가 지금 그 결함을 **실제로 담고 있어** 새 검사가
> 빨개진다. 그 파일은 오너 판정 대기 중이라 아무도 못 고친다(§4).

## 2. 파일 소유 지도

**충돌 방지가 이 문서의 가장 실용적인 부분이다.** 지금까지 합의된 것:

| 경로 | 소유 |
| --- | --- |
| `apps/web/e2e/keyboard-flow.spec.ts` `:26-62` (공용 helper) | **아무도 안 건드림** — `48`·`dd` 약속 |
| `apps/web/e2e/keyboard-flow.spec.ts` `:64-157` (`BA-040-T4` describe) | `48` |
| `apps/web/e2e/keyboard-flow.spec.ts` `:159-218` (`BA-070-T5` describe) | `dd` |
| `apps/web/src/app/trip-create/**` | `dd` |
| `apps/web/src/app/trip/ItemMoveControls`, `MoveDaySheet` | `48` |
| `apps/web/src/shared/ui/components/ConfirmDialog` | ⚠️ **미승인** — `48` 판단 대기 |
| `apps/web/src/app/feed/**`, `apps/web/tsconfig*.json` | `00` |
| `apps/web/e2e/overflow.ts` | **아무도** — 오너 판정 대기 |

### `keyboard-flow.spec.ts`를 쪼개지 않는 이유

두 describe가 완전히 분리돼 있고 겹치는 줄이 0이라 git merge 기준으로 충돌 가능성이
낮다. 쪼개면 `openWithSession`이 복제되는데, 그것은 `overflow.ts`가 두 spec에
공유되는 이유와 반대로 간다.

**조건 둘이 붙어 있다**: 공용 helper(`:26-62`)를 아무도 수정하지 않고, `seeded-trip.ts`
또는 `FIRST_ITEM`을 바꾸면 조율자가 중계한다.

## 3. 측정 기록

**다시 재지 않기 위해 남긴다.** 각 값에 어디서 어떻게 쟀는지를 붙였다.

### 데이터와 무관한 것 — 어디서든 유효

```text
probe  focus() 성공 = YES  /  Tab 도달 = false
```

브라우저에서 tab bar 버튼들에 `tabindex="-1"`을 심어 **키보드로 도달 불가**하게 만든
뒤 잰 값이다. `tab.focus()`는 그래도 성공한다 — 즉 **접근성이 완전히 깨진 상태에서
기존 test가 초록이다.**

이것이 `focus()`를 금지하는 근거이고, `BA-040-T4`·`BA-070-T5` 양쪽에 적용된다.

```text
probe  POINTER_COARSE_MATCHES = true
```

Playwright의 `isMobile` 설정이라 `pointer: coarse` 미디어 쿼리가 매칭된다.

### dev server(MSW) 전제 — 실제 백엔드에서는 달라질 수 있음

```text
probe  /start 단계 전이
         STEP1  h1=Add your trip dates              | focus=BODY
         STEP2  h1=What kind of trip do you enjoy?  | focus=BODY
         STEP3  h1=How much have you planned…       | focus=BODY

probe  2단계의 첫 focusable
         BUTTON  aria-label="Previous step"  textContent=""

probe  이동 후  FOCUS_AFTER_MOVE = BODY,  ITEM_DAY3 = true
```

**`/start`는 단계가 바뀔 때마다 focus를 잃는다.** 코드에도 `focus()`·`autoFocus`가
0건이다(grep). 결과가 둘이다:

- 스크린리더 사용자는 **화면이 바뀐 것을 듣지 못한다** — DOM이 통째로 갈렸는데 focus는
  그대로고 라이브 리전도 없다
- 키보드 사용자는 **매 단계 처음부터 Tab을 눌러야 한다**

**focus를 `h1`으로 보내는 것이 확정이다.** 첫 컨트롤 방식은 측정으로 배제됐다 — 그
자리가 **뒤로가기 버튼**이고 텍스트가 빈 문자열이라, 사용자가 방금 앞으로 왔는데
스크린리더가 *"이전 단계"*를 읽는다.

> 이 셋은 dev server에서 잰 값이다. Docker 게이트는 실제 백엔드를 쓰므로 데이터가 달라
> 단계 구성이 달라질 수 있다. 첫 두 probe(데이터 무관)는 그대로 유효하다.

### 구현 기법 — 선례가 있다

`apps/web/src/app/trip/TripScreen.tsx`와 `apps/web/src/app/trip/RemoveItemControl.tsx`가
`queueMicrotask`로 **DOM이 다시 그려진 뒤에** focus를 준다. 그 타이밍 기법을 따른다.

**다만 패턴의 방향이 다르다.** 그 둘은 *"닫고 → 트리거로 복귀"*이고(ref가 이전에 있던
요소를 가리킨다), `/start`는 *"새 단계로 이동 → 새로 그려진 제목으로"*다. ref가
가리키는 대상이 매 단계 갈아끼워지므로 수명이 다르다 — 기법만 따르고 ref는 이 화면에
맞춘다.

## 4. 막힌 것과 누가 풀어야 하는지

| 항목 | 막힌 이유 | 푸는 사람 |
| --- | --- | --- |
| [#183](https://github.com/yutakdv/Nullnull/issues/183) 표지 서빙 경로 | 배포 도메인 미확정 | BE (조율자가 이슈 올림) |
| [#183](https://github.com/yutakdv/Nullnull/issues/183) `cover.url`이 https만 | importer가 상대 경로를 거절 | BE |
| ~~`48` gap 3의 `ConfirmDialog` 범위~~ | **해제** — 조율자가 `f4a978c`로 절반 고쳤고 나머지는 [#272](https://github.com/yutakdv/Nullnull/issues/272) | `d2` |
| ~~`apps/web/e2e/overflow.ts`~~ | **해제** — 오너 승인으로 되돌렸다(위 *지금 상태* 참조). 사본은 `/tmp/overflow-dd-uncommitted.ts`(`cp -p`, 세션 범위) | — |
| `REVERTED` summary | 계약에 reverted용 문장이 없음 | BE |

## 5. 순서와 의존

```text
0. 게이트 완주 초록          ← 조율자. 이것 전에는 아무도 시작하지 않는다
1. 48: BA-040-T4  ┐
   dd: BA-070-T5  ┘ 동시 가능 (파일 영역이 갈림)
2. 00: #163                  1과 동시 가능 (feed/** 는 겹치지 않음)
3. 00: #266 typecheck        ← 1이 끝난 뒤
```

**3이 1 뒤에 오는 이유**: `48`과 `dd`가 spec을 고치는 중에 e2e typecheck가 들어오면
**미완성 상태를 검사한다.** 그리고 `#266`은 `overflow.ts`의 기존 결함 때문에 처음부터
빨간데, 그 파일은 아무도 못 고친다 — 그 빨강과 작업 중인 spec의 빨강이 섞이면 어느
것이 새 결함인지 가를 수 없다.

> **위 두 문장 중 뒤쪽은 더 이상 참이 아니다.** `overflow.ts`는 되돌려져서 `#266`의
> 새 검사가 그 결함으로는 빨개지지 않는다. 그리고 순서 자체도 실제로는 반대가 됐다 —
> `1`(`BA-040-T4`·`BA-070-T5`)이 먼저 끝나 `1b35283`에 들어갔고, `3`(`#266`)이 지금
> 돌고 있다. 남은 `#272`는 `1`의 잔여분이라 `3`과 동시에 간다(파일이 갈린다:
> `tsconfig*`·`package.json`·`AGENTS.md` vs `ConfirmDialog.tsx`·`keyboard-flow.spec.ts`).

## 6. 조율자 판단이 틀렸던 것

**다음 사람이 *"조율자 말도 확인해라"*를 알아야 해서 남긴다.** 오늘 실제로 있었던 것만
적는다.

- **`responsive` 88건**: ANSI 이스케이프가 안 지워진 채로 세어 스택 트레이스 언급 수를
  실패 건수로 보고했다. 실제는 1건이었다. `sed 's/\x1b\[...//'`가 이 로그에서 안 먹는
  것이 원인이고, `perl -pe 's/\e\[[0-9;]*[A-Za-z]//g'`가 맞다.
- **`splash`·`intro` 실패**: 실패 목록에 없었다. 결론(*"별개 원인이 있다"*)은 맞았지만
  근거가 틀렸고, 근거가 틀린 결론은 다음 판단을 망친다.
- **미푸시 커밋 "4개"**: `--no-merges`로 세어 merge 커밋 둘을 빠뜨렸다. 그대로 밀었으면
  남의 미검증 커밋이 함께 공개됐다.
- **`e2e` 컴파일**: *"저장만 해도 source set 전체가 컴파일 대상"*이라는 `apps/api`의
  Gradle 규칙을 `apps/web`에 옮겼다. TypeScript는 그렇게 동작하지 않는다 —
  `tsconfig.json`의 `include`에 `e2e`가 없어 `tsc`가 그 파일을 아예 안 본다.
  이것이 [#266](https://github.com/yutakdv/Nullnull/issues/266)의 본체가 됐다.
- **`NULLNULL_ENV: staging`**: `ai` 서비스의 env인데 `api` 것으로 읽고, `secret`이
  필요하다는 틀린 제약을 요청문에 넣을 뻔했다. 활성 Spring 프로파일이 `integration`
  하나라 `developmentProfile`이 참이고 secret은 필요 없다.
- **`gap 3` 지시**: 고칠 곳을 `ItemMoveControls`로 지목했는데 측정해 보니
  `ConfirmDialog`의 캡처 시점일 수 있었다.

**같은 부류가 `dd` 쪽에도 있었다**: 변이가 `red=0`이라 *"단언이 약하다"*로 읽힐 뻔했는데,
원인은 같은 문자열이 `session.ts`에 6곳 있고 치환이 **첫 번째(다른 함수)**를 고친
것이었다. `AGENTS.md`의 *"변이 대상은 매번 grep으로 다시 찾는다"*가 **같은 파일 안
중복**에도 적용된다.

## 7. 이 문서를 읽고 시작할 때

1. 게이트가 **완주 초록**인지 먼저 확인한다. 실패 중이면 조율자에게 묻는다.
2. §2에서 자기 경로를 확인한다. 표에 없는 파일을 만져야 하면 **먼저 알린다.**
3. §3의 측정값은 **다시 재지 않는다.** 단 dev server 전제인 것은 실제 백엔드에서
   달라질 수 있으므로, 그 전제가 자기 작업에 유효한지는 본다.
4. `focus()`로 접근성을 재지 않는다. §3의 첫 probe가 그 이유다.
