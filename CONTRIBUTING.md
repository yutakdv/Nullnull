# Nullnull 기여 안내

## 시작 전

1. [README](README.md)와 [문서 지도](docs/README.md)를 읽는다.
2. 작업할 Figma node, ticket, OpenAPI operationId를 연결한다.
3. `git status`로 기존 작업을 확인하고 unrelated change를 건드리지 않는다.
4. [Definition of Ready](docs/engineering/WORKFLOW.md)를 만족하는지 확인한다.
5. [역할 매트릭스](docs/engineering/OWNERSHIP_MATRIX.md)에서 작성자와 필수 검토자를 확인한다.
6. 공모전 배포에 포함되면 [준수 매트릭스](docs/contest/COMPETITION_COMPLIANCE_MATRIX.md)의 증거 항목을 작업 범위에 넣는다.

이 저장소는 최신 목표 서비스의 문서와 이후 생성할 `apps/web`, `apps/api`만 유지한다. 과거 프로토타입은 Git 이력 또는 별도 작업공간의 참고 자료이며 새 기능을 그 구조에 추가하지 않는다.

## Branch와 commit

두 명은 장기 역할 브랜치만 사용한다.

- Frontend 담당: `frontend`
- Backend/AI 담당: `backend`
- 통합·배포 기준선: `main`

각 담당자는 자기 역할 브랜치에서 한 vertical slice만 진행하고 `main`을 base로 PR을 만든다. 상대 담당자의 승인, `docs-contract`, `docker-integration`을 통과한 뒤 merge commit으로 병합한다. `main` direct/force push, self-approval, 역할 브랜치 삭제를 금지한다. 자세한 순서와 교차 계약 변경 절차는 [브랜치·Docker 통합 계약](docs/engineering/BRANCH_AND_INTEGRATION.md)을 따른다.

Commit은 Conventional Commits를 사용한다.

```text
feat(trip): FR-CAN-02 schedule a candidate with optimistic versioning
fix(live): FR-LIV-02 mark expired Seoul snapshots as stale
docs(api): FR-CAN-02 add candidate duplicate response
```

## Contract-first

FE/BE에 걸친 변경 순서:

1. `docs/api/openapi.yaml` 또는 `docs/contracts/events.schema.json`
2. ERD/ADR와 migration 설계
3. generated client/mock/contract test
4. backend/frontend 구현
5. integration/E2E/문서

Generated client와 migration checksum을 직접 고치지 않는다. breaking change는 expand-and-contract와 deprecation 기간을 사용한다.

### 2인 handoff

- Frontend issue에는 기능 ID, Figma node/state, route/component, 예상 operationId와 UI 상태를 적는다.
- Backend/AI issue에는 같은 기능 ID, command/query, example, 상태 전이, 권한·보존·출처 조건을 적는다.
- Backend/AI가 OpenAPI/example을 제안하고 Frontend가 실제 화면에 충분한지 승인한 뒤 계약을 동결한다.
- FE는 승인 example 기반 mock과 생성 client, BE/AI는 동일 example 기반 contract test로 병렬 진행한다.
- handoff 전에는 성공·빈 상태·오류·stale/concurrency와 미결정 사항을 명시한다.

## Pull request

- 한 PR은 한 vertical slice 또는 독립 문서/infra 변화만 담는다.
- `.github/pull_request_template.md`를 채운다.
- contract, migration, auth/security, deployment 변경은 두 팀원의 review가 필요하다.
- UI-only 변경도 Backend/AI가 데이터·권한·analytics 경계 영향을 확인하고, backend-only 변경도 Frontend가 public contract/error 영향을 확인한다.
- Figma 변화는 before/after screenshot과 node link를 첨부한다.
- failed 또는 미실행 test를 숨기지 않는다.
- secret, 사용자 data, 실제 API response 원문을 첨부하지 않는다.
- PR head/base가 각각 `frontend|backend`/`main`인지 확인하고 병합 뒤 양 역할 브랜치를 새 `main`으로 동기화한다.
- 기능설명서에는 해당 PR과 배포 URL에서 실제로 동작하는 기능·실제 호출 API만 반영한다.

## 검증

문서·계약 PR:

```bash
python3 scripts/validate_docs.py
npx --yes markdownlint-cli2@0.23.2
npx --yes @redocly/cli@2.51.1 lint docs/api/openapi.yaml
npx --yes --package ajv-cli@5.0.0 --package ajv-formats@3.0.1 \
  ajv validate --spec=draft2020 -c ajv-formats \
  -s docs/contracts/events.schema.json -d docs/contracts/events.example.json
```

Target scaffold가 만들어진 뒤 명령은 각 app README와 CI가 정본이다. 요구되는 의미는 다음과 같다.

```bash
# frontend
npm run lint
npm run format:check
npm run typecheck
npm run test
npm run build
npm run test:e2e

# backend
./gradlew test
./gradlew integrationTest
./gradlew openapiContractTest
```

모든 `main` PR은 루트에서 `bash scripts/integration-test.sh`도 실행한다. M0 전에는 문서 기준선만 검사하고 `integration_mode=baseline-only`를 명시한다. M0가 `.nullnull-target-stack`을 추가한 뒤에는 PostgreSQL·API·web·E2E가 포함된 Docker 통합 검사를 생략할 수 없다.

DB migration 변경은 PostgreSQL에서 previous→latest upgrade와 app rollback compatibility를 검증한다. route, search, sheet/dialog, 일정 생성/교체/최적화 변경은 Playwright와 keyboard 접근성 test를 포함한다.

로컬 실행과 scaffold 전/후 명령은 [LOCAL_DEVELOPMENT](docs/engineering/LOCAL_DEVELOPMENT.md), GitHub ruleset·release·rollback은 [GITHUB_RELEASE_OPERATIONS](docs/operations/GITHUB_RELEASE_OPERATIONS.md)를 따른다.

## 코드 리뷰 우선순위

1. 권한·개인정보·일정 무결성
2. OpenAPI/event/Figma 계약 일치
3. 실패/동시성/외부 장애 behavior
4. 접근성·모바일 사용성
5. 유지보수성·성능
6. style

## 외부 데이터와 AI

- source/observedAt/targetAt/freshness/confidence/license를 보존한다.
- 비교 가능하지 않은 값을 수치 비교하지 않는다.
- replay/forecast/stale를 live로 표현하지 않는다.
- LLM 출력은 검증된 사실이 아니며 결정적 server validation을 통과해야 한다.
- 새 provider는 약관, attribution, quota, 장애 fallback을 PR에 기록한다.
- 공모전 제출본은 한국관광공사 OpenAPI의 실제 server-side 호출과 호출 증거를 남기고, 화면에 `출처: ⓒ한국관광공사` 또는 승인된 동등 문구를 표시한다.
- 승인 없는 한국관광공사 CI·BI 로고, 브라우저의 서비스 키, 필수 활용을 대신하는 전체 로컬 복제본을 사용하지 않는다.

## 보안 제보

취약점은 public issue로 세부 내용을 공개하지 말고 GitHub의 private security advisory 기능을 사용한다. 처리 절차는 [SECURITY.md](SECURITY.md)를 따른다.
