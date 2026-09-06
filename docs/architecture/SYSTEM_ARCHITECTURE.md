# 시스템 아키텍처

- 상태: Accepted for P0 implementation
- 형태: React PWA + Spring Boot 모듈형 모놀리스 + PostgreSQL
- 배포 목표: AWS, 서울 리전 우선

## 1. 설계 목표

1. 2인 팀이 한 저장소, 한 API 배포 단위로 운영할 수 있어야 한다.
2. FE/BE는 OpenAPI 계약으로 병렬 개발하고 mock/contract test로 조기에 통합한다.
3. 추천·혼잡 결과는 출처와 시각을 잃지 않아야 한다.
4. 일정 적용은 stale preview, 중복 요청, 부분 실패에 안전해야 한다.
5. 외부 관광 API 장애가 여행 조회·편집까지 전파되지 않아야 한다.
6. P0 비용과 운영 복잡도를 낮추되 P1 worker/Redis/ML 분리가 가능해야 한다.

## 2. Context

```mermaid
flowchart LR
    U[여행자 모바일 브라우저]
    WEB[React PWA]
    API[Spring Boot API]
    DB[(PostgreSQL)]
    KTO[KTO 관광/혼잡 API]
    SEOUL[서울 실시간 도시데이터]
    MAP[지도·경로 제공자 P1]
    OBS[CloudWatch]

    U --> WEB
    WEB -->|HTTPS JSON + Cookie| API
    API --> DB
    API -->|수집·검증| KTO
    API -->|수집·검증| SEOUL
    API -. P1 route matrix .-> MAP
    WEB -->|구조화 client event| API
    API --> OBS
```

브라우저가 외부 데이터 API를 직접 호출하지 않는다. API key 보호, 캐시, 쿼터, schema 변화, provenance 보존을 backend adapter가 담당한다.

## 3. 목표 저장소 구조

M0 scaffold PR에서 아래 구조와 `.nullnull-target-stack`을 함께 생성한다. 과거 prototype은 현재 목표 저장소 밖의 Git 이력/별도 작업공간에만 두며 새 구조에 복사하지 않는다.

```text
apps/
  web/                      React + TypeScript + Vite PWA
  api/                      Java 21 + Spring Boot
packages/
  api-client/               OpenAPI로 생성, 직접 수정 금지
  design-tokens/            Figma variable export
docs/
  api/openapi.yaml
  contracts/events.schema.json
infra/                      AWS CDK(TypeScript)
scripts/                    계약 생성·검증·로컬 bootstrap
```

## 4. Backend module 경계

모듈은 같은 process와 DB를 사용하지만 다른 모듈의 table/repository를 직접 조작하지 않는다. 공개 application service 또는 domain event를 통한다.

| Module | 책임 | 소유 table |
| --- | --- | --- |
| `identity` | 익명 session, multi-tab CSRF, owner preference, 삭제 receipt | `owners`, `demo_sessions`, `demo_session_csrf_tokens`, `deletion_*` |
| `catalog` | canonical/localized POI, 검색, 외부 ID | `places`, `place_localizations`, `place_external_refs` |
| `social` | post, saved post, feed/feedback, P1 notification | `posts`, `post_places`, `saved_posts`, `feed_feedback`, `notifications` |
| `trip` | 여행, 관심사, 후보, 일정, 제약, revision | `trips`, `trip_*` |
| `optimization` | run, proposal, decision, revert | `optimization_*`, `route_matrix_snapshots` |
| `crowd` | live/forecast/replay snapshot와 비교 가능성 | `crowd_snapshots`, `source_registry`, `snapshot_sets` |
| `live` | live area mapping, nearby, related place | `live_areas`, `place_relations` |
| `importer` | 일정 원문의 일시적 parsing, draft | `itinerary_import_drafts`(구조화 값만) |
| `analytics` | 허용 목록 기반 event 수집 | `analytics_events` 또는 외부 sink |
| `operations` | readiness, source health, ingest audit, leased job | `collector_runs`, `api_ingest_logs`, `background_jobs` |

권장 package 예:

```text
io.nullnull.trip
  api/             controller, API DTO mapping
  application/     command/query handlers, transaction boundary
  domain/          aggregate, policy, value object, domain event
  infrastructure/  JPA repository, external adapter
```

Controller는 JPA entity를 반환하지 않는다. API DTO와 domain type을 분리하고, 외부 응답 DTO는 adapter 밖으로 노출하지 않는다.

### 2인 팀 ownership

| 계약/구현 | Frontend 담당 | Backend/AI 담당 | merge 조건 |
| --- | --- | --- | --- |
| Figma route/state | 화면, 접근성, URL 복구, loading/empty/error | 필요한 read model·상태 enum 검토 | screen-state/API matrix 공동 승인 |
| OpenAPI | 생성 client 소비성·mock example 검토 | spec/Problem/validation·controller 구현 | lint + generated diff + contract test |
| session/CSRF | bootstrap coordinator, memory token, 401 UX | cookie/token 발급·rotation·Origin 검증 | refresh/new-tab/two-tab E2E |
| trip concurrency | ETag 보관, conflict 비교 UI | aggregate lock/version/transaction | two-tab E2E + DB integration |
| optimizer/AI | preview/차이/승인/복구 UI | deterministic validation, snapshot, ranking, LLM 격리 | golden fixture + no-mutation test |
| source truth | provenance badge/delta 차단 | registry/adapter/incident/comparison policy | mixed-source contract/E2E |
| privacy | URL/body/telemetry 최소화, 위치 local-only | owner scope, retention, deletion worker | canary와 restore-delete test |

각 vertical slice의 DRI는 한 명이지만 contract PR에는 상대 담당의 review가 필수다. FE는 API type을 수동 재선언하지 않고, BE/AI는 합의 없이 field/enum을 추가하지 않는다. FE는 장기 `frontend`, BE/AI는 장기 `backend`에서 작업해 `main`에 PR을 만들고 Docker 통합까지 통과한다.

## 5. Frontend architecture

### Layer

- `app`: router, query client, i18n, session bootstrap, global error boundary.
- `features`: 사용자 작업 단위. 다른 feature의 내부 module을 import하지 않는다.
- `entities`: API entity의 표시·정규화 helper. server source of truth를 대체하지 않는다.
- `shared/ui`: Figma component와 접근성 primitive.
- `shared/api-generated`: OpenAPI generator 산출물. 수동 수정 금지.

### State 원칙

- 서버 데이터: TanStack Query 계열 cache를 사용하고 query key에 owner/trip/filter scope를 포함한다.
- wizard/edit buffer: feature-local reducer/form state. 저장 전 서버 entity를 변형하지 않는다.
- URL: 공유/새로고침 복구가 필요한 route, run id, 선택 tab만 둔다.
- client preference: locale/onboarding 같은 비민감 값만 localStorage에 저장한다.
- session token, 붙여넣기 원문, 정밀 위치는 localStorage에 저장하지 않는다.
- CSRF token은 tab memory에만 둔다. refresh/new tab은 cookie가 있으면 `POST /session/csrf`, cookie가 없거나 401이면 `POST /demo/sessions`를 호출한다. 새 token이 다른 tab token을 폐기한다고 가정하지 않는다.
- 검색과 coarse viewport는 URL에 넣지 않고 read-only POST body로 보낸다. query cache key에는 원문 대신 process-memory hash를 사용하고 persistence/dehydration에서 제외한다.

### API client

`docs/api/openapi.yaml`에서 TypeScript type/client를 생성한다. 생성 결과와 spec hash가 CI에서 일치해야 한다. FE가 임의 response type을 재선언하지 않는다. Problem Details는 공통 error mapper로 UI code/CTA에 연결한다.

## 6. 주요 요청 흐름

### 후보 저장

```mermaid
sequenceDiagram
    participant W as Web
    participant T as Trip API
    participant D as PostgreSQL
    W->>T: POST /trips/{id}/candidates + Idempotency-Key
    T->>D: owner/trip/place 확인
    T->>D: INSERT ... ON CONFLICT(trip_id, place_id)
    D-->>T: created 또는 existing
    T-->>W: 201 created / 200 duplicate
    Note over W,D: TripItem과 trip version은 바뀌지 않는다
```

### 최적화 preview/apply

```mermaid
sequenceDiagram
    participant W as Web
    participant O as Optimization
    participant C as Crowd/Route
    participant D as PostgreSQL

    W->>O: POST optimization(inputTripVersion, scope, target)
    O->>D: trip snapshot + constraints
    O->>C: 비교 가능한 snapshot 요청
    C-->>O: values + provenance + fingerprint
    O->>D: immutable proposal 저장
    O-->>W: 202 runId
    W->>O: GET runId
    O-->>W: READY + before/after + expiresAt
    W->>O: POST decisions(APPLY), If-Match
    O->>D: version/fingerprint/locks 재검증
    alt 유효
      O->>D: 한 transaction으로 items + revision + decision
      O-->>W: APPLIED + newTripVersion
    else stale/conflict
      O-->>W: 409 Problem Details
    end
```

`KEEP`는 decision만 기록하고 여행을 변경하지 않는다. `REVERT`는 과거 row를 덮어쓰지 않고 이전 snapshot을 근거로 새 trip revision을 만든다.

optimization 생성 요청은 `scope` 판별 union이다. `ITEM`은 `targetItemId`, `DAY`는
`targetDate`만 요구하며 `TRIP`은 target field를 받지 않는다. P0 client는 `ITEM` variant만
생성하고 DAY/TRIP은 capability OFF다.

한 run은 최초 APPLY/KEEP decision을 최대 하나만 갖는다. APPLY만 24시간 내 한 번 revert할 수 있다. APPLY 응답은 `beforeRevisionId`, `afterRevisionId`, `resultingTripVersion`, `revertUntil`을 모두 제공하며 `revertUntil`은 server `decidedAt`의 정확히 24시간 뒤다. KEEP 응답에는 revision/revert field가 없고, REVERT는 참조한 APPLY decision과 transaction 전후 revision을 제공한다. run이 사용한 모든 crowd/route snapshot은 junction row로 고정하며, before/after 수치 비교는 두 snapshot ID를 가진 comparison row가 `eligible=true`일 때만 표시한다. ADD의 before와 REMOVE의 after는 명시적 null이고 나머지 operation은 양쪽 상태가 필수다.

S14의 P0 최적화 이력은 기존 run의 상태·시각·대상 여행·실행 링크만 반환한다. 이력을 위해 여행 내용이나 proposal snapshot을 복제하지 않으며 원 trip 삭제/보존 정책을 우회하지 않는다.

### 일정 가져오기

1. 브라우저 parser가 우선 구조화한다.
2. 보조 server parser가 필요하면 `Cache-Control: no-store`, body logging 제외, 원문 비저장을 적용한다.
3. 서버는 구조화 draft와 불확실한 token/매핑만 반환한다.
4. draft는 version/ETag를 가지며 remap/confirm은 If-Match를 요구한다. stale tab은 `IMPORT_DRAFT_CHANGED`로 중단한다.
5. 사용자가 remap한 뒤 confirm하면 item과 각 DATE/TIME/MUST_VISIT/RESERVATION constraint를 한 transaction으로 만들고 source를 IMPORT로 기록한다.
6. import draft는 `NEEDS_REVIEW ↔ READY → CONFIRMED`만 허용하며 짧은 TTL 뒤 EXPIRED/삭제한다.

### Session 삭제

1. delete transaction이 session과 모든 CSRF token을 즉시 revoke한다.
2. deletion request, restore tombstone, leased background job을 함께 만들고 one-purpose status token/URL을 202로 반환한다.
3. worker가 owner-scoped domain data와 raw analytics/feedback을 멱등 삭제한다.
4. status token은 삭제 data를 읽을 권한 없이 상태만 조회하며 7일 뒤 만료한다.
5. backup restore 시 public traffic 전에 tombstone을 재적용하고 삭제 완료를 다시 검증한다.

## 7. 데이터 수집과 신선도

```mermaid
flowchart LR
    S[EventBridge schedule]
    J[Collector job]
    A[Source adapter]
    V[Schema/semantic validator]
    RAW[(짧은 원본 cache 선택)]
    N[(정규화 snapshot)]
    H[Source health]

    S --> J --> A --> V
    V -->|허용된 경우만| RAW
    V --> N
    V --> H
```

각 외부 record는 최소 다음을 가진다.

```text
provenance_id, source, source_registry_version, source_state,
observed_at, target_at, fetched_at, freshness, confidence,
license, official_url, license_url, attribution, metric_definition, normalization_version,
comparison_group_id, collector_run_id, snapshot_set_id
```

### Adapter 규칙

- timeout, retry with jitter, circuit breaker, per-source rate limit을 둔다.
- schema와 enum drift를 검출하면 값 추측 대신 source를 degraded로 표시한다.
- last-known-good는 stale label과 함께만 사용한다.
- 외부 실패 시 여행 CRUD는 정상 작동하고, 혼잡/최적화 기능만 명확히 축소된다.
- replay fixture는 production live 값과 namespace/dataState가 섞이지 않는다.
- source registry revision, provider schema, approval/quota/license/attribution을 snapshot과 함께 고정한다. 공식 incident window는 정상 schema라도 quarantine한다.
- media는 검토된 asset license와 redistribution/attribution 조건이 없는 한 mirror/CDN 배포하지 않는다.

### 공모전 KTO gateway와 증거

공모전 제출 경로는 `web → API → KTO gateway → schema/semantic validator → 최소 normalized cache → API response`다. fixture/replay는 PR의 재현성용이며 실제 KTO 필수 활용 증거가 아니다.

- 운영키는 Secrets Manager에서 task runtime에만 주입하고 browser, image layer, workflow log에 넣지 않는다.
- 화면 요청은 quota-aware read-through 또는 만료 snapshot refresh를 일으킨다. 제출 smoke에서는 실제 provider call이 발생한 release·operation과 응답의 `provenanceId`를 call-audit로 연결한다.
- call-audit에는 secret·query 원문·provider 원문 응답·개인정보를 남기지 않는다. 화면 증거에는 텍스트 출처와 기준시각을 포함하되 request ID나 key를 노출하지 않는다.
- KTO 전체 dataset을 로컬 DB에서만 서비스하지 않는다. 장기 저장이 불가피하면 공식 문의·승인과 저장 범위를 source registry에 기록할 때까지 capability를 열지 않는다.
- provider 장애 시 stale/replay로 안전하게 축소할 수 있지만 제출 전 실제 호출 증거가 없으면 release gate는 실패다.

### 비교 가능성

비교 가능 여부는 UI가 추론하지 않고 backend가 before/after snapshot pair마다 `comparisonEligible`과 `comparisonReasonCode`를 결정한다. temporal 비교는 같은 POI/forecast issue, spatial 비교는 같은 source/scope/group/snapshot set을 요구한다. false이면 before/after/delta 중 비교 수치가 null이며 FE는 이를 0으로 바꾸지 않는다.

## 8. 일관성·동시성·멱등성

### Trip version

- `trips.version`은 확정 일정/제약/여행 날짜가 바뀌는 transaction마다 1 증가한다.
- GET은 `ETag: "<version>"`을 준다.
- mutation은 `If-Match`를 요구하고 불일치하면 409 `TRIP_CHANGED`다.
- 후보 저장처럼 일정 snapshot을 바꾸지 않는 동작은 version을 올리지 않는다.
- 날짜 범위를 줄일 때 범위 밖 item/DATE/RESERVATION constraint가 있으면 자동 이동·삭제하지 않고 422로 전체 요청을 거부한다.

### Idempotency

- 생성/apply/import confirm 같은 재시도 위험 mutation은 `Idempotency-Key`를 요구한다.
- key scope는 owner가 생긴 뒤 `(owner_id, route_template, key)`이고 request hash를 저장한다. 최초 `POST /demo/sessions`는 owner-scoped idempotency 대상이 아니며 valid cookie retry로 수렴한다.
- 같은 key/같은 body는 기존 response를 반환한다.
- 같은 key/다른 body는 409 `IDEMPOTENCY_KEY_REUSED`다.
- 기본 TTL은 24시간이다.

### Transaction

- 후보 일정화, item 교체, optimization apply/revert, import confirm은 transaction 하나로 처리한다.
- 외부 API 호출을 DB transaction 안에서 수행하지 않는다.
- proposal 계산 시 외부/정규화 데이터를 snapshot으로 먼저 고정한다.
- import remap/confirm도 draft version lock과 ETag를 사용하며 stale confirm은 trip을 만들지 않는다.

## 9. 인증·권한·웹 보안

- P0 anonymous session도 내부적으로 `Owner`를 만들고 모든 사용자 data에 owner id를 둔다.
- session cookie: `HttpOnly`, `Secure`, `SameSite=Lax`, 제한된 path/domain, rotation.
- state-changing request는 Origin/Referer allowlist와 CSRF token(double submit 또는 server token)을 검증한다.
- CORS는 production web origin만 허용하고 credentials wildcard를 금지한다.
- API는 URL/요청값의 owner id를 신뢰하지 않고 session owner에서 결정한다.
- rate limit은 edge/WAF와 application owner/IP limit을 함께 사용한다.
- raw request body, cookie, Authorization, itinerary 원문, 좌표는 로그에서 제거한다.
- place search와 coarse viewport는 query string이 아닌 redacted read-only POST body로 받고 CDN/ALB/APM/application access log에서 원문을 제외한다.
- 외부 API secret은 runtime IAM으로 Secrets Manager에서 읽고 frontend bundle에 포함하지 않는다.

## 10. Background work

P0은 PostgreSQL job row + Spring scheduler/비동기 executor로 collector와 optimization을 처리한다. process가 재시작돼도 lease timeout 뒤 job을 재개할 수 있어야 한다. 다음 조건 중 하나를 충족할 때 Redis/SQS/분리 worker ADR을 연다.

claim은 원자적으로 `locked_by`, `lease_until`, attempt를 갱신하고 worker는 heartbeat한다. handler는 deduplication key에 대해 멱등이며 lease가 만료되기 전에는 다른 worker가 가져가지 않는다. max attempt 초과는 FAILED/dead-letter 상태와 alert로 남기고 payload에는 domain ID만 저장한다. 동일 queue는 collector, optimization, deletion을 type별 concurrency/rate limit으로 격리한다.

- 95th percentile queue delay가 30초를 지속적으로 초과
- API autoscaling과 worker scaling 요구가 명확히 다름
- 중복 방지/lease 경합이 DB 부하의 10% 이상
- 외부 adapter가 장시간/대량 batch 작업으로 변함

## 11. 관측성

모든 request/job에 `traceId`, `requestId`, 익명화한 `ownerHash`를 사용한다. 사용자 입력과 secret은 기록하지 않는다.

| 신호 | 핵심 지표/알림 |
| --- | --- |
| Web/API | request rate, p50/p95/p99 latency, 4xx/5xx, saturation |
| Trip | version conflict rate, idempotency replay/conflict |
| Optimization | queue delay, run duration, ready/apply/keep/fail, stale rate |
| Sources | fetch success, freshness lag, schema drift, quota usage 60/80/90% |
| Contest KTO | operation별 실제 호출 성공, release/provenance 연결, attribution coverage |
| DB | connections, CPU/storage, slow query, lock wait, replica/backup state |
| UX | candidate save success, funnel completion, error code별 retry success |

SLO 초안:

- 핵심 읽기/편집 API 월 가용성 99.9%.
- cached P0 API p95 500ms 이하. KTO refresh/read-through 경로는 별도 SLI와 timeout budget을 사용하고 stale 상태로 명확히 축소한다.
- 최적화 run p95 10초 이하, UI는 비동기 상태 제공.
- 승인 없는 일정 변경 0건.
- 혼잡 데이터 freshness SLO는 source별 수집 주기를 별도 dashboard로 관리한다.

## 12. 장애 시 degradation

| 장애 | 유지되는 기능 | 축소/표시 |
| --- | --- | --- |
| KTO 관광 API | 저장된 POI, 여행 CRUD | 새 검색 일부 제한, source stale/unavailable |
| 혼잡 API | 여행/후보/직접 편집 | 최적화 중지, last-known-good는 stale |
| 서울 Live | 일반 feed/여행 | Live replay 또는 unavailable |
| 지도/경로 | 목록/직접 편집 | map tile/route metric 제한, P0 최적화 시간 비교만 |
| optimization worker | 모든 수동 기능 | queued timeout 및 재시도 |
| analytics | 제품 기능 전체 | event drop/buffer, 사용자 요청 실패 금지 |

## 13. 진화 경로

- P1: object storage/CDN media, route provider, notification, dedicated worker/SQS 필요성 평가.
- P2: 추천/예측 model이 독립 배포와 별도 scaling이 필요할 때만 Python ML service를 둔다.
- 서비스 분리는 팀 규모가 아니라 transaction/scale/failure isolation 근거와 운영 인력이 있을 때 수행한다.
