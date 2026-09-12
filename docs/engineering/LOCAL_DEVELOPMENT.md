---
aliases:
  - "로컬 개발 환경 계약"
doc_type: reference
status: baseline
area: engineering
tags:
  - nullnull/reference
  - nullnull/engineering
---

# 로컬 개발 환경 계약

- 상태: B01에서 실행 파일과 exact version을 고정할 기준
- 대상: Frontend 담당 1명, Backend/AI 담당 1명
- 원칙: 새 clone에서 같은 명령·seed·생성물로 같은 화면과 API를 재현한다.

현재 문서는 목표 stack의 실행 계약이다. 검토 중 `apps/api` scaffold, 추천 서비스 `apps/ai`([ADR-0006](../decisions/ARCHITECTURE_DECISIONS.md#adr-0006))와 `compose.yml`이 추가됐다. `apps/web`과 marker가 없어 현재 `scripts/integration-test.sh`는 hard fail한다. 앱이 전혀 없는 초기 기준선에서만 baseline-only를 허용하며, B01 scaffold PR은 내용이 정확히 `version=1`인 `.nullnull-target-stack`, 표의 lock 파일과 앱 명령/Docker stage를 실제로 만든 뒤 CI와 두 개발자 기기에서 검증해야 한다.

## 1. B01 toolchain 결정표

“최신”이나 floating tag를 문서·CI·container에 쓰지 않는다. [#10 기반 결정안](FOUNDATION_DECISIONS.md)에 Node 24·generator/runtime·CDK·scan 도구와 Compose digest의 구체 제안 및 검증 범위를 기록했다. FE 교차 승인 후 아래 lock 위치에 반영하고 B01의 실제 호환성/실행 검증으로 확정한다.

| 도구 | 승인된 선택 원칙 | Exact lock 위치 | B01 완료 증거 | DRI |
| --- | --- | --- | --- | --- |
| Node.js | scaffold 시점의 지원되는 LTS, Vite/plugin/CI 호환 확인 | `.tool-versions` 또는 `.node-version`; `.nvmrc`도 같은 값 | `node --version`과 CI 값 일치 | FE |
| npm | 선택한 Node와 검증된 npm, 다른 package manager 혼용 금지 | root `package.json#packageManager`에 `npm@x.y.z` | local/CI `npm --version`과 lockfile clean install | FE |
| Java | Java 21 LTS, Temurin 계열로 local/CI/container 통일 | `.tool-versions`와 CI setup, container digest | `java -version` 공급자·patch 일치 | BE/AI |
| Gradle | 설치형 Gradle 금지, wrapper만 사용 | `apps/api/gradle/wrapper/gradle-wrapper.properties` distribution URL+checksum | `./gradlew --version`, wrapper validation | BE/AI |
| Spring Boot | Java 21/선택 Gradle과 호환되는 지원 release | version catalog/plugin lock/BOM | dependency report와 integration test | BE/AI |
| Python / uv | `apps/ai` 전용 3.13, uv 0.12.10; 시스템 pip 설치 금지 | `apps/ai/.python-version`, `pyproject.toml`, `uv.lock`, Dockerfile digest | `uv sync --frozen` 뒤 pytest·ruff·mypy 통과, Docker test stage offline 통과 | BE/AI |
| Docker | team 두 기기와 CI가 공통 지원하는 stable Engine/API | `docs`의 minimum과 CI runner, image digest | Compose health와 Testcontainers 통과 | 공동 |
| PostgreSQL | production RDS가 제공하는 동일 major | Compose image digest, Testcontainers image | Flyway + query integration test | BE/AI |
| OpenAPI generator | TypeScript client template과 runtime을 검증한 exact package | root devDependency/lockfile, generator config | 생성 후 working tree diff 0 | 공동 |
| AWS CDK | 검증된 exact CLI/library 조합, minor 번호 일치 요구 아님 | infra package manifest/lockfile | synth/diff artifact 생성 | BE/AI |

권장 기준은 Node LTS + npm, Java 21, Gradle wrapper, Docker Compose v2다. patch 값을 임의로 문서에 추측하지 않고 B01 PR의 자동 compatibility test가 통과한 값으로 채운다. version 변경은 dependency update PR로만 하고 FE/BE/AI test와 image 재현성을 함께 확인한다.

## 2. 목표 directory와 명령 표면

```text
apps/web/                 React + TypeScript + Vite PWA
apps/api/                 Spring Boot + Flyway
apps/ai/                  Python 3.13 + FastAPI 추천 계산 서비스, 내부 계약 v1
packages/api-client/      OpenAPI 생성물, 직접 수정 금지
packages/contracts/       schema-valid examples와 frontend fixtures
infra/                    AWS CDK TypeScript
docs/api/openapi.yaml     HTTP 계약 정본
docs/contracts/           event 계약 정본
```

B01에서 root script 또는 동등한 task runner로 다음 명령을 제공한다.

| 목적 | 목표 명령 | 보장 사항 |
| --- | --- | --- |
| 의존성 설치 | `npm ci` | root lockfile만 사용, postinstall에서 외부 secret 호출 금지 |
| local dependency 시작 | `docker compose up -d postgres` | named volume·health check·`127.0.0.1:5434` 구성 확인함. **실제 기동은 실패할 수 있고, 실패해도 앱은 붙는다** — PM-022 항목 참고. `up -d` 뒤에 container가 **`.env.local`이 가리키는 포트를 publish하는지** 확인한다. `ps --status running`은 liveness만 보므로 부족하다(`ENVIRONMENT.md` §7 0단계) |
| DB migration | `cd apps/api && ./gradlew flywayMigrate` | local profile만, production URL 거부 |
| API 실행 | `cd apps/api && ./gradlew bootRun` | local config와 mock source 기본; `nullnull.ai.base-url`은 `http://127.0.0.1:8090` |
| 추천 서비스 실행 | `cd apps/ai && uv run python -m nullnull_ai.main` | `127.0.0.1:8090`, DB·외부 API 접근 없음 |
| 추천 서비스 검증 | `cd apps/ai && uv run ruff check . && uv run mypy && uv run pytest` | `build/reports/recommendation/evaluation.json` 생성, 계약 JSON drift는 실패 |
| Web 실행 | `cd apps/web && npm run dev` | 같은 origin proxy 또는 명시된 credential CORS |
| client 생성 | `npm run api:generate` | OpenAPI에서만 생성 |
| client 검증 | `npm run api:check` | 재생성 뒤 diff가 있으면 실패 |
| deterministic seed | `npm run db:seed:base` | 고정 UUID/timezone/replay clock |
| edge seed | `npm run db:seed:edge` | empty/stale/conflict/lock/error용 fixture |
| local reset | `npm run db:reset:local` | 아래 local-only guard를 모두 통과해야 실행 |
| 전체 gate | `npm run verify` | docs/contract + web + API test를 조합 |
| B01 정적 gate | `python3 scripts/verify_target_stack.py` | marker, lock/wrapper, task/stage, image digest 확인 |
| PR 통합 gate | `bash scripts/integration-test.sh` | B01 전 baseline-only, B01 후 PostgreSQL/API/web/E2E Docker 통합 |

실제 script 이름을 바꾸면 이 문서, README, CI를 같은 PR에서 바꾼다. 개인 alias나 IDE task만을 필수 실행 경로로 삼지 않는다.

`apps/web` 또는 `apps/api`가 생겼는데 `.nullnull-target-stack`이 없으면 wrapper는
hard fail한다. marker가 생긴 뒤에는 API `test/runtime`, web
`test/runtime/e2e/tooling`, Gradle wrapper checksum, root lockfile, 필요한
Gradle/npm task, immutable external image digest 중 하나라도 빠지면 실패해야 한다.
`scripts/verify_target_stack.py`는 정적 파일을 먼저 검사하고, wrapper는 정규화한
Compose JSON을 다시 검사해 필수 service와 internal network를 확인한다. 미래 앱이
없는 지금의 `integration_mode=baseline-only`를 full Docker 통과로 보고하지 않는다.

## 3. 고정 local port

| Service | Host | Container/internal | 비고 |
| --- | ---: | ---: | --- |
| Web dev server | `5173` | 해당 없음 | browser entry |
| Storybook | `6006` | 해당 없음 | 개발 전용 |
| API | `8080` | `8080` | `/api/v1` |
| PostgreSQL | `5434` | `5432` | 기기 기본 PostgreSQL과 충돌 완화. 5433은 같은 이유로 골랐다가 이 기기에서 점유돼 있어 오너 결정으로 옮겼다(2026-09-13) |

**PM-022 실측(2026-09-13): 처음 고른 5433이 이 기기에서 이미 점유돼 있었다.** `docker compose up -d postgres`가
`bind: address already in use`로 실패했다 — **Docker가 아닌 host PostgreSQL이 `127.0.0.1:5433`을 잡고 있었고**,
`nullnull-local-postgres-1`은 그때까지 `Created` 상태로 한 번도 뜬 적이 없었다. 5433을 고른 이유가 바로 그 충돌 회피였는데
그 자리가 점유돼 있었다. **오너 결정(2026-09-13)으로 host port를 5434로 옮겼고, 저장소 기본값이 5434다.**

위험했던 것은 실패 자체가 아니라 **실패한 뒤에도 앱이 동작한다는 점**이다. `SPRING_DATASOURCE_URL`이 점유된 포트를
가리키면 연결은 성공하고 상대는 **host 서버**다. 그러면 `flywayMigrate`가 프로젝트와 무관한 서버에 migration을 걸고, 이는
`CLAUDE.md`의 *"test는 live demo/dev database에 대고 돌리지 않는다"* 를 정면으로 어긴다. `docker compose ... | tail`처럼
파이프를 쓰면 **exit code가 사라져** 실패가 보이지도 않는다.

**포트를 옮겨도 이 함정은 그대로 남는다.** `docker compose up -d postgres`를 **돌렸다는 것**과 **앱이 붙을 포트를 그
container가 publish한다는 것**은 다르다 — compose와 `.env.local` 중 한쪽만 고치면 container는 running인 채로 앱은 host
서버에 붙는다. 그래서 포트 숫자를 문서에 박아 비교하지 않고 두 값을 **묶어서** 확인한다(`ENVIRONMENT.md` §7 0단계).
| API debug | `5005` | `5005` | opt-in, loopback only |
| 추천 서비스 `apps/ai` | `8090` | `8090` | `/internal/v1`, loopback only; 공개 proxy 대상 아님 |

Docker DB를 public interface에 노출하지 않고 `127.0.0.1`에 bind한다. port override가 필요하면 gitignored local override를 쓰며 공유 fixture나 cookie origin은 바꾸지 않는다.

## 4. 최초 실행 순서

1. repository의 version lock 파일을 적용하고 version verification을 실행한다.
2. example 파일을 복사해 local 설정을 만든다. 실제 provider key 없이 mock/replay가 기본 동작해야 하며 키를 Git에 넣지 않는다.
3. PostgreSQL을 시작하고 health를 확인한다.
4. Flyway를 빈 DB에 적용하고 `base` seed를 넣는다.
5. 추천 서비스 `apps/ai`를 시작하고 `/internal/v1/health/ready`를 확인한다. 없으면 API readiness는 `DEGRADED`다.
6. API를 시작해 liveness, readiness, demo capability를 확인한다.
7. OpenAPI client를 생성하고 clean diff를 확인한다.
8. Web을 시작해 A-1부터 P0 seed journey를 실행한다.
9. 변경 전 `verify`, 변경 뒤 영향별 test와 `verify`를 실행한다.
10. B01에서는 정적 verifier를 실행하고, 역할 브랜치에서
   `bash scripts/integration-test.sh`를 실행해 결과를 `main` PR에 남긴다.

Frontend 담당은 MSW 모드와 실제 local API 모드를 모두 검증한다. Backend/AI 담당은 mock source와 opt-in sandbox source를 구분하고, sandbox key가 없어도 trip/feed/editor 개발이 가능하게 한다.

공모전 실제 KTO 활용은 local/PR fixture가 아니라 승인된 staging/submission profile에서 검증한다. Backend/AI는 runtime secret으로 실제 호출과 redacted call-audit를 확인하고, Frontend는 같은 response의 화면 출처를 확인한다. 공모전 profile에서는 위치 flag를 OFF로 둔다.

## 5. Seed catalog

seed는 실행할 때마다 같은 identifier, 날짜 상대 규칙, source metadata를 만든다. 현재 시각에 의존하는 화면은 고정 replay clock을 사용한다.

| Seed | 포함 데이터 | 사용 화면 |
| --- | --- | --- |
| `base` | 신규/온보딩 완료 owner, KO/EN, JA/ZH 준비 중, 여행 없음/활성 여행, feed/post/place | A, B, C, H |
| `trip-edit` | 여러 날짜/item/candidate와 네 lock 조합 | D/E |
| `optimization` | READY/APPLIED/KEPT/EXPIRED와 오류 6종 proposal, profile history page | F, H, I |
| `live` | LIVE/FORECAST/REPLAY/QUALITATIVE/STALE/UNAVAILABLE | G, I |
| `edge` | empty, long text, pagination, conflict, 삭제 job, P1 capability OFF | 전역/H/I |

실제 외부 응답을 그대로 seed에 복사하지 않는다. 라이선스가 허용하는 최소 fixture이거나 synthetic record만 사용하고 `source`, `schemaVersion`, `capturedAt`, `synthetic` 표시를 포함한다.

## 6. Reset 안전장치

`db:reset:local`은 다음 조건을 코드로 모두 확인해야 한다.

- `NULLNULL_ENV=local`이다.
- DB host는 loopback 또는 Compose service name이고 AWS/RDS hostname이 아니다.
- database 이름은 정확히 allowlisted local 이름이다.
- 확인 문자열 또는 `--yes-local` 같은 명시적 flag가 있다.
- production/staging credential가 process에 있으면 즉시 거부한다.
- 수행 전 대상 host/database를 출력하되 password는 출력하지 않는다.

reset은 Flyway schema를 다시 만들고 선택 seed를 넣는 작업만 한다. repository, Docker 전체, 다른 project volume을 정리하지 않는다. staging reset은 별도 승인형 workflow이며 local 명령으로 지원하지 않는다.

## 7. OpenAPI와 mock 동기화

1. BE/AI 담당이 OpenAPI와 schema-valid example을 먼저 변경한다.
2. Redocly lint, reference resolution, breaking diff를 통과한다.
3. exact-pinned generator로 `packages/api-client`를 재생성한다.
4. FE 담당이 example에서 MSW handler/Storybook fixture를 생성 또는 import한다.
5. provider contract test와 FE component test가 같은 example ID를 사용한다.
6. CI가 재생성 clean diff와 hand-written API type 금지 규칙을 확인한다.

생성물은 직접 수정하지 않는다. generator bug workaround는 config/template patch와 근거 issue를 함께 남긴다.

## 8. Local cookie·network

- 기본 web origin은 `http://localhost:5173`, API는 `http://localhost:8080`이다.
- 선호 방식은 Vite `/api` proxy로 browser에 same-origin처럼 제공하는 것이다.
- cookie 이름·SameSite·Secure 차이는 environment 문서에 명시하고 production 동작을 local HTTP에 억지로 완화하지 않는다.
- CSRF token bootstrap과 credential request는 generated client wrapper 한 곳에서 처리한다.
- PR Compose의 모든 runtime service는 `internal: true` network에만 연결한다.
  `egress-denied` probe가 실제 외부 HTTPS 요청 실패를 확인해야 하며
  `NULLNULL_EXTERNAL_NETWORK=disabled` 같은 환경 변수만으로 차단을 주장하지 않는다.
- local 외부 API network는 명시적 sandbox profile에서만 허용한다.
- HTTPS/cookie/CSP 검증은 staging gate에서 수행한다.

## 9. 로컬 검증 책임

| 변경 | 작성자 실행 | 상대 검토 |
| --- | --- | --- |
| Figma/component | FE web gate + Storybook + mobile screenshot | BE/AI가 server state 누락 확인 |
| OpenAPI/domain | BE/AI API/contract/integration gate | FE가 generated client와 error CTA 확인 |
| source/AI | BE/AI fixture/property/degradation test | FE가 provenance와 안전 문구 확인 |
| infra/env | BE/AI synth/config validation | FE가 public build variable와 rollback 확인 |
| cross-cutting | 둘 다 각 gate | staging acceptance를 상대가 수행 |
| 공모전 KTO/제출 | BE/AI actual-call·audit, FE attribution·익명 flow | 상대가 동일 release evidence 재현 |

역할 브랜치와 병합 순서는 [브랜치·Docker 통합 계약](./BRANCH_AND_INTEGRATION.md)을 따른다.

## 10. 문제 해결 원칙

- lockfile 삭제나 dependency 전부 upgrade로 문제를 숨기지 않는다.
- migration 오류를 local DB 수동 편집으로 우회하지 않는다.
- generated client 오류를 `any` 또는 hand-written duplicate type으로 우회하지 않는다.
- provider 장애는 mock/replay/degraded state로 명시하고 핵심 CRUD를 계속 검증한다.
- 실행할 수 없는 gate는 이유와 재현 조건을 PR에 `not run`으로 남긴다.
