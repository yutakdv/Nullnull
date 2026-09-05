# 환경변수·비밀·Feature flag

- 상태: Accepted naming contract
- 원칙: 설정은 환경별, secret은 runtime 주입, 공개값과 비밀값을 이름부터 분리

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
| `NULLNULL_ENV` | 아니오 | `local` | local/staging/production |
| `SERVER_PORT` | 아니오 | `8080` | container port |
| `APP_PUBLIC_ORIGIN` | 아니오 | `http://localhost:5173` | CORS/Origin 검증 |
| `APP_COOKIE_DOMAIN` | 아니오 | 비움(local) | production domain |
| `APP_COOKIE_SECURE` | 아니오 | `false` local, `true` cloud | prod false 금지 |
| `APP_SESSION_TTL` | 아니오 | `P30D` | session expiry |
| `APP_IMPORT_DRAFT_TTL` | 아니오 | `PT24H` | structured draft only |
| `APP_IDEMPOTENCY_TTL` | 아니오 | `PT24H` | replay record |
| `APP_REVERT_WINDOW` | 아니오 | `PT15M` | optimization undo |
| `APP_DELETION_RECEIPT_TTL` | 아니오 | 정책 승인값 | 완료/실패 receipt 보존 |
| `APP_DELETION_RETRY_LIMIT` | 아니오 | M0/M1 측정 후 고정 | 삭제 job 무한 재시도 방지 |
| `APP_NOTIFICATION_RETENTION` | 아니오 | P1 정책 승인값 | 알림 보존/cleanup |
| `APP_SEARCH_MAX_QUERY_LENGTH` | 아니오 | OpenAPI constraint와 동일 | abuse/log 노출 최소화 |
| `APP_ACCESS_LOG_INCLUDE_QUERY` | 아니오 | `false` | 검색어/identifier query logging 차단 |
| `APP_LOG_RETENTION_DAYS` | 아니오 | `30` IaC input | CloudWatch policy |
| `APP_CROWD_DEFAULT_STALE_AFTER` | 아니오 | source override 필요 | fallback only |
| `SPRING_PROFILES_ACTIVE` | 아니오 | `local` | profile |
| `SPRING_DATASOURCE_URL` | 아니오/민감 | JDBC URL | host는 내부 정보로 log redaction |
| `SPRING_DATASOURCE_USERNAME` | 예 | runtime | DB app role |
| `SPRING_DATASOURCE_PASSWORD` | 예 | runtime | Secrets Manager |
| `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` | 아니오 | `health,prometheus` 내부만 | public actuator 제한 |

duration은 ISO-8601 형식을 사용한다. production은 필수값 누락/안전하지 않은 cookie/CORS 설정이면 fail fast한다.

보존 기간은 이 문서의 임의 default가 개인정보 정책보다 우선하지 않는다. OpenAPI/ERD/cleanup job/IaC 값이 다르면 startup 또는 contract test가 실패해야 한다.

## 4. 외부 source 설정

| 변수 | Secret | 설명 |
| --- | --- | --- |
| `KTO_SERVICE_KEY` | 예 | 한국관광공사 API key |
| `KTO_BASE_URL` | 아니오 | 공식 endpoint, allowlist |
| `KTO_TIMEOUT` | 아니오 | connect/read timeout |
| `KTO_RATE_LIMIT_PER_SECOND` | 아니오 | 승인 quota 이하 |
| `SEOUL_API_KEY` | 예 | 서울 열린데이터 API key |
| `SEOUL_BASE_URL` | 아니오 | 공식 endpoint |
| `SEOUL_TIMEOUT` | 아니오 | timeout |
| `MAP_PROVIDER` | 아니오 | `NONE` P0, provider 결정 후 enum |
| `MAP_API_KEY` | 예 | backend route/geocode key |
| `MAP_BASE_URL` | 아니오 | provider endpoint |
| `AI_PROVIDER` | 아니오 | `NONE` P0 기본; 허용 provider enum |
| `AI_API_KEY` | 예 | P1 AI 설명/보조 기능 승인 뒤 |
| `AI_MODEL_ID` | 아니오 | 평가로 승인한 exact model identifier |
| `AI_TIMEOUT` | 아니오 | request/job timeout |

base URL override는 local/test fixture에 필요하지만 production에서는 hostname allowlist를 검증해 SSRF/잘못된 endpoint를 막는다.

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
| `FEATURE_PASTE_IMPORT_SERVER` | OFF | browser parser 부족 시 승인 후 ON |
| `FEATURE_LIVE_DATA` | OFF local, readiness 기반 cloud | source 불가 시 replay/empty |
| `FEATURE_REPLAY_MODE` | ON staging | production 강제 replay는 banner 필요 |
| `FEATURE_OPTIMIZATION_ITEM` | OFF → rollout | M5 safety gate 후 ON |
| `FEATURE_OPTIMIZATION_DAY` | OFF | P1 |
| `FEATURE_OPTIMIZATION_TRIP` | OFF | P1 |
| `FEATURE_NOTIFICATIONS` | OFF | P1 |
| `FEATURE_NEARBY_LOCATION` | OFF | P1 + privacy review |
| `FEATURE_POST_CREATION` | OFF | P1 + moderation/media |
| `FEATURE_PROFILE_HISTORY` | OFF local 초기 → M5 ON | P0 이력 계약·cursor/보존 test 통과 후 |
| `FEATURE_ACCOUNT_LOGIN` | OFF | account merge/recovery/security 정책 후 |
| `FEATURE_TRIP_INTERESTS_PROFILE` | OFF local 초기 → M1 ON | P0 여행별 관심사 ETag 계약 후 |
| `FEATURE_AI_DRAFT` | OFF | 평가/근거/비용/privacy gate 후 |

flag는 backend capability response가 정본이다. frontend build flag만으로 권한/안전 기능을 제어하지 않는다.

공모전 profile `2026_KTO_WEBAPP`은 다음 startup invariant를 추가한다.

- `FEATURE_ACCOUNT_LOGIN`, `FEATURE_NEARBY_LOCATION`, `FEATURE_NOTIFICATIONS`, `FEATURE_POST_CREATION`, `FEATURE_OPTIMIZATION_DAY`, `FEATURE_OPTIMIZATION_TRIP`은 OFF다.
- `KTO_SERVICE_KEY`가 runtime secret으로 존재하고 `KTO_BASE_URL`이 공식 allowlist와 일치한다.
- KTO 실제 호출과 redacted call-audit가 활성화되고, fixture-only/replay-only provider가 primary가 아니다.
- 익명 demo session, 한국어/영어, P0 핵심 capability가 readiness에 나타난다.
- Frontend에는 profile 이름과 공개 capability만 전달하며 secret이나 provider credential을 전달하지 않는다.

## 7. Local secret 관리

- `.env.example`에는 placeholder와 설명만 둔다.
- 실제 값은 gitignore된 `.env.local` 또는 OS/keychain secret tool에 둔다.
- shell history에 secret을 직접 입력하지 않는다.
- test는 fake key와 network stub을 사용한다.
- debug log level에서도 configuration value를 전체 출력하지 않는다.
- M0 scaffold는 `apps/api/.env.example`, `apps/web/.env.example`를 새 계약에서 생성한다. 과거 prototype의 environment 변수는 이식하지 않는다.

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
- LIVE feature가 ON이면 source registry/key/readiness 설정 존재
- P1 flag가 승인 없이 ON이 아님
- replay와 live가 동일 source state로 반환되지 않음
- logging body/cookie 옵션이 OFF
- application/CDN/APM access log가 query string과 검색어를 기록하지 않음
- deletion/notification/event retention이 정책과 DB cleanup schedule에 일치
- release version/git SHA/contract SHA가 비어 있지 않고 artifact manifest와 일치
- AI provider가 ON이면 approved model/evaluation/key/timeout/kill switch가 존재
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
