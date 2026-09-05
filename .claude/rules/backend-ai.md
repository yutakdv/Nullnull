---
paths:
  - "apps/api/**/*.{java,kt,kts,sql,yaml,yml}"
  - "infra/**/*.{ts,json,yaml,yml}"
  - "docs/api/openapi.yaml"
  - "docs/contracts/**/*.{json,md}"
  - "docs/architecture/**/*.md"
  - "docs/data/**/*.md"
---

# Backend/AI rules

- 기능 ID와 Figma state가 요구하는 query/command/error를 OpenAPI example로 먼저 정의한다.
- controller, application, domain, infrastructure 경계를 지키고 JPA entity/provider DTO를 노출하지 않는다.
- owner는 authenticated session에서 유도한다. 다른 owner의 존재 여부도 노출하지 않는다.
- trip mutation은 ETag, retryable command는 idempotency를 검증하며 저장된 replay projection은 최소화·암호화한다.
- 상태 전이와 잠금은 domain/application layer에서 강제하고 PostgreSQL transaction으로 원자성을 보장한다.
- import draft remap/confirm, optimization apply/revert와 deletion status는 race/retry test를 작성한다.
- 외부 호출을 DB transaction 안에서 수행하지 않고 timeout, bounded retry, circuit, quota, drift, stale/degraded를 처리한다.
- `observedAt`을 모르면 null로 두고 `fetchedAt`으로 대체하지 않는다.
- 비교 가능성은 단일 snapshot의 boolean이 아니라 pair/context policy 결과로 계산·설명한다.
- LLM 입력은 최소화하고 출력은 schema validation과 결정적 fact/route/constraint 검증을 통과시킨다.
- persistent job에는 lease owner/expiry, attempt, next attempt, terminal/dead-letter와 idempotent handler가 필요하다.
- PostgreSQL behavior는 Testcontainers로 검증하며 H2/SQLite 결과로 대체하지 않는다.
- 구조화 log는 requestId/traceId/error code만 포함하고 body/query/cookie/token/raw itinerary/coordinates를 제거한다.
- Backend/AI 작업은 장기 `backend`에서 하고 `main`에만 PR을 만든다. 계약은 additive PR부터 병합하며 상대 승인과 두 required check를 거친다.
- 공모전 release는 KTO OpenAPI를 runtime secret으로 실제 호출하고 operation·시각·outcome·count·release/provenance의 redacted call-audit를 남긴다. file/replay/local mirror만으로 대체하지 않는다.
- 공모전 profile은 로그인 없는 익명 session과 위치 capability OFF를 startup/readiness에서 강제한다.
