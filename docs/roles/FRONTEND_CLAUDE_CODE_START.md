# Frontend 담당자 · Claude Code 시작 안내서

- 대상: Nullnull Frontend 담당자(`FE_DRI`)와 해당 담당자의 Claude Code 세션
- 기준일: 2026-09-05
- 현재 상태: 문서·계약 기준선은 `main`에 병합됐으며, `apps/web/`는 아직 없다
- 이 문서의 목적: 처음 합류한 담당자가 문서 전체를 탐색하지 않고도 안전하게 첫 Frontend slice를 시작하게 한다

## Claude Code에 전달할 첫 요청

새 Claude Code 세션의 repository root에서 다음 문장을 그대로 전달한다.

```text
`CLAUDE.md`와 `docs/roles/FRONTEND_CLAUDE_CODE_START.md`를 먼저 읽어줘.
Nullnull Frontend 담당자로서 현재 Git 상태와 활성 branch를 확인하고,
현재 준비도, 열린 Figma 차단 항목, 지금 안전하게 할 수 있는 가장 작은 다음
Frontend 작업을 보고해줘. 수동 API type/client를 만들거나 P1 capability를
활성화하거나 관계없는 파일을 수정하지 마.
```

특정 기능을 착수할 때는 기준선이 main에 병합되고 `frontend` branch가 준비된 뒤 다음처럼
요청한다.

```text
/nullnull-slice FR-ONB-02
```

또는 feature ID 대신 사용자의 결과를 적는다.

```text
/nullnull-slice 익명 사용자가 한국어와 English를 실제로 전환하고 새로고침 뒤 복구한다
```

`/nullnull-slice`는 현재 branch, Figma node, operationId, 불변식, 테스트와 handoff를
확인하도록 만든 project skill이다. 사용자 요청 없이 commit, push, PR을 만들지 않는다.

## 1. 지금 알아야 할 상태

| 항목 | 현재 사실 | Frontend 행동 |
| --- | --- | --- |
| 기준선 | 문서·계약 기준선과 그 뒤의 병합된 dependency update가 `main`에 있음 | FE 담당자는 최신 `main`의 화면/API 적합성을 먼저 review한다 |
| 역할 branch | `frontend`는 이미 생성된 장기 역할 branch | 작업 전 최신 `main`과 동기화한다. `backend`에서 UI를 구현하거나 역할 branch를 다시 만들지 않는다 |
| 앱 코드 | `apps/web/`, `apps/api/`, generated client, DB migration이 아직 없음 | 구현 완료처럼 행동하지 않는다. 첫 개발은 M0 `FE-001` scaffold다 |
| Figma | 현재 52개 구현 frame, component 49개를 확인했지만 P0 mismatch가 남음 | FCR-001~015 영향 화면은 Figma 수정·검토 전 UI를 확정하지 않는다 |
| P0 언어 | 한국어·English 실제 지원, 日本語·中文만 disabled `준비 중` | English를 준비 중으로 표시하거나 API 호출을 막으면 안 된다 |
| 로그인·위치 | P0은 익명 session, login/위치는 OFF | 로그인 CTA·P1 feature·geolocation request를 활성화하지 않는다 |

M0의 web toolchain, router, i18n shell, generated client 연결 준비처럼 Figma visual
결정을 고정하지 않는 작업은 FCR 종료와 병렬로 할 수 있다. 반면 언어 선택, feed header,
trip의 거리·비교 주장, Live source/list, profile, data guide, ITEM optimizer의
setup/preview/applied 같은 영향 UI를 “임시”로 구현해 합치는 것은 금지한다.

## 2. 읽는 순서

### 첫날 필독

1. [CLAUDE.md](../../CLAUDE.md): 모든 작업의 불변식, 보안, validation 규칙
2. [README](../../README.md): 제품 범위와 두 역할의 merge 원칙
3. [문서 지도](../README.md): 정본 우선순위와 담당별 진입점
4. [Frontend 실행서](./FRONTEND_PLAYBOOK.md): 52개 frame, operationId, backlog, Done 기준
5. [PM 정합성 감사](../project/PM_CONSISTENCY_AUDIT.md): 현재 NO-GO 이유, scope cut, hard gate
6. [Figma 수정 요청](../design/FIGMA_CHANGE_REQUESTS.md): 열려 있는 P0 blocker와 닫는 증거
7. [브랜치·Docker 통합](../engineering/BRANCH_AND_INTEGRATION.md): `frontend → main` PR 규칙
8. [로컬 개발](../engineering/LOCAL_DEVELOPMENT.md): M0 이후 실제 실행 순서와 환경 경계

### 기능별로 추가할 정본

| 확인할 것 | 먼저 읽을 문서 | 다음 정본 |
| --- | --- | --- |
| 화면·route·상태 | [Figma 핸드오프](../design/FIGMA_HANDOFF.md) | Figma node, [Component Catalog](../design/COMPONENT_CATALOG.md) |
| 기능 범위·acceptance | [기능 인벤토리](../product/FUNCTIONAL_INVENTORY.md) | [제품 요구사항](../product/PRODUCT_SPEC.md) |
| HTTP type·오류·example | [OpenAPI](../api/openapi.yaml) | [API 규칙](../api/README.md) |
| 저장·상태 전이 | [ERD](../architecture/ERD.md) | [시스템 구조](../architecture/SYSTEM_ARCHITECTURE.md) |
| component/E2E/a11y | [테스트 전략](../engineering/TEST_STRATEGY.md) | ownership matrix의 화면 row |
| 개인정보·출처·위치 | [개인정보 요구사항](../security/PRIVACY_REQUIREMENTS.md) | [Source Catalog](../data/SOURCE_CATALOG.md) |
| 공모전 화면/증거 | [준수 매트릭스](../contest/COMPETITION_COMPLIANCE_MATRIX.md) | [제출 runbook](../contest/SUBMISSION_RUNBOOK.md) |

문서가 충돌하면 Figma를 우선해 API를 추측하지 않는다. 화면의 시각·copy·상태는 Figma,
동작·데이터·상태 전이는 OpenAPI/event/ERD가 정본이다. 충돌은 같은 기능 ID의 contract
issue와 synchronized 문서 변경으로 해결한다.

## 3. Git 저장소 구조

현재 repository에는 목표 제품의 문서 기준선만 있다.

```text
.
├── .claude/                 # Claude Code rules와 /nullnull-slice skill
├── .github/                 # PR template, docs-contract, docker-integration workflow
├── docs/
│   ├── design/              # Figma handoff, component catalog, FCR
│   ├── product/             # feature ID와 제품 요구사항
│   ├── api/ contracts/      # OpenAPI 3.1, event schema/example
│   ├── architecture/ data/  # ERD, source provenance
│   ├── engineering/ roles/  # 계획, test, branch contract, 실행서
│   ├── security/ contest/   # privacy, contest evidence/runbook
│   └── project/             # PM audit, risk, repository baseline
├── scripts/                 # 문서/target-stack/integration verifier
├── compose.integration.yml  # M0 뒤 full integration contract
└── apps/                    # 아직 없음; M0에서 web/api를 함께 추가
```

M0 후 Frontend의 소유 경로는 `apps/web/**`이며, generated TypeScript API client는
`packages/api-client/**`다. 과거 Python/FastAPI/SQLite 또는 예전 React prototype을
복사하거나 되살리지 않는다. 현재 source tree에 target app이 없다는 것은 실패가 아니라
의도된 baseline 상태다.

## 4. branch와 첫 작업 절차

### 최신 `main`을 검토할 때

1. `main`의 `FRONTEND_PLAYBOOK`, Figma FCR, OpenAPI examples, mobile/a11y
   acceptance를 review한다.
2. 응답 field, Problem code, Figma state가 화면 구현에 부족하면 review comment 또는
   contract issue로 남긴다. hand-written response type으로 해결하지 않는다.
3. M0 scaffold의 구체 라이브러리·생성기 설정을 Backend/AI 담당자와 contract packet으로
   확정한다. 이때 UI visual implementation은 시작하지 않는다.

### 기존 `frontend` 역할 branch 동기화

`frontend`는 이미 origin에 존재하는 장기 역할 branch다. 새로 만들거나 삭제하지 않고,
Frontend 담당자는 작업 트리가 깨끗한지 확인한 뒤 매일 다음 순서로 시작한다.

```bash
git status --short
git fetch origin
git switch frontend
git log -1 --oneline origin/main
git merge --ff-only origin/main
git push origin frontend
```

열린 Frontend PR 작업으로 `frontend`와 `origin/main`이 갈라져 fast-forward할 수
없으면 rebase나 force push를 하지 않는다. `git merge origin/main`으로 병합하고 충돌을
해결한 뒤 전체 gate를 다시 실행한 다음 `frontend`를 push한다.

- 작업 PR은 항상 `frontend → main`이다. `main` direct push, force push, self-approval,
  `backend ↔ frontend` PR/cherry-pick은 금지한다.
- 병합 방식은 merge commit이다. merge 뒤 `frontend`는 새 `main`으로 fast-forward한다.
- 한 사람의 동시 작업은 main slice 1개와 review 1개다.
- PR에는 Backend/AI 담당자 1인의 승인, `docs-contract`, `docker-integration` 통과가 필요하다.

## 5. Frontend가 바로 할 수 있는 최소 작업

`FE-001`을 M0 첫 slice로 쪼갠다. Backend/AI의 corresponding M0 contract와 합의한 뒤
아래까지로 제한한다.

1. `apps/web/`에 React + TypeScript + Vite scaffold를 만든다.
2. router, query client, KO/EN i18n shell, basic mobile layout를 준비한다.
3. OpenAPI generator와 `packages/api-client/` 소비 경로를 연결한다. response type이나
   fetch wrapper를 수동으로 복제하지 않는다.
4. `VITE_*` 공개 config만 허용하고 secret을 browser bundle에 넣지 않는다.

Figma token·Storybook·component manifest는 `FE-002`, error boundary·Problem
mapper·MSW fixture는 `FE-003`, PWA manifest·service worker·offline shell은
`FE-004`의 별도 slice로 진행한다. `FE-001`에 이 범위를 섞지 않는다.

첫 화면 구현은 FCR-001이 닫힌 뒤 `FR-ONB-02` 언어 선택으로 시작한다. 예상 흐름은
`createDemoSession → issueCsrfToken → getCurrentOwner → updatePreferences`이며,
KO/EN은 선택·저장·새로고침 복구, JA/ZH는 disabled `준비 중` 및 API 호출 0건이다.

## 6. 반드시 지킬 UI·데이터 규칙

- `SavedPost`, `TripCandidate`, `TripItem`을 하나의 “저장” 상태로 합치지 않는다.
- 후보 `+`는 일정 item을 만들거나 여행 version을 올리지 않는다.
- ITEM optimizer는 사용자 `APPLY` 전까지 trip을 변경하지 않는다. `KEEP`, error,
  expired, stale도 일정 무변경이다.
- P0 feed의 following/latest/search/bell/follow/filter control은 FCR-002/003이 닫히기 전
  숨기거나 명시적으로 disabled `준비 중`이어야 하며 요청을 보내지 않는다.
- route provider가 없는 동안 Live/optimizer에서 지도·이동 시간·경로를 사실처럼 만들지
  않는다. list-first fallback과 unavailable reason을 사용한다.
- `LIVE`, `FORECAST`, `REPLAY`, `QUALITATIVE`, `STALE`, `UNAVAILABLE`은 label, 출처,
  기준 시각과 함께 나타낸다. 색·숫자만으로 차이를 전달하지 않는다.
- 붙여넣기 원문, 정밀 위치, secret/cookie/session/owner ID를 UI log, fixture, analytics,
  prompt, screenshot에 넣지 않는다.
- P0 submission profile은 로그인 불필요·browser geolocation OFF다.

## 7. Definition of Ready와 Done

### 시작 전 확인

```text
기능 ID → Figma node/state → route/overlay → operationId/schema
→ example/fixture → entity/transition → test ID → FE owner/BE reviewer
```

아래 중 하나라도 비어 있으면 component shape를 임의로 고정하지 말고 blocker로 기록한다.

- default/loading/empty/error/offline, 필요한 stale/replay/conflict state
- cookies/CSRF, ETag/If-Match, Idempotency-Key, Problem CTA
- keyboard/focus, accessible name, 360px, KO/EN acceptance
- provenance, privacy, analytics, P1 capability OFF behavior

### 완료 전 확인

- generated client만 사용하며 regenerated diff가 깨끗하다.
- 성공과 실패 뒤 domain 변화 또는 **미변경** 상태가 persistent UI에 남는다.
- mobile 360px, 768px, KO/EN, keyboard, 200% zoom, reduced motion을 확인한다.
- 화면/node → 기능 ID → operationId → fixture → component/E2E test가 manifest에 연결된다.
- 실행한 검증과 실행하지 못한 검증을 PR에 구분해 기록한다.
- Backend/AI 담당자가 실제 API 또는 동일 canonical fixture로 acceptance를 review한다.

## 8. 검증 명령

현재 baseline에서 실행 가능한 문서 검증:

```bash
python3 scripts/validate_docs.py
npx --yes markdownlint-cli2@0.23.2
npx --yes @redocly/cli@2.51.1 lint docs/api/openapi.yaml
npx --yes --package ajv-cli@5.0.0 --package ajv-formats@3.0.1 \
  ajv validate --spec=draft2020 -c ajv-formats \
  -s docs/contracts/events.schema.json -d docs/contracts/events.example.json
bash scripts/integration-test.sh
```

M0 이후 Frontend 변경에는 다음을 추가한다.

```bash
cd apps/web
npm run lint
npm run format:check
npm run typecheck
npm run test
npm run build
```

route, search, sheet/dialog, trip mutation, optimizer를 바꾸면 영향 Playwright mobile
journey와 keyboard/focus test를 함께 추가한다. target-stack marker가 생긴 뒤에는
repository root에서 `bash scripts/integration-test.sh`가 full Docker gate를 실행한다.

## 9. Claude Code 인계 응답 형식

Claude Code가 작업을 마치면 다음 형식으로 보고하게 한다.

```text
기능: FR-...
Figma: node/state ...
계약: operationId/schema/example ...
사용자 결과: ...
변경 파일: ...
실행한 검증: 명령 → 결과
미실행 검증: 명령 → 이유
호환성·개인정보: ...
차단 항목·다음 담당: ...
```

이 형식은 FE가 구현을 끝냈다는 선언이 아니라, Backend/AI 담당자가 같은 contract와
사용자 결과를 검토할 수 있는 인계 묶음이다.
