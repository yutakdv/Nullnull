---
aliases:
  - "Backend/AI 상세 개발 계획"
doc_type: plan
status: draft
area: roles
tags:
  - nullnull/plan
  - nullnull/roles
---

# Backend/AI 상세 개발 계획

이 문서는 Backend와 AI를 함께 구현할 `backend` 브랜치의 작업 초안이다. 작업 대상은 `~/Desktop/Nullnull`이다. 추천 계산은 `apps/ai`, hydration·재검증·저장은 `apps/api`가 맡는다([ADR-0006](../decisions/ARCHITECTURE_DECISIONS.md#adr-0006)). 날짜·소요일 추정 없이 우선순위와 선행 조건으로 진행한다. **현재 앱 구현은 시작 전**이며 아래 `planned`는 테스트 통과/구현 완료를 뜻하지 않는다.

[문서 홈](../README.md) · [우선순위와 실행 순서](../engineering/IMPLEMENTATION_PLAN.md) · [추천 알고리즘](../architecture/RECOMMENDATION_ALGORITHM.md) · [전체 테스트 기준](../engineering/TEST_STRATEGY.md)

## 읽는 방법과 공통 완료 조건

1. 실행 계획에서 현재 B단계를 고른 뒤 아래 BA 작업 카드를 읽는다. B번호는 개발 단계이고 Figma의 S번호와 다르다.
2. 선행 BA 작업의 계약·구현·검증이 끝난 작업부터 진행한다. 선행 계약이 바뀌면 의존 작업을 재검토한다.
3. 각 카드의 기능 ID에서 [기능 인벤토리](../product/FUNCTIONAL_INVENTORY.md) → [Figma](../design/FIGMA_HANDOFF.md)/[소유권](../engineering/OWNERSHIP_MATRIX.md) → [OpenAPI](../api/openapi.yaml) → [ERD](../architecture/ERD.md)를 따른다. Figma는 새로 승인된 상태가 아니며 열린 FCR을 유지한다.
4. 각 기능의 contract example→domain/DB→adapter/API→FE 생성 client→E2E를 같은 계약 SHA로 묶는다. 화면만의 분기·뒤로가기·접근성 기능은 BE가 인계 fixture와 API 미호출 조건을 제공하고 FE가 구현한다.
5. 모든 카드의 완료는 코드·migration·example·tests·관측·fallback·문서와 FE 인계 검수까지 포함한다. 공통 HTTP/owner/CSRF/한도/멱등성/ETag/TTL 규칙은 [API 규칙](../api/README.md)과 [시스템 설계](../architecture/SYSTEM_ARCHITECTURE.md)를 상속한다.

### 상태 기록

`planned → contract-ready → in-progress → integration-ready → verified` 순서로 기록한다. 외부 결정을 기다리면 `blocked`와 원인·안전한 기본값을, 이번 출시에서 제외하면 `deferred`와 기능 OFF 조건을 적는다. `verified`에는 실제 test report·commit/contract SHA·staging evidence가 필요하다. 이 문서 작성은 어떤 기능도 `verified`로 바꾸지 않는다.

작업 목록의 기계 판독 정본은 [backend-plan.json](../engineering/backend-plan.json)이다. 카드 제목/ID/기능/API/선행 조건/test ID와 JSON은 함께 수정하며 CI가 빠짐·중복·순서·링크를 검사한다. JSON에 구현 상태를 갱신할 때 카드에도 실제 증거를 남긴다. 세부 prose는 아래 카드가 정본이다.

### 항상 적용할 CI 계약

B01 이후 `test`, `integrationTest`, `openapiContractTest`, `recommendationTest`(Gradle)와 `apps/ai` `pytest`(REC corpus)를 실제 Gradle/uv/Docker task로 등록한다. 각 카드의 T번호는 **구현할 acceptance test ID**이며 현재 test 파일이 존재한다는 뜻이 아니다. 테스트 이름/실행 경로/report를 구현 PR에서 연결한다. 필수 suite는 매 main PR에서 실행하며 skip·0건·report 누락·timeout은 실패다. P1/P2 구현 전에는 OFF 계약만 검증하고, 활성 범위로 선정되면 ON 기능 검증도 필수가 된다. [추천 CI 상세](../engineering/TEST_STRATEGY.md#12-추천-핵심-ci-상세)를 따른다.

### Migration과 운영 산출물

각 카드의 entity는 대상 테이블/정책 경계다. 실제 migration에는 owner FK, unique/check, query index, backfill/구버전 호환, TTL/삭제, restore tombstone 재적용을 적는다. field의 정확한 타입·nullable은 ERD/OpenAPI를 참조한다. 표에 없는 새 테이블을 추가하면 ERD부터 제안한다. 각 외부 adapter와 worker에는 지연·결과 개수·안전한 실패 code·재시도/쿼터·runbook 링크를 붙인다. 민감 body를 observability로 대신 저장하지 않는다.

## B00 · 추천·계약 설계

첫 설계. 기능/API/ERD/FCR과 safety fixture를 확정한다.

### BA-000

**추천 설계와 전체 계약 기준선 확정** — P0 / `planned` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: 없음
- 기능 ID: 해당 없음
- API: 해당 없음 (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: 해당 없음; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: API 0.2.1-rc.1 제안 · 이벤트 · 기능 ID · ERD · FCR · 추천 policy 초안

구현 순서:

1. X 공개 commit의 pipeline/filter/scorer 구조를 추천 문서에 고정하고 자체 여행 목적함수와 분리한다
2. REC-CON-01~08, FCR-004/010/011/015와 기능별 request·error·example을 대조한다
3. draft preview 계약 공백과 영업·route 증거 부족을 독립 결정으로 남기고 영향 기능의 FE 검토 순서를 정한다
4. 09-06 PM 검토 PM-004, PM-006, PM-007, PM-008, PM-014, PM-021, PM-024의 영향 계약·화면·실패 fixture를 검토하고 미해결이면 해당 경계를 확정하지 않는다
5. #11 schema/example/InitialOptimizationDecision 제안과 #10 교차 결정의 FE 승인 상태를 기록한다

실패·안전 경계: 새 public endpoint·reason field·학습 이벤트를 구현 속에 숨겨 추가하지 않는다. 내부 설계와 Accepted 계약을 구분한다.

필수 검증:

- `BA-000-T1`: OpenAPI operation 전체와 기능 ID 전체에 담당 task가 존재한다
- `BA-000-T2`: 순서 DAG에 cycle이 없고 P0 Live 작업이 마지막 기능 단계다
- `BA-000-T3`: scope별 target 및 decision별 revision union의 잘못된 example을 거부한다

FE 인계·완료 증거: 검토할 schema diff·canonical examples·FCR evidence 요청·기능별 완료 조건. 디자인 파일을 실제 확인하기 전 FCR을 Closed로 바꾸지 않는다. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

PM 검토 연결: [09-06 발견 사항](../project/PM_REVIEW_2026-09-06.md) — PM-004, PM-006, PM-007, PM-008, PM-014, PM-021, PM-024.

이슈 검토: [#10 기반](../engineering/FOUNDATION_DECISIONS.md) · [#11 계약](../contracts/review-2026-09-06/README.md).

## B01 · 실행 기반·DB·상시 CI

실제 scaffold와 full Docker gate를 함께 만든다.

### BA-001

**Spring 모듈 구조와 실행 도구 고정** — P0 / `planned` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-000](#ba-000)
- 기능 ID: 해당 없음
- API: 해당 없음 (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: 해당 없음; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: apps/api · apps/ai · Java 21 · Python 3.13/uv · Gradle wrapper · module interfaces · 내부 계약 v1

구현 순서:

1. 지원되는 Spring/Java/Gradle 조합을 확인해 정확한 버전·wrapper checksum·image digest를 고정한다
2. 기능 package와 api/application/domain/infrastructure 의존 방향을 enforcement test로 제한한다
3. API·web·client·lock·Docker stage와 version=1 marker를 같은 scaffold slice로 맞춘다
4. 09-06 PM 검토 PM-022의 영향 계약·화면·실패 fixture를 검토하고 미해결이면 해당 경계를 확정하지 않는다
5. #10 D1~D4의 host·toolchain·image 제안을 FE와 합의한 뒤 실제 lock/stage에 반영한다

실패·안전 경계: 빈 app 또는 성공만 반환하는 task로 full Docker 모드를 열지 않는다. recommendation domain(apps/ai의 domain·pipeline, Spring의 recommendation.domain)은 DB·HTTP·LLM을 호출하지 않는다. 추천 계산을 Spring에 중복 구현하지 않는다(ADR-0006).

필수 검증:

- `BA-001-T1`: 새 clone에서 고정 도구로 build하고 checksum 불일치는 실패한다
- `BA-001-T2`: 다른 모듈 repository 직접 참조가 architecture test에서 실패한다
- `BA-001-T3`: marker 뒤 필수 stage·task·digest 누락은 hard fail한다

FE 인계·완료 증거: API 실행/health 주소, 버전 manifest, FE scaffold와 필요한 generation command. 실제 FE scaffold는 FE 인계물이다. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

PM 검토 연결: [09-06 발견 사항](../project/PM_REVIEW_2026-09-06.md) — PM-022.

이슈 검토: [#10 기반](../engineering/FOUNDATION_DECISIONS.md) · [#11 계약](../contracts/review-2026-09-06/README.md).

### BA-002

**PostgreSQL·Flyway·트랜잭션 기반** — P0 / `integration-ready` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-001](#ba-001)
- 기능 ID: 해당 없음
- API: 해당 없음 (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: 해당 없음; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: baseline migration · idempotency_records · revision schema version

구현 순서:

1. 운영과 같은 PostgreSQL major의 Testcontainers와 Flyway migration 계정을 준비한다
2. owner와 aggregate 중심 FK·unique·check·index를 각 기능 slice에서 추가하는 규칙을 만든다
3. 공통 lock 순서와 version 증가 책임 하나를 정하고 빈 DB 및 이전 schema upgrade fixture를 만든다

실패·안전 경계: 이미 적용된 Flyway 파일의 checksum을 수정하지 않는다. production data를 fixture로 사용하지 않는다.

필수 검증:

- `BA-002-T1`: 빈 DB와 직전 배포 schema에서 migration이 성공한다 — `FlywayMigrationIT.baselineMigrationIsAppliedOnPostgres17`, `FlywayMigrationIT.previousSchemaUpgradesToTheLatestVersion`. 후자는 직전 schema의 모든 table에 대표 row를 넣은 뒤 최신으로 올린다. 빈 schema만 올리면 default 없는 NOT NULL column이나 기존 중복 위의 unique index처럼 데이터가 있어야 실패하는 migration을 못 잡는다.
- `BA-002-T2`: check·unique·FK를 직접 SQL로 위반하면 거부된다 — `FlywayMigrationIT.ownerChecksRejectInvalidRows`, `FlywayMigrationIT.accountIdIsUniqueOnlyWhenPresent`, `FlywayMigrationIT.idempotencyRecordConstraintsRejectInvalidRows`, `FlywayMigrationIT.duplicateDeduplicationKeyIsRejected`, `FlywayMigrationIT.unknownStatusAndHalfLeaseAreRejected`
- `BA-002-T3`: transaction 중간 장애는 전체 rollback하며 구버전 app 호환성이 유지된다 — `IdempotencyGuardIT.failureRollsBackTheReservationAndTheEffect`, `IdempotencyGuardIT.completedCommandsReplayAndNeverRunTwice`, `IdempotencyGuardIT.theSameKeyWithAnotherRequestIsRejected`, `IdempotencyGuardIT.theSameKeyAndBodyOnAnotherResourceIsRejected`, `IdempotencyGuardIT.reservationsAreScopedByOwnerAndRoute`, `IdempotencyGuardIT.theStoredProjectionCanDropFieldsFromTheResponse`, `IdempotencyGuardIT.aProjectedReplayIsNotRehydratedByTheGuard`, `IdempotencyGuardIT.malformedKeysAndRoutesAreRejectedBeforeAnythingIsReserved`, `IdempotencyGuardIT.aProjectionWithANulCharacterIsRejected`, `IdempotencyGuardIT.anOversizedProjectionIsRejected`, `IdempotencyGuardIT.anExpiredRecordDoesNotReplayItsStoredResponse`, `IdempotencyGuardIT.anExpiredRecordDoesNotRejectADifferentRequest`, `IdempotencyGuardIT.aDeletedOwnerIsRejectedBeforeAnythingIsReserved`, `IdempotencyConfigurationIT.expiryComesFromTheConfiguredTtlAndTheInjectedClock`, `IdempotencyConfigurationIT.theConfiguredLockTimeoutIsAppliedToTheGuardedTransaction`, `IdempotencyConfigurationIT.aBlockedCommandFailsWithinTheBound`, `OwnerLifecycleLockIT.aSoftDeleteWaitsForTheGuardedCommandThatHoldsTheOwner`
- 단위 검증: canonical request hash `io.nullnull.identity.domain.RequestFingerprintTest`(golden vector로 parameter 정렬을 고정), duration property floor `io.nullnull.identity.application.IdempotencyGuardPropertiesTest` (둘 다 `test` suite)

`BA-002-T3`의 "구버전 app 호환성"에서 실제로 검증한 것과 하지 않은 것:

- 검증함: 이 slice의 migration이 additive다. 직전 schema를 데이터가 있는 상태로 올린 뒤 `information_schema.columns`로 기존 table·column·type·nullability가 그대로 남았고 row가 사라지지 않았음을 확인한다. 새 table만 추가하며 기존 column을 drop/alter하지 않으므로 구버전 app이 읽는 대상은 그대로다.
- 검증하지 않음: 구버전 application binary를 새 schema에 붙여 실제로 실행하는 배포 rehearsal. 배포 pipeline이 생기는 [BA-004](#ba-004) 이후에만 가능하며, 그전까지 이 항목을 통과로 쓰지 않는다.

열린 계약 질문(구현 안에 주석으로도 남김): guarded transaction의 lock 대기 상한이 만료되면 identity가 `CommandLockTimeoutException`을 던진다. API layer는 이를 retryable Problem으로 매핑해야 하지만, 공개된 `ProblemCode` 23개 중 "같은 session의 다른 명령이 진행 중"을 뜻하는 값이 없다. A1에는 HTTP endpoint가 없으므로 여기서 공개 code를 만들지 않고 [BA-003](#ba-003)에서 확정한다.

구현 산출물: migration `V002__owners.sql`·`V003__idempotency_records.sql`, `identity` module(domain `Owner`·`IdempotencyRecord`·`RequestFingerprint`, application port `OwnerRepository`·`IdempotencyRecordStore`·`LockWaitLimit`와 `IdempotencyGuard`·`CommandLockTimeoutException`, infrastructure의 JPA owner adapter·JdbcClient reservation store·`SET LOCAL lock_timeout` adapter), test fixture `OwnerFixtures`, property `nullnull.idempotency.ttl`(`APP_IDEMPOTENCY_TTL` 기본 `PT24H`, 최소 `PT1M`)과 `nullnull.idempotency.lock-timeout`(`APP_IDEMPOTENCY_LOCK_TIMEOUT` 제안 기본값 `PT3S`, 최소 `PT0.1S`). 잠금 순서는 owner lifecycle(`SELECT ... FOR UPDATE`) → idempotency reservation(`INSERT ... ON CONFLICT DO NOTHING` 뒤 `SELECT ... FOR UPDATE`) → command로 고정했고, 모든 대기는 transaction 단위 `lock_timeout`으로 상한을 둔다. 보존은 sweep job에 의존하지 않고 guard가 만료 row를 같은 transaction에서 삭제·재예약한다.

검증 실행과 report: `apps/api`에서 `./gradlew test integrationTest openapiContractTest recommendationTest`, report는 `apps/api/build/reports/tests/<suite>/index.html`이다. `verified`로 올리려면 CI report 경로·contract SHA·FE_DRI 재현 확인이 더 필요하다.

FE 인계·완료 증거: ERD diff, migration 적용 순서, rollback 호환 범위, local seed/reset 명령. 숫자 migration version은 실제 구현 때 충돌 없이 부여한다. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다. ERD 정련(`owners`의 anonymous account 금지와 locale/timezone 길이 check, `idempotency_records`의 예약 상태·route template·owner FK action·TTL index, `BackgroundJobStatus` enum)은 같은 slice에서 [ERD](../architecture/ERD.md)에 반영했다.

### BA-003

**HTTP 공통 정책·readiness·capability** — P0 / `integration-ready` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-001](#ba-001), [BA-002](#ba-002)
- 기능 ID: `FR-OPS-01`, `FR-OPS-02`, `FR-OPS-05`, `FR-OPS-10`
- API: `getLiveness`, `getReadiness`, `getDemoReadiness` (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: 해당 없음; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: Problem Details · requestId · capability projection · health query

구현 순서:

1. body/enum/unknown field와 pagination 상한을 계약에서 검증하고 안전한 Problem mapper를 만든다
2. liveness·DB readiness·선택 source readiness를 분리하며 server capability를 FE에 제공한다
3. route template 기반 로그와 cookie·query·원문 redaction을 filter부터 적용한다
4. [BA-002](#ba-002)가 남긴 `CommandLockTimeoutException`의 공개 계약을 확정한다. lock 대기 상한 만료를 retryable Problem으로 매핑하되 기존 `ProblemCode`로 충분한지, 새 code·status·CTA·Figma state가 필요한지 FE_DRI와 함께 정한다
5. 09-06 PM 검토 PM-019의 영향 계약·화면·실패 fixture를 검토하고 미해결이면 해당 경계를 확정하지 않는다

실패·안전 경계: 선택 provider 장애로 liveness를 실패시키지 않는다. readiness가 없는 기능은 준비 완료로 광고하지 않는다. 안전 불변식 OFF flag는 금지한다.

필수 검증:

- `BA-003-T1`: DB/source 각각의 장애가 올바른 health 범위에만 영향을 준다 — `HealthScopeIT.aDatabaseFailureAffectsReadinessOnly`(필수 database probe가 UNAVAILABLE이면 `/health/ready`가 503 `SOURCE_UNAVAILABLE`+`Retry-After: 5`, `/health/live`는 그대로 200 `UP`), `HealthScopeIT.anOptionalSourceFailureOnlyDegrades`(닿지 않는 추천 서비스는 200 `DEGRADED`이고 NOT_READY가 아니다), `HealthScopeIT.aDatabaseFailureDoesNotChangeTheDemoCapabilities`(infrastructure 장애가 product capability 목록을 바꾸지 않는다), `SystemEndpointsIT.readinessIsDegradedWhileOnlyTheOptionalRecommendationProbeFails`. probe를 DataSource 대신 교체하는 이유는 test class 주석에 적었다(DataSource를 깨면 Flyway·JPA가 함께 죽어 context가 뜨지 않는다).
- `BA-003-T2`: unknown 필드·초과 body·유효하지 않은 flag 조합을 거부한다 — `HttpPolicyIT.anUnknownBodyFieldIsRefused`(400 `INVALID_REQUEST`, 응답이 필드명도 값도 되풀이하지 않는다), `HttpPolicyIT.aDeclaredContentLengthOverTheBoundIsRefused`와 `RequestBodyLimitIT.aDeclaredContentLengthOverTheBoundIsRefused`(413), `RequestSizeLimitFilterTest.anOversizedDeclaredLengthNeverReachesTheChain`(선언 길이 초과는 chain을 아예 호출하지 않는다), `RequestBodyLimitIT.aChunkedBodyOverTheBoundIsRefused`(실제 Tomcat에 `Transfer-Encoding: chunked`로 보내 stream 중 413), `DemoCapabilityQueryTest.aFlagTurnedOnWithoutASourceIsRefused`(source 없는 `FEATURE_*`를 켜면 startup 실패), `AccessLogFilterTest.includingTheQueryInProductionIsRefused`(`APP_ACCESS_LOG_INCLUDE_QUERY=true`+production은 startup 실패)
- `BA-003-T3`: 모든 응답 오류에 안전한 code/requestId가 있고 secret canary가 없다 — `HttpPolicyIT.everyErrorPathCarriesACodeAndARequestId`(404·405·415·body 상한·unknown field·cursor 2종·422 2종·header 누락·406·500 2종 열세 경로 전부 `code`·`requestId`·`X-Request-ID`를 갖고 exception class 이름이 새지 않는다), `HttpPolicyIT.noCanaryReachesALogLineOrAResponseBody`(header·cookie·query·body 네 경로에 같은 canary를 넣고 root logger의 `ListAppender`로 모든 log line을 확인한다)
- 그 밖의 검증: `HttpPolicyIT.cursorFailuresKeepTheirOwnCodes`(`CURSOR_INVALID` 400 / `CURSOR_EXPIRED` 410), `HttpPolicyIT.aServiceConstraintViolationIsUnprocessable`(422 `VALIDATION_FAILED`, `fieldErrors[].field`가 내부 경로가 아닌 parameter 이름), `HttpPolicyIT.exhaustedOwnerCommandContentionIsInternalError`, `HttpPolicyIT.theAccessLogLineIsTheAllowedFieldsOnly`(허용 필드만·query 없음·MDC pattern이 console line에 requestId를 찍는다), `HttpPolicyIT.anUnmatchedRouteIsLoggedWithoutItsUri`, `HttpPolicyIT.aBodyUnderTheBoundIsAccepted`, `RequestBodyLimitIT.aChunkedBodyUnderTheBoundIsAccepted`, `OwnerCommandContentionIT`(흡수되는 경합과 소진되는 경합), `SystemEndpointsIT.demoReadinessPublishesProductCapabilitiesAndNotInfrastructureProbes`, `SystemContractTest.demoReadinessMatchesDemoReadinessSchema`(`DemoReadiness` schema 검증과 capability 이름)
- 단위 검증: `DemoCapabilityQueryTest`(vocabulary 고정, 두 namespace가 이름을 공유하지 않음, source 없는 capability는 UNAVAILABLE, overall 집계), `AccessLogFilterTest`, `RequestSizeLimitFilterTest`(설정 하한) — 모두 `test` suite

`BA-003`에서 실제로 검증한 것과 하지 않은 것:

- 검증함: 결함을 되돌리면 test가 빨개진다. unknown field 거부 해제, 선언 `Content-Length` 검사 제거, stream byte counter 제거, log correlation pattern 제거, route template 대신 raw URI 기록, `CursorException`·`ConstraintViolationException`·`CommandLockTimeoutException` 매핑 제거, source 없는 flag 허용, 필수 probe를 선택으로 취급, production query logging 허용, query string 상시 기록, lock 경합 재시도 제거, 빈 capability 목록을 READY로 집계 — 14개를 하나씩 넣어 해당 test가 실패하는 것을 확인하고 원본을 sha256으로 복원했다.
- 검증하지 않음: HTTP 정책을 실제 계약 endpoint로 확인하는 것. B01에는 request body를 받는 operation이 없어서 unknown field·body 상한·`ConstraintViolationException`은 integration suite 전용 route(`nullnull.testsupport.http`, scan root 밖)로 검증했다. 첫 실제 command endpoint를 만드는 slice가 같은 정책을 그 route에서 다시 확인한다.
- 검증하지 않음: `getDemoReadiness`의 `sessionCookie` 강제. session/auth layer가 아직 없으므로 지금은 누구나 호출할 수 있고, 노출 정보는 `/health/ready`가 이미 공개하는 것과 같은 종류다. [BA-010](#ba-010)이 session filter 뒤로 넣는다.
- 검증하지 않음: `APP_IDEMPOTENCY_LOCK_TIMEOUT`의 값. `PT3S`는 확정값이 아니라 **제안값**이고, 확인 가능한 근거는 측정이 아니라 구조 하나다 — `IdempotencyGuard`가 transaction 전체를 상한 2회 재시도하므로 caller가 겪는 최악은 `2 x PT3S = 6초`이고 그 뒤가 `INTERNAL_ERROR`다. `IdempotencyGuard.execute`를 부르는 production code가 아직 없어 "가장 느린 command"라고 부를 대상이 없다. 첫 실제 command endpoint를 만드는 slice가 그 command의 최악 소요를 **suite에 남는 test**로 재고 `PT3S`가 그것을 덮는 것을 확인하면 그때 확정값이 된다([ENVIRONMENT](../operations/ENVIRONMENT.md#3-backend-일반-설정)).

확정한 계약 결정:

- `CommandLockTimeoutException`은 24번째 `ProblemCode`를 만들지 않는다. 이 timeout은 owner row와 idempotency 예약을 잡는 동안, 즉 command가 실행되기 **전에만** 발생하고 transaction 전체가 rollback되므로 재시도가 idempotent해서가 아니라 구조적으로 안전하다. 그래서 `IdempotencyGuard`가 transaction 전체를 상한 2회까지 다시 시도해 흡수한다. 예산을 소진했다면 command가 owner row를 초 단위로 잡고 있었다는 뜻이고, command는 짧고 transaction 안 외부 호출은 금지이므로 이는 사용자가 재시도로 풀 수 없는 server 결함이다. `identity.api.IdentityProblemHandler`가 `INTERNAL_ERROR`(500, `retryable=false`)로 매핑하고 route template과 requestId만 담은 ERROR 한 줄을 남긴다.
- 승격 경로: 실제 측정에서 사용자에게 보이는 경합이 확인되면 `COMMAND_IN_PROGRESS`(409, `retryable=false`)를 CON ticket으로 추가한다. 새 code에는 FE CTA와 Figma state가 함께 필요하다(`CLAUDE.md`).
- 삭제 경로는 이 timeout에 닿지 않는다. 삭제는 owner를 먼저 soft delete하므로 경쟁하는 command는 `lockAlive`에서 401을 받는다.

FE 검토가 필요한 항목:

- **capability vocabulary**: `getDemoReadiness`의 `CapabilityStatus.name` 값 집합은 계약에 enum이 없어 server가 정한다. 현재 집합은 `live`, `replay`, `optimization` 셋뿐이고 `FR-OPS-02`의 "live/replay/optimization별 상태"가 유일한 문서 근거다. `DemoCapabilityQueryTest.theVocabularyIsPinned`가 고정하며, 이름을 더하거나 바꾸는 것은 FE-facing 계약 변경이므로 FE_DRI 승인이 필요하다.
- `/health/ready`의 `checks`(infrastructure: `database`·`jobs`·`recommendation`)와 `getDemoReadiness`의 `capabilities`(product)는 다른 namespace이고 이름을 공유하지 않는다. FE는 앞의 목록에 화면을 걸지 않는다.
- P0에서 세 capability는 모두 `UNAVAILABLE`이고 `overall`은 `NOT_READY`다. source가 없는 기능을 준비 완료로 광고하지 않는다는 뜻이며 실패 상태가 아니다.
- **caller 동작 변경**: unknown field 거부를 켰으므로 schema에 없는 필드를 담아 보내던 요청은 이제 400이다. 이전에는 조용히 무시됐다. 계약(`additionalProperties: false`, `x-nullnull-common-contract.bounds`)은 처음부터 거부를 약속하고 있었으므로 계약 변경이 아니라 계약 이행이지만, FE mock이 그 사이 여분 필드를 보내고 있다면 같은 시점에 고쳐야 한다. 같은 설정이 `apps/ai` 응답 parsing에도 적용되어, 내부 계약에 없는 필드가 오면 gateway가 `RecommendationUnavailableException`으로 degrade한다(계약 drift를 조용히 삼키지 않는다).

매핑하지 않은 exception과 담당 slice:

- `RecommendationUnavailableException`: 아직 request thread에서 gateway를 부르는 곳이 없다. 처음 호출하는 slice([BA-032](#ba-032)/[BA-051](#ba-051))가 fallback과 함께 공개 매핑을 정한다.
- `JobEnqueueException`: 같은 이유로 처음 request에서 enqueue하는 slice가 정한다.
- `JobLockTimeoutException`, `StaleLeaseException`: worker 전용이며 답할 caller가 없다는 것이 그 자체의 설계다. 매핑하지 않는다.

PM-019 검토 결과: operation별 401/429/`default`/403 선언 보강은 이 slice에서 하지 않는다. 생성 TypeScript client가 바뀌어 Frontend가 첫 green `docker-integration` 도중에 재생성을 강요받기 때문이다. [IMPLEMENTATION_PLAN](../engineering/IMPLEMENTATION_PLAN.md#공동-실행-id)의 `CON-006`으로 등록했고 착수는 Frontend PR #17 병합 다음 PR이다. 문서 전역 규칙(`x-nullnull-common-contract`의 `protectedErrors`·`rateLimitErrors`·`bounds`)은 이미 계약에 있으므로 이 slice는 그 약속을 server가 지키게 만드는 쪽만 했다.

구현 산출물: `shared.http`의 `RouteTemplate`·`AccessLogFilter`·`RequestSizeLimitFilter`·`RequestBodyTooLargeException`, `shared.problem`의 `GlobalExceptionHandler` 확장(cursor·constraint violation·body 초과 매핑, catch-all이 raw URI 대신 route template을 남김, advice 순서 고정)과 `ProblemResponses.write`, `identity.api.IdentityProblemHandler`, `IdempotencyGuard`의 lock 경합 bounded retry(`TransactionTemplate`으로 attempt마다 새 transaction), `operations`의 `DemoCapabilities`·`DemoCapabilityQuery`·`DemoReadinessController`·`CapabilityStatusResponse`, property `spring.jackson.deserialization.fail-on-unknown-properties`·`logging.pattern.correlation`·`nullnull.http.max-request-body-bytes`(`APP_MAX_REQUEST_BODY_BYTES` 기본 `262144`, 최소 `4096`)·`nullnull.http.access-log.include-query`(`APP_ACCESS_LOG_INCLUDE_QUERY` 기본 `false`)·`nullnull.capabilities.*`(`FEATURE_LIVE_DATA`·`FEATURE_REPLAY_MODE`·`FEATURE_OPTIMIZATION_ITEM` 기본 OFF). Problem body의 `instance`는 실제 request URI를 유지하고 log만 route template으로 제한한다. 두 대상의 독자가 다르다는 근거는 `RouteTemplate`과 `ProblemResponses` 주석에 적었다([PRIVACY](../security/PRIVACY_REQUIREMENTS.md#8-로그관측) 8절).

검증 실행과 report: `apps/api`에서 `./gradlew test integrationTest openapiContractTest recommendationTest`, report는 `apps/api/build/reports/tests/<suite>/index.html`이다. `verified`로 올리려면 CI report 경로·contract SHA·FE_DRI 재현 확인이 더 필요하다.

FE 인계·완료 증거: bootstrap default/degraded examples, Problem→CTA 표, 환경별 capability fixture. Live OFF shell과 실제 Live 완료를 구분한다. capability 이름 집합(`live`·`replay`·`optimization`)은 계약에 enum이 없어 server가 정했으므로 FE_DRI 승인이 필요하고, `/health/ready`의 infrastructure check 이름과 섞지 않는다. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

PM 검토 연결: [09-06 발견 사항](../project/PM_REVIEW_2026-09-06.md) — PM-019.

### BA-004

**계약 생성·중요 기능 상시 CI 구성** — P0 / `in-progress` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-001](#ba-001), [BA-002](#ba-002), [BA-003](#ba-003)
- 기능 ID: 해당 없음
- API: 해당 없음 (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: 해당 없음; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: docs-contract · docker-integration/api-quality/ai-quality · api-quality/ai-quality GitHub workflow · test reports

구현 순서:

1. OpenAPI breaking diff·TS client 재생성·event AJV·문서 연결 검사를 고정한다
2. API 단위·DB·contract·추천 safety(apps/ai pytest, ai-quality)·privacy suite를 docker-integration 안에 묶고, 경로 filter workflow api-quality/ai-quality는 조기 피드백으로만 둔다
3. 필수 test ID report·실행 개수·skip·timeout·artifact 누락을 실패로 집계하는 runner를 실제 test와 함께 만든다
4. 09-06 PM 검토 PM-008, PM-016, PM-018, PM-019, PM-021, PM-024의 영향 계약·화면·실패 fixture를 검토하고 미해결이면 해당 경계를 확정하지 않는다
5. #10 D2/D3 generator·schema·scan·infra gate 및 #11 타입/fixture 검증을 실제 tooling stage로 이전한다

실패·안전 경계: 중요 기능 suite는 경로 필터와 관계없이 모든 main PR에서 실행한다. 구현 전 missing suite를 green placeholder로 대체하지 않는다.

필수 검증:

- `BA-004-T1`: 실패 test를 의도적으로 넣은 PR에서 두 required gate 중 해당 gate가 빨갛다
- `BA-004-T2`: 0건 실행·skip·리포트 삭제·하위 command 실패 은폐를 거부한다
- `BA-004-T3`: 외부 egress가 차단된 실제 Compose에서 fixture만으로 재현한다

FE 인계·완료 증거: 생성 client 경로와 contract SHA, MSW 예시, API/FE report 연결 규칙. required check 이름은 정확히 두 개로 유지한다. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

PM 검토 연결: [09-06 발견 사항](../project/PM_REVIEW_2026-09-06.md) — PM-008, PM-016, PM-018, PM-019, PM-021, PM-024.

이슈 검토: [#10 기반](../engineering/FOUNDATION_DECISIONS.md) · [#11 계약](../contracts/review-2026-09-06/README.md).

A4 Backend/AI 구현 증거:

- OpenAPI 비교는 `docs-contract`의 PR event에서만 `origin/main`과 현재 계약을 비교한다. `oasdiff-action/breaking` release SHA를 고정하고 `fail-on: WARN`, `review: false`로 실행한다.
- `scripts/check_test_reports.py`는 네 Gradle suite의 실제 testcase·summary count·실패/error/skip·필수 ID와 report freshness를 확인한다. ready 카드의 모든 acceptance ID를 검사하며 카드별 예외 목록은 없다.
- `BA-004-T1` 로컬 재현: `scripts/tests/test_check_test_reports.py`의 `ReportTests.test_BA_004_T1_failure_error_skip_counts_and_children`, `WrapperExecutionTests.test_BA_004_T1_actual_wrapper_propagates_command_failure`. 실제 wrapper의 하위 command exit 42를 보존한다. PR 생성·원격 required gate 실행 증거는 아직 없다.
- `BA-004-T2`: `ReportTests`, `WrapperExecutionTests.test_BA_004_T2_actual_wrapper_rejects_bad_evidence_and_suppression`, `WorkflowWiringTests.test_BA_004_T2_shipping_wrapper_does_not_suppress_quality_commands`. shell wrapper는 실제 실행하고 Docker/scaffold만 test double로 바꾼다. 실패·skip·suite XML 누락·오래된 보고서가 있는 `|| true` 변이를 거부한다. 모든 command 오류를 XML만으로 추론한다는 보장은 하지 않는다.
- 실제 offline Compose에서 context cache에 누적된 Hikari pool로 SQLSTATE 53300을 재현했다. Gradle test worker의 cache를 1개로 제한해 네 suite를 실행하고, 이 제한 제거 변이도 실제 Compose에서 검사한다. 운영 pool 크기·worker budget은 그대로다.
- 로컬 report: `.artifacts/ba-004/scripts-tests.log`, `.artifacts/ba-004/mutations.json`, `.artifacts/ba-004/oasdiff-breaking.log`. 네 Java suite는 `apps/api/build/test-results/{test,integrationTest,openapiContractTest,recommendationTest}/TEST-*.xml`이다. CI 검사는 `docs-contract` unittest와 `api-quality`/통합 wrapper의 집계 runner로 등록했다.
- A4는 Backend/AI 잔여 CI 범위다. `BA-004-T3`는 C1 source stub의 실제 offline Compose 실행까지 미완료이며 TS client·MSW·client diff는 Frontend 범위다. 따라서 **A4 구현 완료와 BA-004 카드 전체 완료를 구분**하고 카드는 `in-progress`로 유지한다. T3와 Python CI test ID의 report 연결을 완료하기 전에 `integration-ready`로 올리면 집계 runner가 누락 ID를 거부한다.

### BA-005

**영속 job과 수집·추천·삭제 실행 격리** — P0 / `integration-ready` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-002](#ba-002), [BA-003](#ba-003), [BA-004](#ba-004)
- 기능 ID: 해당 없음
- API: 해당 없음 (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: 해당 없음; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: background_jobs · lease_until · locked_by · attempt · deduplication key

구현 순서:

1. 원자 claim·heartbeat·lease 만료 재인수·bounded retry·dead-letter를 구현한다
2. collector·optimization·deletion executor와 동시 실행 budget을 분리한다
3. 완료 쓰기에 현재 lease token/attempt 조건을 넣어 이전 worker의 늦은 결과를 차단한다

실패·안전 경계: job payload는 domain ID만 저장한다. 외부 호출은 긴 DB lock 밖에서 한다. worker 재시작이 새 일정 mutation을 만들지 않는다.

필수 검증:

- `BA-005-T1`: 두 worker가 같은 job을 동시에 commit하지 못한다 — `JobLeaseIT.twoWorkersRacingForOneJobProduceOneClaimAndOneCommit`. 첫 worker가 claim transaction을 연 채로 둘째가 claim하면 `FOR UPDATE SKIP LOCKED`가 즉시 빈 결과를 돌려준다(막히지 않는다). 이어서 자기가 소유자라고 믿는 둘째의 unit of work와 완료를 모두 거부하고 그 domain write는 rollback한다.
- `BA-005-T2`: lease 만료 후 이전 worker의 commit을 거부한다 — `JobLeaseIT.anExpiredLeaseCannotCommitAfterAnotherWorkerRetookTheJob`. `REC-JOB-01`과 같은 test다. lease 만료 → 둘째 worker 재인수(attempt 2) → 첫 worker의 `JobContext.transactional` domain write·완료·heartbeat·retry가 모두 `StaleLeaseException`이고 owner row는 정확히 1개다.
- `BA-005-T3`: poison job 재시도 상한과 삭제 우선 처리 중 API 지연 격리를 검증한다 — `JobWorkerIT.aPoisonJobStopsAtTheCeilingAndDegradesTheJobsCapability`(attempt 3에서 FAILED, `last_error_code`, dead-letter ERROR 한 줄에 key·payload 없음, readiness `jobs`만 DEGRADED, `/health/ready` 200), `JobIsolationIT.aSaturatedExecutorDoesNotDelayAnotherTypeOrTheApi`(포화된 executor가 handler를 잡고 있는 동안 다른 type의 job 완료 지연과 `/health/ready` 지연을 실제로 측정한다).
- port·worker 그 밖의 검증: `JobQueueIT`(enqueue transaction 강제, deduplication 충돌, 다른 type의 key 점유 거부, handler 없는 type 거부, attempt 상한 거부, back-off 전 claim 없음, crash 후 lease 만료 재인수, stale lease의 모든 쓰기 거부, payload 왕복, TTL sweep 정확도, commit 직전 lease 재확인으로 중복 실행 차단, lease 만료 재인수의 attempt 상한과 `LEASE_EXPIRED` dead letter, 완료 job의 dedup key 해제, 비기본값 `nullnull.idempotency.lock-timeout`으로 측정한 TTL sweep의 fast fail 상한), `JobCrashRetryIT`(attempt가 남은 crash는 abandoned sweep이 아니라 `CLAIM_EXPIRED_LEASE`가 재인수하고, 상한에서야 handler 자신의 code로 dead letter가 된다), `JobWorkerIT`(정상 handler의 lease 검증 commit, unit of work 밖 쓰기 거부, unit of work 안 `REQUIRES_NEW` 거부, dead letter 없을 때 probe READY), `JobAbandonedLeaseIT`(hang한 worker의 job이 상한에서 dead letter가 되고 probe가 DEGRADED, row 경합은 attempt를 쓰지 않음), `JobConfigurationIT`(비기본값 `lock-timeout`이 claim·failAbandoned·assertLeaseHeld·heartbeat·complete·retry·deadLetter·deleteFinishedBefore 8개 statement 모두에 걸림, 막힌 쓰기의 fast fail, unit of work의 lease 상한), `FlywayMigrationIT`(완료 job은 dedup key를 잡지 않고, 채워진 previous schema가 V004로 올라간다), `SystemEndpointsIT`(worker가 꺼져 있으면 `jobs` DEGRADED)
- 단위 검증: `JobPayloadTest`(원문·좌표·secret 거부), `JobRequestTest`, `JobPropertiesTest`(단위 없는 숫자 = 밀리초 함정, back-off 계단, `enabled` 누락 시 startup 실패), `JobHandlerRegistryTest`(type 중복·미등록), `JobConnectionBudgetTest`(worker 최악 connection 수요 공식과 거부 message), `JobWorkerStartupTest`(그 검사가 실제로 `start()`에 걸려 있다), `ArchitectureRulesTest.jobHandlersNeverTouchTheDatabaseDirectly`(handler가 JDBC·EntityManager를 직접 만지지 못한다) — 모두 `test` suite

`BA-005`에서 실제로 검증한 것과 하지 않은 것:

- 검증함: 결함을 되돌리면 test가 빨개진다. claim의 `FOR UPDATE SKIP LOCKED` 제거, 완료의 lease 조건 제거, `JobContext.transactional`의 lease 재확인 제거, attempt 상한 off-by-one, unit-of-work guard 무력화를 각각 넣어 해당 test가 실패하는 것을 확인하고 원본을 복원했다. 같은 방식으로 `FAIL_ABANDONED`의 `attempt_count >= max_attempts` 제거(`JobCrashRetryIT`가 attempt 1에서 dead letter를 잡아낸다), `ExpiredIdempotencyRecordEraser`의 주입 값 하드코딩(sweep이 3.04초를 기다려 주입한 1초 상한을 벗어난다), `JdbcJobQueue`의 `applyToCurrentTransaction` 8개 제거(`JobConfigurationIT`가 멈추지 않고 30초 timeout으로 실패한다), worker의 contention catch 제거(`JobAbandonedLeaseIT`의 `last_error_code`가 `HANDLER_ERROR`가 된다)도 확인했다.
- 검증하지 않음: 여러 process의 worker. 위 test는 한 JVM 안의 두 connection으로 재인수를 재현하며, 이는 lease가 DB row 조건으로만 판정되므로 같은 의미다. 실제 다중 task 배포 확인은 ECS가 생기는 [BA-006](#ba-006) 이후에만 가능하다.
- 남은 위험, handler slice가 책임진다: `JobUnitOfWorkGuard`는 handler thread에서 시작된 transaction만 본다. 짝이 되는 `ArchitectureRulesTest.jobHandlersNeverTouchTheDatabaseDirectly`는 **직접 참조만** 금지하므로, handler가 `@Transactional`이 전혀 없는 평범한 협력 class를 통해 `jdbc.sql("INSERT ...").update()`를 실행하면 rule도 guard도 통과하고 lease 밖에서 commit된다(측정함). 한 단계 건너뛴 협력자까지 막는 검사는 아직 없으므로, 첫 handler를 붙이는 [BA-012](#ba-012)가 handler의 모든 쓰기를 `JobContext.transactional` 안의 application service로 보내는 것을 slice 자체의 acceptance로 잡는다.
- test로 지킬 수 없어 수치만 남기는 것: claim을 두 statement로 나눈 결정. 다시 OR 하나로 합쳐도 동작이 같아서 실패하는 기능 test가 없고, plan assertion은 PostgreSQL version과 data에 취약하다. 손으로 잰 근거는 — 완료 row 200,000개에서 V001의 OR 형태 Seq Scan 10.24ms, 분할한 첫 statement Index Scan 0.021ms, V004 이후 OR 형태 BitmapOr 0.022ms. 합치는 변경은 이 수치를 다시 재는 것을 조건으로 한다.
- backlog에서 성능이 달라지는 두 statement: `CLAIM_EXPIRED_LEASE`와 `FAIL_ABANDONED`는 `status = 'RUNNING'`을 부분 index로 좁힐 수 없어 미완료 row 전체를 읽는다. 같은 PostgreSQL에 READY row 100,000개를 더한 뒤 측정: 둘 다 `background_jobs_outstanding_key_idx` Bitmap Index Scan으로 미완료 약 100,009건을 읽고 약 100,006건을 filter로 버리며 9.6ms, claim마다가 아니라 poll tick마다다. 지금은 index를 추가하지 않는다([ERD](../architecture/ERD.md)의 초기 index 목록은 실제 plan을 근거로만 유지한다). 미완료 job이 이 규모로 쌓이는 것이 관측되면 `(type, lease_until) WHERE status = 'RUNNING'`을 추가할 근거가 된다.
- 미구현: handler는 아직 하나도 없다. 삭제 job은 B02/BA-012, collector는 B03, optimization은 B06에서 이 SPI로 붙는다. 그때까지 worker는 보존 sweep만 돌린다.
- handler를 붙이는 slice가 함께 정해야 하는 값: worker의 최악 동시 connection 수요 `2 x slots + types + 1`에 readiness 여유분 2를 더한 값이 `NULLNULL_DB_POOL_MAX` 이하가 아니면 startup이 실패한다([ENVIRONMENT](../operations/ENVIRONMENT.md#3-backend-일반-설정)). BA-012의 실제 type 하나와 기본 concurrency 2는 pool 8이 필요해 기본 10에 들어가며, 이후 type 추가 때 다시 계산한다.
- V004는 dedup unique를 미완료 row 부분 index로 바꾼다. `ON CONFLICT (deduplication_key)`를 쓰는 이전 binary의 enqueue는 이 migration 뒤 실패하므로, handler를 추가하는 첫 slice는 이 migration 이후에 배포한다.

FE 인계·완료 증거: QUEUED/RUNNING/FAILED 예시와 retryable 의미, polling·timeout은 취소가 아니라는 인계 설명. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

### BA-006

**로컬 Docker와 최소 staging 기반** — P0 / `planned` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-001](#ba-001), [BA-002](#ba-002), [BA-003](#ba-003), [BA-004](#ba-004)
- 기능 ID: `NFR-OPS-01`
- API: 해당 없음 (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: 해당 없음; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: compose.integration.yml · ai service · infra/CDK · OIDC · immutable images

구현 순서:

1. 로컬 web→API→apps/ai→PostgreSQL hello와 seed를 단일 wrapper에 연결한다
2. 승인된 계정·비용·domain이 준비되면 CDK network/data/API/web edge의 최소 staging을 만든다
3. OIDC exact subject와 runtime secret 주입을 검증하고 deployment 역할과 관찰 역할을 나눈다
4. 09-06 PM 검토 PM-022의 영향 계약·화면·실패 fixture를 검토하고 미해결이면 해당 경계를 확정하지 않는다
5. #10 D5의 createDemoSession→issueCsrfToken→getCurrentOwner 실제 hello와 seed를 검증한다

실패·안전 경계: 계정·비용·secret 미확정은 외부 배포 blocker이며 로컬 구현까지 막지 않는다. 실제 설정·배포 완료를 문서만으로 표시하지 않는다.

필수 검증:

- `BA-006-T1`: 정규화 Compose의 internal network와 outbound-deny probe가 통과한다
- `BA-006-T2`: frontend bundle·image layer·log에 secret이 없다
- `BA-006-T3`: OIDC의 잘못된 repo/environment subject가 거부된다

FE 인계·완료 증거: 로컬 URL·seed·staging environment 준비 상태와 안전한 public config 목록. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

PM 검토 연결: [09-06 발견 사항](../project/PM_REVIEW_2026-09-06.md) — PM-022.

이슈 검토: [#10 기반](../engineering/FOUNDATION_DECISIONS.md) · [#11 계약](../contracts/review-2026-09-06/README.md).

## B02 · 익명 세션·프로필·삭제

사용자 데이터가 생기기 전에 owner와 cleanup 경계를 닫는다.

### BA-010

**익명 owner·session·CSRF 복구** — P0 / `integration-ready` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-002](#ba-002), [BA-003](#ba-003), [BA-004](#ba-004)
- 기능 ID: `FR-ONB-01`, `FR-SES-01`, `FR-SES-02`, `FR-SES-03`, `NFR-SEC-01`
- API: `createDemoSession`, `issueCsrfToken` (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: `388:257`; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: owners · demo_sessions · demo_session_csrf_tokens

구현 순서:

1. __Host cookie·hash-only token·idle/absolute expiry·revocation으로 owner를 확정한다
2. same-origin CSRF bootstrap와 tab별 독립 token 최대 5개를 구현한다
3. valid cookie 재시도 수렴과 cookie 이전 orphan bootstrap 15분 cleanup을 구현한다
4. 09-06 PM 검토 PM-017의 영향 계약·화면·실패 fixture를 검토하고 미해결이면 해당 경계를 확정하지 않는다
5. #10 D5의 최소 bootstrap을 B01에 인계하되 전체 세션 안전 경계 완료는 B02에서 검증한다

실패·안전 경계: ownerId를 client가 정하지 않는다. 최초 bootstrap에 nullable-owner idempotency 예외를 만들지 않는다. 일반 mutation은 401 뒤 자동 재실행하지 않는다.

필수 검증:

- `BA-010-T1`: owner A/B/C 교차 조회·변경과 CSRF/Origin 위조를 거부한다
- `BA-010-T2`: 미만료 token 5개를 함께 유지하고 6번째 발급 시 last_used_at 기준 LRU token을 회수한다
- `BA-010-T3`: expiry·rotation·response loss 이후 안전한 bootstrap으로 복구한다

FE 인계·완료 증거: 쿠키/헤더 examples, 최초/refresh/만료/두 tab E2E fixture와 401 복구 순서. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

BA-010 구현 증거 (local·full Docker: Java 276 / 114 / 11 / 19, 0 fail/error/skip; Playwright 5건 통과):

- `SessionSafetyIT.isolation/origins/ambiguousCredentials` 및 `SessionContractTest.securityParity`: cookie 유도 owner, 타 session CSRF, Origin·중복 credential과 operation 보안 정책을 검증한다. `/me` production route는 BA-011에서 추가하며 현재 owner 분리는 test-support route로 검사한다.
- `SessionSafetyIT.lru/concurrentTokens/lockContention`, `SessionTimeIT.csrfExpiry`: 5개 유지·6번째 LRU·동시 revoke·token 만료를 검증한다.
- `SessionSafetyIT.bootstrapAndOrphans/expiration/revokedRetention`, `SessionTimeIT.sliding/cutoffs`, `SessionContractTest.responses`: bootstrap 수렴·정리·idle/absolute·secure cookie·CSRF expiry response를 검증한다.
- report: `apps/api/build/test-results/integrationTest/TEST-io.nullnull.identity.SessionSafetyIT.xml`, `TEST-io.nullnull.identity.SessionTimeIT.xml`; `apps/api/build/test-results/openapiContractTest/TEST-io.nullnull.contract.SessionContractTest.xml`. 설정은 `test`의 `SessionPropertiesTest`, migration은 기존 `FlywayMigrationIT`에서 검사한다.
- Playwright `apps/web/e2e/session.spec.ts`: 실제 API transport bootstrap/refresh/다중 탭; HTTP Compose에서 Secure cookie 명시 전달. UI keyboard/focus는 기존 shell 검사이며 세션 화면 구현·브라우저 Secure cookie 수락 검증과 구분한다.
- 공개 shape는 유지하고 LRU 및 response `expiresAt`의 CSRF 만료 의미를 명시했다. V005는 두 table 추가이며 V001~V004를 변경하지 않는다. absolute P90D·CSRF PT2H·touch PT1M은 제안값이다.
- PM-017의 서버 만료/LRU/GC 경계는 구현했다. cookie 유실 안내와 401 뒤 mutation 재실행 금지는 FE 화면 검수로 넘긴다. staging·상대 재현 확인 전 `verified`나 이슈 완료로 쓰지 않는다.

PM 검토 연결: [09-06 발견 사항](../project/PM_REVIEW_2026-09-06.md) — PM-017.

이슈 검토: [#10 기반](../engineering/FOUNDATION_DECISIONS.md) · [#11 계약](../contracts/review-2026-09-06/README.md).

### BA-011

**프로필·locale·onboarding·active trip** — P0 / `integration-ready` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-010](#ba-010)
- 기능 ID: `FR-ONB-02`, `FR-ONB-03`, `FR-PRO-01`, `FR-PRO-02`
- API: `getCurrentOwner`, `updatePreferences` (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: `388:277`, `388:321`, `422:2925`; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: owners.locale/timezone/onboarding_completed/active_trip_id

구현 순서:

1. KO/EN 선택·저장·재조회와 지원하지 않는 locale validation을 구현한다
2. onboarding 완료와 active trip은 owner 범위로 갱신한다
3. guest/login 준비 중·데이터 안내에 필요한 projection을 제공한다
4. 09-06 PM 검토 PM-001, PM-002, PM-006, PM-017의 영향 계약·화면·실패 fixture를 검토하고 미해결이면 해당 경계를 확정하지 않는다
5. #10 D5의 getCurrentOwner 최소 읽기를 B01에 인계하고 locale/profile 전체 완료는 B02에서 검증한다

실패·안전 경계: JA/ZH·정식 계정 로그인은 P0 요청을 보내지 않는 비활성 상태다. 다른 owner 또는 삭제된 trip을 active로 설정하지 못한다.

필수 검증:

- `BA-011-T1`: KO/EN 재조회·format fixture와 unsupported locale 거부를 검증한다
- `BA-011-T2`: active trip owner 위조와 삭제된 trip 참조를 거부한다
- `BA-011-T3`: onboarding 반복 완료가 중복 domain 효과를 만들지 않는다

FE 인계·완료 증거: A-2/S14 정상·empty·disabled·refresh states; UI-only intro skip/route 복구는 FE 담당. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

BA-011 구현 증거 (local·full Docker Java 276 / 121 / 13 / 19, 0 fail/error/skip; Playwright 6건 통과):

- `OwnerPreferencesIT.localeRoundTrip/expiredCookie`: KO/EN·timezone 저장/재조회, unsupported locale 422 field error, 인증을 검사한다.
- `OwnerPreferencesIT.isolationAndUnavailableTrip`, `OwnerPreferencesConcurrencyIT.ownerBoundTripPort`: 실제 cookie owner 분리와 TripLookup에 전달하는 owner 경계를 검사한다. production TripLookup은 BA-030 전까지 항상 부재이며, positive/foreign/deleted trip port 동작은 test override다. 실제 trip table 검증으로 쓰지 않는다.
- `OwnerPreferencesIT.mergePatchAndRepeat/malformedAndAtomic`, `OwnerPreferencesConcurrencyIT.lockedReadPreservesConcurrentChange`: null/absent, unknown/type/content-type, 실패 원자성, owner 잠금 뒤 최신 필드 보존, 반복 onboarding의 owner row version 불변을 검사한다.
- `OwnerContractTest.schemas/mediaType`는 OwnerProfile·Problem과 merge-patch 415를 검사한다. `SessionContractTest.securityParity`가 실제 route/operation/security를 함께 검사한다.
- report: `apps/api/build/test-results/integrationTest/TEST-io.nullnull.identity.OwnerPreferencesIT.xml`, `TEST-io.nullnull.identity.OwnerPreferencesConcurrencyIT.xml`; `apps/api/build/test-results/openapiContractTest/TEST-io.nullnull.contract.OwnerContractTest.xml`. Playwright는 `apps/web/e2e/session.spec.ts`의 BA-011 transport 검사이며 shell keyboard/focus와 함께 실행한다.
- 공개 shape·migration은 그대로다. PM-001/002/017의 서버 KO/EN/guest 상태를 구현했고 UI-only intro·비활성 CTA·cookie 유실 안내는 FE 검수다. PM-006 taxonomy는 BA-030/031의 trip 관심사 범위이며 `/me`에 새 field를 만들지 않았다.

PM 검토 연결: [09-06 발견 사항](../project/PM_REVIEW_2026-09-06.md) — PM-001, PM-002, PM-006, PM-017.

이슈 검토: [#10 기반](../engineering/FOUNDATION_DECISIONS.md) · [#11 계약](../contracts/review-2026-09-06/README.md).

### BA-012

**세션 삭제 receipt·TTL·복원 후 재삭제** — P0 / `integration-ready` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-005](#ba-005), [BA-010](#ba-010), [BA-011](#ba-011)
- 기능 ID: `FR-OPS-09`, `FR-SES-04`
- API: `deleteCurrentSession`, `getDeletionRequest` (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: 해당 없음; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: deletion_requests · deletion_tombstones · background_jobs · revoked session receipt

구현 순서:

1. 한 transaction으로 session/CSRF revoke·receipt·tombstone·job을 생성한다
2. revoked cookie와 같은 key에 한해 24시간 동일 receipt를 재생하고 상태 header token은 7일만 허용한다
3. owner별 삭제 대상 registry를 만들고 새 테이블·cache·학습 export 추가 때 cleanup을 함께 등록한다
4. 09-06 PM 검토 PM-002, PM-017, PM-018의 영향 계약·화면·실패 fixture를 검토하고 미해결이면 해당 경계를 확정하지 않는다

실패·안전 경계: 202는 접수이고 완료가 아니다. status token은 domain 읽기 권한이 없다. 삭제 중 새 데이터 생성과 worker 완료 쓰기를 차단한다. `IdempotencyGuard`는 replay 시 저장된 projection을 그대로 돌려주고 아무것도 재생성하지 않는다. `DeletionReceipt.statusToken`처럼 저장하지 않는 required 필드는 caller가 receipt ID/expiry에서 다시 유도해 응답에 채워야 하며, 그러지 않으면 replay 응답이 schema를 위반한다(현재 동작은 `IdempotencyGuardIT.aProjectedReplayIsNotRehydratedByTheGuard`가 고정한다).

필수 검증:

- `BA-012-T1`: 응답 유실 뒤 같은 receipt만 재생하고 revoked cookie의 다른 API는 401이다
- `BA-012-T2`: 재시도·partial failure·owner 삭제 경합에서 데이터가 부활하지 않는다
- `BA-012-T3`: backup 복원 뒤 tombstone 재적용 전 public traffic이 열리지 않는다

FE 인계·완료 증거: S14 삭제 확인·상태 polling·receipt 분실/만료·부분 실패 예시. 보존 기간 안내는 privacy 문서와 동일하게 전달한다. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

BA-012 구현 증거:

- `DeletionIT`가 `BA-012-T1`의 동일 receipt 재생, revoked cookie의 다른 key·route 401, header-only 상태 token의 정상/오류/정확한 7일 만료 경계, hash-only 저장과 enqueue 실패 원자 rollback을 실제 PostgreSQL에서 검사한다.
- `DeletionJobIT`가 `BA-012-T2`와 `REC-SEC-03`의 부분 실패→재시도→완료, eraser 비중첩, owner profile 비부활을 실제 worker로 검사한다. 첫 production handler가 추가되어 기존 BA-005 synthetic handler 테스트의 pool은 각 context의 실제 type/slot 수식만큼 명시했다. 운영 기본 type 1개·concurrency 2는 reserve 포함 최소 pool 8이고 기본 10 안에 든다.
- `DeletionIT.tombstoneReappliesRestoredOwnerData`와 `TombstoneReapplierTest`가 `BA-012-T3`의 restore 재삭제와 web lifecycle 이전 fail-closed 시작을 검사한다. owner hard delete는 tombstone 21일 뒤에도 30일 revoked session과 idempotency row가 없어질 때까지 기다린다.
- `FlywayMigrationIT`는 V005 populated schema→V006 upgrade를, `SessionContractTest`는 두 operation의 route/security/response schema를 검사한다. report는 `apps/api/build/test-results/{test,integrationTest,openapiContractTest,recommendationTest}/*.xml`이며 Playwright transport는 `apps/web/e2e/session.spec.ts`에 있다.
- token·transaction·worker·lifecycle·TTL 가드 변이 14종은 모두 RED였고 scratch backup 복원 SHA256이 일치한다. 로컬 증거는 `.artifacts/ba-012/token-mutations.json`과 `.artifacts/ba-012/mutations.json`이다.
- 전체 Docker gate는 Java 280/127/13/19, AI pytest 410, web unit 224, Playwright 36을 failures/errors/skipped 0으로 실행했고 generated client diff·npm audit·egress-denied·readiness까지 통과했다. 공유 PostgreSQL에서 V006 FK가 드러낸 기존 BA-005 fixture 정리 순서는 tombstone→receipt→owner 순으로 보강했다.

PM 검토 연결: [09-06 발견 사항](../project/PM_REVIEW_2026-09-06.md) — PM-002, PM-017, PM-018.

## B03 · 공통 데이터·KTO·장소·비교

추천에 필요한 source·relation을 Live 탭과 분리한다.

### BA-020

**공통 source registry·adapter·쿼터·drift** — P0 / `integration-ready` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-005](#ba-005), [BA-010](#ba-010)
- 기능 ID: `FR-DAT-04`, `FR-OPS-03`, `FR-OPS-04`
- API: 해당 없음 (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: 해당 없음; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: source_registry revisions · collector_runs · api_ingest_logs · quality incidents

구현 순서:

1. 공식 source/operation·승인·license·schema·TTL·quota를 versioned registry에 기록한다
2. HTTP timeout·bounded retry/jitter·429·circuit·request coalescing·hostname allowlist를 공통화한다
3. schema/type/range/time drift와 공식 incident window를 검증해 quarantine한다

실패·안전 경계: 외부 원문 body/URL/키를 log에 남기지 않는다. 미승인 source는 비활성이고 stale threshold가 미정이면 신선한 값으로 판정하지 않는다.

필수 검증:

- `BA-020-T1`: `ProviderKitTest`·`CollectorRunRecorderTest`·`SourceRegistryIT`가 429/timeout/circuit·schema/enum/range drift·incident·immutable revision hash·host/config fail-close를 합성 provider와 PostgreSQL로 검증한다.
- `BA-020-T2`: `SourceRegistryIT`가 KST 일일 quota의 60/80/90% 경보·100% 초과 거부 및 다른 source collector run 재사용 거부를 실제 PostgreSQL에서 검증한다.
- `BA-020-T3`: `SourceRegistryIT.slowProviderDoesNotBlockApiRequests`가 네 개의 지연 provider call 중에도 readiness와 owner `/me` 요청이 즉시 처리되는지를 검증한다.

구현·검증 증거:

- `V007__sources.sql`은 source registry/revision/quality incident/collector run/safe ingest ledger를 만들고, immutable revision hash에 approval·quota·license review·scope·retention·refresh·schema·stale·contest use를 포함한다. KTO place detail은 `P7D`, forecast는 `PT24H`, 미신청 related와 B10 전 source는 `DISABLED`다.
- provider transport는 source별 exact host/HTTPS(격리 test stub의 loopback HTTP만 예외), redirect 거부, bounded executor·source permit, retry/jitter/429, circuit, response size 제한과 sanitized error를 사용한다. production host map은 KTO `apis.data.go.kr`과 Seoul `openapi.seoul.go.kr`으로 code에서 고정한다.
- canary는 provider URI에만 주입해 sanitized error와 `api_ingest_logs` row에 남지 않음을 검증한다. audit API/DDL에는 credential·full URI/query·body·user input field가 없다.
- fresh local `./gradlew --no-daemon test --rerun integrationTest --rerun openapiContractTest --rerun recommendationTest --rerun`과 full Docker gate가 통과했다. Docker Java는 288/133/13/19, AI pytest 410, web unit 224, Playwright 36이며 fail/error/skip은 0이다. C1 JUnit은 `apps/api/build/test-results/test/`, `apps/api/build/test-results/integrationTest/`에 있다.
- C1 safety mutation은 다른 source collector run의 SQL predicate 제거와 production host policy guard 우회 두 건을 실제로 RED로 확인했고, 원본을 복구한 focused `BA-020-T1/T2`를 다시 통과시켰다.

FE 인계·완료 증거: source 상태·quota·운영 실패 fixture와 승인 대장. C2에서만 실제 KTO gateway·key·provenance/snapshot을 추가하며, 서울 전용 adapter는 이 단계에서 구현하지 않는다.

### BA-021

**KTO 실제 gateway와 provenance 증거** — P0 / `in-progress` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-020](#ba-020)
- 기능 ID: `FR-OPS-11`
- API: 해당 없음 (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: 해당 없음; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: KTO 관광·집중률·연관 provider adapter · normalized cache · call-audit

구현 순서:

1. 승인된 KTO operation을 server-side로 호출해 transport/schema/semantic validation을 통과시킨다
2. 필요한 POI·여행 범위만 read-through 또는 refresh하고 동일 series를 immutable set으로 묶는다
3. release→provider operation→collector→provenanceId→화면 사용을 비밀 없는 audit로 연결한다
4. 09-06 PM 검토 PM-010, PM-014, PM-023의 영향 계약·화면·실패 fixture를 검토하고 미해결이면 해당 경계를 확정하지 않는다
5. #11 attributionShort·full credit·공식/이용조건 URL projection과 exact host 검토를 반영한다

실패·안전 경계: 전체/장기 mirror는 별도 승인 전 하지 않는다. PR synthetic 성공은 실제 KTO 활용 증거가 아니다. 이미지 사용 권한은 텍스트 데이터 승인과 별도다.

필수 검증:

- `BA-021-T1`: 외부 key가 브라우저·log·artifact에 노출되지 않는다
- `BA-021-T2`: 동일 요청 coalescing과 cache expiry 때 실제 refresh 경로를 fixture로 확인한다
- `BA-021-T3`: staging 실제 KTO 성공 이력과 공개 응답 provenance가 연결되며 mock-only 증거는 release에서 실패한다

FE 인계·완료 증거: 승인된 출처 텍스트·공식 URL·license URL·null 시각·provider별 field 설명, 실제 호출 증거 위치. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

구현·검증 증거 (T3 전):

- `V008__catalog_places.sql`은 `KTO_KOR_SERVICE_2` registry revision 2 (`kto-kor-service2-detailcommon2-v1`)과 `kto_place_snapshots`를 추가한다. snapshot은 source revision·collector run·KTO content/type ID·정규화 title/category/area/address/좌표·hash·fetched/stale만 보존하며 key·전체 URL/query·원문 body·overview·image column은 없다.
- C2 adapter는 오직 `KorService2/detailCommon2`를 server-side에서 호출한다. `firstImageYN=N`, `overviewYN=N`으로 이미지/소개 원문을 수집하지 않고 exact official base/host, transport, response envelope, matching content/type ID, coordinate range를 검증한 뒤에만 cache write와 call audit을 같은 transaction으로 완료한다. forecast와 related-place operation은 이 단계에서 callable하지 않다.
- `KtoDetailResponseValidatorTest`, `KtoKorServicePropertiesTest`, `KtoPlaceDetailGatewayIT`, `FlywayMigrationIT`가 T1/T2와 V007→V008 populated upgrade를 검증한다. 같은 request는 single-flight로 합치고 P7D 이후에만 새 collector run을 만든다. fixture canary와 raw provider body marker가 snapshot·`api_ingest_logs`·`collector_runs`에 없음을 실제 PostgreSQL에서 확인한다.
- C2 mutation은 single-flight 경로를 제거했을 때 `BA-021-T2`가 concurrent response 불일치로 RED가 되는 것과, content/type ID 비교의 `||`를 `&&`로 약화했을 때 `BA-021-T1`이 `SCHEMA_DRIFT` 대신 `OK`로 RED가 되는 것을 실제로 확인했다. 두 원본을 복구한 focused test는 다시 GREEN이다.
- final full Docker gate는 Java `293/136/13/19`, AI pytest `410`, web unit `224`, Playwright `36`을 failure/error/skip 없이 통과했고 generated client, npm audit, egress-denied를 함께 확인했다. 이는 fixture/integration evidence이며 actual KTO success를 뜻하지 않는다.
- `KTO_SERVICE_KEY`와 `KTO_BASE_URL`이 이 checkout/runtime에 제공되지 않아 T3의 staging actual-success/provenance chain은 아직 실행하지 않았다. fixture 성공은 actual KTO evidence가 아니므로 BA-021을 `integration-ready` 또는 완료로 올리지 않으며, C3 public place projection도 시작하지 않는다.

PM 검토 연결: [09-06 발견 사항](../project/PM_REVIEW_2026-09-06.md) — PM-010, PM-014, PM-023.

이슈 검토: [#10 기반](../engineering/FOUNDATION_DECISIONS.md) · [#11 계약](../contracts/review-2026-09-06/README.md).

### BA-022

**Canonical 장소·검색·상세·콘텐츠 권리** — P0 / `planned` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-021](#ba-021)
- 기능 ID: `FR-PLC-01`, `FR-TRC-04`
- API: `searchPlaces`, `getPlace` (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: `438:3158`; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: places · place_localizations · place_external_refs · asset_licenses

구현 순서:

1. 외부 ID의 canonical mapping·중복 병합·폐기된 POI 처리와 locale fallback을 구현한다
2. 100자 bounded POST 검색·50개 page·signed owner/filter cursor와 N+1 없는 상세를 제공한다
3. 출처·권리·영업 확인 근거를 보존하고 불명확 media는 placeholder로 축소한다
4. 09-06 PM 검토 PM-010의 영향 계약·화면·실패 fixture를 검토하고 미해결이면 해당 경계를 확정하지 않는다

실패·안전 경계: 검색어를 URL/log에 넣지 않는다. 관측하지 않은 영업시간·주소·이동 정보를 LLM으로 보충하지 않는다.

필수 검증:

- `BA-022-T1`: 동일 외부 ID 중복과 잘못된 canonical 참조를 차단한다
- `BA-022-T2`: cursor 변조·다른 owner/filter·15분 만료를 거부한다
- `BA-022-T3`: 검색 canary 비로그와 미승인 media 비노출을 검증한다

FE 인계·완료 증거: 검색 loading/empty/404/coverage 부족·KO/EN fallback fixtures, 장소 선택은 canonical ID만 확정. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

PM 검토 연결: [09-06 발견 사항](../project/PM_REVIEW_2026-09-06.md) — PM-010.

### BA-023

**혼잡 예보·시각·비교 적격성·데이터 안내** — P0 / `planned` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-020](#ba-020), [BA-021](#ba-021), [BA-022](#ba-022)
- 기능 ID: `FR-DAT-01`, `FR-DAT-02`, `FR-DAT-03`, `FR-DAT-04`, `FR-DAT-05`, `NFR-DATA-01`
- API: `getPlaceCrowdForecast` (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: `423:2967`; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: crowd_snapshots · snapshot_sets · crowd_comparisons · source registry

구현 순서:

1. LIVE/FORECAST/REPLAY/QUALITATIVE/STALE/UNAVAILABLE와 freshness를 서로 다른 타입으로 매핑한다
2. temporal은 같은 POI/issue/metric, spatial은 같은 source/scope/group/set인 pair만 비교한다
3. readiness와 데이터 안내에 source·license·officialUrl·licenseUrl·시각/null·reason을 투영한다
4. 09-06 PM 검토 PM-010, PM-013, PM-014의 영향 계약·화면·실패 fixture를 검토하고 미해결이면 해당 경계를 확정하지 않는다

실패·안전 경계: provider 발표 시각이 없으면 observedAt=null이고 fetchedAt과 구분한다. 서로 다른 POI의 상대 집중률·AREA와 PLACE·ordinal 단계를 공통 숫자로 환산하지 않는다.

필수 검증:

- `BA-023-T1`: 6-state와 null provenance matrix 전체를 contract/property로 검증한다
- `BA-023-T2`: mixed source/scope/issue/set·stale·replay·incident pair의 delta가 null이다
- `BA-023-T3`: 최신값 갱신이 저장된 preview snapshot과 비교 의미를 바꾸지 않는다

FE 인계·완료 증거: S15·장소 상세·MetricDelta eligible/ineligible 예시. Live 화면 개발 전 공통 source/data guide를 완료한다. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

PM 검토 연결: [09-06 발견 사항](../project/PM_REVIEW_2026-09-06.md) — PM-010, PM-013, PM-014.

### BA-024

**검증된 관련 장소와 추천 후보 검색** — P0 / `planned` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-022](#ba-022), [BA-023](#ba-023)
- 기능 ID: 해당 없음
- API: `listRelatedPlaces` (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: 해당 없음; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: catalog.place_relations · relation evidence · RelatedPlaceRanker(apps/ai related/rank)

구현 순서:

1. 공식 direct relation과 canonical mapping을 검증하고 category 기반 약한 관계는 SIMILAR로 분리한다
2. EXACT/SIMILAR/NONE/CHECKING/UNKNOWN 이유·유효기간·근거를 응답 계약에 매핑한다
3. 후보 수집→보강→hard filter→결정적 정렬을 apps/ai의 순수 ranker에 gateway로 연결하고 Spring은 hydration·fallback(UNKNOWN)만 담당한다
4. 09-06 PM 검토 PM-020의 영향 계약·화면·실패 fixture를 검토하고 미해결이면 해당 경계를 확정하지 않는다

실패·안전 경계: 관계가 있다는 이유로 더 한산하거나 경로 가능하다고 말하지 않는다. 이 API에 없는 tripId/숨은 active trip을 ranking 입력으로 쓰지 않는다.

필수 검증:

- `BA-024-T1`: 중복 canonical 후보·만료 evidence·불확실 mapping을 구분한다
- `BA-024-T2`: 입력 순서와 source 응답 순서가 바뀌어도 같은 결과다
- `BA-024-T3`: CHECKING은 실제 처리 상태에만 사용하고 가짜 대안을 채우지 않는다

FE 인계·완료 증거: 일정 교체와 나중 Live가 재사용할 공통 relation 예시. Live tab 모듈에 이 공통 테이블을 묶지 않는다. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

PM 검토 연결: [09-06 발견 사항](../project/PM_REVIEW_2026-09-06.md) — PM-020.

## B04 · 여행·피드·피드백·후보

발견→저장을 일정 변경 없이 완결한다.

### BA-030

**여행 생성·목록·결정적 초기 일정** — P0 / `planned` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-010](#ba-010), [BA-011](#ba-011), [BA-022](#ba-022)
- 기능 ID: `FR-PRO-03`, `FR-TRC-01`, `FR-TRC-02`, `FR-TRC-03`, `FR-TRC-05`, `FR-TRC-08`, `FR-TRC-09`, `FR-TRC-10`, `FR-TRC-12`, `FR-TRP-01`
- API: `listTrips`, `createTrip`, `getTrip` (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: `384:5673`, `400:1201`, `410:1738`, `422:2925`, `438:3012`, `438:3108`, `438:3134`, `438:3199`, `438:3259`; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: trips · trip_interests · trip_items · initial revision · create idempotency

구현 순서:

1. 필수 start/end/timezone/planningLevel/interests와 optional title의 locale 기반 기본값을 구현한다
2. 사용자가 확인한 canonical seed·날짜·순서·constraint를 한 transaction으로 생성한다
3. owner 목록과 complete trip aggregate/ETag를 batch read로 제공한다
4. 09-06 PM 검토 PM-004, PM-005, PM-006, PM-008의 영향 계약·화면·실패 fixture를 검토하고 미해결이면 해당 경계를 확정하지 않는다

실패·안전 경계: 빈 관심사 허용·30일/총100개/하루20개 상한을 지킨다. FR-TRC-10 추천 draft preview/read 공백은 REC-CON-04에서 계약 해결 후 제공하고 묵시적 일정을 생성하지 않는다.

필수 검증:

- `BA-030-T1`: 날짜 역전·초과·timezone/DST 경계와 기본 title/빈 관심사를 검증한다
- `BA-030-T2`: 중복 create가 한 trip만 만들고 실패 시 부분 item/revision이 없다
- `BA-030-T3`: owner 목록·complete view·wizard client-only 복구가 같은 계약을 따른다

FE 인계·완료 증거: 수동 wizard·최종 확인·empty trip·generated draft 구분 예시. 새 draft API 필요 시 BA-000 계약 검토를 선행한다. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

PM 검토 연결: [09-06 발견 사항](../project/PM_REVIEW_2026-09-06.md) — PM-004, PM-005, PM-006, PM-008.

### BA-031

**여행 metadata·관심사·삭제** — P0 / `planned` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-030](#ba-030), [BA-012](#ba-012)
- 기능 ID: `FR-PRO-05`, `FR-TRP-04`, `FR-TRP-05`
- API: `updateTrip`, `deleteTrip`, `replaceTripInterests` (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: `411:1837`, `422:2925`; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: Trip aggregate · interests · revisions · active_trip_id · deletion cascade

구현 순서:

1. 제목/기간/timezone/관심사 전체 교체마다 If-Match와 단일 version 증가를 적용한다
2. 날짜 축소가 item/DATE/RESERVATION을 범위 밖으로 만들면 422로 전체 거절한다
3. 삭제는 owner 재확인 후 하위 run/candidate/item을 지우고 active trip을 정리한다
4. 09-06 PM 검토 PM-002, PM-006의 영향 계약·화면·실패 fixture를 검토하고 미해결이면 해당 경계를 확정하지 않는다

실패·안전 경계: timezone 변경 시 local wall-clock 보존 규칙을 지킨다. 후보 저장과 달리 관심사·metadata 변경은 기존 preview를 stale로 만든다.

필수 검증:

- `BA-031-T1`: 두 tab 갱신 경쟁에서 하나만 성공하고 최신 편집이 남는다
- `BA-031-T2`: 기간 축소 실패가 item 이동·삭제·version 변경을 만들지 않는다
- `BA-031-T3`: trip 삭제와 run 생성 경합 및 active trip 정리를 검증한다

FE 인계·완료 증거: profile 관심사·기간 충돌·삭제/404·ETag 갱신 예시와 최신 상태 재조회 CTA. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

PM 검토 연결: [09-06 발견 사항](../project/PM_REVIEW_2026-09-06.md) — PM-002, PM-006.

### BA-032

**고정 feed·게시물·SavedPost** — P0 / `planned` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-022](#ba-022), [BA-030](#ba-030)
- 기능 ID: `FR-FED-01`, `FR-FED-02`, `FR-FED-03`, `FR-PST-01`, `FR-PST-02`
- API: `listFeed`, `getPost`, `savePost`, `unsavePost` (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: `391:310`, `396:2926`, `398:611`; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: posts · post_places · saved_posts · fixed feed cursor/read projection

구현 순서:

1. 게시 가능한 curated post와 canonical places를 조회하고 publishedAt/postId 고정 순서를 제공한다
2. opaque cursor의 paging 일관성 정책을 정하고 page 내 owner 저장·selected-trip 상태를 batch hydrate한다
3. SavedPost 저장/해제를 owner/post unique로 수렴시킨다
4. 09-06 PM 검토 PM-001, PM-010, PM-011의 영향 계약·화면·실패 fixture를 검토하고 미해결이면 해당 경계를 확정하지 않는다

실패·안전 경계: P0 tripId는 candidate/scheduled 표시만 바꾸며 순위를 바꾸지 않는다. snapshot table이 필요하면 REC-CON-01/07 ERD·TTL·삭제 검토를 선행한다.

필수 검증:

- `BA-032-T1`: 페이지 사이 새 글·삭제·숨김·같은 정렬 시각에서 중복/누락 정책을 검증한다
- `BA-032-T2`: 다른 owner의 저장 상태가 shared cache로 새지 않는다
- `BA-032-T3`: save/unsave가 후보·item·trip version에 영향을 주지 않는다

FE 인계·완료 증거: 여행 없음/활성 여행/feed empty를 구분한 card/detail fixture와 숨겨야 할 P1 controls. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

PM 검토 연결: [09-06 발견 사항](../project/PM_REVIEW_2026-09-06.md) — PM-001, PM-010, PM-011.

### BA-033

**피드백·분석 이벤트 무결성** — P0 / `planned` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-010](#ba-010), [BA-032](#ba-032)
- 기능 ID: `FR-FED-04`, `FR-OPS-06`
- API: `recordFeedFeedback`, `ingestEventBatch` (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: 해당 없음; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: feed_feedback · analytics_events · idempotency · server-bound owner/session

구현 순서:

1. IMPRESSION/OPEN/HIDE/LIKE/DISLIKE와 JSON Schema event allowlist를 각각 검증한다
2. feedback minute dedup·receivedAt 기반 유효 상태와 HIDE 보존 의미를 계약 example으로 확정한다
3. eventId dedup·batch50·90일 TTL·owner 삭제·품질 계측을 구현한다
4. 09-06 PM 검토 PM-011, PM-016의 영향 계약·화면·실패 fixture를 검토하고 미해결이면 해당 경계를 확정하지 않는다

실패·안전 경계: client event는 실제 일정 변경·노출 인증·방문 증명이 아니다. impression lineage가 없는 P0 데이터로 개인화 모델을 학습시키지 않는다.

필수 검증:

- `BA-033-T1`: unknown property·위조 owner/session·좌표·원문 canary를 거부한다
- `BA-033-T2`: 재전송·동시 LIKE/DISLIKE/HIDE가 정해진 상태로 수렴한다
- `BA-033-T3`: analytics 장애가 제품 command를 실패시키지 않고 owner 삭제/TTL이 반영된다

FE 인계·완료 증거: event emission 순간과 server transaction 진실의 차이, HIDE 갱신·금지 필드·오류 fixtures. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

PM 검토 연결: [09-06 발견 사항](../project/PM_REVIEW_2026-09-06.md) — PM-011, PM-016.

### BA-034

**여행 후보 저장·중복·dismiss** — P0 / `planned` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-030](#ba-030), [BA-032](#ba-032)
- 기능 ID: `FR-CAN-01`, `FR-CAN-02`, `FR-CAN-03`, `FR-CAN-04`, `FR-CAN-05`, `FR-CAN-06`
- API: `listTripCandidates`, `addTripCandidate`, `removeTripCandidate` (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: `399:1011`, `399:1179`, `399:658`, `399:843`, `409:1595`, `412:1912`; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: trip_candidates · candidate_sources · partial unique active trip/place

구현 순서:

1. 저장 출처별 post/place/trip owner·참조를 검증하고 ACTIVE candidate만 만든다
2. partial unique와 idempotency로 201 생성/200 duplicate 응답을 분리한다
3. ACTIVE→DISMISSED와 새 row 재저장, SCHEDULED 직접 삭제 거부를 구현한다
4. 09-06 PM 검토 PM-009의 영향 계약·화면·실패 fixture를 검토하고 미해결이면 해당 경계를 확정하지 않는다

실패·안전 경계: 후보는 날짜/시간 없이 저장하고 item 생성·trip version 증가가 없다. 다른 게시물의 같은 POI도 활성 후보 한 개로 수렴한다.

필수 검증:

- `BA-034-T1`: 두 tap·서로 다른 key 동시 요청·다른 post 같은 POI가 한 row로 수렴한다
- `BA-034-T2`: 후보 저장 실패와 재시도에서 일정 미변경을 확인한다
- `BA-034-T3`: DISMISSED 재저장·SCHEDULED 삭제 거부·owner 분리를 검증한다

FE 인계·완료 증거: picker/201/duplicate/error/candidate count·status fixtures와 같은 key exact retry 방법. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

PM 검토 연결: [09-06 발견 사항](../project/PM_REVIEW_2026-09-06.md) — PM-009.

## B05 · 일정 편집·독립 잠금

원자 command와 version 충돌을 먼저 검증한다.

### BA-040

**일정 item 추가·이동·수정·삭제·순서** — P0 / `planned` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-031](#ba-031), [BA-034](#ba-034)
- 기능 ID: `FR-ITM-01`, `FR-ITM-02`, `FR-ITM-03`, `FR-ITM-04`, `FR-ITM-05`, `FR-ITM-06`, `FR-TRP-02`, `FR-TRP-03`
- API: `addTripItem`, `updateTripItem`, `removeTripItem`, `reorderTripItems` (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: `411:1837`, `412:1912`, `413:2020`, `476:3409`, `479:3816`, `521:3976`, `527:4085`, `527:4695`; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: trip_items · trip_candidates linkage · trip_revisions · ordered positions

구현 순서:

1. 각 command에 owner·If-Match·기간·duration·상한·고정 조건을 검증한다
2. candidate 일정화는 SCHEDULED linkage와 item/revision을 원자 반영한다
3. reorder의 전체 대상·중복·날짜별 position을 검증하고 삭제 disposition별 후보 복원을 명시한다
4. 09-06 PM 검토 PM-002, PM-003, PM-007, PM-008, PM-009의 영향 계약·화면·실패 fixture를 검토하고 미해결이면 해당 경계를 확정하지 않는다

실패·안전 경계: 사용자 edit buffer는 서버 상태가 아니다. 실패·취소·dirty-exit가 domain mutation을 만들지 않는다. lock 관련 기능은 BA-041 통합 전 활성화하지 않는다.

필수 검증:

- `BA-040-T1`: cross-day reorder·position unique 경쟁·부분 실패에서 원자성이 유지된다
- `BA-040-T2`: candidate schedule/RESTORE_CANDIDATE 전이가 item과 동시에 반영된다
- `BA-040-T3`: 날짜·시간·duration·item 상한 경계와 keyboard E2E를 통과한다

FE 인계·완료 증거: 편집 명령별 before/after·new ETag·empty day·충돌 payload; 키보드/취소 UI는 FE 구현. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

PM 검토 연결: [09-06 발견 사항](../project/PM_REVIEW_2026-09-06.md) — PM-002, PM-003, PM-007, PM-008, PM-009.

### BA-041

**네 종류 독립 잠금과 동시 편집 충돌** — P0 / `planned` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-040](#ba-040)
- 기능 ID: `FR-CON-01`, `FR-CON-02`, `FR-CON-03`, `FR-CON-04`, `FR-CON-05`, `FR-CON-06`, `NFR-DATA-02`
- API: `setTripItemConstraint`, `removeTripItemConstraint` (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: `413:2081`, `527:3876`; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: trip_constraints tagged types · TripCommand validation · revision

구현 순서:

1. MUST_VISIT/DATE/TIME/RESERVATION의 type별 필수/null/tolerance를 DB와 domain에 고정한다
2. path type=body type과 source USER/IMPORT를 검증하고 잠금 해제는 해당 row 삭제로 표현한다
3. 수동 edit·교체·optimizer가 같은 constraint validator를 재사용한다
4. 09-06 PM 검토 PM-002, PM-003, PM-005, PM-007, PM-008의 영향 계약·화면·실패 fixture를 검토하고 미해결이면 해당 경계를 확정하지 않는다

실패·안전 경계: 예약 잠금 자동 해제와 한 type 변경으로 다른 type 삭제를 금지한다. 명시적인 해제 command 없이 변경을 통과시키지 않는다.

필수 검증:

- `BA-041-T1`: 네 타입 조합 property test에서 독립 잠금이 보존된다
- `BA-041-T2`: date/time/예약 위반 command와 stale If-Match를 거부한다
- `BA-041-T3`: unlock 동시성·transaction rollback에서 다른 lock row/version이 보존된다

FE 인계·완료 증거: 잠금 영향·해제 확인·LOCK_CONFLICT examples와 Figma state 연결. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

PM 검토 연결: [09-06 발견 사항](../project/PM_REVIEW_2026-09-06.md) — PM-002, PM-003, PM-005, PM-007, PM-008.

### BA-042

**후보 slot 판정·비교 후 장소 교체** — P0 / `planned` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-024](#ba-024), [BA-040](#ba-040), [BA-041](#ba-041)
- 기능 ID: `FR-CAN-07`, `FR-ITM-07`, `FR-ITM-08`
- API: `getCandidateTripMatches`, `replaceTripItem` (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: `412:1912`, `414:2347`, `479:3497`, `527:4537`; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: SlotEvaluator(apps/ai slots/evaluate) · CandidateMatchResult · atomic replacement · relation evidence

구현 순서:

1. 날짜별 canonical POI/영업/체류/이웃 이동/잠금을 검사해 eligible slot 또는 사유를 만든다
2. 근거가 없으면 UNKNOWN, 실제 계산 중이면 CHECKING, 전부 불가면 NONE을 반환한다
3. replacement는 최신 owner/version/관계/constraints를 다시 검증하고 item·candidate linkage·revision을 함께 변경한다
4. 09-06 PM 검토 PM-007, PM-009, PM-014, PM-020의 영향 계약·화면·실패 fixture를 검토하고 미해결이면 해당 경계를 확정하지 않는다

실패·안전 경계: query는 일정을 바꾸지 않는다. 관계 추천과 일정 실행 가능성은 별도이며 suggestedTime을 추정 사실로 만들어 넣지 않는다.

필수 검증:

- `BA-042-T1`: 날짜만 있는 slot/null time·DST·영업/route 결측을 구분한다
- `BA-042-T2`: replace 중간 실패와 stale relation/version은 일정 미변경이다
- `BA-042-T3`: MUST_VISIT/예약 잠금과 기존 candidate linkage가 일관되게 보존된다

FE 인계·완료 증거: comparison eligible/ineligible·EXACT/SIMILAR/NONE/CHECKING/UNKNOWN·교체 성공/실패 fixture. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

PM 검토 연결: [09-06 발견 사항](../project/PM_REVIEW_2026-09-06.md) — PM-007, PM-009, PM-014, PM-020.

## B06 · 승인형 추천·최적화

ITEM preview→APPLY/KEEP→24시간 REVERT를 구현한다.

### BA-050

**최적화 run·snapshot·polling** — P0 / `planned` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-005](#ba-005), [BA-023](#ba-023), [BA-041](#ba-041)
- 기능 ID: `FR-OPT-01`, `FR-OPT-03`, `FR-OPT-16`
- API: `createOptimization`, `getOptimization` (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: `415:2268`, `415:2413`; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: optimization_runs · background_jobs · run_snapshot_sets · route junctions

구현 순서:

1. ITEM target/inputTripVersion/includeCandidates=false를 검증하고 run+job을 원자 생성한다
2. worker는 일관된 trip snapshot과 모든 근거 snapshot/policy hash를 고정하고 apps/ai items/propose를 DB transaction 밖에서 호출한다
3. QUEUED→RUNNING→READY 또는 FAILED/EXPIRED와 Retry-After를 제공한다
4. 09-06 PM 검토 PM-013, PM-015의 영향 계약·화면·실패 fixture를 검토하고 미해결이면 해당 경계를 확정하지 않는다

실패·안전 경계: DAY/TRIP union은 schema에 있어도 P0 capability OFF다. READY 저장 전에 owner/run lease/trip version을 재검증하고 TripItem 쓰기는 하지 않는다.

필수 검증:

- `BA-050-T1`: 잘못된 scope target 조합과 P1 capability 요청을 거부한다
- `BA-050-T2`: job 유실·중복 worker·trip 삭제/편집 경합을 재현한다
- `BA-050-T3`: refresh/polling 복구·만료·timeout에서 run 상태가 역행하지 않는다

FE 인계·완료 증거: run URL·Retry-After·각 상태·expiry fixtures; client timeout이 cancellation이 아니라는 설명. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

PM 검토 연결: [09-06 발견 사항](../project/PM_REVIEW_2026-09-06.md) — PM-013, PM-015.

### BA-051

**ITEM 후보 생성·검증·점수·설명** — P0 / `planned` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-024](#ba-024), [BA-042](#ba-042), [BA-050](#ba-050)
- 기능 ID: `FR-OPT-04`, `FR-OPT-05`, `FR-OPT-06`, `FR-OPT-12`, `FR-OPT-13`, `FR-OPT-14`
- API: 해당 없음 (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: `417:2567`; FCR: `FCR-004`. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: recommendation policy-v1(apps/ai) · ProposalRevalidator · immutable proposals/changes/comparisons · validation summary

구현 순서:

1. X 단계 분리 원칙으로 같은 POI의 지원되는 날짜/시각 후보만 생성한다
2. hard constraints와 comparison을 먼저 통과시킨 뒤 relief/changeCost 점수와 고정 tie-break로 최대3개를 선택한다
3. 전체 resulting trip을 재검증하고 KO/EN 근거 template와 before/after를 저장한다
4. 09-06 PM 검토 PM-013, PM-014, PM-015, PM-020의 영향 계약·화면·실패 fixture를 검토하고 미해결이면 해당 경계를 확정하지 않는다
5. #11 최초 결정 APPLY/KEEP 응답과 서버 revertAvailability 계산·조회/변경 경합을 검증한다

실패·안전 경계: 영업/이동 증거가 필요한데 없으면 ROUTE_UNAVAILABLE 또는 계약상 데이터 실패이며 NO_IMPROVEMENT로 숨기지 않는다. apps/ai 응답은 Spring ProposalRevalidator를 통과한 뒤에만 저장하고 위반은 run FAILED와 alert다. 점수와 후보 개수/예산은 초안이고 성능 주장이 아니다.

필수 검증:

- `BA-051-T1`: REC 핵심 suite 전부: 결정성·isolation·mixed-source·lock·null·후보 cap을 검증한다
- `BA-051-T2`: 입력/현재 clock/source 도착 순서를 바꿔도 고정 snapshot 결과가 재현된다
- `BA-051-T3`: 수치·장소·영업·route 사실을 설명이 추가하지 않고 preview 중 일정 쓰기가 0이다

FE 인계·완료 증거: FCR-004 ITEM READY fixture·eligible delta·이유·validation·APPLY/KEEP UI; 실제 node 반영은 FE 검토 후. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

PM 검토 연결: [09-06 발견 사항](../project/PM_REVIEW_2026-09-06.md) — PM-013, PM-014, PM-015, PM-020.

이슈 검토: [#10 기반](../engineering/FOUNDATION_DECISIONS.md) · [#11 계약](../contracts/review-2026-09-06/README.md).

### BA-052

**APPLY·KEEP의 멱등 원자 결정** — P0 / `planned` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-050](#ba-050), [BA-051](#ba-051)
- 기능 ID: `FR-OPT-07`, `FR-OPT-08`, `FR-OPT-10`, `FR-OPT-11`, `FR-OPT-15`
- API: `decideOptimization` (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: `417:2567`, `485:3517`; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: optimization_decisions · TripCommand · revisions · idempotency response

구현 순서:

1. 완료 idempotency replay를 먼저 분기하고 새 결정은 trip/run/정책 generation을 정해진 순서로 잠근다
2. version·fingerprint·freshness·incident·expiry·lock·proposal 소속을 다시 검증한다
3. APPLY는 item/revision/decision/response를 한 transaction에, KEEP은 decision만 기록한다
4. 09-06 PM 검토 PM-015의 영향 계약·화면·실패 fixture를 검토하고 미해결이면 해당 경계를 확정하지 않는다

실패·안전 경계: 한 run 최초 결정은 최대 하나다. owner 삭제·source incident 갱신과 경쟁을 DB에서 순서화한다. 이후 편집 후 같은 key replay는 원래 응답을 반환하고 다시 적용하지 않는다.

필수 검증:

- `BA-052-T1`: 동시 APPLY/APPLY 및 APPLY/KEEP에서 최초 결정 하나만 반영된다
- `BA-052-T2`: 각 쓰기 지점 fault injection으로 부분 적용0을 확인한다
- `BA-052-T3`: TRIP_CHANGED·DATA_CHANGED·expired·policy 철회가 apply를 차단한다

FE 인계·완료 증거: APPLY 필수 revision/revertUntil와 KEEP 필드 부재의 판별 union, 충돌 재계산·동일 요청 재시도 fixtures. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

PM 검토 연결: [09-06 발견 사항](../project/PM_REVIEW_2026-09-06.md) — PM-015.

### BA-053

**24시간 REVERT·최적화 이력** — P0 / `planned` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-052](#ba-052)
- 기능 ID: `FR-OPT-09`, `FR-PRO-04`
- API: `revertOptimizationDecision`, `listOptimizationHistory` (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: `417:2412`, `422:2925`; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: immutable trip revision · reverted_decision_id unique · history cursor

구현 순서:

1. APPLY의 decidedAt+24h를 revertUntil로 고정하고 현재 trip version이 APPLY 결과와 같은지 확인한다
2. 한 번만 이전 snapshot을 새 revision으로 복원하고 REVERT decision을 기록한다
3. profile history는 status/scope/time/decision/run 링크만 owner-scoped cursor로 제공한다
4. 09-06 PM 검토 PM-002, PM-009, PM-015, PM-016의 영향 계약·화면·실패 fixture를 검토하고 미해결이면 해당 경계를 확정하지 않는다
5. #11 APPLIED refresh·preview TTL 분리·24시간 undo 만료와 metadata 보존을 검증한다

실패·안전 경계: KEEP/REVERT를 다시 되돌리지 않는다. 24시간 만료는 410 REVERT_WINDOW_EXPIRED다. 이력 때문에 일정·proposal 본문을 복제하거나 보존을 늘리지 않는다.

필수 검증:

- `BA-053-T1`: 24시간 직전/정각/이후·두 요청 경쟁·같은 key replay를 검증한다
- `BA-053-T2`: 후속 metadata/관심사/item 편집이 있으면 revert를 거부한다
- `BA-053-T3`: history owner/filter/expiry cursor와 trip 삭제 cascade를 검증한다

FE 인계·완료 증거: undo 가능/만료/후속 변경·S14 이력 empty/failed/expired examples와 새 ETag. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

PM 검토 연결: [09-06 발견 사항](../project/PM_REVIEW_2026-09-06.md) — PM-002, PM-009, PM-015, PM-016.

이슈 검토: [#10 기반](../engineering/FOUNDATION_DECISIONS.md) · [#11 계약](../contracts/review-2026-09-06/README.md).

## B07 · 붙여넣기 import

핵심 일정 도메인을 재사용해 P0 import를 완결한다.

### BA-060

**붙여넣기 parse·remap·confirm** — P0 / `planned` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-012](#ba-012), [BA-022](#ba-022), [BA-030](#ba-030), [BA-040](#ba-040), [BA-041](#ba-041)
- 기능 ID: `FR-TRC-06`, `FR-TRC-07`, `NFR-PRV-01`
- API: `parseTripImport`, `remapTripImport`, `confirmTripImport` (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: `401:1221`; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: itinerary_import_drafts structured only · version · import constraints

구현 순서:

1. 브라우저 parser 보조를 위해 20000자 bounded 원문을 요청 메모리에서만 처리한다
2. canonical suggestions와 짧게 제한한 unresolved token을 구조화하고 NEEDS_REVIEW↔READY·ETag remap을 제공한다
3. confirm은 READY·TTL·If-Match·owner·POI/날짜/lock을 재검증해 trip과 items를 한 transaction으로 만든다
4. 09-06 PM 검토 PM-005, PM-008의 영향 계약·화면·실패 fixture를 검토하고 미해결이면 해당 경계를 확정하지 않는다

실패·안전 경계: 원문·자유 메모·연락처가 unresolved token/exception/job/response에 그대로 남지 않게 allowlist 추출한다. 외부 LLM에 원문을 보내지 않고 24시간 draft TTL을 둔다.

필수 검증:

- `BA-060-T1`: 고유 원문 canary가 DB/cache/log/trace/event/response에 없다
- `BA-060-T2`: stale remap/confirm·만료·미해결 매핑·중복 confirm은 부분 trip을 만들지 않는다
- `BA-060-T3`: parser 날짜·한영 장소·모호한 시간·100item/10suggestion 경계를 검증한다

FE 인계·완료 증거: parse warning/review/remap/confirm·만료 fixtures와 원문 제외 refresh 복구. 수동 입력 fallback은 계속 유지한다. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

PM 검토 연결: [09-06 발견 사항](../project/PM_REVIEW_2026-09-06.md) — PM-005, PM-008.

## B08 · 보안·성능·AWS·핵심 검수

운영을 검증하고 Live 이전 중간 gate를 닫는다.

### BA-070

**전체 권한·privacy·부하·접근성 통합** — P0 / `planned` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-006](#ba-006), [BA-012](#ba-012), [BA-033](#ba-033), [BA-034](#ba-034), [BA-042](#ba-042), [BA-053](#ba-053), [BA-060](#ba-060)
- 기능 ID: `NFR-A11Y-01`, `NFR-A11Y-02`, `NFR-AVL-01`, `NFR-PERF-01`, `NFR-PERF-02`, `NFR-RESP-01`
- API: 해당 없음 (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: 해당 없음; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: authorization matrix · query plans · load fixtures · component/E2E report

구현 순서:

1. 모든 owner resource·CSRF·CORS·body/rate·SSRF·safe error matrix를 실제 PostgreSQL/API로 수행한다
2. 고정 규모와 concurrency·cache 조건에서 p95/queue/SLO를 측정하고 N+1·lock wait·pool을 조정한다
3. FE와 KO/EN·360/768/1280·200%zoom·keyboard·offline 핵심 흐름을 통합한다
4. 09-06 PM 검토 PM-024의 영향 계약·화면·실패 fixture를 검토하고 미해결이면 해당 경계를 확정하지 않는다

실패·안전 경계: 목표 수치를 측정 결과로 기록하지 않는다. CI noisy runner의 부하 결과와 staging SLO를 분리하고 중요 안전 suite 실패는 성능과 관계없이 차단한다.

필수 검증:

- `BA-070-T1`: 다른 owner/expired session/source 장애 조합의 핵심 CRUD가 안전하다
- `BA-070-T2`: redaction canary와 API 응답 PII·secret denylist가 0이다
- `BA-070-T3`: 고정 부하 budget·핵심 keyboard/a11y E2E·P1 OFF variants를 통과한다

FE 인계·완료 증거: 오류/지연/접근성 회귀 report, 성능 fixture 규모·runner·원시 지표, 고칠 항목과 재현 경로. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

PM 검토 연결: [09-06 발견 사항](../project/PM_REVIEW_2026-09-06.md) — PM-024.

### BA-071

**AWS 배포·불변 artifact·롤백** — P0 / `planned` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-006](#ba-006), [BA-070](#ba-070)
- 기능 ID: `FR-OPS-08`
- API: 해당 없음 (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: 해당 없음; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: CDK stacks · release manifest(api/ai image digest) · ECS api/ai services · S3 artifacts · single migration task

구현 순서:

1. private RDS/ECS·CloudFront/OAC·origin bypass 차단·최소 IAM/WAF를 검증한다
2. 동일 image(api, ai)/client/contract/DB/CDK digest를 staging→production에 승격하고 migration 동시성1/DB lock을 적용한다
3. 이전 호환 API image와 web artifact 복구를 실제 staging에서 연습한다
4. 09-06 PM 검토 PM-022의 영향 계약·화면·실패 fixture를 검토하고 미해결이면 해당 경계를 확정하지 않는다

실패·안전 경계: production 비용·계정·domain·상대 승인이 없는 실제 배포는 금지한다. destructive DB down migration을 자동 rollback으로 실행하지 않는다.

필수 검증:

- `BA-071-T1`: 직접 ALB/S3/RDS 접근과 잘못된 OIDC subject가 거부된다
- `BA-071-T2`: 동시에 두 deploy/migration이 실행되지 않는다
- `BA-071-T3`: 같은 manifest artifact만 승격되고 rollback 뒤 핵심 smoke가 통과한다

FE 인계·완료 증거: 공개 config·release/contract SHA·이전 rollback target과 staging acceptance URL. UI artifact 확인은 FE 담당. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

PM 검토 연결: [09-06 발견 사항](../project/PM_REVIEW_2026-09-06.md) — PM-022.

### BA-072

**복원·삭제 재적용·alarm·사고 대응** — P0 / `planned` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-012](#ba-012), [BA-020](#ba-020), [BA-071](#ba-071)
- 기능 ID: `FR-OPS-07`
- API: 해당 없음 (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: 해당 없음; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: RDS PITR · backup retention · tombstone replay · CloudWatch alarm/contact

구현 순서:

1. backup14일 기준과 RPO/RTO 목표를 실제 restore로 측정한다
2. public traffic 전에 tombstone 재적용·삭제 검증을 수행하고 실패 시 닫힌 상태를 유지한다
3. source/quota/queue/security/budget alarm의 실제 primary/secondary 수신과 tabletop을 검증한다

실패·안전 경계: 연락망·restore·alarm 수신 증거가 없는 상태를 운영 준비 완료로 표시하지 않는다. tombstone은 backup 최대 보존보다7일 이상 길게 보존한다.

필수 검증:

- `BA-072-T1`: 삭제 이전 backup 복원 후 해당 owner가 재노출되지 않는다
- `BA-072-T2`: 부분 삭제 실패·lease 재시도·receipt 만료를 incident로 추적한다
- `BA-072-T3`: 수신자 부재 escalation·예산/쿼터 경보와 rollback 판단을 재현한다

FE 인계·완료 증거: 복원 측정값·사고 사용자 문구·safe status·역할 교대 checklist, 비공개 연락처는 저장소에 넣지 않는다. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

### BA-073

**핵심 흐름 검수와 제출 증거 기반** — P0 / `planned` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-021](#ba-021), [BA-053](#ba-053), [BA-060](#ba-060), [BA-070](#ba-070), [BA-071](#ba-071), [BA-072](#ba-072)
- 기능 ID: `FR-OPS-12`, `NFR-CMP-01`, `NFR-CMP-02`, `NFR-CMP-03`
- API: 해당 없음 (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: 해당 없음; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: contest evidence ledger · release manifest · actual KTO operation set

구현 순서:

1. 익명 외부 HTTPS에서 생성→KTO탐색→후보→일정화→preview→APPLY/KEEP/REVERT를 검수한다
2. 실제 KTO call과 화면 attribution·source state·PDF 기능 목록을 같은 release에 연결한다
3. Live 이전에는 핵심 흐름 준비만 판정하고 전체 P0·최종 제출 검수는 BA-092 뒤 다시 수행한다
4. 09-06 PM 검토 PM-001, PM-014, PM-023의 영향 계약·화면·실패 fixture를 검토하고 미해결이면 해당 경계를 확정하지 않는다

실패·안전 경계: 공모전 profile geolocation은 OFF다. 아직 꺼진 Live·P1·모델을 구현/분산효과 성과로 제출하지 않는다. 공식 일정은 최신 공지 재확인 대상이다.

필수 검증:

- `BA-073-T1`: 외부망 익명창에서 로그인 없이 핵심 흐름이 완결된다
- `BA-073-T2`: mock-only KTO audit·출처 누락·위치 요청이 release gate에서 실패한다
- `BA-073-T3`: PDF actual feature/API 목록과 runtime capability가 일치한다

FE 인계·완료 증거: 검증된 화면·호출 operation·evidence ID·미완성 기능 목록. 최종 제출 go/no-go는 Live 이후 공동 확인한다. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

PM 검토 연결: [09-06 발견 사항](../project/PM_REVIEW_2026-09-06.md) — PM-001, PM-014, PM-023.

## B09 · 선택 확장 검토

P1/P2 중 이번 범위에 명시적으로 선정한 작업만 실행한다. 미선정은 deferred.

### BA-080

**독립 검색·feed filter와 정렬 확장** — P1 / `planned` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-032](#ba-032), [BA-033](#ba-033), [BA-073](#ba-073)
- 기능 ID: `FR-FED-05`, `FR-SRC-01`
- API: 해당 없음 (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: 해당 없음; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: proposed search/filter/sort contract · index · cursor policy version

구현 순서:

1. 검색 지표와 사용자 요구로 필요한 filter만 선택해 OpenAPI/Figma를 제안한다
2. 새 filter/sort를 cursor·owner·query hash에 결합하고 index/실행 계획을 검증한다
3. 필터별 empty/unknown/비교 불가와 flag OFF를 정의한다

실패·안전 경계: KTO 다른 POI 상대 집중률·서울 ordinal 혼잡을 공통 낮은 순으로 정렬하지 않는다. 검색 기록 영속은 privacy 승인 전 추가하지 않는다.

필수 검증:

- `BA-080-T1`: filter 전환 시 이전 cursor가 무효화된다
- `BA-080-T2`: source 혼합 순위와 민감 query logging을 거부한다
- `BA-080-T3`: OFF state에서는 새 UI/API 호출이 없다

FE 인계·완료 증거: 새 계약 승인 후 생성 client·필터 examples·성능 보고서. 기본 P0 feed 순서는 그대로 버전 관리한다. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

### BA-081

**계정 인증·익명 승계·follow graph** — P1 / `planned` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-010](#ba-010), [BA-012](#ba-012), [BA-031](#ba-031), [BA-033](#ba-033), [BA-073](#ba-073)
- 기능 ID: `FR-AUT-01`, `FR-FOL-01`
- API: 해당 없음 (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: `422:2925`; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: proposed accounts/identities/follows · merge journal · recovery policy

구현 순서:

1. provider·계정 복구·인증/승계 API와 개인정보 정책을 먼저 결정한다
2. 양 owner 소유권을 재검증해 중복 SavedPost/candidate/trip·active trip·삭제 상태를 원자적으로 승계한다
3. privilege 전환 session rotation 후 follow/unfollow와 feed 계약을 별도 검증한다

실패·안전 경계: 기존 익명 데이터를 로그인만으로 타 계정에 붙이지 않는다. 삭제 진행 owner 병합 금지·재시도/충돌 정책을 정의하고 P0 login은 계속 OFF다.

필수 검증:

- `BA-081-T1`: 계정 탈취·fixation·승계 replay/중간 장애·동시 로그인에서 소유권이 보존된다
- `BA-081-T2`: 동일 POI 후보·follow 중복/blocked target을 계약대로 처리한다
- `BA-081-T3`: 계정 삭제가 분석·추천 export와 follow graph까지 적용된다

FE 인계·완료 증거: login/merge preview·복구·실패·충돌 및 following feed empty/disabled fixtures. 새로운 endpoint는 모두 proposed다. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

### BA-082

**게시물·미디어 업로드·moderation** — P1 / `planned` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-081](#ba-081), [BA-022](#ba-022), [BA-071](#ba-071)
- 기능 ID: `FR-PUB-01`
- API: 해당 없음 (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: 해당 없음; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: proposed upload intents/assets/post states · moderation/audit · S3 quarantine

구현 순서:

1. 작성 권한·업로드 presign 범위·크기/형식/TTL·license attest 계약을 만든다
2. 격리 업로드→검증/검토→게시→숨김/삭제와 abandoned upload cleanup을 구현한다
3. MIME/magic bytes·악성 파일·EXIF·권리·신고/삭제 전파를 검증한다

실패·안전 경계: 미검증 asset은 공개 CDN에 노출하지 않고 임의 remote URL fetch는 금지한다. 게시 중지/권리 철회는 feed/cache/recommendation 노출도 차단한다.

필수 검증:

- `BA-082-T1`: 타 owner presign 재사용·경로 조작·크기 초과·format spoof를 거부한다
- `BA-082-T2`: moderation 전 공개0과 실패 cleanup을 검증한다
- `BA-082-T3`: 삭제/권리 철회가 기존 cursor·cache에서도 반영된다

FE 인계·완료 증거: upload 진행/취소/만료·검토/게시 거절·출처 fixtures와 새 generated client. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

### BA-083

**경로 provider·DAY/TRIP 최적화** — P1 / `planned` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-051](#ba-051), [BA-052](#ba-052), [BA-073](#ba-073)
- 기능 ID: `FR-OPT-02`, `FR-RTE-01`
- API: 해당 없음 (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: `439:3104`; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: route_matrix_snapshots · time windows · scope-specific optimizer policy

구현 순서:

1. provider/약관/쿼터/이동수단·오차/TTL·데이터 전송 범위를 먼저 확정한다
2. directed matrix의 누락/비대칭·영업 예외·예약 time window를 검증한다
3. bounded search로 DAY/TRIP 후보와 route evidence를 만들고 기존 preview/decision engine으로 적용한다

실패·안전 경계: 거리/속도로 임의 travel time을 성공 경로로 간주하지 않는다. 계산 budget 초과는 검증된 부분 해 또는 명확한 실패이며 잠금 완화는 없다.

필수 검증:

- `BA-083-T1`: 불가능한 구간·비대칭·time window·모든 잠금 조합을 검증한다
- `BA-083-T2`: DAY는 targetDate만, TRIP은 target 없음의 union과 capability를 검증한다
- `BA-083-T3`: preview/apply/route stale race와 정책 rollback을 검증한다

FE 인계·완료 증거: DAY/TRIP before/after·route unavailable·scope union examples와 provider attribution. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

### BA-084

**선호 해석·AI draft 보조·근거 설명** — P1 / `planned` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-051](#ba-051), [BA-060](#ba-060), [BA-073](#ba-073)
- 기능 ID: `FR-TRC-11`
- API: 해당 없음 (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: `440:3244`; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: proposed bounded preference schema · AI_PROVIDER=OPENAI adapter(apps/ai) · model/prompt version · evidence templates

구현 순서:

1. 모델 목적·입출력 allowlist·비용/token/timeout·외부 전송 정책을 정한다
2. LLM은 선호 해석과 검증된 evidence 문장만 만들고 canonical 조회·영업·route·잠금은 서버가 판정한다
3. hallucination/prompt injection corpus·KO/EN 검증·template fallback·kill switch를 구현한다

실패·안전 경계: 원문 일정/정밀 위치/secret·DB mutation/APPLY tool을 모델에 주지 않는다. structured ID·수치·사실 검증 실패는 template fallback이며 조용한 자동 일정 변경은0이다.

필수 검증:

- `BA-084-T1`: 유해 provider 지시·임의 ID/숫자/영업 주장 출력을 거부한다
- `BA-084-T2`: timeout·invalid JSON·budget 초과 시 결정적 fallback이 동작한다
- `BA-084-T3`: user approval 전 trip mutation0과 model OFF 핵심 흐름을 검증한다

FE 인계·완료 증거: AI 사용 표기·검증 실패·수동 대안·설명 examples, 수치 개선을 방문자 감소로 표현하지 않는 copy. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

### BA-085

**알림 목록·읽음·대상 유효성** — P1 / `planned` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-053](#ba-053), [BA-073](#ba-073)
- 기능 ID: `FR-NOT-01`, `FR-NOT-02`
- API: `listNotifications`, `markNotificationRead`, `markAllNotificationsRead` (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: `442:3344`; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: notifications · allowlisted deep_link · read_at · event delivery dedup

구현 순서:

1. 알림 type·생성 trigger·중복 key·보존 정책을 정한다
2. 목록/unread count·개별 읽음·모두 읽음을 owner 단위 원자 처리한다
3. 내부 상대경로만 허용하고 삭제된 trip/run의 알림은 안전하게 축소한다
4. 09-06 PM 검토 PM-016의 영향 계약·화면·실패 fixture를 검토하고 미해결이면 해당 경계를 확정하지 않는다

실패·안전 경계: REPLAY로 지금 혼잡 alert를 보내지 않는다. 외부 push/email 채널은 별도 계약이며 목록 구현만으로 발송 기능을 켜지 않는다.

필수 검증:

- `BA-085-T1`: 모두 읽음 반복/동시 새 알림의 기준 시점을 검증한다
- `BA-085-T2`: scheme/host/query/fragment·타 owner deep link를 거부한다
- `BA-085-T3`: OFF·삭제된 target·90일/read30일 TTL을 검증한다

FE 인계·완료 증거: S12 unread/empty/read-all·삭제 대상 fallback fixtures, client N개 mutation 대신 단일 operation. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

PM 검토 연결: [09-06 발견 사항](../project/PM_REVIEW_2026-09-06.md) — PM-016.

### BA-086

**영문 POI coverage·번역 품질** — P1 / `planned` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-022](#ba-022), [BA-073](#ba-073)
- 기능 ID: `FR-LOC-01`
- API: 해당 없음 (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: 해당 없음; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: place_localizations · source locale provenance · translation revision

구현 순서:

1. EngService 등 승인된 영문 source와 canonical ID 연결 품질을 확인한다
2. 누락 시 원문 fallback과 번역 출처를 명시한다
3. 고유명사·날짜·단위·길이·영업 사실의 KO/EN parity를 평가한다

실패·안전 경계: P0 KO/EN 앱 UI 지원과 영문 데이터 coverage 확장을 구분한다. 번역이 새로운 사실이나 지원하지 않는 locale capability를 만들지 않는다.

필수 검증:

- `BA-086-T1`: 동일 POI 언어별 ID/날짜/수치 parity를 검증한다
- `BA-086-T2`: 누락 번역 fallback·출처·권리 표기가 유지된다
- `BA-086-T3`: source 변경/삭제 때 오래된 번역 노출을 차단한다

FE 인계·완료 증거: 영문 coverage 보고서·fallback 기준과 긴 문자열 fixtures. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

### BA-087

**개인화 계측·학습·평가·실험** — P2 / `planned` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-033](#ba-033), [BA-051](#ba-051), [BA-073](#ba-073)
- 기능 ID: `FR-ML-01`
- API: 해당 없음 (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: 해당 없음; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: proposed impression lineage · feature/label dataset · model registry · experiment assignment

구현 순서:

1. served와 viewed·action을 owner/snapshot/policy에 연결할 계약·보존·삭제 lineage부터 만든다
2. 규칙→검증 집계→선형/트리 baseline을 시간 분리·point-in-time feature join으로 비교한다
3. 충분한 표본과 품질 기준 후 shadow→소규모 실험→확대하며 정책 rollback을 고정한다

실패·안전 경계: 데이터가 적으면 P0로 유지한다. 미노출을 negative로 쓰거나 CTR만으로 overtourism 개선을 주장하지 않는다. 노출 확률이 없으면 IPS 인과 개선 주장을 하지 않는다.

필수 검증:

- `BA-087-T1`: 위조 노출·미성숙 label·미래 feature/동일 owner 누수를 차단한다
- `BA-087-T2`: 삭제 owner가 export·feature·모델 lineage에 추적되고 제거된다
- `BA-087-T3`: 고정 holdout 품질·calibration·segment·지연·safety 기준과 실험 중단 조건을 검증한다

FE 인계·완료 증거: 계측 schema·attribution window·동의 정책·평가표. P2 model이 없어도 Live P0로 진행 가능하다. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

### BA-088

모델 분리는 BA-087의 데이터·평가 결과를 추가로 확인한다. worker 분리는 모델 학습 완료를 선행 조건으로 요구하지 않는다. 추천 계산 서비스 분리는 [ADR-0006](../decisions/ARCHITECTURE_DECISIONS.md#adr-0006)으로 P0에 선행 결정됐으므로 이 카드는 worker 분리와 학습 모델 service만 다룬다.

**worker·추천/예측 service 분리** — P2 / `planned` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-005](#ba-005), [BA-070](#ba-070)
- 기능 ID: `FR-ML-02`
- API: 해당 없음 (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: 해당 없음; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: proposed SQS/worker or model service · versioned inference protocol · delivery/outbox

구현 순서:

1. queue p95>30초 지속·lease DB부하10%·독립 scaling 요구 등 측정 trigger를 확인한다
2. 분리 대상만 protocol/version·auth·timeout·dedup·outbox·rollback을 설계한다
3. shadow parity·부하·장애 격리를 검증한 후 점진 전환한다

실패·안전 경계: 측정 근거 없이 Redis/Kafka/대규모 transformer를 필수로 만들지 않는다. 분산 이벤트가 APPLY의 단일 DB 원자성을 대신하지 않는다.

필수 검증:

- `BA-088-T1`: 중복/역순 delivery·network partition·old/new model version 호환을 검증한다
- `BA-088-T2`: service 장애 시 기존 결정적 경로로 fallback한다
- `BA-088-T3`: owner 삭제·snapshot lineage·trace redaction이 경계를 넘어 유지된다

FE 인계·완료 증거: 새 protocol의 FE 영향 유무, 장애 상태 examples·비용·운영 분리 ADR. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

## B10 · Live 최종 기능 단계

서울 adapter→Live API/탭→replay→전체 P0 gate. 위치 확장은 별도 선택이다.

### BA-090

**마지막 단계: 서울 Live adapter·area 매핑** — P0 / `planned` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-020](#ba-020), [BA-023](#ba-023), [BA-024](#ba-024), [BA-073](#ba-073)
- 기능 ID: `FR-LIV-08`
- API: 해당 없음 (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: 해당 없음; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: Seoul adapter · live_areas · mapping policy · quality incident registry

구현 순서:

1. 서울 전용 operation·공공누리 제1유형·공식 URL/attribution·품질 공지를 재확인한다
2. 승인된 area만 bounded 수집하고 observation/issue/freshness를 보존한다
3. AREA→PLACE mapping 확실성·coverage·fallback을 표현하고 미지원 POI는 UNAVAILABLE로 반환한다
4. 09-06 PM 검토 PM-012, PM-013의 영향 계약·화면·실패 fixture를 검토하고 미해결이면 해당 경계를 확정하지 않는다

실패·안전 경계: AREA 관측을 POI 실측으로 바꾸지 않는다. map provider 결정은 목록형 Live의 선행 조건이 아니다. 공통 source 인프라는 B03을 재사용한다.

필수 검증:

- `BA-090-T1`: Seoul XML/JSON fixture의 drift·429·incident·stale을 검증한다
- `BA-090-T2`: coverage 없는 POI를0 또는 임의 AREA 값으로 채우지 않는다
- `BA-090-T3`: source 장애 중 기존 trip CRUD/optimizer 독립성이 유지된다

FE 인계·완료 증거: 서울 정확한 출처·license URL·scope/mapping confidence·Live stale/unavailable fixtures. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

PM 검토 연결: [09-06 발견 사항](../project/PM_REVIEW_2026-09-06.md) — PM-012, PM-013.

### BA-091

**Live 탭 API·장소 검색·대안·후보 저장** — P0 / `planned` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-090](#ba-090), [BA-034](#ba-034), [BA-042](#ba-042)
- 기능 ID: `FR-LIV-01`, `FR-LIV-02`, `FR-LIV-03`, `FR-LIV-04`, `FR-LIV-05`, `FR-LIV-06`, `FR-LIV-09`, `FR-LIV-11`
- API: `queryLiveAreas`, `listLiveAreaPlaces`, `getLivePlace` (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: `418:2523`, `419:2617`, `420:2821`, `420:2950`; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: LiveQuery · coarse viewport · catalog relation reuse · candidate source LIVE

구현 순서:

1. 목록 우선 area query와 선택 유지·area별 place·Live detail projection을 만든다
2. searchPlaces→canonical 선택→getLivePlace coverage 흐름을 연결한다
3. relation NONE/CHECKING/UNKNOWN·비교 불가·후보 저장은 기존 공통 계약을 재사용한다
4. 09-06 PM 검토 PM-010, PM-012, PM-013, PM-020의 영향 계약·화면·실패 fixture를 검토하고 미해결이면 해당 경계를 확정하지 않는다

실패·안전 경계: viewport는 소수점3자리·축별 최소0.01도이며 URL/log/analytics 저장을 금지한다. Live 후보 저장도 일정/version을 바꾸지 않는다.

필수 검증:

- `BA-091-T1`: viewport exact/oversized/invalid·owner cursor·검색 coverage를 검증한다
- `BA-091-T2`: map OFF 목록과 relation 모든 상태·no fake delta를 E2E로 확인한다
- `BA-091-T3`: Live→candidate201/duplicate/retry에서 일정 미변경을 확인한다

FE 인계·완료 증거: S11 전체 상태와 승인된 map ON/OFF parity·attribution fixtures. Live UI 통합은 이 마지막 단계에만 활성화한다. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

PM 검토 연결: [09-06 발견 사항](../project/PM_REVIEW_2026-09-06.md) — PM-010, PM-012, PM-013, PM-020.

### BA-092

**Live replay·장애 fallback·전체 P0 최종 gate** — P0 / `planned` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-091](#ba-091)
- 기능 ID: `FR-LIV-07`
- API: 해당 없음 (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: `421:2850`; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: replay_manifests · replay_manifest_entries · checksums · final evidence ledger

구현 순서:

1. 승인 출처·scrub·capture window·checksum·entry order가 고정된 replay manifest를 검증한다
2. LIVE/REPLAY/STALE/UNAVAILABLE 전환과 persistent badge를 FE에 연결한다
3. BA-073 핵심 검수를 Live 포함 전체 P0로 재실행하고 같은 release의 PDF/API/실제 KTO 증거를 갱신한다
4. 09-06 PM 검토 PM-013, PM-023의 영향 계약·화면·실패 fixture를 검토하고 미해결이면 해당 경계를 확정하지 않는다

실패·안전 경계: replay는 실제 현재값/분산 성과/실제 KTO 호출 증거가 아니다. 이 단계 이후는 출시 검증·회귀 수정이며 새 비Live 기능은 다음 범위로 별도 선정한다.

필수 검증:

- `BA-092-T1`: checksum/manifest 누락·capture window 밖·scrub 실패 replay를 거부한다
- `BA-092-T2`: live↔replay 전환에서 데이터 namespace·label·comparison이 섞이지 않는다
- `BA-092-T3`: 전체 P0 익명 외부망·KO/EN·keyboard·출처·위치 OFF·rollback gate가 통과한다

FE 인계·완료 증거: 최종 Live E2E·화면·readiness와 미활성 P1/P2 목록. 제출 접수 증거는 실제 제출 후 별도로 기록한다. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

PM 검토 연결: [09-06 발견 사항](../project/PM_REVIEW_2026-09-06.md) — PM-013, PM-023.

### BA-093

**Live 이후 위치 동의·주변·재계획 확장** — P1 / `planned` / BE_AI_DRI 구현, FE_DRI 검토

- 선행: [BA-091](#ba-091), [BA-092](#ba-092)
- 기능 ID: `FR-LIV-10`, `FR-NBY-01`
- API: 해당 없음 (미기재 작업은 내부 처리 또는 별도 계약 제안)
- Figma: `442:3370`, `501:3750`; FCR: 해당 없음. 추가 상태는 기능 인벤토리·FCR에서 추적한다.
- 데이터·정책: proposed consent/minimized location request · nearby query · preview-only replan

구현 순서:

1. 위치 목적·정밀도·일회성/철회·TTL·외부 전송·법무 검토를 먼저 닫는다
2. 가능한 area/grid 수준으로 축소하고 거부/미지원은 목록·수동 검색으로 복구한다
3. 재계획은 검증된 proposal만 만들고 동일 APPLY 승인 경계를 유지한다
4. 09-06 PM 검토 PM-012의 영향 계약·화면·실패 fixture를 검토하고 미해결이면 해당 경계를 확정하지 않는다

실패·안전 경계: 공모전 profile에는 활성화하지 않는다. background 위치 수집이나 서버 영구 위치 저장을 기본으로 넣지 않는다. 이 기능은 Live 그룹의 선택 확장이다.

필수 검증:

- `BA-093-T1`: 동의 전/철회/거절/공모전 환경에서 위치 요청과 전송이0이다
- `BA-093-T2`: 요청 종료 후 위치/log/cache/provider 전송 최소화를 검증한다
- `BA-093-T3`: 재계획 실패·source stale·lock conflict가 일정을 바꾸지 않는다

FE 인계·완료 증거: 위치 permission·denied·철회·privacy 안내·preview fixtures와 새 계약 검토. 실제 API/DB test report와 상대 재현 확인을 연결한 뒤 완료 처리한다.

## 이전 초안 ticket을 찾는 방법

기존 ticket은 이관 참조이며 진행 순서로 사용하지 않는다. 미완료 issue를 가져올 때 새 BA ID와 원래 기능 ID를 함께 적는다. FE ticket의 기존 ID는 화면 카탈로그에서 계속 찾을 수 있다.

| 이전 묶음 | 현재 작업 |
| --- | --- |
| CON/ARC/GOV/DX, BE-001~004, INF-001~003 | BA-000~006 |
| BE-101, BE-105, SEC-101 | BA-010~012, BA-060 |
| BE-102/103/104/106 | BA-022/030/031/060 |
| BE-201/202/203/204, AN-201 | BA-022/032/033/034 |
| BE-301~306, QA-301~303 | BA-031/040/041/042 |
| BE-401/402/403/406/407, OPS-401 | BA-020~024 공통 데이터, BA-092 replay |
| BE-404/405 | BA-090/091 Live 전용; relation은 BA-024로 선행 |
| BE-501~507, QA-501/502 | BA-050~053 |
| BE-601/602, OPS/REL/SEC/CMP-60x | BA-070~073, Live 후 BA-092에서 최종 재검증 |
| BE-P1-101~106 및 Search/Worker/English/ML backlog | BA-080~088, BA-093 |

## 작업 기록 템플릿

```text
taskId: BA-...
status: in-progress
scope: 이번에 구현할 command/상태
contract: operationId, schema revision, generated client SHA
migration: 파일, upgrade/rollback 호환 범위
implementation: 실제 code path
verification: test ID → 실행 명령 → report → 결과(not run 포함)
handoff: FE reviewer, fixture, Figma node/state, E2E evidence
openDecision: 결정 ID, 안전한 기본값, 필요한 선행 조건
next: 다음 acceptance 또는 의존 작업
```

날짜별 개발 계획 대신 이 상태와 다음 acceptance를 갱신한다. 실제 관측/실험/배포·제출 증거의 시각은 데이터 진실성을 위해 기록한다.

PM 검토 연결: [09-06 발견 사항](../project/PM_REVIEW_2026-09-06.md) — PM-012.
