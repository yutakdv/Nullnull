---
aliases:
  - "Claude Code — Nullnull project context"
doc_type: reference
status: baseline
area: workspace
tags:
  - nullnull/reference
  - nullnull/workspace
---

# Claude Code — Nullnull project context

@AGENTS.md

## Mission

Nullnull은 발견한 장소를 특정 여행의 후보로 모으고, 검증된 혼잡·경로 근거로 더 나은 일정을 제안한 뒤 사용자가 **직접 승인**하는 모바일 PWA다. 빠른 시각 구현보다 일정 무결성, 데이터 출처, 개인정보 최소화와 접근성을 우선한다.

## Repository baseline

- UI 정본: Figma `02 UI Design` 구현 화면 52개, `01 Components` 최상위 컴포넌트 49개.
- 동작 정본: `docs/api/openapi.yaml`, `docs/contracts/`, `docs/architecture/ERD.md`.
- 화면-node-state 연결: `docs/design/FIGMA_HANDOFF.md`, `COMPONENT_CATALOG.md`.
- 기능 추적: `docs/product/FUNCTIONAL_INVENTORY.md`의 기능 ID.
- 현재 존재: `apps/api`(Spring Boot 4.1.1, Java 21), `apps/ai`(Python 3.13 추천 서비스), `compose.yml`. 아직 없음: `apps/web`, `packages/api-client`, `infra`, `.nullnull-target-stack`(FE scaffold와 같은 PR).
- 추천 계산은 `apps/ai`, hydration·재검증·저장은 `apps/api`다(ADR-0006, `docs/decisions/ARCHITECTURE_DECISIONS.md`). 실행 계획: `docs/superpowers/plans/2026-09-07-recommendation-python-service.md`.
- 현재 저장소에는 목표 서비스 정본만 둔다. 과거 prototype/문서는 Git 이력 또는 별도 작업공간에서만 참고한다.
- 기존/untracked 파일을 사용자 작업으로 간주한다. 임의 삭제·이동·reset/clean을 하지 않는다.

공모전 자격·마감·제출·KTO 필수 활용은 최신 공식 공지가 우선한다. Figma는 시각·문구·화면 상태, 기계 계약은 request/response·저장·상태 전이의 정본이다. 충돌하면 임의로 고르지 말고 기능 ID에서 양쪽 정본을 함께 수정한다.

## P0 product decisions

- 한국어·영어는 실제 선택·복구한다. 일본어·중국어는 disabled `준비 중`이다.
- 가입 없는 익명 session이 P0다. 프로필 로그인 CTA는 P1 전까지 disabled `준비 중`이다.
- 프로필의 최적화 이력은 상태·시각·대상 여행 링크만 보인다. 이력을 위해 일정 내용을 별도 복제·장기 보관하지 않는다.
- P1 알림에는 개별 읽음과 `모두 읽음`, allowlisted deep link가 포함된다.
- 공모전 제출 빌드는 `로그인 불필요`이고 위치 기능은 OFF다. 핵심 심사 흐름은 익명창에서 완결돼야 한다.

## Non-negotiable invariants

1. `SavedPost`, `TripCandidate`, `TripItem`은 서로 다른 resource다.
2. `+`는 날짜·시간 없는 후보만 만들며 trip schedule version을 올리지 않는다.
3. AI/optimizer는 preview만 만들고 사용자 `APPLY` 전에는 trip을 수정하지 않는다.
4. `KEEP`, failed, expired, stale preview는 trip을 수정하지 않는다.
5. apply/revert/import confirm/후보 일정화는 원자적 transaction이다.
6. trip mutation은 owner와 ETag/If-Match를, retryable command는 Idempotency-Key를 검증한다.
7. `MUST_VISIT`, `DATE`, `TIME`, `RESERVATION` 잠금은 독립적이며 자동 해제하지 않는다.
8. provenance와 pair/context 비교 자격 없이 crowd·대안을 수치 비교하지 않는다.
9. LLM은 사실·영업·좌표·경로·혼잡·적용 가능성의 최종 판정자가 아니다.
10. P0은 붙여넣기 원문을 저장/로그/analytics에 남기지 않고 정밀 위치를 서버로 보내지 않는다.
11. client가 보낸 owner/session ID를 신뢰하지 않고 authenticated cookie session에서 유도한다.
12. 제출 서비스는 한국관광공사 OpenAPI를 실제 server-side 호출하며 호출 증거와 승인된 텍스트 출처를 보존한다.

안전 불변식을 demo shortcut, fallback, feature flag로 완화하지 않는다.

## Two-person contract

역할명은 `Frontend`와 `Backend/AI`를 사용하며 상세 RACI는 `docs/engineering/OWNERSHIP_MATRIX.md`, 실행 순서는 `docs/roles/`, 브랜치 계약은 `docs/engineering/BRANCH_AND_INTEGRATION.md`를 따른다.

- Frontend: route/screen/component, 접근성, client state, generated client, Storybook/MSW, Playwright.
- Backend/AI: OpenAPI 초안, domain/DB, session/auth, external data, optimizer/LLM guardrail, AWS/observability.
- Backend/AI가 계약과 example을 제안하고 Frontend가 화면에 충분한지 승인한다.
- FE는 승인 example mock, BE/AI는 같은 example contract test로 병렬 진행한다.
- UI-only도 BE/AI가 data/auth/analytics 경계를, backend-only도 FE가 public contract/error를 검토한다.
- handoff에는 기능 ID, Figma node/state, operationId/schema, 성공·실패 상태, 미결정, 실행한 검증을 남긴다.
- Frontend는 `frontend`, Backend/AI는 `backend`에서 작업하고 상대 승인과 두 required check 뒤 `main`에 merge commit한다.

구체 기능은 `/nullnull-slice <기능 ID 또는 설명>` project skill을 사용한다.

## Start every task

1. `git status`로 사용자 변경과 현재 branch를 확인한다.
2. `README.md`, `docs/README.md`, 기능 ID와 연결된 Figma/API/ERD/test 문서만 읽는다.
3. 현재 역할, 기능 ID, Figma node, operationId와 명시적 비범위를 적는다.
4. contract mismatch, destructive migration, 개인정보·출처 위험을 구현 전에 보고한다.
5. 한 vertical slice의 최소 계획으로 작업한다. unrelated refactor/formatting을 섞지 않는다.
6. OpenAPI/event/ERD 영향을 먼저 반영하고 FE mock·BE contract test를 맞춘다.
7. success뿐 아니라 empty/error/offline/stale/concurrency/accessibility를 검증한다.
8. 사용자가 요청하지 않으면 commit, push, PR, production deploy를 하지 않는다.
9. 공모전 범위이면 실제 구현 상태·KTO 호출·출처·익명 외부망 동작을 준수 매트릭스의 증거와 연결한다.

## Target boundaries

Frontend 상세 규칙은 `.claude/rules/frontend.md`, Backend/AI 상세 규칙은 `.claude/rules/backend-ai.md`가 해당 경로에서 자동 적용된다.

- FE는 feature-oriented module, semantic HTML, generated type/client를 사용한다.
- server state는 query cache, form/edit buffer는 feature-local, 공유 navigation은 URL에 둔다.
- BE는 Java 21/Spring Boot modular monolith/PostgreSQL/Flyway를 사용한다.
- module의 repository/table을 건너 직접 조작하지 않고 application service/domain event를 사용한다.
- 외부 호출을 DB transaction 안에서 수행하지 않는다.
- 비동기 optimizer/deletion/collector는 lease·attempt·retry·dead-letter가 있는 persistent job이다.
- controller는 JPA entity/provider DTO를 반환하지 않는다.
- `apps/ai`는 DB·외부 API·clock·난수 없이 Spring이 보낸 immutable 입력만 계산한다. Spring `recommendation` package는 gateway port·DTO·`ProposalRevalidator`·fallback만 가지며 계산을 중복하지 않는다. 요청에 owner/session ID·원문·좌표를 넣지 않는다.
- `apps/ai` endpoint/schema를 바꾸면 `uv run python -m nullnull_ai.contracts export`로 `apps/ai/contracts/recommendation-internal-v1.json`을 갱신하고 Spring DTO를 맞춘다(`recommendationTest`가 parity를 검사).

## Contract and data rules

- API 정본은 `docs/api/openapi.yaml`, event 정본은 `docs/contracts/events.schema.json`이다.
- closed schema를 `allOf`로 확장하지 않는다. example을 실제 JSON Schema evaluator로 검증한다.
- 새 오류는 stable code, status/run failure plane, retryable, FE CTA, Figma state를 함께 정의한다.
- cursor는 opaque/signed이고 sort·filter·expiry에 결합한다.
- persisted string/array/date range/day item에는 명시적 상한과 duplicate 규칙을 둔다.
- 외부 record는 source, sourceState, observed/target/fetched/effective/expiry 시각, freshness, confidence, license/attribution, schema/normalization version, snapshot provenance를 보존한다.
- 결측값을 0/보통으로 채우거나 replay/forecast/stale를 live로 표시하지 않는다.
- provider drift는 추측 대신 quarantine/degraded 처리한다.

## Validation

변경 범위에 해당하는 가장 좁은 검사부터 모두 실행한다. 실행하지 못한 검사는 통과로 쓰지 않는다.

```bash
# docs and contracts
python3 scripts/validate_docs.py
python3 -m unittest discover -s scripts/tests -p 'test_*.py'
npx --yes markdownlint-cli2@0.23.2
npx --yes @redocly/cli@2.51.1 lint docs/api/openapi.yaml
npx --yes --package ajv-cli@5.0.0 --package ajv-formats@3.0.1 \
  ajv validate --spec=draft2020 -c ajv-formats \
  -s docs/contracts/events.schema.json -d docs/contracts/events.example.json

# target frontend, after B01
(cd apps/web && npm run lint && npm run format:check && npm run typecheck)
(cd apps/web && npm run test && npm run build && npm run test:e2e)

# backend (apps/api): unit · Testcontainers PostgreSQL · OpenAPI contract · apps/ai contract parity
(cd apps/api && ./gradlew test integrationTest openapiContractTest recommendationTest)

# recommendation service (apps/ai)
(cd apps/ai && uv run ruff check . && uv run ruff format --check . && uv run mypy && uv run pytest)

# after the B01 marker, and every main pull request through the wrapper
python3 scripts/verify_target_stack.py
bash scripts/integration-test.sh
```

CI 등록 규칙(required 두 개, `api-quality`·`ai-quality` workflow, test ID 등록)은 `AGENTS.md#ci-검사-등록`을 따른다. Route/search/sheet/dialog/trip mutation/optimization 변경은 Playwright와 keyboard/focus 검사를 포함한다. migration은 empty DB, previous→latest, rollback-compatible app과 실제 PostgreSQL에서 검증한다.

## Gotchas

- Gradle은 Temurin 21로만 실행한다: `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./gradlew --no-daemon …` (기기 기본 `java`는 26). 설치형 Gradle 금지, wrapper만.
- `apps/ai`는 uv 0.12.10 고정. 로컬 bootstrap은 `apps/ai/README.md`(`.uv-bootstrap` venv) 또는 `docker build -f apps/ai/Dockerfile --target test`.
- `docs/**/*.md`는 Obsidian frontmatter(`aliases`, `doc_type`, `status`, `area`, `tags`)가 필수다. `scripts/validate_docs.py`가 링크·heading anchor·frontmatter를 검사한다.
- `docs/roles/BACKEND_AI_PLAYBOOK.md` 카드와 `docs/engineering/backend-plan.json`은 validator가 동기화를 검사한다. title/ID/기능/API/선행/test ID/`데이터·정책`은 둘 다 고친다.
- compose `api-quality`는 `NULLNULL_TEST_DATABASE=external`로 Testcontainers 대신 compose PostgreSQL을 쓰고 `--offline`으로 실행된다. 새 test dependency는 `resolveTestClasspaths`가 해석하는 configuration에 있어야 한다.
- `docs/contracts/review-2026-09-06/`는 FE 계약 검토 재현 자료다(`node_modules`는 gitignore). 삭제·정리하지 않는다.

## Security and privacy

- secret, cookie, token, account ID, 원문 일정, 실제 위치를 source/fixture/screenshot/prompt/log에 넣지 않는다.
- raw request/response logging, CSRF/CORS/Origin 우회, client owner 신뢰를 금지한다.
- SQL/HTML/URL을 context에 맞게 parameterize/escape하고 외부 URL은 allowlist한다.
- production data를 local test에 복사하지 않는다.
- 위치 기능 전에는 동의, 목적, 정밀도, 보존, 삭제를 먼저 문서화한다.
- 삭제는 즉시 revoke, status, tombstone/manifest와 backup 복구 후 재삭제까지 설계한다.
- 공모전 제출 profile에서는 browser geolocation을 요청하지 않고 위치 관련 capability를 OFF로 둔다.

## Completion response

사용자 결과, 주요 파일, 실행한 검증과 결과, 미실행 검사, API/migration/deploy 호환성, 남은 blocker·결정을 짧게 보고한다.
