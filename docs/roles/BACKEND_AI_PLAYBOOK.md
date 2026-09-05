# Backend/AI 실행 플레이북

- 상태: `backend` 역할 브랜치 실행 계약
- 담당: Backend/AI 1명(`BE_AI_DRI`)
- 상대 담당: Frontend 1명(`FE_DRI`)
- 기준일: 2026-09-05
- 대상: Spring API, PostgreSQL, 관광 데이터, optimizer/AI, AWS

이 문서는 Backend/AI 담당자가 Figma의 현재 52개 frame에 필요한 server truth를
빠짐없이 제공하기 위한 작업 순서다. [Figma 수정 요청](../design/FIGMA_CHANGE_REQUESTS.md)의
P0 불일치는 화면 구현 전에 계약 의미를 함께 검토하고, 빠진 ITEM READY preview에
`OptimizationChange`·provenance·validation·APPLY/KEEP example을 제공한다. 기능 의미는
[제품 요구사항](../product/PRODUCT_SPEC.md), 공개 계약은 [OpenAPI](../api/openapi.yaml)와
[이벤트 스키마](../contracts/events.schema.json), 저장 규칙은 [ERD](../architecture/ERD.md),
화면 상태는 [Figma 핸드오프](../design/FIGMA_HANDOFF.md)를 따른다. 공모전 release는
[준수 매트릭스](../contest/COMPETITION_COMPLIANCE_MATRIX.md)를 추가 적용한다.

새 구현의 정본 경로는 `apps/api/`이다. 과거 Backend prototype은 목표 저장소에 포함하지 않으며 M0 scaffold 전 문서·contract 기준선을 새 API 통합 완료로 간주하지 않는다.

## 1. 역할 경계

### Backend/AI가 끝까지 소유하는 것

- `apps/api/**`: Spring module, HTTP adapter, domain policy, transaction, async job과 admin-free operations.
- `docs/api/**`, `docs/contracts/**`: OpenAPI/event schema, canonical examples, error·pagination·idempotency 계약.
- PostgreSQL schema, Flyway, index, owner authorization, ETag/version, deletion/tombstone와 retention.
- 관광지·혼잡 source registry, KTO/서울 adapter, provenance, freshness, license, quota와 drift quarantine.
- deterministic candidate generation/scoring, constraint/route/data validator, proposal/apply/revert 감사 기록.
- LLM의 bounded preference parsing/explanation, prompt/output validation, kill switch와 비용/품질 계측.
- API/unit/integration/contract/property/load/security test, structured log·metric·alarm.
- Docker integration의 API/DB/source fixture와 AWS CDK/OIDC/ECS/RDS/rollback 운영.
- 한국관광공사 OpenAPI 실제 호출과 심사 제출용 비밀 없는 데이터 활용 증거.

### Backend/AI가 소유하지 않는 것

- Figma visual token, route layout, React state, focus/keyboard 구현과 최종 사용자 copy.
- generated client 내부 직접 수정, MSW만을 위한 production response field.
- 대표 화면·반응형·screen reader 품질의 최종 판정.

공개 response/error/data state는 FE가 실제 화면을 구현할 수 있어야 완료된다. Backend/AI가 계약 작성자라는 이유로 혼자 승인하지 않는다.

## 2. Figma 52개 frame 제공표

| 그룹 | Figma frame·화면 | Pri / ticket | Backend/AI 산출물 | operationId·server 경계 |
| --- | --- | --- | --- | --- |
| A | `388:257` A-1 splash | P0 / `BE-101` | owner/session/cookie/CSRF/readiness, 만료·재발급 | `createDemoSession`, `issueCsrfToken`, `getCurrentOwner`, `getDemoReadiness` |
| A | `388:277` A-2 언어 | P0 / `BE-105` | KO/EN validation·저장, JA/ZH 거부 | `updatePreferences` |
| A | `388:321` A-3 소개 | P0 / `BE-105` | onboarding 상태 멱등 저장 | `updatePreferences` |
| B | `391:310` S03-F0 여행 없음 feed | P0 / `BE-202` | empty trip과 feed를 분리한 cursor read | `listFeed`, `listTrips` |
| B | `396:2926` S03-F1 활성 여행 feed | P0 / `BE-202` | active trip 후보/일정 projection | `listFeed`, `recordFeedFeedback` |
| B | `398:611` S03-D 게시물 상세 | P0 / `BE-201`, `BE-204` | post/place/provenance, SavedPost 독립성 | `getPost`, `getPlace`, `savePost`, `unsavePost` |
| B | `399:658` S03-C1 여행 선택 | P0 / `BE-203` | owner-scoped trips와 active trip | `listTrips` |
| B | `399:843` S03-C2 저장 완료 | P0 / `BE-203` | candidate 201, unique key, 일정 무변경 | `addTripCandidate` |
| B | `399:1011` S03-C3 중복 | P0 / `BE-203` | existing candidate 200, `duplicate=true` | `addTripCandidate` |
| B | `399:1179` S03-C4 저장 오류 | P0 / `BE-203` | idempotent replay, Problem, 부분 row 0 | `addTripCandidate` |
| B | `409:1595` S06 공통 저장 sheet | P0 / `BE-203` | feed/post/Live 동일 candidate 계약 | `listTrips`, `addTripCandidate` |
| C | `438:3012` S02-1 날짜 | P0 / `BE-102` | range/timezone/최대 길이 server validation | `createTrip`, `updateTrip` |
| C | `438:3108` S02-2 관심사 | P0 / `BE-102`, `BE-106` | canonical set, 중복·미지원 거부 | `createTrip`, `replaceTripInterests` |
| C | `438:3134` S02-3 계획 수준 | P0 / `BE-102` | enum validation | `createTrip` |
| C | `438:3158` S02-4B 필수 장소 | P0 / `BE-103` | bounded search, canonical POI, seed MUST_VISIT | `searchPlaces`, `getPlace` |
| C | `400:1201` S02-4C 입력 방식 | P0 / `BE-104` consult | server 호출 없는 client 분기 명시 | capability only |
| C | `401:1221` S02-4C-A 붙여넣기 | P0 / `BE-104` | no-store/no-log parser, versioned draft/remap | `parseTripImport`, `remapTripImport` |
| C | `438:3199` S02-4C-C 직접 입력 | P0 / `BE-102`, `BE-103` | POI/date/order 재검증 | `searchPlaces`, `createTrip` |
| C | `438:3259` S02-5C 확인 | P0 / `BE-102`, `BE-104` | idempotent create/confirm, 한 transaction | `createTrip`, `confirmTripImport` |
| C | `384:5673` Final S02-5 결정적 draft | P0 / `BE-102` | 검증된 deterministic seed 일정 | `getTrip` |
| C | `440:3244` S02-6 AI draft | P1 / `BE-P1-105` | bounded model, validator, evidence, kill switch | P1 계약/capability 후에만 |
| D/E | `410:1738` S07-1 여행 보기 | P0 / `BE-301` | complete aggregate, ETag, candidate count | `getTrip`, `listTripCandidates` |
| D/E | `411:1837` S07-2 편집 | P0 / `BE-302`, `BE-306` | atomic commands, version conflict, 범위 축소 충돌 | `updateTrip`, item mutations |
| D/E | `527:4085` S07-2 시간 편집 | P0 / `BE-302`, `BE-304` | local time/duration validation, typed TIME lock | `updateTripItem`, constraint operations |
| D/E | `412:1912` S07-8 후보 panel | P0 / `BE-301`, `BE-303` | ACTIVE/SCHEDULED, match, candidate→item transaction | `listTripCandidates`, `getCandidateTripMatches`, `addTripItem` |
| D/E | `413:2020` S07-9 폐기 dialog | P0 / consult | UI-only draft가 server에 반영되지 않음을 보장 | API 호출 없음 |
| D/E | `413:2081` S07-7 필수 lock 해제 | P0 / `BE-304` | MUST_VISIT만 제거, version + audit | `removeTripItemConstraint` |
| D/E | `414:2347` S07-6 교체 비교 | P0 / `BE-305` | verified relation/provenance와 atomic replace | `listRelatedPlaces`, `replaceTripItem` |
| D/E | `527:4537` S07-6 교체 비교 variant | P0 / `BE-305` | 같은 계약·긴 provenance | 위와 동일 |
| D/E | `476:3409` S07-3 장소 검색 | P0 / `BE-103` | query bound, stable cursor, log redaction | `searchPlaces` |
| D/E | `479:3497` S07-5 교체 대상 | P0 / `BE-305` | item owner/version/replace eligibility | `getTrip`, `listRelatedPlaces` |
| D/E | `479:3816` S07-4 추가 완료 | P0 / `BE-302` | add transaction, unique position, version +1 | `addTripItem` |
| D/E | `527:4380` S07-4 추가 완료 variant | P0 / `BE-301` | write 뒤 동일 read projection | `getTrip` |
| D/E | `521:3976` S07-10 날짜 이동 | P0 / `BE-302` | range/position/lock 검증 | `updateTripItem`, `reorderTripItems` |
| D/E | `527:4695` S07-10 날짜 이동 variant | P0 / `BE-302` | 같은 atomic command | 위와 동일 |
| D/E | `527:3876` S07-10b 날짜 lock 확인 | P0 / `BE-304` | typed conflict와 명시적 해제 | constraint operations |
| F | `415:2268` S09-0 최적화 설정 | P0 / `BE-501`~`BE-504` | ITEM run, input snapshot/fingerprint, lock validation | `createOptimization` |
| F | `415:2413` S09-1 계산 중 | P0 / `BE-501` | persistent job lease/state, retry hint | `getOptimization` |
| F | `439:3104` S09-D1 DAY preview | P1 / `BE-P1-105` | route matrix gate 전 capability OFF | P1 capability 후에만 |
| F | `417:2412` S09-3 적용 완료 | P0 / `BE-505`, `BE-506` | version/fingerprint 재검증, apply/revert revision | `decideOptimization`, `revertOptimizationDecision` |
| F/I | `417:2567` S09 오류 reference | P0 ref / `BE-503`, `BE-505` | 6 code와 정확한 sync/async 상태 | optimization operations/Problem |
| F/I | `485:3517` stale reference | P0 ref / `BE-505` | `TRIP_CHANGED`/`DATA_CHANGED`, old preview 적용 차단 | `createOptimization`, `decideOptimization` |
| G | `418:2523` S11-1 Live | P0 / `BE-401`~`BE-405` | area/places, 거친 viewport, source state | `queryLiveAreas`, `listLiveAreaPlaces` |
| G | `419:2617` S11-2 장소 상세 | P0 / `BE-405` | full provenance/freshness/fallback | `getLivePlace`, `getPlaceCrowdForecast` |
| G | `420:2821` S11-3 대안 | P0 / `BE-405` | relation reason와 pair comparison eligibility | `listRelatedPlaces` |
| G | `420:2950` S11-N 대안 없음 | P0 / `BE-405` | relation `NONE`, 정직한 reason | `listRelatedPlaces` |
| G | `421:2850` S11-R replay | P0 / `BE-401`, `BE-407` | immutable replay manifest/snapshot time | Live operations with `REPLAY` |
| G | `501:3750` S11-4 재계획 진입 | P1 / `BE-P1-102` | DPIA/동의/TTL 전 capability OFF | P1 계약 전 위치 수신 금지 |
| H | `422:2925` S14 프로필 | P0 / `BE-105`, `BE-106`, `BE-507` | owner/trip/history projection, interest ETag, deletion lifecycle | profile/trip/history/deletion operations |
| H | `423:2967` S15 데이터 안내 | P0 / `BE-407` | source registry/capability의 public projection | `getDemoReadiness` 및 provenance-bearing reads |
| H | `442:3344` S12 알림 | P1 / `BE-P1-101` | storage/retention/read-all/deep-link allowlist | notification operations |
| H | `442:3370` S10 주변 | P1 / `BE-P1-102` | 최소화 위치, consent audit/TTL | P1 계약/capability 후에만 |

## 3. operationId 제공 목록

Backend/AI는 아래 50개 operationId의 request/response/error example, authorization, bounds, observability와 contract test를 제공한다.

| Module | Backend/AI가 구현·보증할 operationId |
| --- | --- |
| Platform | `getLiveness`, `getReadiness`, `getDemoReadiness` |
| Session/profile | `createDemoSession`, `issueCsrfToken`, `deleteCurrentSession`, `getDeletionRequest`, `getCurrentOwner`, `updatePreferences` |
| Feed/post | `listFeed`, `recordFeedFeedback`, `getPost`, `savePost`, `unsavePost` |
| Place/data | `searchPlaces`, `getPlace`, `getPlaceCrowdForecast`, `listRelatedPlaces` |
| Trip | `listTrips`, `createTrip`, `getTrip`, `updateTrip`, `deleteTrip`, `replaceTripInterests` |
| Import | `parseTripImport`, `remapTripImport`, `confirmTripImport` |
| Candidate | `listTripCandidates`, `addTripCandidate`, `removeTripCandidate`, `getCandidateTripMatches` |
| Item/constraint | `addTripItem`, `reorderTripItems`, `updateTripItem`, `removeTripItem`, `replaceTripItem`, `setTripItemConstraint`, `removeTripItemConstraint` |
| Optimization | `createOptimization`, `listOptimizationHistory`, `getOptimization`, `decideOptimization`, `revertOptimizationDecision` |
| Live | `queryLiveAreas`, `listLiveAreaPlaces`, `getLivePlace` |
| Notification(P1) | `listNotifications`, `markAllNotificationsRead`, `markNotificationRead` |
| Analytics | `ingestEventBatch` |

각 mutation은 owner/CSRF/Origin 검증, request bound, idempotency 또는 `If-Match`, transaction 범위, 성공/오류 request ID를 명시한다. collection cursor는 서명·filter binding·stable tie-breaker·만료를 보장한다. P1 route가 schema에 있어도 server capability가 꺼져 있으면 mutation을 수행하지 않는다.

## 4. Milestone·ticket 순서

| Milestone | Backend/AI 소유 ticket | 닫히는 server truth |
| --- | --- | --- |
| M0 | `BE-001`~`BE-004`, `DX-002`, `INF-001`~`INF-003` | Spring/DB/Flyway/Problem/contract test, Docker seed, staging/OIDC 뼈대 |
| M1 | `BE-101`~`BE-106`, `SEC-101` | owner/session/CSRF, trip/import/create, profile/preferences/interests, 삭제 job |
| M2 | `BE-201`~`BE-204`, `AN-201` validation | place/post/feed/SavedPost/candidate와 event allowlist |
| M3 | `BE-301`~`BE-306` | trip read/ETag, item transaction, candidate 일정화, constraints, replace/date range |
| M4 | `BE-401`~`BE-407`, `OPS-401` | source registry/collector/KTO/서울/Live/replay/comparison/공개 데이터 안내 |
| M5 | `BE-501`~`BE-507` | persistent run, deterministic optimizer, validator, apply/keep/revert/history |
| M6 | `BE-601`, `BE-602`, `OPS-601`~`OPS-605`, `REL-601`, `REL-602`, `SEC-601` | auth/load/security, backup/rollback/WAF/license/AWS/release/incident |
| P1 | `BE-P1-101`~`BE-P1-106` | 알림·위치·검색·media·AI/DAY·정식 계정 계약과 gate |

`QA-*`, `CON-*`, `GOV-*`, `DX-*`는 공동 gate다. Backend/AI가 fixture/provider 쪽을 제공하고 FE가 실제 client/화면에서 재현해야 닫는다.

## 5. `backend` 브랜치와 일일 작업

`backend`는 Backend/AI 전용 장기 역할 브랜치이며 `main`에 직접 push하지 않는다. 재사용 가능한 이력을 위해 PR merge는 **merge commit**으로 고정하고 squash/rebase merge와 force push를 금지한다. 모든 PR에는 상대 담당자 1인 승인, 최신 `main`, `docs-contract`, `docker-integration`이 필요하다.

### 매일 시작

1. `main`의 마지막 green SHA와 `frontend → main` UI acceptance 변경을 확인한다.
2. 열려 있는 BE PR이 없으면 `backend`를 `origin/main`으로 fast-forward한다.
3. 열려 있는 BE PR이 있으면 `origin/main`을 일반 merge로 받아 contract·migration·Docker gate를 다시 실행한다.
4. issue에 기능 ID, Figma node, operationId/schema, entity/transition, migration, test와 오늘 닫을 acceptance를 적는다.
5. 공개 shape 변경은 OpenAPI/example/failing contract test부터 commit한다.

### 구현과 종료

1. domain invariant → persistence → adapter → HTTP 순서로 구현하고 transaction 경계를 test한다.
2. contract-only additive PR을 먼저 `backend → main`으로 합치면 FE가 기다리지 않고 generated client로 병렬 작업할 수 있다.
3. API/DB/source/optimizer gate와 affected Docker journey를 실행한다.
4. `backend → main` PR 하나만 열고 FE 담당자를 reviewer로 지정한다.
5. PR merge ref의 `docker-integration`에서 main web과 candidate API/DB를 함께 검증한다.
6. merge 후 `backend`를 `main`으로 fast-forward하고 branch를 삭제하지 않는다.

두 역할 브랜치 사이의 PR/cherry-pick, FE가 소비하기 전의 breaking contract, 다른 역할 branch에만 존재하는 migration 의존은 금지한다. 교차 기능은 contract-only PR → `main` merge → 두 역할 branch의 `main` 동기화 → backward-compatible Backend PR → Frontend PR 순서로 합친다. capability가 양쪽에서 준비되기 전에는 켜지 않아 반쪽 구현을 노출하지 않는다. Frontend merge 뒤 server-owned capability는 별도 `backend → main` flag/config PR, FE 승인, 두 required status와 staging acceptance로만 ON한다.

## 6. Definition of Ready

- 기능 ID, Figma node/state, P0/P1와 사용자 action 전후가 정의됐다.
- operationId/schema, owner/auth, CSRF, ETag/idempotency, cursor, bounds가 정해졌다.
- entity, state transition, unique/check/FK, transaction과 migration 전략이 있다.
- success/empty/conflict/rate-limit/failure canonical example과 FE error CTA가 합의됐다.
- provenance, source license/attribution, freshness/comparison, retention/privacy 영향이 있다.
- async 작업이면 lease/attempt/dead-letter/retry/terminal 상태가 있다.
- external source·AI가 꺼지거나 실패했을 때 capability/fallback과 핵심 CRUD 격리가 있다.
- FE가 contract example으로 화면을 구현할 수 있다고 승인했다.

## 7. Definition of Done

- OpenAPI/event/ERD/migration/구현이 같은 state와 cardinality를 표현한다.
- 모든 public operation에 canonical example, Problem code, auth/rate/request ID behavior가 있다.
- owner isolation, CSRF/Origin, idempotency/ETag, rollback, deletion/retention test가 통과한다.
- PostgreSQL Testcontainers에 Flyway를 처음부터 적용하고 downgrade/expand-contract 위험을 검토했다.
- 외부 source 실패·drift·quota·stale이 격리되고 provenance 완전성 100%를 검증했다.
- optimizer는 승인 전 변경 0, lock 보존, deterministic tie-break, atomic apply/revert를 검증했다.
- LLM 없이도 사실 검증과 안전한 핵심 흐름이 동작하며 LLM output은 schema/allowlist를 통과한다.
- API/unit/integration/contract/property/security와 `docker-integration`이 통과했다.
- FE 담당자가 generated client로 default/failure/degraded acceptance를 재현하고 승인했다.
- 공모전 제출에 필요한 실제 KTO 호출·출처·release 증거가 secret 없이 연결됐다.
- 실행하지 못한 검증은 `not run`과 이유·후속 owner를 기록했다.

## 8. 검증 명령과 Docker 통합 기대값

M0 이후 Backend/AI 변경의 로컬 gate는 다음이다.

```bash
cd apps/api
./gradlew test
./gradlew integrationTest
./gradlew openapiContractTest
```

문서·계약 변경에는 root에서 다음을 추가한다.

```bash
python3 scripts/validate_docs.py
npx --yes markdownlint-cli2@0.23.2
npx --yes @redocly/cli@2.51.1 lint docs/api/openapi.yaml
npx --yes --package ajv-cli@5.0.0 --package ajv-formats@3.0.1 \
  ajv validate --spec=draft2020 -c ajv-formats \
  -s docs/contracts/events.schema.json -d docs/contracts/events.example.json
```

`backend → main` PR의 required `docker-integration`은 최소 다음을 보장한다.

- `verify_target_stack.py`가 marker, lock/wrapper, task/stage와 image digest를
  검사하고 정규화 Compose의 필수 service/internal network를 확인한다.
- PR merge ref API image와 현재 main-compatible web image를 함께 build한다.
- fresh PostgreSQL에 전체 Flyway를 적용하고 production DB와 같은 major에서 제약·query를 검증한다.
- deterministic `base`, `trip-edit`, `optimization`, `live`, `edge` 중 영향 seed를 넣는다.
- KTO/서울은 실제 secret 없는 synthetic contract fixture/replay를 사용하고 outbound network를 차단한다.
- liveness/readiness, owner/session/CSRF, client generation diff, 영향 Playwright journey를 검증한다.
- transaction fault, retry/idempotency, source unavailable이 일정 CRUD를 오염시키지 않는지 확인한다.
- client diff, offline security/infra component gate와 실제 outbound-deny probe를
  실행한다.
- log/artifact에서 cookie, CSRF, API key, raw itinerary, 좌표를 scan한다.

모든 main PR의 단일 entrypoint는 저장소 root에서 다음과 같다.

```bash
bash scripts/integration-test.sh
```

현재 `compose.integration.yml`과 wrapper는 존재하지만 target `apps/web`, `apps/api`가 없으므로 `baseline-only`다. 이를 새 stack 통합 통과로 표시하지 않는다.

M0 marker인 repository root의 `.nullnull-target-stack`이 생기기 전 CI는 문서-only baseline validation을 수행하고 `docker-integration`을 명시적인 `baseline-only` 결과로만 끝낼 수 있다. 이는 required status 이름을 고정하기 위한 상태이지 제품 통합 통과가 아니다. marker 없이 `apps/web` 또는 `apps/api`가 먼저 생기면 불완전한 전환이므로 hard fail한다. 내용이 `version=1`인 marker가 생성된 뒤에는 앱, task/stage, lock/wrapper, immutable digest, component service 또는 internal network 중 하나라도 없으면 `baseline-only`로 우회하지 않고 hard fail한다. ruleset required status는 정확히 `docs-contract`, `docker-integration` 두 개이며 component gate는 후자 내부에서 모두 실행한다.

## 9. Frontend에 전달할 contract packet

| 항목 | Backend/AI가 제공할 내용 |
| --- | --- |
| Identity | 기능 ID, Figma node, operationId/schema, milestone/ticket, contract SHA |
| Canonical examples | success/empty/cursor/duplicate/validation/401/409/422/429/503와 example ID |
| Mutation rules | domain 전후, 무변경 보장, transaction, ETag/idempotency key/expiry |
| State machine | 허용 transition, terminal/illegal 상태, async retry/`Retry-After` |
| Data semantics | source/state/timestamps/freshness/confidence/license/attribution/comparison reason |
| Capability | enabled/disabled/degraded 조건과 안전한 사용자 대체 행동 |
| Persistence | entity/constraint/migration/retention/deletion 영향; 민감 body 미저장 |
| Fixture | schema-valid JSON, synthetic 여부, replay clock, provider capture/license 정보 |
| Operations | request/trace/error code, metric/alarm, rate/quota, rollback/kill switch |
| Evidence | contract/integration/property test report, image/schema digest, known limitation |

FE가 필요한 state와 문구를 승인하고 generated client clean regeneration을 확인하기 전에는 공개 계약을 `frozen`으로 표시하지 않는다.

## 10. 금지하는 가정

- Figma가 보여 주지 않는 오류를 UI가 알아서 처리할 것이라고 가정하지 않는다.
- candidate 저장에서 TripItem을 만들거나 trip version을 올리지 않는다.
- optimizer/LLM이 사용자 승인 없이 일정을 쓰거나 lock을 자동 해제하지 않는다.
- LLM을 관광지 존재, 영업시간, 좌표, 경로, 혼잡, 비교 가능성의 사실 source로 쓰지 않는다.
- `fetchedAt`을 `observedAt`으로 대신하거나 서로 다른 source/scope/set 값을 한 점수로 합치지 않는다.
- provenance·license·attribution·replay 표지를 누락한 숫자를 product API에 내보내지 않는다.
- raw 붙여넣기 원문, 정밀 위치, query, cookie/token, provider key를 DB/log/event/error에 남기지 않는다.
- last-write-wins, 부분 apply, 예측 없는 cascade delete, 202를 완료로 간주하는 구현을 하지 않는다.
- 일정 before/after 본문을 프로필 최적화 이력만을 위해 복제·장기 보존하지 않는다.
- mock/replay 값을 LIVE로, source 장애를 0이나 정상으로 표현하지 않는다.
- provider key를 Frontend env, bundle, GitHub PR workflow나 artifact에 주입하지 않는다.
- KTO 파일 dump만으로 필수 OpenAPI 활용을 충족했다고 주장하지 않는다.
- contract test를 맞추기 위해 FE 전용 임시 field나 비정형 `Map<String,Object>`를 추가하지 않는다.

## 11. AI·추천 안전 계약

- P0 일정 생성은 검증된 POI와 규칙 기반 deterministic seed가 기본이다.
- P0 최적화 scope는 `ITEM`; DAY/TRIP은 route matrix·평가·capability gate 후 P1이다.
- candidate generation, 날짜/시간/route 가능성, constraint와 comparison 적격성은 결정적 코드가 판정한다.
- score input, tie-break, source snapshot set, trip version, data fingerprint와 validation 결과를 감사 가능하게 남긴다.
- LLM은 자연어 선호 구조화 또는 이미 검증된 evidence의 설명만 맡는다. output은 schema validation, 근거 ID allowlist, timeout, budget와 kill switch를 통과한다.
- model/vendor/version/prompt hash는 운영 metadata로 기록하되 raw 사용자 입력과 secret은 기록하지 않는다.
- offline fixture와 golden/property test에서 LLM이 꺼져도 안전한 대체 설명과 핵심 CRUD가 동작해야 한다.

## 12. 공모전 심사 증거 책임

Backend/AI는 한국관광공사 OpenAPI가 최종 서비스에서 **실제로 호출되고 기능에 쓰였다는 증거**와 안정성 증거를 담당한다.

- 최종 지정과제 exact label 1개, 실제 KTO OpenAPI 목록, 인증키 신청자·운영계정 상태와 제출 화면 credential 입력 확인 여부를 제출 대장과 release manifest에 연결한다. 키 원문은 대장에 넣지 않는다.
- API별 호출 성공/실패·시각·release SHA·화면 기능 ID를 비식별 집계로 남긴다. 키·원문 request·개인 위치는 증거에 포함하지 않는다.
- 파일 데이터만 사용하거나 로컬 전체 dump만 서비스하면서 필수 OpenAPI 호출이라고 주장하지 않는다.
- 영속 저장이 필요하면 제공기관 허용/별도 신청 근거, 보존·갱신 정책, source schema version을 기록한다.
- 서비스 응답에 실제 source, 관측/발표·대상 시각, freshness, state, confidence, license/attribution을 완전하게 제공한다.
- `출처: ⓒ한국관광공사` 등 승인된 텍스트를 FE가 표시할 수 있게 하고 CI/BI logo asset을 무단 제공하지 않는다.
- 외부망 production URL의 health/readiness, 실제 KTO call, replay 명시, quota와 장애 복구 evidence를 같은 release SHA로 보존한다. fallback은 서비스 가용성 증거일 뿐 KTO 필수 활용을 대체하지 않는다.
- 심사용 핵심 흐름은 운영자 사전 조작이나 사전 데이터 주입 없이 새 anonymous session owner를 자동 발급해 동작하게 한다. deterministic seed/fixture는 비운영 테스트에서만 사용하고 provider 장애가 여행 CRUD를 막지 않게 한다.
- OpenAPI key가 Git, frontend bundle, CI log, screenshot, PDF, Docker layer에 없는지 제출 전 scan한다.
- 기능설명서에는 production에서 실제 사용한 API만 기재하고, 미구현 AI·DAY/TRIP·위치 기능을 완료로 주장하지 않는다.
- 제출 profile은 위치 capability를 startup에서 강제로 OFF로 검증하고 위치 endpoint·좌표 field·browser geolocation 의존이 핵심 flow에 없게 한다.

## 13. 공동 handshake

| Gate | FE_DRI 책임 | BE_AI_DRI 책임 | 둘이 남길 증거 | 통과 조건 |
| --- | --- | --- | --- | --- |
| `ticket-ready` | Figma state·접근성·사용자 transition | domain invariant·데이터/보안 영향 | 기능 ID가 있는 issue | 모호한 CTA/state/owner 없음 |
| `contract-ready` | 필요한 field/state/error와 UI acceptance 승인 | OpenAPI/event/example/state transition 작성 | contract SHA, schema-valid fixture | 생성 client와 provider test가 같은 example 사용 |
| `implementation-ready` | MSW·failing component/E2E test | DB/domain·failing contract/integration test | 양쪽 test ID | 상대 구현을 기다리지 않고 병렬 착수 가능 |
| `integration-ready` | generated client·UI 전체 상태 | 실제 API·seed·readiness·migration | handoff packet, image digest | mock 전용 field 0, contract SHA 일치 |
| `PR-ready` | server response의 사용자 표현 review | `backend → main` PR와 API evidence | PR checklist | 상대 승인, unresolved conversation 0 |
| `merge-ready` | candidate merge ref 사용자 journey | Docker/API/DB/source fixture 검증 | `docker-integration` report | 모든 required check green |
| `release-ready` | production web artifact·외부망 UX | API/data/AWS readiness·rollback | immutable release manifest | 둘 다 go, 실제 공모전 데이터 증거 확보 |

상세 구조는 [System Architecture](../architecture/SYSTEM_ARCHITECTURE.md), 개발 방식은 [Workflow](../engineering/WORKFLOW.md), 테스트 범위는 [Test Strategy](../engineering/TEST_STRATEGY.md), 배포는 [AWS Deployment](../operations/AWS_DEPLOYMENT.md)를 따른다.
