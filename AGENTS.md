---
aliases:
  - "Nullnull 작업 지침"
doc_type: reference
status: baseline
area: workspace
tags:
  - nullnull/reference
  - nullnull/workspace
---

# Nullnull 작업 지침

이 저장소는 오버투어리즘 완화를 위한 여행 플래너 **널널(Nullnull)**의 작업공간이다. Frontend 1명과 Backend/AI 1명이 계약을 공유한다. 최신 Figma와 `docs/`의 계약을 운영 목표의 기준으로 삼으며 현재 목표 서비스에 속하지 않는 과거 프로토타입은 저장소에 다시 섞지 않는다.

공모전 자격·마감·제출·필수 데이터 규칙은 최신 공식 공지, Figma는 시각·화면·문구, OpenAPI/이벤트/ERD는 상태 변화·데이터 의미의 정본이다. 충돌은 임의 우선순위로 덮지 말고 같은 기능 ID에서 함께 해결한다. 자세한 지도는 `docs/README.md`, Claude Code 규칙은 `CLAUDE.md`, 역할은 `docs/engineering/OWNERSHIP_MATRIX.md`를 따른다.

## 작업 원칙

1. 한 번에 전면 교체하지 않는다. 계약이 확정된 vertical slice 단위로 새 목표 stack에 옮긴다.
2. 추천 정확성·데이터 출처·인증·피드백 무결성 등 P0를 시각적 개선이나 복잡한 ML보다 먼저 해결한다.
3. LLM은 관광지와 경로의 사실 판단자가 아니다. 후보 검색, 실시간 값, 영업 여부, 경로 가능성은 결정적 서버 로직이 검증하고 LLM은 선호 해석과 근거 기반 설명에 사용한다.
4. SavedPost, TripCandidate, TripItem을 분리하고 후보 저장으로 일정을 변경하지 않는다.
5. AI/optimizer는 변경 전후를 preview하며 사용자 승인 전에는 일정을 바꾸지 않는다.
6. 합성 시드, 실제 관측, 예측, replay, 오래된 정보, 데이터 부재를 API와 화면에서 명확히 구분한다.
7. 정밀 위치는 P0에서 서버 수집하지 않는다. 붙여넣기 원문도 저장·로그하지 않는다.
8. 변경 시 OpenAPI 계약과 이벤트 스키마를 먼저 갱신하고 프론트 클라이언트를 생성한다.
9. 새로운 외부 데이터 연동은 `source`, `source_state`, `observed_at`, `target_at`, `freshness`, `confidence`, `license`, 비교 가능성 정보를 보존한다.
10. 사용자 변경을 보존하고, unrelated file을 정리하거나 destructive git/file 작업을 하지 않는다.
11. 기능 작업은 `기능 ID → Figma node/state → operationId/schema → entity/transition → test → 담당/검토자`를 연결한다.
12. Frontend는 승인된 생성 client/example을 소비하고, Backend/AI는 계약을 제안하되 Frontend 승인 없이 FE-facing shape를 동결하지 않는다.
13. P0의 로그인, 일본어·중국어 UI는 disabled `준비 중`이며 요청을 보내지 않는다. 한국어·영어는 실제 선택·복구를 지원한다.
14. 공모전 제출본은 로그인 없이 핵심 흐름이 완결되고, 한국관광공사 OpenAPI를 실제 server-side 호출하며 승인된 텍스트 출처와 호출 증거를 남긴다.
15. Frontend는 `frontend`, Backend/AI는 `backend`에서 작업한다. 최신 `main` 기준 `docs-contract`·`docker-integration`이 모두 green이면 상대 승인 대기 없이 auto-merge를 허용하며 merge commit을 사용한다.
16. 추천 계산(feed 순서·관련 장소·slot·ITEM 개선·설명 template)은 Python 서비스 `apps/ai`가 담당하고, Spring `apps/api`는 hydration·gateway·응답 재검증·저장·APPLY를 담당한다(ADR-0006). 계산을 Spring에 중복 구현하지 않고, `apps/ai`에 owner/session ID·원문·좌표를 보내지 않는다.

## 필수 검증

문서·계약 변경:

```bash
python3 scripts/validate_docs.py
python3 -m unittest discover -s scripts/tests -p 'test_*.py'
npx --yes markdownlint-cli2@0.23.2
npx --yes @redocly/cli@2.51.1 lint docs/api/openapi.yaml
npx --yes --package ajv-cli@5.0.0 --package ajv-formats@3.0.1 \
  ajv validate --spec=draft2020 -c ajv-formats \
  -s docs/contracts/events.schema.json -d docs/contracts/events.example.json
```

새 목표 frontend(`apps/web`)가 생성된 뒤:

```bash
cd apps/web
npm run lint
npm run format:check
npm run typecheck
npm run test
npm run build
```

Backend(`apps/api`). Gradle은 wrapper만 쓰고 **Temurin 21**로 실행한다. 기기 기본 JDK가 21이 아니면
`JAVA_HOME`을 Temurin 21로 지정해야 하며, 지정하지 않으면 toolchain 해석에서 실패한다. 이 저장소에서
실제로 실행되는 형태는 `apps/api/CLAUDE.md`에 있다.

```bash
cd apps/api
./gradlew test integrationTest openapiContractTest recommendationTest
```

추천 서비스(`apps/ai`). **uv 0.12.10**으로만 실행한다. CI와 Docker에서는 `uv`가 PATH에 있고, 로컬에
없으면 `apps/ai/README.md`의 `.uv-bootstrap` venv를 쓴다(실제 실행 형태는 `apps/ai/CLAUDE.md`).

```bash
cd apps/ai
uv run ruff check . && uv run ruff format --check . && uv run mypy
uv run pytest
uv run python -m nullnull_ai.contracts export   # endpoint/schema 변경 뒤, 계약 JSON 갱신
```

모든 `main` PR:

```bash
bash scripts/integration-test.sh
```

`.nullnull-target-stack` marker가 추가되기 전까지 이 wrapper는 exit 1로 실패한다(설계된 게이트). 실패를 통과로 대체하지 말고 marker·`apps/web`을 같은 PR에서 완성한다.

라우팅, 검색, sheet/dialog, 여행 생성, 후보 저장, 일정 교체, 최적화 흐름을 바꾸면 Playwright E2E와 키보드 접근성 검사를 함께 추가한다. 새 DB 마이그레이션은 Flyway와 실제 PostgreSQL(Testcontainers/CI)에서 검증한다. 실행하지 못한 검증은 통과로 쓰지 않는다.

## CI 검사 등록

required status는 `docs-contract`·`docker-integration` 두 개뿐이다. 그 안에서 실제로 실행되는 suite와 경로별 조기 피드백 workflow를 아래 표에 등록한다. **기능 slice를 구현하면 같은 PR에서 이 표와 해당 manifest를 갱신한다.** 표에 없는 test ID는 CI가 실행한 것으로 보지 않는다.

| 검사 | 트리거 | 실행 내용 | 커버하는 ID | 상태 |
| --- | --- | --- | --- | --- |
| `docs-contract` | 모든 main PR/push | `validate_docs.py`(Problem code↔FE mapping, backend·frontend plan 검증 포함), `python3 -m unittest discover -s scripts/tests`, plan/Canvas 검증, markdownlint, Redocly, AJV, PR 전용 OpenAPI breaking diff(승인된 예외만 `docs/api/oasdiff-warn-ignore.txt`로 면제하며 `test_oasdiff_exceptions.py`가 이유·승인자·추적 이슈와 stale 행을 검사한다), report runner 부정·wrapper 실행 검사 | BA-000-T1·T2(operation·기능 ID coverage, DAG·B10 순서), BA-004-T1/T2 로컬 재현 검사, FE-003 code mapping, frontend plan DAG. **BA-000-T3은 등록하지 않는다** — `validate_product_contract_alignment`가 discriminator와 variant별 `const`·`required`를 고정할 뿐, 잘못된 example의 거부를 확인하는 negative fixture가 없다 | 실행 중 |
| `docker-integration` | 모든 main PR/push | `integration-test.sh`: verifier→`api-quality`·`ai-quality`·report 집계(실행 ID·freshness)·web·client diff·scan·egress-denied·E2E | 아래 suite 전체 | 실행 중 (`integration_mode=full-docker`) |
| `api-quality` (workflow) | `apps/api/**`, `apps/ai/contracts/**`, `apps/ai/tests/recommendation/fixtures/**`, `apps/ai/tests/recommendation/manifest.json`, `apps/ai/src/nullnull_ai/policy/**`, `docs/api/openapi.yaml`, `backend-plan.json`, report runner/검사 push/PR | Gradle `test integrationTest openapiContractTest recommendationTest` + JUnit/ready-card ID 집계 | BA-001-T2(`ArchitectureRulesTest.modulesNeverReachIntoAnotherModulesInfrastructure`), REC-ARCH-01, REC-DATA-02, REC-DATA-05, REC-JOB-01, REC-SEC-03, BA-001-T2, BA-003-T1~T3, BA-005-T1~T3, BA-010-T1~T3(SessionSafetyIT·SessionTimeIT·SessionContractTest, backend-plan.json), BA-011-T1~T3(OwnerPreferencesIT·OwnerPreferencesConcurrencyIT·OwnerContractTest), BA-012-T1~T3(DeletionIT·DeletionJobIT·TombstoneReapplierTest·SessionContractTest), BA-020-T1~T3(ProviderKitTest·CollectorRunRecorderTest·SourceRegistryIT), BA-021-T1/T2(KtoDetailResponseValidatorTest·KtoKorServicePropertiesTest·KtoPlaceDetailGatewayIT; BA-021-T3은 staging 실제 호출 증거 대기라 미등록), BA-022-T1~T3(CatalogFoundationIT·CatalogPlaceApiIT·CatalogPublicationPropertiesTest·CatalogPlaceProjectionServiceTest), BA-023-T1~T3(CrowdProvenanceProjectionTest·CrowdForecastApiIT·KtoCrowdForecastGatewayIT·FlywayMigrationIT), 내부 계약 parity(5 operation), gateway post-condition, ITEM fixture parity(LockChecks·ProposalRevalidator), policy pin parity, manifest `gradle:*` 경로 실재(`ManifestTestPathParityTest` — 실행되지만 `@DisplayName`에 ID가 없어 집계에 잡히지 않으므로 `REC-CI-4`는 등록 ID로 쓰지 않는다) | 실행 중 |
| `ai-quality` (workflow) | `apps/ai/**` push/PR | ruff, mypy strict, pytest(REC corpus, `evaluation.json`), 계약 JSON sync | `tests/recommendation/manifest.json`의 `implementedTestIds` 중 `suite: pytest` 행(`gradle:*` 행은 `api-quality`가 검증) | 실행 중 |
| `apps/web` suite (`docker-integration` 내부) | 모든 main PR/push | `verify:ci`: tokens drift, eslint, prettier, tsc, `packages/*` typecheck(tsconfig 부재·0건 매칭을 실패로 처리), vitest, build, bundle budget(gzip 상한·빌드 부재·0건 매칭을 실패로 처리). fixture↔OpenAPI ajv 검증, dist MSW 부재 단언, Storybook 커버리지, forced-colors selection, 기본 msw 핸들러 존재 검사 포함 | FE-001~005, FE-101~106, FE-201, FE-301~307, FE-404, FE-601, FE-602 | 실행 중 |
| `apps/web` E2E (`docker-integration` 내부) | 모든 main PR/push | Playwright: shell·온보딩·프로필 실동작, 360px·200% zoom·긴 문구 reflow, 터치 타깃 44px | FE-601, FE-602 | 실행 중 |

등록 규칙:

1. 새 REC ID는 `apps/ai/tests/recommendation/manifest.json`의 `implementedTestIds`와 fixture sha256에 추가하고, Spring 쪽 검증(REC-INT/SEC/JOB/FEED-04)은 해당 Gradle suite 이름을 `docs/engineering/TEST_STRATEGY.md#12`에 연결한다.
2. BA-xxx-Tn acceptance는 구현 PR에서 실제 test class/함수 이름과 report 경로를 카드에 적고 `backend-plan.json` status를 올린다. report 없는 `verified`는 validator가 거부한다.
3. 새 app 디렉터리나 suite가 생기면 workflow path filter, `compose.integration.yml` service, `scripts/integration-test.sh` 실행 단계, 이 표를 같은 PR에서 바꾼다.
4. skip·0건 실행·report 누락·`continue-on-error`·`ignoreFailures`는 금지다. path filter workflow는 조기 피드백일 뿐 required status로 승격하지 않는다.

## 문서 지도

- 전체 지도와 정본: `docs/README.md`
- 제품 범위: `docs/product/PRODUCT_SPEC.md`
- 화면/상태: `docs/design/FIGMA_HANDOFF.md`
- 컴포넌트: `docs/design/COMPONENT_CATALOG.md`
- 시스템/ERD: `docs/architecture/`
- 외부 데이터: `docs/data/SOURCE_CATALOG.md`
- API/이벤트: `docs/api/openapi.yaml`, `docs/contracts/`
- 구현/협업/테스트: `docs/engineering/`
- 역할별 실행서: `docs/roles/`
- 공모전 기준·제출: `docs/contest/`
- 개인정보/위협 모델: `docs/security/`
- AWS/환경: `docs/operations/`
- 결정/위험: `docs/project/DECISIONS_AND_RISKS.md`
