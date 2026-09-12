# 밤 작업 보고서 — 2026-09-12

프론트엔드 완성도 작업. `frontend` 브랜치에 커밋 10개, **push 안 함**.

---

## 1. 한 줄 요약

코드 품질은 이미 높았습니다. 문제는 **완성된 조각들이 서로 연결되지 않은 것**이었고,
그중 가장 큰 것은 `npm run dev`가 아무에게도 동작하지 않아 아무도 앱을 눈으로 확인할
수 없었다는 점입니다. 그것부터 고치니 그동안 보이지 않던 결함들이 드러났습니다.

## 2. 분석하며 파악한 현재 상태

- P0 화면 33개 중 23개가 `integration-ready`. TODO/FIXME 0건. 주석이 "왜"를 설명하는
  수준이 일관되게 높습니다.
- 백엔드는 **아직 아무 화면도 서빙하지 않습니다**. BA-030·032·034·050~053이 전부
  `planned`이라, FE는 승인된 OpenAPI example 기반 MSW mock으로 작업하는 단계입니다.
  `integration-ready`는 "mock 기준 완성, 서버 대기"라는 뜻입니다.
- 주요 사용자 플로우: 온보딩 → 피드 → 게시물/여행 만들기 → 일정 편집·후보 담기 →
  **AI 최적화** → 프로필(이력·관심사·삭제).

## 3. 발견한 주요 문제와 조치

### 🔴 1. `npm run dev`이 완전히 깨져 있었다 → 고침 (`fccfa10`)

`VITE_API_MOCKING`을 설정하는 곳이 레포 전체에 없었습니다. MSW worker가 등록되지
않아 모든 요청이 `localhost:8080` proxy로 빠지고, 백엔드가 없으니 **모든 화면이
"불러오는 중"에서 영구히 멈췄습니다.** `apps/web/README.md`도 없었습니다.

`dev`가 값을 설정하도록 하고, 실제 API를 볼 때 쓰는 `dev:api`를 남겼습니다. README를
새로 썼습니다.

> 주의해서 남긴 것: worker는 `public/`이 아니라 `mocks/`에 있어야 합니다. `dist`로
> 복사되면 production 이미지가 mock을 서빙하고, `integration-test.sh`가 그 이미지로
> E2E를 돌리기 때문에 게이트가 아무것도 증명하지 못한 채 통과합니다. 제가 처음에
> `msw init public`으로 이 설계를 깰 뻔했고, `dist/`에 worker가 새는 것을 확인하고
> 되돌렸습니다.

### 🔴 2. 최적화 플로우의 결말이 없었다 → FE-502 구현 (`071409e`)

P0 핵심 기능인데, "대안 찾아보기"를 누르면 **`optimization`이라는 디버그 문자열만
있는 빈 페이지**에 도착했습니다. 뒤로 가기 버튼도 탭바도 없어 탈출구가 없었고,
프로필 이력의 링크 4개도 전부 같은 곳으로 갔습니다.

계약(`getOptimization`)이 완비돼 있어서 구현했습니다:

- **폴링은 서버가 시키는 대로**: 간격은 응답의 `Retry-After`(계약이 QUEUED/RUNNING에
  보낸다고 명시), 종료 상태가 되면 멈춥니다.
- **만료된 preview는 재시도하지 않음**: `410 PREVIEW_EXPIRED`는 되돌릴 수 없습니다.
- **실패 6종을 각각 구분**하고, 계약이 `retryable`이라고 한 경우에만 재계산을 권합니다.
- **모든 종료 상태에서 "일정은 그대로예요"를 말합니다**(불변식 3·4).
- **FCR-014**: 이탈은 navigation일 뿐 취소가 아니라고 문구로 명시.
- **FCR-005**: route provider가 없으므로 `경로 계산` 대신 혼잡·고정 조건만 언급.

**만들지 않은 것**: READY의 before/after·지표·decision bar는 FE-503/505이고 BA-051·052가
필요합니다. proposals가 빈 배열인 상태에서 preview를 그리면 불변식 8이 금지하는
"출처 없는 비교"가 됩니다. 그래서 "결과 화면은 준비 중"이라고 정직하게 말합니다.

### 🔴 3. 재시도가 중복 생성을 만들었다 (불변식 6) → 고침 (`ed3640f`)

`session.ts`가 스스로 규칙을 적어놨는데(`useCreateOptimization`) 세 군데가 어기고
있었고, **각각 자기 주석이 코드와 반대를 말하고 있었습니다**:

| 대상 | 주석이 약속한 것 | 실제 결과 |
|---|---|---|
| `useCreateTrip` | "그 시도에 한해 재사용" | 타임아웃 재시도 시 **여행이 2개** |
| `useAddTripItem` | "반복 제출이 장소를 두 번 담지 않는다" | 같은 날짜에 **장소가 2번** |
| `AddPlaceScreen` | "같은 누름의 재시도면 재사용" | item 분기엔 키를 **아예 안 넘김** |

키를 호출자가 들고 있게 바꿨습니다. 요청 내용을 fingerprint로 써서 **같은 요청의
재시도는 키를 유지하고, 요청이 바뀌면 새로 만듭니다.**

기존 테스트는 이걸 잡을 수 없었습니다 — 한 번만 제출하고 "키가 UUID인가"만 봤는데,
매번 새로 만드는 코드도 그 조건은 만족합니다. 이름은 "a repeat cannot make a second
trip"이었지만 본문에 repeat이 없었습니다.

### 🟡 4. 접근성 가드가 빈 화면에서만 통과하고 있었다 → 고침 (`56a7582`)

mock을 켜자 E2E 5건이 빨개졌습니다. 화면에 **처음으로 데이터가 들어갔기** 때문입니다.

진짜 결함 2건:
- `Chip`이 `min-height: 44px`만 두고 가로축이 없어, 짧은 라벨("전체"/"All")이 **40px**.
- 프로필 관심사 칩이 `white-space: nowrap`을 상속해 200% 확대에서 화면 밖으로 밀림.

검사 자체의 문제 3건: 의도적으로 가로 스크롤되는 행(`.dayNav`)을 오탐하던 것,
KTO 출처 링크(84×12, 문장 속 inline 링크 — WCAG 2.5.5/2.5.8 면제 대상)를 탭 타깃으로
재던 것, not-found 포커스 테스트가 hydration과 경쟁하던 것.

### 🟡 5. 피드 카드 5장 중 4장이 404 → 고침 (`0488879`)
### 🟡 6. 라이브 탭이 디버그 문자열을 보여줬다 → 준비 중 화면 (`b529428`)
### 🟡 7. 여행 만들기 마법사에 뒤로가기가 없었다 → 고침 (`700cdde`)

날짜를 잘못 고르면 **고칠 방법이 없었습니다.** 단계가 route가 아니라 state여서 브라우저
뒤로가기는 `/start`를 아예 떠나고 초안이 사라집니다. 브라우저로 재현했습니다: 9/15–9/18
선택 → 다음 → 뒤로 → 돌아오면 STEP 1, **선택 0건**.

`wizard.back`("이전 단계"/"Previous step")이 **양쪽 언어에 이미 번역돼 있는데 아무데도
렌더되지 않고 있었습니다** — 설계 의도는 있었고 배선만 빠진 것입니다.

### 🟡 8. 계획 정본 드리프트 → 고침 (`fccc242`)

FE-506이 구현 완료인데 `planned`이었습니다.

### 🔴 9. 세션이 만료되면 아무 말 없이 영원히 로딩 → 고침 (`62c586d`)

`AppShell`이 `useCsrfToken()`을 호출하고 **반환값을 버리고 있었습니다.**
`PROBLEM_POLICY`는 `UNAUTHORIZED`를 `severity: screen`·`recovery: restart-session`으로
선언하는데 둘 다 렌더되지 않았습니다.

만료된 쿠키로 `/feed`를 열면: CSRF 재발급이 401(재시도 안 함 — 만료된 세션에 다시
bootstrap하면 **다른 익명 owner**가 생겨 여행이 전부 끊깁니다), 화면의 요청도 401,
그리고 피드는 `trips.isSuccess`로 게이팅돼 있어 영원히 로딩. 직접 렌더해서 확인한
body 전체가 `"Browse Loading"`이었습니다.

이제 세션 종료 화면과 "다시 시작하기"를 보여줍니다. 재시작은 splash로 보내서
**사용자의 의도적 행동**으로 만듭니다 — 여기서 자동 bootstrap하면 방금 "여행은 다시
볼 수 있다"고 약속한 그 여행을 끊게 됩니다.

401만 이 화면을 띄웁니다. 네트워크 끊김은 복구 가능하고 데이터도 그대로인데 "세션이
만료됐다"고 말하면 거짓말입니다.

### 🔴 10. 여행 목록이 실패하면 피드가 영원히 로딩 → 고침 (`94e30f8`)

위 9번 테스트를 쓰다가 발견했습니다. 피드 쿼리는 `trips.isSuccess`로 게이팅되는데
(cursor가 여행 선택에 묶여 있어서) **`trips.isError`를 읽는 코드가 없어서**, 여행
목록이 실패하면 피드 쿼리가 비활성 상태로 남고 `isPending`이 영원히 true였습니다.

재시도는 둘 다 refetch합니다. 피드만 재시도하면 카드는 돌아오지만 **"피드를 불러오지
못했어요" 배너가 그대로 남은 채**입니다 — `trips.refetch()`를 빼고 돌려서 확인했습니다.

## 4. 검증 결과

| 검사 | 결과 |
|---|---|
| `verify:ci` | ✅ **832 tests / 55 files**, lint·format·tsc·build·budget 통과 (시작 시 793) |
| Playwright E2E | ✅ **48 passed**, 31.0s |
| `validate_docs.py` | ✅ 통과 |
| markdownlint | ✅ 0 issues |
| 번들 budget | ⚠️ JS 95%, **CSS 96%** — 상한에 근접 |

E2E 실패 3건(`session.spec`)은 **`apps/api`(:8080)를 직접 호출**하는 transport 테스트라
백엔드 없이는 실패합니다. 브라우저를 쓰지 않아 MSW가 가로채지 않습니다. 정상입니다.

**모든 수정은 negative control로 검증했습니다** — 고친 것을 일부러 되돌려 해당 테스트가
실제로 실패하는지 확인했습니다. 이 과정에서 제가 새로 쓴 폴링 테스트 3개가 "영원히
폴링" 버그를 **잡지 못한다**는 걸 발견해서 대기 창을 늘렸습니다. 안 돌렸으면 아무것도
증명하지 못하는 테스트를 그대로 커밋할 뻔했습니다.

## 5. 발견했지만 고치지 않은 것

감사에서 15건이 검증을 통과했고, 그중 우선순위가 낮거나 범위 밖인 것들입니다.

| 문제 | 왜 안 고쳤나 |
|---|---|
| `FeedScreen` cursor reset 이펙트가 재실패 시 무한 루프 가능 | 재현 조건이 좁고, 수정이 피드 페이지네이션 전체를 건드립니다 |
| `LockRow`의 `TRIP_CHANGED`가 trip을 refetch하지 않음 | FE-307 범위. 재시도가 같은 stale ETag를 보냅니다 |
| `TripAddButton` 36×36 (44px 미만) — 주석은 44px라고 주장 | 피드 카드가 E2E `SCREENS` 목록에 없어 측정되지 않습니다. 목록 추가 + 수정이 한 세트라 분리했습니다 |
| 피드 `+` 버튼이 접근성 이름은 있는데 동작 없음 | FE-203(후보 담기) 범위 |
| MustVisit 검색 결과의 "담기" 버튼들이 전부 같은 이름 | FE-103 범위 |
| 후보 일정화 후 포커스가 `document.body`로 빠짐 | FE-303 범위 |
| `FeedPostCard`/`TripScreen`에 KTO 출처 누락 (CMP-ATT-001) | **공모전 채점 항목**이라 별도 판단 필요 — 아래 6번 |
| `no-hardcoded-copy` 테스트가 prop 존재만 확인 | 테스트 품질 문제, 기능 영향 없음 |

## 6. 당신이 판단해야 할 것

1. **KTO 출처 표기 범위 (CMP-ATT-001)** — 감사에서 `FeedPostCard`와 `TripScreen`이
   KTO 장소명을 출처 없이 렌더한다고 나왔습니다. `COMPETITION_COMPLIANCE_MATRIX.md`는
   "KTO 화면 DOM/visual coverage 100%"를 점수 항목으로 둡니다. 다만 현재 trip fixture는
   `sourceAttribution: null`이라 실제로는 표시할 데이터가 없는 상태입니다. **"모든 KTO
   장소가 모든 화면에서 출처를 달아야 하는가"는 공모전 규정 해석이라 제가 정하지
   않았습니다.** 피드 카드는 crowd가 있을 때만 출처가 붙습니다.

2. **CSS 번들 96%** — 화면을 몇 개 더 추가하면 상한을 넘습니다. 상한을 올릴지, CSS를
   줄일지 결정이 필요합니다.

3. **`PlaceholderScreen`이 이제 어디에서도 안 쓰입니다** — 삭제할지, 향후 route용
   scaffold로 남길지. 일단 남겼습니다.

4. **BE에 전달할 것**: FE-502가 `getOptimization`을 실제로 소비하기 시작했습니다.
   BA-050이 열리면 mock handler만 지우면 됩니다. `Retry-After`를 QUEUED/RUNNING에
   실제로 보내주셔야 폴링이 동작합니다.

## 7. 다음에 하면 좋은 작업 (우선순위 순)

1. **FE-203 후보 담기** — 피드의 `+` 버튼이 현재 죽어 있습니다(접근성 이름은 있는데
   눌러도 아무 일도 안 납니다). 피드→여행 연결이 끊긴 상태라 체감이 큽니다.
2. **FE-503** — BA-051이 열리면 최적화 결과의 before/after. 지금은 "준비 중"에서 멈춥니다.
3. `TripAddButton` 44px + E2E `SCREENS`에 `/feed` 추가 (한 세트 — 피드 카드가 측정
   대상에 없어서 36×36이 지금까지 안 잡혔습니다).
4. 나머지 접근성 항목(후보 일정화 후 포커스 복귀, MustVisit 중복 버튼 이름).
5. `FeedScreen` cursor reset 무한 루프 가능성.

## 8. 로컬 E2E 주의사항 (시간 낭비 방지)

`npx playwright test`를 돌릴 때 **다른 브라우저 세션이 떠 있으면** 무작위로 테스트
1건이 `networkidle` 대기에서 15분씩 멈춥니다. 매번 다른 테스트가 걸립니다.

원인: dev 서버 모드에서 `/trip/...` 한 화면이 **199개 요청**(대부분 Vite 모듈)을 냅니다.
부하가 겹치면 500ms 정적 구간이 안 생깁니다. **CI에서는 발생하지 않습니다** — compose가
`PLAYWRIGHT_BASE_URL`을 주므로 빌드된 컨테이너를 상대로 돌고 Vite 모듈 요청이 없습니다.

정리하고 돌리면 29초에 48건 통과합니다.

## 9. 커밋

```
94e30f8 fix(frontend): FE-201 stop the feed hanging when the trip list fails
62c586d fix(frontend): FE-101 show an ended session instead of loading for ever
5d4ccc1 docs: night work report for 2026-09-12
700cdde fix(frontend): FE-102 let the wizard go back a step instead of trapping the draft
b529428 feat(frontend): FE-401 give the live tab a screen that says what it is
fccc242 docs(frontend): FE-502 FE-506 record the two tasks that are actually built
071409e feat(frontend): FE-502 build the screen a submitted optimization lands on
0488879 fix(frontend): FE-202 serve a detail for every post the feed lists
ed3640f fix(frontend): FE-102 FE-303 FE-305 hold the Idempotency-Key across a retry
56a7582 fix(frontend): FE-601 repair the a11y guards that only passed on empty screens
fccfa10 fix(frontend): FE-001 make `npm run dev` actually run the app
```

`origin/main`은 작업 시작 시 merge했습니다(계약 변경 4건, 충돌 없음). **push는 하지
않았습니다.**

---

# 후속 작업 — 2026-09-12 (PR #130 병합 후)

커밋 5개. 남은 P0 8개 중 6개가 BE 대기(BA-034·050·051·060·091)라, FE가 독립적으로
할 수 있는 것 — 이미 만든 화면의 미완성 배선과 접근성 결함 — 을 처리했습니다.

## 1. 피드 `+` 버튼이 죽어 있었다 → FE-203 배선 (`2a6ee18`)

`FeedPostCard`는 `onAddCandidate` prop을 처음부터 갖고 있었고 `FeedScreen`이 넘기지
않았습니다. 브라우저에서 fetch를 감싸고 눌러본 결과 **요청 0건, aria-label 변화 없음,
메시지 없음**. `FeedScreen`의 헤더 주석이 경고하던 바로 그 상태였습니다 — "a control
that silently did nothing would be worse than none".

훅·계약·6개 상태 문구가 전부 이미 있었고 핸들러만 없었습니다. 이제 누르면
`POST /trips/{id}/candidates` → 201, 버튼이 `담았어요`로 바뀝니다.

- 일정이 아니라 후보입니다. 계약의 `tripScheduleChanged`가 `const: false`라 불변식 2가
  스키마에 박혀 있고, 테스트가 wire에서 `/items` 요청이 없음을 확인합니다.
- source는 `POST` + 그 게시물 id. `FEED`라는 타입을 지어내면 컴파일이 안 됩니다.
- 장소당 Idempotency-Key 하나, 재시도 동안 유지.
- 이미 담긴 카드와 여행 없는 카드는 아예 바인딩하지 않습니다.

**여행 선택 sheet(S06)는 만들지 않았습니다** — FE-203 본체이고 BA-034가 필요합니다.

## 2. 같은 버튼이 36×36이었다 → 44px (`b8add16`)

`TripAddButton` 주석이 "hit area reaches the 44px minimum"이라고 했는데 구현이
없었습니다. **`/feed`가 E2E 측정 대상 목록에 없어서** 아무도 못 봤습니다. 목록에 추가하니
즉시 4개가 잡혔습니다.

측정해서 찾은 것 2가지 (테스트만 봤으면 놓쳤을 것):
- `box-sizing: content-box`가 브라우저 기본 button padding(1px 6px)을 더해 56×46이 됨
- state variant들이 `background` shorthand로 `background-clip`을 리셋 → 링이 44px
  가장자리에 그려짐

## 3. 잠금 충돌이 복구 불가였다 → refetch (`b870a47`)

409 TRIP_CHANGED 분기가 **아무것도 안 하는 `return`**이었습니다. ETag가 stale해진
순간이라 다시 눌러도 같은 If-Match로 똑같이 실패 — 페이지를 새로고침하기 전까지
동작할 수 없는 버튼 앞에 사용자가 갇힙니다. `CandidatesScreen`은 이미 refetch하고
있었습니다.

## 4. 접근성 2건 (`23ba4a2`)

- MustVisit 검색 결과 버튼이 전부 `담기` → 스크린리더로 구분 불가. 접근성 이름에만
  장소를 넣었습니다(같은 화면 remove 버튼이 이미 쓰던 방식).
- 후보 일정화 후 포커스가 `document.body`로 떨어짐. 첫 수정은 toggle 버튼을 가리켰는데
  **실패했습니다** — 렌더 트리를 찍어보니 성공 후 그 버튼이 사라집니다. remove 버튼으로
  바꿨습니다.

## 5. cursor reset 무한 루프 (`1beb2cf`) — 감사 평가보다 심각했음

reset refetch가 또 CURSOR_EXPIRED를 받으면 latch가 이미 풀려 있어 즉시 재발동.
**600ms에 약 14,900건**을 측정했습니다. 계약이 `retry: 'none'`으로 표시한 코드입니다.

## 검증

| 검사 | 결과 |
|---|---|
| `verify:ci` | ✅ **847 tests / 55 files** (밤 작업 시작 시 793) |
| Playwright E2E | ✅ **53 passed** (신규 피드 화면 5건 포함), 3건은 백엔드 필요 |
| 번들 | JS 95%, CSS 96% |

전부 negative control로 양방향 확인했습니다. 이 과정에서 제가 새로 쓴 테스트 2개가
정작 버그를 못 잡는 걸 발견해 보강했습니다(세션 만료 판정, cursor 두 번째 복구).

## 여전히 남은 것

- **FE-503/504/505** — BA-050/051/052 없음. 특히 503은 `proposals`가 비어 있어 지금
  만들면 불변식 8 위반
- **FE-104, FE-402, FE-403** — BA-060/091 없음
- **FE-203 여행 선택 sheet** — BA-034 없음
- **CMP-ATT-001 출처 표기 범위** — 공모전 규정 해석, 당신 판단 대기
- **CSS 96%** — 화면 추가 시 상한 초과
