---
aliases:
  - "아키텍처 결정 기록"
doc_type: reference
status: baseline
area: decisions
tags:
  - nullnull/reference
  - nullnull/decisions
---

# 아키텍처 결정 기록

ADR ID는 유지하고 한 노트에서 관리한다. 각 결정의 상태·근거·대안·재검토 조건을 보존한다.

## ADR-0001

ADR-0001: 목표 기술 스택과 과거 프로토타입 지위

- 상태: Accepted
- 날짜: 2026-09-04

### ADR-0001 · Context

과거 작업공간에는 FastAPI + SQLite + React JavaScript prototype과 Next.js/FastAPI 현대화 문서가 있었지만 최신 Figma와 목표 계약은 React PWA + Spring Boot + PostgreSQL을 전제로 한다. 팀은 Frontend 1명, Backend/AI 1명이고 AWS 배포가 목표다. 혼재로 인한 오구현을 막기 위해 원격 이력을 보존하는 목표 저장소에는 현재 서비스만 둔다.

### ADR-0001 · Decision

- 목표 frontend: React + TypeScript + Vite PWA.
- 목표 backend: Java 21 + Spring Boot 모듈형 모놀리스.
- database: PostgreSQL, schema migration은 Flyway.
- contract: OpenAPI 3.1을 정본으로 TypeScript client 생성.
- infra: AWS CDK(TypeScript), frontend는 S3/CloudFront, API는 ECS Fargate/ALB, DB는 RDS PostgreSQL.
- 과거 FastAPI/React JS 구현은 목표 저장소에 포함하지 않는다. 필요한 실험 지식은 Git 이력/별도 작업공간에서 검토한 뒤 behavior test와 새 계약으로만 이식한다.
- Redis, microservice, 별도 ML service는 P0에 넣지 않는다. 단, 추천 계산 서비스 `apps/ai`는 [ADR-0006](#adr-0006)으로 이 항목의 예외가 됐다(2026-09-07).
- Node/npm/Java patch, Gradle/Spring/PostgreSQL/generator는 B01 compatibility test가 통과한 exact version을 lock한다. Node는 지원 LTS, Java는 21을 사용하고 floating `latest`에 의존하지 않는다.

### ADR-0001 · Why

- Vite의 React/TypeScript 생태계는 정적 PWA 배포와 빠른 FE 작업에 적합하다. Vite는 TypeScript를 transpile하지만 type check를 수행하지 않으므로 별도 `tsc --noEmit` CI를 둔다. [Vite 공식 가이드](https://vite.dev/guide/), [Vite 기능 안내](https://vite.dev/guide/features.html)
- Spring Boot는 Java 17 이상을 지원하며 Java 21은 장기 지원 runtime으로 팀 표준화에 적합하다. patch version은 dependency automation과 CI 검증을 통해 갱신한다. [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- 2인 팀은 분산 transaction·service discovery·여러 deploy pipeline보다 명확한 module boundary가 있는 단일 API가 관리 가능하다.
- 정적 web과 container API를 분리하면 FE 배포가 빠르고 API의 보안/network 경계가 단순하다.

### ADR-0001 · Consequences

- 초기 scaffold와 일부 검증 코드는 새로 작성해야 한다.
- 과거 endpoint/data를 자동 호환한다고 가정하지 않는다. 필요한 실험 로직은 test로 옮겨 검증한다.
- FE/BE가 함께 영향을 받는 변경은 OpenAPI를 먼저 합의해야 한다.
- Spring Boot major/third-party compatibility는 scaffold PR에서 BOM과 dependency lock 결과로 확정한다.

### ADR-0001 · Rejected alternatives

- **기존 FastAPI를 그대로 운영화**: 빠르지만 최신 원격 기준과 backend 담당 기술 선택에 어긋나며, 현재 SQLite/model/API 계약이 Figma 도메인과 다르다.
- **Next.js full-stack**: SSR이 P0 핵심 요구가 아니고 FE/BE 역할 경계와 Spring backend 계획을 흐린다.
- **처음부터 microservice**: 현재 규모에 비해 운영·관측·배포 비용이 크다.
- **Lambda 중심 API**: 가능하지만 초기 cold start, persistence/job 구성, local parity보다 팀이 익숙한 container 단일 service가 적합하다.

### ADR-0001 · Review trigger

- 팀 구성 또는 backend 역량이 바뀜
- SSR/검색 노출이 핵심 KPI가 됨
- module별 독립 scaling/failure isolation이 수치로 입증됨
- ECS 운영비가 대안 대비 지속적으로 불리함
- Spring/Vite/Java/Node의 지원 종료 또는 critical security advisory로 lock된 조합을 유지할 수 없음
- P0 API 또는 optimizer가 단일 service에서 SLO를 지속적으로 위반함

검토 DRI는 Backend/AI 담당이며 Frontend 담당 승인이 필요하다. B01 dependency lock, B08/최종 검수 production go/no-go와 위 trigger 발생 시 다시 검토한다.

## ADR-0002

ADR-0002: 데이터 진실성 및 AI 사용 경계

- 상태: Accepted
- 날짜: 2026-09-04

### ADR-0002 · Context

Nullnull의 핵심 가치는 혼잡 완화이지만 KTO 예보, 서울 실시간, replay, 정성 정보는 범위와 척도가 다르다. 이를 하나의 수치로 합치거나 LLM이 빈 값을 채우면 그럴듯하지만 검증 불가능한 추천이 된다.

### ADR-0002 · Decision

1. 외부 관측/예측 record에 source, sourceState, observedAt, targetAt, fetchedAt, freshness, confidence, license, normalizationVersion을 보존한다.
2. temporal comparison은 같은 POI와 같은 forecast issue/metric 체계에서만 한다.
3. spatial comparison은 같은 source/scope/comparisonGroup/snapshotSet에서만 한다.
4. 비교 가능 여부와 사유는 backend가 명시한다. UI가 값을 보고 추정하지 않는다.
5. `LIVE`, `FORECAST`, `REPLAY`, `QUALITATIVE`, `STALE`, `UNAVAILABLE`을 별도 state로 유지한다.
6. LLM은 선호 해석과 검증된 근거 설명만 담당한다. POI 존재, 영업, 좌표, 경로, 혼잡 값, 적용 가능성은 결정적 코드가 검증한다.
7. P0 일정 parser는 규칙 기반이며 원문을 보관하지 않는다.
8. 공모전 제출 서비스는 KTO OpenAPI를 실제 server-side 호출하고 redacted call-audit를 화면 provenance에 연결한다. file/replay/local mirror만으로 대체하지 않는다.
9. KTO 화면은 승인된 텍스트 출처를 표시하고 무허가 CI·BI를 사용하지 않는다.

### ADR-0002 · Consequences

- 데이터가 적을 때 UI가 빈 상태를 더 자주 보여도 허위 precision을 만들지 않는다.
- source adapter와 snapshot metadata가 일반 CRUD보다 복잡해진다.
- 추천 품질 평가는 정확도뿐 아니라 provenance 완전성, stale 비율, 비교 적격률을 포함한다.
- P2 model을 도입해도 같은 evidence contract를 통과해야 한다.
- quota-aware cache를 사용하더라도 실제 KTO 호출·서비스 사용 증거와 replay 증거를 별도로 운영해야 한다.

### ADR-0002 · Rejected alternatives

- 모든 source를 1–5 혼잡도로 강제 변환
- LLM에게 외부 검색·영업 여부·경로 가능성을 단독 위임
- replay를 현재 실시간처럼 표시
- 결측값을 0 또는 “보통”으로 채움

### ADR-0002 · Review trigger

- provider가 metric, 갱신 주기, 이용조건, 공간 범위 또는 schema를 변경함
- source quality incident로 잘못된 live/forecast/replay 표시가 발생함
- 서로 다른 source를 교정·비교할 수 있다는 검증 연구와 평가 dataset이 승인됨
- P1/P2 LLM/ML provider 또는 자체 model이 추천 후보·score에 관여하려 함
- provenance 완전성이나 comparison eligibility SLO가 milestone에서 반복 실패함

검토 DRI는 Backend/AI 담당이고 사용자 문구·표현 변경은 Frontend 담당 승인이 필요하다. B03/B10 source onboarding, B06 optimizer, 각 provider 계약 갱신 때 확인한다.

## ADR-0003

ADR-0003: 익명 소유권, 일정 version, 승인형 변경

- 상태: Accepted
- 날짜: 2026-09-04

### ADR-0003 · Context

로그인 장벽 없이 데모를 제공하면서도 사용자별 여행을 격리해야 한다. 모바일의 재시도·중복 tap, 여러 tab, 오래 열린 최적화 preview는 일정 중복이나 덮어쓰기를 만들 수 있다.

### ADR-0003 · Decision

- 익명 사용자도 server-side `Owner`와 `DemoSession`을 가진다.
- browser는 opaque session id를 Secure/HttpOnly cookie로만 가진다.
- 모든 사용자 도메인 row는 owner 또는 owner가 소유한 trip을 통해 권한 검증한다.
- trip 확정 상태에는 단조 증가 `version`과 immutable `TripRevision`을 둔다.
- GET trip의 ETag와 mutation의 If-Match를 사용한다.
- 생성/apply는 Idempotency-Key를 요구한다.
- optimization proposal은 immutable하며 승인 전 trip을 수정하지 않는다.
- apply/revert는 transaction 하나와 새 revision으로 처리한다.
- 첫 session bootstrap에서 owner와 session을 한 transaction으로 만들고 mutation 전에 idempotency owner scope가 존재해야 한다.
- session revoke/사용자 삭제 요청은 즉시 접근을 차단하고, 비동기 삭제 receipt 상태와 tombstone을 추적해 backup restore 뒤에도 삭제를 재적용한다.
- 공모전 제출은 `로그인 불필요` 방식을 사용하고 운영자 seed·개인 계정 없이 anonymous owner가 핵심 흐름을 완결한다.

### ADR-0003 · Consequences

- 로그인 도입 시 anonymous owner를 account owner로 병합하는 migration이 필요하다.
- FE는 409를 일반 실패가 아닌 최신 상태 복구 흐름으로 다룬다.
- 서버에는 idempotency record와 revision 저장 비용이 생기지만 감사·복구가 가능하다.
- 후보 저장은 trip 일정 version을 올리지 않는다.

### ADR-0003 · Rejected alternatives

- localStorage만으로 여행 보관: 기기 종속, 권한/동시성/복구 불가.
- last-write-wins: 다른 tab 또는 stale preview가 최신 변경을 잃게 한다.
- AI 결과 즉시 적용: Figma의 사용자 승인 계약과 안전 원칙을 위반한다.

### ADR-0003 · Review trigger

- 장기 계정 로그인/anonymous owner 병합을 도입함
- cookie/CSRF/session rotation 정책 또는 public origin이 바뀜
- offline mutation queue나 여러 기기 동기화를 도입함
- trip command 처리량 때문에 version aggregate 경계를 바꿀 필요가 수치로 확인됨
- 삭제/복구 incident, owner 간 접근 또는 stale apply safety violation이 발생함

검토 DRI는 Backend/AI 담당이며 conflict·recovery·삭제 UX는 Frontend 담당 승인이 필요하다. B02 session/delete, B05 editor, B06 apply와 production security review에서 확인한다.

## ADR-0004

ADR-0004: 2인 contract-first 화면 개발 방식

- 상태: Accepted
- 날짜: 2026-09-04

### ADR-0004 · Context

Nullnull은 Figma의 52개 frame, 여러 overlay/state, OpenAPI, 외부 데이터와 일정 최적화 불변식을 두 사람이 구현한다. Frontend와 Backend를 긴 기간 따로 개발하면 hand-written mock, 누락된 error state, 마지막 통합 병목과 승인 없는 일정 변경 위험이 커진다.

### ADR-0004 · Decision

- 역할은 Frontend 담당 1명과 Backend/AI 담당 1명으로 고정한다.
- 작업 단위는 layer가 아니라 Figma node와 기능 ID를 끝까지 연결한 vertical slice다.
- slice 착수 전 Figma state, operationId, schema-valid example, Problem code, domain 변화/미변화와 acceptance를 contract packet으로 승인한다.
- Frontend는 generated client와 canonical example 기반 MSW로, Backend/AI는 같은 OpenAPI/example 기반 provider contract test로 병렬 구현한다.
- 상태는 `contract-ready → parallel-build → integration-ready → staging-accepted` gate를 통과한다.
- 모든 P0 화면과 reference variant는 automated fixture/test를 갖춘다. P1 화면은 capability OFF 상태부터 구현하고 조건 충족 전 기능을 활성화하지 않는다.
- contract/product/security/infra 변화는 상대 담당자 승인을 요구한다. 작성자 자신의 승인만으로 완료하지 않는다.
- Frontend는 장기 `frontend`, Backend/AI는 장기 `backend`에서 작업하고 각각 `main`에 PR을 만든다. 상대 승인과 `docs-contract`·`docker-integration` 뒤 merge commit하고 역할 브랜치를 삭제하지 않는다.
- 교차 변경은 additive contract를 먼저 병합하고 양 역할 브랜치를 `main`으로 동기화한 뒤 호환 Backend, Frontend 순으로 진행한다. 새 capability는 양쪽 통합 전 OFF다.
- ownership과 handoff는 `OWNERSHIP_MATRIX.md`, 역할별 실행은 `docs/roles/`, branch/Docker와 세부 협업 순서는 [브랜치·통합 계약](../engineering/BRANCH_AND_INTEGRATION.md)을 따른다.

### ADR-0004 · Consequences

- BE/AI 구현이 늦어도 FE가 합의된 mock으로 시작할 수 있고, FE 화면이 늦어도 API contract test를 먼저 완성할 수 있다.
- OpenAPI example과 screen manifest 관리 비용이 생기지만 마지막 통합과 의미 불일치를 줄인다.
- 두 명 모두 상대 영역의 계약을 review해야 하므로 WIP를 사람당 main slice 1개로 제한한다.
- merge와 done을 구분하며 실제 staging acceptance가 milestone 완료 조건이 된다.
- 역할 브랜치 수는 단순하지만 한 브랜치의 WIP가 길어지면 다음 slice를 시작할 수 없으므로 작은 병합 단위를 강제한다.

### ADR-0004 · Rejected alternatives

- Frontend 전체 완료 후 Backend 연동: mock/API drift와 integration big-bang 위험이 크다.
- Backend endpoint 전체 완료 후 화면 구현: Figma에 필요한 상태 누락을 늦게 발견한다.
- hand-written TypeScript API type: OpenAPI 정본과 쉽게 갈라진다.
- 모든 파일 공동 소유: 두 사람 모두 상대가 처리할 것으로 오해한다.
- slice마다 단기 feature branch: 2인 팀에서는 branch 간 contract SHA와 배포 순서를 추적하는 비용이 더 커 장기 역할 브랜치를 선택한다.

### ADR-0004 · Review trigger

- 팀 인원이 늘거나 역할이 바뀜
- contract-ready 대기 시간이 milestone의 20%를 반복 초과함
- mock과 production API 불일치 incident가 발생함
- required review가 병목이 되어 PR lead time이 2영업일을 반복 초과함
- 별도 mobile/native client 또는 public API consumer가 추가됨

검토 DRI는 공동이며 각 milestone 회고와 팀 구성 변경 시 확인한다.

## ADR-0005

ADR-0005: AWS 환경 경계와 immutable release

- 상태: Accepted baseline
- 날짜: 2026-09-04

### ADR-0005 · Context

2인 팀이 AWS에 mobile web, Spring API, PostgreSQL과 외부 data collector를 배포한다. staging/production 권한 혼용, 동시에 실행되는 migration, stateful CDK 삭제와 다시 build한 artifact는 작은 팀에서도 복구 불가능한 사고를 만들 수 있다.

### ADR-0005 · Decision

- production은 전용 AWS account를 권장한다. 불가능하면 staging과 role/VPC/KMS/secret/stack을 완전히 분리하고 예외를 기록한다.
- CDK stack은 Foundation, Network, Data, Api, WebEdge, Observability 경계로 나누고 stateful Data/Web version/audit resource는 retain/snapshot한다.
- GitHub Actions는 environment별 OIDC role만 사용하고 장기 access key를 저장하지 않는다. trust는 정확한 repository/environment/ref subject로 제한한다.
- staging과 production deploy concurrency는 각각 1이다. production과 migration은 진행 중 run을 취소하지 않는다.
- staging에서 검증한 API image digest와 web artifact checksum을 production으로 그대로 승격한다.
- Semantic Version tag와 release manifest에 source, contract, migration, CDK, artifact digest를 연결한다.
- production은 Frontend 담당과 Backend/AI 담당의 분리된 deployer/approver 또는 observer 역할을 요구한다.
- drift, budget, removal policy, alarm 수신과 rollback rehearsal을 production gate로 둔다.
- 모든 역할 브랜치 PR은 Docker 통합을 거치고, 공모전 release는 익명 외부망·실제 KTO 호출/출처·위치 OFF·공식 PDF 정합성을 추가 gate로 둔다.

### ADR-0005 · Consequences

- 초기 account/stack/OIDC 설정과 artifact storage 비용이 발생한다.
- 동일 artifact 승격으로 environment별 build-time 값은 최소화하고 runtime/config manifest를 엄격히 분리해야 한다.
- 긴급 console 변경도 incident와 후속 IaC PR이 필요하다.
- production account 분리가 어려우면 출시 전 명시적 위험 수용 결정이 필요하다.

### ADR-0005 · Rejected alternatives

- staging/production 한 deploy role과 secret 공유: 오배포와 권한 확산 위험이 크다.
- access key를 GitHub secret에 저장: 장기 credential rotation·노출 위험이 있다.
- environment별 재build: 같은 tag가 다른 code/dependency artifact를 가리킬 수 있다.
- stateful resource를 stack destroy 기본값에 맡김: DB/web rollback/evidence 손실 위험이 있다.
- concurrent production deploy: migration/task/web version의 조합을 증명하기 어렵다.

### ADR-0005 · Review trigger

- AWS Organization/account 구조 또는 domain/region이 바뀜
- 월 비용이 승인 budget 80%를 두 달 연속 넘음
- RTO/RPO rehearsal이 목표를 달성하지 못함
- ECS/Fargate/ALB/NAT 비용이나 SLO가 대안 대비 지속적으로 불리함
- OIDC/IAM drift 또는 배포·artifact 불일치 incident가 발생함
- multi-region, queue/worker 분리 또는 blue/green 배포가 필요해짐

검토 DRI는 Backend/AI 담당이며 Frontend 담당의 release/rollback 검증과 공동 승인이 필요하다. INF-001, B08/최종 검수, production 구조 변경과 위 trigger 발생 시 검토한다.

## ADR-0006

ADR-0006: 추천 계산 서비스 분리(`apps/ai`)와 Spring gateway 경계

- 상태: Accepted
- 날짜: 2026-09-07
- 재검토 대상: [ADR-0001](#adr-0001)의 "별도 ML service는 P0에 넣지 않는다" 항목

### ADR-0006 · Context

Backend/AI 담당은 2026-09-07 추천 계산 전체를 Python 서비스로 두기로 결정했다. 배경은 세 가지다. 첫째, feed 구조 참조로 채택한 [xai-org/x-algorithm](https://github.com/xai-org/x-algorithm) commit `902a06f`는 Rust candidate pipeline과 가중치 없는 Python 모델 코드이므로 코드를 이식할 수 없고 stage 구조만 참고한다. 둘째, P1 LLM 설명(BA-084)은 OpenAI API를 예정하고 있어 Python 생태계가 유리하다. 셋째, P2 개인화 학습(BA-087)이 같은 runtime에서 policy·evaluation gate를 재사용해야 한다. 일정 무결성·데이터 진실성·저장은 여전히 Spring이 담당해야 하므로 경계를 명시한다.

### ADR-0006 · Decision

| 책임 | `apps/api`(Spring) | `apps/ai`(Python 3.13, FastAPI) |
| --- | --- | --- |
| 계산 | 없음(fallback만) | 고정 feed 순서, 관련 장소 순위, 후보 slot, ITEM 날짜/시간 개선, 설명 template |
| 입력 | session·trip·잠금·KTO/crowd snapshot·비교 verdict를 hydrate | Spring이 보낸 immutable 입력만 사용; DB·외부 API·clock·난수 없음 |
| 저장·상태 | run/job·proposal·APPLY/REVERT·cursor·저장 전체 | 저장 없음, stateless |
| 검증 | 응답을 `ProposalRevalidator`로 다시 검증한 뒤에만 저장 | 정책 `policy-v1.yaml`과 `policyHash`를 응답에 포함 |
| 계약 | 공개 OpenAPI 0.2.x 변경 없음 | 내부 계약 v1 `apps/ai/contracts/recommendation-internal-v1.json`, `/internal/v1/*` |
| 네트워크 | `RecommendationGateway`를 DB transaction 밖에서 호출, timeout·bounded retry·circuit | internal network 전용, 공개 ALB 미노출 |
| 장애 | feed는 Spring 고정 순서 fallback, related/slot은 `UNKNOWN`, ITEM run은 `FAILED` | readiness 실패는 Spring readiness를 `DEGRADED`로만 만든다 |

- 요청에는 장소/게시물 ID·시각·잠금·비교 verdict만 담는다. owner/session ID, 원문 일정, 정밀 좌표, secret은 서비스에 보내지 않는다.
- Spring `recommendation` package는 gateway port·DTO·재검증·fallback만 가지며 계산을 중복 구현하지 않는다. 정책 YAML과 REC safety corpus는 `apps/ai`에만 둔다.
- `recommendationTest`(Gradle)는 Spring DTO와 내부 계약 JSON의 parity를, `apps/ai` pytest는 REC corpus와 `evaluation.json`을 검증한다. 두 결과 모두 `docker-integration` 안에서 실행한다.
- P0 `AI_PROVIDER`는 `NONE`뿐이다. `OPENAI`는 BA-084에서 adapter·model ID·timeout·kill switch와 함께 추가하며 그 전에는 startup 실패다.

### ADR-0006 · Consequences

- runtime이 둘이 된다. Python 3.13/uv lock, `apps/ai/Dockerfile`(test/runtime stage), compose `ai`·`ai-quality`, ECS service `ai`와 release manifest `aiImageDigest`가 필요하다.
- 제출 빌드 전에 `apps/ai` 배포 경로가 없으면 related/slot/ITEM은 fallback-only가 된다. 이 결정은 [결정·위험 대장](../project/DECISIONS_AND_RISKS.md)의 열린 결정으로 추적한다.
- 내부 계약 변경은 Frontend 승인 대상이 아니지만, 사용자에게 보이는 설명/상태 문구 변경은 기존대로 Frontend 확인이 필요하다.
- 안전 불변식(preview 무변경, 잠금 독립, 비교 적격성)은 두 곳에서 검증한다. 서비스가 위반 proposal을 반환하면 Spring이 run을 실패 처리하고 alert를 낸다.

### ADR-0006 · Rejected alternatives

- **Java 단일 runtime에서 추천 계산**: 운영 단위는 하나지만 P1 LLM·P2 학습 경로가 Java에 갇히고 x-algorithm 구조 참조의 이점이 줄어든다.
- **x-algorithm 코드 직접 이식**: 학습 가중치와 참여 데이터가 없어 P0에 사용할 수 없다.
- **추천 서비스가 DB를 직접 읽음**: 소유권·transaction·삭제 경계가 두 서비스로 갈라진다.

### ADR-0006 · Review trigger

- 서비스 호출 지연이 optimization p95 목표(queue 포함 10초)를 지속 위반함
- 내부 계약 변경이 한 milestone에 세 번 이상 필요함
- 배포 비용·운영 부담이 2인 팀 기준을 넘어 fallback-only 운영이 길어짐
- P2 학습 모델이 별도 scaling을 요구함(BA-088)

검토 DRI는 Backend/AI 담당이며 사용자 문구·상태 표시 변경은 Frontend 담당 승인이 필요하다. 실행 계획은 [추천 서비스 구현 계획](../superpowers/plans/2026-09-07-recommendation-python-service.md)이다.
