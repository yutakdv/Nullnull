---
aliases:
  - "야간 자동 진행 계획 2026-09-17"
doc_type: reference
status: draft
area: engineering
tags:
  - nullnull/reference
  - nullnull/engineering
---

# 야간 자동 진행 계획 2026-09-17

마감 `2026-09-21 16:00`까지 남은 P0를 밤사이 자동으로 진행하기 위한 계획이다.
**측정하고 쓴 것만 적는다** — 아래 숫자는 전부 이 기계에서 실제로 잰 값이다.

## 시작 상태 (측정값)

| 항목 | 값 |
| --- | --- |
| `apps/web` typecheck | **3초** |
| `apps/web` vitest | **14초 / 969건 통과 / 61 파일** |
| `npm run verify:ci` | **19초 exit 0** |
| 미푸시 커밋 | `4cc6439`, `9c894cc` |
| 작업 트리 | clean (`--untracked-files=all` 0건) |
| 동시 세션 | 없음 |

**내부 루프가 19초다.** 밤사이 수십 회 반복이 가능하므로 자동 진행의 제약은
시간이 아니라 **판단이 필요한 지점**이다. 그 지점을 아래에서 전부 미리 못박는다.

## 먼저: 자동 진행을 그대로 시작하면 실패한다

`verify:ci`의 마지막 단계인 bundle budget이 **한계에 붙어 있다**:

```text
budget: ok    JS   154937 /  157000 gzip bytes (99%)
budget: ok    CSS    8896 /    8900 gzip bytes (100%)
```

**CSS 여유가 4 bytes다.** FE-503/504는 preview UI 한 벌(before/after,
MetricDelta, decision bar)이라 CSS가 반드시 늘고, 그러면 **19초짜리 루프의
마지막 단계에서** 실패한다. 즉 자동 진행이 밤새 작업을 다 해놓고 게이트에서
죽는다 — 가장 비싼 실패 모양이다.

`check-bundle-budget.mjs`는 이것을 **의도**라고 적어 두었다:

> Headroom is deliberately small. The next sheet fails this check, which is
> what should happen if a fourth one lands before the shared module does.

그리고 같은 파일이 **올바른 해법까지 지목**한다 — 선언을 깎는 것이 아니라
**공유 sheet 모듈을 추출**하는 것. 실측으로 그 판단이 맞았다:

| | gzip bytes |
| --- | --- |
| MoveDaySheet + ReplaceSheet + TripPicker (따로) | 1,003 + 1,225 + 1,102 = **3,330** |
| 셋을 합쳐 gzip | **1,982** |
| 이 계산이 예측한 회수량 | ~1,348 |
| **실제 회수량 (STEP 0 완료 후 실측)** | **86** (CSS 8,896 → 8,810) |

**예측이 15배 틀렸고, 그 틀린 이유가 이 계획에서 가장 중요한 교훈이다.**
`cat A B C | gzip`은 *"세 파일을 한 stream으로 압축하면"* 을 재는데, Vite는
이미 모든 module을 **한 CSS 파일로 합쳐서** 내보낸다. 즉 중복 바이트는
**측정하기 전부터 이미 gzip이 접고 있었고**, 내가 잰 1,348은 *"따로 압축 →
같이 압축"* 의 이득이지 *"중복 제거"* 의 이득이 아니었다. 같은 파일의
주석이 이미 *"gzip already folds the geometry this sheet shares"* 라고
적어 두었는데 그것을 읽고도 같은 함정에 빠졌다.

그래도 **STEP 0은 여전히 옳았다** — 여유가 4 → **90 bytes**가 됐고(22배),
`::backdrop` 중복이 셋에서 하나로 줄었다. 다만 **FE-503/504 한 벌을 담기에
90 bytes는 여전히 부족할 수 있다.** 그 지점이 아래 escalation 조건이다.

`.sheet`/`.panel`을 가진 module은 여섯이다(`MoveDaySheet`, `ReplaceSheet`,
`TripPicker`, `RemoveItemControl`, `ConfirmDialog`, `MapUnavailable`).
`44px` 블록은 **35개 파일**에 중복돼 있으나 그쪽은 이번 밤의 범위가 아니다.

## 실행 순서

각 STEP은 **독립 커밋**이고, 앞 STEP이 green이 아니면 다음으로 가지 않는다.

### STEP 0 — 공유 sheet 모듈 추출 (선행, 필수)

- `.sheet`/`.panel`/`.head`/`.title`/`.cancel` 공통 geometry를 shared module로
- 세 sheet가 그것을 쓰도록 교체
- **완료 조건**: `verify:ci` exit 0 **이고** CSS가 8,896보다 **작아짐**
- 예산 상수는 **건드리지 않는다**(낮추는 것은 이 작업의 결과를 재는 장치다)

### STEP 1 — FE-203 — **승격을 되돌렸다. 디자인 판단이 필요하다.**

360px/zoom 측정은 들어갔지만(`dae6d87`) **승격은 성급했다.** 사용자가
*"안 만든 화면들 있다고 했었다"* 고 물어 Figma node를 코드와 전수 대조했더니,
FE-203이 소유한 **node 다섯 중 셋이 코드에 없다**:

| node | Figma | 구현 |
| --- | --- | --- |
| `399:658` S03-C1 | 담기 sheet | `TripPicker.tsx` ✅ |
| `409:1595` S06-1 | 여행 선택 | `TripPicker.tsx` ✅ |
| `399:843` **S03-C2 저장 완료** | `sheet/result` | **없음** |
| `399:1011` **S03-C3 중복** | `sheet/result` | **없음** |
| `399:1179` **S03-C4 저장 오류** | `sheet/error` | **없음** |

**동작은 있다** — `TripAddButton`이 `saved`·`duplicate`·`error` state를 갖고
`FeedScreen`이 응답으로 그것을 바꾼다. 없는 것은 **표현 형태**다: Figma는 셋을
`sheet`로 그렸는데 구현은 버튼 state를 바꾸는 inline 결과다.

**이것이 기록된 결정인지 확인했고, 아니었다.** C2~C4 행이 인용하는
`FCR-002`·`FCR-003`은 feed의 검색·bell·filter chip 제거에 대한 것이지 sheet를
접는 것이 아니다. 즉 **의도된 단순화일 수도 있고 빠뜨린 것일 수도 있는데
어느 쪽인지 적힌 곳이 없다.**

**사람이 정할 것**: (a) inline 버튼 state로 충분하다 → `FCR`을 하나 올려
Figma를 줄이고 FE-203을 승격한다, 또는 (b) sheet 셋을 만든다. 마감까지
남은 시간을 보면 (a)가 현실적이지만, **디자인 정본을 바꾸는 것은 FE 단독
결정이 아니다**(AGENTS.md: 충돌은 같은 기능 ID에서 함께 해결한다).

그동안 status는 `in-progress`로 되돌렸다.

#### 같은 검사를 승격된 카드 전체에 돌렸다 — 셋이 더 나왔다

FE-203만 고치고 끝내면 같은 실수가 남으므로 `integration-ready`·`verified`
카드 전부의 Figma node를 코드와 대조했다. **네 카드가 걸렸고 하나는 무죄다**:

| 카드 | 없는 node | 판정 |
| --- | --- | --- |
| **FE-504** | `417:2567`, `485:3517` | **무죄** — 둘 다 `REF`(참조) frame이지 화면이 아니다. 오류 문구·stale의 디자인 참조이고 동작은 구현돼 있다(7개 code + EXPIRED). 승격 유지 |
| **FE-203** | `399:843`, `399:1011`, `399:1179` | 위 참조. `in-progress`로 되돌림 |
| **FE-102** | `438:3259`(S02-5C 확인), `384:5673`(Final S02-5 추천 draft) | **확인 필요** |
| **FE-103** | `438:3199`(S02-4C-C 직접 입력) | **확인 필요** |
| **FE-305** | `527:4695`(후보 일정화 날짜 선택) | **확인 필요**. 나머지 셋은 짝 node가 구현돼 있어 무죄(`414:2347`가 `replace.ts`에 있다) |

**`REF` frame과 화면을 가르는 것이 이 검사의 핵심**이다 — node가 코드에
없다는 사실만으로는 미구현을 뜻하지 않는다. FE-504가 그 예이고, 그것을
확인하지 않았으면 멀쩡한 카드를 강등시킬 뻔했다.

FE-102·103·305의 셋은 **아침에 확인할 목록**으로 남긴다. 승격을 되돌릴지는
FE-203과 같은 질문(빠뜨린 것인가, 의도된 단순화인가)이고 같은 답이 필요하다.

#### 원래 계획 (참고)

이미 대부분 돼 있다. `TripPicker`가 `FeedScreen.tsx:296`에 연결돼 있고
42건이 통과하며 `duplicate` 처리도 있다. 남은 두 절만 채운다:

- `FE-203-T1`의 **`DISMISSED` 구분** (지금 `duplicate`만 구분한다)
- `FE-203-T3`의 **360px · 200% zoom** (keyboard/focus/Escape는 이미 있다)
- 그 뒤 status를 `planned` → `integration-ready`

### STEP 2 — FE-504 (오류 6종·stale·no improvement)

FE-503보다 **먼저** 한다. 이유: `OptimizationRunScreen`이 이미 실패 코드를
구분해 렌더하고 있어(`optimization-run.test.tsx` 21건) **증축이지 신축이 아니다.**
FE-503은 before/after 비교 UI 신규 제작이라 더 크고, STEP 2가 먼저 green이면
밤이 중간에 끊겨도 P0 하나가 남는다.

핵심 제약: **`APPLY`를 유도하지 않는다**(불변식 3·4).

### STEP 3 — FE-503 — **예산은 열렸고, 다른 것에 막혔다 (BE 차례)**

**예산은 해결됐다**(`40a51f3`, 아래 측정 근거). 그 뒤 실제 구현에 들어가서
**두 번째 blocker**를 만났고 이쪽은 FE가 풀 수 없다.

**READY run의 proposal example이 이 저장소 어디에도 없다.** 세 곳을 전부 찾았다:

| 찾은 곳 | 결과 |
| --- | --- |
| `openapi.yaml`의 200 response example | proposals를 담은 example 없음 |
| `packages/contracts/fixtures/optimizations/` | `history-page*.json` 둘뿐 |
| MSW handler | `proposals: []` — **일부러 비워 둔 것** |

MSW가 그 이유를 직접 적어 두었다:

> proposals stays empty because BA-051 computes them and nothing here may
> invent a metric or a change list — an unsourced comparison is what
> invariant 8 forbids.

즉 FE-503을 지금 만들려면 **metric 값을 지어내야 하고**, 그것이 불변식 8이
금지하는 바로 그 행위다. `CLAUDE.md:67`이 소유권을 정해 두었다 — *"FE는 승인
example mock, BE/AI는 같은 example contract test로 병렬 진행한다."* example을
만드는 것은 FE 몫이 아니다.

**BA-051은 `integration-ready`이고 서버는 실제로 proposal을 쓴다**
(`OptimizationProposalStore`·`ItemProposalMapperTest`가 `comparisonEligible`을
양쪽으로 고정한다). 그래서 이것은 *"기능이 없다"* 가 아니라 *"FE가 붙일 승인
example이 아직 없다"* 이고, **BE가 그 example 하나를 내면 STEP 3는 풀린다.**

**BE에게 요청할 것**: `getOptimizationRun`의 READY 응답 example 1건 —
`proposals[].metrics.crowdComparison`에 `eligible: true`인 것과 `false`인 것이
각각 하나씩 있으면 `MetricDelta`의 두 갈래가 모두 덮인다. 그 컴포넌트는 이미
`eligible`을 받아 숫자 대신 이유를 렌더하도록 만들어져 있다.

#### 예산 결정 (해결됨)

규칙 5대로 상수를 올리지 않고 먼저 측정치를 남겼고, 사용자가 올리기로 결정했다.

| | gzip bytes |
| --- | --- |
| 현재 여유 (STEP 0 이후) | **90** |
| `MetricDelta` + `DecisionBar` CSS를 더했을 때 증가분 | **약 462** (minify 전이라 과대추정) |
| 화면 module 하나의 실측 비용 | `OptimizationRunScreen` 719 · `OptimizeSetupScreen` 920 |

두 컴포넌트는 **만들어져 있지만 어느 화면도 쓰지 않아 번들에 없다**(번들 CSS에
`.value`·`.unavailable` class가 없는 것으로 확인). 그래서 FE-503은 그 둘을
들여오는 비용 + 자기 화면 CSS를 새로 낸다 — **90 bytes로는 어느 쪽도 안 된다.**

**측정 과정에서 두 번 헛짚었고 둘 다 기록해 둔다**(규칙 7②의 얼굴들):

1. `void __probe`로 참조만 만든 첫 probe는 **tree-shaking으로 사라져** 아무것도
   재지 않았다. 번들에 class가 들어갔는지 세어서(`0`) 알았다.
2. 실제로 render한 두 번째 probe는 **빌드가 type error로 죽어** 직전 `dist`를
   읽었다. 숫자는 그럴듯했고 *"비용이 0이다"* 로 읽힐 뻔했다 — `npm run build`의
   log를 열어서 알았다. **없는 report와 변화 없음은 다르다.**

**사람이 정할 것**: 예산을 올릴 것인가, 아니면 44px 블록(35개 파일 중복)을
공유 class로 접어 자리를 만들 것인가. 후자는 그 자체로 독립 작업이고 test가
따로 필요하다 — `check-bundle-budget.mjs`가 두 번에 걸쳐 그렇게 적어 두었다.

<!-- 아래는 결정이 난 뒤의 실행 지침이다. -->

#### 결정이 난 뒤 (원래 계획)

`MetricDelta`·`DecisionBar` 컴포넌트는 **이미 있다**. 화면 배선이 없을 뿐이다
(`OptimizationRunScreen.tsx:30-32`가 `NOT BUILT HERE`라고 명시).

핵심 제약: **`FE-503-T1` — provenance 없는 수치를 만들지 않는다**(불변식 8).
데이터가 없으면 비교를 **그리지 않는 것**이 맞고, 0이나 '보통'으로 채우지 않는다.

### STEP 4 — 문서 동기화

`frontend-plan.json` status·`IMPLEMENTATION_PLAN.md` 실행 ID 표를 같은 PR에서.
`#107` 미연결 P0가 몇 개 줄었는지 다시 센다(지금 30).

## 자동 진행의 규칙 — 판단이 필요한 지점을 미리 못박는다

1. **매 STEP은 `set -o pipefail`과 함께 `verify:ci &&` 뒤에서만 커밋한다.**
   `검사; commit`은 검사를 권고로 만든다(AGENTS.md 규칙 7③).
2. **초록을 판정으로 쓰지 않는다.** 새 검사·새 가드를 넣으면 **변이를 걸어
   빨개지는 것**을 보고 되돌린 뒤 `git status`로 확인한다(규칙 7②).
3. **`git add`와 `git commit`을 한 명령으로 묶는다**(규칙 6).
4. **push하지 않는다.** 두 required check는 `main` 대상 PR에서만 돈다.
   밤사이 쌓고 아침에 사용자가 한 번에 판단한다.
5. **예산 상수를 올려서 통과시키지 않는다.** 올려야 한다고 판단되면 **거기서
   멈추고** 측정치를 남긴다 — 그 파일이 요구하는 절차(먼저 깎아 보고, 측정하고,
   이유를 적는 것)는 사람 판단이다.
6. **계약을 바꾸지 않는다.** `docs/api/openapi.yaml`에 field가 없으면 그것은
   BE 차례다(`#180`이 그 모양이다). 없는 field를 발명하지 않는다.
7. **막히면 다음 STEP으로 넘어가지 않고 멈춘다.** 부분 성공을 커밋하고 무엇이
   막았는지 적는다. 밤사이 잘못된 방향으로 다섯 시간 가는 것이 최악이다.

## 멈춰야 하는 조건 (escalation)

- 예산을 올려야만 통과하는 상태
- 계약에 없는 field가 필요한 상태
- `verify:ci`가 **내 변경과 무관해 보이는 이유**로 빨간 상태
  (→ 규칙 7①: 빨강도 판정이 아닐 수 있다. 도구 부재와 검사 실패를 먼저 가른다)
- 불변식(1·2·3·4·7·8)과 충돌하는 요구

## 이슈 전수조사에서 나온 것 (열린 이슈 57건)

`frontend` 라벨 16건 + 라벨 없는 15건을 전부 카드와 대조했다.

- **16건 중 8건이 후보였고, 검증 끝에 6건을 닫았다**(#79·80·86·87·88·91).
  각 이슈에 *절 → 그것을 증명하는 test 이름*을 표로 남겼고, unit 973건·e2e 101건·
  `verify:ci` exit 0을 함께 적었다. **두 건은 걸러졌다**:
  - **#89 (FE-502)** — `T1`이 두 절인데 *"polling이 background 복귀 후 재개된다"* 를
    증명하는 test가 없다. 만료 run 절만 덮여 있어 규칙 3대로 **아무 절이나 하나로
    ID가 충족된 상태**다. `T1`/`T4`로 쪼개자고 이슈에 적었다.
  - **#93 (FE-506)** — `T1`의 둘째 절과 `T2` 셋을 이번에 채웠고 **`T3`가 남았다**.
    `/profile`은 360px 스윕에 있지만 이력 행의 keyboard·focus를 지목하는 test가 없다.
- **#16(CON-005)은 낡았다.** 가장 급하다던 R2(*"영어 locale에서 detail도
  영어로 오나"*)는 이미 답이 났고 `problem-message.ts:3`이 *"confirmed on
  issue #16 R2"* 로 기록하고 있다. 23개 code 중 6개는 FE 문구가 있고 17개는
  서버의 영어 `detail`로 떨어지는데, 그 17개의 한국어 문구는 **PM·디자인
  승인이 필요하다**(CLAUDE.md가 각 메시지에 Figma state를 요구한다). FE가
  지어낼 수 없다.
- **#10(ARC-001)은 이미 지나갔다** — `apps/web`·`apps/api`가 둘 다 있다.
- **#13(FCR-001~015)은 Figma 작업**이고 `apps/web` 구현은 명시적 비범위다.
  15건 전부 *"Figma 수정 완료 · 검토 대기"* 다.
- **#105(FCR-029)는 정당하게 막혀 있다** — `PlaceSummary`에 `crowd` field가
  아직 없다.

### 그 과정에서 발견한 것: 승격된 카드 11개에 acceptance ID가 없다

`check_test_reports.py`가 backend plan에 하는 일을 frontend plan에 대해
해 보면(각 카드의 `tests[].id`가 실제 test 이름에 나타나는지) **`integration-ready`
카드 중 11개가 미달**이다:

| 카드 | 절/ID |
| --- | --- |
| FE-001·002·003·004 | 2/0 |
| FE-005 | 1/0 |
| FE-102·103 | 3/0 |
| FE-506 | 3/2 |
| FE-601·602 | 3/0 · 2/0 |
| FE-603 | 4/0 |

**`FE-601`처럼 카드 ID는 주석에 있는데 `FE-601-T1` 형태가 없는 경우**가 대부분이다.
동작은 대개 구현·검증돼 있고 **집계기가 읽을 문자열이 없는 것**이다 — 오늘 밤
FE-504·FE-104·FE-506에서 고친 것과 같은 모양이고, 그 셋은 각각 절을 test 본문과
대조한 뒤 달았다.

**한 번에 25개를 다는 것은 하지 않았다.** `AGENTS.md`가 그 위험을 직접 적고
있다 — 이름만 옮겨 적으면 `integration-ready`와 같은 강도로 되돌아간다.
카드마다 절과 본문을 대조해야 하고 그건 이 시간에 자동으로 할 일이 아니다.

## 코드 전수조사 결과 — 결함 0건

이슈만이 아니라 `apps/web` 코드를 직접 훑었다. **FE가 지금 고칠 결함은 없다.**

| 검사 | 결과 |
| --- | --- |
| TODO·FIXME·HACK·미구현 표식 | **0건** (걸린 둘은 UI 문구다) |
| `any`·타입 우회 | **0건** |
| `dangerouslySetInnerHTML` | **0건** |
| geolocation 호출 | **0건** (불변식 10) |
| `localStorage` 남용 | locale 한 곳뿐 — 붙여넣기 원문·좌표 없음 |
| `<dialog>`의 Escape 처리 | 전부 있음 |
| error 상태의 복구 수단 | 전부 있음 |
| mutation 중복 제출 가드 | 전부 있음 |
| Problem code 23개 ↔ FE policy | **정확히 일치**, 양쪽 누락 0 |
| generated client ↔ 계약 | 재생성해도 diff 0 |

**두 건은 결함처럼 보였고 아니었다** — 확인하지 않았으면 없는 일을 만들 뻔했다:

- `MustVisitScreen`이 `isError`에 retry 버튼이 없다. 그러나 그것은 **검색** 오류이고
  쿼리가 입력값에 걸려 있어 **다시 입력하는 것이 곧 재시도**다. `AddPlaceScreen`의
  같은 검색도 똑같이 처리한다.
- `LanguageScreen`·`IntroScreen`이 `isPending` 가드 없이 `mutate`한다. 그러나 그것은
  **best-effort 선호 저장**이고 로컬 상태는 이미 적용됐으며 같은 locale을 다시 보내는
  것은 멱등이다. 주석이 그 의도를 적고 있다.

`준비 중` 화면들(로그인·JA/ZH·Live·DAY/TRIP scope)은 전부 **기록된 P0 결정**이지
미구현이 아니다.

## 아침에 사람이 판단할 것

- 커밋 push 여부와 PR 구성
- FE-505·FE-401~403: **BA-052/053/091/092가 `planned`이라 시작 자체가 불가**
- `#163`(PM-011) 답을 BE가 주면 `FR-FED-04` 연결
- bundle budget 상수를 올릴 것인가(STEP 0 결과를 보고)
