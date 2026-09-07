---
aliases:
  - "추천 서비스(apps/ai) 구현 계획"
doc_type: plan
status: draft
area: engineering
tags:
  - nullnull/plan
  - nullnull/engineering
---

# Nullnull 추천 서비스(`apps/ai`) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** P0 추천 계산 전체(고정 feed 순서, 관련 장소, 후보 slot, ITEM 날짜/시간 개선, 설명 template)를 Python 서비스 `apps/ai`에 구현하고, Spring `apps/api`는 내부 계약 v1로 이를 호출하되 일정 무결성·데이터 진실성 검증과 저장을 자기 transaction 안에서 유지한다.

**Architecture:** `apps/ai`(FastAPI)는 DB·외부 API·clock·난수 없이 Spring이 hydrate한 immutable 입력만 받아 `source → dedup → hydrate → filter → score → select → post-filter` 파이프라인(x-algorithm 구조 참조)을 돌리고 결과·사유·policyHash를 돌려준다. Spring은 session·trip·잠금·optimization run/job·APPLY/REVERT·KTO/crowd adapter·비교 적격성(`TemporalComparisonPolicy`)·cursor·저장을 소유하며, 서비스 응답을 저장하기 전에 trip 불변식을 다시 검증한다. 공개 OpenAPI 0.2.x는 바뀌지 않는다.

**Tech Stack:** Python 3.13.13, uv 0.12.10, FastAPI 0.141.1, pydantic 2.13.5, pytest 9.1.1, ruff 0.16.6, mypy 2.3.1(strict) · Java 21, Spring Boot 4.1.1(`RestClient`), Gradle 9.7.1 · Docker `python:3.13-slim` digest pin.

**Spec:** 알고리즘 정본은 `docs/architecture/RECOMMENDATION_ALGORITHM.md`; 수식·필터 순서·종결 우선순위·손계산 기대값은 [2026-09-06-recommendation-p0.md](2026-09-06-recommendation-p0.md)(Java 코드는 실행 대상이 아니라 명세)이며 이 계획은 그것을 Python으로 옮긴다. CI 기준 `docs/engineering/TEST_STRATEGY.md#12`, 비교 코드 `docs/data/SOURCE_CATALOG.md#9`~`#10`, 경계 `docs/architecture/SYSTEM_ARCHITECTURE.md#14`~`#16`, 카드 BA-024/032/042/050/051/084.

**착수 전 상태(2026-09-07):** `apps/ai` scaffold(파이프라인 프레임워크, 정책 로더·`policy-v1.yaml`·policyHash, feed 고정 순서 pipeline, `/internal/v1/{health,policy,feed/rank}`, 내부 계약 v1 JSON, pytest 29건·ruff·mypy strict 통과, Docker test/runtime stage)와 `apps/api`의 `RecommendationGateway` port·DTO·계약 parity 테스트가 있다. compose에 `ai`·`ai-quality` 서비스와 wrapper 단계가 추가됐다.

**진행 상태(2026-09-07):** Task 1~9 완료(commit `0c2bfe4..067e95a`, 각 task는 spec+quality 검토와 수정 1라운드를 거쳤다). Task 10~11은 사용자 지시로 일시 중지, Task 12는 `infra/` 미존재·AWS 결정(D-001/D-017/D-031) 대기로 보류. 실행 ledger·검토 결과·지연된 Minor 목록은 `.superpowers/sdd/2026-09-07-recommendation-python-service/progress.md`(gitignore)에 있다.

## 결정 기록 D-REC-6 (ADR-0001 재검토, 2026-09-07)

- **결정**: 추천 계산 전체를 `apps/ai`(Python)에서 구현한다. 사용자가 2026-09-07 "추천 전체를 Python 서비스로"를 선택했다. Backend/AI 담당의 제안(P0 Java 유지, Python은 P2 trigger 후)과 우려(제출 2026-09-21까지 P0 핵심 흐름 미구현, 서비스 추가에 따른 gate·운영 비용, ADR-0001 위배)는 두 차례 전달했고 사용자가 재확인했다.
- **사실**: `xai-org/x-algorithm`(Apache-2.0, main `902a06f`, 2026-09-04)은 Rust(candidate-pipeline, home-mixer)·Python(phoenix 모델 학습/서빙 코드, 운영 가중치 없음)·Scala·Java 혼합이다. 재사용하는 것은 stage 구조뿐이며 코드·모델을 저장소/런타임에 넣지 않는다(§13 X4 유지).
- **불변**: CLAUDE.md 12개 불변식은 서비스 경계와 무관하게 Spring transaction에서 강제된다. AI 서비스는 trip을 수정할 수 없고(DB 없음), APPLY는 Spring의 `TripCommand`만 수행한다. LLM은 여전히 사실 판정자가 아니다.
- **재검토 trigger**: 제출 전 `ai` 서비스 장애로 P0 핵심 흐름이 막히면 Spring 측 fallback(feed 고정 순서, related/slot UNKNOWN, ITEM run FAILED retryable)으로 축소 운영한다. 서비스 분리 비용이 제출을 위협하면 사용자가 D-REC-6을 다시 결정한다.
- **ADR 기록 위치**: `docs/decisions/ARCHITECTURE_DECISIONS.md`에 ADR-0006(또는 ADR-0001 amendment)을 문서 담당(Codex)이 추가한다. 이 계획서는 그 전까지의 결정 원본이다.

## 경계와 내부 계약 v1

| 책임 | `apps/api` (Spring) | `apps/ai` (Python) |
| --- | --- | --- |
| public API·session·CSRF·owner·idempotency·ETag | 소유 | 없음 |
| trip/candidate/item/lock 저장·transaction·revision | 소유 | 없음(입력으로만 받음) |
| KTO/서울 adapter·source registry·incident·snapshot·`TemporalComparisonPolicy` | 소유(BA-020/021/023) | verdict를 입력으로 받음 |
| 추천 계산(feed 순서·related·slot·ITEM proposal·설명) | 호출·fallback·재검증 | 소유 |
| 정책 `policy-v1.yaml`·policyHash·REC safety corpus·evaluation.json | policyHash를 fingerprint에 기록 | 소유 |
| LLM provider(P1, OpenAI) | capability flag·readiness | adapter·validator(`AI_PROVIDER`) |
| cursor 서명·feed snapshot 저장·hidden/saved 상태 | 소유(BA-032) | 없음 |

내부 계약 v1(`apps/ai/contracts/recommendation-internal-v1.json`, `/internal/openapi.json`에서 export, Spring `InternalContractParityTest`가 DTO 필드 parity 검사):

| operation | 입력(Spring hydrate) | 출력 | Spring fallback |
| --- | --- | --- | --- |
| `GET /internal/v1/health/live`, `/ready` | — | HealthStatus/ReadinessStatus | readiness probe `recommendation`(optional → DEGRADED) |
| `GET /internal/v1/policy` | — | policyVersion/policyHash/pipelineVersion/serviceVersion | 캐시된 마지막 값; 없으면 run 생성 거부 |
| `POST /internal/v1/feed/rank` (있음) | evaluatedAt, locale, sortVersion, 공개 post 후보(postId, publishedAt, status, primaryPlaceId) | orderedPostIds, rejectedByReason, policyHash | Spring `FeedOrdering`(publishedAt DESC, postId ASC) |
| `POST /internal/v1/related/rank` (Task 7) | source place·category, relation evidence rows, target categories, lookupOutcome | state EXACT/SIMILAR/NONE/CHECKING/UNKNOWN + items | `UNKNOWN` + reason |
| `POST /internal/v1/slots/evaluate` (Task 6) | trip 범위·zone·후보 place·items·opening hours·duplicates·routeEvidence·checking | state + slots[date, suggestedTime=null, eligible, reasonCode] | `UNKNOWN` |
| `POST /internal/v1/items/propose` (Task 5) | target item·locks·neighbours·opening hours·routeEvidence·temporal candidates(verdict 포함) | outcome + proposals(≤3) + rejectedByReason + policyHash | run 재시도 후 FAILED(D-REC-11) |
| `POST /internal/v1/explanations/render` (Task 8) | ExplanationFacts allowlist | summary text, source TEMPLATE/LLM | template를 Spring이 보관하지 않음 → 요청 실패 시 proposal summary는 KO/EN 고정 문구 |

요청은 `X-Request-ID`를 전파하고 owner/session ID·raw itinerary·좌표·hidden/saved 상태를 포함하지 않는다. 응답의 place/post ID는 요청에 있던 것만 허용하며 Spring이 검증한다(새 ID를 임의 생성하지 않음, §3.1 Final check).

## docs 변경 요청 (2026-09-07 반영됨)

2026-09-07 반영: 1~6, 8번 문서를 갱신했다(ADR-0006, `DECISIONS_AND_RISKS` A-022·D-031·D-032, CI 등록 규칙은 `AGENTS.md#ci-검사-등록`). 7번은 Task 11, 8번의 실제 infra 코드는 Task 12에서 구현한다.

1. `docs/decisions/ARCHITECTURE_DECISIONS.md`: ADR-0006 "추천 계산 서비스 분리(apps/ai)" 추가 — context(사용자 결정, x-algorithm 사실), decision(경계 표), consequences(두 runtime·gate·fallback), rejected(Java 단일), review trigger.
2. `docs/architecture/SYSTEM_ARCHITECTURE.md` §3 저장소 구조에 `apps/ai/`, §4·§14~§16의 `recommendation`을 "Spring gateway port + apps/ai 서비스"로, §12 장애 표에 `추천 서비스` 행 추가.
3. `docs/engineering/LOCAL_DEVELOPMENT.md` B01 toolchain 표에 Python 3.13/uv 행, 명령 표에 `apps/ai` 실행·검증, 포트 표에 `8090`.
4. `docs/operations/ENVIRONMENT.md`: Spring `NULLNULL_AI_BASE_URL`, `NULLNULL_AI_CONNECT_TIMEOUT`(PT2S), `NULLNULL_AI_READ_TIMEOUT`(PT5S), `NULLNULL_CURSOR_SECRET`; 서비스 `NULLNULL_ENV`, `NULLNULL_AI_BIND_HOST`, `NULLNULL_AI_PORT`(8090), `NULLNULL_CATALOG_VERSION`(staging/production 필수, 없으면 startup 실패); `AI_PROVIDER` enum에 `OPENAI`(P1) 예정 표기.
5. `docs/engineering/TEST_STRATEGY.md` §12: `recommendationTest`(Gradle)는 계약 parity, REC corpus·`evaluation.json`은 `apps/ai` pytest + compose `ai-quality`로 실행한다고 갱신; artifact 경로 `.artifacts/integration/recommendation-ai/`.
6. `docs/engineering/OWNERSHIP_MATRIX.md`·`BRANCH_AND_INTEGRATION.md`: `apps/ai/**` DRI=BE/AI, 내부 계약 변경은 FE informed(공개 계약 아님).
7. `scripts/verify_target_stack.py`: `apps/ai/Dockerfile` stage `test`/`runtime` 검사 추가(스크립트는 BE/AI 소유, 이 계획 Task 11).
8. `docs/operations/AWS_DEPLOYMENT.md`·`GITHUB_RELEASE_OPERATIONS.md`: ECS service `ai`, ECR repo, SG, 내부 DNS, release manifest `aiImageDigest`(Task 12).

## Global Constraints

- [2026-09-06 계획](2026-09-06-recommendation-p0.md)의 Global Constraints 전부(재현 단위, UNKNOWN 비승격, 점수식·상한·tie-break, 잠금 4종, 영업/duration/route 규칙, 실패 plane, 비교 코드 11개, 관련 장소·slot·feed 규칙, LLM allowlist)를 그대로 상속한다.
- Python 계산 규칙: `decimal.Decimal`만 사용(float 금지), 정책 `numeric.scale/rounding`으로 `policy.quantize`, 비교는 표시 반올림 전. `datetime`은 tz-aware만, `Instant`는 UTC `datetime`, trip 시각은 `zoneinfo.ZoneInfo`로 변환하고 gap/overlap을 거절. `random`·`time.time()`·`datetime.now()`는 `src/nullnull_ai/{domain,feed,item,slot,related,explain,pipeline}`에서 금지(테스트 `test_purity.py`가 AST로 검사).
- 서비스 규칙: 모든 route에 `response_model`, 모든 model `extra="forbid"`, 요청/응답에 owner·session·raw text·좌표 없음, `X-Request-ID` 전파, Problem code는 `INVALID_REQUEST|VALIDATION_FAILED|NOT_FOUND|INTERNAL_ERROR|SORT_VERSION_UNSUPPORTED|POLICY_MISMATCH`. 계약 변경은 `uv run python -m nullnull_ai.contracts export` 후 Spring parity 테스트를 같은 PR에서 통과.
- Spring 규칙: `RecommendationGateway` 호출은 DB transaction 밖, connect/read timeout·bounded retry(GET/idempotent만)·circuit, 응답 ID는 요청 집합 안에서만 허용, 저장 전 `trip` domain 검증(잠금·범위·version) 재실행. 서비스 불가 시 fallback 표를 따르고 `SOURCE_UNAVAILABLE`류로 숨기지 않는다.
- 테스트: 각 task는 `apps/ai/tests/recommendation/manifest.json`의 `implementedTestIds`에 REC ID를 추가하고, property test는 `manifest.randomSeeds` 기반 `random.Random(seed)`로 1,000 case. `evaluation.json`은 pytest가 쓰고 compose `ai-quality`가 artifact로 수집한다. 실행하지 못한 검사는 통과로 쓰지 않는다.
- OpenAPI 0.2.x·ERD·event schema는 바꾸지 않는다. 계약 공백은 D-REC-* 표와 REC-CON-01~08로 추적한다.
- git: `backend` 브랜치, `feat(ai):`/`feat(be):`/`test(ai):` conventional commit. commit checkpoint는 **사용자 지시가 있을 때만**.

## 전제 조건 (main PR 전에 B01로 닫을 항목)

**`apps/api`가 있고 `.nullnull-target-stack`이 없으면 `scripts/integration-test.sh`가 즉시 실패하고(`integration.yml`이 모든 main PR에서 실행), 이것은 baseline-only가 아니라 merge blocker다.** `apps/web` scaffold + marker가 같은 PR에 들어오기 전에는 `backend → main`을 merge할 수 없다. 로컬 검증은 `./gradlew`·`uv run pytest`·Docker 이미지로 한다.

- [x] `apps/api` Dockerfile offline test stage·compose `api-quality`(`--offline`, `NULLNULL_TEST_DATABASE=external`)·image digest pin (2026-09-06)
- [x] `apps/ai` Dockerfile(test/runtime), compose `ai-quality`·`ai`, wrapper `--profile quality`·ai 단계 (2026-09-07)
- [ ] `apps/web` scaffold + root `package.json` 스크립트 + `.nullnull-target-stack` — Frontend 인계물
- [ ] `scripts/verify_target_stack.py`에 `apps/ai/Dockerfile` stage 검사 추가 (Task 11)
- [ ] `integration-test.sh` readiness 루프에 `ai` 준비 확인 추가 (Task 11)
- [ ] `apps/ai` 배포 경로(ECR repo, ECS service `ai`, security group api→ai:8090, 내부 DNS, `NULLNULL_AI_BASE_URL`·`NULLNULL_CATALOG_VERSION` 주입, 비용) — Task 12. 제출 전에 없으면 제출 빌드는 fallback-only(related/slot UNKNOWN, ITEM run FAILED)이며 D-REC-15로 사용자 결정이다.

## 실행 명령

```bash
# Python
cd apps/ai && export PATH="$PWD/.uv-bootstrap/bin:$PATH"     # 최초: python3.13 -m venv .uv-bootstrap && .uv-bootstrap/bin/pip install uv==0.12.10
uv sync --frozen
uv run ruff check . && uv run ruff format --check . && uv run mypy
NULLNULL_AI_REPORT_DIR=build/reports/recommendation uv run pytest
uv run python -m nullnull_ai.contracts export        # endpoint/schema 변경 뒤

# Java
cd apps/api && export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
./gradlew --no-daemon test recommendationTest         # DTO parity 포함
./gradlew --no-daemon check                           # Docker 필요

# 통합(둘 다 이미지로)
docker build -f apps/ai/Dockerfile --target test -t nullnull-ai:test . && docker run --rm --network none nullnull-ai:test
```

## File Structure

```text
apps/ai/src/nullnull_ai/
  domain/{types,policy}.py (기존)  domain/time.py (Task 4)  domain/purity 규칙은 tests/test_purity.py
  pipeline/{stages,runner}.py (기존)
  feed/pipeline.py (기존)
  item/types.py  item/score.py (Task 2)  item/filters.py (Task 4)  item/evaluator.py (Task 5)
  slot/evaluator.py (Task 6)
  related/ranker.py (Task 7)
  explain/{facts,templates,validator,ports,service}.py (Task 8)
  api/schemas.py (기존, task마다 model 추가)  api/{items,slots,related,explanations}.py (Task 5~8)
  evaluation/{fixtures,invariants,report}.py (Task 9)
apps/ai/tests/  test_*.py, recommendation/{manifest.json, fixtures/*.json, test_item_fixtures.py}
apps/ai/contracts/recommendation-internal-v1.json (export 산출물)
apps/api/src/main/java/io/nullnull/
  recommendation/domain/{PolicyDescriptor, feed/*} (기존)  recommendation/domain/{item,slot,related}/* (Task 5~7 DTO)
  recommendation/application/RecommendationGateway.java (기존)  recommendation/application/{FeedFallback,RecommendationUnavailableException}.java (Task 1)
  recommendation/infrastructure/{HttpRecommendationGateway,RecommendationClientProperties,RecommendationServiceProbe}.java (Task 1)
  crowd/domain/{SourceState,QualityFlag,ComparisonScope,CrowdPoint,ComparisonVerdict,ComparisonReasonCode,TemporalComparisonPolicy}.java (Task 3)
  recommendation/application/RunFingerprint.java (Task 10)  shared/cursor/* + social/domain/FeedOrdering.java (Task 10)
```

의존 순서: Task 1(Spring gateway)·Task 2·Task 3은 독립. Task 4 → 5 → (6, 9). Task 7, 8 독립. Task 10은 5·6·7 뒤. Task 11은 마지막.

---

### Task 1: Spring `HttpRecommendationGateway`, fallback, readiness probe (Java)

**Files:**

- Create: `apps/api/src/main/java/io/nullnull/recommendation/application/RecommendationUnavailableException.java`, `FeedFallback.java`
- Create: `apps/api/src/main/java/io/nullnull/recommendation/infrastructure/RecommendationClientProperties.java`, `HttpRecommendationGateway.java`, `RecommendationServiceProbe.java`
- Modify: `apps/api/src/main/resources/application.yaml` (`nullnull.ai.base-url`, `nullnull.ai.connect-timeout`, `nullnull.ai.read-timeout`), `application-local.yaml` (`base-url: http://127.0.0.1:8090`), `application-integration.yaml` (`base-url: ${NULLNULL_AI_BASE_URL}`), `compose.integration.yml` api/api-quality env `NULLNULL_AI_BASE_URL: http://ai:8090`
- Test: `apps/api/src/test/java/io/nullnull/recommendation/infrastructure/HttpRecommendationGatewayTest.java`, `apps/api/src/test/java/io/nullnull/recommendation/application/FeedFallbackTest.java`

**Interfaces:**

- Consumes: `RecommendationGateway`, `PolicyDescriptor`, `FeedRankRequest/Response`(기존).
- Produces: `HttpRecommendationGateway implements RecommendationGateway` (Spring `RestClient`, `X-Request-ID` 전파, 4xx→`RecommendationUnavailableException(retryable=false)` + 자체 hydration 버그 alert, 5xx/IO→`retryable=true`, bounded retry); `List<UUID> FeedFallback.order(List<FeedCandidateIn>, Instant evaluatedAt, int cap)`(서비스와 같은 필터·µs 정밀도·`postId.toString()` tie-break); `RecommendationServiceProbe implements ReadinessProbe`(`required()=false`, **전용 `RestClient` connect/read 1초**, `/internal/v1/health/ready` 200 → READY, else UNAVAILABLE → 전체 DEGRADED; ALB health check가 gateway timeout에 묶이지 않도록).
- feed 순서 parity 테스트(`FeedFallbackTest`): `apps/ai/tests/recommendation/fixtures/feed-order-cases.json`(Task 9에서 생성)을 두 언어가 같은 파일로 소비해 동일 순서를 단언한다.

- [ ] **Step 1: 실패하는 gateway 테스트**

```java
// apps/api/src/test/java/io/nullnull/recommendation/infrastructure/HttpRecommendationGatewayTest.java
package io.nullnull.recommendation.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.nullnull.recommendation.application.RecommendationUnavailableException;
import io.nullnull.recommendation.domain.feed.FeedCandidateIn;
import io.nullnull.recommendation.domain.feed.FeedRankRequest;
import io.nullnull.recommendation.domain.feed.FeedRankResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpRecommendationGatewayTest {

    static final String BODY = """
            {"policyVersion":"policy-v1","policyHash":"%s","pipelineVersion":"nullnull-ai-pipeline-v1","sortVersion":1,
             "evaluated":2,"orderedPostIds":["00000000-0000-0000-0000-000000000001"],"rejectedByReason":{"NOT_PUBLISHED":1},
             "stageCounts":[{"stage":"source:request","inputCount":0,"outputCount":2}]}
            """.formatted("a".repeat(64));

    MockRestServiceServer server;
    HttpRecommendationGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ai.test:8090");
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new HttpRecommendationGateway(builder.build(),
                new RecommendationClientProperties("http://ai.test:8090", Duration.ofSeconds(1), Duration.ofSeconds(2)),
                () -> "req_test-0001");
    }

    @Test
    void postsCamelCaseBodyAndPropagatesRequestId() {
        server.expect(requestTo("http://ai.test:8090/internal/v1/feed/rank")).andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Request-ID", "req_test-0001"))
                .andExpect(jsonPath("$.sortVersion").value(1))
                .andExpect(jsonPath("$.candidates[0].postId").value("00000000-0000-0000-0000-000000000001"))
                .andExpect(jsonPath("$.candidates[0].primaryPlaceId").value("018f3f8e-9b67-7a21-8d31-31d315b93a01"))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));
        FeedRankResponse response = gateway.rankFeed(new FeedRankRequest(Instant.parse("2026-09-06T00:00:00Z"), "ko", 1, List.of(
                new FeedCandidateIn(UUID.fromString("00000000-0000-0000-0000-000000000001"), Instant.parse("2026-09-05T00:00:00Z"),
                        FeedCandidateIn.PostStatus.PUBLISHED, UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93a01")))));
        assertThat(response.orderedPostIds()).containsExactly(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        assertThat(response.policyHash()).hasSize(64);
        server.verify();
    }

    @Test
    void serverErrorIsRetryableUnavailable() {
        server.expect(requestTo("http://ai.test:8090/internal/v1/policy")).andRespond(withServerError());
        assertThatThrownBy(gateway::policy).isInstanceOf(RecommendationUnavailableException.class)
                .satisfies(e -> assertThat(((RecommendationUnavailableException) e).retryable()).isTrue());
    }

    @Test
    void responseIdsOutsideTheRequestAreRejected() {
        server.expect(requestTo("http://ai.test:8090/internal/v1/feed/rank")).andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));
        FeedRankRequest request = new FeedRankRequest(Instant.parse("2026-09-06T00:00:00Z"), "ko", 1, List.of(
                new FeedCandidateIn(UUID.fromString("00000000-0000-0000-0000-000000000009"), Instant.parse("2026-09-05T00:00:00Z"),
                        FeedCandidateIn.PostStatus.PUBLISHED, UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93a01"))));
        assertThatThrownBy(() -> gateway.rankFeed(request)).isInstanceOf(RecommendationUnavailableException.class)
                .hasMessageContaining("not in the request");
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew --no-daemon test --tests 'io.nullnull.recommendation.infrastructure.*'` → 컴파일 실패.

- [ ] **Step 3: 구현**

```java
// apps/api/src/main/java/io/nullnull/recommendation/application/RecommendationUnavailableException.java
package io.nullnull.recommendation.application;

/** The recommendation service could not serve a valid answer. Callers apply the documented fallback; never a silent default. */
public class RecommendationUnavailableException extends RuntimeException {
    private final boolean retryable;

    public RecommendationUnavailableException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}

// apps/api/src/main/java/io/nullnull/recommendation/application/FeedFallback.java
package io.nullnull.recommendation.application;

import io.nullnull.recommendation.domain.feed.FeedCandidateIn;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** §5.1 fixed order computed in Spring when apps/ai is unavailable. */
public final class FeedFallback {

    private FeedFallback() {
    }

    /**
     * Same filters and tie-break as the service (PublishedFilter, CanonicalPlaceFilter, FixedOrderScorer at
     * microsecond precision, postId compared as its canonical string — {@code UUID.compareTo} is signed and
     * would diverge from the service for non-v7 ids).
     */
    public static List<UUID> order(List<FeedCandidateIn> candidates, Instant evaluatedAt, int cap) {
        return candidates.stream()
                .filter(c -> c.status() == FeedCandidateIn.PostStatus.PUBLISHED && c.publishedAt() != null
                        && !c.publishedAt().isAfter(evaluatedAt) && c.primaryPlaceId() != null)
                .sorted(Comparator.comparing(FeedCandidateIn::publishedAt).reversed()
                        .thenComparing(c -> c.postId().toString()))
                .limit(cap)
                .map(FeedCandidateIn::postId)
                .toList();
    }
}

// apps/api/src/main/java/io/nullnull/recommendation/infrastructure/RecommendationClientProperties.java
package io.nullnull.recommendation.infrastructure;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nullnull.ai")
public record RecommendationClientProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {
    public RecommendationClientProperties {
        Objects.requireNonNull(baseUrl, "nullnull.ai.base-url is required");
        Objects.requireNonNull(connectTimeout, "nullnull.ai.connect-timeout is required");
        Objects.requireNonNull(readTimeout, "nullnull.ai.read-timeout is required");
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            throw new IllegalArgumentException("nullnull.ai.base-url must be an http(s) URL");
        }
    }
}

// apps/api/src/main/java/io/nullnull/recommendation/infrastructure/HttpRecommendationGateway.java
package io.nullnull.recommendation.infrastructure;

import io.nullnull.recommendation.application.RecommendationGateway;
import io.nullnull.recommendation.application.RecommendationUnavailableException;
import io.nullnull.recommendation.domain.PolicyDescriptor;
import io.nullnull.recommendation.domain.feed.FeedCandidateIn;
import io.nullnull.recommendation.domain.feed.FeedRankRequest;
import io.nullnull.recommendation.domain.feed.FeedRankResponse;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Internal contract v1 over HTTP. Called outside any DB transaction. Response identifiers are
 * checked against the request so the service can never introduce an id Spring did not hydrate.
 */
public class HttpRecommendationGateway implements RecommendationGateway {

    private final RestClient client;
    private final Supplier<String> requestId;

    public HttpRecommendationGateway(RestClient client, RecommendationClientProperties properties, Supplier<String> requestId) {
        this.client = client;
        this.requestId = requestId;
    }

    @Override
    public PolicyDescriptor policy() {
        try {
            PolicyDescriptor descriptor = client.get().uri("/internal/v1/policy")
                    .header("X-Request-ID", requestId.get()).retrieve().body(PolicyDescriptor.class);
            if (descriptor == null) {
                throw new RecommendationUnavailableException("empty policy response", true, null);
            }
            return descriptor;
        } catch (HttpServerErrorException | ResourceAccessException exception) {
            throw new RecommendationUnavailableException("recommendation service unavailable", true, exception);
        } catch (HttpClientErrorException exception) {
            throw new RecommendationUnavailableException("recommendation request rejected", false, exception);
        }
    }

    @Override
    public FeedRankResponse rankFeed(FeedRankRequest request) {
        FeedRankResponse response;
        try {
            response = client.post().uri("/internal/v1/feed/rank")
                    .header("X-Request-ID", requestId.get())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(FeedRankResponse.class);
        } catch (HttpServerErrorException | ResourceAccessException exception) {
            throw new RecommendationUnavailableException("recommendation service unavailable", true, exception);
        } catch (HttpClientErrorException exception) {
            throw new RecommendationUnavailableException("recommendation request rejected", false, exception);
        }
        if (response == null) {
            throw new RecommendationUnavailableException("empty feed rank response", true, null);
        }
        Set<UUID> requested = new HashSet<>();
        for (FeedCandidateIn candidate : request.candidates()) {
            requested.add(candidate.postId());
        }
        for (UUID postId : response.orderedPostIds()) {
            if (!requested.contains(postId)) {
                throw new RecommendationUnavailableException("service returned a post id not in the request", false, null);
            }
        }
        return response;
    }
}

// apps/api/src/main/java/io/nullnull/recommendation/infrastructure/RecommendationServiceProbe.java
package io.nullnull.recommendation.infrastructure;

import io.nullnull.operations.application.ReadinessProbe;
import java.time.Instant;
import org.springframework.web.client.RestClient;

/** Optional probe: the API stays READY for trips/edits when the recommendation service is down (DEGRADED). */
public class RecommendationServiceProbe implements ReadinessProbe {

    private final RestClient client;

    /** Pass a builder with its own 1-second connect/read timeouts; never the gateway client. */
    public RecommendationServiceProbe(RestClient probeClient) {
        this.client = probeClient;
    }

    @Override
    public String name() {
        return "recommendation";
    }

    @Override
    public boolean required() {
        return false;
    }

    @Override
    public ProbeResult probe(Instant checkedAt) {
        try {
            client.get().uri("/internal/v1/health/ready").retrieve().toBodilessEntity();
            return new ProbeResult(ProbeStatus.READY, checkedAt, null);
        } catch (RuntimeException exception) {
            return new ProbeResult(ProbeStatus.UNAVAILABLE, checkedAt, "recommendation service unreachable");
        }
    }
}
```

Spring 배선(`recommendation/infrastructure/RecommendationClientConfiguration.java`): `@Configuration @EnableConfigurationProperties(RecommendationClientProperties.class)`에서 `RestClient.Builder`(Boot auto-config)에 `baseUrl`·`ClientHttpRequestFactory` timeouts(`JdkClientHttpRequestFactory` `setReadTimeout`, `HttpClient.connectTimeout`)를 적용해 `HttpRecommendationGateway`·`RecommendationServiceProbe` bean을 만든다. `requestId` supplier는 현재 요청의 `RequestIdFilter.current(...)`를 `RequestContextHolder`로 읽고, worker(job) 컨텍스트에서는 run id 기반 값을 넘긴다.

`application.yaml`:

```yaml
nullnull:
  ai:
    base-url: ${NULLNULL_AI_BASE_URL}
    connect-timeout: ${NULLNULL_AI_CONNECT_TIMEOUT:PT2S}
    read-timeout: ${NULLNULL_AI_READ_TIMEOUT:PT5S}
```

`application-local.yaml`에 `nullnull.ai.base-url: http://127.0.0.1:8090`, compose `api`/`api-quality` env에 `NULLNULL_AI_BASE_URL: http://ai:8090`(api-quality의 `SystemEndpointsIT`는 probe가 optional이므로 ai 없이도 READY가 아니라 **DEGRADED**를 기대하도록 갱신).

- [ ] **Step 4: 통과 확인 + ArchUnit** — Run: `./gradlew --no-daemon test` → PASS. `ArchitectureRulesTest`는 `recommendation.infrastructure`의 Spring/HTTP 사용을 허용하고 domain/application은 여전히 순수여야 한다.

- [ ] **Step 5: readiness 통합 테스트 갱신** — `SystemEndpointsIT.readinessReportsDatabaseReady`를 `checks`에 `database=READY`, `recommendation=UNAVAILABLE`, 전체 `DEGRADED`로 바꾸고 `integrationTest`를 실행한다(Testcontainers만 있고 ai 없음). compose 경로에서는 `ai` 서비스가 있으므로 `READY`가 되며, 이 차이는 `docker-integration` 증거에 기록한다.

- [ ] **Step 6: commit checkpoint (사용자 승인 시)**

```bash
git add apps/api/src/main/java/io/nullnull/recommendation apps/api/src/main/resources apps/api/src/test/java/io/nullnull/recommendation apps/api/src/integrationTest compose.integration.yml
git commit -m "feat(be): HTTP gateway to the recommendation service with fixed-order feed fallback"
```

---

### Task 2: ITEM 점수식·tie-break (Python, REC-OPT-02)

**Files:**

- Create: `apps/ai/src/nullnull_ai/item/__init__.py`, `item/types.py`, `item/score.py`
- Test: `apps/ai/tests/item/test_score.py`, `apps/ai/tests/item/test_ordering.py`

**Interfaces:**

- Produces: `ItemScorePolicy(policy).evaluate(metric_code, verdict, shift) -> Admitted | Rejected`; `TemporalShift(before_value, after_value, before_instant, after_instant)` with `shift_minutes()`; `ScoredCandidate(key, proposed_start_time, admission)`; `proposal_sort_key(scored) -> tuple` (score DESC, changeCost ASC, date ASC, time ASC with None first, placeId ASC).
- 기대값은 [Java 계획 Task 1](2026-09-06-recommendation-p0.md#task-1-item-점수식과-고정-tie-break-itemscorepolicy-itemproposalordering)의 손계산과 같다: A 0.110000, B 0.140000, C 최소 개선 미달, D 비교 불가, 24h 0.200000, 5/24h `SCORE_NOT_POSITIVE`.

- [ ] **Step 1: 타입과 실패하는 테스트**

```python
# apps/ai/src/nullnull_ai/item/types.py
"""ITEM optimization input/output types (RECOMMENDATION_ALGORITHM.md §5.4)."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, time
from decimal import Decimal
from enum import Enum
from typing import Mapping
from uuid import UUID
from zoneinfo import ZoneInfo

from nullnull_ai.domain.types import CandidateKey


class LockType(Enum):
    MUST_VISIT = "MUST_VISIT"
    DATE = "DATE"
    TIME = "TIME"
    RESERVATION = "RESERVATION"


@dataclass(frozen=True, slots=True)
class MustVisitLock:
    type: LockType = LockType.MUST_VISIT


@dataclass(frozen=True, slots=True)
class DateLock:
    date: date
    type: LockType = LockType.DATE


@dataclass(frozen=True, slots=True)
class TimeLock:
    start_time: time
    tolerance_minutes: int
    type: LockType = LockType.TIME

    def __post_init__(self) -> None:
        if not 0 <= self.tolerance_minutes <= 180:
            raise ValueError("toleranceMinutes must be 0..180")


@dataclass(frozen=True, slots=True)
class ReservationLock:
    date: date
    start_time: time
    end_time: time | None
    type: LockType = LockType.RESERVATION

    def __post_init__(self) -> None:
        if self.end_time is not None and self.end_time < self.start_time:
            raise ValueError("endTime before startTime")


ItemLock = MustVisitLock | DateLock | TimeLock | ReservationLock


@dataclass(frozen=True, slots=True)
class TargetItem:
    item_id: UUID
    place_id: UUID
    date: date
    start_time: time | None
    duration_minutes: int | None
    position: int

    def __post_init__(self) -> None:
        if self.duration_minutes is not None and self.duration_minutes <= 0:
            raise ValueError("durationMinutes must be positive when present")


@dataclass(frozen=True, slots=True)
class NeighbourItem:
    item_id: UUID
    date: date
    position: int
    start_time: time | None
    duration_minutes: int | None


@dataclass(frozen=True, slots=True)
class OpenWindow:
    opens_at: time
    closes_at: time

    def __post_init__(self) -> None:
        if self.closes_at <= self.opens_at:
            raise ValueError("closesAt must be after opensAt")


@dataclass(frozen=True, slots=True)
class Closed:
    pass


@dataclass(frozen=True, slots=True)
class UnknownHours:
    pass


OpeningWindow = OpenWindow | Closed | UnknownHours


class RouteEvidence(Enum):
    NONE = "NONE"
    VERIFIED = "VERIFIED"


class ForecastResolution(Enum):
    DAY = "DAY"
    HOUR = "HOUR"


@dataclass(frozen=True, slots=True)
class ComparisonVerdict:
    """Computed by Spring's TemporalComparisonPolicy; reason_code is one of SOURCE_CATALOG.md §9."""

    eligible: bool
    reason_code: str


@dataclass(frozen=True, slots=True)
class TemporalCandidate:
    key: CandidateKey
    resolution: ForecastResolution
    before_value: Decimal
    after_value: Decimal
    metric_code: str
    verdict: ComparisonVerdict
    before_snapshot_id: UUID
    after_snapshot_id: UUID

    def __post_init__(self) -> None:
        if self.key.date is None:
            raise ValueError("temporal candidate needs a date")
        if self.resolution is ForecastResolution.DAY and self.key.time is not None:
            raise ValueError("DAY resolution candidates must not carry a time (§5.4)")


@dataclass(frozen=True, slots=True)
class ItemOptimizationInput:
    trip_id: UUID
    trip_version: int
    trip_start: date
    trip_end: date
    trip_zone: ZoneInfo
    target: TargetItem
    locks: tuple[ItemLock, ...]
    neighbours: tuple[NeighbourItem, ...]
    opening_hours: Mapping[date, OpeningWindow]
    route_evidence: RouteEvidence
    candidates: tuple[TemporalCandidate, ...]

    def __post_init__(self) -> None:
        if self.trip_end < self.trip_start:
            raise ValueError("tripEnd before tripStart")
        if len({lock.type for lock in self.locks}) != len(self.locks):
            raise ValueError("at most one lock per type")
```

```python
# apps/ai/src/nullnull_ai/item/score.py
"""§5.5 objective for same-POI temporal candidates. Fixed-point Decimal at policy scale."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal

from nullnull_ai.domain.policy import RecommendationPolicy
from nullnull_ai.domain.types import CandidateKey, Reason, ScoreBreakdown
from nullnull_ai.item.types import ComparisonVerdict

COMPARISON_INELIGIBLE = "COMPARISON_INELIGIBLE"
IMPROVEMENT_BELOW_MINIMUM = "IMPROVEMENT_BELOW_MINIMUM"
SCORE_NOT_POSITIVE = "SCORE_NOT_POSITIVE"
METRIC_POLICY_MISSING = "METRIC_POLICY_MISSING"


@dataclass(frozen=True, slots=True)
class TemporalShift:
    before_value: Decimal
    after_value: Decimal
    before_instant: datetime
    after_instant: datetime

    def shift_minutes(self) -> int:
        return abs(int((self.after_instant - self.before_instant).total_seconds()) // 60)


@dataclass(frozen=True, slots=True)
class Admitted:
    score: ScoreBreakdown
    improvement: Decimal
    relief: Decimal
    change_cost: Decimal


@dataclass(frozen=True, slots=True)
class Rejected:
    reason: Reason


Admission = Admitted | Rejected


class ItemScorePolicy:
    def __init__(self, policy: RecommendationPolicy) -> None:
        self._policy = policy

    def evaluate(self, metric_code: str, verdict: ComparisonVerdict, shift: TemporalShift) -> Admission:
        policy = self._policy
        metric = policy.metrics.get(metric_code)
        if metric is None:
            return Rejected(Reason(METRIC_POLICY_MISSING, f"no scale/minimum for metric {metric_code}"))
        objective = policy.item_objective
        if objective.require_comparison_eligible and not verdict.eligible:
            return Rejected(Reason(COMPARISON_INELIGIBLE, verdict.reason_code))
        improvement = shift.before_value - shift.after_value
        if improvement < metric.minimum_improvement:
            return Rejected(Reason(IMPROVEMENT_BELOW_MINIMUM, f"improvement below policy minimum for {metric_code}"))
        relief = policy.quantize(improvement / metric.metric_scale)
        change_cost = policy.quantize(
            min(Decimal(1), Decimal(shift.shift_minutes()) / Decimal(objective.change_cost_saturation_minutes))
        )
        relief_term = policy.quantize(objective.relief_weight * relief)
        change_cost_term = policy.quantize(objective.change_cost_weight * change_cost)
        score = policy.quantize(relief_term - change_cost_term)
        if objective.require_score_positive and score <= 0:
            return Rejected(Reason(SCORE_NOT_POSITIVE, "score is not positive"))
        breakdown = ScoreBreakdown(
            score,
            (("relief", relief), ("changeCost", change_cost), ("reliefTerm", relief_term), ("changeCostTerm", change_cost_term)),
        )
        return Admitted(breakdown, improvement, relief, change_cost)


@dataclass(frozen=True, slots=True)
class ScoredCandidate:
    key: CandidateKey
    proposed_start_time: object  # datetime.time | None; kept loose to avoid a circular import in the sort key
    admission: Admitted


def proposal_sort_key(scored: ScoredCandidate) -> tuple[Decimal, Decimal, object, int, object, str]:
    """score DESC → changeCost ASC → date ASC → time ASC (date-only first) → placeId ASC (§5.5)."""
    time_value = scored.proposed_start_time
    return (
        -scored.admission.score.score,
        scored.admission.change_cost,
        scored.key.date,
        0 if time_value is None else 1,
        time_value if time_value is not None else 0,
        str(scored.key.place_id),
    )
```

`proposed_start_time`은 `datetime.time | None`으로 선언한다(위 주석은 제거하고 `from datetime import time`을 import). `tuple` 요소 `date`/`time`은 비교 가능하며 `None`은 `(0, 0)`으로 먼저 온다.

```python
# apps/ai/tests/item/test_score.py
from __future__ import annotations

from datetime import UTC, datetime, timedelta
from decimal import Decimal

import pytest

from nullnull_ai.domain.policy import load_default
from nullnull_ai.item.score import Admitted, ItemScorePolicy, Rejected, TemporalShift
from nullnull_ai.item.types import ComparisonVerdict

METRIC = "KTO_RELATIVE_CONCENTRATION_INDEX"
BEFORE = datetime(2026, 9, 12, 1, 0, tzinfo=UTC)
OK = ComparisonVerdict(True, "SAME_METRIC_AND_ISSUE")
policy = ItemScorePolicy(load_default())


def shift(before: int, after: int, minutes: int) -> TemporalShift:
    return TemporalShift(Decimal(before), Decimal(after), BEFORE, BEFORE + timedelta(minutes=minutes))


@pytest.mark.parametrize(
    ("before", "after", "minutes", "score", "relief", "cost"),
    [(80, 60, 60, "0.110000", "0.200000", "0.250000"), (80, 50, 120, "0.140000", "0.300000", "0.500000"),
     (80, 30, 24 * 60, "0.200000", "0.500000", "1.000000")],
)
def test_golden_examples(before: int, after: int, minutes: int, score: str, relief: str, cost: str) -> None:
    admission = policy.evaluate(METRIC, OK, shift(before, after, minutes))
    assert isinstance(admission, Admitted)
    assert admission.score.score == Decimal(score)
    assert admission.relief == Decimal(relief) and admission.change_cost == Decimal(cost)
    assert [name for name, _ in admission.score.contributions] == ["relief", "changeCost", "reliefTerm", "changeCostTerm"]


@pytest.mark.parametrize(
    ("verdict", "before", "after", "minutes", "code"),
    [(OK, 80, 77, 15, "IMPROVEMENT_BELOW_MINIMUM"), (ComparisonVerdict(False, "DIFFERENT_FORECAST_ISSUE"), 80, 20, 60, "COMPARISON_INELIGIBLE"),
     (OK, 80, 75, 24 * 60, "SCORE_NOT_POSITIVE"), (OK, 80, 76, 0, "IMPROVEMENT_BELOW_MINIMUM")],
)
def test_rejections(verdict: ComparisonVerdict, before: int, after: int, minutes: int, code: str) -> None:
    admission = policy.evaluate(METRIC, verdict, shift(before, after, minutes))
    assert isinstance(admission, Rejected) and admission.reason.code == code


def test_boundary_minimum_improvement_exactly_five_is_admitted() -> None:
    assert isinstance(policy.evaluate(METRIC, OK, shift(80, 75, 0)), Admitted)


def test_unknown_metric_is_rejected() -> None:
    admission = policy.evaluate("SEOUL_LIVE_LEVEL", OK, shift(80, 60, 60))
    assert isinstance(admission, Rejected) and admission.reason.code == "METRIC_POLICY_MISSING"
```

```python
# apps/ai/tests/item/test_ordering.py
from __future__ import annotations

import random
from datetime import date, time
from decimal import Decimal
from uuid import UUID

from nullnull_ai.domain.types import CandidateKey, ScoreBreakdown
from nullnull_ai.item.score import Admitted, ScoredCandidate, proposal_sort_key

P1 = UUID("018f3f8e-9b67-7a21-8d31-31d315b93a01")
P2 = UUID("018f3f8e-9b67-7a21-8d31-31d315b93a02")


def scored(place: UUID, day: str, at: time | None, score: str, cost: str) -> ScoredCandidate:
    admitted = Admitted(ScoreBreakdown(Decimal(score)), Decimal(10), Decimal(1), Decimal(cost))
    return ScoredCandidate(CandidateKey(place, date.fromisoformat(day), at), at, admitted)


def test_orders_by_score_cost_date_time_place() -> None:
    expected = [
        scored(P1, "2026-09-13", time(10, 0), "0.140000", "0.500000"),
        scored(P1, "2026-09-13", time(10, 0), "0.110000", "0.250000"),
        scored(P1, "2026-09-13", time(10, 0), "0.110000", "0.300000"),
        scored(P1, "2026-09-12", None, "0.100000", "0.250000"),
        scored(P1, "2026-09-12", time(9, 0), "0.100000", "0.250000"),
        scored(P2, "2026-09-12", time(9, 0), "0.100000", "0.250000"),
    ]
    shuffled = list(expected)
    random.Random(20260906).shuffle(shuffled)
    assert sorted(shuffled, key=proposal_sort_key) == expected
```

- [ ] **Step 2: 실행** — `uv run pytest tests/item -q` → PASS; `uv run mypy` → 통과(`proposed_start_time: time | None`로 선언).
- [ ] **Step 3: manifest** — `implementedTestIds`에 `{"id": "REC-OPT-02", "suite": "pytest", "path": "tests/item/test_score.py"}` 추가.

```bash
git add apps/ai/src/nullnull_ai/item apps/ai/tests/item apps/ai/tests/recommendation/manifest.json
git commit -m "feat(ai): ITEM objective score policy and fixed tie-break (REC-OPT-02)"
```

---

### Task 3: pair 비교 적격성 정책 (Java `crowd.domain`, REC-DATA-02)

Spring이 snapshot·registry·incident를 소유하므로 verdict는 Spring에서 계산해 요청에 실어 보낸다. 코드·테스트는 [Java 계획 Task 2](2026-09-06-recommendation-p0.md#task-2-pair-비교-적격성-정책-crowddomaintemporalcomparisonpolicy)를 **그대로** 구현한다(reviewer 반영판: drift/skew/partial → `MISSING_PROVENANCE`, registry/normalization 불일치 → `DIFFERENT_SOURCE`, 1,000 case property). `ComparisonVerdict`는 `io.nullnull.crowd.domain`에 두고, Task 5의 Spring DTO `TemporalCandidateIn`이 `eligible/reasonCode`로 직렬화한다.

- [ ] Step 1~6: 위 계획의 Step 1~6 실행 후 `./gradlew --no-daemon test` PASS, manifest는 `apps/ai/tests/recommendation/manifest.json`에 `{"id": "REC-DATA-02", "suite": "gradle:test", "path": "apps/api/src/test/java/io/nullnull/crowd/domain/TemporalComparisonPolicyTest.java"}`로 기록한다(서비스 경계를 넘는 REC ID는 어느 suite가 실행하는지 명시).

```bash
git add apps/api/src/main/java/io/nullnull/crowd apps/api/src/test/java/io/nullnull/crowd apps/ai/tests/recommendation/manifest.json
git commit -m "feat(be): temporal comparison eligibility policy (REC-DATA-02)"
```

---

### Task 4: trip timezone 변환·잠금·hard filter (Python, REC-SLOT-01/03)

**Files:**

- Create: `apps/ai/src/nullnull_ai/domain/time.py`, `apps/ai/src/nullnull_ai/item/filters.py`
- Test: `apps/ai/tests/test_time.py`, `apps/ai/tests/item/test_filters.py`, `apps/ai/tests/item/test_locks.py`

**Interfaces:**

- Produces: `resolve(day, at, zone) -> Exact(instant) | Gap | Overlap`; `lock_checks(locks, proposed_date, proposed_time, duration_minutes) -> LockResult(passed: Mapping[LockType, bool], eligibility)`; `same_place`, `not_unchanged`, `within_trip_range`, `opening_hours(window, start, duration)`, `neighbour_overlap(neighbours, target_item_id, day, start, duration)`, `route_evidence(neighbours, target_item_id, from_date, to_date, evidence)` — 모두 `Eligibility` 반환. 규칙·reason code·기대값은 [Java 계획 Task 3](2026-09-06-recommendation-p0.md#task-3-trip-timezone-변환-item-입력-타입-hard-filter)(reviewer 반영판: duration 결측 `DURATION_UNKNOWN`/`NEIGHBOUR_DURATION_UNKNOWN`, RESERVATION은 날짜·시작 시각 고정 + 체류가 `end_time` 안)과 동일.

- [ ] **Step 1: `time.py` 테스트와 구현**

```python
# apps/ai/tests/test_time.py
from __future__ import annotations

from datetime import UTC, date, datetime, time
from zoneinfo import ZoneInfo

from nullnull_ai.domain.time import Exact, Gap, Overlap, resolve


def test_seoul_noon_is_0300_utc() -> None:
    assert resolve(date(2026, 9, 12), time(12, 0), ZoneInfo("Asia/Seoul")) == Exact(datetime(2026, 9, 12, 3, 0, tzinfo=UTC))


def test_spring_forward_gap_is_rejected() -> None:
    assert isinstance(resolve(date(2026, 3, 8), time(2, 30), ZoneInfo("America/New_York")), Gap)


def test_fall_back_overlap_is_rejected_not_guessed() -> None:
    assert isinstance(resolve(date(2026, 11, 1), time(1, 30), ZoneInfo("America/New_York")), Overlap)


def test_midnight_when_no_time() -> None:
    assert resolve(date(2026, 9, 12), None, ZoneInfo("Asia/Seoul")) == Exact(datetime(2026, 9, 11, 15, 0, tzinfo=UTC))


def test_result_does_not_depend_on_the_process_timezone(monkeypatch: pytest.MonkeyPatch) -> None:
    import random
    import time as clock

    rng = random.Random(20260906)
    zones = [ZoneInfo(name) for name in ("Asia/Seoul", "America/New_York", "Europe/London", "Australia/Lord_Howe")]
    cases = [(date(2026, 1, 1) + timedelta(days=rng.randrange(365)), time(rng.randrange(24), rng.choice((0, 30))), rng.choice(zones))
             for _ in range(1_000)]
    expected = [resolve(*case) for case in cases]
    for process_tz in ("UTC", "Asia/Seoul", "America/Los_Angeles"):
        monkeypatch.setenv("TZ", process_tz)
        clock.tzset()
        assert [resolve(*case) for case in cases] == expected
    assert any(isinstance(r, (Gap, Overlap)) for r in expected), "the random corpus must hit DST edges"
```

(`from datetime import timedelta`, `import pytest` 추가.) `Australia/Lord_Howe`는 30분 DST라 gap/overlap이 정수 시각에 걸린다.

```python
# apps/ai/src/nullnull_ai/domain/time.py
"""Trip-local date/time → UTC instant using the trip zone only. DST gaps/overlaps are reported, never guessed (§5.3)."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, date, datetime, time
from zoneinfo import ZoneInfo


@dataclass(frozen=True, slots=True)
class Exact:
    instant: datetime


@dataclass(frozen=True, slots=True)
class Gap:
    pass


@dataclass(frozen=True, slots=True)
class Overlap:
    pass


Resolution = Exact | Gap | Overlap


def resolve(day: date, at: time | None, zone: ZoneInfo) -> Resolution:
    local = datetime.combine(day, at or time.min)
    first = local.replace(tzinfo=zone, fold=0).astimezone(UTC)
    # A non-existent local time never round-trips; check it BEFORE the fold comparison, because a
    # gap also yields two different fold instants and would otherwise be misreported as Overlap.
    if first.astimezone(zone).replace(tzinfo=None) != local:
        return Gap()
    second = local.replace(tzinfo=zone, fold=1).astimezone(UTC)
    if first != second:
        return Overlap()
    return Exact(first)
```

Run: `uv run pytest tests/test_time.py -q` → 4 PASS.

- [ ] **Step 2: `filters.py` 구현 (테스트는 Java 계획 Task 3 Step 5·7의 case를 pytest로 옮긴다)**

```python
# apps/ai/src/nullnull_ai/item/filters.py
"""§5.4 hard checks. Each returns ELIGIBLE, INELIGIBLE (fact known, violated) or UNKNOWN (fact missing)."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from uuid import UUID

from nullnull_ai.domain.types import Eligibility, EligibilityState, Reason
from nullnull_ai.item.types import (
    Closed,
    DateLock,
    ItemLock,
    LockType,
    MustVisitLock,
    NeighbourItem,
    OpeningWindow,
    OpenWindow,
    ReservationLock,
    RouteEvidence,
    TargetItem,
    TimeLock,
    UnknownHours,
)

PLACE_MISMATCH = "PLACE_MISMATCH"
NO_CHANGE = "NO_CHANGE"
OUTSIDE_TRIP_RANGE = "OUTSIDE_TRIP_RANGE"
CLOSED = "CLOSED"
OUTSIDE_OPENING_HOURS = "OUTSIDE_OPENING_HOURS"
OPENING_HOURS_UNKNOWN = "OPENING_HOURS_UNKNOWN"
DURATION_UNKNOWN = "DURATION_UNKNOWN"
NEIGHBOUR_DURATION_UNKNOWN = "NEIGHBOUR_DURATION_UNKNOWN"
OVERLAPS_NEIGHBOUR = "OVERLAPS_NEIGHBOUR"
ROUTE_EVIDENCE_MISSING = "ROUTE_EVIDENCE_MISSING"
DATE_LOCKED = "DATE_LOCKED"
TIME_LOCKED = "TIME_LOCKED"
RESERVATION_LOCKED = "RESERVATION_LOCKED"


def _minutes_between(a: time, b: time) -> int:
    base = date(2000, 1, 1)
    return abs(int((datetime.combine(base, b) - datetime.combine(base, a)).total_seconds()) // 60)


def _add(start: time, minutes: int) -> tuple[time, bool]:
    """Returns (end, wrapped_past_midnight)."""
    end = datetime.combine(date(2000, 1, 1), start) + timedelta(minutes=minutes)
    return end.time(), end.date() != date(2000, 1, 1)


@dataclass(frozen=True, slots=True)
class LockResult:
    passed: Mapping[LockType, bool]
    eligibility: Eligibility


def lock_checks(locks: Sequence[ItemLock], proposed_date: date, proposed_time: time | None, duration_minutes: int | None) -> LockResult:
    passed: dict[LockType, bool] = {}
    reasons: list[Reason] = []
    for lock in locks:
        match lock:
            case MustVisitLock():
                ok = True
            case DateLock(date=locked):
                ok = locked == proposed_date
            case TimeLock(start_time=start, tolerance_minutes=tolerance):
                ok = proposed_time is not None and _minutes_between(start, proposed_time) <= tolerance
            case ReservationLock(date=locked, start_time=start, end_time=end):
                fits = True
                if end is not None and duration_minutes is not None and proposed_time is not None:
                    stay_end, wrapped = _add(proposed_time, duration_minutes)
                    fits = not wrapped and stay_end <= end
                ok = locked == proposed_date and proposed_time == start and fits
        passed[lock.type] = ok
        if not ok:
            code = {LockType.DATE: DATE_LOCKED, LockType.TIME: TIME_LOCKED, LockType.RESERVATION: RESERVATION_LOCKED}[lock.type]
            reasons.append(Reason(code, f"{lock.type.value} lock is not satisfied"))
    eligibility = Eligibility.eligible() if not reasons else Eligibility(EligibilityState.INELIGIBLE, tuple(reasons))
    return LockResult(passed, eligibility)


def same_place(target: TargetItem, candidate_place_id: UUID) -> Eligibility:
    if target.place_id == candidate_place_id:
        return Eligibility.eligible()
    return Eligibility.ineligible(Reason(PLACE_MISMATCH, "P0 ITEM proposals keep the same place"))


def not_unchanged(target: TargetItem, day: date, at: time | None) -> Eligibility:
    if target.date == day and target.start_time == at:
        return Eligibility.ineligible(Reason(NO_CHANGE, "candidate equals the current slot"))
    return Eligibility.eligible()


def within_trip_range(trip_start: date, trip_end: date, day: date) -> Eligibility:
    if trip_start <= day <= trip_end:
        return Eligibility.eligible()
    return Eligibility.ineligible(Reason(OUTSIDE_TRIP_RANGE, "date outside trip"))


def opening_hours(window: OpeningWindow, start: time | None, duration_minutes: int | None) -> Eligibility:
    match window:
        case UnknownHours():
            return Eligibility.unknown(Reason(OPENING_HOURS_UNKNOWN, "opening hours unverified"))
        case Closed():
            return Eligibility.ineligible(Reason(CLOSED, "closed on that date"))
        case OpenWindow(opens_at=opens, closes_at=closes):
            if start is None:
                return Eligibility.eligible()
            if duration_minutes is None:
                return Eligibility.unknown(Reason(DURATION_UNKNOWN, "stay length unverified"))
            end, wrapped = _add(start, duration_minutes)
            if not wrapped and opens <= start and end <= closes:
                return Eligibility.eligible()
            return Eligibility.ineligible(Reason(OUTSIDE_OPENING_HOURS, "stay exceeds the opening window"))


def neighbour_overlap(neighbours: Sequence[NeighbourItem], target_item_id: UUID, day: date, start: time | None, duration_minutes: int | None) -> Eligibility:
    if start is None:
        return Eligibility.eligible()
    if duration_minutes is None:
        return Eligibility.unknown(Reason(DURATION_UNKNOWN, "stay length unverified"))
    end, _ = _add(start, duration_minutes)
    for neighbour in neighbours:
        if neighbour.item_id == target_item_id or neighbour.date != day or neighbour.start_time is None:
            continue
        if neighbour.duration_minutes is None:
            return Eligibility.unknown(Reason(NEIGHBOUR_DURATION_UNKNOWN, "a neighbouring stay has no verified length"))
        n_end, _ = _add(neighbour.start_time, neighbour.duration_minutes)
        if (start < n_end and neighbour.start_time < end) or start == neighbour.start_time:
            return Eligibility.ineligible(Reason(OVERLAPS_NEIGHBOUR, "overlaps another item on that date"))
    return Eligibility.eligible()


def route_evidence(neighbours: Sequence[NeighbourItem], target_item_id: UUID, from_date: date, to_date: date, evidence: RouteEvidence) -> Eligibility:
    legs_affected = any(n.item_id != target_item_id and n.date in (from_date, to_date) for n in neighbours)
    if legs_affected and evidence is not RouteEvidence.VERIFIED:
        return Eligibility.unknown(Reason(ROUTE_EVIDENCE_MISSING, "travel legs change without route evidence"))
    return Eligibility.eligible()
```

테스트(`tests/item/test_locks.py`, `tests/item/test_filters.py`): Java 계획 Task 3 Step 5·7의 모든 단언을 1:1로 옮기고, `test_locks.py`에 1,000 case 독립성 property(`random.Random(20260906)`, 잠금 조합·날짜·시각·duration 무작위 → 각 lock의 verdict가 단독 평가와 같음)를 넣는다.

- [ ] **Step 3: purity 테스트 추가** — `tests/test_purity.py`: `ast`로 `src/nullnull_ai/{domain,item,slot,related,explain,feed,pipeline}` 파일을 파싱해 `random`, `time.time`, `datetime.now`, `datetime.utcnow`, `uuid.uuid4`, `os.environ`, `requests`, `httpx`, `sqlalchemy` 참조가 없음을 단언한다(REC-ARCH-01의 Python 대응).

- [ ] **Step 4: 실행·manifest** — `uv run pytest -q && uv run mypy && uv run ruff check .` → PASS. `REC-SLOT-01`(tests/item/test_locks.py), `REC-SLOT-03`(tests/test_time.py; TZ 독립 property 포함), `REC-ARCH-01`에 `{"id": "REC-ARCH-01", "suite": "pytest", "path": "tests/test_purity.py"}` 항목 추가(Java ArchUnit 항목과 나란히).

```bash
git add apps/ai/src/nullnull_ai/domain/time.py apps/ai/src/nullnull_ai/item/filters.py apps/ai/tests
git commit -m "feat(ai): trip-local time, independent locks and hard filters (REC-SLOT-01/03)"
```

---

### Task 5: `ItemProposalEvaluator`와 `POST /internal/v1/items/propose` (Python + Spring DTO, REC-OPT-01/04/05, REC-SLOT-02)

**Files:**

- Create: `apps/ai/src/nullnull_ai/item/evaluator.py`, `apps/ai/src/nullnull_ai/api/items.py`
- Modify: `apps/ai/src/nullnull_ai/api/schemas.py`(아래 모델), `apps/ai/src/nullnull_ai/main.py`(router 등록), `apps/ai/contracts/recommendation-internal-v1.json`(export)
- Create: `apps/api/src/main/java/io/nullnull/recommendation/domain/item/{ItemProposeRequest,TargetItemIn,LockIn,NeighbourItemIn,OpeningWindowIn,TemporalCandidateIn,ItemProposeResponse,ItemProposalOut}.java`; `RecommendationGateway.proposeItem(ItemProposeRequest)` 추가; `HttpRecommendationGateway` 구현; `InternalContractParityTest`에 parity 단언 추가
- Test: `apps/ai/tests/item/test_evaluator.py`, `apps/ai/tests/test_api_items.py`

**Interfaces:**

- Produces: `ItemProposalEvaluator(policy).evaluate(context, input) -> ItemProposalResult(outcome: Outcome, proposals: tuple[ItemProposal, ...], reasons, summary: RejectionSummary)`; `Outcome ∈ {PROPOSALS, LOCK_CONFLICT, ROUTE_UNAVAILABLE, DATA_INSUFFICIENT, NO_IMPROVEMENT}`; `ItemProposal(rank, candidate, proposed_start_time, before_instant, after_instant, admission: Admitted, lock_checks: Mapping[LockType, bool])`.
- 알고리즘·필터 순서·종결 우선순위·cap 정렬 키·테스트 기대값은 [Java 계획 Task 4](2026-09-06-recommendation-p0.md#task-4-itemproposalevaluator--후보--필터--점수--선택--종결-사유)(reviewer 반영판)를 그대로 따른다.

- [ ] **Step 1: evaluator 구현**

```python
# apps/ai/src/nullnull_ai/item/evaluator.py
"""§5.4–§5.6, §8 worker step: generate → filter → comparability → score → select → classify."""

from __future__ import annotations

from collections import Counter
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import datetime, time
from enum import Enum

from nullnull_ai.domain import time as trip_time
from nullnull_ai.domain.policy import RecommendationPolicy
from nullnull_ai.domain.types import Reason, RecommendationContext
from nullnull_ai.item import filters
from nullnull_ai.item.score import Admitted, ItemScorePolicy, Rejected, ScoredCandidate, TemporalShift, proposal_sort_key
from nullnull_ai.item.score import COMPARISON_INELIGIBLE, METRIC_POLICY_MISSING
from nullnull_ai.item.types import ItemOptimizationInput, LockType, TemporalCandidate, UnknownHours

CANDIDATE_CAP_EXCEEDED = "CANDIDATE_CAP_EXCEEDED"
INVALID_LOCAL_TIME = "INVALID_LOCAL_TIME"
NO_CANDIDATES = "NO_CANDIDATES"

_LOCK = {filters.DATE_LOCKED, filters.TIME_LOCKED, filters.RESERVATION_LOCKED}
_ROUTE = {filters.ROUTE_EVIDENCE_MISSING}
_DATA = {filters.OPENING_HOURS_UNKNOWN, filters.DURATION_UNKNOWN, filters.NEIGHBOUR_DURATION_UNKNOWN, COMPARISON_INELIGIBLE,
         METRIC_POLICY_MISSING, INVALID_LOCAL_TIME}


class Outcome(Enum):
    PROPOSALS = "PROPOSALS"
    LOCK_CONFLICT = "LOCK_CONFLICT"
    ROUTE_UNAVAILABLE = "ROUTE_UNAVAILABLE"
    DATA_INSUFFICIENT = "DATA_INSUFFICIENT"
    NO_IMPROVEMENT = "NO_IMPROVEMENT"


@dataclass(frozen=True, slots=True)
class RejectionSummary:
    evaluated: int
    rejected_by_reason: Mapping[str, int]


@dataclass(frozen=True, slots=True)
class ItemProposal:
    rank: int
    candidate: TemporalCandidate
    proposed_start_time: time | None
    before_instant: datetime
    after_instant: datetime
    admission: Admitted
    lock_checks: Mapping[LockType, bool]


@dataclass(frozen=True, slots=True)
class ItemProposalResult:
    outcome: Outcome
    proposals: tuple[ItemProposal, ...]
    reasons: tuple[Reason, ...]
    summary: RejectionSummary


@dataclass(frozen=True, slots=True)
class _Scored:
    scored: ScoredCandidate
    candidate: TemporalCandidate
    before_instant: datetime
    after_instant: datetime
    lock_checks: Mapping[LockType, bool]


def _classify(code: str) -> str:
    if code in _LOCK:
        return "LOCK"
    if code in _ROUTE:
        return "ROUTE"
    if code in _DATA:
        return "DATA"
    return "NO_IMPROVEMENT"


def _cap_key(candidate: TemporalCandidate) -> tuple[object, int, object, str]:
    at = candidate.key.time
    return (candidate.key.date, 0 if at is None else 1, at if at is not None else 0, str(candidate.key.place_id))


class ItemProposalEvaluator:
    def __init__(self, policy: RecommendationPolicy) -> None:
        self._policy = policy
        self._score = ItemScorePolicy(policy)

    def evaluate(self, context: RecommendationContext, inp: ItemOptimizationInput) -> ItemProposalResult:
        rejected: Counter[str] = Counter()
        if not inp.candidates:
            return ItemProposalResult(Outcome.DATA_INSUFFICIENT, (), (Reason(NO_CANDIDATES, "no temporal candidates supplied"),),
                                      RejectionSummary(0, {}))
        cap = self._policy.candidate_caps.item_detailed
        ordered = sorted(inp.candidates, key=_cap_key)  # fixed key, never arrival order (§4.1, §6)
        considered = ordered[:cap]
        if len(ordered) > cap:
            rejected[CANDIDATE_CAP_EXCEEDED] = len(ordered) - cap
        before = trip_time.resolve(inp.target.date, inp.target.start_time, inp.trip_zone)
        if not isinstance(before, trip_time.Exact):
            return ItemProposalResult(Outcome.DATA_INSUFFICIENT, (), (Reason(INVALID_LOCAL_TIME, "current slot is not a valid local time"),),
                                      RejectionSummary(0, dict(rejected)))
        admitted: list[_Scored] = []
        classes: set[str] = set()
        for candidate in considered:
            scored, reason = self._evaluate_one(inp, before.instant, candidate)
            if scored is not None:
                admitted.append(scored)
            else:
                assert reason is not None
                rejected[reason.code] += 1
                classes.add(_classify(reason.code))
        summary = RejectionSummary(len(considered), dict(sorted(rejected.items())))
        if admitted:
            return ItemProposalResult(Outcome.PROPOSALS, self._select(admitted), (), summary)
        reasons = tuple(Reason(code, "all candidates rejected") for code in sorted(rejected))
        if classes and classes <= {"LOCK"}:
            return ItemProposalResult(Outcome.LOCK_CONFLICT, (), reasons, summary)
        if "ROUTE" in classes:
            return ItemProposalResult(Outcome.ROUTE_UNAVAILABLE, (), reasons, summary)
        if "DATA" in classes:
            return ItemProposalResult(Outcome.DATA_INSUFFICIENT, (), reasons, summary)
        return ItemProposalResult(Outcome.NO_IMPROVEMENT, (), reasons, summary)

    def _evaluate_one(self, inp: ItemOptimizationInput, before_instant: datetime, candidate: TemporalCandidate) -> tuple[_Scored | None, Reason | None]:
        target = inp.target
        day = candidate.key.date
        assert day is not None
        at = candidate.key.time if candidate.key.time is not None else target.start_time
        first = filters.same_place(target, candidate.key.place_id).and_(filters.not_unchanged(target, day, at)).and_(
            filters.within_trip_range(inp.trip_start, inp.trip_end, day))
        if not first.is_eligible:
            return None, first.reasons[0]
        locks = filters.lock_checks(inp.locks, day, at, target.duration_minutes)
        if not locks.eligibility.is_eligible:
            return None, locks.eligibility.reasons[0]
        for verdict in (
            filters.opening_hours(inp.opening_hours.get(day, UnknownHours()), at, target.duration_minutes),
            filters.neighbour_overlap(inp.neighbours, target.item_id, day, at, target.duration_minutes),
            filters.route_evidence(inp.neighbours, target.item_id, target.date, day, inp.route_evidence),
        ):
            if not verdict.is_eligible:
                return None, verdict.reasons[0]
        after = trip_time.resolve(day, at, inp.trip_zone)
        if not isinstance(after, trip_time.Exact):
            return None, Reason(INVALID_LOCAL_TIME, "proposed slot is not a valid local time")
        shift = TemporalShift(candidate.before_value, candidate.after_value, before_instant, after.instant)
        admission = self._score.evaluate(candidate.metric_code, candidate.verdict, shift)
        if isinstance(admission, Rejected):
            return None, admission.reason
        scored = ScoredCandidate(candidate.key, at, admission)
        return _Scored(scored, candidate, before_instant, after.instant, locks.passed), None

    def _select(self, admitted: list[_Scored]) -> tuple[ItemProposal, ...]:
        ordered = sorted(admitted, key=lambda item: proposal_sort_key(item.scored))
        unique: dict[str, _Scored] = {}
        for item in ordered:
            slot = f"{item.candidate.key.date}T{item.scored.proposed_start_time}"
            unique.setdefault(slot, item)
        proposals: list[ItemProposal] = []
        for rank, item in enumerate(list(unique.values())[: self._policy.candidate_caps.item_proposals], start=1):
            proposals.append(ItemProposal(rank, item.candidate, item.scored.proposed_start_time, item.before_instant, item.after_instant,
                                          item.scored.admission, item.lock_checks))
        return tuple(proposals)
```

- [ ] **Step 2: 테스트** — `tests/item/test_evaluator.py`에 Java 계획 Task 4 Step 2의 case 6개(순위·cap 3, 순서 불변 1,000회 + 중복 병합, 고득점 잠금/휴무 무시 없음, 종결 plane 6종, DAY 해상도 시각 유지, cap 고정 키 1,000회)와 REC-OPT-04 property(1,000 case)를 pytest로 옮긴다. 손계산 기대값: 12:00 후보 0.140000, 13일 10:00(24h) 0.120000, 11:00 0.110000, 13:00 0.050000.

- [ ] **Step 3: 계약 model과 endpoint**

`api/schemas.py`에 추가(모두 `ContractModel`):

```python
class LockIn(ContractModel):
    type: Literal["MUST_VISIT", "DATE", "TIME", "RESERVATION"]
    date: date_ | None = None
    start_time: time_ | None = None
    end_time: time_ | None = None
    tolerance_minutes: int | None = Field(default=None, ge=0, le=180)


class TargetItemIn(ContractModel):
    item_id: UUID
    place_id: UUID
    date: date_
    start_time: time_ | None
    duration_minutes: int | None = Field(default=None, gt=0)
    position: int


class NeighbourItemIn(ContractModel):
    item_id: UUID
    date: date_
    position: int
    start_time: time_ | None
    duration_minutes: int | None


class OpeningWindowIn(ContractModel):
    state: Literal["OPEN", "CLOSED", "UNKNOWN"]
    opens_at: time_ | None = None
    closes_at: time_ | None = None


class TemporalCandidateIn(ContractModel):
    place_id: UUID
    date: date_
    time: time_ | None
    resolution: Literal["DAY", "HOUR"]
    before_value: Decimal
    after_value: Decimal
    metric_code: str
    verdict_eligible: bool
    verdict_reason_code: str
    before_snapshot_id: UUID
    after_snapshot_id: UUID


class ItemProposeRequest(ContractModel):
    evaluated_at: AwareDatetime
    trip_id: UUID
    trip_version: int = Field(ge=1)
    trip_start: date_
    trip_end: date_
    trip_zone: str
    target: TargetItemIn
    locks: list[LockIn] = Field(max_length=4)
    neighbours: list[NeighbourItemIn] = Field(max_length=100)
    opening_hours: dict[date_, OpeningWindowIn]
    route_evidence: Literal["NONE", "VERIFIED"]
    candidates: list[TemporalCandidateIn] = Field(max_length=2000)


class ItemProposalOut(ContractModel):
    rank: int
    date: date_
    start_time: time_ | None
    before_instant: AwareDatetime
    after_instant: AwareDatetime
    score: Decimal
    improvement: Decimal
    relief: Decimal
    change_cost: Decimal
    before_snapshot_id: UUID
    after_snapshot_id: UUID
    lock_checks: dict[str, bool]


class ItemProposeResponse(ContractModel):
    policy_version: str
    policy_hash: str
    pipeline_version: str
    outcome: Literal["PROPOSALS", "LOCK_CONFLICT", "ROUTE_UNAVAILABLE", "DATA_INSUFFICIENT", "NO_IMPROVEMENT"]
    proposals: list[ItemProposalOut]
    reasons: list[str]
    evaluated: int
    rejected_by_reason: dict[str, int]
```

(`date_`, `time_`는 `from datetime import date as date_, time as time_`; `dict[date_, ...]`의 JSON key는 ISO 문자열이다.) `api/items.py`는 요청을 `ItemOptimizationInput`으로 변환(`ZoneInfo(trip_zone)` 실패, lock type별 필수 field, DAY+time, 중복 lock 등 domain `ValueError` → `ApiProblemError("VALIDATION_FAILED", 422)`; 5xx는 서비스 장애, 4xx는 Spring hydration 버그라는 구분 유지), evaluator를 돌리고 `ItemProposeResponse`로 투영한다. Decimal은 `field_serializer`로 문자열 직렬화한다(Spring은 `BigDecimal`로 읽음).

- [ ] **Step 4: Spring DTO parity** — Java record들을 위 필드명과 같게 만들고 `InternalContractParityTest`에 `ItemProposeRequest/TargetItemIn/LockIn/NeighbourItemIn/OpeningWindowIn/TemporalCandidateIn/ItemProposeResponse/ItemProposalOut` parity를 추가한다. `RecommendationGateway.proposeItem`은 응답의 `date/startTime`이 요청 후보 집합에 있는지, `rank`가 1..3 연속인지 검증한다.

- [ ] **Step 5: 실행** — `uv run python -m nullnull_ai.contracts export && uv run pytest -q && uv run mypy && uv run ruff check .` → PASS; `./gradlew --no-daemon test recommendationTest` → PASS. manifest에 `REC-OPT-01`, `REC-OPT-04`, `REC-SLOT-02`(tests/item/test_evaluator.py) 추가. `REC-OPT-05`는 공개 failure code 구분(contract 계층)이므로 Spring BA-050 매핑 테스트와 함께 등록한다.

```bash
git add apps/ai apps/api/src/main/java/io/nullnull/recommendation apps/api/src/recommendationTest
git commit -m "feat(ai): deterministic ITEM proposal evaluator and items/propose contract (REC-OPT-01/04/05)"
```

---

### Task 6: `SlotEvaluator`와 `POST /internal/v1/slots/evaluate` (Python + Spring DTO, BA-042)

**Files:**

- Create: `apps/ai/src/nullnull_ai/slot/__init__.py`, `slot/evaluator.py`, `apps/ai/src/nullnull_ai/api/slots.py`
- Modify: `api/schemas.py`, `main.py`, contract export; Spring `recommendation/domain/slot/{SlotEvaluateRequest,SlotOut,SlotEvaluateResponse}.java`, gateway 메서드, parity 테스트
- Test: `apps/ai/tests/slot/test_evaluator.py`, `apps/ai/tests/test_api_slots.py`

**Interfaces:**

- Produces: `SlotEvaluator(policy).evaluate(context, CandidateSlotInput) -> SlotResult(state: SlotState, slots: tuple[Slot, ...], reasons)`; `Slot(date, suggested_time=None, eligible, reason_code)`; `SlotState ∈ {EXACT, CHECKING, UNKNOWN, NONE}`; 공개 reasonCode allowlist(D-REC-2): `OUTSIDE_TRIP_RANGE, CLOSED, OPENING_HOURS_UNKNOWN, DUPLICATE_PLACE, ROUTE_EVIDENCE_MISSING, DAY_ITEM_LIMIT`.
- 규칙·기대값은 [Java 계획 Task 5](2026-09-06-recommendation-p0.md#task-5-slotevaluator--후보의-가능한-날짜-slot-getcandidatetripmatches)(`ChronoUnit.DAYS` 수정판: Python은 `(trip_end - trip_start).days + 1`). **`slotDates` cap은 스펙 §4.1의 30**(trip 최대 30일과 일치; 2026-09-07 policy-v1.yaml을 90→30으로 고쳐 policyHash가 바뀌었다). Java 계획의 90은 오기다.

- [ ] **Step 1: 구현**

```python
# apps/ai/src/nullnull_ai/slot/evaluator.py
"""§5.3: per trip date, decide whether a candidate could be scheduled without inventing a time."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import date, timedelta
from enum import Enum
from uuid import UUID
from zoneinfo import ZoneInfo

from nullnull_ai.domain.policy import RecommendationPolicy
from nullnull_ai.domain.types import Eligibility, EligibilityState, Reason, RecommendationContext
from nullnull_ai.item import filters
from nullnull_ai.item.types import NeighbourItem, OpeningWindow, RouteEvidence, UnknownHours

DUPLICATE_PLACE = "DUPLICATE_PLACE"
DAY_ITEM_LIMIT = "DAY_ITEM_LIMIT"
DATE_CAP_EXCEEDED = "DATE_CAP_EXCEEDED"
_NO_ITEM = UUID(int=0)


class SlotState(Enum):
    EXACT = "EXACT"
    CHECKING = "CHECKING"
    UNKNOWN = "UNKNOWN"
    NONE = "NONE"


@dataclass(frozen=True, slots=True)
class Slot:
    date: date
    suggested_time: None
    eligible: bool
    reason_code: str | None

    def __post_init__(self) -> None:
        if self.eligible == (self.reason_code is not None):
            raise ValueError("reasonCode is present exactly when the slot is not eligible")


@dataclass(frozen=True, slots=True)
class CandidateSlotInput:
    trip_id: UUID
    candidate_id: UUID
    place_id: UUID
    trip_start: date
    trip_end: date
    trip_zone: ZoneInfo
    duration_minutes: int | None
    items: tuple[NeighbourItem, ...]
    opening_hours: Mapping[date, OpeningWindow]
    dates_with_same_place: frozenset[date]
    route_evidence: RouteEvidence
    max_items_per_day: int
    checking: bool

    def __post_init__(self) -> None:
        if self.trip_end < self.trip_start:
            raise ValueError("tripEnd before tripStart")
        if self.max_items_per_day < 1:
            raise ValueError("maxItemsPerDay must be >= 1")


@dataclass(frozen=True, slots=True)
class SlotResult:
    state: SlotState
    slots: tuple[Slot, ...]
    reasons: tuple[Reason, ...]


class SlotEvaluator:
    def __init__(self, policy: RecommendationPolicy) -> None:
        self._policy = policy

    def evaluate(self, context: RecommendationContext, inp: CandidateSlotInput) -> SlotResult:
        cap = self._policy.candidate_caps.slot_dates
        reasons: list[Reason] = []
        total_days = (inp.trip_end - inp.trip_start).days + 1
        if total_days > cap:
            reasons.append(Reason(DATE_CAP_EXCEEDED, "trip has more dates than the slot cap"))
        slots: list[Slot] = []
        any_unknown = False
        for offset in range(min(total_days, cap)):
            day = inp.trip_start + timedelta(days=offset)
            verdict = self._evaluate_date(inp, day)
            any_unknown = any_unknown or verdict.state is EligibilityState.UNKNOWN
            slots.append(Slot(day, None, True, None) if verdict.is_eligible else Slot(day, None, False, verdict.reasons[0].code))
        if inp.checking:
            state = SlotState.CHECKING
        elif any(slot.eligible for slot in slots):
            state = SlotState.EXACT
        elif any_unknown:
            state = SlotState.UNKNOWN
        else:
            state = SlotState.NONE
        return SlotResult(state, tuple(slots), tuple(reasons))

    @staticmethod
    def _evaluate_date(inp: CandidateSlotInput, day: date) -> Eligibility:
        if day in inp.dates_with_same_place:
            return Eligibility.ineligible(Reason(DUPLICATE_PLACE, "place already scheduled on that date"))
        same_day: Sequence[NeighbourItem] = [item for item in inp.items if item.date == day]
        if len(same_day) >= inp.max_items_per_day:
            return Eligibility.ineligible(Reason(DAY_ITEM_LIMIT, "day already holds the maximum number of items"))
        hours = filters.opening_hours(inp.opening_hours.get(day, UnknownHours()), None, inp.duration_minutes)
        if not hours.is_eligible:
            return hours
        evidence = RouteEvidence.VERIFIED if not same_day else inp.route_evidence
        return filters.route_evidence(inp.items, _NO_ITEM, day, day, evidence)
```

- [ ] **Step 2: 테스트·계약·parity** — Java 계획 Task 5 Step 2의 case 4개를 pytest로 옮기고(200일 여행 → `DATE_CAP_EXCEEDED` 기록·slot 90개), `SlotEvaluateRequest`(evaluatedAt, tripId, candidateId, placeId, tripStart, tripEnd, tripZone, durationMinutes, items[NeighbourItemIn], openingHours, datesWithSamePlace[date], routeEvidence, maxItemsPerDay, checking)·`SlotEvaluateResponse`(policyVersion, policyHash, state, slots[SlotOut{date, suggestedTime, eligible, reasonCode}], reasons)를 추가한다. Spring gateway는 응답 date가 trip 범위 안이고 `suggestedTime`이 null인지 검증한다(P0는 시각을 만들지 않음).

- [ ] **Step 3: 실행·manifest** — 전체 검증 PASS 후 `REC-SLOT-02`에 `tests/slot/test_evaluator.py` 항목 추가.

```bash
git add apps/ai apps/api/src/main/java/io/nullnull/recommendation apps/api/src/recommendationTest
git commit -m "feat(ai): candidate slot evaluator and slots/evaluate contract (BA-042)"
```

---

### Task 7: `RelatedPlaceRanker`와 `POST /internal/v1/related/rank` (Python + Spring DTO, REC-REL-01/02)

**Files:**

- Create: `apps/ai/src/nullnull_ai/related/__init__.py`, `related/ranker.py`, `apps/ai/src/nullnull_ai/api/related.py`
- Modify: `api/schemas.py`, `main.py`, contract export; Spring `recommendation/domain/related/*` DTO, gateway 메서드, parity 테스트
- Test: `apps/ai/tests/related/test_ranker.py`, `apps/ai/tests/test_api_related.py`

**Interfaces:**

- Produces: `RelatedPlaceRanker(policy).rank(context, source_place_id, source_category, candidates, categories, outcome) -> RelatedResult(state, items: tuple[RankedRelated, ...], reasons)`; `RankedRelated(place_id, tier, category_match: Decimal | None, evidence: tuple[RelationCandidate, ...])`.
- 규칙·기대값은 [Java 계획 Task 6](2026-09-06-recommendation-p0.md#task-6-relatedplaceranker--검증된-관련-장소-순위-listrelatedplaces)와 동일: 채널명 순 cap, canonical 병합, tier·categoryMatch(None 마지막)·placeId 정렬, 만료 evidence 제거, 불확실 매핑 quarantine → UNKNOWN, lookupOutcome → CHECKING/UNKNOWN/NONE.

- [ ] **Step 1: 구현**

```python
# apps/ai/src/nullnull_ai/related/ranker.py
"""§5.2 verified relations only; no crowd, popularity or exposure in the ordering."""

from __future__ import annotations

from collections import defaultdict
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal
from enum import Enum
from uuid import UUID

from nullnull_ai.domain.policy import RecommendationPolicy
from nullnull_ai.domain.types import Reason, RecommendationContext

EVIDENCE_EXPIRED = "EVIDENCE_EXPIRED"
MAPPING_UNCERTAIN = "MAPPING_UNCERTAIN"
SELF_REFERENCE = "SELF_REFERENCE"
CHANNEL_CAP_EXCEEDED = "CHANNEL_CAP_EXCEEDED"
MERGED_CAP_EXCEEDED = "MERGED_CAP_EXCEEDED"
TAXONOMY_MISMATCH = "TAXONOMY_MISMATCH"


class RelationTier(Enum):
    EXACT = "EXACT"
    SIMILAR = "SIMILAR"


class MappingCertainty(Enum):
    CERTAIN = "CERTAIN"
    UNCERTAIN = "UNCERTAIN"


class LookupOutcome(Enum):
    COMPLETE = "COMPLETE"
    SOURCE_FAILED = "SOURCE_FAILED"
    JOB_RUNNING = "JOB_RUNNING"


class RelationState(Enum):
    EXACT = "EXACT"
    SIMILAR = "SIMILAR"
    NONE = "NONE"
    CHECKING = "CHECKING"
    UNKNOWN = "UNKNOWN"


@dataclass(frozen=True, slots=True)
class RelationCandidate:
    source_place_id: UUID
    target_place_id: UUID
    tier: RelationTier
    source_code: str
    channel: str
    confidence: Decimal | None
    effective_at: datetime
    expires_at: datetime | None
    mapping: MappingCertainty


@dataclass(frozen=True, slots=True)
class PlaceCategory:
    place_id: UUID
    category_code: str | None
    parent_category_code: str | None
    taxonomy_version: str


@dataclass(frozen=True, slots=True)
class RankedRelated:
    place_id: UUID
    tier: RelationTier
    category_match: Decimal | None
    evidence: tuple[RelationCandidate, ...]


@dataclass(frozen=True, slots=True)
class RelatedResult:
    state: RelationState
    items: tuple[RankedRelated, ...]
    reasons: tuple[Reason, ...]


def _add_once(reasons: list[Reason], code: str, detail: str) -> None:
    if all(reason.code != code for reason in reasons):
        reasons.append(Reason(code, detail))


def _category_match(source: PlaceCategory, target: PlaceCategory | None, reasons: list[Reason]) -> Decimal | None:
    if target is None or target.category_code is None or source.category_code is None:
        return None
    if source.taxonomy_version != target.taxonomy_version:
        _add_once(reasons, TAXONOMY_MISMATCH, "categories come from different taxonomy versions")
        return None
    if source.category_code == target.category_code:
        return Decimal(1)
    if source.parent_category_code is not None and source.parent_category_code == target.parent_category_code:
        return Decimal("0.5")
    return Decimal(0)


class RelatedPlaceRanker:
    def __init__(self, policy: RecommendationPolicy) -> None:
        self._policy = policy

    def rank(self, context: RecommendationContext, source_place_id: UUID, source_category: PlaceCategory,
             candidates: Sequence[RelationCandidate], categories: Mapping[UUID, PlaceCategory], outcome: LookupOutcome) -> RelatedResult:
        reasons: list[Reason] = []
        uncertain = False
        by_channel: dict[str, list[RelationCandidate]] = defaultdict(list)
        for candidate in candidates:
            if candidate.target_place_id == source_place_id:
                _add_once(reasons, SELF_REFERENCE, "relation points at the source place")
                continue
            expired = candidate.expires_at is not None and candidate.expires_at <= context.evaluated_at
            if expired or candidate.effective_at > context.evaluated_at:
                _add_once(reasons, EVIDENCE_EXPIRED, "relation evidence outside its effective window")
                continue
            if candidate.mapping is MappingCertainty.UNCERTAIN:
                uncertain = True
                _add_once(reasons, MAPPING_UNCERTAIN, "canonical mapping uncertain; quarantined")
                continue
            by_channel[candidate.channel].append(candidate)

        row_key = lambda c: (c.tier.value, str(c.target_place_id))  # noqa: E731 — EXACT < SIMILAR alphabetically as in Java enum order
        per_channel = self._policy.candidate_caps.related_per_channel
        kept: list[RelationCandidate] = []
        for channel in sorted(by_channel):
            rows = sorted(by_channel[channel], key=row_key)
            if len(rows) > per_channel:
                _add_once(reasons, CHANNEL_CAP_EXCEEDED, "channel exceeded its candidate cap")
            kept.extend(rows[:per_channel])

        merged: dict[UUID, list[RelationCandidate]] = {}
        for candidate in kept:
            merged.setdefault(candidate.target_place_id, []).append(candidate)
        ranked: list[RankedRelated] = []
        for place_id, evidence in merged.items():
            evidence_sorted = tuple(sorted(evidence, key=lambda c: (*row_key(c), c.channel)))
            tier = RelationTier.EXACT if any(e.tier is RelationTier.EXACT for e in evidence_sorted) else RelationTier.SIMILAR
            ranked.append(RankedRelated(place_id, tier, _category_match(source_category, categories.get(place_id), reasons), evidence_sorted))

        ranked.sort(key=lambda r: (r.tier.value, 0 if r.category_match is not None else 1,
                                   -(r.category_match if r.category_match is not None else Decimal(0)), str(r.place_id)))
        merged_cap = self._policy.candidate_caps.related_merged
        if len(ranked) > merged_cap:
            _add_once(reasons, MERGED_CAP_EXCEEDED, "merged candidates exceeded the cap")
            ranked = ranked[:merged_cap]

        if outcome is LookupOutcome.JOB_RUNNING:
            state = RelationState.CHECKING
        elif outcome is LookupOutcome.SOURCE_FAILED:
            state = RelationState.UNKNOWN
        elif not ranked:
            state = RelationState.UNKNOWN if uncertain else RelationState.NONE
        else:
            state = RelationState.EXACT if ranked[0].tier is RelationTier.EXACT else RelationState.SIMILAR
        return RelatedResult(state, tuple(ranked), tuple(reasons))
```

`row_key`의 tier 정렬은 `"EXACT" < "SIMILAR"` 문자열 순서에 의존한다. 테스트에 그 가정을 단언으로 남긴다(`assert RelationTier.EXACT.value < RelationTier.SIMILAR.value`).

- [ ] **Step 2: 테스트·계약·parity** — Java 계획 Task 6 Step 2의 case 6개를 pytest로 옮긴다(1,000회 셔플 불변). `RelatedRankRequest`(evaluatedAt, sourcePlaceId, sourceCategory{placeId, categoryCode, parentCategoryCode, taxonomyVersion}, candidates[RelationCandidateIn], categories[PlaceCategoryIn], lookupOutcome)·`RelatedRankResponse`(policyVersion, policyHash, state, items[{placeId, tier, categoryMatch, evidenceCount, channels[]}], reasons). Spring gateway는 응답 placeId가 요청 후보 target 집합 안인지 검증한다.

- [ ] **Step 3: 실행·manifest** — PASS 후 `REC-REL-01`, `REC-REL-02` 추가(REC-REL-03은 BA-024 projection).

```bash
git add apps/ai apps/api/src/main/java/io/nullnull/recommendation apps/api/src/recommendationTest
git commit -m "feat(ai): verified related-place ranker and related/rank contract (REC-REL-01/02)"
```

---

### Task 8: 설명 template·validator·LLM port·`POST /internal/v1/explanations/render` (Python, REC-LLM-01)

**Files:**

- Create: `apps/ai/src/nullnull_ai/explain/__init__.py`, `explain/facts.py`, `explain/templates.py`, `explain/validator.py`, `explain/ports.py`, `explain/service.py`, `apps/ai/src/nullnull_ai/api/explanations.py`
- Modify: `settings.py`(`AI_PROVIDER: Literal["NONE"]` 유지; `OPENAI`는 BA-084 계약 뒤 추가), `api/schemas.py`, `main.py`, contract export
- Test: `apps/ai/tests/explain/test_templates.py`, `test_validator.py`, `test_service.py`, `apps/ai/tests/test_api_explanations.py`

**Interfaces:**

- Produces: `ExplanationFacts(locale, place_name, before_date, before_time, after_date, after_time, before_value, after_value, metric_label, attribution, forecast_issue_id)`; `render(facts) -> str`(출처 보존, 장소명 축약, 500자); `accepts(facts, text) -> bool`; `LlmExplanationPort.rewrite(facts, template) -> str | None`; `NoopLlmExplanationPort`; `ExplanationService(port).summary(facts) -> tuple[str, Literal["TEMPLATE","LLM"]]`.
- 규칙·금지어·기대값은 [Java 계획 Task 8](2026-09-06-recommendation-p0.md#task-8-koen-근거-template-llm-port-출력-validator)(reviewer 반영판: 영업/경로/거리 어휘 금지, 출처 보존 절단)과 동일. KO 문장: `"{place} 방문을 {before}에서 {after}로 옮기면 {metric}가 {b}에서 {a}로 {delta}포인트 낮아져요. {attribution}"`, EN: `"Moving {place} from {before} to {after} lowers {metric} from {b} to {a} ({delta} points). {attribution}"`.

- [ ] **Step 1: 구현 요지**

```python
# explain/validator.py (핵심 부분)
_NUMBER = re.compile(r"\d+(?:\.\d+)?")
_UUID = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
_URL = re.compile(r"https?://|www\.")
FORBIDDEN = ("방문자", "한산", "여유로", "절약", "%", "영업", "문 열", "문을 열", "열려", "닫", "휴무", "경로", "가까", "거리", "이동 시간",
             "visitor", "quiet", "empty", "save ", "open", "close", "route", "closer", "distance", "walk", "drive", "km")

def accepts(facts: ExplanationFacts, text: str | None) -> bool:
    if not text or not text.strip() or len(text) > MAX_LENGTH or "\n" in text:
        return False
    if _UUID.search(text) or _URL.search(text):
        return False
    lower = text.lower()
    if any(word in lower for word in FORBIDDEN):
        return False
    allowed = {_plain(facts.before_value), _plain(facts.after_value), _plain(facts.point_delta())}
    for day in (facts.before_date, facts.after_date):
        allowed |= {str(day.month), str(day.day), str(day.year)}
    for at in (facts.before_time, facts.after_time):
        if at is not None:
            allowed |= {str(at.hour), f"{at.hour:02d}", f"{at.minute:02d}"}
    return all(match.group() in allowed for match in _NUMBER.finditer(text))
```

`_plain(value)`는 `format(value.normalize(), "f")`로 후행 0을 제거한다. 금지어 검사 전에 allowlist 사실 문자열(`place_name`, `metric_label`, `attribution`)을 text에서 마스킹해, 장소명에 '거리'·'Open'·'km'이 들어 있어도 LLM 재작성이 무조건 거절되지 않게 한다(template 경로는 사실 문자열만 쓰므로 검증 대상이 아니다). `ExplanationService.summary`는 port 예외를 삼키고 template로 복귀하며 `("...", "TEMPLATE")`/`("...", "LLM")`을 반환한다. endpoint 응답 `ExplanationRenderResponse(policy_version, policy_hash, summary, source)`. `AI_PROVIDER=NONE`이면 `NoopLlmExplanationPort`; `OPENAI` adapter는 BA-084에서 `settings.ai_provider` Literal 확장·`AI_MODEL_ID`·timeout·kill switch와 함께 추가하고, 그 전까지 `OPENAI` 값은 startup 실패다(silent fallback 금지).

- [ ] **Step 2: 테스트** — Java 계획 Task 8의 case(KO/EN 문장 일치, 480자 장소명에서 출처 보존, 숫자/UUID/URL/금지어 거절, port 예외·부적합 출력 → template, 주입 문장이 규칙을 통과하면 텍스트로만 반환)를 pytest로 옮긴다. `uv run pytest -q` PASS 후 `REC-LLM-01`(tests/explain/test_service.py) 추가.

```bash
git add apps/ai
git commit -m "feat(ai): KO/EN explanation templates with validated optional LLM rewrite (REC-LLM-01)"
```

---

### Task 9: 평가 harness — synthetic fixture, 독립 불변식 검사, `evaluation.json` gate (Python, REC-CI-4/6)

**Files:**

- Create: `apps/ai/src/nullnull_ai/evaluation/fixtures.py`(strict fixture parser), `evaluation/invariants.py`(evaluator를 쓰지 않는 독립 검사), `evaluation/report.py`(counter 누적·`evaluation.json` 작성)
- Create: `apps/ai/tests/recommendation/fixtures/{temporal-same-issue,temporal-mixed-issue,locked-reservation-and-time,unknown-hours-and-route,deterministic-score-boundaries,stale-incident-missing}.json`, `apps/ai/tests/recommendation/test_item_fixtures.py`, `apps/ai/tests/conftest.py`(session 종료 시 report 작성 hook)
- Modify: `apps/ai/tests/recommendation/manifest.json`(`fixtures[]` 6개, sha256), `apps/ai/tests/recommendation/test_manifest.py`(report 작성을 `evaluation/report.py`로 이동), `apps/ai/pyproject.toml`(`pytest` 종료 시 safety 정수 ≠ 0이면 실패하는 `conftest` hook)
- Modify: `compose.integration.yml` `ai-quality`는 이미 `NULLNULL_AI_REPORT_DIR`로 artifact를 수집한다; `scripts/integration-test.sh` 증거 수집에 `.artifacts/integration/recommendation-ai/evaluation.json` 존재 검사 추가

**Interfaces:**

- Fixture JSON 형식과 6개 fixture의 내용·기대값은 [Java 계획 Task 9](2026-09-06-recommendation-p0.md#task-9-평가-harness--synthetic-fixture-독립-불변식-검사-evaluationjson)의 표(reviewer 반영판: `locked-reservation-and-time` → `LOCK_CONFLICT`/`RESERVATION_LOCKED: 2`, 경계 표의 `5/5분 → 0.035833`)와 동일하다. camelCase key, `dataOrigin: SYNTHETIC` 필수.
- `report.record_fixture(id, expect_proposals, got_proposals, violations, unsupported, mismatches, outcome)`; `report.write(directory, manifest, policy)`; `pytest_sessionfinish`에서 write 후 safety 정수가 0이 아니면 `session.exitstatus = 1`.

- [ ] **Step 1: parser·invariant checker** — Java 계획 Task 9 Step 2의 `FixtureLoader`·`InvariantChecker`를 Python으로 옮긴다. checker는 `filters.py`를 import하지 않고 raw 규칙을 다시 적는다(잠금 4종, trip 범위, 영업창·duration, verdict.eligible, 최소 개선 5(KTO), score > 0).
- [ ] **Step 2: `test_item_fixtures.py`** — manifest의 ITEM fixture를 `pytest.mark.parametrize`로 돌리고: outcome·proposals(date, time, score 문자열)·rejectedByReason 일치, 독립 불변식 위반 0, 25회 seed 셔플 결정성, `expectProposals`면 proposals ≥ 1. 분모 0(ITEM fixture 없음)은 구성 오류로 실패. 기록은 단언 **전에** `report.record_fixture`.
- [ ] **Step 3: fail-closed** — `conftest.py`의 `pytest_sessionfinish`: `evaluation.json` 작성 후 `safety.hardViolations/unsupportedComparisons/deterministicMismatches`가 정수이고 0이 아니면 종료 코드 1. REC-CI-6 확인: `item/score.py`의 comparison 검사를 임시로 끄고 `stale-incident-missing`·`temporal-mixed-issue`가 빨간지 확인한 뒤 되돌린다(commit 금지).
- [ ] **Step 4: 실행·manifest** — `NULLNULL_AI_REPORT_DIR=build/reports/recommendation uv run pytest -q` PASS; `docker build -f apps/ai/Dockerfile --target test -t nullnull-ai:test . && docker run --rm --network none nullnull-ai:test` PASS. `REC-OPT-01`(corpus, tests/recommendation/test_item_fixtures.py) 항목 추가, `fixtures[]` sha256 기입.

```bash
git add apps/ai scripts/integration-test.sh
git commit -m "test(ai): recommendation fixture corpus with independent invariant checks and fail-closed report"
```

---

### Task 10: Spring — run fingerprint, 반환 proposal 재검증, cursor·feed 정렬 (Java)

**Files:**

- Create: `apps/api/src/main/java/io/nullnull/recommendation/application/RunFingerprint.java`(Java 계획 Task 10과 동일), `apps/api/src/main/java/io/nullnull/trip/domain/{LockChecks,ItemLock,LockType}.java`(Java 계획 Task 3의 `LockChecks`를 `trip` 모듈로 — 수동 편집·교체·APPLY 재검증이 같은 validator를 쓴다, BA-041 step 3), `apps/api/src/main/java/io/nullnull/recommendation/application/ProposalRevalidator.java`
- Create: `apps/api/src/main/java/io/nullnull/shared/cursor/{CursorClaims,CursorException,SignedCursorCodec}.java`, `apps/api/src/main/java/io/nullnull/social/domain/FeedOrdering.java`(Java 계획 Task 7과 동일; `FeedFallback`이 `FeedOrdering.comparator()`를 사용하도록 교체)
- Test: `RunFingerprintTest`, `LockChecksTest`(property 1,000), `ProposalRevalidatorTest`, `SignedCursorCodecTest`, `FeedOrderingTest`

**Interfaces:**

- `ProposalRevalidator.check(ItemProposeRequest request, ItemProposeResponse response) -> List<Reason>`(§3.1 Final check, 불변식 8): 각 proposal을 요청 후보에 `(placeId, date, time)`으로 매칭해 존재해야 하고, **snapshot ID·verdict는 응답 값을 무시하고 요청 후보의 것을 사용**하며 `verdict.eligible == true`, `beforeValue − afterValue == improvement ≥ policy.minimumImprovement(metric)`, `score > 0`, trip 범위 안, `LockChecks` 통과, 영업창 안, 요청 neighbours로 overlap 재계산(duration 없으면 거절), `rank` 1..3 연속, `policyHash`가 `/policy` 캐시와 같음. 위반이 하나라도 있으면 worker는 run을 `FAILED(DATA_CHANGED, retryable=false)`로 기록하고 proposal을 저장하지 않으며 SEV0급 alert를 낸다(D-REC-13).
- 이중 구현 drift 방어: `apps/ai/tests/recommendation/fixtures/*.json`을 Java `LockChecksTest`·`ProposalRevalidatorTest`가 같은 파일로 읽는다(Gradle `systemProperty("nullnull.ai.fixtures.path", ...)`, `InternalContractParityTest`와 같은 방식). fixture의 `expected.outcome == LOCK_CONFLICT`면 Java도 모든 후보를 잠금으로 거절해야 한다.
- `RunFingerprint.Inputs`에 `policyHash`는 서비스 응답 값, `pipelineVersion`을 `algorithmVersion`으로 저장(`optimization_runs.algorithm_version`).

- [ ] Step 1~4: 각 class를 테스트 우선으로 구현하고 `./gradlew --no-daemon test` PASS. `REC-FEED-04`·`REC-INT-*`는 BA-032/050~053 DB slice에서 등록한다.

```bash
git add apps/api/src/main/java/io/nullnull apps/api/src/test/java/io/nullnull
git commit -m "feat(be): run fingerprint, proposal revalidation, signed cursor and feed ordering"
```

---

### Task 11: CI·Docker 경계 마무리 (root tooling)

**Files:**

- Modify: `scripts/verify_target_stack.py`(`apps/ai/Dockerfile` stage `test`/`runtime`·digest 검사, required compose service에 `ai`·`ai-quality` 추가), `scripts/integration-test.sh`(readiness 루프에 `ai` 준비 확인 — `api`의 `/health/ready`가 `recommendation=READY`를 보고할 때까지 대기, `.artifacts/integration/recommendation-ai/evaluation.json` 존재 검사), `scripts/tests/`에 verifier 부정 테스트(ai Dockerfile stage 누락 → 실패)
- Modify: `apps/api/src/integrationTest/.../SystemEndpointsIT`(Task 1에서 DEGRADED 기대로 바꾼 것을 compose 경로에서는 READY로 확인하는 별도 smoke는 wrapper의 curl 단계가 담당)

- [ ] Step 1: verifier 변경 + 부정 테스트 → `python3 -m unittest discover -s scripts/tests -p 'test_*.py'` PASS.
- [ ] Step 2: `docker compose --profile quality -f compose.integration.yml config --format json > cfg.json && python3 scripts/verify_target_stack.py --compose-config cfg.json` → `compose:valid`.
- [ ] Step 3: `apps/web`·marker가 없는 동안 `bash scripts/integration-test.sh`는 **실패**한다(`apps/api` 존재 + marker 부재). marker가 생긴 뒤 wrapper 전체를 한 번 실행해 `full-docker`와 artifact(`recommendation/`, `recommendation-ai/`, `api-test-results/`, `playwright/`)를 확인한다.

```bash
git add scripts compose.integration.yml
git commit -m "infra: recommendation service in the Docker integration gate"
```

---

### Task 12: `apps/ai` 배포 경로 (infra, BA-006/071)

**Files:**

- Modify: `infra/`(CDK, B01 이후 생성) Api stack — ECR repository `nullnull-ai`, ECS Fargate service `ai`(desired 1 staging), task definition(env `NULLNULL_ENV`, `NULLNULL_AI_BIND_HOST=0.0.0.0`, `NULLNULL_AI_PORT=8090`, `NULLNULL_CATALOG_VERSION` from release manifest, `AI_PROVIDER=NONE`), Cloud Map/Service Connect 내부 DNS `ai.nullnull.internal`, security group inbound 8090 **api service SG only**, 외부 ALB 미노출; api task env `NULLNULL_AI_BASE_URL=http://ai.nullnull.internal:8090`
- Modify: `.github/workflows/`(release) — `apps/ai` image build/push(digest를 release manifest `aiImageDigest`에 기록); `docs/operations/AWS_DEPLOYMENT.md`·`GITHUB_RELEASE_OPERATIONS.md` 갱신은 docs 요청 목록 8번

- [ ] Step 1: CDK synth/diff에서 `ai` service·SG·DNS가 나오고 `api`만 8090 inbound를 가진다.
- [ ] Step 2: staging에서 `api /health/ready`의 `recommendation=READY`, `ai` task log에 secret 없음, 비용 항목(Fargate 0.25 vCPU/0.5GB 시작점, 실제 값은 측정 후) 기록.
- [ ] Step 3: rollback rehearsal — `ai` 이전 digest로 되돌려도 `api`가 계약 v1로 동작(추가 field 없음).

```bash
git add infra .github/workflows
git commit -m "infra: recommendation service ECS deployment"
```

---

## B 단계 연결과 CI-R 매핑

| Task | 서비스 | 소비하는 BA 카드 | REC ID(등록) | CI-R |
| --- | --- | --- | --- | --- |
| 1 gateway·fallback | api | BA-003, BA-032, BA-050 | — | CI-R0 |
| 2 점수식 | ai | BA-051 | REC-OPT-02 | CI-R3 |
| 3 비교 정책 | api | BA-023, BA-051 | REC-DATA-02 | CI-R2 |
| 4 시간·잠금·필터 | ai | BA-042, BA-051 | REC-SLOT-01, 03, REC-ARCH-01(purity) | CI-R2 |
| 5 ITEM 평가기·계약 | ai+api | BA-051 | REC-OPT-01, 04, 05, REC-SLOT-02 | CI-R3 |
| 6 slot·계약 | ai+api | BA-042 | (REC-SLOT-02 보강) | CI-R2 |
| 7 related·계약 | ai+api | BA-024, BA-091 | REC-REL-01, 02 | CI-R1 |
| 8 설명·LLM | ai | BA-051, BA-084 | REC-LLM-01 | CI-R3 |
| 9 harness | ai | BA-004, BA-051 | REC-OPT-01(corpus) | CI-R0/R3 |
| 10 fingerprint·재검증·cursor | api | BA-050/051/052, BA-032 | — | CI-R3 |
| 11 CI·Docker | root | BA-001/004/006 | — | CI-R0 |
| 12 배포 경로 | infra | BA-006, BA-071 | — | release |

DB·HTTP가 필요한 REC-FEED-01~04, REC-REL-03, REC-FBK-01~04, REC-INT-01~06, REC-SEC-01~03, REC-JOB-01~02, REC-OPT-03, REC-DATA-01/03/04/05/06은 B03~B06 slice(BA-020/023/032/033/034/050/052/053)에서 등록한다.

## 미결 결정

[Java 계획의 D-REC-1~10](2026-09-06-recommendation-p0.md#미결-결정-ba-000-계약-검토상대-승인-대상)을 그대로 상속하며, 아래를 추가한다.

| ID | 내용 | 기본값 |
| --- | --- | --- |
| D-REC-6 | 추천 전체 Python 서비스 — **결정됨(2026-09-07)** | 이 계획. ADR-0006 문서화는 Codex 요청 목록 1번 |
| D-REC-11 | `apps/ai` 불가 시 optimization run의 public failure code: `OptimizationFailure.code`에 서비스 장애 값이 없음 | 5xx/IO만 lease 기반 bounded retry(최대 attempt는 BA-005 값) 후 `FAILED(DATA_CHANGED, retryable=true)`; 4xx는 Spring hydration 버그이므로 즉시 `FAILED(retryable=false)` + alert. `SERVICE_UNAVAILABLE` code 신설을 **지금** REC-CON-09로 제안(FE CTA "잠시 후 다시 시도") |
| D-REC-15 | 제출(2026-09-21) 빌드에 `apps/ai` 배포 경로(Task 12)가 없으면 ITEM 최적화·related·slot이 fallback-only가 된다 | Task 12를 B08 전에 끝내거나, 제출 범위에서 ITEM 최적화를 제외한다고 사용자가 결정. 기본값은 "Task 12 수행" |
| D-REC-12 | 내부 protocol 인증: P0는 internal network만(compose `internal: true`, ECS security group) | 네트워크 격리; token/mTLS는 staging 전 결정 |
| D-REC-13 | Spring 재검증 실패(서비스가 잠금 위반 proposal 반환) 처리 | run `FAILED(DATA_CHANGED, retryable=false)` + SEV0급 alert(승인 없는 변경 위험 신호) |
| D-REC-14 | `NULLNULL_CATALOG_VERSION`의 출처 | P0는 배포 시 env(release manifest)로 주입; catalog snapshot version이 DB에 생기면 요청 필드로 이동 |
| D-REC-16 | 시작 시각이 없는 target item의 ITEM 비용 규약: 현재는 자정 기준으로 shift를 재므로 같은 날 시각을 부여하는 제안도 changeCost가 포화(1.0)된다 | 보수적 규약 유지(구현·테스트로 고정, `item/evaluator.py` docstring). 대안은 날짜 단위 비용. 제품 결정 전까지 시각 미정 item에는 개선폭이 큰 제안만 나온다 |

## Self-review

2026-09-07 critical-reviewer 2차 반영: `ProposalRevalidator` 검증 항목 확장(verdict·snapshot·개선폭·score·overlap), merge blocker 문구 정정과 Task 12(배포)·D-REC-15 추가, feed 순서 parity(µs·필터·`toString` tie-break·공용 fixture), `resolve()` gap 우선 검사, runner 중복 충돌 quarantine, naive datetime·domain 오류 422, `NULLNULL_CATALOG_VERSION` 필수, probe 전용 timeout, slotDates 30, REC-OPT-05·REC-SLOT-03 등록 조건, validator 마스킹, D-REC-11 4xx/5xx 구분.

- Spec coverage: §3 pipeline(runner, Task 5·6·7이 stage로 구성), §4.1 caps(policy), §5.1 feed(scaffold + Task 1 fallback + Task 10 cursor), §5.2(Task 7), §5.3(Task 6), §5.4~5.6(Task 2·3·4·5), §6 결정성(runner dedup·정렬, Task 5 cap 키, seed test, purity test), §8 fingerprint/재검증(Task 10; APPLY transaction은 BA-052), §9.1(Task 8), §10 저장(BA-032/050), §11(Task 9), §12 REC-CON·D-REC(표), §13 X 참조(구조만, 기록).
- Placeholder scan: Task 4·6·7·8·9·10·11은 Java 계획의 case를 "1:1로 옮긴다"고 참조하는데, 그 문서에 실제 코드·기대값이 있으므로 실행자는 두 문서를 함께 연다(헤더 Spec에 명시). 새로 정의한 Python 코드는 본문에 있다.
- Type consistency: `ComparisonVerdict(eligible, reason_code)`(Task 2·5, Spring `eligible/reasonCode`), `CandidateKey(place_id, date, time)`(scaffold), `ScoredCandidate(key, proposed_start_time, admission)`(Task 2·5), `Eligibility.and_`(scaffold), `filters.route_evidence(neighbours, target_item_id, from_date, to_date, evidence)`(Task 4·5·6), `RecommendationContext(evaluated_at, policy_version, policy_hash, catalog_version, request_id)`(scaffold), `RecommendationGateway.{policy, rankFeed, proposeItem, evaluateSlots, rankRelated}`(Task 1·5·6·7).
