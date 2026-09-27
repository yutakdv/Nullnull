---
aliases:
  - "FE 실행 계획 240 쿠키 없음 신호"
doc_type: reference
status: draft
area: engineering
tags:
  - nullnull/reference
  - nullnull/engineering
---

# FE 실행 계획 — #240 쿠키 없음 신호 소비

BE가 `missingCredential` 신호를 구현했고([#240](https://github.com/yutakdv/Nullnull/issues/240),
`backend` 로컬 `3e31499`), 별도 `backend → main` PR로 나온다. 이 문서는 **그것이
`main`에 들어온 뒤** FE가 할 일을 순서대로 적는다.

읽는 사람: 이 작업을 이어받는 FE 세션. 지금 시작하면 안 되는 이유부터 읽어라.

## 지금 시작하면 안 되는 이유

계약에 그 필드가 **아직 없다.**

```bash
grep -c missingCredential docs/api/openapi.yaml packages/api-client/src/generated/openapi.ts
# 둘 다 0
```

그래서 지금 `problemResponse` 오버로드를 만들면 `as` 캐스팅으로 없는 필드를 심어야
하고, 계약이 오면 **다시 고쳐야 한다.** 호출자가 79곳이라 시그니처를 건드릴 이유도
없다. 준비 작업으로 보이지만 실제로는 되돌릴 일을 만드는 것이다.

## 계약 모양 (BE가 확정해 알려준 것)

```ts
isProblem(e) && e.code === 'UNAUTHORIZED' && e.missingCredential === 'SESSION_COOKIE'
```

- `Problem`의 **optional property**로 들어간다(`allOf` 확장 없음 — 닫힌 schema다)
- **쿠키가 아예 없을 때만** 실린다. 그 밖의 401(만료·폐기·위조·빈 값)에는 필드 자체가 없다
- `code`·`status`·`retryable: false`는 그대로. `detail`만 *"No session cookie was sent."*
- 계약에 example 둘: `sessionAbsent`, `sessionInvalid`
- BE 측정: oasdiff breaking 0

**같이 바뀐 서버 동작** — FE가 알아야 하는 것:

- cross-origin의 상태 변경 요청은 쿠키 유무와 무관하게 **필드 없는 403 `CSRF_INVALID`**.
  이전에는 무효 쿠키면 401이었다. 신호가 same-origin 검사보다 먼저 나가지 않게 하려는 것

## 실행 순서

### 1. main을 끌어온다

```bash
git fetch origin main
git switch frontend && git merge --no-ff origin/main
```

merge commit을 쓴다(원칙 15). rebase·force push 금지.

### 2. 생성 client를 재생성한다

```bash
cd packages/api-client && npm run generate
grep -c missingCredential src/generated/openapi.ts    # 0이 아니어야 한다
```

**커밋된 계약에서 생성한다.** 생성물이 계약과 어긋나면 `check-generated.mjs`가
`docker-integration`에서 잡는다.

### 3. `AppShell`의 판정을 바꾼다

현재(`AppShell.tsx:120`):

```ts
const bootstrapped = session.isSuccess;
const sessionGone =
  isProblem(csrf.error) && csrf.error.code === 'UNAUTHORIZED' && !bootstrapped;
```

`bootstrapped`는 *"이 탭에서 splash의 bootstrap이 성공했는가"*를 캐시로 관측하는
**우회**다. 첫 방문의 정상 401이 "세션 종료" 화면으로 굳는 것만 막고, **쿠키 없음과
만료를 구별하지 못한다**(#240의 06:52 코멘트 ③).

신호가 오면 그 구별이 가능해진다.

| 상황 | 지금 | 신호 이후 |
| --- | --- | --- |
| 쿠키 없이 딥링크 | 세션 종료 화면 | **자동 bootstrap → 정상 진입** |
| 만료 쿠키로 딥링크 | 세션 종료 화면 | 세션 종료 화면 (그대로, 올바름) |

**자동 bootstrap을 넣을 때의 위험**: 구별 없이 bootstrap하면 만료 사용자가 **다른
익명 owner**를 받아 여행이 전부 유실된다(`SessionSafetyIT.expiration`). 그래서
`missingCredential === 'SESSION_COOKIE'`가 **참일 때만** bootstrap해야 하고, 그것을
test로 고정해야 한다. 이 조건을 느슨하게 쓰면 #240이 막으려던 바로 그 사고가 난다.

### 4. MSW에 변형을 만든다

`problemResponse(code)`는 code당 fixture 하나를 꺼낸다(`problemFixtures`가
`Record<ProblemCode, Problem>`이다). 같은 code의 두 번째 변형은 그 체계에 자리가
없으므로, **한 필드를 덧씌우는 오버로드**를 더한다.

```ts
problemResponse('UNAUTHORIZED', { missingCredential: 'SESSION_COOKIE' })
// 주의: 현재 두 번째 인자는 headers 다
```

현재 시그니처가 `(code, headers)`이므로 **세 번째 인자를 더하거나 옵션 객체로
바꾸는 편**이 안전하다. 호출자가 79곳이라 기존 인자 의미를 바꾸면 전부 영향을 받는다.

BE는 fixture 대신 **계약의 named example 둘**을 두기로 했다. MSW 변형을 그 example에
맞춰 고정한다.

### 5. e2e를 되살린다

이 신호가 없어서 난 실패 13건이 정확히 *"쿠키 없는 브라우저가 splash 아닌 route로 진입"*이다.
Playwright의 각 context가 새 브라우저라 `/` 이외로 들어가면 전부 이 case다.

### 6. push는 한 번에

`AGENTS.md` 규칙 7③: 여러 번 밀면 게이트가 매번 처음부터 돌고 **누구의 커밋도 완주
초록을 본 적 없는 상태**가 된다. 위 1–5를 다 하고 **한 번** push한다.

**push 직후 auto-merge를 끈다.**

```bash
gh pr merge 234 --disable-auto
```

`.github/workflows/auto-merge.yml`이 `synchronize`에 반응하고 조건이
`head_ref == 'frontend'`라, **`frontend`에 push할 때마다 다시 켜진다.** 켜진 채로
게이트가 초록이 되면 완주를 보기 전에 머지된다.

완주 한 바퀴를 **관측한 뒤** 머지 여부를 정한다.

## 검증

```bash
cd apps/web && npm run verify:ci
```

`docker-integration`은 로컬에서 돌리지 않는다. 돌리지 않은 검사를 통과로 쓰지 않는다.

`e2e/session.integration.spec.ts`(`10d86196` 전 이름 `session.spec.ts`)의
`BA-010`·`011`·`012` 3건은 **실제 backend가 필요하다.** 이 문서를 쓸 때는 로컬에서 원래
빨갰고, 제 변경과 무관하다는 것을 `git stash`로 확인했다. 지금은 로컬 mock 실행에
나타나지 않는다 — `playwright.config.ts`의 `testIgnore`가 `*.integration.spec.ts`를
composed API 실행에서만 모은다. 그래서 로컬 결과는 이 3건에 대해 아무것도 판정하지 않고,
판정은 `docker-integration`만 낸다.

## 열려 있는 것

없다. 여기 적혀 있던 항목(session spec의 하드코딩된 `http://localhost:5173` 세 곳과
compose의 `APP_PUBLIC_ORIGIN`을 **한 PR로** 넣어야 한다는 BE 판단, #233 09-16)은 **이
문서보다 먼저 닫혀 있었다.** `7ffd37e8`(DX-003, 09-17)이 두 쪽을 한 커밋으로 넣었다 —
spec의 세 literal을 `PLAYWRIGHT_BASE_URL`에서 유도하는
`ORIGIN`으로 바꾸고(지금 `e2e/session.integration.spec.ts:27`, `http://localhost:5173`은
:19 주석에 옛 값으로만 남는다), `compose.integration.yml`의 `APP_PUBLIC_ORIGIN`과 e2e
`PLAYWRIGHT_BASE_URL`을 둘 다 `http://localhost:4173`으로 맞췄다(지금 :200, :226). 줄
번호 `16,53,94`는 `7ffd37e8` 직전 파일의 것이었다.
