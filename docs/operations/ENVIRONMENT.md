---
aliases:
  - "환경변수·비밀·Feature flag"
doc_type: reference
status: baseline
area: operations
tags:
  - nullnull/reference
  - nullnull/operations
---

# 환경변수·비밀·Feature flag

- 상태: Accepted naming contract
- 원칙: 설정은 환경별, secret은 runtime 주입, 공개값과 비밀값을 이름부터 분리

> 구현 순서: [B00~B10 실행 계획](../engineering/IMPLEMENTATION_PLAN.md)을 따른다. 공통 KTO·장소·forecast·비교·relation은 B03, Live 전용 서울 연동·area/API/탭은 B10 마지막이다. Live 이전 검수는 핵심 흐름의 중간 gate이며 전체 P0 완료가 아니다.

## 1. 환경

| 환경 | 데이터 | 외부 API | 배포/접근 |
| --- | --- | --- | --- |
| local | synthetic seed/local PostgreSQL | mock 기본, opt-in sandbox | 개발자 기기 |
| test | test fixture/Testcontainers | network 호출 금지 | CI ephemeral |
| staging | 비식별 demo dataset | 제한된 실연동 + replay | main 자동 배포, 팀 접근 |
| production | 실제 사용자 최소 데이터 | 승인된 production key | 수동 승인 |

production data를 local/staging으로 복사하지 않는다. 장애 재현에는 구조화 필드만 scrub한 synthetic fixture를 만든다.

### Environment 소유권과 격리

| 환경 | Config DRI | 변경 승인 | AWS/account 원칙 | Reset |
| --- | --- | --- | --- | --- |
| local | 각 개발자 | 본인, 공유 example은 상대 review | AWS credential 불필요 | guard된 local 명령만 |
| test | 해당 code DRI | CI | ephemeral PostgreSQL, 외부 network 차단 | job 종료 시 폐기 |
| staging | BE_AI_DRI | contract 영향 시 FE_DRI | non-production account/role/secret | 승인형 seed refresh만 |
| production | BE_AI_DRI | FE_DRI 승인 필수 | dedicated account 권장, production OIDC role | 일반 reset 금지 |

AWS account ID, role ARN, alarm address 같은 deployment metadata는 GitHub environment variable 또는 보호된 운영 설정에서 관리한다. 값 자체는 공개 문서에 넣지 않지만 owner와 검증 상태는 결정 대장/release manifest에 남긴다.

## 2. Frontend build-time 설정

Vite의 `VITE_` 변수는 build output에 공개된다. secret을 넣을 수 없다.

| 변수 | 예 | 필수 | 설명 |
| --- | --- | --- | --- |
| `VITE_APP_ENV` | `staging` | 예 | 표시/telemetry 환경 구분 |
| `VITE_API_BASE_URL` | `/api/v1` | 예 | 동일 origin 상대 경로 권장 |
| `VITE_APP_VERSION` | git SHA | 예 | 오류/analytics release 연결 |
| `VITE_DEFAULT_LOCALE` | `ko-KR` | 예 | 초기 locale |
| `VITE_DEFAULT_TIMEZONE` | `Asia/Seoul` | 예 | 초기 timezone |
| `VITE_MAP_STYLE_ID` | 공개 style id | provider 결정 후 | 공개 가능 identifier만 |
| `VITE_SENTRY_DSN` | public DSN | 선택 | 도입 시 개인정보 설정 검토 |

`VITE_KTO_KEY`, `VITE_SEOUL_KEY`, DB credential, AWS key 같은 이름은 금지하며 build scan에서 차단한다.

## 3. Backend 일반 설정

| 변수 | Secret | 기본/예 | 설명 |
| --- | --- | --- | --- |
| `NULLNULL_ENV` | 아니오 | `local` | 1절 환경과 같은 어휘 `local`/`test`/`staging`/`production`. 이 넷 밖의 값은 access-log·provider host guard에서 startup 실패한다 |
| `SERVER_PORT` | 아니오 | `8080` | container port |
| `APP_PUBLIC_ORIGIN` | 아니오 | `http://localhost:5173` | CORS/Origin 검증 |
| `APP_COOKIE_DOMAIN` | 아니오 | 모든 환경에서 비움 | `__Host-` cookie에 Domain attribute 금지 |
| `APP_COOKIE_SECURE` | 아니오 | 기본 `true`, `false`는 local 단독 profile만 | test/integration/cloud는 `true`; Domain 금지 |
| `APP_SESSION_TTL` | 아니오 | `P30D` | idle sliding expiry |
| `APP_SESSION_ABSOLUTE_TTL` | 아니오 | `P90D` | 생성부터 absolute 상한; idle 30일의 3배. **확정(2026-09-13 오너 승인, PM-017)** |
| `APP_CSRF_TOKEN_TTL` | 아니오 | `PT2H` | tab token 갱신 주기; session 만료보다 길지 않음. **확정(2026-09-13 오너 승인, PM-017)** |
| `nullnull.session.touch-interval` | 아니오 | `PT1M` | 반복 요청 DB touch 제한; 첫 비-bootstrap 요청은 항상 기록. **확정(2026-09-13 오너 승인, PM-017)** |
| `APP_IMPORT_DRAFT_TTL` | 아니오 | `PT24H` | structured draft only |
| `APP_IDEMPOTENCY_TTL` | 아니오 | `PT24H` | replay record 보존, 최소 `PT1M` |
| `APP_IDEMPOTENCY_LOCK_TIMEOUT` | 아니오 | `PT3S` 제안값 | guarded transaction의 `lock_timeout`, 최소 `PT0.1S`. 만료는 BA-003의 bounded retry가 흡수한다. 근거와 확정 조건은 아래 |
| `APP_REVERT_WINDOW` | 아니오 | `PT24H` | optimization undo |
| `APP_DELETION_RETRY_LIMIT` | 아니오 | `5` 제안값 | 삭제 job의 `max_attempts`; BA-012가 enqueue 시 읽고 1~20을 강제한다 |
| `APP_DELETION_STATUS_TOKEN_TTL` | 아니오 | `P7D` | 삭제 상태 bearer hash 보존 기간. 경계 시각부터 410이며 sweep은 hash를 null로 만든다 |
| `APP_DELETION_TOMBSTONE_RETENTION` | 아니오 | `P21D` 제안값 | backup 최대 보존 14일과 추가 7일을 덮는 restore 재삭제 manifest 보존 |
| `NULLNULL_DELETION_TOKEN_SECRET` | production 예 | runtime secret | request ID와 만료 시각을 묶는 HMAC-SHA256 key, UTF-8 32 byte 이상. cursor secret과 분리 |
| `APP_NOTIFICATION_RETENTION` | 아니오 | P1 정책 승인값 | 알림 보존/cleanup |
| `APP_SEARCH_MAX_QUERY_LENGTH` | 아니오 | OpenAPI constraint와 동일 | abuse/log 노출 최소화 |
| `APP_MAX_REQUEST_BODY_BYTES` | 아니오 | `262144` 제안값 | request body 상한(byte), 최소 `4096`. 선언된 `Content-Length` 초과는 body를 읽기 전에, chunked 초과는 stream 중에 413 `INVALID_REQUEST`다. 근거는 아래 |
| `APP_ACCESS_LOG_INCLUDE_QUERY` | 아니오 | `false` | 검색어/identifier query logging 차단. access log filter가 실제로 읽으며, `NULLNULL_ENV=production`에서 `true`면 startup이 실패한다 |
| `APP_LOG_RETENTION_DAYS` | 아니오 | `30` IaC input | CloudWatch policy |
| `APP_CROWD_DEFAULT_STALE_AFTER` | 아니오 | source override 필요 | fallback only |
| `NULLNULL_JOBS_ENABLED` | 아니오 | `true` | job worker polling과 보존 sweep을 함께 켠다. `false`는 test/점검 전용이며 보존 sweep도 함께 멈춘다. 값이 없으면 startup에서 실패한다(primitive 기본값 `false`로 조용히 꺼지지 않게). 꺼져 있거나 아직 시작하지 않았으면 readiness `jobs`가 DEGRADED다 |
| `NULLNULL_JOB_LEASE` | 아니오 | `PT60S` 제안값 | claim이 잡는 lease 길이, 최소 `PT1S`. heartbeat 주기는 lease/3으로 파생한다 |
| `NULLNULL_JOB_POLL_INTERVAL` | 아니오 | `PT1S` 제안값 | type별 claim 주기, 최소 `PT0.01S` |
| `NULLNULL_JOB_LOCK_TIMEOUT` | 아니오 | `PT3S` 제안값 | job 자신의 transaction에 거는 `lock_timeout`, 최소 `PT0.1S` |
| `NULLNULL_JOB_MAX_ATTEMPTS` | 아니오 | `5` 제안값 | enqueue가 허용하는 `max_attempts` 상한(1..20). 각 job은 자기 값을 따로 정한다 |
| `NULLNULL_JOB_RETRY_BACKOFF` | 아니오 | `PT10S` 제안값 | 첫 재시도 지연, 실패마다 2배, 최소 `PT1S` |
| `NULLNULL_JOB_MAX_RETRY_BACKOFF` | 아니오 | `PT5M` 제안값 | 재시도 지연 상한, `NULLNULL_JOB_RETRY_BACKOFF` 이상 |
| `NULLNULL_JOB_DEAD_LETTER_WINDOW` | 아니오 | `PT15M` 제안값 | 이 구간에 FAILED job이 있으면 readiness `jobs`가 DEGRADED, 최소 `PT1M` |
| `NULLNULL_JOB_FINISHED_RETENTION` | 아니오 | `P7D` 제안값 | COMPLETED/FAILED job row 보존, 최소 `PT1H` |
| `NULLNULL_JOB_RETENTION_SWEEP_INTERVAL` | 아니오 | `PT1M` 제안값 | bootstrap 15분 만료 후 다음 sweep에서 정리, 최소 `PT1M` |
| `NULLNULL_JOB_DEFAULT_CONCURRENCY` | 아니오 | `2` 제안값 | type별 동시 실행 기본값(1..64). 아래 connection budget에 걸리면 startup에서 실패한다 |
| `NULLNULL_DB_POOL_MAX` | 아니오 | `10` | `spring.datasource.hikari.maximum-pool-size`. HTTP thread와 job worker가 같이 쓰는 pool이다 |
| `NULLNULL_AI_BASE_URL` | 아니오/내부 | `http://127.0.0.1:8090` local, ECS 내부 DNS cloud | 추천 서비스 `apps/ai` 주소; 공개 host 금지 |
| `NULLNULL_AI_CONNECT_TIMEOUT` | 아니오 | `PT2S` | gateway connect timeout |
| `NULLNULL_AI_READ_TIMEOUT` | 아니오 | `PT5S` | gateway read timeout; readiness probe는 별도 1초 |
| `NULLNULL_CATALOG_PUBLIC_ENABLED` | 아니오 | `false` | C3 canonical 장소 projection의 release gate. local/test 검증 외에는 C2 T3 staging KTO provenance와 최종 AWS release 전까지 `true` 금지 |
| `NULLNULL_CURSOR_SECRET` | 예 | runtime | feed/history/catalog opaque cursor 서명 key. catalog projection을 production에서 켤 때 UTF-8 32 byte 이상 별도 값이 필요 |
| `NULLNULL_CROWD_MAX_RANGE_DAYS` | 아니오 | `30` | `getPlaceCrowdForecast`의 from~to 상한(일). provider가 30일 일 단위 series만 발표하므로 1~31 밖의 값은 startup에서 거부한다 |
| `NULLNULL_PROVIDER_CONNECT_TIMEOUT` | 아니오 | `PT2S` | 외부 provider TCP connect 상한 |
| `NULLNULL_PROVIDER_REQUEST_TIMEOUT` | 아니오 | `PT5S` | provider 전체 요청 상한; API request executor와 분리 |
| `NULLNULL_PROVIDER_MAX_RESPONSE_BYTES` | 아니오 | `2097152` | provider 응답 최대 byte; 초과는 안전한 provider failure |
| `NULLNULL_PROVIDER_EXECUTOR_THREADS` | 아니오 | `8` 제안값 | 외부 I/O 전용 bounded executor thread 수 |
| `NULLNULL_PROVIDER_EXECUTOR_QUEUE_CAPACITY` | 아니오 | `32` 제안값 | provider executor 대기열 상한; 가득 차면 거부하고 API worker를 쓰지 않음 |
| `NULLNULL_PROVIDER_PER_SOURCE_CONCURRENCY` | 아니오 | `4` 제안값 | source 하나가 executor를 독점하지 못하게 하는 permit 수 |
| `NULLNULL_PROVIDER_RETRY_ATTEMPTS` | 아니오 | `3` | IO/5xx/429만 retry하는 전체 시도 수 |
| `NULLNULL_PROVIDER_RETRY_BASE_DELAY` | 아니오 | `PT0.1S` | jitter가 붙는 첫 retry 지연 |
| `NULLNULL_PROVIDER_RETRY_MAX_DELAY` | 아니오 | `PT2S` | retry 지연 상한; provider `Retry-After`도 이 상한 안에서만 존중 |
| `NULLNULL_PROVIDER_CIRCUIT_FAILURE_THRESHOLD` | 아니오 | `5` | 같은 source의 실패가 circuit을 여는 횟수 |
| `NULLNULL_PROVIDER_CIRCUIT_FAILURE_WINDOW` | 아니오 | `PT30S` | 위 실패 횟수를 세는 창 |
| `NULLNULL_PROVIDER_CIRCUIT_OPEN_DURATION` | 아니오 | `PT60S` | 열린 circuit이 빠른 안전 실패를 반환하는 기간 |
| `SPRING_PROFILES_ACTIVE` | 아니오 | `local` | profile |
| `SPRING_DATASOURCE_URL` | 아니오/민감 | JDBC URL | host는 내부 정보로 log redaction |
| `SPRING_DATASOURCE_USERNAME` | 예 | runtime | DB app role |
| `SPRING_DATASOURCE_PASSWORD` | 예 | runtime | Secrets Manager |
| `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` | 아니오 | `health,prometheus` 내부만 | public actuator 제한 |

duration은 ISO-8601 형식을 사용한다. 단위 없는 숫자는 Spring이 밀리초로 읽으므로 `APP_IDEMPOTENCY_TTL=24`는 24시간이 아니라 `PT0.024S`다. 두 idempotency duration은 위 최소값 미만이면 property 이름과 받은 값을 적어 startup에서 실패한다. production은 필수값 누락/안전하지 않은 cookie/CORS 설정이면 fail fast한다.

`NULLNULL_JOB_*` duration도 같은 규칙과 같은 startup 실패를 따른다. `NULLNULL_JOB_LEASE=60`은 1분이 아니라 60밀리초여서 어떤 handler도 lease 안에 끝내지 못하고 모든 job이 무한히 재인수되므로, 최소값 미만은 시작을 막는다.

위 job 값은 계약 문서의 수치가 아니라 BA-005의 engineering 제안값이다. 근거는 다음과 같고 실제 부하 측정 뒤 다시 정한다.

- lease `PT60S`와 heartbeat lease/3: heartbeat 한 번을 놓쳐도 lease가 남고, worker가 죽으면 1분 안에 다른 worker가 재인수한다.
- 재시도 `PT10S`→2배→`PT5M`: 일시적 provider 오류는 초 단위에 풀리고, 상한은 poison job이 attempt 상한까지 도달하는 시간을 사람이 대응할 수 있는 범위로 묶는다. jitter는 넣지 않는다(worker 수가 적고 claim이 이미 직렬화한다).
- attempt 상한 `5`: 위 backoff에서 시도 사이 대기는 10+20+40+80초 = 150초(2분 30초)이고 마지막 시도는 그 뒤에 실행된다. `NULLNULL_JOB_RETRY_BACKOFF`나 상한을 바꾸면 이 합도 함께 바뀐다. dead-letter alert가 사람에게 넘어가기 전 재시도로 풀릴 시간을 준다는 뜻이다.
- dead-letter window `PT15M`: readiness scrape 간격보다 충분히 길어 한 번의 dead letter를 놓치지 않고, 반복되지 않으면 스스로 해제된다.
- finished job 보존 `P7D`: 주말에 생긴 dead letter를 다음 근무일에 조사할 수 있고, table은 작게 유지된다.

`APP_MAX_REQUEST_BODY_BYTES=262144`(256 KiB)도 계약 수치가 아니라 BA-003의 engineering 제안값이다. [API README 14절](../api/README.md#14-요청-한도)에 적힌 가장 큰 입력에서 유도한다.

- 문서화된 최대 입력은 붙여넣기 원문 20,000자다. UTF-8 한글은 자당 3 byte이므로 자연스러운 형태로 60,000 byte다.
- 같은 20,000자를 client가 전부 `\uXXXX` escape로 보내면 자당 6 byte라서 120,000 byte다. 규격을 지키는 client가 만들 수 있는 최악이 이 값이다.
- 256 KiB = 262,144 byte는 그 최악의 약 2.18배, 자연스러운 형태의 약 4.37배다. envelope(locale·timezone·trip ID·draft item 등)까지 들어갈 여유가 남는다.
- 한 단계 아래 128 KiB(131,072)는 최악 형태 위로 11,072 byte만 남아 draft item 100개짜리 confirm envelope을 덮지 못하고, 한 단계 위 512 KiB는 이를 정당화할 문서화된 입력이 없다.
- 최소 `4096`: 그보다 낮은 값은 정상 요청도 통과할 수 없어 설정 실수가 "전부 거부하는 API"로 보이므로 startup에서 막는다.

상한을 넘은 요청에는 **regime이 둘 있고 둘 다 의도된 동작**이다. 거절한 body의 남은 bytes를 서버가 읽어 버려야 connection을 재사용할 수 있는데, 그 예산이 `server.tomcat.max-swallow-size`(`apps/api/src/main/resources/application.yaml`)다.

- 예산이 재는 것은 body 총량이 아니라 **거절 시점 이후 남은 bytes**다. 상한을 넘은 순간 이미 상한만큼은 읽힌 뒤이므로, 경계는 대략 `상한 + 예산 = 262144 + 2097152 = 2359296` byte 부근이고 정확한 지점은 converter가 미리 읽어 둔 buffer 크기만큼 움직인다.
- 남은 bytes가 예산 안이면: 깨끗한 `413 INVALID_REQUEST` Problem이 온다. 선언된 `Content-Length`든 chunked든 같다.
- 남은 bytes가 예산을 넘으면: 서버가 나머지를 읽지 않고 connection을 끊는다. caller는 전송 타이밍에 따라 먼저 413을 받거나 HTTP 응답 없이 transport 오류를 받는다. 후자는 network 실패로 처리한다. raw-socket 검사는 응답과 독립적으로 upload하고 후속 request를 pipeline하여 connection 재사용이 거부됨을 검사한다.
- 예산은 `2097152` byte(2 MiB) = 허용 상한 `262144`의 8배이며, Tomcat 기본값과 같은 수다. 명시적으로 적는 이유는 값을 바꾸기 위해서가 아니라 두 regime의 경계를 우리가 고른 수로 고정하기 위해서다. `APP_MAX_REQUEST_BODY_BYTES`를 바꾸면 이 값도 함께 다시 정한다.
- 무제한(`-1`)은 쓰지 않는다. 이미 거절한 body를 끝없이 읽는 것은 DoS 경로다.
- 두 regime은 `RequestBodySwallowBoundIT`가 shipped 상한(`262144`)에서 고정한다. `RequestBodyLimitIT`는 상한을 `8192`로 낮춰 property가 실제로 배선됐는지만 확인하므로 이 경계를 볼 수 없다.

`APP_IDEMPOTENCY_LOCK_TIMEOUT=PT3S`도 BA-003의 engineering 제안값이며, 확인 가능한 근거는 측정이 아니라 구조 하나다. guard는 만료된 lock wait을 transaction 전체 재시도로 흡수하고 시도 횟수는 둘이므로(`IdempotencyGuard.LOCK_CONTENTION_ATTEMPTS`), caller가 겪는 최악은 `2 x PT3S = 6초`이고 그 뒤가 `INTERNAL_ERROR`다. 값을 올리면 그 6초가 같이 늘어난다. 바닥 `PT0.1S`는 PostgreSQL이 `lock_timeout = 0`을 "무한 대기"로 읽어 bound 자체가 사라지기 때문이다(`IdempotencyGuard.MINIMUM_LOCK_TIMEOUT`).

이 값은 **측정으로 뒷받침되지 않았다**. `IdempotencyGuard.execute`를 호출하는 production code가 아직 없어서(B01은 command endpoint를 내보내지 않는다) "가장 느린 command"라고 부를 대상이 없다. 첫 실제 command endpoint를 만드는 slice가 그 command의 최악 소요를 suite에 남는 test로 재고 `PT3S`가 그것을 덮는지 확인하면, 그때 확정값이 된다.

type별 동시 실행은 `nullnull.jobs.concurrency.<type>` property로 덮는다(예: `nullnull.jobs.concurrency.deletion=1`). map key라서 환경변수보다 설정 파일/실행 인자로 지정한다.

동시 실행은 thread만이 아니라 **connection**을 쓴다. worker는 HTTP thread와 같은 Hikari pool을 쓰므로, worker가 pool을 다 가져가면 readiness의 database probe가 connection을 못 받아 503이 되고 task가 ALB에서 빠진다(측정: 한 type을 concurrency 10으로 두고 unit of work를 잡게 하니 `/health/ready`가 10.08초 뒤 503). 그래서 시작할 때 최악 수요를 계산해 넘으면 startup에서 실패한다.

- 최악 수요 = `2 x slots + types + 1`. `slots`는 type별 concurrency의 합이다. in-flight job 하나가 unit of work로 connection 1개, 같은 job의 heartbeat가 다른 thread에서 1개를 더 쓰고, type마다 poll의 claim이 1개, 보존 sweep이 1개다.
- 여유분은 2개다. readiness 응답 한 건이 한 번에 connection 1개를 쓰고(database probe → jobs probe 순차), ALB health check와 다른 호출이 겹칠 수 있어서다.
- 조건: `2 x slots + types + 1 + 2 <= NULLNULL_DB_POOL_MAX`. 실패 message가 두 수와 대처(`NULLNULL_JOB_DEFAULT_CONCURRENCY`/`nullnull.jobs.concurrency.<type>` 낮추기 또는 `NULLNULL_DB_POOL_MAX` 올리기)를 함께 적는다.
- 현재 BA-012의 실제 handler type은 `delete-owner-data` 하나다. 기본 concurrency 2이면 `2x2+1+1 = 6`, readiness 여유분까지 8이므로 pool 10에 들어간다. B03/B06에서 type이 늘면 같은 공식으로 다시 계산한다.

삭제 receipt 행은 별도 환경값으로 임의 단축하지 않는다. status bearer hash는 7일에 null로 만들고,
tombstone과 owner 행은 `retain_until`을 지났더라도 30일 revoked session과 24시간 idempotency row가
실제로 사라진 뒤에만 hard delete한다. local/test/integration은 secret이 비었을 때 process마다 임시 key를
생성하므로 재시작을 넘는 status polling이 필요하면 명시적으로 설정해야 한다.

### 추천 서비스 `apps/ai` 설정

[ADR-0006](../decisions/ARCHITECTURE_DECISIONS.md#adr-0006)의 Python 서비스는 DB·외부 API 설정을 갖지 않는다.

| 변수 | Secret | 기본/예 | 설명 |
| --- | --- | --- | --- |
| `NULLNULL_ENV` | 아니오 | `local` | local/test/staging/production |
| `NULLNULL_AI_BIND_HOST` | 아니오 | `127.0.0.1` local, `0.0.0.0` container | bind address |
| `NULLNULL_AI_PORT` | 아니오 | `8090` | 1024..65535 |
| `NULLNULL_CATALOG_VERSION` | 아니오 | staging/production 필수, 없으면 startup 실패 | 응답 `catalogVersion`; local/test는 `catalog-unversioned-<env>` placeholder |
| `AI_PROVIDER` | 아니오 | `NONE` | P0 허용값은 `NONE`뿐; `OPENAI`는 BA-084 adapter 전까지 startup 실패 |
| `AI_API_KEY`, `AI_MODEL_ID`, `AI_TIMEOUT` | 4절과 동일 | BA-084 | provider `OPENAI` 승인 뒤 이 서비스에서만 읽는다 |
| `NULLNULL_AI_REPORT_DIR` | 아니오 | test stage만 | `evaluation.json` 출력 위치 |

보존 기간은 이 문서의 임의 default가 개인정보 정책보다 우선하지 않는다. OpenAPI/ERD/cleanup job/IaC 값이 다르면 startup 또는 contract test가 실패해야 한다.

## 4. 외부 source 설정

| 변수 | Secret | 설명 |
| --- | --- | --- |
| `KTO_SERVICE_KEY` | 예 | 공공데이터포털 KTO **decoding key**; runtime에만 주입 |
| `KTO_BASE_URL` | 아니오 | C2 exact `https://apis.data.go.kr/B551011/KorService2`; contest profile에서는 다른 path/host 거부 |
| `KTO_FORECAST_BASE_URL` | 아니오 | C4 exact `https://apis.data.go.kr/B551011/TatsCnctrRateService`; KorService2의 하위 경로가 아닌 별도 승인 endpoint이며 contest profile에서는 다른 path/host 거부 |
| `KTO_MOBILE_APP`, `KTO_MOBILE_OS` | 아니오 | C2 `detailCommon2` request metadata; 기본 `Nullnull`/`ETC` |
| `APP_RELEASE_VERSION` | 아니오 | safe `api_ingest_logs.release_version`; credential나 URL이 아님 |
| `APP_CONTEST_PROFILE` | 아니오 | `2026_KTO_WEBAPP`이면 KTO key와 exact base가 startup invariant |
| `KTO_TIMEOUT` | 아니오 | connect/read timeout |
| `KTO_RATE_LIMIT_PER_SECOND` | 아니오 | 승인 quota 이하 |
| `KTO_ALLOWED_HOST` | 아니오 | `apis.data.go.kr`; C1 HTTP allowlist. production은 이 exact host 집합만 허용하며 `127.0.0.1` 같은 test stub으로 drift할 수 없다 |
| `SEOUL_API_KEY` | 예 | 서울 열린데이터 API key |
| `SEOUL_BASE_URL` | 아니오 | 공식 endpoint |
| `SEOUL_TIMEOUT` | 아니오 | timeout |
| `SEOUL_ALLOWED_HOST` | 아니오 | `openapi.seoul.go.kr`; C1 HTTP allowlist. production은 이 exact host만 허용한다 |
| `MAP_PROVIDER` | 아니오 | `NONE` P0, provider 결정 후 enum |
| `MAP_API_KEY` | 예 | backend route/geocode key |
| `MAP_BASE_URL` | 아니오 | provider endpoint |
| `AI_PROVIDER` | 아니오 | `NONE` P0 기본; `OPENAI`는 P1 BA-084에서 추가하는 enum, 읽는 곳은 `apps/ai` |
| `AI_API_KEY` | 예 | P1 AI 설명/보조 기능 승인 뒤 |
| `AI_MODEL_ID` | 아니오 | 평가로 승인한 exact model identifier |
| `AI_TIMEOUT` | 아니오 | request/job timeout |

`ProviderHttpClient`는 redirect를 따르지 않고 source별 exact hostname·HTTPS만 허용한다. local/test fixture는 `127.0.0.1` override를 쓸 수 있지만 production은 `KTO_KOR_SERVICE_2`/`KTO_CONCENTRATION_FORECAST`/`KTO_RELATED_PLACES`의 `apis.data.go.kr` 및 `SEOUL_CITYDATA`의 `openapi.seoul.go.kr` 외의 host, 누락 source, 추가 source 설정으로 startup하지 않는다. C2 KTO client는 그 위에 `KorService2` exact path와 `detailCommon2` operation을 추가로 고정한다. provider key·전체 URL/query·응답 원문은 config/log/audit에 남기지 않는다.

## 5. AWS runtime metadata

일반적으로 AWS SDK default credential chain/task role을 사용하고 access key 변수를 만들지 않는다.

| 변수 | Secret | 설명 |
| --- | --- | --- |
| `AWS_REGION` | 아니오 | `ap-northeast-2` |
| `AWS_SECRETS_PREFIX` | 아니오 | environment별 secret namespace |
| `AWS_ASSET_BUCKET` | 아니오 | P1 media bucket name |
| `OTEL_SERVICE_NAME` | 아니오 | `nullnull-api` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | 내부 | collector 도입 시 |

`AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`를 ECS/GitHub repository secret으로 운영하지 않는다. GitHub는 OIDC, ECS는 task role을 쓴다.

다음은 build/runtime에 주입되는 release metadata이며 secret이 아니다.

| 변수 | 설명 |
| --- | --- |
| `APP_RELEASE_VERSION` | immutable release/tag version |
| `APP_GIT_SHA` | build commit SHA |
| `APP_CONTRACT_SHA` | OpenAPI+event schema digest |
| `APP_BUILD_RUN_ID` | CI run 추적값 |
| `APP_CONTEST_PROFILE` | 제출 release에서만 `2026_KTO_WEBAPP`, 그 외 `NONE` |

FE의 `VITE_APP_VERSION`과 API의 release metadata는 같은 release manifest를 가리켜야 한다. 값 불일치는 staging smoke에서 실패한다.

## 6. Feature flag

| Flag | P0 기본 | 설명/제거 조건 |
| --- | --- | --- |
| `NULLNULL_CATALOG_PUBLIC_ENABLED` | OFF | C3 local projection은 기본 차단. C2 T3 staging provenance와 최종 AWS release에서만 별도 cursor secret과 함께 ON 가능 |
| `FEATURE_PASTE_IMPORT_SERVER` | OFF | browser parser 부족 시 승인 후 ON |
| `FEATURE_LIVE_DATA` | OFF (모든 환경) | B10이 live source를 붙이는 slice에서만 ON 가능. source 불가 시 replay/empty |
| `FEATURE_REPLAY_MODE` | OFF (모든 환경) | B03이 replay dataset을 만드는 slice에서만 ON 가능. production 강제 replay는 banner 필요 |
| `FEATURE_OPTIMIZATION_ITEM` | OFF (모든 환경) | B06 safety gate를 통과하는 slice에서만 ON 가능 |
| `FEATURE_OPTIMIZATION_DAY` | OFF | P1 |
| `FEATURE_OPTIMIZATION_TRIP` | OFF | P1 |
| `FEATURE_NOTIFICATIONS` | OFF | P1 |
| `FEATURE_NEARBY_LOCATION` | OFF | P1 + privacy review |
| `FEATURE_POST_CREATION` | OFF | P1 + moderation/media |
| `FEATURE_PROFILE_HISTORY` | OFF local 초기 → B06 ON | P0 이력 계약·cursor/보존 test 통과 후 |
| `FEATURE_ACCOUNT_LOGIN` | OFF | account merge/recovery/security 정책 후 |
| `FEATURE_TRIP_INTERESTS_PROFILE` | OFF local 초기 → B04/BA-031 검수 후 ON | P0 여행별 관심사 ETag 계약 후 |
| `FEATURE_AI_DRAFT` | OFF | 평가/근거/비용/privacy gate 후 |

flag는 backend capability response가 정본이다. frontend build flag만으로 권한/안전 기능을 제어하지 않는다.

BA-003이 `getDemoReadiness`에 연결한 flag는 `FEATURE_LIVE_DATA`·`FEATURE_REPLAY_MODE`·`FEATURE_OPTIMIZATION_ITEM` 셋이며, capability 이름은 각각 `live`·`replay`·`optimization`이다(`FR-OPS-02`). flag는 기능을 끄는 방향으로만 쓴다. P0에는 셋 다 server-side source가 없어 응답은 `UNAVAILABLE`이고, `true`로 켜면 9절의 "LIVE feature가 ON이면 source registry/key/readiness 설정 존재" 규칙에 따라 startup이 실패한다. source를 만드는 slice(B03 replay, B06 optimization, B10 live)가 그 flag를 켤 수 있게 된다.

공모전 profile `2026_KTO_WEBAPP`은 다음 startup invariant를 추가한다.

- `FEATURE_ACCOUNT_LOGIN`, `FEATURE_NEARBY_LOCATION`, `FEATURE_NOTIFICATIONS`, `FEATURE_POST_CREATION`, `FEATURE_OPTIMIZATION_DAY`, `FEATURE_OPTIMIZATION_TRIP`은 OFF다.
- `KTO_SERVICE_KEY`가 runtime secret으로 존재하고 `KTO_BASE_URL`·`KTO_FORECAST_BASE_URL`이 각각 공식 allowlist와 일치한다. 둘 중 하나라도 어긋나면 startup이 실패한다.
- KTO 실제 호출과 redacted call-audit가 활성화되고, fixture-only/replay-only provider가 primary가 아니다.
- 익명 demo session, 한국어/영어, P0 핵심 capability가 readiness에 나타난다.
- Frontend에는 profile 이름과 공개 capability만 전달하며 secret이나 provider credential을 전달하지 않는다.

## 7. Local secret 관리

- `.env.example`에는 placeholder와 설명만 둔다.
- 실제 값은 gitignore된 `.env.local` 또는 OS/keychain secret tool에 둔다.
- shell history에 secret을 직접 입력하지 않는다.
- test는 fake key와 network stub을 사용한다.
- debug log level에서도 configuration value를 전체 출력하지 않는다.
- 실제 KTO 호출 증거를 만드는 operator smoke는 별도 승인 변수가 있어야 한다. C2 `ktoSmoke`는 `NULLNULL_KTO_SMOKE_APPROVED=true`(+`NULLNULL_KTO_SMOKE_CONTENT_ID`/`_CONTENT_TYPE_ID`), C4 `ktoForecastSmoke`는 `NULLNULL_KTO_FORECAST_SMOKE_APPROVED=true`(+`NULLNULL_KTO_FORECAST_SMOKE_PLACE_ID`)가 필요하며 둘 다 redacted ID만 출력한다. CI와 PR gate는 이 변수를 설정하지 않는다.
- **승인 변수는 `.env.local`에서 읽히지 않는다.** `KtoSmokeEnvironment.ALLOWED_NAMES`에 없고 두 main이 `System.getenv()`로만 읽으므로, **승인은 명령을 실행하는 사람의 shell이 갖는다.** 파일에 적어도 승인이 되지 않는 것이 설계다 — 감사 기록의 출처가 사람이어야 하기 때문이다.
- **세 단계이며 순서가 있다.** `ktoForecastSmoke`는 `place_external_refs`를 join하는데 그 행은 canonical ingest만 만든다. C2 gateway는 자기 snapshot을 스스로 매핑하지 않으므로(의도된 분리), 가운데 단계 없이 C4를 돌리면 `NoVerifiedKtoMappingException`으로 끝난다.

**선행 조건 넷.** 하나라도 빠지면 실패 메시지가 원인을 가리키지 않는다.

1. **앱이 붙을 포트를 우리가 띄운 container가 publish한다.** container가 떴는지만 보면 부족하다 — container가 한 포트에 떠 있고 `.env.local`이 다른 포트(가령 host postgres가 잡고 있는 자리)를 가리키는 상태가 **그 검사를 통과한다.** 그래서 `.env.local`이 가리키는 포트와 **실제로 publish된 포트를 묶어서** 본다. 아래 명령이 포트 숫자를 박아 두지 않는 이유이기도 하다.
2. **`SPRING_DATASOURCE_*` 세 값이 그 DB와 맞는다.** 비밀번호는 `compose.yml`의 `local-only`다. 포트의 저장소 기본값은 **5434**이고, 기기마다 다를 수 있으니 `.env.local`을 정본으로 본다.
3. **shell에 `SPRING_DATASOURCE_*`가 export돼 있지 않다.** 있으면 `.env.local`을 **덮는다**(아래).
4. **`JAVA_HOME`이 Temurin 21**이고, Gradle daemon이 예전 환경을 들고 있지 않다(`./gradlew --stop`).

명령은 저장소 root에서 시작한다. 대화형 zsh는 `interactive_comments`가 기본 off라 **`#` 주석을 붙여 붙여넣으면 `command not found: #`**가 나므로 블록 안에 주석을 두지 않는다.

```bash
cd "$(git rev-parse --show-toplevel)"
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
env | grep '^SPRING_DATASOURCE_' && echo 'WARNING: these override .env.local'
docker compose up -d postgres
port=$(sed -n 's#^SPRING_DATASOURCE_URL=jdbc:postgresql://[^:]*:\([0-9]\{1,5\}\)/.*#\1#p' apps/api/.env.local)
docker ps --format '{{.Ports}}' | grep -q ":${port}->" \
  && echo "OK: a running container publishes ${port}" \
  || echo "FAIL: .env.local points at ${port} and no container publishes it"
```

```bash
cd "$(git rev-parse --show-toplevel)/apps/api"
./gradlew --stop
NULLNULL_KTO_SMOKE_APPROVED=true \
NULLNULL_KTO_SMOKE_CONTENT_ID=126508 \
NULLNULL_KTO_SMOKE_CONTENT_TYPE_ID=12 \
  ./gradlew ktoSmoke --console=plain
```

```bash
NULLNULL_KTO_INGEST_CONTENT_ID=126508 \
NULLNULL_KTO_INGEST_CONTENT_TYPE_ID=12 \
  ./gradlew ktoCanonicalIngest --console=plain
```

```bash
NULLNULL_KTO_FORECAST_SMOKE_APPROVED=true \
NULLNULL_KTO_FORECAST_SMOKE_PLACE_ID=<2단계가 출력한 placeId> \
  ./gradlew ktoForecastSmoke --console=plain
```

  **`detailIntro2` 탐색 probe는 이 3단계와 별개이고 0단계가 필요 없다(A-027).** DB를 쓰지 않기 때문이다 — snapshot도 collector run도 audit row도 만들지 않고 응답의 **field 모양만** 출력한다. 그래서 container가 떠 있든 아니든 결과가 같다. 승인 변수는 여기서도 **shell이 갖는다**(`.env.local`에서 읽히지 않는다). 1회만 돌린다.

```bash
cd "$(git rev-parse --show-toplevel)/apps/api"
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
NULLNULL_KTO_INTRO_PROBE_APPROVED=true \
NULLNULL_KTO_INTRO_PROBE_CONTENT_ID=126508 \
NULLNULL_KTO_INTRO_PROBE_CONTENT_TYPE_ID=12 \
  ./gradlew ktoIntroProbe --console=plain
```

  출력은 `KTO_INTRO_PROBE_FIELD name=... type=... length=...` 한 줄씩이고, `usetime`·`restdate`만 앞 40자 미리보기와 줄 수·markup 여부가 붙는다. **원문 body는 찍지 않는다**(`CMP-KTO-008`). 이 probe는 `SOURCE_CATALOG`의 승인 범위를 넓히지 않는다 — 채택은 응답을 본 뒤의 별도 결정이고, 자유 텍스트로 판명되면 파싱하지 않는다(불변식 9).

  **0단계를 `up -d`만으로 끝내지 않는 이유(실측 2026-09-13).** 이 기기에서 `docker compose up -d postgres`는 실패했다 — `bind: address already in use`. **Docker가 아닌 host PostgreSQL이 127.0.0.1:5433을 이미 잡고 있었고**, `compose.yml`의 주석이 5433을 고른 이유가 바로 그 충돌 회피였는데 그 자리가 이미 점유돼 있었다. `nullnull-local-postgres-1`은 그때까지 `Created` 상태로 **한 번도 뜬 적이 없었다.** 오너 결정으로 host port를 **5434**로 옮겼다.

  위험한 쪽은 실패가 아니라 **그 뒤에도 앱이 동작한다는 것**이다. `SPRING_DATASOURCE_URL`이 점유된 포트를 가리키면 연결은 성공하고, 상대는 **host 서버**다. 그대로 두면 Flyway가 프로젝트와 무관한 서버에 migration을 건다 — `CLAUDE.md`의 *"test는 live demo/dev database에 대고 돌리지 않는다"* 를 정면으로 어긴다. 게다가 `docker compose ... | tail` 처럼 파이프를 쓰면 **exit code가 사라져** 실패가 보이지도 않는다.

  **결정(2026-09-13, 오너):** host PostgreSQL은 그대로 두고 저장소의 publish 포트를 **5434**로 옮긴다(`compose.yml`, `.env.example`, `application-local.yaml`). 기기별 예외가 필요하면 gitignored override를 쓰되, 어느 쪽이든 **0단계의 확인이 통과해야** 1단계로 간다.

  **그리고 그 확인이 liveness가 아니라 신원을 봐야 한다.** "container가 떴다"와 "앱이 붙을 곳이 그 container다"는 다르다 — 포트를 옮기면서 `.env.local`만, 혹은 compose만 고치면 **container는 멀쩡히 running인 채로 앱은 host 서버에 붙는다.** 이번에 우리를 구한 것은 그 host 서버의 비밀번호가 달랐다는 우연뿐이고, 맞았다면 migration 18개가 남의 DB에 **오류 없이** 걸렸을 것이다. 그래서 0단계는 두 값을 **묶어서** 비교한다.

  **그리고 `.env.local`을 고쳤는데 반영이 안 될 수 있다.** `KtoSmokeEnvironment.load`는 파일을 먼저 읽은 뒤 **process 환경변수로 덮는다** — 그게 올바른 우선순위지만 **증상이 없다.** 파일은 맞는데 예전 값으로 계속 실패하고, 파일이 읽히긴 했는지조차 알 수 없다(실제로 운영자가 여기서 30분을 썼다). 그래서 세 main이 부팅 전에 **각 설정이 어디서 왔는지**를 출력한다.

```text
KTO_SMOKE_SETTINGS SPRING_DATASOURCE_URL <- process env (overrides .env.local)
KTO_SMOKE_SETTINGS KTO_SERVICE_KEY <- .env.local
KTO_SMOKE_SETTINGS KTO_FORECAST_BASE_URL <- absent
```

  **shell 우회로가 필요하면 `readDotenv`의 규칙을 그대로 재현한다.** 빈 값을 버리는 형태는 이렇다.

```bash
while IFS='=' read -r k v; do [ -n "$v" ] && export "$k=$v"; done < .env.local
```

  **`.env.local`을 shell로 내보내는 우회로를 쓸 때 주의.** 이 파일에는 의도적으로 **빈 값**이 여럿 있고(`APP_COOKIE_SECURE=` 등, `.env.example`의 규칙대로 사용자 값은 비워 둔다), `readDotenv`는 `if (!value.isEmpty())`로 **빈 값을 버린다** — 그래서 Spring 기본값이 살아난다. 그런데 `set -a; source .env.local`처럼 통째로 내보내면 **빈 문자열이 그대로 process 환경변수가 되어** 기본값을 누르고 `Invalid boolean value []`로 죽는다. 우회로는 `readDotenv`의 규칙을 재현해야 하며, 필요한 변수만 골라 넘기는 편이 안전하다.

  **이름과 출처만 찍고 값은 절대 찍지 않는다** — 이 목록에는 `KTO_SERVICE_KEY`와 `SPRING_DATASOURCE_PASSWORD`가 들어 있고, 값이 새면 진단이 제거하는 혼란보다 나쁘다. `KtoSmokeEnvironmentTest`가 그 부재를 변이로 고정한다.

  `NULLNULL_ENV`는 `local` 또는 `staging`이어야 하고(두 번 검사한다), `KTO_FORECAST_BASE_URL`은 `.env.local`에 있어야 한다(allowlist 값이고 어긋나면 startup이 실패한다). 성공 표식은 `KTO_SMOKE_OK`·`KTO_CANONICAL_INGEST_OK`·`KTO_FORECAST_SMOKE_OK`이고, 남는 증거는 `api_ingest_logs` 행·`collector_runs` outcome·`kto_place_snapshots`·`places`/`place_external_refs`·`crowd_snapshots`다. **`coverage=0`은 실패가 아니라 "그 장소에 예보 행이 없었다"는 뜻이므로 호출 증거로는 유효하되 예보 증거로는 쓰지 않는다.**

- B01 scaffold는 `apps/api/.env.example`, `apps/web/.env.example`를 새 계약에서 생성한다. 과거 prototype의 environment 변수는 이식하지 않는다.

exact tool version, port, seed와 guarded reset은 [LOCAL_DEVELOPMENT.md](../engineering/LOCAL_DEVELOPMENT.md)를 따른다. example 파일은 매 CI에서 실제 configuration binding과 비교해 누락/폐기 변수를 검출한다.

## 8. Secrets Manager namespace

예시 ARN/경로(실제 account id를 문서에 기록하지 않음):

```text
/nullnull/staging/database/application
/nullnull/staging/sources/kto
/nullnull/staging/sources/seoul
/nullnull/production/database/application
/nullnull/production/sources/kto
/nullnull/production/sources/seoul
```

- production/staging secret과 KMS/IAM policy를 분리한다.
- DB credential은 managed rotation 가능성을 우선 검토한다.
- 외부 API key는 사업자 절차에 따른 수동 rotation runbook과 owner를 둔다.
- secret access와 rotation failure는 CloudTrail/CloudWatch로 감시한다.
- rotation 뒤 ECS가 새 값을 읽는 방법(새 task rollout 또는 runtime refresh)을 secret별로 기록한다.

## 9. Configuration validation

startup에서 다음을 검증하고 production은 오류 시 시작하지 않는다.

- origin이 HTTPS이고 wildcard가 아님
- cookie secure=true
- datasource가 PostgreSQL이고 TLS 정책 충족
- secret placeholder/빈 값 없음
- timeout/rate limit/TTL이 안전 범위
- provider timeout/response byte/executor/permit/retry/circuit 값이 양수 범위이고 production source host가 reviewed exact allowlist와 일치
- LIVE feature가 ON이면 source registry/key/readiness 설정 존재
- P1 flag가 승인 없이 ON이 아님
- replay와 live가 동일 source state로 반환되지 않음
- logging body/cookie 옵션이 OFF
- application/CDN/APM access log가 query string과 검색어를 기록하지 않음
- deletion/notification/event retention이 정책과 DB cleanup schedule에 일치
- release version/git SHA/contract SHA가 비어 있지 않고 artifact manifest와 일치
- AI provider가 ON이면 approved model/evaluation/key/timeout/kill switch가 존재
- `apps/ai`는 staging/production에서 `NULLNULL_CATALOG_VERSION`이 없거나 `AI_PROVIDER`가 허용 enum 밖이면 시작하지 않음; Spring은 `NULLNULL_AI_BASE_URL`이 없으면 시작하지 않음
- production AWS account/stack prefix가 staging 값과 다름
- contest profile이면 위치/계정/P1 flag OFF, KTO 운영 secret·공식 host·call-audit·익명 session 준비 완료

readiness는 필수 DB 실패와 선택 source degradation을 구분한다. 선택 source 하나의 장애로 liveness를 실패시키지 않는다.

## 10. Secret incident

secret이 log, commit, artifact에 노출됐다고 의심되면 삭제만 하지 않는다.

1. 즉시 해당 key revoke/rotate.
2. 영향 환경과 접근 log 확인.
3. Git history/artifact/cache에서 제거하되 이미 노출된 key는 재사용하지 않음.
4. 서비스가 새 secret으로 동작하는지 확인.
5. 원인과 scanner/gate 보강을 기록.

security/privacy incident의 severity, acknowledgment와 통지 판단 시간은 [INCIDENT_RESPONSE.md](./INCIDENT_RESPONSE.md)를 따른다. secret이 노출된 commit을 단순 revert하는 것은 rotation을 대체하지 않는다.

## 계약 상수 검증

APP_REVERT_WINDOW=PT24H는 API 0.2.0의 decidedAt+24시간 계약이다. 다른 값으로 시작하려면 먼저 계약 변경 검토가 필요하며 현재 profile에서는 startup validation으로 거부한다. preview TTL·cursor15분·orphan bootstrap15분과 혼동하지 않는다. CI는 설정값, APPLY 응답 시각 계산, 만료 경계의 REVERT_WINDOW_EXPIRED를 함께 검증한다.
