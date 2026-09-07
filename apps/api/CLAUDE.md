# apps/api — Nullnull public web API

Java 21 · Spring Boot 4.1.1 modular monolith · PostgreSQL/Flyway. 저장소 전체 규칙은 root `CLAUDE.md`,
Java 코드 상세 규칙은 `.claude/rules/backend-ai.md`가 자동 적용된다. 이 파일은 이 앱의 지도·명령·함정만 둔다.

## Module 지도

`src/main/java/io/nullnull/` 아래 module 경계다. module의 repository/table을 건너 직접 조작하지 않고
application service 또는 domain event를 쓴다.

| Module | 담당 |
| --- | --- |
| `trip` | 여행·일정 item·후보·잠금·version/ETag |
| `crowd` | 외부 source·provenance·혼잡 예보·비교 적격성 |
| `social` | feed·게시물·SavedPost·피드백 |
| `recommendation` | `apps/ai` gateway port·DTO·`ProposalRevalidator`·fallback **만** |
| `operations` | 영속 job·수집/삭제 실행·readiness·capability |
| `shared` | HTTP 공통 정책·error·session/owner·공용 kernel |

## 검증

Gradle은 **Temurin 21로만** 실행한다. 기기 기본 `java`는 26이라 `JAVA_HOME` 없이 실행하면 실패한다.
설치형 Gradle 금지, wrapper만 쓴다.

```bash
cd apps/api
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
  ./gradlew --no-daemon test integrationTest openapiContractTest recommendationTest
```

- `test` 단위 · `integrationTest` Testcontainers PostgreSQL · `openapiContractTest` OpenAPI 계약
- `recommendationTest` `apps/ai` 내부 계약 parity(operation·DTO·fixture·policy pin)

## Gotchas

- compose `api-quality`는 `NULLNULL_TEST_DATABASE=external`로 Testcontainers 대신 compose PostgreSQL을 쓰고
  `--offline`로 실행된다. 새 test dependency는 `resolveTestClasspaths`가 해석하는 configuration에 있어야 한다.
- `recommendation` package는 추천 계산을 **중복 구현하지 않는다**(ADR-0006). feed 순서·관련 장소·slot·ITEM 개선·
  설명 template은 `apps/ai`가 계산하고 여기서는 hydration·재검증·저장·APPLY만 한다.
- `apps/ai` 요청에 owner/session ID·붙여넣기 원문·좌표를 넣지 않는다.
- `apps/ai`의 endpoint/schema를 바꾸면 `apps/ai/contracts/recommendation-internal-v1.json`을 갱신하고
  Spring DTO를 맞춘다. `recommendationTest`가 parity를 검사한다.
- controller는 JPA entity/provider DTO를 반환하지 않는다. 외부 호출을 DB transaction 안에서 하지 않는다.
- migration은 empty DB, previous→latest, rollback-compatible app으로 실제 PostgreSQL에서 검증한다.
