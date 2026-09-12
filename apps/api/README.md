# Nullnull API (`apps/api`)

Java 21 + Spring Boot modular monolith for the Nullnull public web API. Contract: `docs/api/openapi.yaml` (0.2.x; the contract test accepts any 0.2 version). Design: `docs/architecture/SYSTEM_ARCHITECTURE.md`, `docs/architecture/RECOMMENDATION_ALGORITHM.md`. Work plan: `docs/roles/BACKEND_AI_PLAYBOOK.md`.

## Toolchain (B01 lock)

| Tool | Value | Where it is pinned |
| --- | --- | --- |
| Java | Temurin 21.0.11 (local build `+10`) | `.tool-versions`, Gradle toolchain (`languageVersion = 21`). `setup-java`가 Adoptium 목록과 매칭하도록 build 접미사는 pin하지 않는다 |
| Gradle | 9.7.1 wrapper + sha256 | `gradle/wrapper/gradle-wrapper.properties` |
| Spring Boot | 4.1.1 | `gradle/libs.versions.toml` |
| PostgreSQL | 17.6 (digest-pinned) | `compose.yml`, `Dockerfile`, Testcontainers fixture |

Installed Gradle distributions are not used; always run `./gradlew`.

## Run locally

저장소 root에서 시작한다. `up -d`가 실패해도 `bootRun`은 **다른 PostgreSQL에 붙을 수 있으므로**(그 자리를 기기의 host
서버가 잡고 있는 경우) 그 사이에 포트의 신원을 확인한다 — 포트 숫자는 `application-local.yaml`에서 읽어 박아 두지
않는다. 배경과 smoke용 4단계 선행 조건은 [ENVIRONMENT.md](../../docs/operations/ENVIRONMENT.md) §7에 있다.
대화형 zsh는 `#` 주석을 붙여넣으면 `command not found: #`가 나므로 블록 안에 주석을 두지 않는다.

```bash
docker compose up -d postgres
port=$(sed -n 's#.*url: jdbc:postgresql://[^:]*:\([0-9]\{1,5\}\)/.*#\1#p' apps/api/src/main/resources/application-local.yaml)
docker ps --format '{{.Ports}}' | grep -q ":${port}->" \
  && echo "OK: a running container publishes ${port}" \
  || echo "FAIL: the local profile points at ${port} and no container publishes it"
```

```bash
cd apps/api
./gradlew bootRun
curl -s http://localhost:8080/api/v1/health/ready
```

`bootRun`은 profile `local`로 `http://localhost:8080/api/v1`에 뜬다. shell에 `SPRING_DATASOURCE_*`가 export돼 있으면
`application-local.yaml`을 덮으므로 위 확인이 통과해도 앱은 다른 곳에 붙는다(`env | grep '^SPRING_DATASOURCE_'`).

## Verify

```bash
./gradlew test                     # unit, architecture (REC-ARCH-01), pure policy
./gradlew integrationTest          # real PostgreSQL via Testcontainers (Docker required)
./gradlew openapiContractTest      # responses vs docs/api/openapi.yaml with a 2020-12 evaluator
./gradlew recommendationTest       # Spring DTO parity with apps/ai/contracts/recommendation-internal-v1.json
./gradlew check                    # all of the above
```

Inside the PR Compose gate set `NULLNULL_TEST_DATABASE=external` plus `SPRING_DATASOURCE_*` so the DB suites use the compose PostgreSQL service instead of Testcontainers.

## Layout

```text
src/main/java/io/nullnull/
  NullnullApplication.java
  shared/{clock,ids,http,problem}/   technical values only: Clock bean, UUIDv7, X-Request-ID, Problem Details
  operations/{api,application,infrastructure}/   health/readiness (getLiveness, getReadiness)
  recommendation/{domain,application}/           gateway port + DTOs of the apps/ai internal contract (algorithms live in apps/ai) (no Spring/DB/HTTP/clock)
src/main/resources/
  db/migration/V001__background_jobs.sql
src/test, src/integrationTest, src/openapiContractTest, src/recommendationTest, src/testFixtures
```

Module boundaries (`api → application → domain`, infrastructure private per module, `recommendation.domain/application` free of framework/IO/clock/randomness) are enforced by `ArchitectureRulesTest`. Recommendation algorithms, the policy file and the REC safety corpus live in `apps/ai`; Spring re-validates trip invariants before persisting anything the service returns.

## Not yet in place

- `.nullnull-target-stack` marker and the full Docker gate need the `apps/web` scaffold in the same B01 slice.
- No persistence: no JPA entity, no repository, no `@Transactional`, and `V001__background_jobs.sql` is the only migration.
- No session, authentication or CSRF handling. The only endpoints served are the anonymous `/health/live` and `/health/ready`.
- `trip`, `crowd` and `social` carry pure domain rules only, with no controller, application service or storage. The catalog (place), identity, optimization, Live, importer and analytics modules do not exist.
