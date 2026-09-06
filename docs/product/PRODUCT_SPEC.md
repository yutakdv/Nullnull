# Nullnull 제품 요구사항

- 상태: Accepted for P0 implementation
- 기준일: 2026-09-05
- 대상: 모바일 웹앱(PWA), P0 UI 한국어·English
- 팀: Frontend 1명, Backend/AI 1명

## 1. 문제와 목표

여행자는 SNS에서 발견한 장소와 실시간 현장 정보를 일정으로 옮기는 과정에서 맥락을 잃는다. 인기 장소에 같은 시간대 방문이 몰리고, 혼잡 회피 제안은 근거가 불분명하거나 사용자의 확정 일정을 임의로 바꾸기 쉽다.

Nullnull은 발견한 장소를 **특정 여행의 후보**로 축적하고, 일정 제약과 신뢰 가능한 혼잡·경로 데이터를 바탕으로 더 나은 변경안을 제시한다. 사용자는 적용 전후를 비교한 뒤 직접 승인한다.

### 제품 목표

- 발견 → 후보 저장 → 일정 확정의 전환 마찰을 줄인다.
- 같은 장소의 덜 붐비는 시간 또는 비교 가능한 대체 장소를 제안한다.
- 추천의 출처, 기준 시각, 신뢰도, 비교 가능 여부를 이해할 수 있게 한다.
- AI 제안에도 사용자의 필수 방문, 날짜, 시간, 예약 제약을 보존한다.

### P0 성공 기준

| 지표 | 정의 | 초기 목표/게이트 |
| --- | --- | --- |
| 후보 저장 성공률 | 저장 시도 중 `TripCandidate` 생성 성공 | 99% 이상(서버 오류 제외) |
| 일정 반영률 | 후보 상세 확인 후 `TripItem`으로 확정 | 계측 후 기준선 설정 |
| 최적화 승인율 | 유효 preview 중 apply 선택 | 계측 후 기준선 설정 |
| 잘못된 자동 변경 | 승인 없이 일정이 바뀐 사건 | 0건 |
| 출처 표시 완전성 | 혼잡/대체 데이터 응답의 provenance 필드 충족 | 100% |
| P0 핵심 흐름 성공 | E2E: 여행 생성→후보 저장→일정 추가→preview→apply | CI 100% |

수치는 출시 전 트래픽이 없으므로 허위 목표를 만들지 않고, P0에서는 안전성·완전성 게이트를 우선한다. 사용자 행동 목표는 파일럿 2주 후 기준선을 정한다.

## 2. 사용자와 핵심 시나리오

### 주요 사용자

- 여행 전: SNS/검색에서 장소를 모으고 일정을 만드는 여행자
- 여행 중: 주변 혼잡과 대안을 빠르게 확인하는 여행자
- 운영자: 데이터 연동 상태와 데모 준비 상태를 확인하는 팀원

### P0 핵심 시나리오

1. 사용자가 한국어나 English를 선택하고 서비스 소개를 확인한다. 日本語와 中文은 `준비 중`으로 구분한다.
2. 여행이 없다면 날짜, 관심사, 계획 수준으로 첫 여행을 만든다.
3. 피드 게시물 또는 장소 상세에서 `+`를 눌러 저장할 여행을 고른다.
4. 장소는 날짜·시간 없는 후보가 되며 중복/실패 상태가 명확히 표시된다.
5. 내 여행에서 후보를 날짜·시간과 함께 일정에 추가하거나 직접 검색해 추가한다.
6. 필수 방문/날짜/시간/예약 잠금을 지정할 수 있다.
7. 혼잡 완화 최적화를 요청하면 변경 전후와 근거를 preview한다.
8. 사용자가 적용하면 trip version 검증 후 원자적으로 반영한다. 유지하면 아무것도 바뀌지 않는다.
9. 여행 중 Live에서 현황, 장소 상세, 비교 가능한 대안을 확인한다.
10. 프로필에서 내 여행, 여행별 관심사, AI 최적화 상태 이력과 데이터 안내를 확인한다.

## 3. 범위

### P0 — 출시 필수

| Capability | 포함 기능 |
| --- | --- |
| 시작 | 스플래시, KO/EN 언어 선택, JA/ZH 준비 중 표시, 소개, 익명 데모 세션 |
| 여행 생성 | 날짜, 관심사, 계획 수준, 필수 장소, 직접 입력/붙여넣기 파싱, 확인 |
| 탐색/저장 | 피드, 게시물 상세, 여행 선택, 후보 저장, 중복/오류 상태 |
| 내 여행 | 보기/편집, 날짜·시간 변경, 장소 검색·추가·삭제·교체, 후보 패널, 변경 취소 확인 |
| 제약 | 필수 방문, 날짜, 시간, 예약 잠금의 독립 관리 |
| 최적화 | 단일 장소의 더 한산한 날짜/시간 제안, preview, apply/keep, stale 재계산, revert |
| Live | 목록 필수, 승인된 provider가 있을 때 지도, 장소 상세, 대안, 후보 없음, replay/demo 상태 |
| 신뢰 | 데이터 안내, 출처·관측시각·대상시각·신뢰도·상태 표시 |
| 프로필 | guest 상태, 준비 중 login CTA, 내 여행 목록, AI 최적화 상태 이력, 여행별 관심사, locale, 데이터 안내, session 삭제 |
| 운영 | health/readiness, 외부 소스 상태, 구조화 로그, 기본 알림 |

### P1 — P0 안정화 후

- 검색 전용 탭
- 하루/여행 전체 순서·경로 최적화
- 알림 센터(개별/모두 읽음, unread, deep link)와 주변 추천
- 게시물 작성/미디어 저장소
- Live에서 현재 위치 기반 재계획(정밀 위치 서버 전송은 별도 동의)
- 영어 POI 명칭·설명 coverage와 번역 QA 강화(P0의 앱 UI chrome·고정 문구 영문 지원은 이미 포함)
- 정식 계정 login·익명 데이터 승계
- 선택한 지도·경로 사업자의 route matrix와 time window 최적화

### P2 — 검증 후

- 별도 ML 서비스, 개인화 ranking, 고급 수요 예측
- 다지역 확장과 운영자 CMS
- Redis/queue 분리 및 독립 worker 확장

### 명시적 비범위(P0)

- 결제·예약 대행
- 사용자 간 메시지/팔로우/댓글 등 완전한 SNS
- LLM이 관광지 존재, 영업 여부, 경로 가능성을 단독 판단하는 기능
- 사용자의 승인 없는 일정 자동 변경
- 백그라운드 정밀 위치 추적
- 서로 다른 출처/범위의 혼잡 값을 하나의 숫자 순위로 임의 합성
- P0 정식 계정 로그인·회원가입·소셜 login. S14의 login CTA는 P0에서 disabled `준비 중`으로 구현한다.

### 공모전 제출 profile

공모전 배포는 일반 P0보다 좁은 안전 profile이며 [공식 공지 요약](../contest/2026-관광데이터-활용-공모전-공지-심사기준.md)과 [준수 매트릭스](../contest/COMPETITION_COMPLIANCE_MATRIX.md)를 함께 만족해야 한다.

| 항목 | 제출 계약 | 출시 차단 조건 |
| --- | --- | --- |
| 접속 | 외부 HTTPS URL, 익명창, `로그인 불필요` | 핵심 흐름에 계정·운영자 조작이 필요함 |
| 구현 범위 | 배포본에서 끝까지 동작하는 P0만 기능설명서에 기재 | stub, dead CTA, mock-only 기능을 완료로 표기 |
| KTO 데이터 | Backend가 한국관광공사 OpenAPI를 실제 호출하고 비밀값 없는 call-audit 보존 | 파일/전체 mirror/replay만 사용하거나 호출 이력 없음 |
| 출처 | KTO 데이터가 보이는 화면과 상세 안내에 `출처: ⓒ한국관광공사` 또는 승인된 동등 문구 | 출처 누락, `TourAPI`만 표기, 승인 없는 CI·BI 로고 |
| 위치 | `FEATURE_NEARBY_LOCATION=OFF`, browser geolocation prompt 없음, 지역/장소 직접 선택 | 개인 위치의 서버 전송 또는 제출 직전 미검토 활성화 |
| 데이터 상태 | LIVE/FORECAST/REPLAY/STALE/UNAVAILABLE과 기준시각을 그대로 표시 | replay·cache를 실시간으로 오인시키는 문구 |

공식 마감은 2026-09-21 16:00(KST)이고 내부 제출 목표는 2026-09-20 16:00이다. 심사표의 구현성·기획력·데이터 활용·발전성은 새로운 기능 수가 아니라 위 핵심 흐름의 완결성과 검증 증거로 충족한다.

## 4. 도메인 용어와 불변식

| 용어 | 정의 | 반드시 지킬 규칙 |
| --- | --- | --- |
| SavedPost | 사용자가 나중에 볼 게시물을 보관한 상태 | 여행 일정과 무관할 수 있다. |
| TripCandidate | 특정 여행에 검토 대상으로 저장한 장소 | 기본값은 날짜/시간 없음. |
| TripItem | 날짜·순서·선택적 시간대를 가진 확정 일정 | 서버 version을 통해 동시 변경을 보호한다. |
| Constraint | 필수 방문/날짜/시간/예약 제약 | 종류별로 독립 잠금하며 암묵적으로 묶지 않는다. |
| OptimizationRun | 입력 snapshot에 대한 최적화 작업 | 입력 tripVersion과 data fingerprint를 보관한다. |
| Proposal | 적용 전 변경안 | 사용자 승인 전 TripItem을 변경하지 않는다. |
| Decision | apply/keep/revert의 감사 기록 | 같은 idempotency key는 같은 결과여야 한다. |
| CrowdSnapshot | 특정 출처·범위·시각의 혼잡 관측/예보 | provenance와 신선도 없이 노출하지 않는다. |

핵심 불변식:

1. `TripCandidate` 생성은 `TripItem` 생성이 아니다.
2. 한 사용자는 다른 사용자의 여행, 후보, 최적화 결과를 읽거나 변경할 수 없다.
3. apply 시 현재 `tripVersion`과 preview의 기준 version이 다르면 `TRIP_CHANGED`다.
4. preview 후 핵심 데이터가 바뀌면 `DATA_CHANGED`로 재계산한다.
5. 잠금과 충돌하는 제안은 만들거나 적용하지 않는다.
6. apply는 전체 성공 또는 전체 미적용이어야 한다.
7. 삭제는 감사·복구 정책에 맞게 처리하되 UI에서는 즉시 일관된 결과를 준다.
8. provenance가 불완전한 값은 정확한 수치처럼 표현하지 않는다.

## 5. 기능 요구사항

### 온보딩과 세션

- 최초 방문자는 한국어나 English를 선택하고 소개를 건너뛰거나 완료할 수 있다.
- 日本語와 中文은 항목과 `준비 중` 설명을 보이되 P0에서 선택·저장되지 않는다.
- P0은 익명 데모 세션을 지원한다. 세션 식별자는 Secure/HttpOnly/SameSite 쿠키로 관리한다.
- 서비스 핵심 기능은 회원가입을 강제하지 않는다. 정식 계정 연동은 호환 가능한 owner 모델로 확장한다.

### 여행 생성과 가져오기

- 날짜 범위는 종료일이 시작일보다 빠를 수 없고 서비스 지원 범위를 벗어나면 안내한다.
- 화면에 별도 제목 단계가 없으므로 제목은 선택값이다. 생략하면 서버가 session locale에 맞춰 `새 여행`/`New trip`이라는 결정적 기본값을 만들고 사용자는 나중에 수정할 수 있다.
- 관심사는 다중 선택이며 0개도 허용한다. 0개일 때는 생성 자체를 막지 않고 개인화 품질이 낮을 수 있음을 알리며, 사용자가 나중에 수정할 수 있다.
- 계획 수준은 `NOTHING`, `MUST_VISIT_ONLY`, `MOSTLY_PLANNED` 중 하나다.
- 붙여넣기 원문은 브라우저에서 우선 파싱하고, 서버 전송이 필요하면 동의·비저장·비로그 원칙을 적용한다.
- 파싱 결과는 원문이 아니라 구조화 draft로 보여주며 사용자가 매핑을 고친 뒤 확정한다.

### 피드와 후보 저장

- 피드는 장소/게시물의 핵심 맥락과 데이터 상태를 제공한다.
- P0 피드에는 계약이 없는 `팔로잉`/`최신` tab, 전역 검색, 알림, 작성자 팔로우, `혼잡도 낮은 순`/`지금 가기 좋아요`/지역 filter를 활성 control로 노출하지 않는다. 해당 기능은 P1 계약과 비교 적격성 규칙을 갖춘 뒤 연다.
- 저장 sheet에서 여행을 고르고, 활성 여행이 하나면 빠른 저장 경로를 제공할 수 있다.
- 이미 같은 여행에 후보가 있으면 성공처럼 보이는 중복 상태를 주되 중복 row를 만들지 않는다.
- 실패 시 일정이 변경되지 않았음을 명시하고 재시도할 수 있어야 한다.

### 내 여행 편집

- 보기와 편집 모드를 구분한다.
- 드래그가 있더라도 키보드와 버튼 기반 순서 변경을 함께 제공한다.
- 편집 중 이탈 시 변경이 있으면 폐기 확인 dialog를 연다.
- 날짜 이동, 시간 편집, 후보 일정화, 검색 추가, 삭제, 교체가 가능하다.
- optimistic UI를 쓰더라도 서버 충돌 시 최신 상태로 복구한다.
- 여행 이름, 시작일, 종료일, timezone은 `updateTrip`으로 수정하고 `If-Match`로 동시성을 보호한다.
- 날짜 범위를 줄였을 때 범위 밖 TripItem이 있으면 서버는 묵시적으로 삭제하지 않고 422와 구조화된 충돌 목록을 반환한다. UI는 이동·제거·취소를 사용자가 선택하게 한다.

### 최적화

- P0 기본 scope는 `ITEM`이며 한 장소를 더 한산한 날짜/시간으로 이동하는 제안이다.
- `ITEM` 요청은 선택한 `targetItemId`가 필수이며 DAY/TRIP target field를 함께 보내지 않는다.
- P1에서 `DAY`, `TRIP` scope를 연다.
- 후보 포함 옵션은 기본 OFF다.
- preview에는 before/after, 개선 지표, 검증 가능한 경우에만 이동/경로 영향, 제약 영향, 데이터 출처·신선도를 표시한다. P0에 route provider가 없으면 지도 대신 동등한 목록/timeline을 제공하고 이동 수치를 추정하지 않는다.
- apply/keep가 분명한 decision bar를 제공한다.
- apply 후 응답의 `beforeRevisionId`, `afterRevisionId`, `revertUntil`로 대상 revision과
  server `decidedAt` 기준 24시간의 되돌리기 가능 기간을 알려 준다.
- 되돌리기 기간이 끝나면 `REVERT_WINDOW_EXPIRED`를 적용 결과의 persistent 만료 상태로
  표시하고 현재 일정을 바꾸거나 자동 재시도하지 않는다.
- 오류 코드는 Figma 핸드오프와 OpenAPI 공통 오류 모델을 따른다.

### Live

- 목록은 항상 제공한다. 승인된 지도 provider·license·attribution이 준비된 환경에서만
  지도 capability를 열고 목록과 동일한 필터/선택 상태를 유지한다.
- 서울 실시간 값은 API가 제공한 서울특별시 attribution과 공식 source/license URL을
  표시하며 KTO attribution과 합치지 않는다.
- 장소명 검색을 노출하면 `searchPlaces`의 canonical 결과를 선택한 뒤 `getLivePlace`로 조회한다. Live coverage가 없는 장소는 값을 합성하지 않고 `UNAVAILABLE`과 다음 행동을 보여 준다.
- 실시간이 아니면 `REPLAY`, `FORECAST`, `QUALITATIVE`, `STALE`, `UNAVAILABLE`을 숨기지 않는다.
- 대체 장소는 `EXACT`, `SIMILAR`, `NONE`, `CHECKING`, `UNKNOWN` 관계를 구분한다.
- 비교 불가능한 데이터는 개선 수치나 순위를 만들지 않고 이유를 설명한다.
- P0 위치는 브라우저 안에서만 사용하며 명시적 동의 없이 서버로 전송하지 않는다.
- 공모전 제출 profile은 더 엄격하게 위치 capability를 OFF로 하고 browser geolocation 자체를 요청하지 않는다.

### 프로필

- P0은 `ANONYMOUS` owner를 guest로 표시하고 login CTA에 `준비 중`을 명시한다. CTA는 disabled이며 login route/API를 호출하지 않는다.
- `내 여행`에서 active trip과 전체 여행을 구분하고, row를 누르면 해당 여행으로 이동한다.
- `AI 최적화 이력`은 최적화 run ID, 여행, scope, 상태, 요청/완료 시각, decision을 보여 준다. 이력을 위해 일정 before/after 본문을 별도 보존하지 않고 보존 중인 run의 상세만 기존 `getOptimization`으로 조회한다.
- 이력 목록은 `listOptimizationHistory`의 cursor page를 사용하고 Frontend는 generated client로만 연결한다.
- `여행별 관심사`는 여행 선택 후 `replaceTripInterests` 요청으로 전체 set을 교체하며 ETag 충돌을 보여 준다.
- 데이터 안내, locale 변경, session/내 데이터 삭제로 진입할 수 있다.

### P1 알림

- 알림은 목록, unread count, 개별 읽음, `모두 읽음`, 허용된 내부 deep link를 제공한다.
- `모두 읽음`은 개별 API를 반복하지 않고 `markAllNotificationsRead`라는 단일 멱등 mutation을 사용한다.
- deep link는 서버와 클라이언트 모두의 route allowlist를 통과해야 하며, 대상이 없으면 안전한 fallback과 안내를 제공한다.

## 6. 데이터·추천 원칙

### 시간 비교

- 같은 POI, 같은 forecast issue/version, 같은 지표 체계 안에서만 전후를 수치 비교한다.
- `observedAt`과 `targetAt`을 구분한다.
- 현재 값과 예보 값을 같은 의미처럼 섞지 않는다.

### 공간 비교

- 같은 source, scope, comparisonGroup, snapshot batch인 경우에만 순위·개선폭을 계산한다.
- 서울 실시간과 KTO 혼잡 예보처럼 척도가 다른 데이터는 하나의 숫자로 정규화해 단정하지 않는다.
- 대체 장소 관계는 출처 또는 결정적 규칙의 근거를 보존한다.

### LLM 사용 경계

LLM은 사용자의 자연어 선호를 구조화하거나, 서버가 검증한 근거를 읽기 쉬운 설명으로 바꾸는 용도로만 사용한다. 후보 검색, 실시간 값, 영업 여부, 좌표, 이동 가능성, 제약 충돌, 적용 여부는 결정적 서버 로직이 담당한다. P0 일정 붙여넣기는 규칙 기반 한국어 parser를 기본으로 한다.

## 7. 접근성·반응형·국제화

- 기준 viewport: 360px 모바일, 768px 태블릿, 1280px 데스크톱.
- 터치 target은 원칙적으로 44×44px 이상이다.
- 모든 interactive control에 접근 가능한 이름과 keyboard focus가 있다.
- dialog/sheet는 focus trap, Escape 닫기, 닫힌 뒤 trigger focus 복귀를 지원한다.
- 색만으로 혼잡·선택·오류를 전달하지 않는다.
- `prefers-reduced-motion`을 존중한다.
- 한국어와 English UI copy를 P0로 제공하고 긴 영어 문자열에서도 핵심 CTA와 수치가 잘리지 않는다.
- 외부 소스에 영어 POI 문안이 없으면 한국어 원문을 선정적 기계번역하지 않고 원문/언어 표시와 안전한 fallback을 쓴다.
- 날짜·시간·숫자 표기는 locale과 timezone(`Asia/Seoul` 기본)을 명시한다.

## 8. 2인 역할 분담과 인계

| 영역 | Frontend 담당자 | Backend/AI 담당자 | 공동 gate |
| --- | --- | --- | --- |
| 화면·라우팅 | Figma 구조, component/state, i18n, accessibility, PWA | response capability·deep-link 데이터 | 360px KO/EN E2E·상태 fixture |
| 여행·후보·일정 | edit draft, form validation, mutation UX, generated client | owner/version/idempotency, transaction, constraint | OpenAPI 예시·contract·happy/failure E2E |
| 최적화·AI | preview/decision/error UI, polling/backoff | deterministic optimizer, data validation, LLM 경계, audit | 승인 전 변경 0·apply 원자성 |
| Live·데이터 | list-first, provenance/state/fallback, 승인 시 map | connector, mapping, freshness, comparison eligibility | map OFF와 소스 장애·replay·stale rehearsal |
| 프로필·알림 | guest/login-준비 중, 목록/편집/deep-link UI | owner query, history projection, interests, read mutation | 추가 API contract 먼저 merge |
| 품질·운영 | component/unit/Playwright/a11y/RUM | unit/integration/contract/data quality/metrics | 수직 slice별 양쪽 required check |

인계 순서는 `계약 이슈 확정 → OpenAPI 및 예시 수정 → Backend contract test 시나리오 확정 → Frontend client 재생성 → 양쪽 E2E`다. 실제 endpoint가 아직 없어도 mock은 확정된 OpenAPI example로만 만든다. Frontend는 `frontend`, Backend/AI는 `backend`에서 작업해 각각 `main`에 PR을 만들며, 교차 변경 순서는 [브랜치·Docker 통합 계약](../engineering/BRANCH_AND_INTEGRATION.md)을 따른다. 화면별 세부 책임은 [Figma 핸드오프](../design/FIGMA_HANDOFF.md)를 따른다.

## 9. 개인정보와 보존

| 데이터 | P0 처리 | 기본 보존 |
| --- | --- | --- |
| 익명 session | 난수 식별자, HttpOnly cookie | 마지막 활동 후 30일 또는 사용자 삭제 |
| 여행/후보/일정 | 계정 또는 session owner에 귀속 | 사용자 삭제 시 제거, 운영 백업은 별도 만료 |
| 붙여넣기 원문 | 기본적으로 브라우저 처리, 서버 비저장·비로그 | 요청 처리 종료 즉시 폐기 |
| 정밀 위치 | 서버 수집 안 함 | 없음 |
| 구조화 이벤트 | 가명 owner, 최소 필드 | 90일 후 집계 또는 삭제 |
| 오류 로그 | request id 중심, 본문/쿠키/원문 제외 | 30일(운영 확정 전 기본값) |

보존 기간은 법무·운영 검토 전 기술 기본값이며, 개인정보 처리방침 확정 시 더 짧은 값이 우선한다.

## 10. 출시 완료 정의

- Figma P0 화면의 default/loading/empty/error/success/stale 상태가 구현됐다.
- OpenAPI와 구현의 contract test가 통과한다.
- 전체 P0 happy path와 핵심 실패 흐름이 Playwright에서 통과한다.
- 키보드, screen reader 이름, contrast, reduced motion 검사가 통과한다.
- PostgreSQL migration upgrade/downgrade가 staging snapshot에서 검증됐다.
- 외부 데이터별 source/freshness/confidence와 장애 fallback이 노출된다.
- 일정 apply가 version/idempotency/transaction 조건을 충족한다.
- AWS staging에서 백업 복구, secret 주입, alert, rollback rehearsal를 마쳤다.
- 결정 대장의 P0 blocker가 모두 닫혔다.
- A-2의 KO/EN은 실제로 작동하고 JA/ZH는 준비 중으로만 표시된다.
- S14의 guest/내 여행/최적화 상태 이력/여행별 관심사/데이터 안내가 완성됐고 login CTA는 준비 중 상태다.
- 최적화 이력 목적으로 일정 본문을 추가 보존하지 않는다.
- 외부망·익명창에서 핵심 심사 흐름이 동작하고 공모전 위치 flag가 OFF다.
- 최종 배포본에서 실제 KTO OpenAPI 호출·call-audit·화면 출처를 검증했다.
- 공식 기능설명서 양식을 유지하고 배포된 기능·API와 PDF의 목록이 일치한다.
- `frontend`/`backend` PR의 `docs-contract`와 M0 상태에 맞는 `docker-integration`이 통과했다.
