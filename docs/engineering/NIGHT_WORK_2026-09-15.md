---
aliases:
  - "야간 작업 보고 2026-09-15"
doc_type: runbook
status: active
area: engineering
tags:
  - nullnull/runbook
  - nullnull/frontend
---

# 야간 FE 작업 보고 (2026-09-15)

**푸시·PR 없음.** 브랜치 `frontend`에 커밋 7개, 작업 트리 clean.

```text
12750f3  feat(frontend): FE-104 read a pasted itinerary into a trip (FR-TRC-06/07/09)
2bb62d6  docs(engineering): FR-TRP-04 record the night's frontend work
13e36b8  feat(frontend): FE-105 let a trip be deleted from the profile (FR-TRP-04)
2ad33a9  feat(frontend): FE-305 let a stop be taken off the itinerary (FR-ITM-06)
a87cfb8  feat(frontend): FE-203 open the trip picker from the feed's + (FR-CAN-01)
f167f36  feat(frontend): FE-203 build the trip picker the feed has been doing without
8141c4f  test(frontend): BA-040-T4 BA-070-T5 keyboard and focus through the editor (#233)
```

> `git log origin/frontend..HEAD`가 큰 수로 보이면 로컬 `origin/frontend` ref가 낡은 것이다.
> 갈라진 것이 아니라 `origin/frontend`가 `HEAD`의 조상이라 fast-forward 가능하고,
> **`main` 기준으로는 7 앞 / 0 뒤**다.

검증(마지막 커밋 기준): `verify:ci` **968 통과**(61 파일), Playwright **110 통과**,
JS 154539/157000(98%), CSS 8896/8900(100%).
Playwright `session.spec` 3건 실패는 로컬에 `apps/api`(:8080)가 없어서이고 `main`에서도 같다.

## 1. 한 일

### #233 — keyboard/focus E2E (`e2e/keyboard-flow.spec.ts`)

BE 카드 **`BA-040`·`BA-070` 두 장이 절 하나 때문에 영구 승격 불가**였고 그 절이 FE 소유였다.
집계기가 acceptance ID를 JUnit testcase 이름에서 찾는데, FE 소유 acceptance는 Playwright에
있고 일정 편집을 키보드로 검사하는 것이 **하나도 없었다**.

**가장 중요한 것은 통과한 테스트가 아니라 버린 테스트다.** 처음 쓴 단언 중 셋이 발화할 수
없었고 변이를 넣어보고서야 알았다. "live region에 글자가 있다"는 성공 안내를 지워도 통과했고
(진행 중 "Moving"이 같은 영역을 채운다), "닫으면 trigger로 포커스 복귀"는 `restoreTo`를 지워도
Escape·Cancel 양쪽에서 통과했다(네이티브 `<dialog>`가 스스로 복귀시킨다). 첫째는 성공 문구를
직접 단언하도록 고쳤고 **둘째는 단언을 지웠다** — 브라우저에서 판별 가능한 형태를 찾지 못했고,
지워지는 것을 통과시키는 단언은 커버리지로 읽히므로 없느니만 못하다.

> **BE 쪽 배선이 남아 있다.** `scripts/integration-test.sh`가 집계를 e2e 실행 **뒤로** 옮기고
> `--e2e-junit-dir`를 넘겨야 ID가 회수된다. spec은 `docker-integration` 안에서 이미 돌고 회귀 시
> 게이트를 막는다.

### FE-203 — 여행 선택 시트 (`Sheet/TripPicker`, C02) · **연결까지 완료**

**P0 기능 `FR-CAN-01`에 구현이 없었다.** `FeedScreen`이 `trips.data.items[0]`을 쓰고 있어서
**fixture의 여행 4개 중 1개만 도달 가능**했다 — `+`를 눌러도 항상 첫 여행에만 담겼다.

시트는 **고르기만 한다.** 저장은 호출자에 남겼다 — 시트가 저장까지 하면 후보가 기록되는 자리가
둘이 되고, 그것이 불변식 1이 `SavedPost`/`TripCandidate`/`TripItem`을 나누는 이유다.
상태 4개(trips/loading/empty/failed)를 각각 다르게 그린다. 현재 담고 있는 여행은 목록에서
제거하지 않고 표시만 한다(빼면 다른 행이 손가락 아래에서 움직인다).

`FeedScreen`은 이제 고른 여행을 `chosenTripId`로 들고, 그 여행이 목록에서 사라지면 첫 여행으로
되돌아간다. `saveCandidate`는 tripId를 **명시적으로** 받는다.

### FE-305 — 일정에서 빼기 (`RemoveItemControl`, FR-ITM-06)

**hook·MSW 핸들러·번역 문구 18개가 전부 있는데 렌더하는 화면이 0건이었다.** 실수로 넣은 장소를
뺄 방법이 아예 없었다.

선택이 곧 기능이다. handoff가 규칙을 명시한다: *"삭제가 후보 복원인지 완전 제거인지는 사용자가
고르게 한다. 기본값은 후보 복원."* 그래서 `ConfirmDialog`를 쓰지 않았다 — 그것은 confirm+cancel
구조라 셋째 행동을 `body` 슬롯에 밀어넣어야 하고, 그러면 본문 텍스트처럼 보이는 주요 행동이 된다.

두 번 걸린 것:

- `autoFocus`가 **닫힌 dialog가 모든 행에 마운트**돼 있어 마운트 시점에 포커스를 끌어갔다 →
  화면 탭 순서가 깨졌다(`trip-screen` 키보드 테스트가 잡았다). 명령형 `focus()`로 교체.
- 성공 안내를 **지워지는 행 안에** 렌더해서 메시지가 행과 함께 사라졌다 → `onAnnounce`로
  `TripScreen`까지 끌어올렸다.

### FE-105 — 여행 삭제 (FR-TRP-04) · **오늘 마지막 작업**

P0인데 **hook·핸들러·문구·컨트롤 전부 0건**이었다. 잘못 만든 여행을 지울 경로가 없었다.

핵심 판단 셋:

| 결정 | 이유 |
| --- | --- |
| ETag를 `TripSummary.version`에서 만든다 | 계약이 ETag를 **따옴표 친 trip version**으로 정의한다(`"7"`, `^"[1-9][0-9]*"$`). 목록 행이 이미 유효한 `If-Match`를 들고 있어 여행마다 `getTrip`을 부를 필요가 없다. 이 유도가 깨지면 서버가 409로 답한다 — **fail closed**가 불변식 6의 목적이다 |
| Idempotency-Key를 **확인창 열 때** 만든다 | 전송 시점에 만들면 같은 삭제의 재시도가 매번 새 key를 받아 **두 번째 파괴 명령**이 된다 |
| 성공을 204에서 읽는다 | 다른 trip hook이 쓰는 `if (!data) fail(...)`은 **성공한 삭제를 실패로 읽는다**. `useUnsavePost`가 선례 |

캐시는 invalidate가 아니라 **remove**한다 — invalidate는 사용자가 방금 없앤 자원을 서버에 다시
묻는다.

컨트롤은 링크 **뒤에, 링크 밖에** 둔다. 버튼은 anchor 안에 중첩될 수 없고, 프로필 키보드
테스트가 한 번 탭해서 여행 링크가 포커스를 받기를 기대한다. 여는 것이 일상 동작이고 지우는 것은
아니므로 탭 순서에서도 앞서지 않는다.

MSW 핸들러는 **상태를 갖고 실제로 행을 지운다.** fixture를 그대로 내면 *"지운 여행이 목록에서
사라졌다"* 가 화면이 아무것도 하기 전에 이미 참이 된다.

**변이 검증 4건** — 단언마다 실패시킬 수 있음을 확인:

| 변이 | 결과 |
| --- | --- |
| `If-Match`에서 따옴표 제거 | **2건 red** |
| 핸들러가 행을 지우지 않게 | **1건 red** |
| 성공 안내 삭제 | **1건 red** |
| 409 분기 무력화 | **1건 red** |

**스스로 만든 회귀 1건을 잡았다.** `Delete {name}` 전체 라벨을 보이는 텍스트로 쓰니 버튼이
205–226px가 돼 **200% 확대에서 가로 스크롤**이 생겼다(WCAG 1.4.10). 원인을 추측하지 않고
가른 실험: `flex-wrap`만 제거하면 **원래 픽셀 수치 그대로 red**, `flex-wrap`만 되돌리면 green —
즉 **실제 수정은 `flex-wrap`이고 글리프는 그 자체로는 장식**이다. 그래도 글리프+`aria-label`은
유지했다(같은 모양의 행 컨트롤이 이 앱에 11개 있고, 226px 버튼은 360px에서도 나쁘다).

> 이 과정에서 **내가 쓴 검사가 스스로 vacuous했던 일이 두 번** 있었다. 존재하지 않는 경로에
> `grep`해서 토큰이 전부 MISSING이라고 읽은 것과, `--grep`로 고른 1.5초짜리 실행을 변이 검증으로
> 쓴 것이다. 둘 다 *"빨강은 틀렸다와 물어보지 못했다 둘 다와 양립한다"* 의 사례다.

### FE-104 — 붙여넣기 import (`ImportPasteScreen`, FR-TRC-06/07/09) · **아침 이후 추가분**

**`BA-060`이 `integration-ready`가 되면서 풀렸다.** 어젯밤 문서에는 차단으로 적혀 있었다.
계약은 `parseTripImport`·`remapTripImport`·`confirmTripImport` 셋을 내내 들고 있었는데
**FE 쪽에는 hook·핸들러·문구·화면이 전부 0건**이었다.

**개인정보 규칙이 곧 기능이다.** 원문은 요청 본문으로만 나가고 그 외 어디에도 없다 — query
string·cache key·sessionStorage(핸드오프가 위자드 persistence에서 **이름을 대어 제외**한다)
어디에도 안 남고, draft가 돌아오는 순간 버린다. MSW 핸들러도 `rawText`를 빈 값 거절에만 쓰고
바로 버린다 — mock이야말로 보존이 사고로 시작되는 자리다.

**`dismissed`(#223)가 흐름을 끝낼 수 있게 만든다.** 자유 메모 줄은 label이 **비어서** 오고
제안도 없으니 영영 resolve할 수 없다. 물릴 방법이 없으면 그 draft는 READY에 도달하지 못한다 —
`FCR-019`가 기록한 막다른 길이다. 같은 필드가 item도 물리는데, 둘이 **같은 막다른 길**이기
때문이다(고칠 수도 없고 뺄 수도 없는 것을 draft가 들고 있는 상태).

**진입은 위자드 step 3에서 한다.** `400:1201`(입력 방식 선택)은 **만들지 않았다** — confirm
경계가 `FCR-018`에서 아직 Open이고, 지으면 `/start/must-visit`이 하나 더 생긴다(routes.tsx에
정의돼 있는데 아무도 링크하지 않아 **URL을 직접 쳐야만** 열리는 화면). 진입점 자체를 테스트가
단언하고, 떼면 빨개진다.

**변이 검증 5건** — 단언마다 실패시킬 수 있음을 확인:

| 변이 | 결과 |
| --- | --- |
| confirm에서 `If-Match` 제거(그 hook만 범위 지정) | **1건 red** |
| MSW가 `rawText`를 draft에 되돌려보냄 | **1건 red** |
| remap이 `dismissed`를 무시 | **1건 red** |
| confirm CTA의 READY 게이트 제거 | **1건 red** |
| step 3 진입점 제거 | **1건 red** |

> **두 번째 변이가 내 테스트의 진짜 구멍을 찾았다.** 개인정보 점검이 **나가는 요청만** 보고
> 있어서, 원문을 되돌려보내는 서버가 그대로 통과했다 — 계약의 *"response must not echo
> rawText"* 야말로 내가 검사한다고 주장한 바로 그것인데 검사하지 않고 있었다. 지금은 응답
> 본문과 렌더된 화면까지 훑고, 그 변이에서 빨개진다.
>
> **변이 두 건은 그 자체가 판정이 아니었고 다시 했다.** 하나는 의도한 한 곳 대신 **여섯 곳**에
> 치환됐고(`red: NOTHING`의 "변이 미적용"), 하나는 **다른 테스트를 돌리는 선택자**로 쟀다.

`frontend-plan.json`의 FE-104를 `planned` → **`in-progress`** 로 올리고 operation 셋과
featureId 셋을 선언에 맞췄다. `integration-ready`로 올리지 **않은** 이유는 `FE-104-T2`(offline/
stale)와 `FE-104-T3`(200% zoom·reduced motion)을 증명하는 단언이 아직 없기 때문이다.

## 2. 남은 일

### FE 단독으로 가능 (다음에 이어서)

| 항목 | 상태 |
| --- | --- |
| **`FR-ONB-03` 건너뛰기** | `IntroScreen` 버튼 1개뿐, 주석은 "continue or skip"이라 적음 |
| **`FR-ONB-03` 재방문 redirect** | `SplashScreen`이 무조건 `/language`. `OwnerProfile.onboardingCompleted`가 required라 **새 요청 없이** 분기 가능 |
| **`PostScreen` 저장 컨트롤** | 장소 행에 저장 수단이 **없다**(출처 표기에서 끝남). S06 시트는 "피드/상세 공통"이라 `TripPicker`를 여기에도 붙이면 된다 |
| **FE-203 중복 → 기존 후보로 이동** | `TripAddButton.tsx:52`에 *"a duplicate must stay navigable to the existing one"* 라고 적혀 있고 **그래서 중복 상태 버튼을 일부러 누를 수 있게 뒀는데, 배선한 호출자가 없다.** 피드에서 `/trip/:id/candidates`로 가는 링크가 0건이다(가는 곳은 `TripScreen:160`뿐). FR-ITM-06·FR-TRP-04와 같은 죽은 코드 모양 — 다만 **아래 주의 참조** |

### 설계 확정 대기 (FE 단독 불가)

- **`FR-ITM-04` 시간/duration 수정** — hook(`useUpdateTripItem`)은 있고 **호출자가 0건**이지만,
  `FCR-017`이 *"별도 시간 편집"* 을 **Open blocker**로 남겨 뒀다. Figma node가 없어 라벨을
  발명해야 하므로 **착수하지 않았다.** `527:4085`는 날짜 이동 결과이지 시간 편집 화면이 아니다.
- **`FR-PLC-01` 장소 상세** — **어젯밤 문서에서 "FE 단독 가능"이라고 쓴 것은 틀렸다.**
  계약(`getPlace`)·fixture(`place-detail.json`)는 있지만 `getPlace`를 쓰는 행이
  `FIGMA_HANDOFF.md`에 **0건**이다. 기능표의 node 칸도 `post/search/Live`(진입점)이지 전용
  frame이 아니다. `419:2617`은 `getLivePlace`(FE-402, `BA-091` 대기)이지 이것이 아니다.
  `FR-ITM-04`와 같은 설계 차단이다.
- **FE-203 중복 네비게이션의 모양** — 위 표의 죽은 코드는 실재하지만, 핸드오프는 C2/C3/C4를
  **`sheet/result`·`sheet/error`** 로, 즉 **저장 시트 안의 결과 상태**로 규정한다. 카탈로그에도
  이를 감싸는 **`C33 Sheet/SaveCandidate`** (TripPicker + TripAddButton + SheetGrab 조합)가
  정의돼 있고 **구현이 없다.** 지금 만든 `TripPicker`는 고르는 즉시 닫히고 결과는 카드 버튼에
  뜬다. 그래서 선택지가 둘이다: **(a)** 중복 버튼에 후보 패널 이동만 배선(문구 신규 필요, 상태
  표시를 동작으로 바꾸므로 접근성 이름도 바뀜), **(b)** `C33`을 만들어 결과 3종을 시트 안에서
  처리. (b)가 정본에 맞지만 **CSS 예산이 4바이트 남아** 새 시트는 확실히 넘긴다. 오너 판단이
  필요하다.
- **위자드 step 4/5**(`438:3199` 직접 입력, `438:3259` confirm) — `FCR-018`·`FCR-019` Open
- **`MustVisitScreen`** — 도달 불가이고 고른 값을 버린다. `CreateTripRequest`에
  `mustVisitPlaceIds`가 없어 **BE 차단**
- **"내 여행" 탭** — `/profile`로 가는데 `activeTripId` 배선이 없어 **BE 차단**

### BE/오너 대기

- **#183 게시물 큐레이션** — 제출 1번 스크린샷이 `/feed`인데 게시물 0건. `placeId`가 실재하는
  카탈로그 행이어야 하고 그것은 `BA-021` 소관이라, **본문·제목·표지는 지금 쓸 수 있고 `placeId`만
  게이트 개방 후** 채우면 된다([서식](../contest/CURATED_POSTS_TEMPLATE.md))
- **#180** `FR-TRC-04` P0/P1 결정 — #185를 막고 있다
- **#163·#165·#170·#204** — 전부 내 답변이 마지막이고 BE 차례
- **#223** `dismissed`가 계약에 착륙했으므로 `FIGMA_HANDOFF.md`의 *"unresolved 수정 후에만
  confirm"* 서술을 정정할 수 있다 — **아직 안 고쳤다**
- Live 탭 5화면 — `BA-090/091/092` 전부 `planned`

## 3. 주의

- **CSS 번들 100%** (8896/8900, **여유 4바이트**). 다음 CSS 추가는 거의 확실히 넘긴다.
  FE-104에서 이미 한 번 걸렸고, **상한을 올리는 대신** 새 stylesheet가 위자드의
  `screen`/`head`/`title`/`lead`/`hint`를 **재사용하도록 고쳐서** 통과시켰다(같은 flow이므로
  중복 정의가 애초에 틀린 것이었다). 상한은 `TripPicker` 때 이미 한 번 올렸고, 그때 적어둔
  진짜 해법은 여전히 유효하다 — `MoveDaySheet`/`TripPicker`/`RemoveItemControl`/
  `ImportPasteScreen`이 공유하는 시트·행 geometry를 **모듈 하나로 빼는 것**이고, 별도 리팩터다.
  **다음 사람에게**: 넘치면 상한을 올리기 전에 중복부터 찾아라.
- 커밋 메시지에 AI co-author 트레일러를 넣으면 `commit-msg` 훅이 거부한다.
- **plan 상태**: `FE-104`는 `planned` → **`in-progress`** 로 올렸고 operation·featureId 선언을
  실제 구현에 맞췄다(`validate_frontend_plan.py`·`validate_docs.py` 통과). `integration-ready`가
  아닌 이유는 `-T2`(offline/stale)·`-T3`(200% zoom·reduced motion)을 증명하는 단언이 없기
  때문이다. 반면 `FR-TRP-04`는 `frontend-plan.json`에 **카드가 아예 없다** — FE-\* 실행 ID를
  부여할지는 플랜 소유자 판단이 필요하다.
