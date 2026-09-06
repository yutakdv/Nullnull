# API 사용 규칙

- 정본: [`openapi.yaml`](openapi.yaml)
- base path: `/api/v1`
- media type: JSON, 오류는 `application/problem+json`
- casing: JSON `camelCase`, DB `snake_case`
- 시간: RFC 3339 UTC offset 포함, 날짜는 ISO 8601 `YYYY-MM-DD`

## 1. 계약 우선 흐름

1. FE/BE가 사용자 흐름과 acceptance를 합의한다.
2. `openapi.yaml`을 먼저 수정하고 lint/breaking-change 검사를 통과시킨다.
3. FE TypeScript client와 mock handler를 생성한다.
4. BE interface/controller contract test를 작성한다.
5. 양쪽 구현 후 통합 E2E를 추가한다.

생성 client는 `packages/api-client/`에 두며 수동 수정하지 않는다. CI는 spec hash와 생성 결과가 일치하는지 확인한다. 구체 generator와 명령은 첫 scaffold PR에서 lockfile과 함께 확정한다.

### 2인 역할 경계

| 단계 | Frontend 담당 | Backend/AI 담당 | 공동 완료 조건 |
| --- | --- | --- | --- |
| 계약 제안 | Figma 상태·필요 field·접근성·복구 UX | domain invariant·오류·상태 전이·한도 | example로 happy/error/empty 확인 |
| 병렬 구현 | 생성 client, MSW mock, query/form 상태 | controller/application/DB/adapter, AI validator | 같은 spec hash 사용 |
| 오류 처리 | `code`→번역/CTA, ETag/cursor/token 운반 | stable Problem, transaction, owner scope, Retry-After | contract + Playwright |
| 데이터 진실성 | provenance/state/비교 불가 표시 | source registry/snapshot/pair policy | mixed-source fixture 통과 |
| 변경 승인 | preview diff, APPLY/KEEP/REVERT UX | immutable proposal, apply-time 재검증 | 승인 전 trip mutation 0건 |

FE는 response type을 재선언하거나 unknown field에 의존하지 않는다. Backend/AI는 합의 없이 enum/nullable/error code를 바꾸지 않는다. 계약 변경 PR은 상대 담당 승인이 필요하다.

## 2. 화면–API 매트릭스

| Figma/기능 | 읽기 | 쓰기 | 주요 실패/상태 |
| --- | --- | --- | --- |
| A-1 splash | `/health/ready`, `/me`, `/demo/readiness` | `/demo/sessions` | session 만료, degraded |
| S03-F0/F1 feed | `/feed`, `/trips` | `/feed/feedback` | empty trip, cursor error |
| S03-D post | `/posts/{id}` | `/posts/{id}/saved` | 404, 저장 중복 |
| S03-C/S06 저장 sheet | `/trips` | `/trips/{id}/candidates` | duplicate, source unavailable |
| S02 여행 생성 | `POST /places/search` | `/trips` | validation, idempotency conflict |
| S02 붙여넣기 | `POST /places/search` | `/trip-imports/parse`, remap, confirm | expired, unresolved, draft conflict |
| S07 내 여행 | `/trips/{id}`, candidates/matches | trip/item/constraint endpoints | `TRIP_CHANGED`, `LOCK_CONFLICT` |
| S09 최적화 | `/optimizations/{runId}` | create/decision/revert | 6개 Figma error code |
| S11 Live | `/live/areas`, area places, live place, related | 후보 저장만 | replay/stale/unavailable/none |
| S14 profile | `/me`, `/optimizations` | preferences | P0 상태-only optimization history |
| S15 data | demo readiness, deletion status | session delete | revoke, async deletion |
| S12 notifications | `/notifications` | single/all read | P1, internal deep-link only |

여행 생성 화면에는 제목 입력 단계가 없다. `createTrip.title`은 선택값이며 생략하면
서버가 session locale에 맞는 결정적 기본 제목(`새 여행`/`New trip`)을 만든다. 관심사
배열은 필수 field지만 빈 배열을 허용하며, 이 경우 UI는 추천 품질이 낮을 수 있음을
알린다. `updateTrip.timezone`은 IANA timezone만 받고 기존 local date/time을 묵시적으로
이동시키지 않는다.

## 3. Session과 CSRF

- cookie가 없으면 `POST /demo/sessions`가 Owner, `__Host-nullnull_session` HttpOnly cookie, response body의 CSRF token을 만든다. valid cookie로 retry하면 200과 같은 Owner로 수렴하며 이유 없이 session을 rotate하지 않는다.
- refresh/new tab은 valid cookie와 `POST /session/csrf`로 tab-local token을 받는다. token 발급은 same-origin 검증을 거치며 다른 tab token을 무효화하지 않는다.
- FE는 CSRF token을 memory에만 두며 token별 `expiresAt` 전에 갱신한다. Backend는 session당 미만료 hash 최대 5개만 둔다.
- 모든 mutation은 session cookie + `X-CSRF-Token` 조합을 요구한다.
- cookie는 HTTPS production에서 `Secure; HttpOnly; SameSite=Lax; Path=/`를 사용한다.
- 401이면 cookie 유무에 따라 CSRF 재발급 또는 session 생성을 한 번 시도한 뒤 안전한 read만 재시도한다. mutation 자동 재실행은 동일 idempotency key가 있는 경우에도 UI가 요청 결과 불명을 처리하는 경로에서만 허용한다.
- 다른 owner의 resource도 404로 응답해 존재 여부를 노출하지 않는다.

## 4. ETag와 trip version

Trip 확정 상태를 바꾸는 응답은 `ETag: "7"`처럼 version을 준다. FE는 최신 ETag를 보관해 다음 mutation의 `If-Match`에 보낸다.

다음 동작은 version을 올린다.

- 여행 이름/날짜/timezone/계획 수준/관심사 변경
- 일정 추가·수정·삭제·이동·순서 변경·교체
- constraint 추가·삭제
- optimization apply/revert
- import draft remap은 trip version과 별도의 draft version을 올린다.

다음 동작은 version을 올리지 않는다.

- post 저장
- 후보 저장/삭제/조회
- feed feedback/event
- optimization preview 생성/keep

불일치는 409 `TRIP_CHANGED`이며 `currentTripVersion`과 선택적 `recomputeUrl`을 준다. FE는 무조건 재시도하지 않고 최신 내용을 보여준다.

Import draft도 ETag/If-Match를 사용한다. stale remap/confirm은 409 `IMPORT_DRAFT_CHANGED`, 만료는 410 `IMPORT_DRAFT_EXPIRED`이며 stale confirm은 trip을 만들지 않는다. 여행 기간 축소로 item/DATE/RESERVATION lock이 범위 밖이 되면 자동 삭제·이동 없이 422로 거부한다.

### FCR-010/015 최적화 판별 union

- `CreateOptimizationRequest`는 `scope`로 판별한다. `ITEM`은 `targetItemId`, `DAY`는
  `targetDate`만 필수이며 `TRIP`에는 target field가 없다. P0 Frontend는 ITEM request만
  생성한다.
- `OptimizationDecision`은 `decision`으로 판별한다. APPLY는
  `beforeRevisionId`, `afterRevisionId`, `resultingTripVersion`, `revertUntil`이 모두
  필수이며 `revertUntil`은 server `decidedAt`의 정확히 24시간 뒤다.
- KEEP은 revision/revert field를 반환하지 않는다. REVERT는 `revertedDecisionId`와
  transaction 전후 revision을 반환한다. Frontend는 optional field 조합을 추론하지 않고
  생성된 union을 exhaustive하게 처리한다.
- `revertUntil`이 지났거나 server가 410 `REVERT_WINDOW_EXPIRED`를 반환하면 persistent
  applied 화면을 만료 상태로 바꾸고 되돌리기 요청을 다시 보내지 않는다.

## 5. 멱등성

OpenAPI에서 `Idempotency-Key`가 required인 요청은 다음 규칙을 따른다.

- UUID를 권장하고 FE가 사용자 action 시작 시 한 번 생성한다.
- network retry는 같은 key와 byte-equivalent semantic body를 사용한다.
- 새로운 사용자 action은 새 key를 사용한다.
- 같은 key/같은 body는 원래 status/body를 반환한다.
- 같은 key/다른 body는 409 `IDEMPOTENCY_KEY_REUSED`다.
- server 보존은 기본 24시간이다.
- scope는 Owner 생성 뒤 `(ownerId, routeTemplate, key)`다. 최초 `/demo/sessions`는 owner-scoped idempotency 대상이 아니며 valid cookie retry로 수렴한다.

## 6. Pagination과 정렬

- 사용자 feed/list는 cursor pagination을 기본으로 한다.
- cursor는 opaque이며 FE가 해석·수정하지 않는다.
- response의 `page.nextCursor`, `page.hasMore`만 사용한다.
- 기본 limit 20, 최대 50.
- 새 filter/search query는 cursor를 폐기한다.
- 정렬은 endpoint 계약의 일부이며 임의 query sort를 P0에 노출하지 않는다.
- cursor는 owner, endpoint, filter hash, 고정 정렬 key, schema version, 15분 만료를 서명한 opaque base64url 값이다.
- 다른 owner/endpoint/filter에 재사용하거나 변조하면 400 `CURSOR_INVALID`, 만료면 410 `CURSOR_EXPIRED`다. cursor가 있으면 filter는 발급 당시와 byte-equivalent semantics여야 한다.

## 7. 후보 저장 의미

`POST /trips/{tripId}/candidates`는 활성 후보가 없을 때 201, 이미 있으면 200을 준다. 두 경우 모두 `CandidateSaveResult`를 반환한다.

```json
{
  "candidate": { "id": "...", "status": "ACTIVE" },
  "duplicate": true,
  "tripScheduleChanged": false
}
```

FE는 duplicate를 오류 toast로만 처리하지 않고 Figma S03-C3 상태를 보여준다. 이 endpoint는 날짜·시간을 받지 않고 TripItem을 만들지 않는다.

후보 상태 전이는 `ACTIVE → SCHEDULED`(candidateId로 item 생성), `SCHEDULED → ACTIVE`(item 삭제 disposition=RESTORE_CANDIDATE), `ACTIVE → DISMISSED`만 허용한다. SCHEDULED 후보를 직접 삭제할 수 없고 먼저 item을 처리해야 한다. DISMISSED row는 terminal audit이며 같은 장소를 다시 저장하면 새 ACTIVE row를 만든다.

## 8. Constraint와 import lock

Constraint는 임의 `value` object가 아니라 `type` discriminator를 가진 tagged union이다.

| type | 필수 field | 의미 |
| --- | --- | --- |
| `MUST_VISIT` | `type`, `locked=true` | 장소 자체 고정 |
| `DATE` | 위 + `date` | 날짜 고정 |
| `TIME` | 위 + `startTime`, `toleranceMinutes` | local time과 허용 오차 고정 |
| `RESERVATION` | 위 + `date`, `startTime`, 선택 `endTime` | 예약 날짜/시간 고정 |

`PUT .../constraints/{constraintType}`의 path와 body type은 같아야 한다. row 존재 자체가 lock이므로 unlock은 DELETE다. 네 잠금은 독립적이며 한 type의 PUT/DELETE가 다른 type을 바꾸지 않는다. import draft item도 동일 union을 반환하며 confirm할 때 server가 `source=IMPORT`를 기록한다.

## 9. 오류 계약

오류 body는 RFC 9457 형태의 Problem Details에 안정적인 `code`를 추가한다.

```json
{
  "type": "/problems/trip-changed",
  "title": "Trip changed",
  "status": 409,
  "code": "TRIP_CHANGED",
  "detail": "다른 곳에서 일정이 바뀌었어요.",
  "instance": "/api/v1/optimizations/…/decisions",
  "requestId": "req_…",
  "retryable": true,
  "fieldErrors": [],
  "currentTripVersion": 8,
  "recomputeUrl": "/api/v1/trips/…/optimizations"
}
```

### UI mapping

| Code | HTTP | UI 행동 | 자동 재시도 |
| --- | --- | --- | --- |
| `INVALID_REQUEST` | 400 | 입력/지원 문의 | 금지 |
| `UNAUTHORIZED` | 401 | session bootstrap 또는 로그인 | GET 1회만 |
| `FORBIDDEN` | 403 | 작업 불가 안내 | 금지 |
| `NOT_FOUND` | 404 | 사라진 resource/목록 이동 | 금지 |
| `VALIDATION_FAILED` | 422 | fieldErrors 연결 | 금지 |
| `CSRF_INVALID` | 403 | tab token 1회 재발급 후 사용자 action 재확인 | mutation 자동 금지 |
| `CURSOR_INVALID` | 400 | 첫 page부터 다시 조회 | 금지 |
| `CURSOR_EXPIRED` | 410 | 첫 page부터 다시 조회 | 금지 |
| `TRIP_CHANGED` | 409 | 최신 일정 조회·재계산 | 사용자 확인 후 |
| `DATA_CHANGED` | 409 | 최신 데이터로 재계산 | 사용자 CTA |
| `LOCK_CONFLICT` | 409/422 | 잠금 조건 확인 | 금지 |
| `ROUTE_UNAVAILABLE` | 503 | 재시도/현재 일정 유지 | backoff, 최대 1회 |
| `NO_IMPROVEMENT` | 422 | 내 여행으로 복귀 | 금지 |
| `APPLY_FAILED` | 500/503 | “일정은 바뀌지 않음” + 재시도 | 같은 key로 사용자 CTA |
| `IDEMPOTENCY_KEY_REUSED` | 409 | client bug로 기록, 새 action 안내 | 금지 |
| `IMPORT_DRAFT_EXPIRED` | 410 | 다시 붙여넣기 | 금지 |
| `IMPORT_DRAFT_CHANGED` | 409 | 최신 draft 표시·사용자 수정 보존 | 금지 |
| `PREVIEW_EXPIRED` | 410 | 새 최적화 실행 | 금지 |
| `REVERT_WINDOW_EXPIRED` | 410 | 적용 결과 유지·되돌리기 만료 표시 | 금지 |
| `DELETION_STATUS_EXPIRED` | 410 | 완료 여부 지원 안내 | 금지 |
| `SOURCE_UNAVAILABLE` | 503 | stale/replay/empty fallback | endpoint별 |
| `RATE_LIMITED` | 429 | Retry-After 표시/대기 | header 이후 |
| `INTERNAL_ERROR` | 500 | requestId와 재시도 | 안전한 GET만 |

Backend는 stack trace, SQL, 외부 API body, secret을 detail에 넣지 않는다. FE는 `detail`을 HTML로 렌더링하지 않는다.

모든 응답은 `X-Request-ID`를 제공한다. session 보호 operation은 명시되지 않아도 401, 모든 operation은 429 `RATE_LIMITED`를 반환할 수 있으며 429/503의 `Retry-After`는 초 단위다. CI는 이 공통 규칙과 operation별 response가 어긋나지 않는지 검사한다.

### Optimization 단계별 오류

| 단계 | 오류 code | trip 변경 여부 | FE 처리 |
| --- | --- | --- | --- |
| create preflight | `TRIP_CHANGED`, `LOCK_CONFLICT`, `RATE_LIMITED` | 없음 | 최신 trip/잠금 표시 |
| async run | `TRIP_CHANGED`, `DATA_CHANGED`, `LOCK_CONFLICT`, `ROUTE_UNAVAILABLE`, `NO_IMPROVEMENT` | 없음 | run failure 화면과 허용 CTA |
| APPLY | `TRIP_CHANGED`, `DATA_CHANGED`, `LOCK_CONFLICT`, `NO_IMPROVEMENT`, `ROUTE_UNAVAILABLE`, `APPLY_FAILED` | 실패 시 없음 | 동일 action 결과 조회 후 재시도/재계산 |
| REVERT | `TRIP_CHANGED`, `REVERT_WINDOW_EXPIRED`, `APPLY_FAILED` | 실패 시 없음 | 현재 trip 유지·만료 상태 고정 |

HTTP와 run failure가 같은 code 의미를 사용한다. exception 종류나 provider message를 새 code처럼 노출하지 않는다.

## 10. 데이터 provenance 표시

혼잡/대체 장소 응답의 `DataProvenance`는 선택적 장식이 아니다.

- `sourceState`: LIVE/FORECAST/REPLAY/QUALITATIVE/STALE/UNAVAILABLE
- `observedAt`: 관측/발표된 시각
- `targetAt`: 예보가 가리키는 시각
- `fetchedAt`, `staleAt`, `freshness`
- `provenanceId`, `sourceRegistryVersion`
- `confidence`, `normalizationVersion`, `license`, `officialUrl`, `licenseUrl`,
  `attribution`, `metricDefinition`
- `comparisonEligible`, `comparisonReasonCode`
- `comparisonGroupId`, `collectorRunId`, `snapshotSetId`
- `scope`, `mappingType`, `fallbackUsed`

수치 delta는 `comparisonEligible=true`인 경우에만 표시한다. false이면 `comparisonReasonCode`에 대응하는 설명을 표시하며 null 값을 0으로 바꾸지 않는다.

`SEOUL_CITYDATA`는 `sourceDisplayName=서울시 실시간 도시데이터`,
`license=공공누리 제1유형`,
`attribution=출처: 서울특별시 「서울시 실시간 도시데이터」(2022년 공개, 공공누리 제1유형)`을
사용한다. Frontend는 `attribution`을 그대로 표시하고 `officialUrl`을 출처 링크,
`licenseUrl`을 이용조건 링크로 사용한다. KTO attribution과 합치거나 provider별 문구를
client에서 다시 만들지 않는다.

### 공모전 KTO API 계약

- 브라우저는 한국관광공사 OpenAPI나 인증키를 직접 호출·보관하지 않는다. Backend source gateway가 요청하고 공개 API에는 정규화·검증된 최소 field만 반환한다.
- 제출 배포본은 실제 KTO 호출 경로를 사용한다. file/replay/mock/전체 로컬 mirror만으로 이 요건을 대신하지 않는다.
- gateway는 `sourceCode`, provider operation, `requestedAt`, `completedAt`, outcome/status class, response count, collector/request ID, deployed release ID를 비밀값 없이 call-audit로 남긴다. key, full URL query, 원문 응답과 사용자 입력은 남기지 않는다.
- 조회는 quota를 지키는 read-through/refresh cache를 쓸 수 있으나 제출 심사 flow에서 실제 호출과 서비스 내 사용을 재현할 수 있어야 한다. 불가피한 장기·전체 저장은 구현 전에 공식 문의와 별도 승인 증거가 필요하다.
- `DataProvenance.attribution`의 KTO 기본값은 `출처: ⓒ한국관광공사`다. FE는 이를 실제 화면에 표시하고 `TourAPI`만 단독 표기하거나 승인 없는 CI·BI logo를 사용하지 않는다.
- PR의 fixture test는 외부망을 차단하지만 staging/submission smoke는 승인된 운영키로 실제 call, call-audit, UI attribution을 한 흐름에서 확인한다.

## 11. Optimization polling

1. create가 202와 run URL을 반환한다.
2. `QUEUED|RUNNING`이면 response의 `Retry-After`를 따른다. 기본 1초에서 최대 5초 backoff.
3. tab이 background이면 polling을 줄이고 foreground 복귀 시 즉시 한 번 조회한다.
4. `READY` proposal은 immutable이며 URL refresh로 복구 가능하다.
5. `expiresAt` 이후 apply하지 않는다.
6. apply는 preview의 trip version과 data fingerprint를 server가 재검증한다.
7. client timeout은 run 취소를 의미하지 않는다. 같은 run URL을 다시 조회한다.

한 run은 최대 3개 immutable proposal과 최초 decision 하나만 가진다. ADD는 `before=null`, REMOVE는 `after=null`, 나머지는 양쪽 상태가 필수다. APPLY만 `beforeRevisionId/afterRevisionId`와 24시간 `revertUntil`을 받으며 한 번만 되돌릴 수 있다. KEEP은 trip version을 올리지 않는다. S14 이력은 상태·시각·trip/run 링크만 반환하며 이력 전용 일정 snapshot을 만들지 않는다.

## 12. 날짜·시간·locale

- trip date는 trip timezone의 calendar date다.
- `startTime`은 offset 없는 local time이며 항상 trip timezone과 함께 해석한다.
- 외부 snapshot은 RFC 3339 timestamp로 offset을 포함한다.
- 날짜 boundary 계산은 backend가 trip timezone 기준으로 수행한다.
- UI message translation key는 frontend가 소유하고 API `code`에 매핑한다. API `detail`은 fallback이다.

## 13. 개인정보·query body

- `POST /places/search`와 `POST /live/areas`는 side effect 없는 read-only POST다. 검색어/coarse viewport를 URL·CDN/ALB/APM access log에서 제외하기 위한 선택이며 `private, no-store`다.
- 검색 body는 저장/analytics/trace 금지다. live viewport는 소수점 3자리로 반올림하고 각 축 0.01도 이상인 coarse bounds만 허용하며 device exact coordinate를 보내지 않는다.
- analytics body는 `sessionId/ownerId`를 받지 않는다. Backend가 인증 cookie에서 bind하며 route는 query/실제 UUID가 없는 route template만 허용한다.
- session DELETE는 즉시 revoke 후 202 receipt와 memory-only status token을 반환한다. 상태 endpoint는 삭제 데이터 접근 권한 없이 상태만 보여주며 token hash는 7일 뒤 삭제한다.
- DELETE 응답을 잃은 경우에만 revoked cookie + 같은 Idempotency-Key를 24시간 허용해 동일 receipt를 재생한다. 그 cookie의 다른 route는 즉시 401이다. status token은 receipt ID/expiry에서 결정적으로 서명해 재생 가능하게 하고 원문을 DB에 저장하지 않는다.

## 14. 요청 한도

| 입력 | P0 상한 |
| --- | --- |
| trip | 30일, 총 item 100, 하루 item 20, 후보 500 |
| interests/constraints | trip당 20 / item당 독립 type 최대 4 |
| import | 원문 20,000자, draft item/update 각 100, suggestion/token 10 |
| search | 100자, page 최대 50 |
| note/title/body | schema의 maxLength 적용; unknown field 거부 |
| optimization | proposal 3, change 100, snapshot ref 20 |
| analytics | batch 50, 자유 properties 금지 |

Edge body limit, Spring request limit, Bean Validation, domain invariant를 모두 적용한다. 한 계층이 허용하더라도 다음 계층이 무제한으로 처리하지 않는다.

## 15. 캐시

| 응답 | 기본 정책 |
| --- | --- |
| session/me/trip | private, no-store 또는 짧은 client query cache; CDN cache 금지 |
| import parse | `Cache-Control: no-store` |
| public place/post image | content hash 기반 long cache |
| feed/place JSON | owner state가 섞이면 CDN cache 금지 |
| live/crowd | server-side source별 TTL, response에 freshness 노출 |
| optimization | private, no-store |

## 16. 계약 검증 게이트

첫 scaffold PR에서 다음을 자동화한다.

- OpenAPI syntax/lint
- breaking change detection against main
- unique operationId 및 path parameter 검사
- generated TypeScript client diff 없음
- Spring controller contract test
- Problem code enum과 FE translation mapping 일치
- example/fixture schema validation
- secret/PII field name denylist scan
- 모든 Detail example의 additionalProperties/required validation
- tagged constraint union과 optimization ADD/REMOVE fixture
- session create/refresh/new-tab/two-tab CSRF E2E
- import draft stale If-Match, decision cardinality, one-time revert test
- cursor owner/filter/expiry 변조와 search/query log canary
- deletion status/tombstone/backup restore 재적용 integration test
- KTO secret browser 비노출, call-audit redaction, provider operation별 contract fixture
- staging의 실제 KTO call → normalized response → UI attribution smoke evidence
- 공모전 profile에서 위치 endpoint/capability OFF와 geolocation network 요청 0건
