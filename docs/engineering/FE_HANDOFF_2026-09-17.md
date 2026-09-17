---
aliases:
  - "FE 인계 2026-09-17"
doc_type: reference
status: draft
area: engineering
tags:
  - nullnull/reference
  - nullnull/engineering
---

# FE 인계 2026-09-17 — BE/AI가 올린 9건의 현재 상태

BE/AI가 FE 처리 대상으로 올린 `#233`·`#225`·`#208`·`#188`·`#185`·`#180`·`#170`·`#165`·`#163`
9건을 전수 확인한 결과다. **결론부터: 9건 중 FE가 코드로 닫을 수 있는 것은 남아 있지 않다.**
남은 것은 BE 차례이거나, 배포(`BA-006`·`BA-071`)를 기다리거나, BE의 답을 기다리는 결정이다.

이 문서가 필요한 이유는 **이슈 본문이 양방향으로 낡았기** 때문이다. `#188`의 네 항목과
`#225`는 이슈가 요구한 것이 이미 구현돼 있었고, 반대로 `#107`은 이슈가 적은 것보다
**악화돼 있었다**(20건 → 38건, 현재 33건). 이슈 본문을 현재 상태로 읽으면 양쪽 다 틀린다.

## 한눈에

| 이슈 | 상태 | 다음 행동의 주인 |
| --- | --- | --- |
| [#225](https://github.com/yutakdv/Nullnull/issues/225) | **닫힘** — 요구한 것이 이미 구현돼 있었다 | 없음 |
| [#223](https://github.com/yutakdv/Nullnull/issues/223) | **닫힘** — 세 층 모두 이미 구현 | 없음 |
| [#208](https://github.com/yutakdv/Nullnull/issues/208) | 고침, PR #234가 머지되면 자동으로 닫힌다 | 머지 |
| [#185](https://github.com/yutakdv/Nullnull/issues/185) | 고침, PR #234가 머지되면 자동으로 닫힌다 | 머지 |
| [#188](https://github.com/yutakdv/Nullnull/issues/188) | FE 몫 4행 전부 구현·통과 | 나머지 5행은 배포 대기 |
| [#233](https://github.com/yutakdv/Nullnull/issues/233) | 4층까지 FE가 고침, **5층은 BE가 가져감** | **BE** |
| [#165](https://github.com/yutakdv/Nullnull/issues/165) | FE 검토 답변 완료 | **BE** (BA-034/040/053 해제) |
| [#170](https://github.com/yutakdv/Nullnull/issues/170) | FE 결정 답변 완료 | **BE** (401·403 **둘 다** 열거 필요) |
| [#163](https://github.com/yutakdv/Nullnull/issues/163) | FE 결정 답변 완료 | **BE** (PM-011) |
| [#180](https://github.com/yutakdv/Nullnull/issues/180) | P0 범위 확인 완료 | **BE** (계약 field) |

## 게이트 판정 — 추정이 아니라 실제 실행

`d2ac198` 기준 PR [#234](https://github.com/yutakdv/Nullnull/pull/234):

- `docs-contract` **SUCCESS**
- `docker-integration` **FAILURE** — 11 failed / 94 passed

실패 11건의 내역을 원인별로 갈라 둔다. **합계만 보면 내 변경 탓으로 읽히지만 아니다**:

| 건수 | spec | 내 변경과의 관계 |
| --- | --- | --- |
| 6 | `shell.spec` | **내 변경 이전부터 실패**하고 있었다 |
| 1 | `responsive.spec` | **내 변경 이전부터 실패**하고 있었다 |
| 4 | `keyboard-flow.spec` | **내가 만든 것** — 아래 참조 |

### 내가 남긴 것 하나 — `openWithSession`이 CI에서만 죽는다

`keyboard-flow.spec.ts`의 helper가 `waitForURL`에서 timeout한다. **로컬에서는 통과하고
CI에서만 실패한다.** 원인을 아직 측정하지 못했고, 측정하지 않은 것을 추측으로 적지 않는다.
로컬 초록은 이 질문에 답한 적이 없다(규칙 6의 아홉째와 같은 모양).

숨기지 않고 적는 이유는, 이 4건이 `shell.spec`의 6건과 **한 덩어리로 보이면** BE가
`docker-integration`을 볼 때 원인을 하나로 오인하기 때문이다. 둘은 다른 결함이다.

## `#233` — 5층짜리 사슬, 4층은 닫혔다

이 이슈는 한 가지 실패가 아니라 **다섯 층이 겹친 것**이었다. 층마다 따로 측정했다.

| 층 | 증상 | 원인 | 주인 |
| --- | --- | --- | --- |
| 1 | `Request failed with status 200` | `serve.mjs`에 `/api` proxy가 없어 index.html(823B)이 돌아왔다 | FE **완료** |
| 2 | CSRF 403 | `APP_PUBLIC_ORIGIN` 불일치. Origin header만 A/B: `web:4173`→403, `localhost:5173`→201 | FE **완료** |
| 3 | cookie 유실 | browser가 `web` origin에서 `Secure`/`__Host-` cookie를 버린다 | BE 측정, FE가 절반 |
| 4 | 401 "세션이 종료됨" | `SplashScreen`만 bootstrap하는데 test가 직접 navigate | FE **완료**(6→4) |
| 5 | `places=0, trips=0, trip_items=0` | fixture가 없다 | **BE가 가져감** |

5층에서 FE 우회는 **둘 다 막혀 있다**: `placeId`를 얻을 길이 없고, `searchPlaces`는
`NULLNULL_CATALOG_PUBLIC_ENABLED=false` 뒤에 있다.

## `#188` — FE 몫 4행은 구현·통과, 나머지 5행은 배포 대기

이슈가 "오늘 닫을 수 있다"고 적은 3건과 "절반"인 1건은 **전부 코드에 있고 통과한다**:

| 행 | 구현 | 확인 |
| --- | --- | --- |
| CMP-LOC-002 | `e2e/location-off.spec.ts` | 존재 |
| CMP-ATT-003 | `src/shared/ui/__tests__/image-assets.test.ts` | 4 tests 통과 |
| CMP-ATT-002 | 같은 파일 | 통과 |
| CMP-ATT-001 | `src/shared/ui/__tests__/attribution-coverage.test.ts` | 2 tests 통과 |

**통과만으로 쓰지 않았다.** 이슈가 경고한 *"대상이 0건이면 공허하게 통과한다"* 함정이
실제로 막혀 있는지 변이로 확인했다:

- `attribution-coverage.test.ts:85-88`이 `reads.length`·`renders.length`에
  `toBeGreaterThan(0)`을 걸어 **빈 scan을 실패로 만든다** — 이슈가 요구한 그대로다.
- `image-assets.test.ts`에 **허용목록에 없는 asset을 실제로 만들어** 빨개지는 것을 확인했고
  되돌린 뒤 `git status --porcelain`이 비었음을 확인했다. **이 scan은 발화한다.**

### 촬영 목록 — 이미 사전 확정돼 있다

이슈는 *"CMP-SUB-007에서 FE가 지금 할 수 있는 유일한 것: 촬영 목록 사전 확정"* 이라고
적었다. 그 목록은 [runbook](../contest/SUBMISSION_RUNBOOK.md)에 이미 있다(대표 1 + 상세 4,
route·상태·선행조건까지). 이번에 **표의 주장을 코드와 대조해** 다시 확인했다:

| # | route | 주장 | 대조 결과 |
| --- | --- | --- | --- |
| 1 | `/feed` | KTO 출처 + 혼잡 label | `FeedPostCard.tsx:84`가 `DataAttribution` 렌더 ✅ |
| 2 | `/trip/{id}` | 잠금 3종 독립 표시 | `LockRow.tsx`가 DATE·TIME·MUST_VISIT 분리 ✅ |
| 3 | `/trip/{id}/candidates` | 관계 badge + 날짜 선택 | `CandidatesScreen.tsx:257` relation-badge ✅ |
| 5 | `/trip/{id}/optimize` | ITEM 범위 + preview 고지 | `OptimizeSetupScreen.tsx:16-21` ✅ |

다섯 route 모두 `routes.tsx`에 실재한다. **표를 고칠 것이 없다.**

### DRI 2행 — 이미 반영돼 있다

`CMP-SUB-004`는 현재 매트릭스에서 이미 `BE·AI / FE`이고, `CMP-SUB-007`은 선행조건
셋(`BA-006`·`BA-021-T3`·`#183`)을 이미 적고 있다. `a004d59`에서 들어갔다.

## BE 차례인 4건 — 답변은 끝났고 행동이 남았다

- **`#165`** 후보 전이 matrix: FE 검토 완료. `BA-034`·`BA-040`·`BA-053`이 이것으로 풀린다.
- **`#170`** 오류 status 구분: **BE의 가정이 반증됐다.** 401만이 아니라 **401과 403 둘 다**
  열거해야 한다. 화면이 두 경우에 다른 CTA를 내야 하기 때문이다.
- **`#163`** PM-011: author·hearts·hashtags는 **P0 밖**이다. P0에 남는 것은
  `IMPRESSION`·`OPEN` 둘뿐이다.
- **`#180`** 날짜 없는 필수 장소: **P0 제출 범위가 맞다.** 계약에 field가 없어 지금은
  draft에만 남고 서버로 가지 않는다(`wizard.ts:196`의 `toCreateRequest`가 의도적으로
  `mustVisit`를 빼고, 그 이유를 주석으로 적어 뒀다). field가 생기면 **고칠 곳은 그 한 줄**이다.

## 남은 blocker

- **`BA-006`**(배포 URL)이 45개 카드 중 유일한 `blocked`이고, REQUIRED/EXCLUSION 21행 중
  **11행**을 막고 있다. `CMP-SUB-004`·`005`·`007`·`ACC-001`·`LOC-003`이 전부 여기 걸려 있다.
- **로컬 e2e는 judge 증거로 쓸 수 없다.** 개발 서버는 `VITE_API_MOCKING=on`(MSW)이고
  `docker-integration`은 catalog 게이트가 닫혀 있다. 매트릭스가 `CMP-SUB-007`에
  "mock 제외"를 적은 이유가 이것이다.
- **`#107`은 악화됐다** — 이슈 본문은 20건이라고 적지만 실제로 38건까지 갔고 현재 33건이다.
  (이후 셋을 연결해 **30**이 됐다. 남은 30 중 11건은 `FR-OPS-*`, 4건은 `FR-DAT-*`로
  FE 화면이 없고, 12건은 FE-503/504/505·FE-401~403이 붙는 날 함께 해소된다.)

## BE에게 하나 요청합니다 — FE-503이 이것 하나에 막혀 있습니다

**READY optimization run의 proposal example이 저장소 어디에도 없습니다.** 세 곳을
모두 찾았습니다:

| 찾은 곳 | 결과 |
| --- | --- |
| `openapi.yaml`의 `getOptimizationRun` 200 example | proposals를 담은 것 없음 |
| `packages/contracts/fixtures/optimizations/` | `history-page*.json` 둘뿐 |
| FE의 MSW handler | `proposals: []` — **일부러 비워 둔 것** |

MSW가 그 이유를 직접 적고 있습니다:

> proposals stays empty because BA-051 computes them and nothing here may
> invent a metric or a change list — an unsourced comparison is what
> invariant 8 forbids.

**`BA-051`은 `integration-ready`이고 서버는 실제로 proposal을 씁니다**
(`OptimizationProposalStore`, `ItemProposalMapperTest`가 `comparisonEligible`을
양방향으로 고정합니다). 그래서 이건 기능의 문제가 아니라 **FE가 붙일 승인 example이
없는 것**이고, `CLAUDE.md:67`이 소유권을 정해 둔 자리입니다 — *"FE는 승인 example
mock, BE/AI는 같은 example contract test로 병렬 진행한다."*

**필요한 것**: `getOptimizationRun`의 READY 응답 example **1건**.
`proposals[].metrics.crowdComparison`에 `eligible: true`인 것과 `false`인 것이 각각
하나씩 있으면 충분합니다 — `MetricDelta` 컴포넌트가 이미 `eligible`을 받아 숫자 대신
이유를 렌더하도록 만들어져 있어서(불변식 8), 그 두 갈래가 모두 덮입니다.

이것이 오면 FE-503은 바로 붙습니다. 화면 쪽 준비는 끝났습니다 — 컴포넌트
(`MetricDelta`·`DecisionBar`)가 이미 있고, bundle 예산도 그것을 담도록 올려 뒀습니다.
