# 화면·기능 소유권 매트릭스

- 상태: Accepted execution contract
- 역할: `FE_DRI` 1명, `BE_AI_DRI` 1명
- 정본: Figma `02 UI Design`, 기능 인벤토리, OpenAPI/event schema

이 문서는 업무를 기술 layer로 분리해 마지막에 합치는 것을 막기 위한 실행 책임표다. FE 담당은 사용자 경험의 DRI, Backend/AI 담당은 server truth의 DRI다. 두 사람은 각 화면의 계약과 staging acceptance를 공동 승인한다.

총괄 PM은 별도의 구현 seat가 아니라 governance 승인자다. scope, priority, 사용자 문구,
공모전 claim과 최종 go/no-go를 결정한다. FE의 접근성·시각 완결성 또는 BE/AI의
보안·데이터·도메인 무결성 veto를 덮을 수 없으며, GitHub의 필수 상대 review를
대신하지 않는다.

실제 이름/GitHub handle은 M0에서 다음 표를 채운다.

| Role key | 실제 이름 | GitHub handle | 부재 시 release 역할 |
| --- | --- | --- | --- |
| `FE_DRI` | `TBD` | `TBD` | 사용자 영향/화면 rollback 판단 |
| `BE_AI_DRI` | `TBD` | `TBD` | API/data/infra rollback 판단 |

임의 handle을 기입하지 않는다. production 전 `TBD`는 허용하지 않는다.

## 1. 전체 Figma frame 책임

`UI`는 FE가 제공할 화면/state/test, `Server truth`는 BE/AI가 제공할 계약/domain/fixture를 뜻한다. 공동 gate는 상대 담당자가 실제 API로 재현한다.

| Node | 화면/variant | Pri | FE_DRI: UI | BE_AI_DRI: Server truth | 공동 gate |
| --- | --- | --- | --- | --- | --- |
| `388:257` | A-1 splash | P0 | bootstrap/retry/redirect | session owner/readiness/CSRF | 신규·재방문·실패·만료 |
| `388:277` | A-2 언어 | P0 | KO/EN 전환, JA/ZH disabled, 긴 문자열 | locale preference와 미지원 값 거부 | refresh·KO/EN copy/format·준비 중 |
| `388:321` | A-3 소개 | P0 | 계속/건너뛰기 | onboarding 상태 | redirect loop 없음 |
| `391:310` | S03-F0 여행 없음 feed | P0 | empty-trip CTA/feed | feed+trip empty read | 여행 없음≠feed 오류 |
| `396:2926` | S03-F1 활성 여행 feed | P0 | active context/save state | trip-scoped feed state | 저장 전후 일관성 |
| `398:611` | S03-D 게시물 상세 | P0 | deep link/detail/actions | post/place/saved read | 404·삭제·저장 상태 |
| `399:658` | S03-C1 여행 선택 | P0 | picker/focus/new trip CTA | owner-scoped trips | empty/loading/selection |
| `399:843` | S03-C2 저장 완료 | P0 | success result/next action | candidate 201/idempotency | item/version 미변경 |
| `399:1011` | S03-C3 중복 | P0 | duplicate 안내/기존 이동 | existing candidate 200 | row 한 개 유지 |
| `399:1179` | S03-C4 저장 오류 | P0 | retry/미변경 문구 | Problem/replay key | 재시도 중복 없음 |
| `409:1595` | S06 공통 저장 sheet | P0 | 재사용 sheet contract | 위 candidate 계약 | feed/detail/live 공통 |
| `438:3012` | S02-1 날짜 | P0 | range/timezone validation | create/update range rule | 경계·역전·축소 충돌 |
| `438:3108` | S02-2 관심사 | P0 | chip/0개 안내 | canonical interest set | 중복·미지원 값 |
| `438:3134` | S02-3 계획 수준 | P0 | enum 선택/복구 | planning enum | back/refresh 복구 |
| `438:3158` | S02-4B 필수 장소 | P0 | search/select/remove | place mapping/constraint | canonical POI만 확정 |
| `400:1201` | S02-4C 입력 방식 | P0 | 분기/뒤로가기 | capability | 선택 복구 |
| `401:1221` | S02-4C-A 붙여넣기 | P0 | parse/review/remap | no-store parser/draft TTL | 원문 DB/log/event 0 |
| `438:3199` | S02-4C-C 직접 입력 | P0 | 날짜별 장소/순서 | place validation | 유효 range/order |
| `438:3259` | S02-5C 확인 | P0 | 구조화 요약/수정 | idempotent create/confirm | 부분 trip 없음 |
| `384:5673` | Final S02-5 결정적 draft | P0 | result/evidence/CTA | deterministic seed schedule | 근거·lock 일치 |
| `440:3244` | S02-6 AI draft | P1 | capability/AI 표기 | bounded AI orchestration | OFF state·승인 경계 |
| `410:1738` | S07-1 여행 보기 | P0 | day/item/candidate read | aggregate/ETag | complete view·empty day |
| `411:1837` | S07-2 편집 | P0 | edit buffer/save/cancel | atomic commands/version | 실패 부분 반영 0 |
| `527:4085` | S07-2 시간 편집 | P0 | time/duration/lock UI | time validation/constraint | timezone·독립 lock |
| `412:1912` | S07-8 후보 panel | P0 | active/scheduled/date picker | candidate page/match/schedule | 일정화 원자 적용 |
| `413:2020` | S07-9 폐기 dialog | P0 | dirty-exit/focus | 해당 없음 | 폐기·계속 편집 |
| `413:2081` | S07-7 필수 lock 해제 | P0 | 결과 명시 confirm | typed constraint removal | 다른 lock 유지 |
| `414:2347` | S07-6 교체 비교 | P0 | before/after/provenance | related/replace command | 비교 불가 처리 |
| `527:4537` | S07-6 교체 비교 variant | P0 | 긴 정보/mobile variant | 동일 계약 | 시각·데이터 동등성 |
| `476:3409` | S07-3 장소 검색 | P0 | debounce/result/empty/error | bounded place search | 취소·race·rate limit |
| `479:3497` | S07-5 교체 대상 | P0 | target picker | replace eligibility | 잘못된 item 방지 |
| `479:3816` | S07-4 추가 완료 | P0 | 새 item 강조/result | add item/version | position/version +1 |
| `527:4380` | S07-4 추가 완료 variant | P0 | variant parity | 동일 계약 | 새로고침 일치 |
| `521:3976` | S07-10 날짜 이동 | P0 | target date/order | move command | range/position 유효 |
| `527:4695` | S07-10 날짜 이동 variant | P0 | date-lock 표현 | 동일 계약 | keyboard 동등 기능 |
| `527:3876` | S07-10b 날짜 lock 확인 | P0 | 영향 confirm | typed date-lock conflict | 무단 unlock 없음 |
| `415:2268` | S09-0 최적화 설정 | P0 | item/scope/lock/capability | create run/input snapshot | P0 ITEM만 활성 |
| `415:2413` | S09-1 계산 중 | P0 | poll/back/resume/timeout | async state/Retry-After | refresh 복원 |
| `439:3104` | S09-D1 하루 preview | P1 | DAY capability/preview | day engine/route gate | OFF state 우선 |
| `417:2412` | S09-3 적용 완료 | P0 | applied/undo/result | revision/decision/revert | 원자 apply·revert |
| `417:2567` | S09 오류 reference | P0 ref | 오류 6종 story/CTA | 정확 code/state | 각 오류 일정 미변경 |
| `485:3517` | stale reference | P0 ref | 재계산 UX | trip/data fingerprint | stale 적용 차단 |
| `418:2523` | S11-1 Live | P0 | list-first/area selection, map capability | area/places/readiness | map OFF 목록·map ON attribution·state label |
| `419:2617` | S11-2 Live 장소 상세 | P0 | detail/freshness/action | place provenance | source/time/state 완전성 |
| `420:2821` | S11-3 대안 | P0 | relation/reason/metric | verified related/comparison | eligible일 때만 delta |
| `420:2950` | S11-N 대안 없음 | P0 | empty/recovery CTA | relation NONE/reason | fake 후보 없음 |
| `421:2850` | S11-R replay | P0 | replay badge/banner | replay snapshot metadata | 현재값 오인 없음 |
| `501:3750` | S11-4 재계획 진입 | P1 | 동의/capability | consent/location boundary | OFF state·DPIA 선행 |
| `422:2925` | S14 프로필 | P0 | guest/login 준비 중, KO/EN, 여행·관심사·최적화 이력, 삭제/data link | owner/trip/history projection, interests, deletion job | refresh·ETag·이력 cursor·삭제 추적 |
| `423:2967` | S15 데이터 안내 | P0 | source/state/신뢰 설명 | source registry/capability | 실제 응답 용어와 일치 |
| `442:3344` | S12 알림 | P1 | empty/unread/read-all/deep link | notification/read/allowlist | OFF state·삭제 target |
| `442:3370` | S10 주변 | P1 | opt-in/list/취소 | consent/minimized location | 정밀 위치 기본 수집 0 |

위 표는 현재 Figma의 52개 frame이다. P0 reference frame도 구현 대상
Storybook/fixture에서 제외하지 않는다. 다만 ITEM READY preview가 빠져 있으므로
`FCR-004` 수정 node가 생기기 전에는 최적화 화면 inventory가 완결된 것이 아니다.
그룹 container node는 화면 수에 포함하지 않으며 디자인 추적용으로만 유지한다.

## 2. 경로·artifact 소유권

| 경로/대상 | Primary DRI | Required reviewer | 변경 조건 |
| --- | --- | --- | --- |
| `apps/web/**` | FE | BE/AI: API/data 의미 변경 시 | web gate, screenshot, contract SHA |
| `apps/api/**` | BE/AI | FE: public API/error 변경 시 | API/integration/contract gate |
| `packages/api-client/**` | 생성기 | FE+BE/AI | 직접 편집 금지, clean regeneration |
| `packages/contracts/**` | BE/AI fixture, FE consumer | 상대 담당자 | schema-valid, no PII |
| `docs/api/**`, `docs/contracts/**` | BE/AI | FE 필수 | lint/breaking diff/generated client |
| `docs/design/**`, UI token | FE | BE/AI: 기능 의미 확인 | Figma node traceability/visual diff |
| `docs/product/**` | 공동 | 둘 다 + PM 범위 승인 | 기능 ID·priority·claim 변경 합의 |
| DB/Flyway | BE/AI | FE informed, destructive는 공동 | compatibility/restore evidence |
| source/AI/optimizer | BE/AI | FE: 설명/상태 확인 | evidence/safety/property test |
| `infra/**`, operations | BE/AI | FE 필수 | synth/diff/rollback/public config 검토 |
| privacy/security | 발견자 작성 | 둘 다 | threat/retention/incident gate |
| `docs/contest/**`, 제출 PDF/evidence | 공동(항목별 DRI) | 둘 다 | 실제 배포·KTO 호출·출처·양식·마감 대조 |

Primary DRI와 CODEOWNERS는 같은 개념이 아니다. 실제 handle 확정 뒤 실행 경로의
CODEOWNERS에는 두 팀원을 함께 지정한다. PR 작성자는 자기 PR을 승인할 수 없으므로
web에 FE만, API에 BE/AI만 owner로 지정한 상태에서 code-owner review를 강제하면
일반 역할 PR이 막힐 수 있다. 표의 Primary DRI는 구현 책임을, ruleset의 상대 1인
승인은 독립 검토를 보장한다. required reviewer가 휴가·장애로 부재하면 production
merge를 미루는 것이 기본이며 긴급 변경은 incident 절차와 다음 영업일 사후 검토가
필요하다.

## 3. Slice별 handoff 경계

| Slice | FE가 BE/AI에 제공 | BE/AI가 FE에 제공 | 통합 전에 금지 |
| --- | --- | --- | --- |
| Session/onboarding | redirect diagram, locale/401 UX | bootstrap/cookie/CSRF examples | localStorage owner를 server owner로 간주 |
| Trip create/import | 구조화 draft와 validation UX | command schema, parse warning/remap examples | raw pasted text persistence/log |
| Feed/candidate | card state와 retry semantics | cursor/duplicate/idempotency examples | 후보 저장 시 item 생성 |
| Trip edit | edit command intent와 dirty UX | typed constraints, ETag/conflict payload | last-write-wins/부분 command |
| Optimization | before/after/decision/error display | immutable proposal/evidence/validation | LLM 단독 사실 판단·자동 apply |
| Live | label/relation/empty UX | provenance/comparison eligibility | source 혼합 delta/가짜 fallback |
| Profile/delete | 삭제 confirm·receipt/status UX | revoke/job/tombstone lifecycle | 202를 완료로 표시 |
| Notifications P1 | read/deep-link/empty UX | type enum/read-all/allowlist | 외부 URL 임의 redirect |
| Contest judge flow | 외부망·익명 UI, attribution, screenshot/PDF | 실제 KTO call-audit, readiness, 위치 OFF | mock/P1을 구현 완료로 제출 |

## 4. Review와 승인 규칙

- 계약 PR: BE/AI가 작성하고 FE가 화면에 필요한 state와 생성 client를 승인한다.
- 화면 PR: FE가 작성하고 BE/AI가 server truth/데이터 문구/analytics allowlist를 확인한다.
- DB·AI·source PR: BE/AI가 작성하고 FE가 사용자에게 보이는 degradation과 설명을 확인한다.
- 인프라·release PR: BE/AI가 실행하고 FE가 web artifact/config/rollback 및 사용자 journey를 확인한다.
- 기능 ID, priority, 개인정보, 일정 무결성, production 비용을 바꾸는 PR은 둘 다 승인한다.
- scope, 사용자 문구, 공모전 claim은 두 DRI의 기술 영향 검토 뒤 총괄 PM이 승인한다.
  PM 승인은 자동 gate나 필수 상대 review를 대체하지 않는다.
- 자신의 변경을 자신만의 수동 확인으로 승인하지 않는다. 자동 gate와 상대의 재현 evidence가 모두 필요하다.
- FE는 `frontend`, BE/AI는 `backend`에서 작업해 `main`에 PR을 만들고 상대 승인·`docs-contract`·`docker-integration` 후 merge commit한다.
- 공모전 제출 기술 go/no-go와 접수 완료는 둘 다 확인하며, 제출 양식의 실제
  구현/API 목록은 각 담당자가 자기 영역을 서명하고 상대가 대조한다. 총괄 PM은 이
  증거를 바탕으로 최종 제출 범위와 claim의 go/no-go를 결정한다.

## 5. 담당 변경 절차

담당 변경은 issue assignee만 바꾸지 않고 다음을 넘긴다.

1. 마지막 승인 contract SHA와 Figma node 목록
2. 완료/미완료 fixture와 acceptance ID
3. 열린 migration/flag/source/보안 위험
4. staging URL/release version과 rollback target
5. 결정 대장의 owner·필요 시점 갱신

둘 중 한 사람이 장기간 부재하면 WIP를 늘리지 않고 P0 safety와 운영 유지에 범위를 줄인다. Backend/AI 운영 책임을 FE에게 묵시적으로 전가하거나, FE 접근성 검수를 BE/AI가 생략하지 않는다.
