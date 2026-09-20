---
aliases:
  - "P1 전체 실행 계획(조율자용)"
doc_type: plan
status: draft
area: engineering
tags:
  - nullnull/plan
  - nullnull/engineering
---

# P1 전체 실행 계획 — 조율자 세션용

오너가 2026-09-20에 **P1 8장 전체 진행**을 결정했다. 이 문서는 조율자 세션이 그대로 실행하는 계획이다.
작업자 세션은 이 문서를 읽고 자기 스트림만 본다.

## 0. 이 계획이 딛고 선 사실

전부 2026-09-20 세션에서 읽은 것이다. 추정은 추정이라고 적었다.

| 사실 | 출처 |
| --- | --- |
| P1 카드는 8장(BA-080~086, BA-093), 전부 `planned` | `docs/engineering/backend-plan.json` |
| 그중 7장이 `BA-073`(`planned`)에 의존하고, BA-093은 BA-091·BA-092에 의존 | 같은 파일 `dependsOn` |
| 52개 operation 중 `x-release: P1`은 알림 3개뿐 | `docs/api/openapi.yaml` |
| FE 계획에 P1 구현 카드가 0장(`FE-308` 하나가 `deferred`) | `docs/engineering/frontend-plan.json` |
| `FE-P1-101`~`106`은 실행 ID 표에만 있고 JSON에 없다 | `docs/engineering/IMPLEMENTATION_PLAN.md` |
| 1차 자료 제출 마감 2026-09-21 16:00 | `docs/contest/2026-관광데이터-활용-공모전-공지-심사기준.md` |
| 최신 migration은 `V036` | `apps/api/src/main/resources/db/migration/` |
| 통합 게이트 compose project 이름이 `nullnull-integration`으로 고정 | `compose.integration.yml` |

**마감 충돌은 보고했고 오너가 강행을 선택했다.** 이 문서는 그 결정 위에서 쓰였고 다시 논쟁하지 않는다.
다만 `R-033`(과도한 범위로 핵심 flow 불완전)과 `R-029`(마감)가 원장에 이미 있으므로,
조율자는 **제출 스트림을 다른 스트림과 같은 슬롯에서 경쟁시키지 않는다**.

## 1. 오너 결정 (2026-09-20 확정)

| 결정 | 답 | 원장 |
| --- | --- | --- |
| 경로 provider | **카카오 계열 API** | `D-002` 닫는다 |
| 로그인 | **이메일 매직링크 · 계정 1개 · 익명 승계** | `D-004` 닫는다 |
| AI provider | **연다. 단 adapter는 마지막 단계** (§3) | **`D-034` 신설** |
| 게시물 | **S3 격리 업로드 + 오너 수동 승인** | `D-008` 닫는다 |
| 알림 정책 | BE가 제안하고 오너가 승인 | `D-026`, §4 BA-085 |

**카카오에 대해 조율자가 ADR 첫 줄에서 확인할 것**: 지도·장소(카카오맵)와 길찾기·이동시간(카카오모빌리티)은
서비스·키·약관이 다를 수 있다. 저장소에는 두 제품 중 어느 것이 route matrix를 주는지 적힌 곳이 없다.
**확인 전에는 어느 쪽도 가격·쿼터를 단정하지 않는다**(`D-002` 완료 증거가 요구하는 것이 그 비교다).

## 2. AI 일정 최적화 — 실측 상태

**이미 구현돼 있고 제출 빌드에서 켜져 있다. LLM은 여기에 관여하지 않는다.**

- 계산: `apps/ai`의 결정적 pipeline — `item/evaluator.py`·`item/score.py`·`item/filters.py`,
  `feed/pipeline.py`, `related/ranker.py`, `draft/composer.py`, `explain/templates.py`.
  DB·외부 API·clock·난수 없이 immutable 입력만 계산한다(ADR-0006).
- 배선: `HttpRecommendationGateway` → `OptimizeItemHandler` → `ProposalRevalidator` → preview 저장 →
  APPLY/KEEP/REVERT.
- 카드: BA-050~055가 전부 `integration-ready`.
- 제출 빌드: `infra/src/staging.ts`가 staging API task에만 `FEATURE_OPTIMIZATION_ITEM: "true"`를 고정하고
  `infra/test/staging.test.ts`가 그것을 단언한다. 오너 2026-09-19 결정이 `docs/operations/ENVIRONMENT.md`에 기록돼 있다.

**미검증 지점은 정확히 하나다**: staging 외부 익명창에서 preview → APPLY/KEEP/REVERT가 실제로 도는지.
그것이 `BA-073-T1`이고 [#305](https://github.com/yutakdv/Nullnull/issues/305)이며, 로컬 test는 이것을 대신하지 못한다.
**그래서 제출 스트림이 AI 최적화의 유일한 증명 경로다** — P1 어느 카드도 이것을 대신 증명하지 않는다.

## 3. OpenAI를 쓸 것인가 — 판단

**연다. 단 `AI_PROVIDER=OPENAI` adapter는 마지막 wave에 둔다.** BA-084를 두 단계로 쪼갠다.

근거는 넷이다.

1. **최적화는 LLM이 하지 않는다**(불변식 9). OpenAI가 더하는 것은 선호 해석과 설명 문장뿐이다.
   설명은 template가 정본이고 LLM 실패 시 template로 돌아가므로, **성공해도 화면이 크게 달라지지 않고 실패해도 같다.**
2. **BA-084의 test 3개가 전부 거부·fallback·mutation 0을 잰다.** 구현량의 대부분이 LLM을 신뢰하지 않게 만드는 장치이고,
   그 장치는 provider가 무엇이든 필요하다.
3. **제출 심사에서 LLM 가점 근거가 저장소에 없다.** 데이터 활용 배점의 정본은 KTO 실호출이다(`CMP-KTO-003`).
4. 외부 전송이 생기면 불변식 10과 개인정보 경계를 다시 증명해야 한다. 그 증명은 adapter보다 오래 걸린다.

- **BA-084①(provider 무관, 먼저)**: bounded preference schema, 출력 validator(임의 ID·숫자·영업 주장 거부),
  hallucination/prompt injection corpus, KO/EN 검증, kill switch, template fallback 경로.
  `AI_PROVIDER=NONE`으로 전부 동작하고 카드의 안전 절이 전부 증명된다.
- **BA-084②(마지막)**: `settings.py`의 `Literal` 확장, OpenAI adapter, `AI_MODEL_ID`·timeout·예산.
  **키와 월 예산이 오너에게서 오면 그때 시작한다.** 그 전에는 `OPENAI` 값이 startup 실패로 남는다(silent fallback 금지).

②를 앞당기고 싶으면 필요한 것은 키와 월 상한 두 값뿐이다.

## 4. 카드별 실행 계획

### 선행 P0 (강행이어도 먼저 끝나야 하는 것)

- **제출 스트림**: [#305](https://github.com/yutakdv/Nullnull/issues/305) — `BA-071-T4` rollback 연습 → 마지막 배포 →
  edge open → 외부 익명창(`BA-073-T1`) → KTO 실호출(`BA-021-T3`) → 호출 목록(`BA-073-T3`) → release gate.
  **순서가 고정이고 중간에 재배포하면 4~6을 다시 한다.**
- **Live 스트림**: BA-090 → BA-091 → BA-092. BA-093의 선행이다.
- BA-006·BA-072 잔여는 제출 스트림에 붙는다.

### P1 8장

| 카드 | 선행 | migration | 계약 | 비고 |
| --- | --- | --- | --- | --- |
| BA-085 알림 | 없음(계약 있음) | `V037` | **pattern 정정만** | deepLink가 FE 라우트와 어긋나 있다(§6) |
| BA-086 영문 coverage | 없음 | `V040` | 노출 필드 생기면 | FE 의존 가장 적음 |
| BA-080 검색·filter | BA-085 뒤 | `V042` | 신규 | `/search` 디자인 없음 |
| BA-083 경로·DAY/TRIP | ADR 먼저 | `V039` | 신규 | 카카오 ADR → gateway → matrix |
| BA-084 AI(①) | 없음 | 없음 | 신규 | ②는 마지막 |
| BA-081 auth·승계 | 없음 | `V038` | 신규 | L4 — 불변식 11 재검증 |
| BA-082 게시물 | BA-081 | `V041` | 신규 | S3 격리 + 수동 승인 |
| BA-093 위치·주변 | BA-091·092 | `V043` | 신규 | 제출 profile에서는 OFF 유지 |

**Flyway 번호는 조율자가 위 표로 미리 배정한다.** 전역 합계라 작업자가 각자 고르면 충돌한다.
그리고 새 migration을 넣는 PR은 `FlywayMigrationIT.populateEveryTable`에 **직전** migration의 table을 채운다.

## 5. 세션 토폴로지

**조율자 1 + 작업자 동시 3.** 3을 넘기지 않는 이유는 취향이 아니라 측정된 제약이다 —
통합 게이트의 compose project 이름이 고정이라 전체 게이트는 **한 번에 하나만** 돌고,
`.git` index와 push가 공유라 동시 커밋이 서로의 파일을 싣는다(AGENTS 등록 규칙 6).

| 세션 | 모델 | effort | 소유 | 근거 |
| --- | --- | --- | --- | --- |
| 조율자 | Opus 5 | `xhigh` | 계약 정본·migration 번호·게이트 슬롯·PR·FE 창구 | 판단이 결과를 바꾸지만 상시 돌아야 한다. Fable은 주간 쿼터가 있어 상시 세션에 두지 않는다 |
| W-DEPLOY | Codex `gpt-5.6-sol` | high | #305 제출 스트림, BA-072 잔여 | 배포 운영은 오너 2026-09-19 결정으로 Codex. Claude로 돌리면 Opus 5 / `high` |
| W-NOTIF | Opus 5 | `high` | BA-085 → BA-080 | 일반 구현. migration 설계 구간만 `xhigh` |
| W-AUTH | Opus 5 | **`max`** | BA-081 | L4 — 인증·session·소유권 불변식 11·승계 원자성 |
| W-ROUTE | Opus 5 | `xhigh` | BA-083 (ADR → gateway → matrix) | 외부 provider 계약 + migration + optimizer 정책 |
| W-AI | Opus 5 | `high` | BA-084① → BA-086 | `apps/ai`는 격리된 앱이라 충돌이 가장 적다 |
| W-LIVE | Opus 5 | `xhigh` | BA-090 → BA-091 → BA-092 | multi-service, 외부 source |
| W-UPLOAD | Opus 5 | `high` | BA-082 | 정책이 결정돼 남은 것은 구현. 보안 검토만 따로 |

보조(세션이 아니라 subagent로 부른다):

- 탐색·로그 요약: `Explore` / sonnet / `medium`
- test·lint·build 실행: `test-runner` / haiku / `low`
- 계약 동결 직전·auth diff 적대적 검토: codex-companion (Fable 쿼터를 상시로 쓰지 않는다)

### Wave 배정 (동시 3)

1. **Wave 1** — W-DEPLOY(#305) · W-NOTIF(BA-085) · W-ROUTE(ADR 조사만, 코드 충돌 0)
2. **Wave 2** — W-AUTH(BA-081) · W-AI(BA-084①) · W-LIVE(BA-090~091)
3. **Wave 3** — W-ROUTE(BA-083 구현) · W-NOTIF(BA-080) · W-AI(BA-086)
4. **Wave 4** — W-UPLOAD(BA-082) · W-LIVE(BA-092) · BA-093
5. **Wave 5** — BA-084②(키·예산이 온 경우에만)

## 6. deepLink 불일치 — Wave 1에서 먼저 닫는다

BA-085를 계약 그대로 구현하면 **FE가 라우팅할 수 없는 링크를 발급한다.**

- 계약의 `Notification.deepLink` pattern은 `/trips/<uuid>`(복수)를 요구한다.
- FE 실제 라우트는 `trip/:tripId`(단수)이고, `docs/contracts/events.schema.json`의 `routeTemplate`
  allowlist도 `/trip/:tripId`다.
- `/notifications` 라우트가 FE에 없고, `/live/areas/{id}`·`/live/places/{id}`도 없다(`live` 한 화면뿐).

`PM-016`이 이미 이 불일치를 **차단**으로 적어뒀다. 조율자가 FE 판정을 받아 pattern을 고치고,
그 전에는 알림 생산자를 켜지 않는다.

## 7. 공유 checkout 안전 규칙 (작업자 필독)

1. **격리 worktree에서만 `apps/api` 검증을 한다.** worktree는 `HEAD` + 자기 파일만으로 만든다.
   공유 트리를 `rsync`·`cp`로 동기화하지 않는다.
2. **커밋은 `git commit -m … -- <경로>` 한 명령으로 한다.** `git add`와 `git commit` 사이에 확인을 끼우면
   그 창에 남의 파일이 실린다. 확인은 커밋 **뒤에** `git show --stat`으로 한다.
3. **push 전에 `git log --oneline origin/backend..HEAD`로 밀 커밋 목록을 본다.** 남의 것이 섞였으면 멈추고 알린다.
4. **전체 게이트는 조율자가 슬롯을 배분한다.** compose project 이름이 고정이라 동시에 둘이 돌 수 없다.
5. **Flyway 번호는 §4 표에서만 가져온다.**
6. **계약 파일(`docs/api/openapi.yaml`·`docs/contracts/`)은 조율자만 편집한다.** 작업자는 제안을 이슈에 올린다.
7. 기존 미커밋·untracked 파일은 건드리지 않는다. 오너가 보류한 작업이 디스크에만 있는 경로가 있다.

## 8. FE 사전 요청

[#310](https://github.com/yutakdv/Nullnull/issues/310)으로 올렸다. BE가 단독으로 못 푸는 것만 담았다 —
FE-P1 카드 6장 생성, 계약 승인 7건, 디자인 2건(`/search`·`/posts/new`), deepLink 판정, 오너 결정 4건 통보,
`D-030` FCR 반영 확인.

**1번(카드 생성)이 가장 먼저다.** 그것이 없으면 FE 쪽 승격 경로가 아예 없다.

## 9. 완료 판정

카드마다 `backend-plan.json`의 `tests[].id`가 JUnit testcase 이름에 나타나야 `integration-ready`이고,
`verified`는 acceptance ID마다 그것을 증명하는 testcase를 `evidence.provenBy`로 지목해야 한다.
**이름만 옮겨 적지 않는다** — 절을 testcase 본문과 대조하고, 대조할 것이 없으면 칸을 비워 승격을 막는다.

## 10. 조율 상태 (2026-09-20, 조율자 세션 `nullnull-a0` 기록)

이 절은 계획이 아니라 **실행 중 상태**다. 위 §1~§9와 어긋나는 곳은 이 절이 최신이다.

### 바뀐 전제 넷

1. **Live가 제출 전으로 당겨졌다**(`A-054`). `A-033`의 목업 허용과 #64의 2026-09-19 코멘트를 대체한다.
2. **서울 API는 HTTP 전용이고 인증키가 URL 경로에 들어간다.** `ProviderHttpClient.validateTarget`이 HTTPS만
   통과시키므로 직접 호출이 불가능하다. 오너가 **HTTPS proxy**를 골랐고, proxy가 키를 주입해 `apps/api`가
   서울 키를 한 번도 갖지 않는 형태다. 배포는 #305 순서 2(마지막 배포)에 **포함시킨다** — 따로 배포하면
   증거를 다시 뽑아야 한다.
3. **로그인 화면은 껍데기다**(`A-056`). `A-039`의 *미리 채워진 자격 증명*·*`createDemoSession`* 두 절은
   구현된 적이 없고 내렸다. 귀결로 **#305 step 3의 전제가 현재 배포본으로 이미 충족된다.**
4. **#305는 조율자가 몰 수 없다.** `deploy`·`rollback`의 **plan 단계**가 `NULLNULL_VERIFIER_TOKEN`을 요구하고
   `execute`는 요구하지 않는다(plan.json의 `verifierTokenSha256`만 읽는다). 분업이 §5의 가정과 반대다.
   그리고 이 기계에 남은 plan 디렉터리는 `bootstrap` 하나라 `rollback --previous-plan`이
   `cannot-rollback-bootstrap`으로 거절된다. **오너가 셀을 직접 돌린다.**

### 배포·증거 상태 (조사 결과, 2026-09-20)

| release | 시각 | 가진 증거 |
| --- | --- | --- |
| rc.1001 | 05:29Z 로컬 overlay | actual-call |
| rc.6 | 13:24Z | actual-call `verified` |
| rc.9 | 19:14Z | secret-scan `clean-partial`, kto-inventory(`no-usable-call`) |
| **rc.10** | **21:33Z, 배포된 것으로 보임(run #10, main `c083f1e`)** | **없음** |

검사기가 다른 release의 증거를 거절하므로 **#305의 4~6은 어느 경우에도 제출 release에서 새로 뽑는다.**
`check_submission_inventory.py --ledger`가 읽을 ledger JSON은 **저장소에 없다** — 조율자가 초안을 만들고
오너가 승인한다.

### 세션 배정 (측정된 effort 기준)

| 세션 | model · effort | 스트림 | worktree · branch |
| --- | --- | --- | --- |
| `nullnull-a0` | Opus 5 · `xhigh` | 조율자 — 계약 정본·migration 번호·게이트 슬롯·PR·FE 창구 | 공유 checkout, `backend` |
| `nullnull-34` | Opus 5 · `xhigh` | W-LIVE (`BA-090`→`BA-091`). BA-083은 `A-055`로 멈춤 | `wt-route` · `work/live` |
| `nullnull-cf` | Opus 5 · `high` | W-NOTIF (`BA-085`→`BA-080`) | `wt-notif` · `work/notif` |
| `nullnull-41` | Opus 5 · `high` | W-AI (`BA-084①` 완료 → `BA-086`) | `wt-ai` · `work/ai` |

**worktree가 자기 index를 가지므로 규칙 6의 index 충돌이 구조적으로 사라진다.** 작업자는 자기 worktree에서
커밋하고 push는 하지 않는다. 통합은 조율자가 한다.

### Flyway 전역 배정

`V037` 알림(cf, 작성됨) · `V038` auth 예약 · `V039` route 예약(BA-083 멈춤) · `V040` 영문(41) ·
`V041` 게시물 예약 · `V042` 검색 예약 · `V043` 위치 예약 · `V044` BA-090(34) · `V045` BA-091(34) ·
`V046` BA-092 예약.

**`populateEveryTable` 의무는 `V030`까지로 완결돼 있다** — `V031`~`V036`은 `CREATE TABLE`이 0건이다(실측).
새 migration을 넣을 때 그 **직전** migration의 DDL을 직접 읽고 판단한다.

### 승격 규칙

`BA-084`·`BA-085`·`BA-080`·`BA-090`이 전부 `BA-073`(제출 스트림, `planned`)에 의존한다. **구현·test는 하되
`integration-ready` 승격은 제출 스트림 뒤에 조율자가 한번에 올린다.** 증거는 카드에 쌓아만 둔다.

### 활성 oasdiff 예외 — 머지 직후 지워야 한다

`deepLink` pattern 정정 한 줄이 이 저장소의 **첫 활성 예외**다. 계약 PR이 `main`에 들어가는 **직후 후속
커밋으로** `docs/api/oasdiff-ignore.txt`와 등록부 행을 함께 비운다. 같은 PR에서는 못 비운다(그 시점엔 finding이
실재한다). 미루면 **무관한 다음 PR이 빨간불을 받는다** — 그 문서가 같은 일이 두 번 났다고 기록한다.
**지우는 사람은 그 PR의 주인, 즉 조율자다.**

### 조율자가 오늘 틀린 것 셋 (작업자가 잡았다)

- **넷째 capability를 만들라고 승인했다.** `DemoCapabilities` javadoc이 *"the set is exactly those three and
  nothing invented beyond them"* 이고 `DemoCapabilityQueryTest`가 `containsExactly`로 박아 둔다. 가드의 빨강을
  *"등록해라"* 한 방향으로만 읽었다.
- **open-redirect를 통과시키는 CHECK 정규식을 승인했다.** `^/[A-Za-z0-9/_-]*$`가 `//localhost/x`를 통과한다.
  `//evil.example`이 막힌 것은 점이 문자 class 밖이라서였지 의도가 아니었다.
- **HTTPS proxy를 "재배포 비용"으로 배제했다.** 그 비용은 #305의 4~6이 이미 돌았을 때만 발생하고 아직 안 돌았다.

셋 다 **인용된 쪽이 다시 확인해서** 잡혔다. 그 규칙이 오늘 세 번 값을 했다.
