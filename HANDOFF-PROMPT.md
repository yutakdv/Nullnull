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

순서: ~~Slice 0(D)~~ → ~~A1 BA-002~~ → ~~A2 BA-005~~ → ~~A3 BA-003~~ → ~~A4 BA-004 Backend/AI CI~~ → ~~B1 BA-010~~ → ~~B2 BA-011~~ → **B3 BA-012(다음)** → C1 BA-020 → C2 BA-021 → C3 BA-022 → C4 BA-023 → C5 BA-024.

## 2. 현재 상태 (B2 BA-011 구현 후)

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
- **A4 구현 완료와 BA-004 카드 전체 완료는 다르다.** 계획의 T3는 C1 stub으로 미뤄졌고 TS client/MSW는 FE 범위다. 카드를 `integration-ready`로 올리면 같은 계획이 요구한 “ready 카드의 모든 tests[].id” 검사와 모순된다. 예외 목록을 추가하지 않고 BA-004는 `in-progress`로 유지했다. C1에서 T3와 Python CI test ID report 연결까지 닫아야 한다.
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
| A4 | BA-004 | `## Slice A4` | Backend/AI CI 구현 완료. 카드 `in-progress`: T3(C1)·FE 범위 및 CI test ID report 연결 미완료 |
| B1 | BA-010 | `## Slice B1` | 익명 owner·session·CSRF. `V005__demo_sessions.sql`. **A3의 `@NullnullOperation` 정책 표를 실제로 강제하는 slice** |
| B2 | BA-011 | `## Slice B2` | 프로필·locale·onboarding. merge-patch의 null/absent 구분 |
| B3 | BA-012 | `## Slice B3` | 세션 삭제 receipt·TTL·복원 후 재삭제(`TombstoneReapplier implements SmartLifecycle`) |
| C1 | BA-020 | `## Slice C1` | source registry·adapter kit·쿼터·drift |
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
