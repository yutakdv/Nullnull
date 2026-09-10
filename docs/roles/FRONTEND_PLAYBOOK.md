---
aliases:
  - "Frontend 실행 플레이북"
doc_type: reference
status: baseline
area: roles
tags:
  - nullnull/reference
  - nullnull/roles
---

# Frontend 실행 플레이북

- 상태: `frontend` 역할 브랜치 실행 계약
- 담당: Frontend 1명(`FE_DRI`)
- 상대 담당: Backend/AI 1명(`BE_AI_DRI`)
- 기준일: 2026-09-05
- 대상: 모바일 웹앱/PWA, P0 한국어·English

이 문서는 Frontend 담당자가 Figma의 현재 52개 구현 frame을 누락 없이 제품 기능으로
옮기기 위한 작업 순서다. 단, [Figma 수정 요청](../design/FIGMA_CHANGE_REQUESTS.md)의
FCR-001~015가 닫히기 전 영향 화면을 구현 승인 상태로 보지 않으며, 빠진 P0 ITEM
preview는 수정 후 inventory에 추가한다. 시각·문구·상태는
[Figma 핸드오프](../design/FIGMA_HANDOFF.md), component는
[Component Catalog](../design/COMPONENT_CATALOG.md), 기능 의미는
[제품 요구사항](../product/PRODUCT_SPEC.md), HTTP shape은 [OpenAPI](../api/openapi.yaml)를
따른다. 공모전 release는 [준수 매트릭스](../contest/COMPETITION_COMPLIANCE_MATRIX.md)를
추가 적용한다. 충돌을 발견하면 화면에서 임시로 추론하지 않고 같은 기능 ID의 contract
issue를 먼저 연다.

처음 합류하거나 Claude Code를 시작할 때는 먼저
[Frontend 담당자 · Claude Code 시작 안내서](./FRONTEND_CLAUDE_CODE_START.md)를 읽는다.
현재 branch/PR 상태, 필독 순서, M0의 최소 범위와 Claude Code에 전달할 prompt를 한 곳에
정리했다.

새 구현의 정본 경로는 `apps/web/`이다. 과거 frontend prototype은 목표 저장소에 포함하지 않으며 B01 scaffold 전 문서·mock 기준선을 제품 구현 완료로 간주하지 않는다.

## 1. 역할 경계

### Frontend가 끝까지 소유하는 것

- `apps/web/**`: route, layout, feature UI, client state, error boundary, PWA.
- 현재 Figma 52개 frame, `FCR-004`로 추가할 ITEM preview와 49개 최상위
  component의 화면·variant·Storybook 추적성.
- 한국어·English UI, locale별 날짜·시간·숫자 형식, 긴 문자열과 360px 대응.
- generated TypeScript client의 소비, credential/CSRF wrapper, query cache와 mutation 상태.
- OpenAPI example에서 생성·가져온 MSW fixture. production 전용 임의 response type은 만들지 않는다.
- keyboard, focus, screen reader 이름, touch target, reduced motion, map의 list 대안.
- 브라우저 계측 emission과 사용자에게 보이는 오류·출처·freshness·capability 표현.
- component/unit/visual/Playwright/a11y test 및 web artifact·screenshot 증거.

### Frontend가 소유하지 않는 것

- OpenAPI 의미, 서버 validation, owner 권한, transaction과 idempotency 구현.
- PostgreSQL/Flyway, 수집 adapter, 데이터 비교 적격성, optimizer/LLM의 사실 판단.
- AWS 계정·network·ECS/RDS/IAM 운영과 외부 API key 보관.

소유하지 않는다는 것은 검토하지 않는다는 뜻이 아니다. FE-facing schema, 오류, 데이터 문구, web runtime 설정과 사용자 journey는 FE 승인이 있어야 계약을 동결할 수 있다.

## 2. 화면과 API 인계의 정본

frame의 UI/server 책임은 [소유권 매트릭스](../engineering/OWNERSHIP_MATRIX.md), 전체 operation은 [OpenAPI](../api/openapi.yaml), 상태별 동작은 [Figma 핸드오프](../design/FIGMA_HANDOFF.md), frame과 `FE-*` 실행 ID의 매핑은 [공통 실행 순서](../engineering/IMPLEMENTATION_PLAN.md)에 모았다. 이 문서에 같은 표를 복제하지 않는다.

작업 목록의 기계 판독 정본은 [frontend-plan.json](../engineering/frontend-plan.json)이고 GitHub issue는 그 투영이다. 상태는 JSON이 정본이므로 구현 PR에서 `status`를 올리고 issue를 닫는다. `scripts/validate_frontend_plan.py`가 phase·기능 ID·operation·Figma node·선행 관계와 순환을 검사한다. Backend와 달리 operation 독점 소유와 전체 기능 coverage는 검사하지 않는다 — FE는 operation을 소비하므로 한 operation이 여러 화면에 나타나고, 서버·운영 전용 요구사항에는 FE task가 없다.

### BE 미구현 endpoint를 목데이터로 선행할 때

일정상 계약만 있고 구현이 없는 endpoint를 FE가 먼저 만들 수 있다. 그때 **선행했다는 사실을
해당 Backend/AI issue에 코멘트로 남긴다.** 목데이터가 코드 안에만 있으면 나중에 누가 무엇을
교체해야 하는지 저장소를 뒤져야 하고, 실제 응답과 다른 채로 병합될 위험이 PR #17에서 이미
한 번 현실이 됐다(FE fixture 4종이 ajv를 통과하고도 서버와 달랐다).

코멘트에 담을 것:

- 어떤 FE 작업이 무엇을 가정하고 선행했는지
- **실제 fixture 내용**(JSON 그대로). 값을 지어낸 필드는 그렇다고 밝힌다
- FE가 가정한 서버 동작 중 **다르면 화면을 고쳐야 하는 것**
- 교체 절차: fixture → `manifest.json`의 `serverVerified` → msw handler 삭제 → PROVISIONAL 주석 삭제

코드 쪽에도 같은 내용을 남긴다 — `packages/contracts/src/index.ts`의 export 위 주석,
`fixtures/manifest.json`의 `serverVerified: false`, 그리고 해당 msw handler에 삭제 조건.
화면은 생성 client로 실제 호출하므로 endpoint가 열리면 fixture와 handler만 지우면 된다.

계약에 필드가 아예 없어 화면을 못 만드는 경우는 목데이터로 메우지 않는다. 없는 값을 만들면
불변식 8(provenance 없는 수치 비교 금지)에 걸리므로 `FCR-*`로 올리고 그 부분만 비운다
(`FCR-029` 참조).

## 3. Client 공통 책임

쿠키/CSRF/request ID/If-Match/Idempotency-Key를 공통 wrapper에서 처리하고 생성 타입을 수동 복제하지 않는다. 401은 안전한 GET만 1회 복구하며 mutation의 재시도는 동일 body/key에 대한 명시적 사용자 행동으로 제한한다. 검색·viewport는 민감 body이고 query cache persistence에서 제외한다.

## 4. 개발 순서

[공통 실행 순서](../engineering/IMPLEMENTATION_PLAN.md)의 B00~B10을 따른다. 세션→공통 장소/데이터→여행/feed/후보→편집→최적화→import→검수→선정 확장→Live 순서다. Live 화면·서울 연동은 마지막이며 데이터 안내·검색·relation fixture는 먼저 제공받는다. `FE-*` 실행 ID와 담당 frame은 [공통 실행 순서](../engineering/IMPLEMENTATION_PLAN.md)의 Frontend 실행 ID 표에 정의돼 있다. ID 번호는 화면을 찾는 참조이고 실행 순서는 B번호가 정한다.

## 5. `frontend` 브랜치와 작업 절차

`frontend`는 FE 전용 장기 역할 브랜치이며 `main`에 직접 push하지 않는다. 장기 브랜치를 재사용하므로 PR merge 방식은 **merge commit**으로 고정한다. squash/rebase merge를 쓰면 source branch와 `main` 이력이 갈라져 force push가 필요해지므로 금지한다. 모든 PR에는 상대 담당자 1인 승인, 최신 `main`, `docs-contract`, `docker-integration`이 필요하다.

### 작업 시작

1. `main`의 마지막 green SHA와 `backend → main` 계약 PR을 확인한다.
2. 열려 있는 FE PR이 없으면 `frontend`를 `origin/main`으로 fast-forward한다.
3. 열려 있는 FE PR이 있으면 `origin/main`을 일반 merge로 받아 conflict를 해결하고 gate를 다시 실행한다.
4. issue에 기능 ID, Figma node, operationId, contract SHA, 이번에 닫을 acceptance를 적는다.
5. OpenAPI example 기반 fixture와 failing test를 먼저 연결한다.

### 구현과 종료

1. route/component/state를 작은 commit으로 구현한다.
2. generated client가 없는 shape는 hand-written type으로 우회하지 않고 contract blocker로 전환한다.
3. web gate와 영향 E2E를 실행하고 실제 결과를 기록한다.
4. `frontend → main` PR 하나만 열고 BE/AI 담당자를 reviewer로 지정한다.
5. PR의 `docker-integration`이 candidate merge ref를 실제 API·DB와 함께 검증해야 한다.
6. merge 후 `frontend`를 `main`으로 fast-forward하고 merged branch를 삭제하지 않는다.

두 역할 브랜치 사이의 PR이나 cherry-pick은 금지한다. 교차 기능은 `backend`의 contract-only PR → `main` merge → 두 역할 branch의 `main` 동기화 → backward-compatible Backend PR → Frontend PR 순서로 합친다. capability가 양쪽에서 준비되기 전에는 켜지 않아 반쪽 구현을 노출하지 않는다. Frontend PR 뒤 server-owned capability를 켤 때는 Backend/AI의 별도 flag/config PR, FE 승인, 두 required status와 staging acceptance가 필요하다.

## 6. Definition of Ready

- 기능 ID, P0/P1, Figma node와 route/overlay가 연결돼 있다.
- 사용자 action 전후의 domain 변화 또는 무변경 불변식이 적혀 있다.
- 사용할 operationId, auth/CSRF, ETag/idempotency, success/error example이 있다.
- default/loading/empty/error/offline과 필요한 stale/replay/conflict fixture ID가 있다.
- component 49종 중 사용할 export와 variant가 정해졌다.
- focus 시작·복귀, keyboard 행동, accessible name, 360px/KO/EN acceptance가 있다.
- 개인정보, 위치, raw text, provenance, analytics allowlist 영향이 검토됐다.
- 미지원 P1 또는 외부 source는 capability OFF/fallback이 정의됐다.

하나라도 없으면 UI shape를 고정하지 말고 contract issue에 누락을 기록한다.

## 7. Definition of Done

- Figma node → 기능 ID → operationId → fixture → component/E2E test가 screen manifest에 연결됐다.
- API는 generated client만 사용하고 재생성 뒤 manual diff가 없다.
- default/loading/empty/error/offline 및 해당 stale/replay/conflict 상태가 구현됐다.
- mutation 중복 실행이 막히고 성공·실패 결과가 toast 외 persistent UI에도 남는다.
- 360px·768px·1280px, KO/EN, 200% zoom, keyboard, focus, reduced motion을 검증했다.
- SavedPost/Candidate/TripItem, four constraints, LIVE/FORECAST/REPLAY/QUALITATIVE/STALE/UNAVAILABLE를 혼용하지 않는다.
- unit/component/visual/E2E와 `docker-integration`이 통과했다.
- BE/AI 담당자가 실제 API로 acceptance를 재현하고 승인했다.
- 사용자 문구·화면과 공모전 기능설명서에 남길 증거가 실제 구현과 일치한다.
- 실행하지 못한 검증은 통과로 표시하지 않고 PR에 `not run`과 이유를 남겼다.

## 8. 검증 명령과 Docker 통합 기대값

B01 이후 Frontend 변경의 로컬 gate는 다음이다.

```bash
cd apps/web
npm run lint
npm run format:check
npm run typecheck
npm run test
npm run build
```

문서나 계약도 바꿨다면 repository root에서 다음을 실행한다.

```bash
python3 scripts/validate_docs.py
npx --yes markdownlint-cli2@0.23.2
npx --yes @redocly/cli@2.51.1 lint docs/api/openapi.yaml
npx --yes --package ajv-cli@5.0.0 --package ajv-formats@3.0.1 \
  ajv validate --spec=draft2020 -c ajv-formats \
  -s docs/contracts/events.schema.json -d docs/contracts/events.example.json
```

`frontend → main` PR의 required `docker-integration`은 최소 다음을 보장한다.

- `verify_target_stack.py`가 marker, lock/wrapper, task/stage와 image digest를
  검사하고 정규화 Compose의 필수 service/internal network를 확인한다.
- PR merge ref 기준 web image와 현재 main-compatible API image를 함께 build한다.
- 깨끗한 PostgreSQL volume에 Flyway를 적용하고 deterministic `base`, `edge`, 영향 seed를 넣는다.
- 외부 key 없이 KTO synthetic contract fixture/replay adapter를 사용한다.
- API readiness 후 web을 열어 영향 P0 Playwright mobile journey, axe, cookie/CSRF, ETag를 검증한다.
- OpenAPI client 재생성 diff가 0인지 확인한다.
- offline security/infra component gate와 실제 outbound-deny probe를 실행한다.
- 실패 시 container log·screenshot·trace를 secret 없이 artifact로 남기고 non-zero로 끝낸다.

모든 main PR의 단일 entrypoint는 저장소 root에서 다음과 같다.

```bash
bash scripts/integration-test.sh
```

현재 `compose.integration.yml`과 wrapper는 존재하지만 target `apps/web`, `apps/api`가 생기기 전에는 `baseline-only`다. 이 결과를 target 앱 통합 증거로 표시할 수 없다.

B01 marker인 repository root의 `.nullnull-target-stack`이 생기기 전 CI는 문서-only baseline validation을 수행하고 `docker-integration`을 명시적인 `baseline-only` 결과로만 끝낼 수 있다. 이는 required status 이름을 미리 고정하기 위한 상태이지 제품 통합 통과가 아니다. marker 없이 `apps/web` 또는 `apps/api`가 먼저 생기면 불완전한 전환이므로 hard fail한다. 내용이 `version=1`인 marker가 생성된 뒤에는 앱, task/stage, lock/wrapper, immutable digest, component service 또는 internal network 중 하나라도 없으면 `baseline-only`로 우회하지 않고 hard fail한다. ruleset required status는 정확히 `docs-contract`, `docker-integration` 두 개이며 component gate는 후자 내부에서 모두 실행한다.

## 9. Backend/AI에 전달할 handoff packet

| 항목 | FE가 제공할 내용 |
| --- | --- |
| Identity | 기능 ID, Figma node, route/overlay, milestone/ticket, PR SHA |
| User transition | action 전/후 화면·domain 기대값, 실패 시 반드시 유지할 상태 |
| Request behavior | 호출 시점, debounce, polling/backoff, cancel, retry, idempotency key 수명 |
| State coverage | example/fixture ID와 default/loading/empty/error/offline/stale/replay/conflict |
| Error UX | Problem code별 문구, CTA, field focus, retry 가능 여부 |
| Data UI | source/state/observedAt/targetAt/freshness/confidence/license/비교 가능성 배치 |
| Accessibility | focus diagram, keyboard 표, live-region 정책, map list 대안 |
| Analytics | allowlisted event 이름·trigger·필드; 자유문·좌표·raw query 없음 |
| Evidence | Storybook URL, mobile screenshots, test/trace, known limitation |

BE/AI가 제공해야 하는 contract SHA·canonical examples·state machine·migration/readiness가 빠졌다면 `integration-ready`로 표시하지 않는다.

## 10. 금지하는 가정

- 후보 저장이 일정 추가라고 가정하지 않는다. `addTripCandidate`로 TripItem/version을 바꾸지 않는다.
- 202 삭제 receipt를 삭제 완료로 표시하지 않는다. `getDeletionRequest` terminal 상태를 확인한다.
- login, JA/ZH, DAY/TRIP 최적화, 위치 재계획, 알림을 capability 없이 호출하지 않는다.
- `fetchedAt`을 관측시각으로, `UNAVAILABLE`을 0으로, 비교 불가 delta를 0%로 표시하지 않는다.
- 서로 다른 source/scope/set의 값을 client에서 정규화·순위화하지 않는다.
- 서버 entity를 전역 수동 store에 복제하거나 localStorage owner를 server owner로 간주하지 않는다.
- mutation을 낙관적으로 최종 확정하거나 stale conflict를 last-write-wins로 덮지 않는다.
- 붙여넣기 원문, 정밀 위치, cookie/token, 자유 검색어를 persistence·analytics·오류 보고에 넣지 않는다.
- LLM 문장을 장소 존재·영업·경로·혼잡 사실의 근거로 표시하지 않는다.
- 중요 성공/실패를 toast·색·map marker만으로 전달하지 않는다.
- OpenAPI generated type 오류를 `any`나 별도 interface로 우회하지 않는다.

## 11. 공모전 심사 증거 책임

Frontend는 심사위원이 외부망·익명창·로그인 없이 핵심 흐름을 완주할 수 있다는 **실제 화면 증거**를 담당한다.

- `일정 입력·확인 → Feed → +Trip 후보 저장 → My Trip 일정화/편집 → ITEM 최적화 preview/apply/revert → Live/replay` 녹화와 E2E run을 같은 release SHA에 연결한다.
- 대표 이미지 1장과 실제 P0 상세 화면 3~5장을 staging이 아닌 제출 production release에서 캡처한다.
- 출처를 `출처: ⓒ한국관광공사` 등 승인된 텍스트로 표시하고 공식 CI/BI logo를 허가 없이 사용하지 않는다.
- source state, 관측/발표·대상 시각, freshness, 비교 불가 이유가 screenshot에서도 판독 가능하게 한다.
- login CTA, JA/ZH, P1 화면을 구현된 기능처럼 보이게 하지 않고 `준비 중`/OFF를 명확히 표시한다.
- loading/error/empty/대안 없음에서도 dead end가 없는 외부망 smoke 결과를 보존한다.
- 기능설명서에 적는 route·문구·스크린샷은 최종 배포본과 일치시킨다. prototype/Figma만으로 구현 증거를 대체하지 않는다.
- secret, API key, 개인 데이터, 내부 URL이 screenshot·trace·bundle·PDF에 없는지 제출 전 검사한다.
- 제출 profile에서는 `FEATURE_NEARBY_LOCATION=OFF`를 확인하고 geolocation API·permission prompt·좌표 request가 0건임을 Playwright/network로 증명한다.

## 12. 공동 handshake

| Gate | FE_DRI 책임 | BE_AI_DRI 책임 | 둘이 남길 증거 | 통과 조건 |
| --- | --- | --- | --- | --- |
| `ticket-ready` | Figma state·접근성·사용자 transition | domain invariant·데이터/보안 영향 | 기능 ID가 있는 issue | 모호한 CTA/state/owner 없음 |
| `contract-ready` | 필요한 field/state/error와 UI acceptance 승인 | OpenAPI/event/example/state transition 작성 | contract SHA, schema-valid fixture | 생성 client와 provider test가 같은 example 사용 |
| `implementation-ready` | MSW·failing component/E2E test | DB/domain·failing contract/integration test | 양쪽 test ID | 상대 구현을 기다리지 않고 병렬 착수 가능 |
| `integration-ready` | generated client·UI 전체 상태 | 실제 API·seed·readiness·migration | handoff packet, image digest | mock 전용 field 0, contract SHA 일치 |
| `PR-ready` | `frontend → main` PR와 web evidence | server truth·문구 review | PR checklist | 상대 승인, unresolved conversation 0 |
| `merge-ready` | candidate merge ref 사용자 journey | Docker/API/DB/source fixture 검증 | `docker-integration` report | 모든 required check green |
| `release-ready` | production web artifact·외부망 UX | API/data/AWS readiness·rollback | immutable release manifest | 둘 다 go, 실제 공모전 기능 증거 확보 |

상세 소유권은 [Ownership Matrix](../engineering/OWNERSHIP_MATRIX.md), 개발 방식은 [Workflow](../engineering/BRANCH_AND_INTEGRATION.md), 테스트 범위는 [Test Strategy](../engineering/TEST_STRATEGY.md)를 따른다.
