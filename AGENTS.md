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
16. 추천 계산(feed 순서·관련 장소·slot·ITEM 개선·설명 template)은 Python 서비스 `apps/ai`가 담당하고, Spring `apps/api`는 hydration·gateway·응답 재검증·저장·APPLY를 담당한다(ADR-0006). 계산을 Spring에 중복 구현하지 않고, `apps/ai`에 owner/session ID·원문·좌표를 보내지 않는다. 예외: 관련 장소(`listRelatedPlaces`)의 병합·정렬은 Spring이 한다(`docs/decisions/ARCHITECTURE_DECISIONS.md`의 ADR-0006 · 예외).

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
| `docs-contract` | 모든 main PR/push | `validate_docs.py`(Problem code↔FE mapping, backend·frontend plan 검증 포함), `python3 -m unittest discover -s scripts/tests`, plan/Canvas 검증, markdownlint, Redocly, AJV(example 통과 + `docs/contracts/events-negative/`의 5건이 **거절되는지**를 `check_event_negatives.py`가 판정한다 — `ajv test --invalid`는 glob 0건에서 exit 0이므로 exit code를 통과로 쓰지 않고 디렉터리 목록과 대조한다), PR 전용 OpenAPI breaking diff(승인된 예외만 `docs/api/oasdiff-ignore.txt`로 면제하며 `test_oasdiff_exceptions.py`가 이유·승인자·추적 이슈와 stale 행을 검사한다), `check_test_row_ownership.py`(test의 `DELETE`·`TRUNCATE`·`UPDATE`·집계 읽기가 **행을 지목하는지** 본다. 필수 게이트는 모든 suite를 **한 DB**에서 돌리므로 지목하지 않는 문장은 *앞서 돈 모든 test*에 대한 문장이 된다 — 죽거나(FK), 남의 fixture를 데려가거나, 남의 행을 세어 단언한다. **로컬 Testcontainers는 context마다 컨테이너가 달라 이 부류를 재현할 수 없다.** 이 검사 자체가 하루에 **거짓 양성 세 모양**을 냈다 — SQL의 작은따옴표, `" + "` 이어붙이기, jsonb의 이스케이프된 큰따옴표. 셋 다 *"이 문장은 어디서 끝나는가"* 를 너무 단순히 답한 것이고, 세 번째는 **인용된 쪽이 자기 파일을 다시 읽어** 잡았다), report runner 부정·wrapper 실행 검사, `check_container_test_inputs.py`(Dockerfile의 COPY 허용목록과 suite가 **실제로 여는** `../../` 경로를 양방향 대조한다 — 둘은 따로 선언된 두 목록이고 어긋나도 `api-quality`는 전체 checkout이라 **초록으로 남는다**. `docker-integration`만 `NoSuchFileException`으로 죽는다. **두 번 물렸다**: `packages/contracts/fixtures`와 `docs/data/SOURCE_CATALOG.md`. 두 번째는 Dockerfile이 **첫 번째 사고의 주석을 달고 있는 채로** 났다 — 고칠 수 있는 곳에 규칙을 두는 것과 기계가 그것을 강제하는 것은 다르다), `check_actual_call_evidence.py`(CMP-KTO-003 — staging smoke의 actual-call report를 판정한다. 증거가 없으면 `actual_call=blocked`이고 **통과로 세지 않는다**; local 환경·`mock`/`replay`/`fixture` source·거절된 호출·다른 release의 증거는 전부 실패다. 배포·제출 경로는 같은 script를 `--require-verified`로 돌려 증거 없는 release를 막는다) | BA-000-T1·T2(operation·기능 ID coverage, DAG·B10 순서), BA-004-T1/T2 로컬 재현 검사, FE-003 code mapping, frontend plan DAG. **BA-000-T3은 여기가 아니라 `docker-integration`이 실행한다** — `api:check` 안의 `check-examples.mjs`가 `packages/contracts/fixtures/negative/`의 6건을 ajv에 먹여 **거부되지 않으면 실패**시킨다(`negative_rejected=6`). 이 행의 `validate_product_contract_alignment`는 discriminator와 variant별 `const`·`required`를 **고정**할 뿐이고, 구조 고정과 거부 증명은 다르다 | 실행 중 |
| `docker-integration` | 모든 main PR/push | `integration-test.sh`: verifier→`api-quality`·`ai-quality`·egress-denied(`check_egress_report.py`가 판정한다 — probe가 출력하는 `outbound_network=denied` 토큰을 요구하고, 아무 판정도 내지 않은 실행은 통과로 세지 않는다. exit code만으로는 probe가 probe이기를 그만둔 경우를 볼 수 없다)·web·client diff·scan·`infra-plan`(`check_infra_report.py`가 판정한다. `infra/`가 들어온 #280부터 `infra_check=pass scope=offline-synth-and-assertions`이고, `blocked`나 판정 token 없는 실행은 **통과로 세지 않는다**)·E2E·report 집계(실행 ID·freshness, E2E JUnit까지 — 브라우저 suite가 report를 쓴 뒤라야 읽을 수 있어 맨 끝이다) | BA-006-T2(`verify_target_stack.py`의 `check_browser_bundle_inputs` — browser-facing build가 credential을 **건네받지 않음**을 compose 정규화 config에서 고정한다. 게이트는 자기 secret이 없으므로(`integration.yml`이 아무것도 넘기지 않는다) 값을 스캔할 수 없고, **건네받은 적이 없다**를 대신 증명한다 — layer는 build가 받은 것을 간직하고 Vite는 `VITE_*`를 번들에 inline하므로 입력이 곧 유출 경로다. **세 면 중 둘이다**: 번들과 image layer는 입력으로 덮이지만 **log는 이 검사가 보지 않는다**. 값 스캔은 `scripts/check_secret_exposure.py`가 하며 실제 secret이 있는 환경(local·staging)에서만 의미가 있어 **아직 어느 게이트도 돌리지 않는다** — `infra_check=blocked`와 같은 정직한 공백이고 staging이 서는 `BA-071`이 그것을 돌린다) · 아래 suite 전체 | 실행 중 (`integration_mode=full-docker`) |
| `api-quality` (workflow) | `apps/api/**`, `apps/ai/contracts/**`, `apps/ai/tests/recommendation/fixtures/**`, `apps/ai/tests/recommendation/manifest.json`, `apps/ai/src/nullnull_ai/policy/**`, `docs/api/openapi.yaml`, `backend-plan.json`, `packages/contracts/fixtures/**`, report runner/검사 push/PR | Gradle `test integrationTest openapiContractTest recommendationTest` + JUnit/ready-card ID 집계 | BA-001-T2(`ArchitectureRulesTest.modulesNeverReachIntoAnotherModulesInfrastructure`), REC-ARCH-01, REC-DATA-02, REC-JOB-01, REC-SEC-03, BA-000-T3(`ProblemFixtureContractTest` — fixture의 code↔status/retryable/title/type을 `ProblemCode`에 고정, 429 producer 부재 서술도 양방향 고정), BA-001-T2, BA-003-T1~T3(`DemoReadinessContractTest` — demo readiness fixture를 `DemoCapabilityQuery`의 실제 출력에 고정), BA-005-T1~T6, BA-005-T7~T10(`JobWorkerIT`·`JobAbandonedLeaseIT` — queue가 **읽을 수 없는** payload. mapper가 statement 안에서 throw해 statement 전체가 rollback됐다 — claim에서는 그 행이 같은 type의 뒤 job을 영원히 막았고, sweep에서는 그 type의 abandoned 행이 모두 RUNNING으로 남았다. 행은 enqueue가 쓸 수 없는 모양이라 test가 직접 심는다. sweep 쪽은 죽은 process가 남기는 RUNNING·lease 만료·attempt 소진 행을 심는다 — 그런 payload는 claim이 handler 전에 끝내므로 붙잡고 있을 handler가 없고, 한 sweep statement에 두 행이 함께 있어야 하기 때문이다. T7의 로그 검사는 root logger의 message와 cause에서 canary를 찾는다 — worker logger와 message만 보면 cause를 기록하는 회귀에도 초록이었다), BA-010-T1~T8(SessionSafetyIT·SessionTimeIT·SessionContractTest·SessionCookiePresenceIT·SystemContractTest·ArchitectureRulesTest, backend-plan.json — T4~T8은 #240 A-1이다: 요청에 session cookie가 아예 없을 때만 401에 `missingCredential`이 실리고(T4, 실제 Tomcat과 계약 schema로도 잰다) session 해석이 거절한 401은 원인과 무관하게 body·헤더 값까지 같으며(T5) cross-origin 상태 변경은 cookie 유무와 무관하게 같은 403이다(T6, origin 검사가 세션 해석보다 먼저다). T7은 그 필드의 생산자가 interceptor 하나뿐임을 ArchUnit으로, T8은 보낸 cookie를 어느 parser로 봐도 missing이라 답하지 않음을 실제 Tomcat으로 고정한다 — MockMvc의 `.cookie()`는 Tomcat parser를 거치지 않는다), BA-011-T1~T3(OwnerPreferencesIT·OwnerPreferencesConcurrencyIT·OwnerContractTest), BA-012-T1~T5(DeletionIT·DeletionJobIT·TombstoneReapplierTest·SessionContractTest·DeletionIncidentSignalIT·DeleteOwnerDataHandlerTest — T4는 dead letter가 된 삭제가 RUNNING에 남지 않고 FAILED로 끝나는 것, T5는 job 행 잠금 경합으로 멈춘 attempt가 PARTIAL_FAILED로 기록되지 않는 것. 마지막 attempt라면 dead letter hook이 FAILED로 끝낸다), BA-072-T2·T4·T5·T6·T8·T9(`DeletionIncidentSignalIT`·`JobCrashRetryIT`·`DeletionReceiptExpiryIT` — 삭제 실패·lease 재인수·미완료 receipt 만료가 운영자가 alarm으로 거는 `ops.alarm name=…` 한 줄을 남긴다. 줄 형식의 정본은 `OpsAlarm`이고 `OpsAlarmTest`가 리터럴로 고정한다. 이 신호를 쓰다가 `markFailed`가 PostgreSQL에서 타입을 정하지 못해 **삭제 실패가 한 번도 기록되지 않던 것**이 드러났다. `DeletionJobIT`는 최종 COMPLETED만 봐서 초록이었다. T1·T3·T7은 staging 몫이라 미등록), BA-020-T1~T3(ProviderKitTest·CollectorRunRecorderTest·SourceRegistryIT·`ProviderOutcomeVocabularyIT` — 검증 어휘가 세 곳에 따로 선언돼 있다: `ProviderResponseValidator.Outcome`, `IngestAudit.ValidationResult`, 그리고 `api_ingest_validation_check` CHECK. `CollectorRunRecorder`가 `valueOf(outcome.name())`으로 둘을 잇고 행은 CHECK를 만족해야 하므로, 한 곳에만 값을 더하면 **그 outcome을 실제로 내는 분기에서만** 런타임에 터진다. 세 집합을 양방향으로 대조한다), BA-021-T1/T2(KtoDetailResponseValidatorTest·KtoKorServicePropertiesTest·KtoPlaceDetailGatewayIT; BA-021-T3은 staging 실제 호출 증거 대기라 미등록), BA-022-T1~T3(CatalogFoundationIT·CatalogPlaceApiIT·CatalogPublicationPropertiesTest·CatalogPlaceProjectionServiceTest·`KtoCanonicalIngestMainTest` — `ktoCanonicalIngest`가 `ktoSmoke`와 `ktoForecastSmoke` 사이의 빠진 단계다. `findFreshRequest`가 join하는 `place_external_refs`를 만드는 것은 canonical ingest뿐인데 production 호출자가 없어서 C4 smoke가 돌 수 없었다. evidence 줄에 provider text가 새지 않는지 변이로 고정한다), BA-023-T1~T6(`TemporalComparisonPolicyTest` — `T2`·`T4`·`T5`·`T6`은 **증명이 먼저 있었고 ID가 없던** 자리다([#224](https://github.com/yutakdv/Nullnull/issues/224)). 그 class가 부적격 사유 일곱을 우선순위대로 돌고 인접 쌍까지 고정하며 property 기반 판별 방향도 넣는데, class `@DisplayName`이 `REC-DATA` 계열이고 method에는 `@DisplayName`이 아예 없어 집계기가 아무것도 못 봤다 — 규칙 3의 **거울상**이다. 원래 `T2`는 *"…pair의 delta가 null이다"* 로 정책 판정과 응답의 값을 한 절에 묶고 있었는데, 뒤쪽은 `CrowdComparison.delta`이고 그 생산자가 없다. 지금은 `BA-051-T6`가 그 층의 주인이다. · CrowdProvenanceProjectionTest·CrowdForecastApiIT·KtoCrowdForecastGatewayIT·FlywayMigrationIT·`CrowdQualityFlagCoverageIT` — `quality_flags`의 다섯 값 **각각**이 (a) 생산 가능함을 실제로 보이거나 (b) 응답이 거절돼 snapshot 자체가 없음을 실제로 보이거나 (c) 생산자 대기 + 소유 카드로 등록돼 있거나 셋 중 하나임을 요구하고, enum과 CHECK를 양방향 대조한다. **"통과하지만 증명하지 않는 검사"의 짝인 "구현됐지만 발화할 수 없는 가드"를 잡는 장치다**; `KtoCrowdForecastGatewayIT`가 나간 요청의 `areaCd`/`signguCd`를 단언해 #109의 결합 코드 회귀를 막는다), BA-023-T7~T23(`CrowdForecastQueryIT`·`CrowdForecastQueryFailsClosedIT`·`QueryBudgetIT`·`ArchitectureRulesTest` — 배치 `queryPlaceCrowdForecasts`(#105). `T8`은 item마다 **단건 `getPlaceCrowdForecast` 응답 JSON 전체**와 대조한다 — 어느 set을 고르는지(최신 fresh → 최신 stale, `fetched_at` 동률은 id)를 test에 다시 적지 않고 단건을 기준으로 삼는다. 동률 fixture가 있어 `ss.id DESC`를 지우면 `T8`이 빨갛고, 두 장소가 나눠 가진 set이 있어 (set, place) 쌍을 id 목록 둘로 바꿔도 `T8`이 빨갛다. `T14`는 다섯 branch(fresh·stale·no-coverage·merged·unresolved)를 **따로**, soft로 잰다: 경계가 추가 id당 0.5문장이라 일부 id에서만 도는 조회는 섞인 요청에서 경계 아래로 숨는다 — stale 조회를 장소별로 돌린 변이는 fresh가 없는 두 branch(STALE·NO_COVERAGE)에서만, 해석 실패 id 재조회는 UNRESOLVED에서만 빨갰다. `T15`는 **transitive**이고 **port 구현체도 주어**다: collector를 새 중간 class 뒤에 숨긴 변이에 직접 의존 규칙은 초록이었고, transitive 규칙도 interface에서 구현체로 건너가지 않아 `JdbcCrowdForecastQuery`에 collector를 넣는 변이는 구현체를 주어로 둔 뒤에야 빨갰다. 같은 class라 단건 route도 묶는다. `T16`~`T19`는 빠진 필드 하나씩, `T20`은 0001~9999년 밖의 `from`·`to` — 수정 전 **단건 route도** 500이었다. `T21`~`T23`는 어휘다(#105 FE 후속): `unavailableReason` token 목록과 `ordinalLevel` 척도를 **계약과 서버 양방향으로** 대조하고, 저장된 단계가 계약을 어기고 나가지 않게 읽기 경계에서 막는다(기준은 척도 안의 숫자가 아니라 **source에 검토된 매핑이 있는지**다 — 지금 그런 source는 없다). **JSON Schema `enum`을 쓰지 않은 것은 측정이다** — enum이 없던 응답 property에 enum을 더하면 oasdiff가 값마다·operation마다 한 건씩 **174 error**를 내고, `x-extensible-enum`+`pattern`은 0 error다. 그 대가로 생성 client에 union type이 없어 두 vocabulary를 잇는 것은 이 test뿐이다), BA-024-T1~T7(`PlaceRelationFoundationIT`·`CatalogRelationQueryIT`·`CatalogRelatedPlacesApiIT`·`CatalogRelationProjectionServiceTest`·`RelationStateCoverageTest` — `place_relations`는 pair당이 아니라 **source당 한 행**이라(`(source, target, source_code)` unique) 두 출처가 한 장소를 지목하면 두 행이고, projection이 그 장소를 두 번 냈다. 내부 계약의 gateway post-condition이 *"related places must be merged, not repeated"* 로 이미 금지하던 것인데 병합하는 쪽이 없었다. **`T2`가 HTTP가 아니라 24개 순열 전수 unit test인 이유가 측정에서 나왔다**: 정렬을 제거하면 unit case는 빨개지는데 IT 전체는 초록으로 남는다 — `JdbcCatalogRelationQuery`가 이미 `(tier, target)` 순으로 주므로 IT는 *이 PostgreSQL이 실제로 내놓는* 도착 순서만 몰 수 있다. `T7`은 다섯 값을 성질로 가른다: `NONE`은 오너 결정(§9)으로 배제, `EXACT`는 유일한 provider가 미승인, `CHECKING`은 검증 job 부재, `SIMILAR`·`UNKNOWN`만 생산된다. 서버 enum과 계약 enum을 양방향 대조한다) · BA-060-T1·T2·T3·T5·T8·T14·T17(`TripImportParseIT`·`TripImportFailsClosedIT`·`TripImportIT` — canary 넷은 원문에 유일한 문자열을 심고 **있으면 안 되는 모든 곳**에서 찾는다. 두 table 검사가 column 나열이 아니라 **`to_jsonb(row)::text`** 인 것이 핵심이다: 오늘의 column을 나열한 검사는 내일 누가 column을 더해도 계속 통과하고 **새는 것은 아무도 이름을 안 적은 그 column**이다. `T14`는 두 절이라 변이도 둘이다 — 연도 없는 날짜를 올해로 채우는 것과 맨 시각을 오전으로 읽는 것. 한국어 맨 시각(`3시`)은 하루에 두 순간이라 token으로 내는데, **`오후 3시`가 15:00이 되는 case가 같은 test에 있어야** *"결정하지 않는다"* 절이 **아무것도 결정하지 않는 parser**로 충족되지 않는다. `T17`은 `TripScheduleRules`의 message에 날짜를 넣는 것이 실제 작업이었다 — `createTrip`에서는 호출자가 방금 그 목록을 썼으니 견딜 만하지만 붙여넣기는 2주를 채울 수 있고 그 날을 찾는 방법이 손으로 세는 것뿐이었다. 조용히 자르지 않는 것을 따로 단언한다: 하나를 몰래 버리는 서버도 *"trip 안 만들어짐"* 단언은 통과한다), BA-060-T15·T16·T18·T19(`TripImportParseIT`·`TripImportFailsClosedIT` — **커밋은 `f96cd6f`이고 그 메시지는 `BA-052`라고 적혀 있다**: 공유 index 때문에 남의 커밋에 실렸다(규칙 6). `T19`가 *"선언 ↔ 전송"* 의 첫 사례다 — `parseTripImport`만 `Cache-Control: no-store`이고 형제들은 `private, no-store`인데 `SessionContractTest`는 **선언 여부만** 본다. `T18`의 변이는 **날짜만 있는 붙여넣기 case를 넣은 뒤에야** 발화했다: `CatalogPlaceProjectionService.search`가 자기도 게이트를 보므로 장소 줄이 있는 붙여넣기는 parse의 확인 없이도 503이 되고, **parse 자신의 확인이 유일한 방벽인 경우는 조회할 것이 없는 붙여넣기뿐**이다. label이 패턴 allowlist로 항상 비는 것이 `T1`의 조건이다 — 문자 allowlist는 한글 메모를 통과시킨다), BA-026-T1~T6(`CatalogRelationDeriveIT` — `place_relations`에 writer가 없어 `BA-024`의 일곱 절이 초록인 채로 사용자에게는 영원히 `UNKNOWN`이던 것을 닫는다. 규칙은 지어낸 것이 아니라 `V007`의 `metric_definition`에 *'동일 taxonomy·region 기반 SIMILAR 규칙'* 으로 **문장으로 있었다**. `mapping_certainty`가 `CONFIRMED`인 것은 그 열이 *"canonical mapping이 확인됐는가"* 를 묻기 때문이고 — 규칙 행은 양 끝이 이미 우리 canonical id다 — 약한 것은 *관계*이고 그건 `SIMILAR`가 말한다. `T5`의 fixture가 `status`가 아니라 `category_code`를 바꾸는 이유는 `V027`의 trigger가 **relation을 가진 장소의 폐기를 먼저 거부**해서 절을 검사할 수 없게 만들기 때문이다), BA-025-T1~T5(`CuratedHoursImportIT` — `place_hours`에 행을 만드는 production 경로가 없어 이미 등록된 `getCandidateTripMatches`가 운영에서 항상 `UNKNOWN`이던 것을 닫는다. `T2`와 `T3`를 나눈 것이 측정으로 정당화됐다: supersede를 delete로 바꾸면 `T3`만 빨갛고 **`T2`는 통과한다** — 현재 관측이 하나인 것은 맞으니까. 근거의 이력이 사라지는 것을 잡는 것은 `T3`뿐이다), BA-060-T4·T6·T7·T9~T13(`ArchitectureRulesTest`·`TripImportIT` — `T11`이 단언하는 것은 검사가 아니라 **순서**다. 만료 pre-check가 guard **앞**에 있어야 만료된 draft가 410을 내고, 뒤에 있으면 guard가 저장된 201을 replay한다. 그 test가 의미를 가지려면 draft 만료와 key 보존기간이 같이 끝나면 안 되므로(둘 다 24시간) fixture는 clock을 미는 대신 `expires_at`을 지난 값으로 심는다 — 25시간을 밀면 호출자 session이 먼저 만료돼 draft에 닿지도 못하고 `MutableClock`은 되돌릴 수 없어 뒤따르는 모든 test가 빨개진다. 창이 닫힌 것은 **행의 속성**이지 시간의 속성이 아니다), BA-030-T1~T6(`TripCreationRulesTest`·`TripCreationIT`·`InterestVocabularyContractTest`·`TripDetailFailsClosedIT`·`TripMutationFixtureIT` — T5·T6은 trip 장소의 provider 출처 값과, 외부 출처가 없는 장소의 null이다. 그 공유 projection의 출처는 이 전에 어떤 test도 보지 않았다(변이로 확인) — T4는 catalog 공개 게이트가 닫힌 상태의 `getTrip`이다. item 없는 trip은 답하고 item 있는 trip은 503이며 seedItems create는 **쓰기 전에** 거절한다는 세 상태를 한 case로 묶었다: 따로 두면 "비었으면 200"이 임의 규칙으로 읽혀 다음 사람이 전부 503으로 만들거나(만들 수 있는 답을 거부) item을 빼고 200을 주게 된다(#162가 배제한 silent-empty). 어휘는 계약의 `x-nullnull-interest-codes`와 `InterestVocabulary`가 같은 13개임을 양방향으로 고정한다. `enum`이 아닌 extension인 이유는 어휘의 정본이 FE 소유 Figma chip 목록이라 chip 추가가 breaking 변경이 되면 안 되기 때문이고, extension은 generated client에 union type을 주지 못하므로 이 test 말고는 둘의 drift를 잡는 것이 없다), BA-050-T1~T8(`OptimizationRunIT`·`OptimizationWorkerIT`·`OptimizationCapabilityOffIT`·`RecommendationRequestShapeTest` — `OptimizationCapabilityOffIT`는 **flag 기본값(꺼짐)** 상태를 검사한다. `OptimizationRunIT`가 켜고 돌리므로 이 파일이 없으면 **제출 profile이 실제로 쓰는 설정을 아무것도 안 본다**(`FeedFailsClosedIT`와 같은 이유다). `RecommendationRequestShapeTest`는 `apps/ai` 요청의 **직렬화된 key 집합이 선언된 component 집합과 같음**을 고정한다 — 선례였던 `jsonPath("$.ownerId").doesNotExist()`는 `ExplanationRenderRequest`에 그 component가 **없어서 어떤 회귀에서도 실패할 수 없는** 단언이었고, 없는 것을 없다고 단언하는 것은 영원히 초록이다. 이 카드는 `items/propose`를 **부르지 않는다** — 호출과 READY 쓰기는 `BA-051`이고, 여기는 READY를 막는 gate까지다), BA-052-T1~T15(`OptimizeDecisionIT`·`OptimizeDecisionFaultIT`·`OptimizationDecisionSchemaIT`·`ComparisonWithdrawalTest`·`OptimizationRunIT` — `decideOptimization`의 APPLY·KEEP 멱등 원자 결정: 최초 결정 하나만 남고, 쓰기 지점마다 fault를 넣어도 부분 적용이 0이며, 동시 결정의 패자는 index(APPLY/KEEP)나 trip version 재조회(APPLY/APPLY)가 막는다), BA-053-T1~T11(`OptimizeRevertIT`·`OptimizationDecisionSchemaIT` — 24시간 REVERT와 최적화 이력: 창 경계, 후속 편집 거부, 동시 revert, history cursor), BA-054-T1~T9(`OptimizeRevertIT` — `revertAvailability` 읽기 투영의 각 상태와, AVAILABLE을 읽은 뒤에도 revert가 다시 하는 검사), BA-055-T1~T22(`TripDraftPreviewIT`·`TripDraftPreviewFailsClosedIT`·`TripDraftPreviewGatewayIT`·`DraftComposeGatewayTest`·`RecommendationRequestShapeTest` — 저장 없는 여행 초안 preview. 핵심은 T3이다: preview가 준 stop을 그대로 seedItems로 옮긴 `createTrip`이 201이고 (장소, 날짜, position) 집합이 같다. `verifyDraft`는 규칙 하나당 ID 하나(T7·T13~T19·T21·T22)이며, 규칙을 하나씩 지워 쟀을 때 여덟은 각자 자기 case만 빨갰고 case가 없던 둘(날짜 범위·policy hash)에 T21·T22를 더했다(`3864fdb`). 응답 schema는 `TripDraftPreviewContractTest`가 보지만 ID가 없어 집계에 잡히지 않는다), BA-033-T1·T2(`FeedFeedbackIT`·`FeedFeedbackBucketTest` — `recordFeedFeedback`. dedup 분 bucket이 **Java에 있는 이유**는 `date_trunc('minute', timestamptz)`가 IMMUTABLE이 아니라 **STABLE**이라 PostgreSQL이 generated column·index expression·CHECK 어디에도 받지 않기 때문이다. 그래서 *"저장된 bucket이 `occurred_at`이 속한 분인가"* 는 DB가 스스로 검사할 수 없고 test가 고정한다), BA-033-T3(`ArchitectureRulesTest.analyticsIsNeverOnAProductCommandsPath` — 제품 command가 analytics에 **닿을 경로 자체가 없음**을 고정한다. ingest를 실패시키고 command가 사는 것을 보는 방식은 *한 command에 대해 그날 하루* 증명하지만, 이건 module 밖의 코드가 analytics를 **이름 부르는 순간** 빨개진다), BA-032-T4(`CuratedPostImportIT` — `A-031` 운영 스크립트. `BA-032` step 1이 curated post를 **조회**만 서술하고 **만드는 쪽이 어느 카드에도 없었다**(#183). plan 파일 하나가 transaction 하나이고 PRIMARY 하나 검사를 **plan 단계에서** 한다 — `V022` trigger가 파일 중간에서 터지면 운영자가 어디까지 들어갔는지 추적해야 한다), BA-070-T1(`OwnerIsolationMatrixIT` — trip을 이름에 담은 operation **전부**에 대해 *남의 trip*과 *존재한 적 없는 trip*의 응답이 **같은지** 본다. 403은 거절이면서도 caller가 실제로 묻던 것(그 id가 있는가)에 답해버리므로 불변식 11의 기준은 거절이 아니라 **구별 불가능**이다. 호출 목록은 계약에서 읽으므로 새 `/trips/{tripId}` 경로가 **존재만으로** 이 matrix에 들어온다 — 손으로 적은 목록은 조용히 낡고 그게 이 test가 막으려는 실패다. **세 번째 호출이 이것을 공허하지 않게 만든다**: 두 응답은 요청이 소유권 판단에 닿기 전에 죽어서도 쉽게 일치하므로(거절되는 body, 받지 않는 content type, 요구하는 ETag) 자기 trip에 대한 같은 호출이 **달라야** 한다. 첫 실행이 정확히 거기서 빨갰고 옳았다 — 16 중 14가 소유권이 아니라 검증을 재고 있었고 그중 `deleteTrip`은 뒤따르는 모든 operation의 fixture를 지우고 있었다), BA-070-T2(`RedactionAndDenylistIT`·`CookieParserLogRedactionIT`·`DatasourceUrlLogRedactionIT` — 요청이 실어 온 canary가 어떤 로그 줄에도 닿지 않고 응답 body에 설정된 secret이 없다. 앞의 것은 MockMvc라 Tomcat의 Cookie parser를 한 번도 타지 않아, 파싱 못 한 cookie pair를 원문 그대로 INFO에 남기는 통로(session token이 그 안에 있을 수 있다)를 볼 수 없었고 뒤의 것이 실제 Tomcat에서 그 통로를 잰다 — Tomcat은 JVM당 한 번만 INFO로 남겨 앞선 test가 그 기회를 쓰면 부재 단언이 설정 없이도 통과하므로 요청마다 그 기회를 되돌린다. 셋째는 요청이 닿지 않는 통로인 datasource URL을 잰다 — 실제로 받아들여지는 canary 비밀번호(와 sslpassword)를 URL에 실어 접속한 context의 기동 출력 전체와, driver가 모양으로 거절한 URL 세 가지를 훑는다. URL을 그대로 찍던 logger 넷(Hibernate `connections.pooling`, Flyway `FlywayExecutor`, pgjdbc `Driver`·`PGPropertyUtil`)을 하나씩 되돌리면 각자 자기 case만 빨개진다. log level로 닫을 수 없는 `user:password@` URL의 기동 실패 오류는 이 class 밖이다), BA-070-T3(`QueryBudgetIT` — 크기를 아는 요청의 statement 수와 pooled connection 수가 읽는 행 수에 따라 자라지 않는다. 상한은 시간이 아니라 구조이고 숫자가 아니라 증가에 걸린다: 행을 늘렸을 때의 증가가 N+1(행당 statement 1개)의 절반 미만이어야 한다), BA-070-T4(`CapabilityOffCoverageIT` — `getDemoReadiness`가 공개한 P1 capability 전부가 꺼져 있고, 각각이 operation 거절 목록과 생산자 대기 목록 중 정확히 한 곳에 있다. 내일 넷째를 더하면 어느 목록에도 없어 빨개진다) · BA-034-T1~T3(`CandidateIT` — partial unique 수렴·DISMISSED 재저장·SCHEDULED 거부·owner 분리, fixture↔서버 shape 대조 포함), BA-012-T2 전이 소거(`OwnerDataErasureIT` — soft delete 단계에서 trip aggregate 일곱 table이 비는지. 스키마 sweep이 못 보는 범위다), BA-032-T1~T3(`FeedIT`·`FeedFailsClosedIT` — `replaceTripInterests` 없이. feed는 catalog 공개 게이트가 닫혀 있으면 503이고 그 동작도 검사한다), BA-031-T1~T3(`TripScheduleRulesTest`·`TripMutationIT` — `updateTrip`·`deleteTrip`·`replaceTripInterests`. 교체는 merge가 아닌 전체 치환이고 빈 집합도 유효하며 version은 한 번만 오른다), BA-033-T1~T3(`AnalyticsIngestIT`·`EventBatchValidatorTest`·`EventContractTest` — `ingestEventBatch`만. batch는 정본 `docs/contracts/events.schema.json`으로 검증하고 Java로 다시 구현하지 않는다. `EventContractTest`가 route allowlist와 event 이름이 OpenAPI와 정본에서 같은 목록임을 고정한다 — `baseEvent`의 `properties`가 무제약이라 branch 없는 이름이 추가되면 그 event의 payload는 아무 검사도 받지 않는다. `recordFeedFeedback`은 구현됐으나 **IMPRESSION·OPEN만 기록하고 HIDE·LIKE·DISLIKE는 거절한다** — PM-011이 *읽어올 때 무엇인지*를 정하지 않았고, 받아서 저장하면 제품이 보여줄 수도 되돌릴 수도 없는 상태를 만들면서 client는 그것이 있다고 믿는다. 계약 enum에는 다섯이 그대로 남는다(보낼 수 있는 값을 빼는 것은 breaking). **그래도 BA-033 전체 완료로 쓰지 않는다** — `BA-033-T3`의 *"analytics 장애가 제품 command를 실패시키지 않는다"* 는 별개이고 PM-011도 열려 있다), 내부 계약 parity(5 operation), gateway post-condition, ITEM fixture parity(LockChecks·ProposalRevalidator), policy pin parity, manifest `gradle:*` 경로 실재(`ManifestTestPathParityTest` — 실행되지만 `@DisplayName`에 ID가 없어 집계에 잡히지 않으므로 `REC-CI-4`는 등록 ID로 쓰지 않는다) | 실행 중 |
| `ai-quality` (workflow) | `apps/ai/**` push/PR | ruff, mypy strict, pytest(REC corpus, `evaluation.json`), 계약 JSON sync | `tests/recommendation/manifest.json`의 `implementedTestIds` 중 `suite: pytest` 행(`gradle:*` 행은 `api-quality`가 검증). **컨테이너 밖에서도 확인한다** — `check_test_reports.py`가 manifest의 pytest 행과 `evaluation.json`의 `implementedTestIds`를 양방향으로 대조하므로, manifest가 주장하는데 corpus가 돌리지 않은 ID도 corpus가 돌렸는데 manifest에 없는 ID도 실패다. `missingTestIds`는 아직 구현되지 않은 REC 목록이라 비어 있기를 요구하지 않는다 | 실행 중 |
| `apps/web` suite (`docker-integration` 내부) | 모든 main PR/push | `verify:ci`: tokens drift, eslint, prettier, tsc, `e2e/` typecheck(`tsconfig.e2e.json` — 기본 program의 `include`에 `e2e`가 없어 spec의 타입 오류를 **어느 게이트도 보지 못했다**. `overflow.ts`의 반환 프로퍼티 이름이 바뀌고 호출부 넷이 남았을 때 `tsc`는 exit 0이었고 브라우저에서 34건이 죽었다(#266). 별도 program인 것은 spec만 Node type이 필요하기 때문이다 — `src/`에 `process`를 주면 그 type은 번들에 없는 것을 있다고 말한다), `packages/*` typecheck(tsconfig 부재·0건 매칭을 실패로 처리), vitest, build, bundle budget(gzip 상한·빌드 부재·0건 매칭을 실패로 처리). fixture↔OpenAPI ajv 검증, dist MSW 부재 단언, Storybook 커버리지, forced-colors selection, 기본 msw 핸들러 존재 검사 포함 | FE-001~005, FE-101~106, FE-201, FE-202, FE-301~307, FE-404, FE-501, FE-502, FE-503-T1~T3, FE-506, FE-601, FE-602, FE-603-T2~T4(`image-assets.test.ts`·`attribution-coverage.test.ts` — 공모전 준수 셋. **구현·실행은 되는데 이 표에 없어서 "CI가 실행한 것으로 보지 않는" 상태로 있었다**; 규칙이 잡으라는 것을 규칙 자신이 놓친 자리다. `T2`는 image asset 허용목록 정확 일치와 외부 origin 금지, `T3`는 `TourAPI` 단독 표기 금지, `T4`는 `sourceAttribution`을 가진 place를 그리는 곳이 `DataAttribution`을 렌더하는지다. **`T4`에 0건 가드가 들어 있는 것이 핵심이다** — 대상이 0건이면 *"모든 파일이 credit을 렌더한다"* 는 빈 집합에 대해 공허하게 통과하므로, 그것만으로는 catalog 게이트가 닫힌 상태의 초록과 구별되지 않는다. 양방향 측정: 허용목록 밖 asset·shipped source의 `TourAPI` 단독·외부 image origin·`DataAttribution` 제거 넷이 각각 반경 1로 발화하고, 파일을 0개로 만들면 0건 가드가 발화한다. 거짓 양성 0 — `출처: ⓒ한국관광공사 TourAPI`와 `Korea Tourism Organization TourAPI`는 통과한다. 표지 5장(`docs/contest/covers/`)이 이 scan 범위 **밖**인 것은 의도된 것이다: 앱 번들이 아니라 배포 도메인이 서빙하는 콘텐츠 자산이고, `apps/web/public/`에 두면 허용목록 검사가 빨개진다고 그 디렉터리의 `README.md`가 적고 있다) | 실행 중 |
| `apps/web` E2E (`docker-integration` 내부) | 모든 main PR/push | Playwright: shell·온보딩·프로필 실동작, 360px·200% zoom·긴 문구 reflow, 터치 타깃 44px, geolocation 미요청 | FE-503-T4(`responsive.spec.ts` — READY proposal과 decision bar가 렌더된 최적화 화면에 dialog·sheet가 없고 reduced motion에서 모든 animation·transition이 사실상 제거됨을 검증), FE-601, FE-602, FE-603-T1(`location-off.spec.ts` — `CMP-LOC-002`. **unit 으로는 증명되지 않는 절이라 여기 있다**: `profile.test.tsx`가 `navigator.geolocation`을 대체해 한 화면을 덮지만 나머지 일곱에 대해 아무 말도 하지 않고, permission prompt 가 판정 흐름 어디서든 뜨면 준수 실패다. `screens.ts`의 8화면을 재사용해 화면마다 셋을 본다 — API 를 wrap 해 호출을 **기록하면서 throw** 하고, `page.on('dialog')`로 wrapper 가 덮지 못한 경로를 잡고, 나간 요청의 URL·body 에서 좌표 형태 넷을 찾는다. 셋을 나눈 이유는 **다르게 실패하기 때문**이다: 좌표는 navigator 없이도 수집될 수 있고(map SDK·IP lookup) 규칙이 막는 것은 그것을 **보내는 것**이다) | 실행 중 |

등록 규칙:

1. 새 REC ID는 `apps/ai/tests/recommendation/manifest.json`의 `implementedTestIds`와 fixture sha256에 추가하고, Spring 쪽 검증(REC-INT/SEC/JOB/FEED-04)은 해당 Gradle suite 이름을 `docs/engineering/TEST_STRATEGY.md#12`에 연결한다.
2. BA-xxx-Tn acceptance는 구현 PR에서 실제 test class/함수 이름과 report 경로를 카드에 적고 `backend-plan.json` status를 올린다.

   **`integration-ready`와 `verified`는 다른 것을 증명한다.** `check_test_reports.py`는 둘 모두에 대해 acceptance ID가 JUnit testcase 이름에 **나타나는지**만 본다. 나타나는 것은 증명하는 것이 아니다 — `BA-002-T3`은 ID를 단 testcase가 **20개**인데 전부 두 절 중 첫 절만 덮고 있었다(지금은 `T3`/`T4`로 쪼갰다, 아래).

   그래서 `verified`는 **acceptance ID마다 그것을 증명하는 testcase를 지목하도록** 요구한다(`evidence.provenBy`). 지목한 이름은 (a) 그 ID를 담고 있어야 하고 (b) 실제 report에 존재해야 한다. **test가 없는 절은 지목할 이름이 없으므로 승격이 그 자리에서 막힌다** — 이것이 이 칸의 존재 이유이고, 3의 한-절 규칙과 맞물린다.

   **기계가 잡는 것은 "거짓말한 이름"뿐이다.** 지목한 testcase가 실재하는지, 그 ID를 담고 있는지는 validator가 본다. 그러나 *그 testcase가 그 절을 정말 증명하는가*는 볼 수 없다 — **강제력은 표를 채우는 행위 자체에서 나온다.** 채우는 사람이 각 절을 그 ID를 단 testcase 본문과 대조해야 하고, 대조할 것이 없으면 칸이 비고 승격이 막힌다. 이 절차를 생략하고 이름만 옮겨 적으면 `integration-ready`와 같은 강도로 되돌아간다.

   `evidence.reviewer`는 **요구하지 않는다.** 이 저장소는 두 required check가 green이면 사람 승인 없이 auto-merge한다(원칙 15). 채우면 일어나지 않은 검토를 기록하게 되는 칸이므로 없앴다. task 수준의 `reviewer`(RACI 역할)는 그대로 남는다.
3. **acceptance assertion은 한 절만 쓴다.** `check_test_reports.py`는 `tests[].id`가 JUnit 이름에 **나타나는지**만 보므로, 한 ID에 여러 절을 묶으면 그중 **아무 절이나** 증명하는 test 하나로 그 ID가 충족된다 — 집계기가 나머지를 볼 방법이 없다. "A하고 B한다"가 필요하면 `T3`(A)·`T4`(B)로 나눈다. 그러면 B를 증명하는 test가 없을 때 `integration-ready` 승격이 **그 자리에서** 막힌다.

   **`backend` push의 초록은 required가 아니다.** trigger를 실제로 읽으면 `docs-contract`와 `docker-integration`은 **`main` 대상 PR/push에서만** 돌고, `api-quality`만 `push: [main, backend]`라 브랜치 push에도 돈다. 그래서 **밀면 초록이 하나 켜지는데 required 둘은 아무것도 돌지 않은 상태**다 — *"밀었더니 초록"* 으로 읽히기 가장 쉬운 자리다. **게이트는 PR이 있어야 돈다.**

   **고칠 수 없는 파일에 규칙을 적지 마라.** Flyway migration은 적용되면 checksum이 고정돼 **주석을 정정할 수 없다.** 그래서 migration 주석은 *그 순간의 의도 기록*이지 살아 있는 명세가 아니다. 같은 규칙이 두 곳에 있으면 **둘 중 하나가 반드시 먼저 상하고**, 고칠 수 없는 쪽이 남는다. 오늘 이 모양을 셋 만났다 — `V025` 주석의 hydration 규칙, `BA-005`의 pool 상수 넷(전역 합계가 파일 넷에 흩어짐), `AWAITING_THEIR_SLICE`의 `"BA-050 feed slice"` 라벨(BA-050에 feed가 없다). 규칙은 **고칠 수 있는 곳 한 군데**에 두고 migration 주석은 그곳을 가리킨다.

   **그 쌍둥이가 있다: 고칠 수 있는 자리인데 제약의 출처를 안 적어 과일반화되는 주석.** `TripImportIT`가 *"시계를 밀면 호출자 session이 먼저 만료돼 대상에 닿지 못한다"* 고 적어 두었다. **그 카드에서는 참이다** — draft 만료 24시간과 idempotency key 보존 24시간이 같이 끝나 미룰 수 있는 시간이 없다. 그런데 **출처가 안 적혀 있어 보편 규칙으로 읽힌다.** `BA-053`은 24시간 revert 창을 25시간으로 닫아야 했고 session은 `P30D`·`P90D`라 충돌이 없는데, 그 주석을 그대로 믿었으면 **가능한 길을 포기했을 것**이다.

   **그리고 반대로 무시하면 그 주석이 경고한 부류에 다른 이름으로 걸린다** — 실제로 그렇게 됐다. 그 문장이 가리키던 위험은 실재했고 주체가 session이 아니라 **CSRF token(`PT2H`)** 이었을 뿐이다. **출처 없는 제약은 무시되거나 맹종되고, 둘 다 틀린다.** 지우지 말고 **왜 여기서 참인지**를 적어라 — 읽는 사람이 자기 맥락에 그 조건이 있는지 볼 수 있어야 판단이 가능하다.

   **그리고 받는 쪽에는 반대 위험이 있다: 인용이 붙은 제약일수록 아무도 인용을 안 연다.** 실측: *"429는 열거하지 마라(A-025)"* 를 받았는데 `A-025` 원문은 *"application은 429를 **발행**하지 않는다"* 였고, 계약의 `x-nullnull-common-contract.rateLimitErrors`는 **선언이 의도된 것**이라고 명시한다(edge가 돌려주는 429를 client가 처리해야 하므로). 그대로 돌았으면 **정본이 있으라고 한 선언을 지웠을 것**이다. 압축된 것은 제약의 세기가 아니라 **대상**(발행 vs 선언)이었고, 출처가 붙어 있다는 것은 **그 출처가 확인됐다는 뜻이 아니라 아무도 안 연다는 뜻**이었다. 규칙 6의 *"조율자가 인용한 근거도 인용된 쪽이 다시 확인한다"* 가 여기서는 **받는 쪽**의 의무다.

   **계약이 범위를 안 정한 열은 "자유"가 아니라 "출처를 찾아라"다.** `optimization_proposals.crowd_delta`가 `numeric(5, 4)`로 들어갈 뻔했다 — 최대 ±9.9999인데 그 델타의 출처인 `V011 crowd_snapshots.value`는 `numeric(14, 4)`다. **아무도 재지 않은 숫자를 지키려고 실제 측정을 거부하는 열**이 될 뻔했고, 이유는 계약의 `crowdDelta`에 범위가 없어 *"안 정했으니 적당히"* 로 읽은 것이다. 같은 편집에서 `comparison_reason_code varchar(40)`도 지어냈다가 계약의 `maxLength 100`으로 맞췄다. 범위가 비어 있으면 **그 값이 어디서 오는지를 찾아 거기에 맞추고 출처를 주석에 적는다.** migration은 적용되면 고칠 수 없으므로 이 자리의 임의 기입은 되돌릴 수 없다. **다만 "정본이 비었다"와 "정본이 틀렸다"는 대응이 다르다** — 전자는 출처를 찾아 맞추는 것이고, 후자는 정본을 고치는 것이다. 같은 migration에서 `optimization_changes.trip_item_id`가 `ERD`에 FK로 적혀 있었는데 계약은 그 id를 *"the preview reserves"* 라고 말한다 — FK를 걸면 id를 발명하는 바로 그 operation만 거부된다. 거기서는 열을 맞추는 것이 아니라 **`ERD`를 고치는 것**이 답이었다. 비어 있으면 채우고, 어긋나 있으면 어느 쪽이 틀렸는지를 먼저 정한다.

   **생산자 없음에도 두 종류가 있다.** `BA-042-T7`을 쓰며 `SIMILAR`과 `CHECKING`을 같이 "생산자 없음"으로 등록했는데 coverage test가 거절했다 — **`CHECKING`은 `SlotEvaluateResponse.State`에 있다.** 둘은 다르게 부재한다: `SIMILAR`은 **서비스 어휘에 아예 없어** enum 교차검증으로 증명되고, `CHECKING`은 **어휘엔 있고 입력이 없을 뿐**이라 enum이 아무 말도 못 한다. 그래서 후자는 **그것을 증명하는 test를 지목해야 한다**(나가는 요청의 `checking`이 항상 false임을 단언). 규칙: **enum이 검사할 수 없는 주장은 주석이 아니라 test를 가리킨다.**

   **거울상도 있다: 절은 증명됐는데 ID가 없는 경우.** `BA-042-T3`(#165 Q2 전이 + #199 잠금)은 `TripItemReplaceIT`의 네 case가 이미 증명하는데 그 이름들이 `BA-040` ID만 달고 있어 **집계기가 아무것도 못 본다.** 카드가 약속한 것을 코드가 지키는데 기계가 볼 방법이 없는 상태다. **한 test가 두 카드의 절을 증명하는 것은 정상이고**, `@DisplayName`에 두 ID를 같이 달고 각 카드의 `provenBy`가 그 이름을 지목하면 된다 — 다만 **이름만 옮겨 적지 말고 본문과 대조한 뒤** 단다.

   **쪼개면 미해결 하나가 드러난다(미해결임을 드러내는 것이 목적이다).** `check_test_reports.py`의 `read_junit`은 기본으로 `GRADLE_SUITES`(`test`·`integrationTest`·`openapiContractTest`·`recommendationTest`) **네 디렉터리만** 읽는다. **Python 쪽은 `--script-junit-dir`로, CI 게이트 판정 쪽은 `--gate-junit-dir`로 닫았다. Playwright 쪽도 [#233](https://github.com/yutakdv/Nullnull/issues/233)으로 닫았다** — `playwright.config.ts`가 CI에서 JUnit을 내고 `compose.integration.yml`이 그 디렉터리를 밖으로 내보내며, `integration-test.sh`가 집계를 **E2E 뒤로** 옮겨 `--e2e-junit-dir`를 넘긴다. 집계가 E2E 앞에 있으면 **아직 생산되지 않은 report를 읽기** 때문에(`--run-start` freshness가 있어 조용히 초록이 되진 않고 빨개진다) 그 전까지는 flag를 넘기지 않았다. 옮기기 전후 판정이 같다는 것은 쟀다: 같은 Gradle·script report에 실제 Playwright 1.56이 쓴 XML을 더해도 `test_reports=valid`가 그대로였고, report 부재·skip·이전 실행의 report는 각각 빨갛다. **아직 회수할 것은 없다** — e2e 제목이 단 것은 `BA-010`·`BA-011`·`BA-012`처럼 **카드 ID**이고 집계기 regex는 `BA-\d{3}-T\d+`를 찾는다. 그 셋은 이미 JUnit으로 `integration-ready`다. FE가 절 ID를 E2E 제목에 다는 날 바로 집계된다. Python 쪽은 `scripts/run_script_tests.py`가 `scripts/tests`를 JUnit XML로 내보내고 `api-quality`가 그것을 같이 먹인다. 그래서 build toolchain·target-stack marker·CI wrapper처럼 **Java로는 증명할 수 없는 acceptance도 이제 집계된다**(`BA-001-T3`이 첫 사례다). docstring이 acceptance ID로 **시작할 때만** 주장으로 세며, 산문 속 언급은 method 이름으로 보고돼 regex에 걸리지 않는다 — 이 저장소에 그런 docstring이 이미 있다. **게이트 판정은 `scripts/record_gate_evidence.py`가 받는다** — testcase가 단언할 수 없는 것(`BA-004-T3`의 *"실제 Compose에서 재현"*)을 게이트가 증명할 때, **그 게이트가 실제로 낸 verdict token이 report에 있을 때만** 기록한다. 토큰이 없으면 기록하지 않고 실패한다 — 없으면 *"shell script의 한 줄이 실행됐으므로 acceptance가 증명됐다"* 가 되고 그게 rubber stamp다. **증거는 집계보다 먼저 생산돼야 한다**: 이 판정을 집계기 뒤에 두면 파일은 증거처럼 보이는데 집계기는 매번 못 읽는다.

   **한 곳에서만 보이는 증거는 한 곳에서만 물어라.** `api-quality`는 full Compose를 띄우지 않아 `gateChecks`를 만들 수 없으므로 `--backend-plan`을 **주지 않는다**. 볼 수 없는 답을 묻는 검사는 없는 것보다 나쁘다 — 사람이 그 빨간색을 무시하는 법을 배운다. 카드 완결성은 required gate의 질문이다. 그래서 **FE가 소유한 acceptance ID는 그가 실제로 구현해도 집계기에 나타나지 않았다** — `BA-040-T4`(keyboard/focus E2E)가 Playwright라 그랬고, 그 카드를 `integration-ready`로 올리려면 그 ID가 JUnit 이름에 있어야 하므로 **승격이 영원히 막혀 있었다.** `BA-004`의 Python test가 `scripts/tests`에 있어 Java 집계기만 보면 0건이던 것과 같은 모양이고, 이번에는 소유자가 FE다. **`BA-004` 쪽은 `--script-junit-dir`로, FE 쪽은 `--e2e-junit-dir`로 배선이 닫혔다**(위 항목, #233) — 그리고 **그 E2E도 [#282](https://github.com/yutakdv/Nullnull/pull/282)로 생겼다**: `keyboard-flow.spec.ts`가 일정 편집을 키보드로 조작하며 `BA-040-T4` 이름의 testcase 4건과 `BA-070-T5` 이름의 testcase 7건이 게이트 E2E JUnit에서 통과했다. 그전의 keyboard test는 404 페이지 링크 포커스뿐이었고, 거기 그 ID를 달았으면 *"ID는 있는데 절이 증명 안 됨"* 이 됐을 것이다. **`BA-070-T5`는 아직 `externalOwner`를 단다** — 네 절 중 *"focus ring이 여러 Tab에 걸쳐 보인다"* 를 재는 test가 `responsive.spec.ts`에 FE ID로만 있어서다(카드에 사유를 적었다). **그 사유는 이 문장이 머지된 뒤 사실이 아니게 됐다**: FE가 `BA-070-T5`를 그 testcase(`puts focus on something visible`) 제목에 달았고, 그것이 그 절을 재는 유일한 test다 — Tab을 여덟 번 누르고 각 정지점을 **자기 자신의 resting style**과 대조하므로 평소에도 있는 장식 그림자로는 통과하지 못한다. 같은 이유로 `keyboard-flow.spec.ts`의 Tab 1회·`boxShadow !== 'none'` 판정에서는 ID를 뗐다(그 절에 대해 발화할 수 없는 단언이었다). 게이트 JUnit에 그 이름이 잡히면 `externalOwner`를 뗄 수 있고, **판정은 소유자인 BE/AI가 한다** — 여기서는 전제가 바뀐 것만 적는다. 다만 **"FE plan으로 옮기면 된다"는 답이 아니다**: `check_test_reports.py`는 backend plan의 ID를 **실제 JUnit testcase 이름**과 대조하는데, `validate_frontend_plan.py`는 `verified` 카드의 `evidence.testIds`가 **그 카드 자신의 `tests[].id`와 같은지**만 보고 `report`·`contractSha`·`reviewer`는 빈 문자열이 아닌지만 본다 — report를 열지도, test 실행과 대조하지도 않는다. 옮기면 "집계기가 못 보는 ID"가 "아무것도 검증하지 않는 ID"가 된다.

   실제로 걸린 둘: `BA-002-T3`(*"transaction 중간 장애는 전체 rollback하며 **구버전 app 호환성이 유지된다**"*)은 `integration-ready`인데 ID를 단 testcase **20개**가 전부 첫 절이었고 둘째 절을 다루는 것이 없었다. **닫았다**([#194](https://github.com/yutakdv/Nullnull/issues/194)): `T3`(rollback)와 `T4`(직전 schema 모양의 write가 최신 schema에서 받아들여진다)로 쪼개고 `T4`를 `FlywayMigrationIT`에서 증명했다. 쪼개면서 **절 문구도 좁혔다** — 원래 문구를 그대로 `T4`에 달면 카드가 스스로 *"통과로 쓰지 않는다"* 고 적어둔 구버전 binary rehearsal에 ID를 다는 것이 된다. **쪼갤 때는 남은 절이 무엇을 증명할 수 있는지까지 다시 읽는다.** `BA-034-T1`(*"**두 tap**·서로 다른 key 동시 요청·다른 post 같은 POI"*)은 세 절 중 첫 절이 비어 있었다 — 동시성 case는 일부러 key를 다르게 주므로 대체가 되지 않는다(index가 없어도 두 tap은 한 행, guard가 없어도 동시성은 한 행이다). 기존 카드를 일괄로 쪼개지는 않고 status를 올릴 때 절이 다 덮였는지 본다.
4. 새 app 디렉터리나 suite가 생기면 workflow path filter, `compose.integration.yml` service, `scripts/integration-test.sh` 실행 단계, 이 표를 같은 PR에서 바꾼다.

   **새 migration을 넣으면 `FlywayMigrationIT`의 두 곳이 같이 움직인다.** `previousSchemaUpgradesToTheLatestVersion`은 *직전 버전까지 migrate → 모든 table에 행을 넣고 → 최신까지 migrate*한다. 그래서 `populateEveryTable`의 table 목록은 **항상 마지막 migration의 직전까지**를 담는다 — `V025`를 넣는 PR은 `V024`가 만든 table을 **그때** 채워야 하고, `V025` 자신의 table은 `V026`이 생길 때 들어간다. **이건 `V025`를 커밋하는 쪽의 몫이다**: 앞 migration의 주인이 미리 넣으면 그 PR의 CI에는 뒤 migration이 없어 존재하지 않는 table에 INSERT한다.

   행 수 단언(`seededAfterPreviousSchema`)도 같은 이유로 움직인다. **숫자가 움직이는 것이 그 장치가 작동하는 방식**이므로 왜 바뀌었는지 주석에 적고 지우지 않는다 — 그 단언이 "조용히 데이터를 심는 migration"을 드러내는 유일한 장치다.
5. skip·0건 실행·report 누락·`continue-on-error`·`ignoreFailures`는 금지다. path filter workflow는 조기 피드백일 뿐 required status로 승격하지 않는다.
6. **여러 세션이 한 checkout을 공유하면 `apps/api` 검증은 격리 worktree에서 한다.** 파일을 나누는 것으로는 부족하다 — 겹치는 것은 파일이 아니라 **빌드 산출물과 전역 합계**다. 동시 Gradle이 같은 resource jar를 다시 쓰면 test가 읽던 jar가 깨져 `Unable to calculate checksum`·`EOFException: ZLIB`로 **모든 Spring context가 기동 실패**하고, 자기 변경과 무관한 suite까지 전부 빨개진다(실측: 285개 중 131개).

   **worktree는 `HEAD` + 자기 파일만으로 만든다.** 공유 트리를 `rsync`로 통째로 동기화하면 격리되는 것은 `build/`뿐이고 **남의 미커밋 중간 상태가 함께 복사된다.** 실제로 한 세션이 그렇게 만든 트리에서 남의 편집 중인 파일 때문에 빨개진 것을 **자기 migration 탓으로 30분 좁혀 들어갔다.** 그걸 깬 측정은 *"내 변경을 통째로 지워도 같은 실패가 재현되는가"* 였다 — 재현되면 원인은 내 변경이 아니다. 그다음이 두 트리 `diff -rq`다.

   **`.git`도 공유다.** `git push origin backend`는 브랜치 전체를 밀므로, 한 세션의 push가 **다른 세션이 방금 만든 미검증 커밋까지 공개한다**(실제로 일어났다 — amend하려던 커밋이 먼저 나갔다). push 전에 `git log --oneline origin/backend..HEAD`로 **밀 커밋 목록을 먼저 보고**, 남의 것이 섞였으면 그 세션에 알린다. **그리고 그 순간이 완주 초록을 본 직후라면 가장 위험하다** — 초록은 `origin/backend`가 가리키는 tree에 대한 판정이고, 그 사이 누가 커밋했다면 **초록이 가리키는 tree와 밀리는 tree가 다르다.** 실측: 완주 초록이 나온 순간 공유 checkout의 `HEAD`는 이미 한 커밋 앞서 있었고, 그것을 밀었으면 **게이트를 통과한 적 없는 배선 커밋이 초록 뒤에 숨어 나갔을 것**이다(미리 재보니 그 커밋이 `BA-050` test 둘을 깼다). *"새로 밀 것이 없다"* 는 확인 없이 참이 되지 않는다.

   **`.git`의 index도 공유다 — `git add`와 `git commit` 사이가 창이다.** push 규칙이 *"남의 커밋이 내 push에 실린다"* 라면 이것은 그 앞 단계이고 방향이 반대다: **내 파일이 남의 커밋 메시지 아래로 들어간다.** 실제로 한 세션이 9파일을 staging한 뒤 `git diff --cached --stat`으로 확인하는 사이에 다른 세션이 `git commit`을 했고, index가 하나뿐이라 **그 9파일이 전부 남의 커밋에 실렸다**(`f96cd6f` — 메시지는 `BA-052`의 eraser 하나만 설명하는데 내용의 9/10이 `BA-060`이다). **`git add`와 `git commit`을 한 명령으로 묶어라.** 사이에 확인을 끼우면 그 창이 열린다 — 확인은 `git commit` **뒤에** `git show --stat`으로 한다. 그리고 **이미 밀린 것은 되돌리지 않는다**: 세 세션이 붙어 있는 branch의 history를 다시 쓰는 것이 잘못된 커밋 메시지 하나보다 나쁘다. 잃은 것은 추적성뿐이므로 **카드에 `절 → 커밋` 매핑을 적어 복구한다.**

   **그리고 그 규칙을 지켜도 안 막히는 구멍이 하나 있다: `reset --soft` 뒤의 `commit`은 index 전체를 커밋한다.** 남의 hunk를 빼려고 `git reset --soft HEAD~2`를 하면 되돌린 커밋들의 내용이 전부 index에 올라오고, 거기서 `git add <내 파일> && git commit`을 해도 들어가는 것은 **index 전체**다. 실측(버리는 저장소): `reset --soft HEAD~2` 뒤 index에 `b.txt`·`c.txt`가 있었고 `git add c.txt && git commit`이 **둘 다** 커밋했다. 앞 문단의 *"한 명령으로 묶어라"* 는 **창**을 닫는 규칙인데 이것은 창이 아니라 **`commit`이 보는 범위**의 문제라, 그 규칙을 정확히 지킨 사람이 여기 빠진다.

   **되는 형태는 `git commit -m … -- <경로>`다**(같은 실측: `c.txt`만 커밋되고 `b.txt`는 index에 남았다). `reset --mixed`도 증상은 없애지만 **staging을 전부 푼다** — 재구성 중에는 남의 hunk를 index에 둔 채로 내 것만 내는 것이 요구사항이므로 `--mixed`는 그 요구를 만족하지 못한다. 둘을 나란히 적으면 다음 사람이 `--mixed`를 골라 같은 자리에서 한 번 더 물린다.

   **작업 파일을 아예 안 건드리는 형태가 더 낫다**: `git show HEAD:<파일>`을 꺼내 내 편집만 적용하고 그 blob을 index에 직접 넣으면(`hash-object -w` + `update-index --cacheinfo`) 남의 미커밋 hunk가 **디스크에서 한 번도 사라지지 않는다.** 아래 열째가 *"되돌리는 순간이 남의 작업이 마지막으로 위험한 자리"* 라고 적은 그 순간이 없어진다.

   **선언은 소유가 아니다 — 그리고 예고한 직후가 가장 위험하다.** 위 항목이 *"내 파일이 남의 커밋으로 간다"* 라면 이것은 그 거울상인 *"남의 파일을 내 것으로 읽는다"* 다. 한 세션이 조율자에게 *"내가 만질 파일은 `apps/api/src/main/java/io/nullnull/shared/…`"* 라고 적어 보냈고, **그 직후** `git status`에 정확히 그 자리의 새 디렉터리 둘이 `??`로 떴다. 그 세션은 **코드를 한 줄도 쓰지 않은 상태였다.** 열어보니 다른 세션의 `DeploymentProfileGuard`와 그 test였다 — 파일 이름은 안 겹치고 package만 같았다. 확인하지 않고 `git add`를 했으면 남의 작업이 그 세션의 커밋에 실렸고, `git checkout --`를 했으면 날아갔다. **이 저장소는 그 두 사고를 이미 각각 겪었고 위 두 항목이 그것이다.** 확인 비용은 `find` 한 번이었다.

   **선언하는 행위 자체가 오독의 조건을 만든다.** 그래서 이것은 단순한 *"확인해라"* 와 다르다 — 자기가 방금 적은 경로가 머릿속에서 가장 최근 항목이므로, 그 자리에 나타난 낯선 파일은 *"내가 만든 것"* 으로 읽히는 쪽이 기본값이 된다. 공유 checkout에서 경로를 예고하는 것은 그 경로를 **예약하지 않는다.** 예약하는 것은 아무것도 없다: 작업 트리는 세션 수와 무관하게 하나이고 경로 기반 git 명령은 전부 그 하나를 본다(열째). 부수 효과로 **package가 같으면 파일 이름이 안 겹쳐도 source set이 같다** — 컴파일 불가 상태로 저장하면 옆 세션의 test 실행이 죽는다(아래 *"막는 것은 실행이 아니라 저장이다"*). 규칙은 거기 한 군데에만 둔다.

   **그리고 컴파일 불가 상태의 파일은 그 자체로 공유 자원을 잠근다.** `compileIntegrationTestJava`는 source set **전체**를 컴파일하므로, 한 세션이 record를 넓히고 기존 호출부를 안 고친 채 **저장만 해도** 다른 세션의 test 실행이 `cannot find symbol`로 죽는다. Gradle을 안 돌려도, commit을 안 해도 그렇다 — *"나는 실행을 피했으니 안전하다"* 가 틀리는 자리다. **막는 것은 실행이 아니라 저장이다.** 기존 test가 쓰는 record·시그니처를 넓힐 때는 **호출부를 같은 편집에서 고치고**, 저장 뒤 격리 worktree에서 `compileIntegrationTestJava`를 한 번 돌린다(4초다).

   **조율자의 말은 가장 빠르게 퍼지므로 가장 싸게 확인돼야 한다.** 세 세션에서 조율자가 낸 표기·상수·ID 형식은 검증 없이 양쪽으로 간다 — 실제로 조율자가 지어낸 `T8b` 형식이 메시지 **두 번**만에 두 카드로 번졌고(집계기 regex `BA-\d{3}-T\d+`에 매칭조차 안 되는 형식이다), 조율자가 **동료의 미확인 문장을 근거로** 내린 게이트 동작 결정이 카드에 실렸다가 그 동료의 반증으로 뒤집혔다. 그래서 규격은 쓰는 자리에서 대조하고, **조율자가 인용한 근거도 인용된 쪽이 다시 확인한다.** "네가 그렇게 말했다"는 가장 싸게 확인할 수 있는 주장이다.

   **하루에 두 번 더 났고 둘 다 동료가 잡았다. 두 번째는 모양이 다르다.**

   - **찾아본 곳에 없는 것을 없다고 말했다.** `git`이 라이선스 게이트로 죽자 조율자가 `/opt/homebrew/bin`·`/usr/local/bin`·`/usr/bin` 셋을 보고 *"이 기계에 다른 git 바이너리가 없다"* 고 단정했고, 그 위에 *"오너만 `sudo`로 고칠 수 있다"* 를 얹어 **네 세션에게 커밋·push 금지를 뿌렸다.** 실제로는 `/Applications/Xcode.app/.../usr/bin/git`과 `/Library/Developer/CommandLineTools/usr/bin/git`이 있었고 `DEVELOPER_DIR` 한 줄이면 됐다 — **세 세션이 각각 독립적으로** 그것을 찾아 보냈다. 부재를 주장할 때는 **어디까지 찾았는지**를 같이 적는다.
   - **다른 언어의 관용구를 확인 없이 옮겼다.** 빈 환경변수 문제의 해법으로 조율자가 `${VAR:-default}`를 제시했는데 그것은 **셸 문법**이다. Spring `PropertyPlaceholderHelper`의 `valueSeparator`는 `:` 하나라 `${VAR:-default}`는 *기본값이 `-default`인 문자열*로 읽힌다 — **고치는 대신 조용히 틀린 값을 넣는다.** 받은 세션이 *"미검증이니 퍼뜨리지 마라"* 로 멈춰서 다음 세션까지 가지 않았다.

   **둘째가 앞의 것들과 다른 이유**: 미확인 *사실*이 아니라 **미확인 *문법***이다. 사실은 틀리면 대개 시끄럽게 실패하는데, 잘못된 placeholder는 **파싱되고 기동하고 틀린 값을 담는다.** 그래서 이 부류는 `&&`로도 exit code로도 잡히지 않는다 — **쓰는 자리에서 실제로 해석시켜 보는 것**이 유일한 확인이다.

   **git은 경로를 알지 소유자를 모른다 — 열째다.** `diff`·`add`·`commit`·`stash`·`checkout --` 전부 **경로 단위이지 작성자 단위가 아니다.** *"내 변경만"* 을 뜻하는 git 명령은 없다. 그래서 한 파일을 남과 공유할 때 *"그쪽 것만 patch로 떠서 비켜준다"* 는 절차는 **첫 줄에서 이미 깨진다**: 실측으로 두 파일의 `git diff`가 157줄이었고 그 안에 한 세션의 18줄과 다른 세션의 7줄이 **함께** 들어 있었다. 이어지는 `git checkout --`은 **둘 다** 지우고, 커밋할 것이 없어지고, 마지막 `git apply`가 출발점으로 되돌린다. 일곱째(*"`git checkout --`은 내가 고치지 않은 파일에만 맞다"*)가 겨누던 상황이고, **그 규칙을 쓴 조율자가 자기 규칙을 `cp`에 대한 것으로만 읽어서** patch로 바꾸면 피했다고 믿은 자리다.

   **비켜주는 방식은 하나다: 커밋하는 쪽이 `HEAD` 위에 자기 편집만 재구성한다.** 남은 아무것도 하지 않고 그의 파일은 디스크에서 한 번도 사라지지 않는다. 재구성이 맞는지는 **결합 파일과 대조해서** 확인한다 — 차이가 남의 hunk와 **정확히** 같아야 하고 하나라도 다르면 **거기서 멈춘다**. 실패 모드가 *"남의 작업이 날아간다"* 에서 *"재구성이 안 맞으면 시작 전에 멈춘다"* 로 바뀌는 것이 이 절차의 값이다. 되돌림도 **예측하지 말고 관측한다**: 복원 뒤 `git diff`에 남의 hunk가 돌아왔는지(실측 `7`)와 내 것이 남지 않았는지(실측 `0`)를 **둘 다** 세고 어긋나면 중단한다. 그 순간이 남의 작업이 마지막으로 위험한 자리다.

   **그리고 재구성의 base가 아직 유효한지 먼저 묻는다** — `git diff --name-only <base> HEAD -- <경로>`가 비어 있지 않으면 그 사이 누가 그 경로를 커밋한 것이고 **재구성이 그것을 지운다.** 전역 합계의 다른 얼굴이다: 움직인 값이 합계가 아니라 **내 재구성이 딛고 선 base**이고 그것도 내 파일에 없다. 실측으로 base는 `4fbfc16`이었는데 커밋 시점 `HEAD`는 `06b56ec`였다 — 그 커밋이 두 파일을 건드리지 않아 비어 있었을 뿐이다.

   **비켜줄 hunk에 살아 있는 주인이 있는지도 먼저 확인한다.** 비켜주기는 *"나중에 다시 얹는다"* 를 전제하는데 **주인이 없으면 그 전제가 없다** — 아무도 이어받지 않고, 지우면 복구할 사람이 없다. 주인 없는 미커밋 작업은 비켜주기의 대상이 아니라 **에스컬레이션 대상**이다.

   **그리고 그 질문의 답이 셋일 수 있다.** *"살아 있는 주인이 있는가"* 에 **있다**(그가 이어받는다)와 **없다**(에스컬레이션한다) 말고 **"없지만 지워도 안 되고, 기한이 정해져 있다"** 가 있다 — 미커밋 편집이 **의도적으로 보류된 것**일 때다. 실측으로 한 경로의 tracked 7파일·untracked 18개가 그랬다: 만든 세션은 사라졌고 그래서 *"주인 없는 작업"* 으로 읽었는데, 오너에게 물으니 **기능 개발이 끝나는 시점에 쓰려고 미리 만든 것**이라 그때까지 커밋하지 않고 그대로 둔다는 답이었다. **에스컬레이션이 그 답을 만들어 냈으므로 앞 문단은 그대로 옳다** — 틀린 것은 결론이 아니라 그 전에 붙인 이름이다.

   이 셋째 칸은 **비켜주기를 일회성이 아니라 반복 작업으로 만든다.** 주인이 이어받기를 기다리는 것이 아니라 **그 상태가 기한까지 계속되므로**, 같은 파일을 만질 때마다 매번 `HEAD` 위에 자기 편집만 재구성한다. 대신 쉬워지는 것이 하나 있다: **그 hunk는 자라지 않는다.** base 가드(*"그 사이 누가 그 경로를 커밋했나"*)만 보면 되고 상대의 편집이 움직일까 걱정할 필요가 없다. **카드나 작업이 그 판정을 기다리면 기한까지 안 나간다** — 기다리는 대신 비켜서 간다.

   **동결은 명령 이름이 아니라 효과로 적는다 — 열한째다.** *"이 경로에 `git checkout --`·`stash`·`restore`를 돌리지 마라"* 는 **`rm`과 `git clean`에 대해 아무 말도 하지 않는다.** 금지를 명령 이름으로 적으면 **반드시 빠진 명령이 생기고** 빠진 것이 가장 파괴적일 수 있다. 적어야 하는 것은 **그 경로의 파일이 사라지거나 바뀌게 하는 모든 것**이고 명령 이름은 예시일 뿐이다(`checkout --`·`restore`·`stash`·`clean`·`rm`). **untracked에 대해서는 git 명령 목록만으로 부족하다** — editor의 삭제, 빌드 script의 정리 단계, `find -delete`가 전부 같은 효과를 낸다.

   **그리고 tracked와 untracked는 무게가 다르다.** tracked는 `HEAD`가 사본을 들고 있어 최악이 *"그 편집을 잃는다"* 지만, **untracked는 git object가 없어** `rm` 한 번, `git clean -fd` 한 번이면 **아무 데도 없다.** 실측으로 한 경로가 tracked 21줄과 **untracked 18개 파일**을 함께 들고 있었고 동결은 tracked 쪽만 겨누고 있었다. **그 `21줄`도 부분이다** — 내가 그때 센 것은 **내가 재구성하던 두 파일**이었고, 같은 세션의 같은 작업은 실제로 **tracked 7파일 42+/30-**에 걸쳐 있었다(나머지 다섯은 `README`·`docs/README`·`AWS_DEPLOYMENT`·`STAGING_BRINGUP_PLAN`·`DECISIONS_AND_RISKS`이고 mtime이 전부 같은 시간대다). **앞 문단이 *"항목을 세지 말고 파일을 세라"* 라면 이것은 그 옆이다: 파일을 세긴 셌는데 내가 만지던 것만 셌다.** 세기 전에 **무엇의 크기를 재는지**를 정한다 — *"내 작업 범위"* 와 *"그 작업이 걸친 범위"* 는 다른 것이고, 동결·백업·에스컬레이션은 전부 후자를 묻는다. **untracked는 세는 것부터 틀린다**: `git status --porcelain`은 untracked **디렉터리를 한 줄로 접어서** `scripts/aws/` 한 줄 뒤에 12개를, 다른 한 줄 뒤에 production guard와 그 test를 숨긴다. 실제로 그 줄을 세어 *"15개"* 라고 보고했고 진짜 숫자는 **18개**였으며 빠진 둘이 **커밋 권한을 못 받아 디스크에만 있던 작업**이었다. **항목을 세지 말고 파일을 세라**(`--untracked-files=all`). **그리고 백업의 기준은 열째의 기준과 다르다** — 열째는 *"살아 있는 주인이 있는가"* 로 비켜주기와 에스컬레이션을 가르지만, **주인이 있어도 복구 경로는 없을 수 있다.** 실측으로 그 18개 중 셋은 **살아 있는 세션의 완성품**이었는데도 디스크가 유일본이었다 — 그 세션이 **커밋 권한을 받지 못해** git에 넣을 수 없었기 때문이다. 그래서 **에스컬레이션은 주인 유무로 가르고 백업은 복구 경로 유무로 가른다**: 주인이 있으면 오너 판정 대상이 아니지만, git object가 없으면 사본은 떠 둔다. 주인 없는 untracked에는 오너 결정 전까지 **트리 밖 사본을 한 벌 떠 두고**, 그 사본이 세션 범위라 영구 보관이 아니라는 것까지 적는다 — 그리고 **내용 사본과 시각 사본은 다른 물건이다**: 보통의 `cp`는 내용을 지키고 **mtime을 복사 시각으로 덮는다**(실측으로 그렇게 잃을 뻔했다). 소멸성 증거는 `cp -p`이거나 값을 따로 적어 두는 것이거나 둘 중 하나다.

   **그리고 로컬 초록은 이 충돌에 대해 아무 말도 할 수 없다 — 아홉째다.** `TestcontainersConfiguration`은 `@SpringBootTest` 설정이 다른 context마다 **자기 PostgreSQL 컨테이너**를 띄운다. 필수 게이트는 `NULLNULL_TEST_DATABASE=external`로 **모든 context가 한 DB를 공유한다.** 그래서 *"다른 test가 남긴 행이 내 DELETE를 막는다"* 는 부류는 **로컬에서 재현될 수 없고**, 로컬 초록은 그 질문에 답한 적이 없다. 실측: `OwnerIsolationMatrixIT`가 trip·place를 정리하지 않아 `RedactionAndDenylistIT`의 `DELETE FROM places`가 `trip_candidates` FK에 걸렸고, Compose 게이트에서 **387 중 79가 빨갰다.** 목록의 첫 이름은 **남의 test**였다 — `places`는 일부러 cascade하지 않으므로(후보 밑에서 장소가 사라지면 안 된다) 행을 남긴 쪽이 아니라 지우려는 쪽이 죽는다. 로컬에서 두 class를 순서까지 맞춰 돌려도 초록이었고, **변이가 발화하지 않는 것을 "내 가설이 틀렸다"로 읽을 뻔했다.**

   게이트 조건은 **로컬에서 만들 수 있다.** 이 한 번으로 CI와 같은 실패 두 건이 같은 이름으로 재현됐다:

   ```bash
   docker run -d --name nullnull-repro-db -e POSTGRES_DB=nullnull_repro \
     -e POSTGRES_USER=nullnull -e POSTGRES_PASSWORD=repro-only -p 55432:5432 \
     postgres@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94
   NULLNULL_TEST_DATABASE=external \
     SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/nullnull_repro \
     SPRING_DATASOURCE_USERNAME=nullnull SPRING_DATASOURCE_PASSWORD=repro-only \
     ./gradlew --no-daemon integrationTest --rerun --tests '*AIT' --tests '*BIT'
   ```

   **이 결함은 얼굴이 둘이고 뿌리는 하나다 — 행을 지목하지 않는 문장.** 실패 77건을 원인별로 가르면 **66건이 통짜 `DELETE`가 FK에 걸려 죽은 것**이고 **11건은 단언이 틀린 것**이다. 후자의 모양은 `assertThat(count("SELECT count(*) FROM places")).isOne()`이 `expected: 1 but was: 216`으로 죽는 것이다 — **공유 DB에서 전역 개수를 읽으면 그것은 내 test에 대한 단언이 아니라 앞서 돈 모든 test에 대한 단언이다.** *"내가 넣은 것이 하나다"* 가 재려던 것이고 *"표에 하나뿐이다"* 는 그것을 재는 척한 것이다. **기준은 `WHERE`의 존재가 아니라 *자기 행을 지목하는가*다.** `DELETE FROM media_assets WHERE NOT EXISTS (SELECT 1 FROM posts …)`는 `WHERE`가 있지만 *"post가 안 쓰는 모든 asset"* 이라 남의 place media를 전부 포함한다 — catalog가 통짜 삭제를 그만두자 그 자리가 바로 13건 중 8건이 됐다.

   **그리고 값이 겹치는 것으로는 부족하다 — 같은 *열*에서 겹쳐야 위험이다.** `WHERE source_code = ?`가 위험한 것은 그 값을 **같은 열에** 쓰는 다른 class가 있기 때문이고, 값만 같고 열이 다르면 위험이 아니다. 실측: 전수 조사에서 `JobWorkerIT`의 `WHERE type LIKE 'worker-%'`가 위험 목록에 올랐다 — `JobLeaseIT`에 `"worker-a"`가 있어서다. **본문을 읽으니 그것은 job type이 아니라 worker 이름이었고**(`claim("worker-a")`), 그 class의 type은 `lease-test`다. grep은 이 둘을 구별하지 못한다. 190개 문장 중 실제 위험은 **1건**이었고, 이 단계가 없었으면 거짓 양성이 하나 섞인 목록이 나갔을 것이다 — 같은 날 조율자가 85/86을 거짓 양성으로 뿌린 뒤였다.

   **읽기 쪽이 쓰기 쪽보다 나쁘고, 얼굴이 셋이다.** 쓰기만 고치면 절반이다 — 같은 값으로 범위를 잡는 **읽기**가 남아 있으면 검사는 여전히 남의 행을 본다.

   1. **`count(*) … WHERE <공유 값>`을 수로 단언한다** — 남의 행을 센다. 앞의 *전역 개수* 와 같은 부류인데 `WHERE`가 있어 검사를 통과한다.
   2. **단일 행 쿼리가 죽는다** — `SELECT status … WHERE source_code = ?`는 남의 run이 하나만 있어도 `IncorrectResultSizeDataAccessException`이다. 시끄럽게 실패하니 그나마 낫다.
   3. **`ORDER BY … LIMIT 1`은 죽지 않는 대신 남의 행을 검사 대상으로 삼는다** — canary를 남의 run에서 찾고 **통과한다.** 셋 중 제일 나쁘다: *"검사 범위가 조용히 줄어든 경우"* 의 가장 완성된 형태이고, 초록이 아무것도 말하지 않는다.

   **그리고 이 부류의 fix는 초록 두 개로 증명되지 않는다.** *"남의 행이 살아남았다"* 와 *"내 단언이 통과한다"* 는 **아무것도 안 지우게 만든 가짜 fix도 만족한다.** 세 번째 조건이 그것을 배제한다 — **"자기 행을 남기지 않았다(0)"**. 셋을 같이 재라.

   **행을 만드는 test는 그 행을 지운다.** trip 아래(`trip_items`·`trip_candidates`·`candidate_sources`·`trip_constraints`)는 cascade하고 `owners.active_trip_id`는 ON DELETE SET NULL이므로 `trips` 삭제 하나로 충분하지만, **`places`는 자기 문장이 필요하다.**

   **그리고 *"검사가 N건 잡는다"* 와 *"게이트가 N건 빨갛다"* 는 등급이 다른 주장이고, 작업을 정당화하는 것은 후자뿐이다.** 조율자가 새 검사의 findings 86건을 세 세션에 작업 목록으로 보냈는데 **85건이 거짓 양성**이었다 — 검사가 Java 문자열의 끝을 세 가지 방식으로 잘못 잡았다(SQL의 작은따옴표, `" + "` 이어붙이기, jsonb의 이스케이프된 큰따옴표). 잡은 것은 **인용된 쪽이 자기 파일을 다시 읽어서**였다. 다만 같은 목록 안에서 **게이트가 실제로 빨갰던 77건은 거짓이 아니었고** 그것을 고친 작업은 게이트가 독립적으로 확인했다. **부풀려진 것은 "아직 발화하지 않은" 쪽이고, 조율자가 그 둘을 한 숫자로 합쳐 보냈다.** 목록을 돌릴 때 두 숫자를 **따로 적는다** — 하나는 측정이고 하나는 도구의 주장이다.

   **그래서 testcase 총계의 절대값을 남의 것과 비교하지 마라 — 여덟째다.** 두 세션이 같은 suite를 재고 `379`와 `381`을 보고했고, 조율자가 그 차이를 *"한쪽 실행이 자기 파일을 안 봤다"* 로 읽고 규칙까지 쓰려 했다. **둘 다 옳았다**: 한쪽 worktree는 `dd606f0`(377)에서, 다른 쪽은 `19a7da1`(379)에서 만들어졌고 그 사이 커밋이 testcase를 둘 늘렸다. `377+2=379`, `379+2=381`. **절대값은 잰 시각의 함수이지 변경의 함수가 아니다.** 비교해야 하는 것은 **같은 commit에서 잰 baseline과의 차이**다 — worktree가 어느 commit에서 만들어졌는지 적고 `baseline(그 commit) + 내가 더한 case 수 == 내 실행의 총계`인지 본다. 확인은 `git show <commit> -- 'apps/api/src/*Test*/*' | grep -c '^+\s*@Test'` 한 줄이었고, 조율자는 그것을 안 하고 추론을 보냈다 — **규칙이 될 주장일수록 가장 싸게 확인돼야 한다.** 카드 증거에 총계를 적을 때는 **어느 commit에서 잰 것인지 같이 적는다.**

   **worktree를 공유 트리에서 원복하지 마라 — 일곱째다.** 변이를 되돌릴 때 `cp $공유트리/파일`을 쓰면 가져오는 것은 `HEAD`가 아니라 **그 순간 남이 편집 중인 상태**다. 실제로 한 세션이 그렇게 원복했는데 옆 세션이 `CursorClaims`와 `SignedCursorCodec`을 **함께** 고치는 중이었고, 그중 한 파일만 복사해 와서 worktree가 컴파일 불가가 됐다 — 그것을 **변이 결과로 읽을 뻔했다.** **원복 방법은 파일이 누구 것이냐에 따라 갈린다** — 한 문장으로 적었더니 절반이 틀렸다: `git checkout -- <path>`는 **내가 고치지 않은 파일**에만 맞고, **내 미커밋 파일에 쓰면 내 작업이 날아간다**(같은 세션이 그날 그것도 당했다). 내가 고친 파일이면 **내 트리에서 그 파일만 이름으로** 복사한다. 그리고 애초에 **worktree를 만들 때도 `git status` 루프를 돌리지 마라** — 세션이 셋이면 그 목록은 내 것이 아니다. **`HEAD` + 내 파일을 이름으로 나열**한다. 둘을 섞으면 한쪽은 남의 작업을 끌어오고 다른 쪽은 내 작업을 지운다. `cp`는 *"worktree는 `HEAD` + 자기 파일만으로 만든다"* 를 뒷문으로 깨는 것이고, 앞의 `rsync` 사례와 같은 실패가 **되돌리는 순간에** 일어나는 판이다. 규칙 7②의 *"되돌린 뒤 `git status`로 확인한다"* 가 이것을 잡는 마지막 그물이다.

   그리고 **전역 합계는 각자의 파일에 없지만 각자의 변경에 반응한다.** `JobConnectionBudget`은 job type 수의 **합**에 걸리고, `FlywayMigrationIT`의 "previous schema"는 **마지막 migration이 무엇인가**에 걸린다. 그래서 두 PR이 **각자 green인데 merge하면 red**가 될 수 있다. 이런 값은 **한 사람이 마지막에 정한다** — "나중에 들어가는 쪽이 다시 계산한다"는 규칙은 누가 마지막인지 아무도 모를 때 깨진다.

7. **검사를 넣었다와 검사가 발화한다는 다른 확인이다. 새 검사를 넣는 PR에서 둘 다 한다.**

   ① **돌았는가** — log에 step이 명령과 함께 찍혔는가. **초록도 빨강도 판정이 아닐 수 있다.** 초록은 *"돌고 통과했다"* 와 *"조용히 안 돌았다"* 둘 다와 양립하고, **빨강은 *"틀렸다"* 와 *"물어보지 못했다"* 둘 다와 양립한다** — 격리 worktree에 `node_modules`가 없어 `openapi-typescript: command not found`(exit 127)로 빨개진 것을 *"생성물이 낡았다"* 로 읽을 뻔한 일이 있었다. **exit code는 도구 부재와 검사 실패를 구분하지 못한다.** 그래서 그 빨강을 전파하기 전에 **검사가 판정을 내렸는지** 먼저 본다. **그리고 무엇을 물을지에서 이미 기울 수 있다** — `gh pr view`에 `headRefOid`·`statusCheckRollup`을 묻고 **`state`만 빼서** 이미 머지된 PR을 열려 있다고 읽은 일이 있었다. 읽지 않고 추론한 것이 아니라 **읽기는 읽었고 자기 그림을 지지하는 필드만 골랐다.** 고치는 방법은 조회를 늘리는 것이 아니라 **이 주장을 거짓으로 만들 값이 무엇인지 먼저 적고 그것을 묻는 것**이다. 그리고 **"두 측정이 일치했다"** 를 쓸 때는 **얼마나 독립인지**를 같이 적는다 — 같은 suite를 두 경로로 돌린 것은 *두 방법*이 아니라 *한 방법의 두 실행*이고, 일치는 거의 보장돼 있어 아무것도 더 말하지 않는다(`ajv test --invalid`의 glob 0건, egress probe의 exit code, `actual_call=blocked`가 전부 그 모양이었다).
   **①에는 배선판이 있다 — 검사는 돌았고 판정도 냈는데 그것을 읽는 줄이 끊긴 경우다.** `검사 | tail -8 && commit`에서 `&&`가 보는 것은 검사가 아니라 **`tail`의 exit code**이고 `tail`은 언제나 0이다. 실제로 `markdownlint`가 1건을 찍은 실행에서 바로 다음 줄의 `echo OK`가 찍혔다. 같은 모양이 background 실행에도 있다 — harness가 보고하는 exit code도 pipe의 것이다. **검사와 commit·push를 `&&`로 묶을 때는 `set -o pipefail`을 켠다.** ①이 *"빨강이 판정이 아닐 수 있다"* 라면 이것은 그 거울로 **초록이 판정이 아니라 pipe인 경우**다.

   **그리고 초록에는 셋째 얼굴이 있다 — 돌았고, 통과했고, 통과시킨 것이 test가 재려던 것이 아니었다.** 앞의 둘은 *실행 여부*의 문제라 log가 답하는데 이것은 **의존 관계**의 문제라 log에 정상으로 찍힌다. 실측: `OptimizeRevertIT`가 `ORDER BY decided_at, id`로 APPLY와 REVERT의 순서를 단언했고 초록이었다. 그 초록을 만든 것은 순서 보장이 아니라 **벽시계가 흘렀다는 우연**이었다 — 아무 데도 적혀 있지 않았고 쓴 사람도 몰랐다. `MutableClock`을 넣자 두 행의 `decided_at`이 같아지고 `UuidV7`의 시간 부분까지 같아져 **정렬 키 둘이 동시에 무너졌다.**

   **드러나는 방식이 이 얼굴의 특징이다**: 아무도 안 건드리면 영원히 안 드러나고, **무관한 변경이 그 우연을 걷어낼 때만** 나온다. 그때 빨강은 그 변경을 가리키므로 *"내 변경이 깼다"* 로 읽기 쉽다. 가르는 것은 반경이다 — 아래 face 4의 짝을 본다.

   **그리고 출력이 판정이기 이전에 부작용일 수 있다.** 위 항목들은 전부 *찍힌 것을 어떻게 읽는가*인데 이것은 한 단계 앞이다 — **라벨이 실행된다.** 실측: 구역 제목을 `` echo "=== does `git commit` after `reset --soft` …" `` 로 썼고, 큰따옴표 안의 백틱은 명령 치환이라 셸이 라벨을 만들려고 **`git commit`을 실제로 실행했다**(`reset`은 터미널 유틸 `tset`으로 풀려 `illegal option`을 냈다). 측정 자체는 무사했는데 **무사한 이유가 버리는 저장소였고 마침 index가 비어 있었다는 것**이다. 공유 checkout이면 **아무도 쓰지 않은 메시지로 그때 staged된 것이 커밋된다** — `f96cd6f`보다 나쁘다, 그건 적어도 누가 만든 커밋인지는 알 수 있었다. 셸 metacharacter가 든 라벨은 작은따옴표나 `printf '%s\n'`로 낸다.

   **한 도구의 두 출력 형식을 한 줄에 섞지 마라.** `git diff --stat`은 파일마다 **변경된 줄 합**을 주고 마지막에 **전체 요약**(`11 insertions(+), 10 deletions(-)`)을 붙이며, `--numstat`이 그 합을 파일별로 쪼갠다(`4+/4-`·`7+/6-`). 실측으로 대조표에 *"playbook 13 (11+/10-)"* 이라고 적었는데 괄호 안은 **두 파일 합계**였다 — 그 자리에 있으면 **그 파일의 분해로 읽힌다**. 상위 숫자는 전부 맞았고 틀린 것은 **어느 출력에서 온 값인지**였다. `11+10=21`이 `13`과 안 맞는다는 것을 옆 세션이 산수로 잡았다. **두 형식을 인용할 때는 어느 것에서 왔는지를 값에 붙인다** — 같은 명령의 출력이라는 것이 같은 대상을 잰다는 뜻은 아니다.

   **출력의 꼬리는 판정이 아니다.** `tail`로 본 마지막 몇 줄은 보통 **파생**이고 원인은 그 위에 있다. 실측: 260건 중 **한 건**이 실패하자 `raise`가 그 report 파일 전체를 읽기 불가로 만들어 suite가 `0건`이 됐고, 그 suite가 들고 있던 **모든 acceptance ID가 "missing"으로 번져** 150줄이 넘는 목록이 됐다. 원인 두 줄(`failures=1, expected 0`과 `zero executed testcases`)은 **목록 위에 정직하게 있었다.** 꼬리만 본 사람이 *"집계기가 부분 출력을 조용히 통과시킨다"* 로 읽었고, 그 보고로 **고칠 것이 없는 장치를 고치기 직전까지** 갔다. ①이 *"빨강도 판정이 아닐 수 있다"* 라면 이것은 그 안쪽이다: **빨강의 일부만 보는 것도 판정이 아니다.**

   **그리고 이미 의심할 이유를 가진 빨강도 판정이 아니다.** 도구의 빨강과 **내가 직접 읽은 코드**가 어긋나면 **그 어긋남 자체가 먼저 해소돼야 할 질문**이지 둘 중 하나를 등급으로 올릴 근거가 아니다. 실측: 정적 검사가 어떤 `UPDATE`를 *"행을 지목하지 않는다"* 로 잡았는데, 보고한 사람은 그 문장에서 **`WHERE id = ?`를 직접 봤고** 그 검사에 거짓 양성 전력이 셋 기록돼 있다는 것까지 적어두고서, 요약에서 **최고 등급(*"게이트가 실제로 빨갰다"*)** 으로 올려 **남의 미커밋 작업을 결함으로 지목했다.** 실제로는 연결된 SQL 조각 사이의 **주석이 문장 끝을 가린** 거짓 양성이었다. **검사에 거짓 양성 전력이 기록돼 있으면 기본값은 *"도구가 옳다"* 가 아니다.** 기존 ①의 항목들이 *"초록을 읽는 법"* 에 치우쳐 있었다면 이 둘은 **빨강을 읽는 법**이다.

   **그리고 *"확인했다"* 를 쓸 때는 *무엇을* 확인했는지를 같이 쓴다. 이 부류는 문장 자체가 참이라 검토자에게 단서가 없다.** 하루에 세 건이 났고 셋 다 다르게 어긋났다 — **주체**: 시계를 25시간 밀기 전에 session TTL(P30D)을 재고 *"시간 제약을 쟀다"* 로 보고했는데, 그 요청에는 시간에 묶인 자격증명이 둘이었고 `APP_CSRF_TOKEN_TTL`은 **PT2H**였다. **범위**: *"AWS 25개 재확인"* 이라는 라벨로 찍은 숫자가 **AWS로 필터링하지 않은 전체 dirty 수**였다. **존재 vs 작동**: push 가드를 원장에 정본으로 적고 *"가드를 만들었다"* 를 *"가드가 잡는다"* 로 적었는데, 발화시켜 보니 23개 중 12개를 통과시키고 있었다.

   **셋 다 *"확인했다"* 가 참이다.** 그래서 고치는 방법은 검토가 아니라 **쓰는 자리에서 잡게 하는 것**이다 — *"session 수명을 쟀다"*·*"필터 안 건 전체 수다"*·*"패턴을 적었다"* 라고 적게 만들면 **적는 사람이 거기서 자기 오류를 본다.** `provenBy` 칸이 작동하는 방식과 같다.

   **거절을 단언할 때는 status와 code를 둘 다 적는다.** 위 CSRF 건을 드러낸 것이 그 형태였다: `code`만 봤으면 *"REVERT_WINDOW_EXPIRED가 아니다"* 까지였는데 `status`가 403이라 **거절한 층**이 바로 나왔다. 하나는 *무엇이 거절됐나*, 다른 하나는 *누가 거절했나*를 말한다. `BA-070-T1`의 *"거절이면서도 caller가 묻던 것에 답한다"* 와는 다른 축이다 — 그건 *무엇을 노출하나*이고 이건 *어디서 죽었나*다.

   **같은 가족의 마지막 얼굴: 사슬 전체가 측정으로 서 있는데 그것이 *비대칭을 설명하지 않는* 경우.** 앞의 셋이 *"확인한 것이 주장과 다르다"* 라면 이것은 **확인한 것이 전부 참인데 원인이 아니다**. 실측(`#240`): E2E 실패의 원인으로 1ms 경합이 제시됐고 기제 자체는 측정으로 섰는데, **그 코드가 통과하는 브랜치에도 똑같이 있었다** — `apps/web/src/app/AppShell.tsx`가 `origin/main`과 `origin/frontend`에서 바이트 동일임을 확인했다(경합 자체는 재현하지 않았다). 한쪽만 빨간 이유를 설명하지 못하면 그것은 기제이지 원인이 아니다.

   **검증 가능한 부분이 전부 참이라 검토자에게 단서가 없다는 점이 위 셋과 같고**, 그래서 처방도 같은 자리에 둔다: 설명을 보낼 때 **그것이 설명하지 *않는* 것을 같이 적는다.** *"선다 / 안 선다"* 두 칸을 나란히 쓰면 쓰는 사람이 빈 칸을 본다.

   **옮기거나 만드는 단계는 그 다음 단계가 숫자를 내기 전에 스스로를 단언해야 한다.** 한 세션이 격리 worktree로 파일 열 개를 옮기고 전체 검증을 돌렸는데, **파일이 하나도 옮겨지지 않은 채로** 검증이 끝까지 돌았다. 원인은 `for f in $F`였다 — **zsh는 따옴표 없는 변수를 공백으로 분할하지 않는다**(bash와 다르다). 열 경로가 한 문자열이 되어 `cp`가 통째로 실패했다. **위험한 것은 결과가 이상하지 않았다는 것이다**: `test 421 failures=0`이 찍혔고 그것은 **진짜 수이고 진짜 초록**이라 *"내 변경이 아무것도 안 더한다"* 로 읽힌다. 들통난 것은 `scripts/tests`가 266이 아니라 **251**이었던 것과 `parity exit=2`(파일 없음) 둘뿐이었다. 그래서 복사·생성·동기화 뒤에는 **개수나 존재를 단언하고 아니면 멈춘다** — 검증이 그럴듯한 숫자를 내기 **전에** 앞에서 막는 형태다.

   **그날 이 부류가 다섯 번 났고 다섯 번 다 *재는 장치* 쪽이었다**: 잘린 `head -3`(placeholder가 없다고 읽을 뻔했다), 빈 `PIPESTATUS`(zsh에 없는 bash 배열이라 찍힌 `OK`를 exit code로 읽을 뻔했다), zsh 따옴표(`bad math expression`으로 여덟 줄이 전부 *"선언 없음"* 으로 찍혔다), 위 미분할, 그리고 다섯째. **넷은 출력이 이상했지만 다섯째는 정상보다 더 정상처럼 보였다.**

   **그리고 같은 zsh 분할이 *되돌리는* 명령에서 나면 결과가 다르다.** 위 다섯은 전부 **재는 장치**라 틀린 숫자가 나오는데, `git add $FILES`는 `fatal: pathspec 'a.java b.java c.java' did not match any files`로 죽고 **`set -e`면 재구성이 중간에서 멈춘다** — 반쯤 재구성된 트리가 남고, 그 상태는 규칙 10이 끝에 요구하는 두 카운트를 **셀 지점에 도달하지도 못한다.** 되돌리는 절차의 명령에는 인용을 빼지 않는다.

   **그리고 다섯째는 앞의 넷과 고치는 방법이 다르다.** `grep -c 'APP_COOKIE_SECURE must be'`가 `0`을 냈고 *"내 커밋이 사라졌다"* 로 읽힐 뻔했는데, 그 문자열은 **어떤 정상 상태에서도 소스에 없다** — 이름은 파라미터로 가고 메시지는 런타임에 조립된다. 그러니 그 `0`은 *"없다"* 가 아니라 **"이 방법으로는 있을 수 없다"** 다. 규칙 6의 *"찾아본 곳에 없는 것을 없다고 말했다"* 는 **범위**의 문제라 더 넓게 찾으면 나오지만, 이것은 **형태**의 문제라 아무리 넓게 찾아도 안 나온다. **부재를 결과로 쓰기 전에 그것이 그 형태로 존재할 수 있는지를 먼저 확인하고, 범위가 좁았던 것과 형태가 애초에 아닌 것을 다르게 고친다.**

   ② **발화하는가** — 그 검사가 막으려는 것을 **실제로 만들어** 빨개지는지 본다. 가드를 지우거나, 값을 거부 대상으로 바꾸거나, 생성물을 어긋나게 한다. **되돌린 뒤 `git status`로 확인한다.**

   ②가 없으면 **장치가 있다는 믿음만 생긴다.** 하루에 *"발화할 수 없는 단언"* 다섯 건이 나왔고 **넷이 검사를 쓰는 순간에 생겼다** — 없어서 못 잡은 것이 아니라 **잡으려고 쓴 것이 잡지 못하는 모양**이 됐다:

   - `jsonPath("$.ownerId").doesNotExist()` — 그 record에 `ownerId` component가 **없어서** 어떤 회귀에서도 실패할 수 없다
   - `cover_media_asset_id` column 개수 검사 — 그 이름의 column이 **생길 리 없었다**(V021이 만든 이름은 `cover_asset_id`)
   - handler의 *"trip이 사라졌다"* 분기 — cascade라 **관측 불가능**
   - hours query의 넷째 필터 — trigger가 이미 막아 **발화 불가**
   - `assertRegex(version, r'\d+\.\d+\.\d+')` — **부분 일치**라 `^7.13.0`이 통과한다. 거부하려던 값에 통과했다

   마지막 것은 *"발화할 수 없는 단언"* 을 지우는 규칙을 **쓰면서** 났다. 자기 검사에도 ②를 한다.

   **그리고 가드·필터·정규식처럼 *통과시키는 것이 일*인 장치는 틀려도 초록이다.** 위 다섯은 *"단언이 발화하지 않는다"* 였고 이것은 한 등급 아래다 — 잘못 읽은 코드는 다음 단계의 컴파일이나 test가 잡을 수 있지만, **막아야 할 것을 통과시키는 가드는 아무도 안 잡는다.** 실측: push 가드의 `^(scripts/aws/|…)$`에서 `$`가 교체군 전체에 걸려 `scripts/aws/`가 **정확히 그 문자열일 때만** 매칭됐고, 오너 지시 대상 23개 중 12개(실행 script 전부)를 통과시키고 있었다. 원장에 정본으로 적힌 장치였다.

   **위험한 것은 그것이 *자기가 방금 만든* 장치라는 점이다.** *"내가 만들었으니 안다"* 가 가장 강한 자리인데 **그 믿음의 대가가 가장 조용한 자리**이기도 하다. 그래서 가드는 ②를 **양방향으로** 한다 — 잡아야 할 것과 잡으면 안 될 것을 둘 다 먹이고 개수를 센다(위 건은 `23/23`, 거짓 양성 `0`).

   **거울상은 시끄럽게 틀리는 가드이고, 그쪽이 싸다.** 같은 가드가 나중에 배치를 막았는데 거짓 양성이었다 — 보호 대상은 **오너의 hunk**인데 정규식이 **파일 이름**을 걸었고, 그 파일에 든 것은 무관한 문단이었다. 조용한 오류는 세 번의 push를 그냥 통과시켰고 시끄러운 오류는 5분을 멈추고 **실제 불변식**을 찾게 했다 — 그런데 **그 자리에 처음 적은 불변식도 틀렸다.** `git diff -- <파일> | grep -c '^[+-][^+-]'`의 `[^+-]`는 `+++`/`---` 헤더를 거르려던 것인데 **내용이 `-`로 시작하는 줄까지 같이 버린다.** markdown bullet은 diff에서 `+- …`·`-- …`가 되므로 전부 탈락한다. 실측: 변경 13줄 중 **5줄**(추가된 bullet 셋 + 제거된 둘)을 못 봤고, **하필 그 5줄이 지키려던 오너 hunk의 내용**이었다. 그 수가 `8`로 나온 것은 **옆 파일의 참값과 우연히 같아서** 두 오류가 서로를 가렸고 *"양쪽 8, 일관된다"* 로 읽혔다 — 세 세션이 같은 숫자를 보고 서로 확인했다고 믿었다. **그리고 변조 시험이 과소계수보다 강한 것을 말한다**: 보호 대상 bullet 한 줄을 통째로 다른 문장으로 바꿔도 그 수는 **8 그대로**다. 전체 삭제는 잡고 **내용 변경은 못 잡는다** — 그 hunk는 거의 전부 bullet이므로 사실상 무효다. **그래서 수를 세지 마라.** `--numstat`은 지금 맞지만 그것도 수이고 수는 우연히 맞을 수 있다. **그래서 이 자리에 명령을 적지 않는다.** 이 문단은 하루에 **다섯 번** 명령을 처방했고 다섯 번 다 틀렸는데 **매번 다른 자리**였다 — 둘째 문자(`[^+-]`가 bullet을 실명), 세는 것 자체(`--numstat`이 내용 변조에 무반응), 입력 범위(`git diff | shasum`에 `index`·`@@`가 섞여 남의 커밋마다 거짓 양성), 인자 전달(`P="a b"; git diff -- $P`를 zsh가 안 쪼개 **빈 diff의 해시를 앞뒤로 비교하고 "통과"를 찍었다**), 그리고 필터(`sed`가 내용이 `--- a/`로 시작하는 제거 줄을 지우고, **0줄의 해시는 빈 문자열의 상수라 *"무사"* 와 *"아무것도 못 읽었다"* 가 같은 값이 된다**). 고칠 때마다 정본을 고쳐야 했고 그때마다 게이트가 처음부터 다시 돌았다.

   **규칙은 *"보호 단위가 무엇인지 먼저 적는다"* 이고 그 셋은 전부 한 구현이다.** 구현을 정본에 적으면 구현이 틀릴 때마다 정본이 틀린다 — *"고칠 수 없는 파일에 규칙을 적지 마라"* 의 사촌이고, 여기는 고칠 수 있었기에 네 번 고쳤다. **오너 hunk가 그대로인지는 줄을 읽어 확인하고 자동화는 보조로 둔다.** 실제로 세 숫자(`8`·`11`·`13`)가 엇갈렸을 때 판정을 낸 유일한 방법이 **줄을 하나씩 읽은 것**이었고, 그것만 매번 옳았다. 자동화를 쓰면 **비공허 단언을 같이 건다** — 읽은 줄 수가 0이면 멈춘다. 그것이 없으면 도구가 죽은 것과 대상이 무사한 것을 구별하지 못한다. **그리고 그 단언은 도구 자신이 내면 안 된다** — 해시도 줄 수도 같은 파서가 내면 파서가 깨질 때 **둘이 같이 움직여** 자기와 일관된 채로 조용해진다(실측: 필터가 전부 지운 경우 *"0줄, 해시 `e3b0c44…`"* 가 나오는데 그 값은 빈 문자열의 SHA-256이라 *"무사"* 와 구별되지 않는다). 숫자는 **다른 생산자**에서 받는다(`git diff --numstat`처럼 도구 자신의 별개 경로). 이것이 위 `94ead37`의 구분과 같다 — **판정이 자기 control에 얹히면 control이 바뀔 때 판정이 조용히 사라진다.** 가드를 쓸 때는 **무엇이 보호 단위인지**를 먼저 적는다 — 파일·경로·hunk는 다른 것이고, 규칙 6의 *"선언은 소유가 아니다"* 가 사람에 대해 적은 것이 정규식에서도 그대로 난다.

   **②의 결과도 판정이 아닐 수 있다.** 변이를 걸고 `red: NOTHING`이 나오면 *"가드가 잡는다"* 로 읽게 되는데, 그것은 **세 가지와 양립한다**: 가드가 정말 잡거나, **변이가 적용되지 않았거나**(치환 문자열이 실제 코드와 안 맞았다 — 줄바꿈된 signature를 가정했는데 한 줄이었다), **이번 실행이 아예 없었거나**(빌드가 실패해 report가 안 생겼고 직전 실행의 XML을 읽었다). 세 번 다 실제로 일어났다. 그래서 변이 검증은 **`git diff`로 적용을 먼저 보고, report를 지우고 시작한다** — **없는 report와 실패 0건은 다르다.** 도구가 `NOTHING`이 아니라 `NO REPORT`라고 말하게 만드는 것이 그 구분을 사람에게 맡기지 않는 방법이다. **그리고 반대쪽도 있다: 변이가 너무 많이 잡으면 그것도 판정이 아니다.** 한 절을 겨눈 변이가 여섯 절을 전부 빨갛게 하면 보통 **동작을 바꾼 것이 아니라 코드를 못 돌게 만든 것**이다 — `UPDATE`를 `DELETE`로 바꾸면서 첫 parameter가 갈 곳을 잃어 pass 전체가 죽은 사례가 있었고, 그것을 *"이 절이 load-bearing이다"* 로 읽을 뻔했다. **폭발 반경 자체가 신호다.** 그리고 같은 이유로 **DB 제약을 겨눈 변이는 그 절의 증거가 아니다** — `EXACT`를 쓰게 만들면 CHECK가 insert를 거절해 여섯이 다 죽는데, 그것은 *"가드가 있다"* 를 말하지 *"이 절이 그 가드를 부른다"* 를 말하지 않는다. **table이 둘째 줄이고 script가 첫째 줄**이다.

   **`red: NOTHING`의 얼굴을 세어 두면 여섯이다. 전부 *"판정이 아니다"* 쪽이다.**

   1. 변이가 **적용되지 않았다** — 치환 문자열이 실제 코드와 안 맞았거나, **남이 그 파일을 옮겼다**. 후자가 공유 checkout의 판이다: `JdbcCatalogPlaceQuery`에 mapper가 셋인데 `searchPlaces`가 쓰는 것은 `searchHit`이고, 마지막으로 읽은 뒤 다른 카드가 파일을 바꿔서 두 번 다 엉뚱한 mapper를 고쳤다. **변이 대상은 매번 `grep`으로 다시 찾는다 — 위치는 기억할 수 있는 것이 아니다.**

      **그리고 옮기는 것이 남일 필요가 없다. 자기 편집이 자기 줄 번호를 민다.** 남이 옮긴 것은 *"공유 checkout이니까"* 로 경계하는데 자기 편집은 **방금 자기가 했으니 안다고 믿고**, 그 믿음이 정확히 틀리는 자리다. 실측: `OptimizationService`에 219줄을 더한 뒤 `sed -n '455,500p'`로 `get(...)`을 읽었는데 그 범위는 다른 메서드 안이었다. 같은 형태가 상수에도 있다 — 몇 교환 전에 잰 `HEAD` SHA를 push 가드에 타이핑했고 그 사이 남이 커밋했다(고치는 방법은 단언을 푸는 것이 아니라 **worktree가 실제로 뜬 base 값에 묶는 것**이었다).

      **이것은 변이에만 적용되지 않고, 읽기 쪽 결과가 더 나쁘다.** 변이는 `red: NOTHING`으로 조용히 실패하는데 **잘못 읽은 선례는 설계의 근거가 되어 그 위에 코드가 쌓인다** — 위 사례에서 읽으려던 것은 *"읽기 경로가 capability를 묻는가"* 였고, 틀리게 읽었으면 route 하나가 **계약이 선언하지 않은 403**을 내고 있었을 것이다. **위치로 읽지 말고 패턴으로 읽는다**(`awk '/^    public X foo\(/,/^    }$/'`).
   2. **이번 실행이 아예 없었다** — 빌드가 실패해 report가 안 생겼고 직전 XML을 읽었다. `NOTHING`이 아니라 `NO REPORT`라고 말하게 만든다.
   3. **pipe가 exit code를 먹었다** — 위 ①의 배선판.
   4. **변이가 코드를 못 돌게 만들었다** — 폭발 반경 자체가 신호다. **반대 방향도 신호다**: 반경이 넓은데 **코드는 멀쩡하면** 동작을 바꾼 것이 아니라 **여러 절이 공유하던 전제를 걷어낸 것**이고, 그 전제가 어디에도 적혀 있지 않았다면 그것이 발견이다(위 ①의 *초록의 셋째 얼굴*). 실측: 시계를 고정하는 한 줄이 두 절을 같이 빨갛게 했고 원인은 회귀가 아니라 **그 둘이 벽시계에 기대고 있었다**는 사실이었다.
   5. **지운 가드가 일하는 가드가 아니었다.** 같은 경계가 세 곳에 선언돼 있으면(`AddTripItemCommand`·`UpdateTripItemCommand`·`TripItem` 생성자의 duration 상한) **어느 하나도 단독으로 필요하지 않고**, 한 경로의 **둘을 같이** 지워야 빨개진다. 변이는 적용됐고 실행도 됐는데 **일하지 않는 사본**에 걸린 것이다. 증상이 없는 동안에도 값이 하나 새고 있다 — `TripItem` 생성자의 message가 `seedItems[].durationMinutes`라 앞의 둘 중 하나가 사라지는 날 **그 operation에 존재하지 않는 field 경로**가 FE에 간다.

      **그리고 그 다섯째에는 변종이 있다고 적었는데, 그 예가 틀렸다 — 측정으로 뒤집혔다.** `BA-052-T1`(*"한 run은 최초 결정 하나만 반영한다"*)을 application의 `status != READY`와 DB의 partial unique index가 둘 다 지키고, 각각 끄면 `red=0`·둘 다 꺼야 `red=1`이었다. 이 문단은 거기서 *"`IdempotencyGuard.guarded`가 `owners.lockAlive`를 잡으므로 같은 owner의 두 요청은 동시에 뜰 수 없고, index는 HTTP 표면에서 영원히 중재하지 않는다"* 고 결론냈다. **틀렸다.** `decide()`는 run을 guard **진입 전에** 읽고 그 snapshot으로 상태를 검사하므로, 두 요청이 둘 다 `READY`를 읽은 채 guard 앞에 줄을 선다. 실측(`a042d33`, `OptimizeDecisionIT`): *"BA-052-T12 an APPLY and a KEEP sent at once: the index keeps one, the other rolls back"* — 6회 중 KEEP·APPLY가 3회씩 이겼고, 진 APPLY는 item을 **실제로 옮긴 뒤** index 거절로 rollback됐다 — 그리고 *"BA-052-T13 two APPLYs sent at once: the trip version keeps one, the other is refused"* (APPLY/APPLY의 패자를 막는 것은 index가 아니라 trip version 재조회다). 변이: status 검사를 끄면 `red=2`(순차 T1 둘), `insertIfFirst`를 무시하면 `red=1`(T12만). revert도 같은 모양이다 — *"BA-053-T7 two reverts of one APPLY sent at once: the trip version keeps one, the other is refused"* 는 V033 index가 HTTP에서 중재하지 않는다는 결론은 옛 문단과 같았지만, 이유는 owner 잠금이 아니라 두 요청이 모두 APPLY를 읽은 뒤 진 쪽을 transaction 안의 trip version 재조회(`409 TRIP_CHANGED`)가 V033보다 먼저 막기 때문이었다.

      **이 틀린 문단이 실제로 한 일**: 조율자가 이것을 인용해 `BA-052-T1`의 "동시" 절을 *"HTTP로 도달 불가이니 쪼개라"* 고 지시했고, 작업자의 독립 검토가 코드로 반박한 뒤 경쟁 test로 뒤집었다. 재현 장치도 한 번 틀렸다 — *"owners 행을 잠가 두 요청을 세운다"* 는 CSRF 재검증이 그 행을 먼저 잡았다 놓아서 두 요청이 run을 읽기 **전에** 직렬화됐고, 실제로 세운 것은 `optimization_runs` 잠금이었다.

      **남는 규칙**: *"이 사본은 이 경로에서 도달 불가"* 는 잠금 순서를 **읽어서** 주장하지 않고, 도달을 **시도하는** test로 주장한다. 읽은 순서는 한 줄(여기서는 run 조회가 guard 앞에 있다는 것)을 놓치면 통째로 틀리고, 그 틀린 명제는 정본에 적히는 순간 근거로 인용된다. 공개 경로의 test가 *"어느 줄이 막았는가"* 가 아니라 **결과**를 단언해야 한다는 것은 그대로다 — 한 줄을 지목해 적으면 다음 사람이 그 줄을 지우고 초록을 본 뒤 **다른 쪽을 죽은 코드로 읽는다.**

      **그 자리에서 변이 자체도 한 번 틀렸다.** `if (!store.insert(x))`를 `if (false && !store.insert(x))`로 바꿨는데 Java 단락 평가로 **insert가 아예 호출되지 않았고**, 뒤따르는 조회가 404가 되어 무관한 case까지 빨개졌다(반경 2). **거절을 껐다고 생각했는데 쓰기를 껐다.** 반경이 1을 넘으면 먼저 *"내가 끈 것이 정말 그것인가"* 를 본다.
   6. **로컬에서는 원리적으로 발화할 수 없다** — 규칙 6의 아홉째. 재현될 수 없는 곳에서 변이를 걸고 초록을 본 뒤 *"내 가설이 틀렸다"* 로 읽는 자리다.

   **그리고 다른 축이 하나 있다: 변이가 판정을 냈는데 그 판정이 반올림인 경우.** 위 여섯이 *"판정이 아니다"* 라면 이것은 *"판정인데 우연이다"* 다. `BA-070-T3`의 쿼리 예산을 **결함의 비용과 같은 비율로** 잡았더니 — N+1은 행당 statement 1개이고 상한이 *"행 수 미만"* 이었다 — **결함이 경계 위에 정확히 앉았고** 변이가 1개 안쪽으로 들어와 초록이 됐다. **상한은 결함의 비율에서 유도하되 한 자리 내려 잡는다**(행당 0.5개). 같은 유도인데 경계가 결함에서 떨어진다. 같은 카드에서 앞선 세 번도 측정이 틀렸다: counter가 fixture까지 셌고(데이터와 함께 비용이 3배가 됐는데 재려던 요청은 그대로였다 — **test를 재고 있었다**), 상한을 등호로 잡았고(아무도 안 잰 budget이 된다), 그리고 위 ①이었다. **부하를 재는 검사는 네 번 다시 재고 나서야 무언가를 재기 시작했다.**

   **거울상이 하나 더 있다: 장치가 없는데 주석이 있다고 적는 것.** `OptimizationFailureCode`의 javadoc이 *"두 선언은 `OptimizationFailureVocabularyIT`가 서로 대조한다"* 고 적고 있었는데 **그 class가 없었다** — 두 slice 전에 쓰였고 그 이름이 나오는 곳은 그 문장 하나뿐이라 아무도 알 수 없었다. **①②가 잡는 것보다 싸게 생긴다**(주석 한 줄이면 된다) 는데 남기는 것은 같다: *장치가 있다는 믿음*. `check_test_reports.py`가 `provenBy`에 대해 지목한 이름이 실재하는지 보는데 **산문에 대해서는 아무도 안 보고 있었다.** `scripts/check_cited_tests.py`가 그 비대칭을 닫는다 — 주석이 `*Test`·`*IT` 모양의 이름을 인용하면 그 class가 실재하는지 대조한다. 추적되는 Markdown도 같은 규칙으로 읽는다([#251](https://github.com/yutakdv/Nullnull/issues/251)) — test class를 가장 많이 인용하는 곳이 이 파일의 CI 표인데 처음에는 `*.java`만 읽었다. inline code만이 아니라 평문과 fenced block까지 읽는다: 잰 결과 평문 언급 30곳 중 29곳이 실제 인용이었다. `docs/superpowers/plans/`만 뺀다 — 구현 계획서는 **앞으로 만들** class를 이름 부르는 문서이고, 첫 실행에서 잡힌 13개 이름(52곳)이 전부 ADR-0006 이전 Java 계획서의 그런 이름이었다. 살아 있는 문서의 실제 오류는 0이었다. 첫 실행이 **두 건을 더 찾았고 둘 다 접미사 뒤바뀜이었다**(`...Test`라 적혔는데 실제는 `...IT`, 그리고 그 반대). 그쪽이 더 나쁘다 — **검사는 실재하므로** 인용된 이름을 grep한 사람이 아무것도 못 찾고 *"검증하는 것이 없다"* 로 결론내린다.

   **그런데 이 검사가 원리적으로 못 닿는 부류가 있다: 이름이 아니라 *범주*를 가리키는 포인터.** 실측: `confirm-dialog.test.tsx`가 focus trap을 단언하지 않는 이유로 *"the trap itself is the browser's and is **covered by the Playwright suite** rather than asserted here"* 를 적고 있었는데, 그 Playwright 단언이 **modality를 재고 있지 않았다** — `showModal()`을 `show()`로 바꿔도 반경 0이었다. 포인터가 빈 곳을 가리키고 있었고, 그 test를 고친 뒤에야 그 문장이 참이 됐다.

   **이름을 가리키는 포인터보다 조용히 낡는다.** 이름은 사라지면 grep이 0건을 내고 위 검사가 잡는다. 범주(*"Playwright suite"*, *"unit test"*, *"the gate"*)는 **계속 존재하면서 내용만 비어간다** — 검사에 걸릴 문자열이 애초에 없다. 위 *"주석이 담은 명제는 아무도 검사하지 않는다"* 의 하위 사례이고, 그중 자동화가 닿을 수 없는 쪽이다. **다른 곳이 덮는다고 적을 때는 범주가 아니라 그 test의 이름을 적는다** — 그러면 적어도 이름의 실재는 기계가 본다.

   **그리고 반대 방향이 하나 더 있다: 가드가 정확히 발화하는데 그 발화의 근거가 코드와 다른 경우.** 위 항목들이 *결함을 못 잡는* 장치라면 이것은 **수리를 막는** 장치다. 실측(`#170`): `ProblemResponseCoverageTest`가 `csrfToken 선언 ⟺ 403 선언`을 **양방향 등호**로 걸고 있었고 계약은 그 아래서 초록이었다. 그런데 403의 생산자는 둘이다 — CSRF token 검사와, `SessionHttpConfiguration.preHandle`이 **security를 읽기 전에** 모든 비안전 메서드에 거는 same-origin 검사. 후자 때문에 `security: []`인 `createDemoSession`도 403을 내고 `SessionSafetyIT`가 그것을 이미 단언하고 있었다. 그래서 **실제로 나는 403을 계약에 적는 순간 그 가드가 거절했다.**

   **이 부류가 가장 설득력 있게 틀린다**: 빨간색이 뜨면 사람은 자기 수정을 의심하지 가드를 의심하지 않는다. 게다가 여기서는 의심할 단서조차 없다 — 가드가 방금까지 초록이었고 정확히 내 변경에만 반응한다. 그래서 **가드가 수리를 거절하면 수리를 좁히기 전에 가드의 *유도*를 코드와 대조한다.**

   **그 앞 단계가 있다: 거절하는 가드를 애초에 만들지 않는 것.** 선례를 옮길 때 **형태를 옮기면 틀리고 그 선례가 *무엇을 재는지*를 옮겨야 맞는다.** 실측: `traps focus`를 고치라는 지시에 조율자가 `keyboard-flow.spec.ts`의 tab bar 순회(`keyboard.press('Tab')` 루프)를 선례로 지목했는데, 지시받은 쪽이 두 전략을 같은 probe로 나란히 재서 **배제했다** — 정상 modal sheet에서도 Tab 4회째에 초점이 `<dialog>` 밖으로 나간다(브라우저가 문서를 한 번 거쳐 돌아온다: `IN:Day 2, IN:Day 3, IN:Day 4, OUT:서울 가을 여행, IN:Cancel, IN:Day 2`). 그래서 *"모든 Tab이 안에 머문다"* 는 **정상 코드에서 빨개지는 단언**이고, 형태를 그대로 옮겼으면 멀쩡한 코드를 거부하는 test가 됐다.

   **두 자리의 성질이 반대였다**: tab bar는 *"네 탭에 순서대로 도달하는가"* 라 순회 중 다른 곳을 지나도 무관하고, trap은 *"밖으로 못 나가는가"* 다. 쓴 것은 sheet 밖 버튼에 `.focus()`를 걸고 초점이 따라가는지이고(modality가 정확히 그것을 금지한다 — top layer 밖은 inert라 호출이 no-op), 양방향으로 쟀다: 정상 6/6 통과, `showModal()`→`show()` 변이에 반경 1.

   **규칙은 둘이다.** 새 자리의 **정상 코드에서 먼저 돌려 본다** — 가드가 정상을 거부하면 그건 수리가 아니라 새 결함이다. 그리고 **조율자가 지목한 선례도 인용된 쪽이 다시 확인한다**(규칙 6의 그 문장이 여기서는 선례에 적용된다). 지시를 받은 쪽이 측정으로 배제한 것이 옳았고, 배제하지 않았으면 그 test가 다음 사람에게 *"정상 코드가 깨졌다"* 로 보였을 것이다.

   **그 짝이 규칙 7②의 거울이다: *"이 검사가 내 수리를 거절할 것이다"* 도 발화시켜 봐야 한다.** 7②가 *검사를 넣었다 ≠ 검사가 발화한다*라면 이것은 그 반대편이고, **더 비싸다** — 거절을 예측하는 쪽은 보통 **그 가드를 완화하자고 제안하는 쪽**이기 때문이다. 안 재고 넓혔는데 실제로는 다른 이유로 초록이었다면 **멀쩡한 가드를 근거 없이 느슨하게 만든 것**이고, 완화는 되돌리기 어려우며 다음 결함이 그 구멍으로 나간다. 그리고 넓힌 뒤에는 **양방향으로 다시 발화시킨다** — 실측으로 하나를 빼면 `red=1`, 안전 메서드에 하나를 더해도 `red=1`이었고, 그 둘이 있어야 *"약해지지 않았다"* 가 측정이 된다.

   **그 가드의 주석이 이 결함의 뿌리였고, 그 형태가 따로 적힐 값이 있다: 주석이 담은 *명제*는 아무도 검사하지 않는다.** 그 문장은 *"403 has exactly one producer"* 였고 **grep 한 번이 반증한다.** `check_cited_tests.py`가 닫은 것은 **이름**의 비대칭(인용한 class가 실재하는가)이고, **명제**에 대응하는 것은 없다 — 그런데 사람이 근거로 삼는 것은 명제다. 검사로 닫기 어려운 종류라 더 오래 산다. 쓰는 쪽에서 할 수 있는 것은 하나다: **셀 수 있는 사실을 적는 주석은 그것을 어떻게 셌는지 같이 적는다.**

   **그리고 발화할 수 없게 만드는 것이 test harness 자신일 수 있다.** 앞의 다섯은 전부 *대상*이 잘못 골라진 경우였는데, `BA-070-T2`를 쓰며 나온 셋은 **재는 도구가 잴 수 없는 것을 재고 있었다**:

   - **`MockMvc.param()`은 `getQueryString()`을 채우지 않는다.** parameter map만 채운다. 그래서 *"access log에 query string이 남지 않는다"* 를 검사한다면서 **query string이 있는 요청을 한 번도 보내지 않았고**, `include-query=true`로 켜도 초록이었다. 검사 대상이 아니라 **요청을 만드는 방식**이 절을 지웠다.
   - **`searchPlaces`는 POST이고 검색어가 body에 있다** — URL에 검색어가 가지 않게 하려고 그렇게 만든 것이다. 그것으로 *"URL이 로그에 남지 않는다"* 를 증명하려던 것이 애초에 틀렸다. **그 절을 가진 operation을 골라야 한다**(`listTrips`가 진짜 query parameter를 받는다).
   - **denylist가 응답 텍스트만 훑었다.** cursor는 자기 payload의 base64url이라, **서명 키를 payload에 넣은 codec은 매 페이지마다 키를 나눠주면서 검사를 통과한다.** base64 blob을 디코드해서 같이 훑어야 하고, **cursor가 실제로 발급됐는지도 단언해야 한다** — 장소가 둘이고 `limit=1`이 아니면 `nextCursor`가 null이라 그 경로는 아예 안 훑린다. 이것이 *"검사 범위가 조용히 줄어든 경우"* 의 정확한 모양이다.

   셋 다 **변이를 걸었는데 빨개지지 않아서** 나왔다. 고친 뒤에는 변이가 각각 자기 case만 잡는다 — *"어느 case가 빨개지는가"* 가 *"빨개지는가"* 보다 많은 것을 말한다.

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
