# 기능 인벤토리와 추적성

- 상태: Accepted baseline
- 목적: Figma 화면, API operation, 구현 ticket, acceptance를 기능 ID로 연결
- 우선순위: P0 출시 필수, P1 후속, P2 검증 후
- 역할: Frontend 1명, Backend/AI 1명; 화면별 상세는 [Figma 핸드오프](../design/FIGMA_HANDOFF.md)

기능 ID는 issue/branch/PR/test 이름에 사용한다. UI frame이 없는 운영 기능도 제품 안정성에 필요하면 포함한다.

## 1. 시작·세션·프로필

| ID | Pri | 요구사항 | Figma | API/event | 핵심 acceptance |
| --- | --- | --- | --- | --- | --- |
| FR-ONB-01 | P0 | splash에서 session/readiness를 bootstrap | `388:257` | `createDemoSession`, `issueCsrfToken`, `getCurrentOwner` | blank/redirect loop 없음, retry 제공 |
| FR-ONB-02 | P0 | 한국어·English 선택/저장, 日本語·中文 준비 중 표시 | `388:277` | `updatePreferences` | KO/EN copy·format 전환, JA/ZH는 선택/저장 불가 |
| FR-ONB-03 | P0 | intro 계속/건너뛰기 | `388:321` | `onboarding_completed` | 완료 후 재방문 redirect |
| FR-SES-01 | P0 | 가입 없는 익명 owner/session | A-1 | `createDemoSession` | Secure/HttpOnly cookie, owner 격리 |
| FR-SES-02 | P0 | session 만료/rotation/rebootstrap·새 tab | 공통 | `createDemoSession`, `issueCsrfToken`, 401 | 안전한 GET만 1회 자동 복구, tab token 독립 |
| FR-SES-03 | P0 | CSRF/Origin 보호 | 공통 | `issueCsrfToken`, 모든 mutation | token 없거나 origin 불일치 거절 |
| FR-SES-04 | P0 | session과 소유 data 삭제 요청/상태 확인 | S14 | `deleteCurrentSession`, `getDeletionRequest` | 즉시 revoke, receipt token으로 status만 조회 |
| FR-PRO-01 | P0 | guest 프로필 shell/locale/active trip/데이터 안내 진입 | `422:2925` | `getCurrentOwner`, `updatePreferences` | 새로고침 후 유지, 내부 route allowlist |
| FR-PRO-02 | P0 | 계정 login CTA를 disabled `준비 중`으로 표시 | `422:2925` | client capability(`accountAuth=false`) | API/route 호출 없음, 가짜 활성 CTA 없음 |
| FR-PRO-03 | P0 | 내 여행 목록·active trip·empty state | `422:2925` | `listTrips` | row deep link, loading/empty/error 구분 |
| FR-PRO-04 | P0 | AI 최적화 run 상태 이력 | `422:2925` | `listOptimizationHistory`, `getOptimization` | 상태/scope/시각/decision; 이력용 일정 본문 추가 보존 없음 |
| FR-PRO-05 | P0 | 여행별 관심사 조회·전체 교체 | `422:2925` | `listTrips`, `getTrip`, `replaceTripInterests` | ETag/If-Match, 0개 허용, 중복 없음 |
| FR-DAT-01 | P0 | 6개 데이터 state/source 의미 설명 | `423:2967` | `getDemoReadiness`, `data_guide_opened` | LIVE/FORECAST/REPLAY/QUALITATIVE/STALE/UNAVAILABLE 설명 |

## 2. 여행 생성·가져오기

| ID | Pri | 요구사항 | Figma | API/event | 핵심 acceptance |
| --- | --- | --- | --- | --- | --- |
| FR-TRC-01 | P0 | 여행 시작/종료일 입력 | `438:3012` | `createTrip` | 역전 차단, timezone 일관성 |
| FR-TRC-02 | P0 | 관심사 다중 선택 | `438:3108` | `replaceTripInterests` | 0개 허용 시 품질 안내, 중복 없음 |
| FR-TRC-03 | P0 | 계획 수준 선택 | `438:3134` | `PlanningLevel` | 세 enum만 허용 |
| FR-TRC-04 | P0 | 필수 방문 장소 검색/선택 | `438:3158` | `searchPlaces`, seed constraint | canonical POI 매핑 |
| FR-TRC-05 | P0 | 입력 방법 선택 | `400:1201` | client state | 수동/붙여넣기 분기 복구 |
| FR-TRC-06 | P0 | 일정 텍스트 붙여넣기/parse | `401:1221` | `parseTripImport` | raw text 비저장·비로그·비echo |
| FR-TRC-07 | P0 | 불확실 장소/날짜/time remap | 붙여넣기 review | `remapTripImport` | unresolved를 사용자가 수정 |
| FR-TRC-08 | P0 | 직접 일정 입력 | `438:3199` | local draft/`searchPlaces` | 날짜·순서 validation |
| FR-TRC-09 | P0 | 구조화된 생성 내용 최종 확인 | `438:3259` | `createTrip`/`confirmTripImport` | 멱등 생성, 부분 trip 없음 |
| FR-TRC-10 | P0 | 결정적 추천 draft 제공 | `384:5673` | `createTrip` seed/read | 근거 없는 LLM 사실 생성 금지 |
| FR-TRC-11 | P1 | AI 기반 draft 보조 | `440:3244` | future optimization | AI state/근거/사용자 확인 |
| FR-TRC-12 | P0 | wizard 뒤로가기/refresh 복구 | S02 전체 | client session state | raw text 제외 입력 보존 |

## 3. Feed·게시물·후보 저장

| ID | Pri | 요구사항 | Figma | API/event | 핵심 acceptance |
| --- | --- | --- | --- | --- | --- |
| FR-FED-01 | P0 | 여행이 없을 때 feed와 여행 생성 CTA | `391:310` | `listFeed`, `listTrips` | empty trip이 feed 오류로 보이지 않음 |
| FR-FED-02 | P0 | 활성 여행 context가 있는 feed | `396:2926` | `listFeed?tripId` | candidate/scheduled state 표시 |
| FR-FED-03 | P0 | 고정 정렬 cursor pagination | feed | `listFeed` | opaque cursor, 중복/누락 없는 다음 page |
| FR-FED-04 | P0 | 허용된 피드 노출·상호작용 피드백 기록 | feed | `recordFeedFeedback` | allowlist event만 수집, 중복·PII 차단 |
| FR-PST-01 | P0 | 게시물 상세와 연결 장소 조회 | `398:611` | `getPost` | deep link/404 처리 |
| FR-PST-02 | P0 | 게시물 자체 저장/해제 | post/feed | `savePost`, `unsavePost` | TripCandidate/TripItem과 독립 |
| FR-PLC-01 | P0 | 표준 장소 상세를 화면 간 일관되게 조회 | post/search/Live | `getPlace` | canonical ID·출처 유지, 404 구분 |
| FR-CAN-01 | P0 | `+`에서 대상 여행 picker 열기 | `399:658`, `409:1595` | `listTrips` | focus trap/복귀, empty/new trip |
| FR-CAN-02 | P0 | 장소를 여행 후보로 저장 | `399:843` | `addTripCandidate` | 201, 날짜/time 없음, version 미변경 |
| FR-CAN-03 | P0 | 같은 여행·장소 중복 상태 | `399:1011` | `addTripCandidate` 200 | row 하나, duplicate=true |
| FR-CAN-04 | P0 | 저장 실패와 재시도 | `399:1179` | Problem Details | “일정 미변경”, 같은 key exact retry |
| FR-CAN-05 | P0 | 후보 목록/상태 조회 | `412:1912` | `listTripCandidates` | ACTIVE/SCHEDULED 구분 |
| FR-CAN-06 | P0 | 후보 dismiss | 후보 panel | `removeTripCandidate` | 일정 item에 영향 없음 |
| FR-CAN-07 | P0 | 후보의 가능한 날짜/slot 조회 | `412:1912` | `getCandidateTripMatches` | none/checking/unknown 구분 |

P0 feed에서는 계약이 없는 `팔로잉`/`최신`, 전역 검색, 알림, 작성자 팔로우와
혼잡·시점·지역 filter chip을 숨긴다. 화면에 남겨야 한다면 disabled `준비 중`과
이유를 제공하고 API·route를 호출하지 않는다.

## 4. 내 여행 보기·편집·제약

| ID | Pri | 요구사항 | Figma | API/event | 핵심 acceptance |
| --- | --- | --- | --- | --- | --- |
| FR-TRP-01 | P0 | 날짜별 확정 일정과 후보 수 조회 | `410:1738` | `getTrip` | ETag와 complete view |
| FR-TRP-02 | P0 | 보기/편집 mode 전환 | `411:1837` | client edit buffer | 서버 entity 즉시 변형 금지 |
| FR-TRP-03 | P0 | 편집 중 이탈 시 폐기 확인 | `413:2020` | client state | focus/escape/trigger 복귀 |
| FR-TRP-04 | P0 | 여행과 소유 하위 데이터를 삭제 | profile/trip menu | `deleteTrip` | 소유권 재검증, 확인 후 원자 삭제, active trip 정리 |
| FR-TRP-05 | P0 | 여행 이름·날짜 범위·timezone 수정 | `411:1837`, profile trip edit | `updateTrip` | If-Match; 범위 밖 item은 422 충돌으로 반환하고 묵시적 삭제 없음 |
| FR-ITM-01 | P0 | 검색한 장소를 일정에 추가 | `476:3409`, `479:3816` | `addTripItem` | 날짜/position, version +1 |
| FR-ITM-02 | P0 | 후보를 날짜 지정해 일정화 | `412:1912` | `addTripItem(candidateId)` | candidate SCHEDULED와 item 원자 적용 |
| FR-ITM-03 | P0 | 날짜 이동 | `521:3976`, `527:4695` | `updateTripItem`/`reorderTripItems` | trip 범위/position 유효 |
| FR-ITM-04 | P0 | 시간/duration 수정 | `527:4085` | `updateTripItem` | timezone/local time 일치 |
| FR-ITM-05 | P0 | 같은/다른 날짜 순서 변경 | edit | `reorderTripItems` | 전체 order 원자 적용, keyboard 가능 |
| FR-ITM-06 | P0 | 일정 item 삭제 | edit | `removeTripItem` | 후보 복원/완전 제거 선택 |
| FR-ITM-07 | P0 | 교체 대상 선택 | `479:3497` | local selection | 잘못된 item 교체 방지 |
| FR-ITM-08 | P0 | 기존/대안 장소 비교 후 교체 | `414:2347`, `527:4537` | `listRelatedPlaces`, `replaceTripItem` | relation/provenance 표시, 원자 적용 |
| FR-CON-01 | P0 | 필수 방문 lock | S07 edit | `setTripItemConstraint` | 다른 lock과 독립 |
| FR-CON-02 | P0 | 날짜 lock | `527:3876` | constraint set/remove | 해제 전 결과 확인 dialog |
| FR-CON-03 | P0 | 시간 lock | `527:4085` | constraint set/remove | TIME만 변경 |
| FR-CON-04 | P0 | 예약 lock | edit | constraint set/remove | optimizer 자동 해제 금지 |
| FR-CON-05 | P0 | 필수 방문 해제 확인 | `413:2081` | `removeTripItemConstraint` | 구체적 결과 설명 |
| FR-CON-06 | P0 | stale trip 편집 충돌 복구 | 공통 | If-Match/`TRIP_CHANGED` | 최신 상태를 잃지 않음 |

## 5. 최적화

| ID | Pri | 요구사항 | Figma | API/event | 핵심 acceptance |
| --- | --- | --- | --- | --- | --- |
| FR-OPT-01 | P0 | item scope와 대상 선택 | `415:2268` | `createOptimization` | P0 ITEM만 활성, 후보 포함 OFF |
| FR-OPT-02 | P1 | DAY/TRIP scope | `439:3104` | same enum, capability flag | route matrix/안전 gate 후 |
| FR-OPT-03 | P0 | 비동기 계산/loading/poll | `415:2413` | `getOptimization` | Retry-After/backoff/refresh 복구 |
| FR-OPT-04 | P0 | ITEM before/after 변경 목록 | `FCR-004` 대상 P0 preview | `OptimizationChange` | item/date/time/position 명시, 실제 Figma node 연결 전 FE 착수 금지 |
| FR-OPT-05 | P0 | 혼잡/이동 delta 표시 | preview | `OptimizationMetrics` | eligible일 때만 수치 |
| FR-OPT-06 | P0 | lock 보존 validation 표시 | preview | `ValidationSummary` | 모든 constraint pass |
| FR-OPT-07 | P0 | 사용자가 proposal apply | decision bar | `decideOptimization` APPLY | version/fingerprint 재검증, 원자 적용 |
| FR-OPT-08 | P0 | 현재 일정 keep | decision bar | `decideOptimization` KEEP | trip/version 미변경 |
| FR-OPT-09 | P0 | 적용 완료와 undo | `417:2412` | `revertOptimizationDecision` | 새 revision, 감사 record 유지 |
| FR-OPT-10 | P0 | trip stale 재계산 | `485:3517` | `TRIP_CHANGED` | 최신 trip으로 명시적 재요청 |
| FR-OPT-11 | P0 | data stale 재계산 | error ref | `DATA_CHANGED` | 기존 preview 적용 금지 |
| FR-OPT-12 | P0 | lock 충돌 처리 | `417:2567` | `LOCK_CONFLICT` | 조건 확인 CTA, 자동 unlock 금지 |
| FR-OPT-13 | P0 | 경로 불가 처리 | `417:2567` | `ROUTE_UNAVAILABLE` | retry/keep, 일정 미변경 |
| FR-OPT-14 | P0 | 개선안 없음 처리 | `417:2567` | `NO_IMPROVEMENT` | 현재 일정이 조건 내 적합함을 안내 |
| FR-OPT-15 | P0 | apply 실패 처리 | `417:2567` | `APPLY_FAILED` | 부분 반영 0, same-key 재시도 |
| FR-OPT-16 | P0 | run expiry/failure | 공통 | `FAILED`/`EXPIRED` | preview를 적용 가능하게 표시하지 않음 |

## 6. Live·데이터 신뢰

| ID | Pri | 요구사항 | Figma | API/event | 핵심 acceptance |
| --- | --- | --- | --- | --- | --- |
| FR-LIV-01 | P0 | area 목록과 capability-gated 지도 선택 유지 | `418:2523` | `queryLiveAreas` | 목록 필수; 지도는 provider 승인 때만, 거친 viewport만 사용 |
| FR-LIV-02 | P0 | area별 장소 조회 | S11-1 | `listLiveAreaPlaces` | mapping/fallback 표시 가능 |
| FR-LIV-03 | P0 | Live 장소 상세 | `419:2617` | `getLivePlace` | state/source/시각 표시 |
| FR-LIV-04 | P0 | 대체 장소 목록 | `420:2821` | `listRelatedPlaces` | EXACT/SIMILAR 이유 |
| FR-LIV-05 | P0 | 유효 대안 없음 | `420:2950` | relation NONE | 다른 행동 CTA, fake 후보 없음 |
| FR-LIV-06 | P0 | 확인 중/불명 관계 | S11 states | CHECKING/UNKNOWN | loading/불충분을 구분 |
| FR-LIV-07 | P0 | replay demo | `421:2850` | sourceState REPLAY | 현재 실시간처럼 표현 금지 |
| FR-LIV-08 | P0 | stale/unavailable degradation | 공통 | provenance state | trip/manual 기능은 유지 |
| FR-LIV-09 | P0 | Live 장소를 후보로 저장 | S11 detail | `addTripCandidate` source LIVE | 일정 즉시 변경 없음 |
| FR-LIV-10 | P1 | 위치 동의 기반 재계획 진입 | `501:3750` | future live replan | 목적/정밀도/보존 동의 선행 |
| FR-LIV-11 | P0 | 장소명 검색 후 Live coverage 조회 | `418:2523` | `searchPlaces`, `getLivePlace` | canonical 선택, 미지원 장소는 UNAVAILABLE이고 값 합성 금지 |
| FR-DAT-02 | P0 | temporal 비교 적격성 | 공통 | `comparisonAxis=TEMPORAL` | 같은 POI/forecast issue |
| FR-DAT-03 | P0 | spatial 비교 적격성 | 공통 | `comparisonAxis=SPATIAL` | 같은 source/scope/group/set |
| FR-DAT-04 | P0 | source schema drift 격리 | 개발/운영 | readiness/source health | 추측값 대신 degraded |
| FR-DAT-05 | P0 | 장소별 혼잡 예보 시계열 조회 | Live/상세 | `getPlaceCrowdForecast` | 발표·대상 시각과 비교 적격성 포함 |

## 7. P1/P2 확장

| ID | Pri | 요구사항 | Figma/route | API/전제 | 핵심 acceptance |
| --- | --- | --- | --- | --- | --- |
| FR-SRC-01 | P1 | 독립 검색 tab | `/search` | catalog/search 지표 | query/filter 전환 시 cursor reset |
| FR-FED-05 | P1 | 피드 정렬·시점·지역 filter | feed | **filter/정렬 계약 추가 필요** | 비교 적격성 없는 source 혼합 순위 금지, filter 변경 시 cursor reset |
| FR-FOL-01 | P1 | 팔로잉 feed·작성자 팔로우 | feed/post | **계정/follow graph 계약 추가 필요** | 익명 P0에서 가짜 활성 control 없음 |
| FR-NOT-01 | P1 | 알림 목록/unread/deep link와 개별 읽음 | `442:3344` | `listNotifications`, `markNotificationRead` | 내부 route allowlist, 멱등 unread count |
| FR-NOT-02 | P1 | `모두 읽음` 단일 mutation | `442:3344` | `markAllNotificationsRead` | N개 개별 호출 금지, 재시도해도 결과 동일 |
| FR-NBY-01 | P1 | 주변 장소 | `442:3370` | 위치 privacy review | 명시적 동의·denied fallback |
| FR-PUB-01 | P1 | 게시물 작성/미디어 | `/posts/new` | moderation/license/S3 | upload 취소·실패·권리 상태 |
| FR-LOC-01 | P1 | 영어 POI 명칭·설명 coverage/번역 QA 강화 | language/profile | EngService/소스별 언어 fallback | 원문·번역 출처 표시 |
| FR-AUT-01 | P1 | 정식 계정 login·익명 owner 데이터 승계 | `422:2925` | **인증/승계 계약 추가 필요** | 중복 owner merge·session fixation·소유권 검증 |
| FR-RTE-01 | P1 | route matrix/time window | optimization | provider/쿼터/약관 | 이동 불가를 성공 경로로 오인하지 않음 |
| FR-ML-01 | P2 | 개인화 ranking | 별도 | baseline/eval/설명가능성 | offline/online 개선 검증 후 사용 |
| FR-ML-02 | P2 | 독립 예측/추천 service | 별도 | scale/failure isolation trigger | 모듈러 monolith 분리 근거 기록 |

## 8. 운영·품질 기능

| ID | Pri | 요구사항 | 근거/API | Acceptance |
| --- | --- | --- | --- | --- |
| FR-OPS-01 | P0 | liveness/readiness 분리 | `getLiveness`, `getReadiness` | 선택 source 장애는 liveness 실패 아님 |
| FR-OPS-02 | P0 | demo capability readiness | `getDemoReadiness` | live/replay/optimization별 상태 |
| FR-OPS-03 | P0 | source 수집/검증/신선도 | architecture | timeout/retry/circuit/drift |
| FR-OPS-04 | P0 | source quota 관측 | AWS runbook | 60/80/90% alert |
| FR-OPS-05 | P0 | request/trace/error code log | API 공통 | 민감 body 없이 상관관계 |
| FR-OPS-06 | P0 | analytics allowlist/dedup | `ingestEventBatch` | JSON Schema, PII 금지 |
| FR-OPS-07 | P0 | backup/PITR/restore | AWS runbook | 실제 staging rehearsal |
| FR-OPS-08 | P0 | API/web rollback | AWS runbook | 이전 image/object 복구 |
| FR-OPS-09 | P0 | 사용자 data 삭제 TTL | session/ERD | revoke 즉시, job 추적 |
| FR-OPS-10 | P0 | feature capability flag | environment doc | 안전 불변식 비활성화 불가 |
| FR-OPS-11 | P0 | KTO 실제 호출과 비밀값 없는 call-audit | source catalog, contest matrix | 제출 기능별 호출 시각·operation·결과·화면 사용 증거 |
| FR-OPS-12 | P0 | 공모전 익명 외부망 smoke와 기능설명서 정합성 | submission runbook | 실제 배포 기능/API만 PDF에 기록, 접수 증거 보관 |

## 9. 비기능 요구사항

| ID | 분류 | 요구사항 | 검증 |
| --- | --- | --- | --- |
| NFR-A11Y-01 | 접근성 | keyboard-only P0 flow, focus trap/restore | Playwright + manual SR |
| NFR-A11Y-02 | 접근성 | 색 외 text/icon state 표현 | visual/manual |
| NFR-RESP-01 | 반응형 | 360/768/1280, 200% zoom, safe-area | viewport suite |
| NFR-PERF-01 | 성능 | field LCP p75 ≤2.5s, INP ≤200ms, CLS ≤0.1 | RUM/Lighthouse |
| NFR-PERF-02 | 성능 | cached API p95 ≤500ms, optimization p95 ≤10s | load/metric |
| NFR-AVL-01 | 가용성 | 핵심 read/edit 월 99.9% 목표 | CloudWatch SLI |
| NFR-SEC-01 | 보안 | owner authorization matrix, CSRF/CORS/rate limit | integration/security test |
| NFR-PRV-01 | 개인정보 | raw itinerary/정밀 위치/secret 로그 0 | denylist scan |
| NFR-DATA-01 | 신뢰 | provenance 필드 완전성 100% | contract/property test |
| NFR-DATA-02 | 신뢰 | 승인 없는 일정 변경 0 | invariant/E2E/metric |
| NFR-OPS-01 | 운영 | IaC 재현·staging 자동·production 승인 | CDK/CI evidence |
| NFR-CMP-01 | 공모전 | 로그인 없는 외부 URL에서 심사 핵심 흐름 완결 | external/incognito judge E2E |
| NFR-CMP-02 | 공모전 | KTO 텍스트 출처 100%, 승인 없는 CI·BI logo 0건 | DOM/visual/license audit |
| NFR-CMP-03 | 공모전 | 제출 profile에서 geolocation 요청·개인 위치 서버 전송 0건 | permission/network/telemetry test |

## 10. 2인 소유권과 수직 slice 인계

| 기능 ID/영역 | Frontend 주 소유 | Backend/AI 주 소유 | merge 전 공동 검증 |
| --- | --- | --- | --- |
| `FR-ONB-*`, `FR-PRO-*` | route guard, KO/EN, S14 상태/편집 UI | session/profile/trip/history projection | bootstrap·profile refresh E2E, 추가 계약 client 재생성 |
| `FR-TRC-*` | wizard/draft/입력·재진입 | canonical mapping/import/create transaction | raw text 비영속, idempotent create |
| `FR-FED-*`, `FR-PST-*`, `FR-CAN-*` | card/detail/sheet/result states | feed/post/candidate ownership·멱등성 | SavedPost·Candidate·Item 분리 E2E |
| `FR-TRP-*`, `FR-ITM-*`, `FR-CON-*` | edit buffer, compare/reorder/confirm/accessibility | ETag/version/constraint/atomic mutation | stale conflict·lock·rollback E2E |
| `FR-OPT-*` | setup/poll/preview/decision/error UI | optimizer, provenance/fingerprint, atomic apply/audit | 승인 전 mutation 0, 6 error code, revert |
| `FR-LIV-*`, `FR-DAT-*` | list-first/state/relation/fallback, 승인 시 map | connector/mapping/freshness/comparability | LIVE·FORECAST·REPLAY·QUALITATIVE·STALE·UNAVAILABLE fixture |
| `FR-NOT-*`, `FR-NBY-*`, `FR-PUB-*` | P1 route·permission·interaction | P1 schema/policy/storage | P0와 분리된 feature flag·contract |
| `FR-OPS-*`, `NFR-*` | web telemetry/visual/a11y/E2E·출처 DOM | API/data/security/ops telemetry·KTO call-audit | 양쪽 required check, 익명 외부망과 staging evidence |

기능 인계는 `ID가 있는 issue → OpenAPI/event 변경 → Backend contract example/test → Frontend generated client·fixture → E2E`를 따른다. Frontend는 확정되지 않은 response shape를 자체 정의하지 않고, Backend/AI는 Figma 상태가 요구하는 계약을 UI 구현 후에 추가하지 않는다.

공모전 관련 `FR-OPS-11~12`, `NFR-CMP-*`는 독립된 홍보 작업이 아니라 각 심사 대상 slice의 완료 조건이다. PR에는 [준수 매트릭스](../contest/COMPETITION_COMPLIANCE_MATRIX.md)의 requirement/evidence ID를 연결하고, 배포되지 않은 P1·mock-only 기능은 기능설명서 목록에서 제외한다.

## 11. 변경 규칙

- 새 기능은 새 ID를 추가하고 기존 ID를 재사용해 의미를 바꾸지 않는다.
- 취소된 기능은 row를 삭제하지 않고 `Dropped`와 근거 ADR을 남긴다.
- Figma node가 교체되면 ID는 유지하고 node reference를 갱신한다.
- API operationId가 바뀌면 breaking change review와 generated client migration을 한다.
- test report는 이 ID를 suite/test title에 포함해 요구사항 누락을 찾을 수 있게 한다.
