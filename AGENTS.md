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
| `docs-contract` | 모든 main PR/push | `validate_docs.py`(Problem code↔FE mapping, backend·frontend plan 검증 포함), `python3 -m unittest discover -s scripts/tests`, plan/Canvas 검증, markdownlint, Redocly, AJV(example 통과 + `docs/contracts/events-negative/`의 5건이 **거절되는지**를 `check_event_negatives.py`가 판정한다 — `ajv test --invalid`는 glob 0건에서 exit 0이므로 exit code를 통과로 쓰지 않고 디렉터리 목록과 대조한다), PR 전용 OpenAPI breaking diff(승인된 예외만 `docs/api/oasdiff-ignore.txt`로 면제하며 `test_oasdiff_exceptions.py`가 이유·승인자·추적 이슈와 stale 행을 검사한다), report runner 부정·wrapper 실행 검사, `check_actual_call_evidence.py`(CMP-KTO-003 — staging smoke의 actual-call report를 판정한다. 증거가 없으면 `actual_call=blocked`이고 **통과로 세지 않는다**; local 환경·`mock`/`replay`/`fixture` source·거절된 호출·다른 release의 증거는 전부 실패다. 배포·제출 경로는 같은 script를 `--require-verified`로 돌려 증거 없는 release를 막는다) | BA-000-T1·T2(operation·기능 ID coverage, DAG·B10 순서), BA-004-T1/T2 로컬 재현 검사, FE-003 code mapping, frontend plan DAG. **BA-000-T3은 여기가 아니라 `docker-integration`이 실행한다** — `api:check` 안의 `check-examples.mjs`가 `packages/contracts/fixtures/negative/`의 6건을 ajv에 먹여 **거부되지 않으면 실패**시킨다(`negative_rejected=6`). 이 행의 `validate_product_contract_alignment`는 discriminator와 variant별 `const`·`required`를 **고정**할 뿐이고, 구조 고정과 거부 증명은 다르다 | 실행 중 |
| `docker-integration` | 모든 main PR/push | `integration-test.sh`: verifier→`api-quality`·`ai-quality`·report 집계(실행 ID·freshness)·web·client diff·scan·`infra-plan`(`check_infra_report.py`가 판정하며 현재 `infra_check=blocked`, **통과로 세지 않는다**)·egress-denied(`check_egress_report.py`가 판정한다 — probe가 출력하는 `outbound_network=denied` 토큰을 요구하고, 아무 판정도 내지 않은 실행은 통과로 세지 않는다. exit code만으로는 probe가 probe이기를 그만둔 경우를 볼 수 없다)·E2E | BA-006-T2(`verify_target_stack.py`의 `check_browser_bundle_inputs` — browser-facing build가 credential을 **건네받지 않음**을 compose 정규화 config에서 고정한다. 게이트는 자기 secret이 없으므로(`integration.yml`이 아무것도 넘기지 않는다) 값을 스캔할 수 없고, **건네받은 적이 없다**를 대신 증명한다 — layer는 build가 받은 것을 간직하고 Vite는 `VITE_*`를 번들에 inline하므로 입력이 곧 유출 경로다. **세 면 중 둘이다**: 번들과 image layer는 입력으로 덮이지만 **log는 이 검사가 보지 않는다**. 값 스캔은 `scripts/check_secret_exposure.py`가 하며 실제 secret이 있는 환경(local·staging)에서만 의미가 있어 **아직 어느 게이트도 돌리지 않는다** — `infra_check=blocked`와 같은 정직한 공백이고 staging이 서는 `BA-071`이 그것을 돌린다) · 아래 suite 전체 | 실행 중 (`integration_mode=full-docker`) |
| `api-quality` (workflow) | `apps/api/**`, `apps/ai/contracts/**`, `apps/ai/tests/recommendation/fixtures/**`, `apps/ai/tests/recommendation/manifest.json`, `apps/ai/src/nullnull_ai/policy/**`, `docs/api/openapi.yaml`, `backend-plan.json`, `packages/contracts/fixtures/**`, report runner/검사 push/PR | Gradle `test integrationTest openapiContractTest recommendationTest` + JUnit/ready-card ID 집계 | BA-001-T2(`ArchitectureRulesTest.modulesNeverReachIntoAnotherModulesInfrastructure`), REC-ARCH-01, REC-DATA-02, REC-DATA-05, REC-JOB-01, REC-SEC-03, BA-000-T3(`ProblemFixtureContractTest` — fixture의 code↔status/retryable/title/type을 `ProblemCode`에 고정, 429 producer 부재 서술도 양방향 고정), BA-001-T2, BA-003-T1~T3(`DemoReadinessContractTest` — demo readiness fixture를 `DemoCapabilityQuery`의 실제 출력에 고정), BA-005-T1~T3, BA-010-T1~T3(SessionSafetyIT·SessionTimeIT·SessionContractTest, backend-plan.json), BA-011-T1~T3(OwnerPreferencesIT·OwnerPreferencesConcurrencyIT·OwnerContractTest), BA-012-T1~T3(DeletionIT·DeletionJobIT·TombstoneReapplierTest·SessionContractTest), BA-020-T1~T3(ProviderKitTest·CollectorRunRecorderTest·SourceRegistryIT·`ProviderOutcomeVocabularyIT` — 검증 어휘가 세 곳에 따로 선언돼 있다: `ProviderResponseValidator.Outcome`, `IngestAudit.ValidationResult`, 그리고 `api_ingest_validation_check` CHECK. `CollectorRunRecorder`가 `valueOf(outcome.name())`으로 둘을 잇고 행은 CHECK를 만족해야 하므로, 한 곳에만 값을 더하면 **그 outcome을 실제로 내는 분기에서만** 런타임에 터진다. 세 집합을 양방향으로 대조한다), BA-021-T1/T2(KtoDetailResponseValidatorTest·KtoKorServicePropertiesTest·KtoPlaceDetailGatewayIT; BA-021-T3은 staging 실제 호출 증거 대기라 미등록), BA-022-T1~T3(CatalogFoundationIT·CatalogPlaceApiIT·CatalogPublicationPropertiesTest·CatalogPlaceProjectionServiceTest·`KtoCanonicalIngestMainTest` — `ktoCanonicalIngest`가 `ktoSmoke`와 `ktoForecastSmoke` 사이의 빠진 단계다. `findFreshRequest`가 join하는 `place_external_refs`를 만드는 것은 canonical ingest뿐인데 production 호출자가 없어서 C4 smoke가 돌 수 없었다. evidence 줄에 provider text가 새지 않는지 변이로 고정한다), BA-023-T1~T3(CrowdProvenanceProjectionTest·CrowdForecastApiIT·KtoCrowdForecastGatewayIT·FlywayMigrationIT·`CrowdQualityFlagCoverageIT` — `quality_flags`의 다섯 값 **각각**이 (a) 생산 가능함을 실제로 보이거나 (b) 응답이 거절돼 snapshot 자체가 없음을 실제로 보이거나 (c) 생산자 대기 + 소유 카드로 등록돼 있거나 셋 중 하나임을 요구하고, enum과 CHECK를 양방향 대조한다. **"통과하지만 증명하지 않는 검사"의 짝인 "구현됐지만 발화할 수 없는 가드"를 잡는 장치다**; `KtoCrowdForecastGatewayIT`가 나간 요청의 `areaCd`/`signguCd`를 단언해 #109의 결합 코드 회귀를 막는다), BA-024-T1~T7(`PlaceRelationFoundationIT`·`CatalogRelationQueryIT`·`CatalogRelatedPlacesApiIT`·`CatalogRelationProjectionServiceTest`·`RelationStateCoverageTest` — `place_relations`는 pair당이 아니라 **source당 한 행**이라(`(source, target, source_code)` unique) 두 출처가 한 장소를 지목하면 두 행이고, projection이 그 장소를 두 번 냈다. 내부 계약의 gateway post-condition이 *"related places must be merged, not repeated"* 로 이미 금지하던 것인데 병합하는 쪽이 없었다. **`T2`가 HTTP가 아니라 24개 순열 전수 unit test인 이유가 측정에서 나왔다**: 정렬을 제거하면 unit case는 빨개지는데 IT 전체는 초록으로 남는다 — `JdbcCatalogRelationQuery`가 이미 `(tier, target)` 순으로 주므로 IT는 *이 PostgreSQL이 실제로 내놓는* 도착 순서만 몰 수 있다. `T7`은 다섯 값을 성질로 가른다: `NONE`은 오너 결정(§9)으로 배제, `EXACT`는 유일한 provider가 미승인, `CHECKING`은 검증 job 부재, `SIMILAR`·`UNKNOWN`만 생산된다. 서버 enum과 계약 enum을 양방향 대조한다) · BA-025-T1~T5(`CuratedHoursImportIT` — `place_hours`에 행을 만드는 production 경로가 없어 이미 등록된 `getCandidateTripMatches`가 운영에서 항상 `UNKNOWN`이던 것을 닫는다. `T2`와 `T3`를 나눈 것이 측정으로 정당화됐다: supersede를 delete로 바꾸면 `T3`만 빨갛고 **`T2`는 통과한다** — 현재 관측이 하나인 것은 맞으니까. 근거의 이력이 사라지는 것을 잡는 것은 `T3`뿐이다), BA-060-T4·T6·T7·T9~T13(`ArchitectureRulesTest`·`TripImportIT` — `T11`이 단언하는 것은 검사가 아니라 **순서**다. 만료 pre-check가 guard **앞**에 있어야 만료된 draft가 410을 내고, 뒤에 있으면 guard가 저장된 201을 replay한다. 그 test가 의미를 가지려면 draft 만료와 key 보존기간이 같이 끝나면 안 되므로(둘 다 24시간) fixture는 clock을 미는 대신 `expires_at`을 지난 값으로 심는다 — 25시간을 밀면 호출자 session이 먼저 만료돼 draft에 닿지도 못하고 `MutableClock`은 되돌릴 수 없어 뒤따르는 모든 test가 빨개진다. 창이 닫힌 것은 **행의 속성**이지 시간의 속성이 아니다), BA-030-T1~T4(`TripCreationRulesTest`·`TripCreationIT`·`InterestVocabularyContractTest`·`TripDetailFailsClosedIT` — T4는 catalog 공개 게이트가 닫힌 상태의 `getTrip`이다. item 없는 trip은 답하고 item 있는 trip은 503이며 seedItems create는 **쓰기 전에** 거절한다는 세 상태를 한 case로 묶었다: 따로 두면 "비었으면 200"이 임의 규칙으로 읽혀 다음 사람이 전부 503으로 만들거나(만들 수 있는 답을 거부) item을 빼고 200을 주게 된다(#162가 배제한 silent-empty). 어휘는 계약의 `x-nullnull-interest-codes`와 `InterestVocabulary`가 같은 13개임을 양방향으로 고정한다. `enum`이 아닌 extension인 이유는 어휘의 정본이 FE 소유 Figma chip 목록이라 chip 추가가 breaking 변경이 되면 안 되기 때문이고, extension은 generated client에 union type을 주지 못하므로 이 test 말고는 둘의 drift를 잡는 것이 없다), BA-050-T1~T8(`OptimizationRunIT`·`OptimizationWorkerIT`·`OptimizationCapabilityOffIT`·`RecommendationRequestShapeTest` — `OptimizationCapabilityOffIT`는 **flag 기본값(꺼짐)** 상태를 검사한다. `OptimizationRunIT`가 켜고 돌리므로 이 파일이 없으면 **제출 profile이 실제로 쓰는 설정을 아무것도 안 본다**(`FeedFailsClosedIT`와 같은 이유다). `RecommendationRequestShapeTest`는 `apps/ai` 요청의 **직렬화된 key 집합이 선언된 component 집합과 같음**을 고정한다 — 선례였던 `jsonPath("$.ownerId").doesNotExist()`는 `ExplanationRenderRequest`에 그 component가 **없어서 어떤 회귀에서도 실패할 수 없는** 단언이었고, 없는 것을 없다고 단언하는 것은 영원히 초록이다. 이 카드는 `items/propose`를 **부르지 않는다** — 호출과 READY 쓰기는 `BA-051`이고, 여기는 READY를 막는 gate까지다), BA-033-T1·T2(`FeedFeedbackIT`·`FeedFeedbackBucketTest` — `recordFeedFeedback`. dedup 분 bucket이 **Java에 있는 이유**는 `date_trunc('minute', timestamptz)`가 IMMUTABLE이 아니라 **STABLE**이라 PostgreSQL이 generated column·index expression·CHECK 어디에도 받지 않기 때문이다. 그래서 *"저장된 bucket이 `occurred_at`이 속한 분인가"* 는 DB가 스스로 검사할 수 없고 test가 고정한다), BA-033-T3(`ArchitectureRulesTest.analyticsIsNeverOnAProductCommandsPath` — 제품 command가 analytics에 **닿을 경로 자체가 없음**을 고정한다. ingest를 실패시키고 command가 사는 것을 보는 방식은 *한 command에 대해 그날 하루* 증명하지만, 이건 module 밖의 코드가 analytics를 **이름 부르는 순간** 빨개진다), BA-032-T4(`CuratedPostImportIT` — `A-031` 운영 스크립트. `BA-032` step 1이 curated post를 **조회**만 서술하고 **만드는 쪽이 어느 카드에도 없었다**(#183). plan 파일 하나가 transaction 하나이고 PRIMARY 하나 검사를 **plan 단계에서** 한다 — `V022` trigger가 파일 중간에서 터지면 운영자가 어디까지 들어갔는지 추적해야 한다), BA-070-T1(`OwnerIsolationMatrixIT` — trip을 이름에 담은 operation **전부**에 대해 *남의 trip*과 *존재한 적 없는 trip*의 응답이 **같은지** 본다. 403은 거절이면서도 caller가 실제로 묻던 것(그 id가 있는가)에 답해버리므로 불변식 11의 기준은 거절이 아니라 **구별 불가능**이다. 호출 목록은 계약에서 읽으므로 새 `/trips/{tripId}` 경로가 **존재만으로** 이 matrix에 들어온다 — 손으로 적은 목록은 조용히 낡고 그게 이 test가 막으려는 실패다. **세 번째 호출이 이것을 공허하지 않게 만든다**: 두 응답은 요청이 소유권 판단에 닿기 전에 죽어서도 쉽게 일치하므로(거절되는 body, 받지 않는 content type, 요구하는 ETag) 자기 trip에 대한 같은 호출이 **달라야** 한다. 첫 실행이 정확히 거기서 빨갰고 옳았다 — 16 중 14가 소유권이 아니라 검증을 재고 있었고 그중 `deleteTrip`은 뒤따르는 모든 operation의 fixture를 지우고 있었다) · BA-034-T1~T3(`CandidateIT` — partial unique 수렴·DISMISSED 재저장·SCHEDULED 거부·owner 분리, fixture↔서버 shape 대조 포함), BA-012-T2 전이 소거(`OwnerDataErasureIT` — soft delete 단계에서 trip aggregate 일곱 table이 비는지. 스키마 sweep이 못 보는 범위다), BA-032-T1~T3(`FeedIT`·`FeedFailsClosedIT` — `replaceTripInterests` 없이. feed는 catalog 공개 게이트가 닫혀 있으면 503이고 그 동작도 검사한다), BA-031-T1~T3(`TripScheduleRulesTest`·`TripMutationIT` — `updateTrip`·`deleteTrip`·`replaceTripInterests`. 교체는 merge가 아닌 전체 치환이고 빈 집합도 유효하며 version은 한 번만 오른다), BA-033-T1~T3(`AnalyticsIngestIT`·`EventBatchValidatorTest`·`EventContractTest` — `ingestEventBatch`만. batch는 정본 `docs/contracts/events.schema.json`으로 검증하고 Java로 다시 구현하지 않는다. `EventContractTest`가 route allowlist와 event 이름이 OpenAPI와 정본에서 같은 목록임을 고정한다 — `baseEvent`의 `properties`가 무제약이라 branch 없는 이름이 추가되면 그 event의 payload는 아무 검사도 받지 않는다. `recordFeedFeedback`은 구현됐으나 **IMPRESSION·OPEN만 기록하고 HIDE·LIKE·DISLIKE는 거절한다** — PM-011이 *읽어올 때 무엇인지*를 정하지 않았고, 받아서 저장하면 제품이 보여줄 수도 되돌릴 수도 없는 상태를 만들면서 client는 그것이 있다고 믿는다. 계약 enum에는 다섯이 그대로 남는다(보낼 수 있는 값을 빼는 것은 breaking). **그래도 BA-033 전체 완료로 쓰지 않는다** — `BA-033-T3`의 *"analytics 장애가 제품 command를 실패시키지 않는다"* 는 별개이고 PM-011도 열려 있다), 내부 계약 parity(5 operation), gateway post-condition, ITEM fixture parity(LockChecks·ProposalRevalidator), policy pin parity, manifest `gradle:*` 경로 실재(`ManifestTestPathParityTest` — 실행되지만 `@DisplayName`에 ID가 없어 집계에 잡히지 않으므로 `REC-CI-4`는 등록 ID로 쓰지 않는다) | 실행 중 |
| `ai-quality` (workflow) | `apps/ai/**` push/PR | ruff, mypy strict, pytest(REC corpus, `evaluation.json`), 계약 JSON sync | `tests/recommendation/manifest.json`의 `implementedTestIds` 중 `suite: pytest` 행(`gradle:*` 행은 `api-quality`가 검증). **컨테이너 밖에서도 확인한다** — `check_test_reports.py`가 manifest의 pytest 행과 `evaluation.json`의 `implementedTestIds`를 양방향으로 대조하므로, manifest가 주장하는데 corpus가 돌리지 않은 ID도 corpus가 돌렸는데 manifest에 없는 ID도 실패다. `missingTestIds`는 아직 구현되지 않은 REC 목록이라 비어 있기를 요구하지 않는다 | 실행 중 |
| `apps/web` suite (`docker-integration` 내부) | 모든 main PR/push | `verify:ci`: tokens drift, eslint, prettier, tsc, `packages/*` typecheck(tsconfig 부재·0건 매칭을 실패로 처리), vitest, build, bundle budget(gzip 상한·빌드 부재·0건 매칭을 실패로 처리). fixture↔OpenAPI ajv 검증, dist MSW 부재 단언, Storybook 커버리지, forced-colors selection, 기본 msw 핸들러 존재 검사 포함 | FE-001~005, FE-101~106, FE-201, FE-202, FE-301~307, FE-404, FE-501, FE-502, FE-506, FE-601, FE-602 | 실행 중 |
| `apps/web` E2E (`docker-integration` 내부) | 모든 main PR/push | Playwright: shell·온보딩·프로필 실동작, 360px·200% zoom·긴 문구 reflow, 터치 타깃 44px | FE-601, FE-602 | 실행 중 |

등록 규칙:

1. 새 REC ID는 `apps/ai/tests/recommendation/manifest.json`의 `implementedTestIds`와 fixture sha256에 추가하고, Spring 쪽 검증(REC-INT/SEC/JOB/FEED-04)은 해당 Gradle suite 이름을 `docs/engineering/TEST_STRATEGY.md#12`에 연결한다.
2. BA-xxx-Tn acceptance는 구현 PR에서 실제 test class/함수 이름과 report 경로를 카드에 적고 `backend-plan.json` status를 올린다.

   **`integration-ready`와 `verified`는 다른 것을 증명한다.** `check_test_reports.py`는 둘 모두에 대해 acceptance ID가 JUnit testcase 이름에 **나타나는지**만 본다. 나타나는 것은 증명하는 것이 아니다 — `BA-002-T3`은 ID를 단 testcase가 **20개**인데 전부 두 절 중 첫 절만 덮는다.

   그래서 `verified`는 **acceptance ID마다 그것을 증명하는 testcase를 지목하도록** 요구한다(`evidence.provenBy`). 지목한 이름은 (a) 그 ID를 담고 있어야 하고 (b) 실제 report에 존재해야 한다. **test가 없는 절은 지목할 이름이 없으므로 승격이 그 자리에서 막힌다** — 이것이 이 칸의 존재 이유이고, 3의 한-절 규칙과 맞물린다.

   **기계가 잡는 것은 "거짓말한 이름"뿐이다.** 지목한 testcase가 실재하는지, 그 ID를 담고 있는지는 validator가 본다. 그러나 *그 testcase가 그 절을 정말 증명하는가*는 볼 수 없다 — **강제력은 표를 채우는 행위 자체에서 나온다.** 채우는 사람이 각 절을 그 ID를 단 testcase 본문과 대조해야 하고, 대조할 것이 없으면 칸이 비고 승격이 막힌다. 이 절차를 생략하고 이름만 옮겨 적으면 `integration-ready`와 같은 강도로 되돌아간다.

   `evidence.reviewer`는 **요구하지 않는다.** 이 저장소는 두 required check가 green이면 사람 승인 없이 auto-merge한다(원칙 15). 채우면 일어나지 않은 검토를 기록하게 되는 칸이므로 없앴다. task 수준의 `reviewer`(RACI 역할)는 그대로 남는다.
3. **acceptance assertion은 한 절만 쓴다.** `check_test_reports.py`는 `tests[].id`가 JUnit 이름에 **나타나는지**만 보므로, 한 ID에 여러 절을 묶으면 그중 **아무 절이나** 증명하는 test 하나로 그 ID가 충족된다 — 집계기가 나머지를 볼 방법이 없다. "A하고 B한다"가 필요하면 `T3`(A)·`T4`(B)로 나눈다. 그러면 B를 증명하는 test가 없을 때 `integration-ready` 승격이 **그 자리에서** 막힌다.

   **`backend` push의 초록은 required가 아니다.** trigger를 실제로 읽으면 `docs-contract`와 `docker-integration`은 **`main` 대상 PR/push에서만** 돌고, `api-quality`만 `push: [main, backend]`라 브랜치 push에도 돈다. 그래서 **밀면 초록이 하나 켜지는데 required 둘은 아무것도 돌지 않은 상태**다 — *"밀었더니 초록"* 으로 읽히기 가장 쉬운 자리다. **게이트는 PR이 있어야 돈다.**

   **고칠 수 없는 파일에 규칙을 적지 마라.** Flyway migration은 적용되면 checksum이 고정돼 **주석을 정정할 수 없다.** 그래서 migration 주석은 *그 순간의 의도 기록*이지 살아 있는 명세가 아니다. 같은 규칙이 두 곳에 있으면 **둘 중 하나가 반드시 먼저 상하고**, 고칠 수 없는 쪽이 남는다. 오늘 이 모양을 셋 만났다 — `V025` 주석의 hydration 규칙, `BA-005`의 pool 상수 넷(전역 합계가 파일 넷에 흩어짐), `AWAITING_THEIR_SLICE`의 `"BA-050 feed slice"` 라벨(BA-050에 feed가 없다). 규칙은 **고칠 수 있는 곳 한 군데**에 두고 migration 주석은 그곳을 가리킨다.

   **계약이 범위를 안 정한 열은 "자유"가 아니라 "출처를 찾아라"다.** `optimization_proposals.crowd_delta`가 `numeric(5, 4)`로 들어갈 뻔했다 — 최대 ±9.9999인데 그 델타의 출처인 `V011 crowd_snapshots.value`는 `numeric(14, 4)`다. **아무도 재지 않은 숫자를 지키려고 실제 측정을 거부하는 열**이 될 뻔했고, 이유는 계약의 `crowdDelta`에 범위가 없어 *"안 정했으니 적당히"* 로 읽은 것이다. 같은 편집에서 `comparison_reason_code varchar(40)`도 지어냈다가 계약의 `maxLength 100`으로 맞췄다. 범위가 비어 있으면 **그 값이 어디서 오는지를 찾아 거기에 맞추고 출처를 주석에 적는다.** migration은 적용되면 고칠 수 없으므로 이 자리의 임의 기입은 되돌릴 수 없다. **다만 "정본이 비었다"와 "정본이 틀렸다"는 대응이 다르다** — 전자는 출처를 찾아 맞추는 것이고, 후자는 정본을 고치는 것이다. 같은 migration에서 `optimization_changes.trip_item_id`가 `ERD`에 FK로 적혀 있었는데 계약은 그 id를 *"the preview reserves"* 라고 말한다 — FK를 걸면 id를 발명하는 바로 그 operation만 거부된다. 거기서는 열을 맞추는 것이 아니라 **`ERD`를 고치는 것**이 답이었다. 비어 있으면 채우고, 어긋나 있으면 어느 쪽이 틀렸는지를 먼저 정한다.

   **생산자 없음에도 두 종류가 있다.** `BA-042-T7`을 쓰며 `SIMILAR`과 `CHECKING`을 같이 "생산자 없음"으로 등록했는데 coverage test가 거절했다 — **`CHECKING`은 `SlotEvaluateResponse.State`에 있다.** 둘은 다르게 부재한다: `SIMILAR`은 **서비스 어휘에 아예 없어** enum 교차검증으로 증명되고, `CHECKING`은 **어휘엔 있고 입력이 없을 뿐**이라 enum이 아무 말도 못 한다. 그래서 후자는 **그것을 증명하는 test를 지목해야 한다**(나가는 요청의 `checking`이 항상 false임을 단언). 규칙: **enum이 검사할 수 없는 주장은 주석이 아니라 test를 가리킨다.**

   **거울상도 있다: 절은 증명됐는데 ID가 없는 경우.** `BA-042-T3`(#165 Q2 전이 + #199 잠금)은 `TripItemReplaceIT`의 네 case가 이미 증명하는데 그 이름들이 `BA-040` ID만 달고 있어 **집계기가 아무것도 못 본다.** 카드가 약속한 것을 코드가 지키는데 기계가 볼 방법이 없는 상태다. **한 test가 두 카드의 절을 증명하는 것은 정상이고**, `@DisplayName`에 두 ID를 같이 달고 각 카드의 `provenBy`가 그 이름을 지목하면 된다 — 다만 **이름만 옮겨 적지 말고 본문과 대조한 뒤** 단다.

   **쪼개면 미해결 하나가 드러난다(미해결임을 드러내는 것이 목적이다).** `check_test_reports.py`의 `read_junit`은 기본으로 `GRADLE_SUITES`(`test`·`integrationTest`·`openapiContractTest`·`recommendationTest`) **네 디렉터리만** 읽는다. **Python 쪽은 `--script-junit-dir`로, CI 게이트 판정 쪽은 `--gate-junit-dir`로 닫았다. Playwright 쪽은 절반이다** — `playwright.config.ts`가 CI에서 JUnit을 내고 `compose.integration.yml`이 그 디렉터리를 밖으로 내보내며 집계기에 `--e2e-junit-dir`가 있는데, **`integration-test.sh`가 그 flag를 넘기지 않는다.** 일부러다: 집계기가 `:161`이고 e2e는 `:289`라 지금 넘기면 **아직 생산되지 않은 report를 읽는다**(`--run-start` freshness가 있어 조용히 초록이 되진 않고 빨개진다). 그리고 **지금 회수할 것이 없다** — e2e 제목이 단 것은 `BA-010`·`BA-011`·`BA-012`처럼 **카드 ID**이고 집계기 regex는 `BA-\d{3}-T\d+`를 찾는다. 그 셋은 이미 JUnit으로 `integration-ready`다. **FE 소유 acceptance ID가 실제로 생기는 날** 순서를 고치고 flag를 넘긴다 — `scripts/run_script_tests.py`가 `scripts/tests`를 JUnit XML로 내보내고 `api-quality`가 그것을 같이 먹인다. 그래서 build toolchain·target-stack marker·CI wrapper처럼 **Java로는 증명할 수 없는 acceptance도 이제 집계된다**(`BA-001-T3`이 첫 사례다). docstring이 acceptance ID로 **시작할 때만** 주장으로 세며, 산문 속 언급은 method 이름으로 보고돼 regex에 걸리지 않는다 — 이 저장소에 그런 docstring이 이미 있다. **게이트 판정은 `scripts/record_gate_evidence.py`가 받는다** — testcase가 단언할 수 없는 것(`BA-004-T3`의 *"실제 Compose에서 재현"*)을 게이트가 증명할 때, **그 게이트가 실제로 낸 verdict token이 report에 있을 때만** 기록한다. 토큰이 없으면 기록하지 않고 실패한다 — 없으면 *"shell script의 한 줄이 실행됐으므로 acceptance가 증명됐다"* 가 되고 그게 rubber stamp다. **증거는 집계보다 먼저 생산돼야 한다**: 이 판정을 집계기 뒤에 두면 파일은 증거처럼 보이는데 집계기는 매번 못 읽는다.

   **한 곳에서만 보이는 증거는 한 곳에서만 물어라.** `api-quality`는 full Compose를 띄우지 않아 `gateChecks`를 만들 수 없으므로 `--backend-plan`을 **주지 않는다**. 볼 수 없는 답을 묻는 검사는 없는 것보다 나쁘다 — 사람이 그 빨간색을 무시하는 법을 배운다. 카드 완결성은 required gate의 질문이다. 그래서 **FE가 소유한 acceptance ID는 그가 실제로 구현해도 집계기에 나타나지 않는다** — `BA-040-T4`(keyboard/focus E2E)가 Playwright라 그렇고, 그 카드를 `integration-ready`로 올리려면 그 ID가 JUnit 이름에 있어야 하므로 **승격이 영원히 막힌다.** `BA-004`의 Python test가 `scripts/tests`에 있어 Java 집계기만 보면 0건이던 것과 같은 모양이고, 이번에는 소유자가 FE다. **`BA-004` 쪽은 `--script-junit-dir`로 닫혔고 FE 쪽도 장치는 섰다**(위 `--e2e-junit-dir` 항목) — 남은 것은 배선 순서와, **`BA-040-T4`를 증명할 E2E 자체가 없다**는 것이다. 지금 있는 keyboard test는 404 페이지 링크 포커스이지 일정 편집이 아니고, 거기 그 ID를 달면 *"ID는 있는데 절이 증명 안 됨"* 이 된다. 다만 **"FE plan으로 옮기면 된다"는 답이 아니다**: `check_test_reports.py`는 backend plan의 ID를 **실제 JUnit testcase 이름**과 대조하는데, `validate_frontend_plan.py`는 `verified` 카드의 `evidence.testIds`가 **그 카드 자신의 `tests[].id`와 같은지**만 보고 `report`·`contractSha`·`reviewer`는 빈 문자열이 아닌지만 본다 — report를 열지도, test 실행과 대조하지도 않는다. 옮기면 "집계기가 못 보는 ID"가 "아무것도 검증하지 않는 ID"가 된다.

   실제로 걸린 둘: `BA-002-T3`(*"transaction 중간 장애는 전체 rollback하며 **구버전 app 호환성이 유지된다**"*)은 `integration-ready`인데 ID를 단 testcase **20개**가 전부 첫 절이고 둘째 절을 다루는 것이 없다. `BA-034-T1`(*"**두 tap**·서로 다른 key 동시 요청·다른 post 같은 POI"*)은 세 절 중 첫 절이 비어 있었다 — 동시성 case는 일부러 key를 다르게 주므로 대체가 되지 않는다(index가 없어도 두 tap은 한 행, guard가 없어도 동시성은 한 행이다). 기존 카드를 일괄로 쪼개지는 않고 status를 올릴 때 절이 다 덮였는지 본다.
4. 새 app 디렉터리나 suite가 생기면 workflow path filter, `compose.integration.yml` service, `scripts/integration-test.sh` 실행 단계, 이 표를 같은 PR에서 바꾼다.

   **새 migration을 넣으면 `FlywayMigrationIT`의 두 곳이 같이 움직인다.** `previousSchemaUpgradesToTheLatestVersion`은 *직전 버전까지 migrate → 모든 table에 행을 넣고 → 최신까지 migrate*한다. 그래서 `populateEveryTable`의 table 목록은 **항상 마지막 migration의 직전까지**를 담는다 — `V025`를 넣는 PR은 `V024`가 만든 table을 **그때** 채워야 하고, `V025` 자신의 table은 `V026`이 생길 때 들어간다. **이건 `V025`를 커밋하는 쪽의 몫이다**: 앞 migration의 주인이 미리 넣으면 그 PR의 CI에는 뒤 migration이 없어 존재하지 않는 table에 INSERT한다.

   행 수 단언(`seededAfterPreviousSchema`)도 같은 이유로 움직인다. **숫자가 움직이는 것이 그 장치가 작동하는 방식**이므로 왜 바뀌었는지 주석에 적고 지우지 않는다 — 그 단언이 "조용히 데이터를 심는 migration"을 드러내는 유일한 장치다.
5. skip·0건 실행·report 누락·`continue-on-error`·`ignoreFailures`는 금지다. path filter workflow는 조기 피드백일 뿐 required status로 승격하지 않는다.
6. **여러 세션이 한 checkout을 공유하면 `apps/api` 검증은 격리 worktree에서 한다.** 파일을 나누는 것으로는 부족하다 — 겹치는 것은 파일이 아니라 **빌드 산출물과 전역 합계**다. 동시 Gradle이 같은 resource jar를 다시 쓰면 test가 읽던 jar가 깨져 `Unable to calculate checksum`·`EOFException: ZLIB`로 **모든 Spring context가 기동 실패**하고, 자기 변경과 무관한 suite까지 전부 빨개진다(실측: 285개 중 131개).

   **worktree는 `HEAD` + 자기 파일만으로 만든다.** 공유 트리를 `rsync`로 통째로 동기화하면 격리되는 것은 `build/`뿐이고 **남의 미커밋 중간 상태가 함께 복사된다.** 실제로 한 세션이 그렇게 만든 트리에서 남의 편집 중인 파일 때문에 빨개진 것을 **자기 migration 탓으로 30분 좁혀 들어갔다.** 그걸 깬 측정은 *"내 변경을 통째로 지워도 같은 실패가 재현되는가"* 였다 — 재현되면 원인은 내 변경이 아니다. 그다음이 두 트리 `diff -rq`다.

   **`.git`도 공유다.** `git push origin backend`는 브랜치 전체를 밀므로, 한 세션의 push가 **다른 세션이 방금 만든 미검증 커밋까지 공개한다**(실제로 일어났다 — amend하려던 커밋이 먼저 나갔다). push 전에 `git log --oneline origin/backend..HEAD`로 **밀 커밋 목록을 먼저 보고**, 남의 것이 섞였으면 그 세션에 알린다.

   **그리고 컴파일 불가 상태의 파일은 그 자체로 공유 자원을 잠근다.** `compileIntegrationTestJava`는 source set **전체**를 컴파일하므로, 한 세션이 record를 넓히고 기존 호출부를 안 고친 채 **저장만 해도** 다른 세션의 test 실행이 `cannot find symbol`로 죽는다. Gradle을 안 돌려도, commit을 안 해도 그렇다 — *"나는 실행을 피했으니 안전하다"* 가 틀리는 자리다. **막는 것은 실행이 아니라 저장이다.** 기존 test가 쓰는 record·시그니처를 넓힐 때는 **호출부를 같은 편집에서 고치고**, 저장 뒤 격리 worktree에서 `compileIntegrationTestJava`를 한 번 돌린다(4초다).

   **조율자의 말은 가장 빠르게 퍼지므로 가장 싸게 확인돼야 한다.** 세 세션에서 조율자가 낸 표기·상수·ID 형식은 검증 없이 양쪽으로 간다 — 실제로 조율자가 지어낸 `T8b` 형식이 메시지 **두 번**만에 두 카드로 번졌고(집계기 regex `BA-\d{3}-T\d+`에 매칭조차 안 되는 형식이다), 조율자가 **동료의 미확인 문장을 근거로** 내린 게이트 동작 결정이 카드에 실렸다가 그 동료의 반증으로 뒤집혔다. 그래서 규격은 쓰는 자리에서 대조하고, **조율자가 인용한 근거도 인용된 쪽이 다시 확인한다.** "네가 그렇게 말했다"는 가장 싸게 확인할 수 있는 주장이다.

   그리고 **전역 합계는 각자의 파일에 없지만 각자의 변경에 반응한다.** `JobConnectionBudget`은 job type 수의 **합**에 걸리고, `FlywayMigrationIT`의 "previous schema"는 **마지막 migration이 무엇인가**에 걸린다. 그래서 두 PR이 **각자 green인데 merge하면 red**가 될 수 있다. 이런 값은 **한 사람이 마지막에 정한다** — "나중에 들어가는 쪽이 다시 계산한다"는 규칙은 누가 마지막인지 아무도 모를 때 깨진다.

7. **검사를 넣었다와 검사가 발화한다는 다른 확인이다. 새 검사를 넣는 PR에서 둘 다 한다.**

   ① **돌았는가** — log에 step이 명령과 함께 찍혔는가. **초록도 빨강도 판정이 아닐 수 있다.** 초록은 *"돌고 통과했다"* 와 *"조용히 안 돌았다"* 둘 다와 양립하고, **빨강은 *"틀렸다"* 와 *"물어보지 못했다"* 둘 다와 양립한다** — 격리 worktree에 `node_modules`가 없어 `openapi-typescript: command not found`(exit 127)로 빨개진 것을 *"생성물이 낡았다"* 로 읽을 뻔한 일이 있었다. **exit code는 도구 부재와 검사 실패를 구분하지 못한다.** 그래서 그 빨강을 전파하기 전에 **검사가 판정을 내렸는지** 먼저 본다. **그리고 무엇을 물을지에서 이미 기울 수 있다** — `gh pr view`에 `headRefOid`·`statusCheckRollup`을 묻고 **`state`만 빼서** 이미 머지된 PR을 열려 있다고 읽은 일이 있었다. 읽지 않고 추론한 것이 아니라 **읽기는 읽었고 자기 그림을 지지하는 필드만 골랐다.** 고치는 방법은 조회를 늘리는 것이 아니라 **이 주장을 거짓으로 만들 값이 무엇인지 먼저 적고 그것을 묻는 것**이다. 그리고 **"두 측정이 일치했다"** 를 쓸 때는 **얼마나 독립인지**를 같이 적는다 — 같은 suite를 두 경로로 돌린 것은 *두 방법*이 아니라 *한 방법의 두 실행*이고, 일치는 거의 보장돼 있어 아무것도 더 말하지 않는다(`ajv test --invalid`의 glob 0건, egress probe의 exit code, `actual_call=blocked`가 전부 그 모양이었다).
   **①에는 배선판이 있다 — 검사는 돌았고 판정도 냈는데 그것을 읽는 줄이 끊긴 경우다.** `검사 | tail -8 && commit`에서 `&&`가 보는 것은 검사가 아니라 **`tail`의 exit code**이고 `tail`은 언제나 0이다. 실제로 `markdownlint`가 1건을 찍은 실행에서 바로 다음 줄의 `echo OK`가 찍혔다. 같은 모양이 background 실행에도 있다 — harness가 보고하는 exit code도 pipe의 것이다. **검사와 commit·push를 `&&`로 묶을 때는 `set -o pipefail`을 켠다.** ①이 *"빨강이 판정이 아닐 수 있다"* 라면 이것은 그 거울로 **초록이 판정이 아니라 pipe인 경우**다.

   ② **발화하는가** — 그 검사가 막으려는 것을 **실제로 만들어** 빨개지는지 본다. 가드를 지우거나, 값을 거부 대상으로 바꾸거나, 생성물을 어긋나게 한다. **되돌린 뒤 `git status`로 확인한다.**

   ②가 없으면 **장치가 있다는 믿음만 생긴다.** 하루에 *"발화할 수 없는 단언"* 다섯 건이 나왔고 **넷이 검사를 쓰는 순간에 생겼다** — 없어서 못 잡은 것이 아니라 **잡으려고 쓴 것이 잡지 못하는 모양**이 됐다:

   - `jsonPath("$.ownerId").doesNotExist()` — 그 record에 `ownerId` component가 **없어서** 어떤 회귀에서도 실패할 수 없다
   - `cover_media_asset_id` column 개수 검사 — 그 이름의 column이 **생길 리 없었다**(V021이 만든 이름은 `cover_asset_id`)
   - handler의 *"trip이 사라졌다"* 분기 — cascade라 **관측 불가능**
   - hours query의 넷째 필터 — trigger가 이미 막아 **발화 불가**
   - `assertRegex(version, r'\d+\.\d+\.\d+')` — **부분 일치**라 `^7.13.0`이 통과한다. 거부하려던 값에 통과했다

   마지막 것은 *"발화할 수 없는 단언"* 을 지우는 규칙을 **쓰면서** 났다. 자기 검사에도 ②를 한다.

   **거울상이 하나 더 있다: 장치가 없는데 주석이 있다고 적는 것.** `OptimizationFailureCode`의 javadoc이 *"두 선언은 `OptimizationFailureVocabularyIT`가 서로 대조한다"* 고 적고 있었는데 **그 class가 없었다** — 두 slice 전에 쓰였고 그 이름이 나오는 곳은 그 문장 하나뿐이라 아무도 알 수 없었다. **①②가 잡는 것보다 싸게 생긴다**(주석 한 줄이면 된다) 는데 남기는 것은 같다: *장치가 있다는 믿음*. `check_test_reports.py`가 `provenBy`에 대해 지목한 이름이 실재하는지 보는데 **산문에 대해서는 아무도 안 보고 있었다.** `scripts/check_cited_tests.py`가 그 비대칭을 닫는다 — 주석이 `*Test`·`*IT` 모양의 이름을 인용하면 그 class가 실재하는지 대조한다. 첫 실행이 **두 건을 더 찾았고 둘 다 접미사 뒤바뀜이었다**(`...Test`라 적혔는데 실제는 `...IT`, 그리고 그 반대). 그쪽이 더 나쁘다 — **검사는 실재하므로** 인용된 이름을 grep한 사람이 아무것도 못 찾고 *"검증하는 것이 없다"* 로 결론내린다.

   **③에는 조율판이 있다.** 여러 세션이 한 PR에 밀면 required 게이트는 **매번 처음부터** 돈다. 각 push가 개별로는 옳아도 — 실패 수정·게이트 이관·새 test·문서 — 합치면 **누구의 커밋도 게이트를 통과한 적이 없는 상태**가 된다. 실측: `#219`에서 head가 여덟 번 움직이는 동안 `docker-integration`이 **완주 0회**였고, 동결하고 한 바퀴 돌리자 **첫 완주에 통과**했다. **완주 한 번을 보기 전에는 어느 커밋도 통과한 적이 없다** — 연속으로 밀어야 하면 동결하고 한 바퀴 돌린다. 한 사람이 결과를 안 읽는 것이 아니라 **아무도 읽을 수 없는 상태를 여럿이 함께 만드는 것**이다.

   그리고 ①② 위에 둘이 더 있다. **③ 결과를 읽고 멈추는가** — `검사; commit; push`는 검사를 **권고**로 만든다. exit code로 막아야 한다(`&&`, `set -o pipefail`). **④ 그 검사를 애초에 돌렸는가** — `&&`는 **돌린 검사에만** 걸리고 **목록에서 빠뜨린 검사에는 걸 `&&`가 없다.** 그래서 변경 경로가 요구하는 필수 검증 **목록을 매번 다시 읽는다**(위 *필수 검증* 절). 기억으로 돌리면 빠뜨린 검사는 ①②③ 어느 것으로도 잡히지 않는다 — 실제로 `docs/**`를 고치며 `markdownlint`를 건너뛴 채 commit과 push가 나간 일이 있었고, 통과한 것은 검증이 아니라 운이었다.

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
