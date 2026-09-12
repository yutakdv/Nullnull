# HANDOFF — Backend/AI 독립 작업 A~D (BA-002 → BA-024)

**작성 2026-09-08 · branch `backend` · 다음 담당자가 다른 도구(Codex 등)일 수 있음을 전제로 씀**

이 문서는 "지금 멈춰도 다른 에이전트가 곧바로 이어받는다"를 목적으로 한다. 대화 맥락 없이 이 파일 + 아래 3개 정본만 읽고 재개할 수 있어야 한다. 어긋나면 이 문서가 아니라 정본을 믿는다.

## 0. 30초 재개 절차

```bash
cd /Users/yutak/Desktop/Nullnull
git status --short --untracked-files=all   # 깨끗해야 한다 (아래 §2)
cat .superpowers/sdd/2026-09-08-backend-a-to-d/progress.md | tail -120   # 판정 이력
cat .superpowers/sdd/2026-09-08-backend-a-to-d/plan.md                   # 전체 실행 계획
```

읽을 정본 3개:

| 무엇 | 어디 | 성격 |
| --- | --- | --- |
| 실행 계획(A~D 13개 slice) | `.superpowers/sdd/2026-09-08-backend-a-to-d/plan.md` | gitignored 로컬 사본. 원본은 `~/.claude/plans/a-d-cryptic-planet.md` |
| 판정·검증 이력 | `.superpowers/sdd/2026-09-08-backend-a-to-d/progress.md` | gitignored. slice별 지적/판정/변이 결과 |
| 카드 정본 | `docs/roles/BACKEND_AI_PLAYBOOK.md` + `docs/engineering/backend-plan.json` | **tracked. validator가 둘의 동기화를 검사한다** |

## 1. 이 작업이 무엇인가

Frontend 협업 없이 Backend/AI 혼자 닫을 수 있는 카드를 순서대로 구현한다. 인프라(E)는 범위 밖.

순서: ~~Slice 0(D)~~ → ~~A1 BA-002~~ → ~~A2 BA-005~~ → ~~A3 BA-003~~ → ~~A4 BA-004 Backend/AI CI~~ → ~~B1 BA-010~~ → ~~B2 BA-011~~ → ~~B3 BA-012~~ → ~~C1 BA-020~~ → **C2 BA-021(진행 중; T3 대기)** → **C3 BA-022(진행 중; 같은 T3 대기)** → ~~C4 BA-023~~ → **C5 BA-024(다음)**.

## 2. 현재 상태 (C4 BA-023 구현 후, PR #108 대기)

### C4(BA-023) — 혼잡 예보 · 2026-09-11

- `V011`이 immutable `snapshot_sets`/`crowd_snapshots`와 KTO 집중률 registry revision 2
  (`kto-tats-cnctr-rate-v4.1`, 공식 operation `tatsCnctrRatedList`)를 추가한다. raw body·요청 URL·key는
  column이 없고, 모든 `UPDATE`와 set-snapshot provenance 불일치를 trigger가 거부한다.
- 공개 `getPlaceCrowdForecast`는 저장된 검증 완료 snapshot set만 투영하고 provider를 부르지 않는다. 수집은
  operator/background 전용 `KtoCrowdForecastGateway`다. route는 C3 canonical resolver를 재사용하므로
  `NULLNULL_CATALOG_PUBLIC_ENABLED=false`에서 fail-closed다.
- **되돌리지 말 것:** 비교 결과를 저장하지 않는다(`crowd_comparisons` table 없음). 격리는 읽기 시점에
  `source_quality_incidents`로 계산한다 — snapshot이 immutable이라 저장된 row가 가질 수 없는 유일한 사실이다.
- **적대적 검토 결과:** 최초 구현의 `CrowdComparisonService`는 production 호출자가 없었고 그 test가
  `BA-023-T2` 증거로 등록돼 있었다. 삭제하고 실제 경로(`eligibility()`의 `PROVIDER_INCIDENT` 가드)로 교체했다.
  변이 실측: 가드 삭제 시 두 test RED, 복원 SHA `b93a468…` 일치 후 GREEN.
- **`ROUTE_UNAVAILABLE` → `SOURCE_UNAVAILABLE`:** fail-closed catalog/crowd gate가 최적화 run 실패용 code를
  쓰고 있었다(`경로를 확인하지 못했어요`). 커밋 `69805f6`에서 고쳤다. FE fixture 영향 있음 — #105/#106에 기록.
- **`FlywayMigrationIT` 주의:** migration을 추가할 때마다 직전 migration의 table을 `populateEveryTable`에
  넣어야 한다. V010의 trigger는 `search_path`로 부모 row를 찾으므로 upgrade schema를 경로에 올린
  `SET LOCAL` 안에서 채운다. V012를 추가하는 사람은 V011의 두 table을 같은 방식으로 채워야 한다.
- 검증: Temurin 21 `320/153/13/19` 0 fail/error/skip, `scripts/integration-test.sh` exit 0
  `integration_mode=full-docker`(AI 410, web 224, Playwright 36, npm audit clean, egress-denied).
- **미완료:** 실제 KTO `tatsCnctrRatedList` 호출 증거가 없다. `ktoForecastSmoke`는 `KTO_SERVICE_KEY`와
  `NULLNULL_KTO_FORECAST_SMOKE_APPROVED=true`가 필요하다. BA-021-T3와 함께 남아 있어 BA-023도 `verified`가
  아니고 #35를 닫지 않았다.
- **2026-09-11 실제 KTO 호출에서 드러난 차단 요인 (#109):** 승인 후 `actualKtoSmoke`를 돌렸더니
  `KtoForecastRequest`가 `areaCode is not a KTO area identifier`로 실패했다. C2 detail 호출 자체는 성공했다.
  실제 `detailCommon2` 응답은 `areacode`·`sigungucode`·`cat1/2/3`이 빈 문자열이고 값은 `lDongRegnCd`·
  `lDongSignguCd`·`lclsSystm1/2/3`에 있다. 현재 validator는 빈 쪽만 읽으므로 **실제 KTO place는 canonical
  ingest에서 전부 거부되고 forecast 수집도 시작되지 않는다.** fixture가 전부 legacy 필드 이름이라 네 suite가
  GREEN인 채로 이 단절을 덮고 있었다. `tatsCnctrRatedList`는 `areaCd`가 필수이고 법정동 `11/110`과 legacy
  `1/1` 모두 `totalCount 0`이라 파라미터 계약도 미해결이다. **추측으로 채우지 말고 공공데이터포털
  `TatsCnctrRateService` 공식 활용가이드로 고정할 것.** 코드 체계 변경은 `PlaceSummary.categoryCode`/
  `regionCode`의 의미를 바꾸므로 FE(#34)와 함께 정한다.

- **C3 후속 완료 (#109 절반 해소, PR #111 병합):** `V012`가 KTO_KOR_SERVICE_2를 revision 4
  (`kto-kor-service2-detailcommon2-v3`)로 올리고 validator가 `lclsSystm1`·`lDongRegnCd`·`lDongSignguCd`를
  읽는다. retired 식별자만 담긴 응답은 remap하지 않고 `SCHEMA_DRIFT`로 quarantine하고, 둘 다 없는 경우는
  분류 없는 장소라 drift가 아니다. fixture는 실측 응답 모양이다. **실제 KTO place가 canonical catalog에
  저장된다.**
- **FE 승인 대기 계약 (되돌리지 말 것):** `PlaceSummary`/`PlaceDetail`의 `sourceAttribution`·`categoryName`·
  `regionName`은 **`required`가 아니다.** 승인 전에 required로 올리면 FE의 `PlaceSummary` 리터럴
  (`candidates.test.ts`·`locks.test.ts`·`trip-view.test.ts`)이 TS2739로 깨진다. 서버는 항상 보낸다. FE가
  채택을 끝낸 뒤에만 required로 올린다(#34).
- **표시용이 아닌 필드 3개:** `PlaceSummary.categoryCode`·`regionCode`와 `CrowdMetric.label`. 계약 설명에
  명시돼 있다. 문구는 `state`+`provenance.metricDefinition`으로 만들고 credit은 `provenance.attribution`을
  그대로 쓴다(FE 결정, #105).
- **`FieldError.field`는 점 경로 전용**이고 슬래시를 쓰지 않는다. 빈 경로는 `"request"` 센티널이다(#38).

- **다음은 C5 BA-024**(검증된 관련 장소·추천 후보 검색). 다만 FE가 #34에 올린 출처 표시 요청 — `PlaceSummary`에
  source/attribution field가 없어 공모전 REQUIRED `CMP-ATT-001`을 못 채우는 문제 — 를 **BA-024보다 먼저**
  처리하겠다고 #34에 적었다(해당 FCR 번호는 FE가 PR #106에서 `FIGMA_CHANGE_REQUESTS.md` 표에 등록 중이므로 여기서
  중복 등록하지 않는다). 서버는 `place_external_refs` + `source_registry_revisions.canonical_contract`에 데이터를
  이미 갖고 있고 계약/투영만 없다. FE의 shape 승인 대기 중이다.

### C1 BA-020 이후 누적 상태

### B3(BA-012) — 삭제 receipt·worker·restore tombstone

- V006과 `DELETE /session`, `GET /deletion-requests/{id}`를 구현했다. 접수 transaction은 모든 owner session/CSRF revoke, owner deleted 표시, hash-only receipt, tombstone, deletion job을 함께 만든다. revoked cookie는 같은 route/key의 완료 projection만 24시간 재생하며 다른 접근은 401이다.
- 상태 token은 request ID와 정확한 expiry epoch를 HMAC-SHA256으로 묶고 DB에는 SHA-256 hash만 둔다. 7일 경계부터 410이며 token hash를 지운다. tombstone은 제안값 21일, revoked session은 30일이라 owner hard delete는 retained FK가 모두 사라질 때까지 기다린다.
- 실제 worker는 `JobContext.transactional`의 짧은 eraser 단위로 실행하고 partial failure를 재시도한다. `TombstoneReapplier`는 web lifecycle보다 먼저 동기 재삭제하며 하나라도 실패하면 startup을 열지 않는다. `REC-SEC-03`을 `gradle:integrationTest`에 등록했다.
- 변이 14종이 전부 RED였고 SHA 복원이 일치했다. 로컬 증거는 `.artifacts/ba-012/`; JUnit은 `apps/api/build/test-results/`다. BA-012는 `integration-ready`이며 다음은 C1 BA-020, V007이다.
- 최종 local과 full Docker Java는 280/127/13/19, 실패·오류·skip 0이다. AI 410, web unit 224, Playwright 36, client diff·audit·egress-denied가 통과했다. Docker 공유 DB에서 발견한 V006 FK fixture 회귀도 자식→owner 정리 순서로 수정한 뒤 전체 gate를 다시 통과했다.
- C1은 `V007__sources.sql`로 source registry/revision/quality incident/collector run/safe ingest ledger를 추가했다. revision hash는 collection controls 전체를 덮고, KTO place detail `P7D`·forecast `PT24H`, KTO development key `DEV_APPROVED`, 미신청 related/서울/Replay `DISABLED`, internal catalog rule `PROD_APPROVED`를 seed한다.
- provider kit은 exact host·redirect 거부·bounded executor/permit·retry/jitter/429·circuit·response byte limit을 공통화한다. source run 재사용 quota reservation, audit canary, drift/incident quarantine, provider 지연 중 readiness/owner 요청 격리를 Testcontainers와 stub provider로 검증했다. fresh local Java 네 suite와 full Docker gate(Java 288/133/13/19, AI 410, web 224, Playwright 36; fail/error/skip 0)가 통과했고 C1은 `integration-ready`다.
- C1 safety mutation 두 건도 실제 RED다: 다른 source collector run의 SQL predicate를 제거하면 `BA-020-T2`가, production host policy의 guard를 우회하면 `BA-020-T1`이 각각 실패한다. 두 구현을 복구한 focused test를 다시 통과시켰다.
- 다음 C2는 승인된 KTO operation의 실제 server-side gateway와 provenance/snapshot·실제 호출 evidence다. C1의 registry decision은 provider key나 실제 호출을 구현한 것이 아니다.

### C2(BA-021) — KTO `detailCommon2` gateway·normalized snapshot (진행 중)

- `V008__catalog_places.sql`은 `KTO_KOR_SERVICE_2`를 revision 2 (`kto-kor-service2-detailcommon2-v1`)로 올리고 `kto_place_snapshots`를 추가했다. snapshot은 source revision/collector run/content/type ID/title/category/area/address/좌표/hash/fetched/stale만 가진다. key·full URL/query·raw body·overview·image는 schema와 audit API에 없다.
- `KtoKorServiceClient`는 exact `https://apis.data.go.kr/B551011/KorService2/detailCommon2`만 쓴다. `firstImageYN=N`/`overviewYN=N`, matching content/type, envelope·range validation, P7D cache, same-request single-flight, quota/audit/transactional write를 구현했다. forecast와 related operation은 아직 callable하지 않다.
- `KtoDetailResponseValidatorTest`, `KtoKorServicePropertiesTest`, `KtoPlaceDetailGatewayIT`, `FlywayMigrationIT`로 T1/T2와 V007→V008 populated upgrade를 통과했다. PostgreSQL fixture에서 service-key canary와 raw-body marker가 snapshot/audit/run row에 없고, concurrent miss 1회/expiry refresh 1회를 확인했다.
- C2 mutation은 single-flight 제거 시 `BA-021-T2` RED, content/type identity check의 `||`→`&&` 약화 시 `BA-021-T1` RED를 실제 확인한 뒤 원본을 복구하고 focused test를 다시 GREEN으로 만들었다.
- final full Docker gate는 Java `293/136/13/19`, AI `410`, web `224`, Playwright `36`, generated client·npm audit·egress-denied까지 GREEN이다. 이 fixture gate는 actual KTO success를 대체하지 않는다.
- **남은 T3:** 이 runtime에는 `KTO_SERVICE_KEY`와 staging public provenance route가 없다. fixture 성공은 actual KTO evidence가 아니므로 BA-021은 `in-progress`이며, secret이 주입된 staging에서 actual success → collector audit → C3 public provenance를 확인하기 전에는 issue 종료·C3 시작·release claim을 하지 않는다.

**최신 main 수신:** PR #17/#21의 `26d5d90`을 backend에 통합했다. 이제 `apps/web`, 생성 client, `.nullnull-target-stack`이 존재한다. 아래 과거 A3/A4 기록의 “marker 부재로 full wrapper exit 1”은 현재에는 적용하지 않는다. 전체 wrapper `integration_mode=full-docker`, exit 0을 확인했다. A4 자체 커밋은 `552a539`, main 수신 merge commit은 `308ee35`다.


### B2(BA-011) — owner preference 구현

- `GET /me`와 `PATCH /me`를 구현했다. merge-patch는 absent 유지/activeTripId null 해제, unknown/type/빈 object 거부, KO/EN·timezone 검증, session owner lock 뒤 갱신을 강제한다. `ApiException.fieldErrors`를 Problem 응답에 연결했다.
- `TripLookup` port는 BA-030 전까지 production에서 항상 false다. test override로 owner/삭제 경계를 검증하지만 실제 trip table 구현이라고 쓰지 않는다. 반복 onboarding은 PostgreSQL `xmin`이 변하지 않는다.
- local·full Docker: **276 / 121 / 13 / 19**, 전부 0 fail/error/skip. AI 410, web 148, Playwright 6. docs/root unittest 89/Markdownlint/Redocly/AJV/client diff/audit/egress-denied 통과.
- 변이 **22종 최종 RED**, cp+SHA256 복원 일치. 저장 대입문 4종의 첫 시도는 넓은 치환으로 compile 실패했으므로 증거에서 제외했다. 정확한 대입문과 새 JUnit freshness로 재검증했다. `.artifacts/ba-011/mutations.json`과 local progress 참조.
- 실제 full Docker에서 기존 `RequestBodySwallowBoundIT`가 IOException만 예상해 실패했다. 원래 `HttpClient`는 응답을 먼저 받으면 업로드를 중단할 수 있다. raw TCP로 응답과 독립적인 업로드/후속 pipeline을 검사하도록 고쳤고 `max-swallow-size=-1` 변이는 전체 upload 완료로 RED다. 정상 2097152 값은 그대로다. **큰 body는 413을 먼저 받거나 응답 없는 transport 오류일 수 있다.** 기존의 “반드시 HTTP 응답 없음” 주장은 폐기한다.
- 공개 shape·migration 변화는 없다. 세부 문구와 generated client를 동기화했다. BA-011은 integration-ready; main merge·FE 검수는 별도다.
- 다음 **B3(BA-012), V006**. API 삭제 transaction·receipt HMAC·job·tombstone을 구현한다. session token 보존은 idle 30일, 여행 데이터는 사용자 삭제까지라는 privacy 표를 따르고 두 보존 정책을 섞지 않는다. expired non-revoked session hash 정리는 보강해야 하며 owner 자동 GC는 PM-017의 별도 결정이다.

### B1(BA-010) — 세션·CSRF 구현

- `V005__demo_sessions.sql`을 추가했다. V001~V004는 그대로다. session/CSRF bearer는 SecureRandom 32B base64url, DB에는 SHA-256만 저장한다.
- `SessionService`는 bootstrap 201/valid-cookie 200 수렴, first touch/idle sliding/absolute cap, LRU 5개, owner→session lock을 강제한다. HTTP interceptor는 operation annotation에 따라 cookie/Origin/CSRF를 검증한다. `getDemoReadiness`의 미강제 예외는 제거됐다.
- **`@NullnullOperation`은 A3 코드에 실제로 없었다.** B1이 새로 추가하고 모든 실제 controller의 route/security와 OpenAPI를 contract test로 맞췄다. 미매핑 404는 그대로다.
- `SessionBootstrap.expiresAt`와 `CsrfTokenResponse.expiresAt`는 **반환한 CSRF token의 만료**다. session TTL은 서버에서 별도로 검사한다. body shape는 그대로이고 generated client를 재생성했다.
- orphan owner/session은 15분에 정리 대상, 기본 sweep은 1분이다. 첫 비-bootstrap 요청을 기록한 session은 orphan cleanup에서 보존한다. revoked session은 30일, CSRF는 token 만료에 삭제한다. absolute P90D·CSRF PT2H·touch PT1M은 제안값이다.
- local·full Docker Java **276 / 114 / 11 / 19**, 모두 0 fail/error/skip. AI pytest 410, web 148, Playwright 5건, client diff·audit·egress-denied 통과. docs·root unittest 89·Markdownlint·Redocly·AJV 통과.
- 변이 **31종 최종 RED**, 모든 cp+SHA 복원 일치. 세션 lock 제거가 처음 살아남아 동시 revoke commit 뒤 발급 거부를 검사하도록 보강했고 재검증은 RED다. 기록 `.artifacts/ba-010/mutations.json`, 상세는 local progress.
- 실제 Docker에서 기존 job suite 5개가 `DELETE FROM owners` 전에 session fixture를 정리하지 않아 FK 오류가 났다. 테스트 setup의 자식→owner 정리 순서를 고쳤다. 운영 FK를 완화하지 않았다.
- Playwright는 `API_INTERNAL_BASE_URL`의 실제 API로 직접 호출한다. **현재 `apps/web/serve.mjs`에는 API proxy가 없다.** web 경유 시 HTML 200을 받아 테스트가 실패했고 직접 API 연결로 수정했다. HTTP Compose에서 Secure cookie를 명시 전송하는 transport 검사이며, browser Secure cookie 수락·아직 없는 session UI 검증은 아니다. FE runtime proxy와 cookie 유실 안내는 FE 인계 사항이다.
- BA-010은 `integration-ready`; main merge·FE 재현 전 이슈 완료/`verified`로 쓰지 않는다. 새 PR #100은 필수 CI 통과지만 확인 시 승인 기록이 없어 미병합이다.
- 다음은 **B2(BA-011)**. production `/me`를 추가하고 현재 test-support owner 분리 검사를 실제 route에 연결한다. B3는 revoked cookie의 24h replay context와 30d session retention을 재사용한다. **B2에서 재확인한 보존 경계:** privacy의 30일은 session token이고 여행 데이터는 사용자 삭제까지다. idle 만료를 이유로 사업 데이터를 자동 삭제한다고 해석하지 않는다. B3에서 만료된 non-revoked session hash 정리를 보강하고, PM-017의 owner GC 정책은 별도 결정으로 유지한다.

### main 수신 후 검증 기준선

- 실제 full Docker: Java `test 274 / integrationTest 101 / openapiContractTest 9 / recommendationTest 19`, 전부 0 fail/error/skip. main의 `ManifestTestPathParityTest` 2건이 추가돼 recommendationTest가 17→19다.
- AI pytest 410, corpus 9/9·partial=false·safety.failures=[]; ruff/format/mypy 통과. web verify:ci 148건 및 build, Playwright 4건 통과. generated client diff·audit·egress-denied·readiness 통과.
- root scripts 89건, 문서 검증 통과. 로그 `.artifacts/ba-004/full-integration-merged.log`, 실제 report `.artifacts/integration/`.
- B1부터 marker 부재를 이유로 full gate를 생략할 수 없다. CON-006의 “FE PR #17 병합 후” 선행 조건도 이제 충족됐다.

### git

- 이번 A4(BA-004) 커밋은 CI·보고서 검사·test context cache만 추가한다. 공개 API·migration 변경은 없다.

- `origin/main` = `26d5d90` (PR #17·#21까지 병합됨). **BA-002(`11d6efc`)는 이미 main에 있다.**
- 로컬 `main` ref는 낡았다(`3546086`). 판단에 쓰기 전에 `git fetch` 후 `origin/main`을 봐라.
- `backend`는 `origin/backend`로 push돼 있다. 담고 있는 것: `296b5af`(BA-005 job runtime), `4dea8cc`(origin/main 수신 merge), `a047ed8`(BA-003), 그리고 이 문서.
- **BA-005와 BA-003은 아직 main에 없다.** main 병합은 사용자가 지시할 때만 한다.
- 조회 시 열린 PR은 없다. 사용자 후속 지시로 승인 PR 병합 및 완료 이슈 갱신이 허용됐다. #17/#21은 이미 main에 있어 A4 커밋 뒤 backend로 수신했다. deploy는 여전히 비범위다.

### A3(BA-003) — 완료, `a047ed8`로 커밋됨

구현(36파일) → 적대적 검토 3렌즈(17건 판정) → 수정 라운드 1 → 검증 3렌즈(8건) → 수정 라운드 2 → 검증 1렌즈(5건) → 직접 수정. 모든 가드는 결함을 되살렸을 때 실제로 red가 되는 것까지 확인했다.

**커밋 시점 검증 실측**: `test 274 / integrationTest 101 / openapiContractTest 9 / recommendationTest 17` (0 fail/error/skip), `validate_docs.py`·`scripts/tests` 70건·markdownlint 47파일·redocly 전부 통과. 이 숫자가 줄면 회귀다.

### A4(BA-004) — Backend/AI CI 구현

- PR 전용 `origin/main` OpenAPI breaking diff(oasdiff action v0.1.15 SHA pin), JUnit·ready-card ID·Gradle manifest·evaluation·freshness 집계와 native/Compose 연결을 구현했다.
- `TemporalComparisonPolicyTest.randomPairsAreEligibleOnlyWhenAllFiveConditionsHold`에 `REC-DATA-02` testcase ID를 추가했다. 기존에는 class display name에만 있어 새 runner가 실제로 실패했다.
- **A4 구현 완료와 BA-004 카드 전체 완료는 다르다.** C1의 isolated provider stub은 구현됐지만, BA-004-T3의 실제 Compose egress evidence와 BA-004-T1/T2 Python CI test ID report 연결은 아직 하나의 ready-card report contract로 합쳐지지 않았다. 예외 목록을 추가하지 않고 BA-004는 `in-progress`로 유지한다.
- 실제 Compose에서 기본 Spring context cache의 Hikari pool 누적으로 `SQLSTATE 53300`이 발생했다. Gradle test worker의 `spring.test.context.cache.maxSize=1`로 제한했다. app pool/worker budget은 그대로다.
- 최종 실측(main 수신 전): local·offline Compose 각각 `274 / 101 / 9 / 17`, 0 fail/error/skip. Python unittest 89, docs·Markdownlint·Redocly·AJV 통과. 코드/설정 변이 25개 최종 RED+SHA 복원 일치, OpenAPI 경로 제거 변이 exit 1.
- 변이/실행 근거: `.superpowers/sdd/2026-09-08-backend-a-to-d/progress.md`의 A4 절, `.artifacts/ba-004/`(로컬). 실행하지 않은 원격 PR gate를 로컬 재현과 혼동하지 않는다.

**다음 작업은 B3(BA-012)이다.** `plan.md`의 `## Slice B3` 절과 V006을 따른다.

## 3. A3에서 내린 판정 중 이후 slice가 알아야 할 것

구현은 끝났다. 아래는 **되돌리면 안 되는 결정**과 **이후 slice가 넘겨받는 부채**다.

### 되돌리지 말 것

- **catch-all은 throwable을 로그 인자로 넘기지 않는다.** `GlobalExceptionHandler.framesOf`가 class와 frame만 문자열로 만든다. frame(클래스·메서드·파일·줄)에는 호출자 입력이 원리적으로 못 들어가지만 message에는 들어간다 — `apps/api/src/main/java`의 `throw new` 145개 중 **60개**가 값을 보간한다(예: `UuidV7`의 `"not a UUIDv7: " + uuid`). 편의를 위해 `exception`을 인자로 되돌리면 canary 테스트가 즉시 red가 된다.
- **`MissingPathVariableException`은 400으로 잡지 않는다.** Spring 자신이 500으로 분류하는 서버 결함(`@PathVariable` 이름이 URI template과 불일치)이라 catch-all의 500 + operator 로그로 가야 한다. `ServletRequestBindingException` 같은 상위 타입으로 매핑을 넓히면 이게 조용한 400이 된다.
- **`DemoCapabilityQuery`는 ON 플래그에 startup을 실패시킨다.** source 없는 capability를 READY로 광고하지 않기 위한 것이고, ENVIRONMENT.md §6 표도 여기에 맞춰 OFF로 정정했다. staging을 띄우려고 완화하지 말고 source를 만드는 slice(B03 replay / B06 optimization / B10 live)가 켠다.
- **`server.tomcat.max-swallow-size` 줄을 지워도 어떤 테스트도 못 잡는다.** 선택한 값이 Tomcat 기본값과 같기 때문이다. 이건 알려진 사실이고, 설정 파일에 줄이 있는지 검사하는 테스트는 동작이 아니라 파일을 검사하는 것이라 넣지 않기로 했다.

### 이후 slice가 넘겨받는 부채

- **CON-006**: operation별 `401`/`429`/`default`/**`413`** 선언을 `docs/api/openapi.yaml`에 추가한다. **FE PR #17이 main에 병합된 뒤의 PR에서** 한다. 완료 조건에 "생성 client를 재생성하지 않으면 `api-client-diff`의 `check-generated.mjs`가 실패한다"를 명시할 것. 현재 PM-019 실측: 50 operation 중 401 선언 4개·429 선언 4개, `default` 누락 6개, **403 선언 0개**, `Forbidden`/`Gone` reusable response 자체가 없음.
- **`getDemoReadiness`의 `sessionCookie`가 미강제**다. `ImplementedOperationsRegistry.SECURITY_NOT_YET_ENFORCED`에 등록돼 있고 `SystemContractTest`가 그 집합을 순회하며 "계약이 scheme을 선언하는데 라우트는 인증 없이 200을 준다"를 단언한다. **B1(BA-010)이 session layer를 넣으면 이 테스트가 red가 된다** — 그때 집합에서 빼는 게 정상 절차다.
- **`handleUnexpected`의 `bodyTooLarge` 분기를 삭제했다.** 오늘 이 shape를 만드는 코드가 없고(Jackson은 `HttpMessageNotReadableException`으로 감싼다) 도달시키려면 합성 route가 필요해서, 프로젝트의 "요청 범위를 넘는 방어 코드 금지" 규칙을 따랐다. 대가: **`RequestBodyTooLargeException`을 다른 타입으로 감싸는 converter가 생기면 초과 body가 413이 아니라 500이 된다.** 비-Jackson body converter(multipart, 커스텀)를 추가하는 첫 slice는 초과 body가 여전히 413인지 다시 확인해야 한다.
- **미매핑 예외 2개**: `RecommendationUnavailableException`(BA-032/BA-051 소유), `JobEnqueueException`. 지금은 catch-all의 500이다.
- **`MissingServletRequestPartException`은 이 계층에 없다**(`jakarta.servlet.ServletException`을 직접 상속). `@RequestPart`를 쓰는 첫 route가 따로 매핑해야 한다.
- **BA-005가 남긴 제약**: `JobConnectionBudget`이 startup에서 pool 대비 worker 수요를 검사한다. **첫 실제 handler slice(BA-012/B03/B06)는 concurrency와 `NULLNULL_DB_POOL_MAX`를 함께 정해야 한다**(type 3개 × 기본 concurrency 2 → pool ≥ 18). 또한 handler를 추가하는 slice는 `V004` 마이그레이션 **뒤에** 와야 한다(구버전의 `ON CONFLICT (deduplication_key)`가 성립하지 않는다).
- **`APP_IDEMPOTENCY_LOCK_TIMEOUT`은 제안값이다.** `IdempotencyGuard.execute`에 production 호출자가 아직 0개라 측정이 불가능하다. 첫 실제 command endpoint를 만드는 slice가 **suite에 남는 테스트로** 측정해 확정값으로 올린다.

## 4. slice를 닫는 방법 (커밋 규약)

```bash
git add <명시 경로만>          # git add -A 금지. $HOME 자체가 git 저장소다
git commit -m "feat(be): BA-0xx <한 줄 요약>"
```

- **Work ID(`BA-0xx`)를 반드시 포함**한다(프로젝트 규칙).
- **attribution 문구를 넣지 않는다**(생성도구 언급 금지).
- `.obsidian/graph.json`은 사용자 파일이다. **stage하지 않는다.**
- `backend-plan.json` status를 `integration-ready`로 올리고, 카드에 실제 test class·report 경로를 적는다(`verified`는 staging 증거가 있어야 한다 — validator가 거부한다).
- **push는 `origin/backend`까지만** 사용자가 승인했다. main 병합·PR 생성은 별도 지시가 필요하다.

## 5. A3 이후 남은 slice

`plan.md`의 해당 절을 그대로 따른다. 각 slice는 **구현 → 적대적 검토 → 판정 → 변이로 증명된 수정 → 네 suite + docs 게이트 → 커밋**이다.

| Slice | 카드 | plan.md 절 | 비고 |
| --- | --- | --- | --- |
| A4 | BA-004 | `## Slice A4` | Backend/AI CI 구현 완료. 카드 `in-progress`: C1 isolated stub은 구현됐지만 actual Compose egress evidence와 Python CI test ID report 연결 미완료 |
| B1 | BA-010 | `## Slice B1` | 익명 owner·session·CSRF. `V005__demo_sessions.sql`. **A3의 `@NullnullOperation` 정책 표를 실제로 강제하는 slice** |
| B2 | BA-011 | `## Slice B2` | 프로필·locale·onboarding. merge-patch의 null/absent 구분 |
| B3 | BA-012 | `## Slice B3` | 세션 삭제 receipt·TTL·복원 후 재삭제(`TombstoneReapplier implements SmartLifecycle`) |
| C1 | BA-020 | `## Slice C1` | `integration-ready`: source registry·adapter kit·쿼터·drift |
| C2 | BA-021 | `## Slice C2` | KTO 실호출. **Step 0(provider spec 고정)을 코드보다 먼저** |
| C3 | BA-022 | `## Slice C3` | canonical 장소·검색·상세·콘텐츠 권리 |
| C4 | BA-023 | `## Slice C4` | 혼잡 예보·provenance·비교 적격성 |
| C5 | BA-024 | `## Slice C5` | 검증된 관련 장소 |

### plan.md의 migration 번호는 하나씩 밀려 있다 (중요)

A2(BA-005)가 계획에 없던 `V004__background_jobs_outstanding_key.sql`을 추가했기 때문에, `plan.md`가 B1 이후 slice에 적어둔 번호는 전부 +1 해야 한다. 현재 존재하는 것은 `V001__background_jobs` · `V002__owners` · `V003__idempotency_records` · `V004__background_jobs_outstanding_key` 넷이다.

| slice | plan.md 표기 | 실제로 써야 할 번호 |
| --- | --- | --- |
| B1 BA-010 | `V004__demo_sessions.sql` | **`V005__demo_sessions.sql`** |
| B3 BA-012 | `V005__deletion.sql` | **`V006__deletion.sql`** |
| C1 BA-020 | `V006__sources.sql` | **`V007__sources.sql`** |
| C2 BA-021 | `V007__catalog_places.sql` | **`V008__catalog_places.sql`** |
| C3 BA-022 | `V008__catalog_rights.sql` | **`V009__catalog_rights.sql`** |
| C4 BA-023 | `V009__crowd_snapshots.sql` | **`V010__crowd_snapshots.sql`** |
| C5 BA-024 | `V010__place_relations.sql` | **`V011__place_relations.sql`** |

**이미 적용된 migration(V001~V004)의 이름이나 내용을 고쳐서 번호를 맞추지 마라.** 어디서든 한 번 실행된 migration은 수정하지 않고 앞으로만 고친다(`.claude/rules/database-migrations.md`). 새 파일에 다음 번호를 쓰면 된다.

## 6. 검증 — 이 기기에서 실제로 도는 형태

```bash
# Codex 실행 환경에 DEBUG=release가 있으면 먼저 unset DEBUG (아래 §7).
# Java (Temurin 21 필수. 기기 기본 java는 26이라 JAVA_HOME 없이는 실패한다. wrapper만, 설치형 Gradle 금지)
cd /Users/yutak/Desktop/Nullnull/apps/api
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
./gradlew --no-daemon test --rerun integrationTest --rerun openapiContractTest --rerun recommendationTest --rerun

# 문서·계약 (반드시 저장소 루트에서)
cd /Users/yutak/Desktop/Nullnull
python3 scripts/validate_docs.py
python3 -m unittest discover -s scripts/tests -p 'test_*.py'
npx --yes markdownlint-cli2@0.23.2
npx --yes @redocly/cli@2.51.1 lint docs/api/openapi.yaml

# apps/ai를 건드린 경우만 (uv 0.12.10)
cd apps/ai && export PATH="$PWD/.uv-bootstrap/bin:$PATH"
uv run ruff check . && uv run ruff format --check . && uv run mypy && uv run pytest

# phase 끝: 컨테이너 게이트 (offline·external DB)
docker compose -f compose.integration.yml --profile quality run --rm api-quality
```

**A3 직전 기준선**: `test 274 / integrationTest 95 / openapiContractTest 9 / recommendationTest 17`, 전부 0 fail/error/skip. 이 숫자가 줄면 회귀다.

`bash scripts/integration-test.sh` 전체는 `.nullnull-target-stack` marker(FE scaffold와 같은 PR)가 없는 지금 **exit 1이 정상**이다. 통과로도 버그로도 쓰지 않는다.

## 7. 이 작업에서 실제로 당한 함정 (반복하지 말 것)

1. **`--rerun`은 마지막 task에만 붙는다.** `./gradlew test integrationTest openapiContractTest recommendationTest --rerun`은 앞 세 개를 UP-TO-DATE로 넘긴다 → 낡은 XML을 읽고 "통과"라고 보고하게 된다. task 이름마다 반복하라.
2. **exit 0은 실행의 증거가 아니다.** macOS에는 `timeout`이 없어서 그걸 쓴 컨테이너 실행이 아예 안 돌았는데 파이프 때문에 exit 0으로 보였고, 그때 읽은 숫자는 이전 실행의 잔여 artifact였다. 카운트는 반드시 `build/test-results/<suite>/*.xml`에서 읽어라.
3. **`git checkout`/`restore`/`stash`/`reset`/`clean` 금지.** 슬라이스 전체가 미커밋이라 이 명령들이 곧 파괴다. 변이 검증 시 파일을 scratchpad에 `cp`해두고 `cp`로 복원한 뒤 `shasum -a 256`으로 대조하라. (실제로 이 실수로 편집분을 한 번 날렸다.)
4. **루트 게이트를 `apps/api`나 `apps/ai` 안에서 돌리지 마라.** `unittest discover`와 `validate_docs.py`가 상대 경로로 깨진다.
5. **여러 에이전트를 같은 작업 트리에서 병렬로 돌리지 마라.** 검증 3렌즈를 동시에 돌렸더니 서로의 probe 파일과 `build/test-results`를 덮어써 한 실행이 파괴됐다. 병렬이 필요하면 `rsync -a --exclude build`로 격리 복사본에서 돌려라.
6. **공유 DB는 양방향 위험이다.** 앞 테스트의 잔여 행 때문에 실패할 수도, **우연히 통과할 수도** 있다. 컨테이너 게이트는 깨끗한 DB와 더러운 DB 양쪽에서 돌려 확인했다.
7. **문서 함정**: `docs/**/*.md`는 Obsidian frontmatter 필수. `IMPLEMENTATION_PLAN.md`·`BACKEND_AI_PLAYBOOK.md`에는 날짜·소요일을 못 쓴다(`2026-09-08`·`09/08`·`(1d)`를 validator가 거부). 본문에 `FCR-0XX`를 쓰면 `FIGMA_CHANGE_REQUESTS.md` 표에 먼저 등록해야 한다.
8. **API 이름을 기억으로 쓰지 마라.** `getAllValidationResults()`는 Spring 7.0.9에 없다(`getParameterValidationResults()`). `javap`로 실제 jar를 확인하거나 Context7을 써라.

9. **Codex tool 환경의 `DEBUG=release`가 Spring Boot DEBUG 로깅을 켠다.** Boot 4.1.1 `LoggingApplicationListener.isSet`은 값이 null/`false`가 아니면 true다. A4 첫 전체 실행과 HttpPolicyIT 독립 실행에서 Spring의 `Resolved [RuntimeException: ...]` DEBUG 로그에 canary가 노출되어 기존 검사가 실패했다. `env -u DEBUG JAVA_HOME=... ./gradlew ...`로 해당 테스트 프로세스에서만 제거하니 통과했다. canary 단언·앱 logging 설정을 완화하지 않는다.

## 8. 이 프로젝트에서 반복해서 나온 결함 유형

**"증거처럼 보이지만 아닌 것"** 이 압도적으로 많았다. 새 가드를 넣을 때마다 *결함을 되살렸을 때 실제로 빨개지는지* 확인하는 게 이 저장소의 합격선이다.

추가 사례(이번 세션): **presence-only 비교가 검사를 통째로 무력화한 두 건.**

- `validate_backend_plan.py`가 카드 status를 `value not in card`로 검사했다. 카드 본문에 "`integration-ready`로 올리지 않는다"라고 **적는 순간** 그 문자열이 존재하므로, 그 카드는 manifest가 어떤 status를 주장해도 통과한다. status에 대한 **산문을 쓰는 것이 가장 자연스러운 일**이라 구멍이 사고로 열린다. 이제 카드 **헤더 줄**과 정확히 비교한다.
- `check-examples.mjs`의 `FIXTURE_OF`가 example 이름만으로 키를 잡아, 두 operation이 같은 이름을 쓰면 엉뚱한 fixture에 고정된다. 값이 달라 드러났을 뿐 같았다면 조용히 통과했다.

**presence-only를 고칠 때는 같은 루프의 다른 필드도 함께 본다.** 위 status 구멍을 고치면서 `status` 하나만 바꾸고 넘어갔는데, 같은 루프의 `priority`·`figmaNodes`·`designRequests`·test ID가 그대로 presence-only였고 **BA-030 카드가 이미 그 조건을 만족시키고 있었다**(산문에 `FCR-020`이 있어 manifest가 `designRequests: ["FCR-020"]`를 주장해도 통과했다 — 실측 확인). 이제 여섯 필드 전부 그것을 선언하는 **한 줄**과 정확히 비교한다. 결함 하나를 고칠 때 같은 모양이 옆에 몇 개 더 있는지 세는 것이 규칙이다.

**새 테이블을 추가하면 owner 삭제 경로를 함께 본다.** `DeletionIT` BA-012-T2가 `information_schema`에서 `owner_id` column을 가진 **모든** table을 훑어 "소유 모듈이 지우거나 명시적 이유로 보존"을 요구한다. BA-030의 `trips`가 이걸 어겨서 `TripOwnerDataEraser`를 추가했다 — owner를 삭제해도 trip이 남는 개인정보 결함이었다. **BA-032(posts·saved_posts)와 BA-034(trip_candidates)도 owner 소유 테이블을 추가하므로 같은 자리다.** 그 검사가 잡아 주지만, 잡히고 나서 붙이는 것보다 migration과 같은 PR에서 eraser를 쓰는 게 맞다.

실제 사례: `evaluation.json` 게이트가 존재만 검사 / wrapper 호출 단언이 **주석 처리된 줄**에 매칭 / `PURE_PACKAGES` 자기비교가 자신의 축소를 못 잡음 / `APP_IDEMPOTENCY_TTL=24`가 **24밀리초**로 부팅 / `@Lock(PESSIMISTIC_WRITE)`를 지워도 전부 green / lease보다 긴 작업이 만료된 lease로 커밋하고 handler를 두 번 실행 / `deduplication_key` UNIQUE가 종료 행까지 덮어 예약 collector가 조용히 영영 안 도는 시나리오 / canary 테스트가 `getFormattedMessage()`만 봐서 throwable로 새는 걸 못 봄.

## 9. 사용자 확정 결정 (재논의 불필요)

- KTO 키 사용 가능 → 제출(production) 빌드에서 **`DEV_APPROVED` 개발 키(1,000/일) 실호출 허용**.
- FE 미승인 계약(BA-011/022/023/024)은 OpenAPI 0.2.1-rc.1 example 기준으로 **controller까지 구현**하고 `openapiContractTest`로 고정한다.
- CSRF token 5개 초과 → **`last_used_at` 기준 LRU 회수**.
- D-015 stale threshold → **forecast `PT24H`, place detail `P7D`**.
- `KTO_RELATED_PLACES` → **미신청이므로 `DISABLED`**. C5는 `NULLNULL_CATALOG_RULE` SIMILAR + `UNKNOWN(reason SOURCE_DISABLED)`만.

나머지 미확정값은 `plan.md` §15 표를 따르되 **제안값**으로 표기하고 근거를 적는다. 숫자를 지어내지 않는다.

## 10. 절대 규칙

- slice 커밋과 `origin/backend` push는 승인된 범위다. **main 병합·PR 생성·deploy는 사용자가 지시할 때만 한다.**
- `main`에서 직접 작업하지 않는다. Backend/AI는 `backend`.
- `git add -A` 금지(특히 `$HOME`이 그 자체로 git 저장소다). 경로를 명시한다.
- 커밋·PR에 **생성도구 attribution을 넣지 않는다**.
- secret·cookie·token·계정 ID·원문 일정·실제 위치를 source/fixture/screenshot/prompt/log에 넣지 않는다.
- 파괴적·유료 작업(rm -rf, reset --hard, DROP, AWS delete/deploy)은 세션에서 명시 확인을 받는다.
- 실행하지 못한 검사를 통과로 쓰지 않는다. 불가능하면 `검증 생략: <이유>`를 명시한다.

## 11. 정본 정합성 PR 이후 (P0 묶음 PR의 첫 번째)

이 절은 `~/.claude/plans/fe-be-ai-dazzling-dongarra.md`의 **P0 PR**을 실행한 기록이다. 코드 변경은 `@DisplayName` 한 줄뿐이고 나머지는 문서·정본이다.

### 되돌리지 말 것

- **`BA-000`·`BA-001`은 `contract-ready`, `BA-006`은 `blocked`다.** 셋 다 `backend-plan.json`과 `BACKEND_AI_PLAYBOOK.md` 카드 제목 줄을 **같은 커밋에서** 바꿨다. 한쪽만 바꾸면 `docs-contract`가 양방향으로 실패한다(JSON만 → `task card missing manifest value contract-ready`, 카드만 → `... missing manifest value planned`).
- **`BA-000`·`BA-001`을 `integration-ready` 이상으로 올리지 마라.** `scripts/check_test_reports.py:99-100`은 `integration-ready`·`verified`인 task에 한해 모든 `tests[].id`를 **Gradle JUnit testcase 이름**에서 찾는다. 두 카드의 증거는 Python gate(`validate_backend_plan.py`·`validate_docs.py`·`verify_target_stack.py`)라 JUnit XML에 나타나지 않으므로 두 required check가 즉시 실패한다. `contract-ready`와 `blocked`는 이 검사에서 건너뛴다(실측 확인).
- **카드↔JSON status 동기화는 "포함 여부"만 본다**(`validate_backend_plan.py:154`, `value not in card`). 즉 카드 **본문 산문**에 status 단어가 들어 있으면 **제목 줄이 낡아도 validator가 통과한다.** 상태를 바꿀 때는 반드시 `### BA-xxx` 바로 아래 굵은 제목 줄(`**제목** — P0 / \`status\` / …`)을 직접 확인하라. 현재 이 가면 현상을 이미 가진 카드가 7개 있다(BA-002/003/010/023이 `verified`, BA-004/021이 `integration-ready`, BA-081이 `blocked`를 산문에 포함).
- **`BA-000-T3`은 CI가 강제하지 않는다.** `AGENTS.md`의 `docs-contract` 행에서 T3를 등록 해제했다. `validate_docs.py`의 `validate_product_contract_alignment`는 discriminator와 variant별 `const`·`required`를 고정해 "거부할 수 있는 구조"까지만 지키고, 잘못된 example을 실제 evaluator에 넣어 거부를 확인하는 negative fixture가 없다. 구조 고정과 거부 증명을 같은 것으로 쓰지 마라.
- **`BA-001-T2`는 `ArchitectureRulesTest.modulesNeverReachIntoAnotherModulesInfrastructure`다.** `@DisplayName("BA-001-T2 …")`를 붙여야 testcase `name` 속성에 ID가 실려 집계에 잡힌다(class-level `@DisplayName`은 testsuite 이름이라 잡히지 않는다). 이 줄을 지우면 `AGENTS.md`의 `api-quality` 행이 다시 거짓이 된다.
- **root `package.json`의 `security:scan`을 삭제하지 마라.** `scripts/verify_target_stack.py:98`이 `{api:check, security:scan, infra:check}` 세 이름의 **선언**을 요구한다. 이름을 지우면 target-stack 검사가 실패한다. "호출하는 gate가 없다"는 진단은 맞지만 해법이 삭제가 아니다 → #119.
- **`scripts/infra-check.mjs`는 죽은 script가 아니라 `docker-integration` 안에서 돈다**(`compose.integration.yml`의 `infra-plan` service). `infra/`가 없으면 exit 0이라 통과로 집계된다. 지금 hard fail로 바꾸면 `main`이 깨지므로(BA-006이 `blocked`) #119에서 `infra/` 생성과 함께 처리한다.
- **`FOUNDATION_DECISIONS.md` D1은 "채택되지 않음"이다.** `apps/web`·marker는 `frontend`에서 만들어져 PR #17로 `frontend → main` 병합됐다. backend가 scaffold를 host한 적이 없으므로 이 절을 근거로 새 인계 절차를 만들지 마라. D2 anchor는 `docs/api/README.md:28`이 링크하므로 건드리지 마라.

### 이슈 상태

- **닫았다**: #25(BA-003) #27(BA-005) #29(BA-010) #30(BA-011) #31(BA-012) #35(BA-023). 여섯 건 모두 plan status는 `integration-ready` 그대로이고 **`verified`로 올리지 않았다.** 카드·이슈 본문의 "상대 재현 확인 전 완료로 쓰지 않는다"가 막는 것은 `verified` 승격이며, 잔여는 각각 다른 이슈가 단독 추적한다는 오너 판단으로 닫았다.
- **새로 연 이슈**: #118(계약 공백 — `statusUrl` 정의, terminal status 집합, `Retry-After`, 404/410 응답별 `code` const, 429 미구현, merge-patch 415 미열거, `createDemoSession` `Cache-Control` 미선언), #119(root gate script 2건이 선언만 되고 강제하지 않음).
- **추적자 없는 잔여 1건**: BA-010의 FE 화면 검수 인계(cookie 유실 안내, 401 뒤 mutation 자동 재실행 금지, `apps/web/serve.mjs`의 API proxy 부재, browser Secure cookie 수락). 전용 FE 이슈를 찾지 못했고 #29를 닫았으므로 **여기가 유일한 기록이다.**
- **#107의 전제는 절반만 맞다.** `FR-SES-01`·`FR-SES-04`만 구현 완료이고, `FR-SES-02`(재-bootstrap 없음)·`FR-SES-03`(`issueCsrfToken` 호출 없음)은 부분, **`FR-PLC-01`은 미구현**이다(`apps/web` 전체에서 `getPlace` 호출 0건). 다섯 개를 한꺼번에 featureIds에 넣으면 plan이 실제보다 앞선다.

### 이 PR에서 실제로 실행한 검증

- `python3 scripts/validate_docs.py` 통과(50 operation, link/anchor, product-contract alignment, backend·frontend plan).
- `python3 -m unittest discover -s scripts/tests` **110건 OK**. `test_current_repository`가 실제 저장소에 대해 validator 문제 0건을 단언하므로 JSON·카드가 갈라진 중간 커밋은 push할 수 없다.
- `npx markdownlint-cli2@0.23.2` 47파일 0 issue, `@redocly/cli@2.51.1 lint` valid, AJV valid.
- Temurin 21 `test integrationTest openapiContractTest recommendationTest` = **322 / 154 / 13 / 19**, failure·error·skip 0. `--rerun`을 task마다 반복했다.
- `grep -o 'name="BA-001-T2[^"]*"' build/test-results/test/TEST-io.nullnull.ArchitectureRulesTest.xml`이 실제로 ID를 반환한다. **build 성공이 아니라 이 grep이 `@DisplayName`이 testcase 이름에 실렸다는 증거다.**
- `scripts/check_test_reports.py` → `test_reports=valid` (새 status 3개 반영 후).
- `bash scripts/integration-test.sh` → **EXIT=0, `integration_mode=full-docker`**. AI pytest 410, web unit 597(42 파일), Playwright 51, `evaluation_report=valid`, `test_reports=valid`, npm audit 보고서 생성, egress-denied 통과.

## 12. FE 질문 대응 — 계약 정정과 첫 response example

### 되돌리지 말 것

- **`runLink`는 API path가 아니라 client router path이고, pattern은 `/trip/`(단수)다.** 앱 라우터가 `trip/:tripId/optimizations/:runId`인데 계약의 옛 `/trips/`(복수)는 **앱 라우터와도 API와도 일치하지 않아** `ProfileScreen`의 `to={run.runLink}`가 404였다. 복수로 되돌리지 마라. `packages/contracts/fixtures/optimizations/history-page.json`의 3줄이 같이 움직인다 — **ajv가 pattern을 강제하므로 한쪽만 고치면 `apps/web` vitest가 깨진다.**
- **이 정정은 oasdiff 승인 예외로 통과시켰다. 예외 경로를 함부로 넓히지 마라.** oasdiff는 response property의 pattern 변경을 방향과 무관하게 잡는다 — 축소는 `response-property-pattern-changed`(warning, `fail-on: WARN`이라 실패), 삭제는 `response-property-pattern-removed`(error)로 더 나쁘다. 그래서 `docs/api/oasdiff-ignore.txt`에 **정확한 메시지 한 줄**만 넣고 [등록부](docs/api/BREAKING_CHANGE_EXCEPTIONS.md)에 이유·승인자·추적 이슈를 적었다.
  - **이것은 검사를 끄는 것이 아니다.** 실측으로 확인했다 — 같은 spec에 `runLink` 외의 breaking 변경(required 응답 property를 optional로)을 넣으면 ignore 파일이 있어도 **error로 실패한다.**
  - `scripts/tests/test_oasdiff_exceptions.py`가 강제한다. 변이 4종이 전부 RED다: 등록부 행 삭제, 승인자 공백, 추적 이슈 제거, ignore 줄만 삭제(stale 행). SHA 복원 일치를 확인했다.
  - **예외는 스스로 만료된다. 그걸 강제하는 것은 unit test가 아니라 `scripts/check_oasdiff_exceptions.py`다.** 정정이 `main`에 들어가면 base가 새 값이 되어 그 메시지가 더 이상 보고되지 않는데, `test_oasdiff_exceptions.py`는 ignore↔등록부 **대응만** 검사하므로 이 상황을 잡지 못한다(한 번 잘못 주장했다가 실측으로 확인했다). 그래서 `docs-contract`에 별도 step을 두어 **ignore 줄이 실제 oasdiff 출력에 없으면 실패**시킨다. 매칭되지 않는 줄은 지워야만 green이 된다.
  - 만료된 예외는 등록부의 `## 만료된 예외 (기록)` 절로 옮긴다. parser는 `##`를 만나면 멈추므로 기록이 활성 표를 오염시키지 않는다.
  - `runLink` 예외는 PR #122 병합으로 **이미 만료됐고 정리했다.** 현재 활성 예외는 0건이며 그것이 정상 상태다.
  - 로컬 재현(push 없이): `git show origin/main:docs/api/openapi.yaml > /tmp/base-openapi.yaml && docker run --rm -v /tmp:/spec -v "$PWD/docs/api:/rev" tufin/oasdiff breaking /spec/base-openapi.yaml /rev/openapi.yaml --fail-on WARN --err-ignore /rev/oasdiff-ignore.txt`. **`--warn-ignore`만으로는 안 된다** — breaking 변경은 거의 전부 oasdiff가 `error`로 보고하고 `warn-ignore`는 warning만 억제한다. 처음에는 workflow가 `warn-ignore`만 넘겨서 예외 경로가 error를 하나도 덮지 못했다(#145에서 드러남). **계약 PR 전에 이걸 먼저 돌려라.**
- **`getPlace` example의 `description`·`thumbnailUrl`·`thumbnailAsset`은 null이 정답이다.** collector가 `overviewYN=N`·`firstImageYN=N`으로 요청해 overview 텍스트와 이미지를 **저장하지 않는다.** 여기에 풍부한 텍스트를 넣은 example은 실제 연동 첫날 깨지는 허구다.
- **`place-detail.json`의 `externalId`(`KTO-PENDING-CAPTURE`)와 `location` 좌표는 captured provider 증거가 아니다.** `categoryCode`/`regionCode`의 `HS`/`11`만 실제 `detailCommon2` 호출에서 온 값이다(#109). manifest `placeDetail.basis`에 이 구분을 적어 뒀다. 실응답을 잡으면 교체한다.
- **`NULLNULL_CATALOG_PUBLIC_ENABLED=false`를 FE 편의를 위해 켜지 마라.** fail-closed는 BA-021-T3 staging 증거 전까지 유지되는 설계된 안전 gate다. FE가 막힌 문제는 flag가 아니라 **승인된 example이 없던 것**이었고, 그건 위 fixture로 풀었다.
- **`FCR-005`의 조건부는 해제했다.** `418:2523`의 정렬 control 결함은 **`FCR-025`(P0 blocker, Open)가 단독 추적**한다. `FCR-005` 증거 절에 메모 한 줄만 남겼고 중복 추적하지 않는다. `#13`은 `FCR-016~028`을 명시적 비범위로 두므로 이 결함은 `#13`을 막는 근거가 아니다.
- **`FCR-011`의 폭 blocker 원인은 계약 버전이 아니다.** 문서에 있던 "main은 아직 `0.2.0`"은 거짓이었다(main은 `0.2.1-rc.1`이고 `attributionShort`와 example 2개가 있다). 진짜 원인은 **서버가 `attributionShort`를 항상 `null`로 내보내는 것**이다 — `apps/api` main 전체에서 이 필드를 채우는 코드가 없고 `CrowdProvenanceProjection`이 `null` 리터럴을 넣는다. crowd 계약 PR에서 서버가 실제 값을 채운다.

### FE에 넘긴 것 (BE가 더 댈 것 없음)

- `FR-SES-02`(401 뒤 재-bootstrap)·`FR-SES-03`(`issueCsrfToken` 호출)은 서버가 이미 완성돼 있어 client 작업만 남았다. 재시도는 **safe GET·1회**로 제한해야 한다(mutation 자동 재시도는 불변식 6 위반).
- 두 기능 ID가 **어느 FE task에도 등록돼 있지 않다.** FE-101이 이미 `operations`에 `issueCsrfToken`을 갖고 있어 거기 붙이는 것이 가장 싸다.
- `FR-PLC-01`은 FE 소유가 맞지만 `getPlace`를 부르는 화면이 Figma에 없다. 기존 task에 억지로 붙이지 말고 화면 결정 후 신규 task를 만든다.
- **`fixtures.test.ts`는 `apps/web`(FE 소유)라 새 fixture의 ajv 단언을 내가 넣지 못했다.** `placeFixtures.detail`을 export까지 해 뒀으니 FE가 한 줄 추가하면 된다. 이번 세션에서 같은 ajv 설정으로 직접 검증했고 `PlaceDetail` 스키마를 통과한다.

### 남은 계약 공백 (#118)

`ordinalLevel`의 진짜 공백은 숫자가 아니라 **표시 단어**다. 디자인 정본은 "막대 + `4 · 혼잡` 문구"를 요구하는데(`COMPONENT_CATALOG.md:90`, 어휘는 `FIGMA_CHANGE_REQUESTS.md:200`의 `1 · 매우 여유`~`4 · 혼잡`), 계약의 `label`은 "diagnostic, not display copy"라 FE가 단어를 받을 곳이 없다. `pattern`만 추가하는 안은 채택하지 않는다. 어휘는 서울 실시간 도시데이터 실응답 1건으로 확정한 뒤 계약에 넣는다 — 지금 enum에 적으면 임의 기입이다. `FCR-029`가 그때까지 "혼잡 표시 없이 구현"으로 이미 합의돼 있다.

## 13. 대기 목록과 진행 순서 (세션이 끊겨도 이어받을 것)

### 배포 단계 — 순서상 마지막이지 블로커가 아니다

**AWS 배포는 전체 작업 완료 + Docker 검증 통과 뒤에 착수한다(오너 확정).** 따라서 아래는 "응답 대기"가 아니라 **계획된 마지막 단계**다. 사용자나 옆 세션에 에스컬레이션하지 마라 — 물어봐야 같은 답이 돌아온다.

- `BA-021-T3`(staging 실호출 증거) → `BA-022` → **B04·B05 전체**. 이 체인은 배포 전까지 열리지 않는다.
- 따라서 `BA-022`와 B04·B05 카드가 `in-progress`/`planned`에 머무는 것은 **정상이다.** 실패나 미완으로 적지 마라.
- **억지로 `integration-ready`로 올리지 말고, `check_test_reports.py`가 막는 지점도 우회하지 마라.** 그 검사가 요구하는 JUnit 증거가 아직 없는 게 맞다.
- **실질 완료 게이트는 `bash scripts/integration-test.sh`(full-docker)다.** 각 slice를 "Docker에서 통과"까지 끌고 가는 것이 지금 확보 가능한 최고 수준의 증거다.

### 실제로 외부 응답을 기다리는 것

- **#109 남은 절반** — `tatsCnctrRatedList` 파라미터 계약. forecast 실호출이 아직 한 번도 성공한 적이 없다. **배포와 무관하게 미리 풀어 둘 것**(5자리 법정동 `11110` 재시도 → `TatsCnctrRateService`의 코드 목록 operation 확인 → 공공데이터포털 15128555 활용가이드). 로컬에서 진행 가능하다. 다만 **두 번째 승인 operation을 추가하는 단계**(registry 승인·allowlist·quota 산정)에 도달하면 그때는 올린다.
  - 일정 위험: **불변식 12**(제출 서비스가 KTO를 실제 server-side 호출하고 증거를 보존)가 배포 단계에 묶여 있는데, forecast 실호출이 배포 직전에 처음 시도되면 위험하다. 그래서 #109를 미리 푸는 것이 순서상 중요하다.
- **#118 429 범위**, **#119 `infra:check`** — 자율 판단으로 진행 승인됨. 근거를 PR 본문과 이슈 코멘트에 남긴다.
  - **#119 설계 전제**: `infra/`는 계획상 마지막에 생긴다. 따라서 "부재하면 통과"도 틀렸지만 **"부재하면 즉시 hard fail"도 지금은 틀리다.** 부재를 명시적으로 `blocked`/`not-yet`으로 기록하고 **통과로 집계하지 않는** 형태가 맞다. 조용한 `exit 0`만 없애면 된다.

### 자율 진행에서 제외 (오너 권한·비용)

U-9 AWS 계정·도메인·비용 집행, staging 프로비저닝 · U-1/U-4 공모전 제출 행위 · production deploy · 유료 리소스 생성 · 파괴적 작업.

### 운용 규칙

막히면 **대기하지 말고 블로킹 사유가 다른 issue로 전환**한다. 같은 뿌리(예: 배포)에 걸린 것끼리 옮겨봐야 의미가 없다. 응답이 도착하면 안전한 지점에서 마무리하고 복귀한다. unblocked 작업이 모두 소진되면 그 상태 자체를 보고한다.
