# 2인 팀 개발 방식

- 역할: Frontend 담당 1명(`FE_DRI`), Backend/AI 담당 1명(`BE_AI_DRI`)
- 원칙: 소유권은 명확하게, 계약과 사용자 흐름은 공동 책임
- 단위: 기술 layer가 아니라 끝까지 동작하는 vertical slice

실제 이름과 GitHub handle은 M0에서 `FE_DRI`, `BE_AI_DRI` 자리에 기록한다. 이름이 비어 있어도 책임이 공동으로 흐려지지 않도록 아래 경로·화면 기준의 DRI를 사용한다. AI는 독립된 세 번째 담당이 아니라 Backend/AI 담당의 책임 범위이며, 사용자 승인·결정적 검증·근거 보존 규칙을 동일하게 적용한다.

## 1. 책임 분담

| 영역 | Frontend 담당 | Backend/AI 담당 | 공동 승인 |
| --- | --- | --- | --- |
| Figma handoff/design token | DRI | Consulted | 화면 의미 변경 |
| 접근성/반응형/PWA | DRI | Consulted | P0 gate |
| OpenAPI consumer/client | DRI | DRI(provider) | 모든 contract change |
| Spring/domain/DB migration | Consulted | DRI | 불변식·보존 정책 |
| 외부 관광/혼잡 adapter | Informed | DRI | UI data state 문구 |
| 추천/최적화 규칙 | UI 검증 | DRI | before/after와 안전성 |
| analytics event schema | DRI(emission) | DRI(validation) | event 추가/변경 |
| E2E | DRI 작성 | fixture/API 지원 | merge gate |
| AWS/IaC/운영 | Consulted | DRI | production 배포 |
| 제품 범위/우선순위·공모전 claim | 영향·실행성 제안 | 영향·안전성 제안 | 총괄 PM 승인, 기술 safety는 둘 다 |

DRI는 작업을 끝까지 책임지는 사람이지 단독 결정권자가 아니다. FE/BE·AI 경계를 넘는 계약 변경은 두 사람 승인 없이는 merge하지 않는다.

### 역할별 최종 책임

**Frontend 담당**은 Figma의 모든 화면과 component variant, route, client state, 접근성, 반응형, PWA, 생성 API client 사용, MSW fixture, component/E2E test와 사용자에게 보이는 데이터 출처·오류 문구의 최종 책임자다. 서버 응답을 화면에서 임의로 추론하거나 mock 전용 필드를 production 코드에 남기지 않는다.

**Backend/AI 담당**은 OpenAPI와 event 계약, session/권한, domain 불변식, PostgreSQL/Flyway, 외부 데이터 adapter와 provenance, 추천·최적화·LLM 경계, 비동기 job, 운영 관측, AWS/IaC의 최종 책임자다. Figma가 요구하는 상태를 누락하지 않도록 모든 응답 예시와 error code를 제공한다.

**공동 책임**은 Figma와 계약의 의미 충돌 해소, acceptance 재현,
security/privacy review, staging 기술 go/no-go와 production 배포다. 총괄 PM은 두
담당자의 영향 분석을 받아 scope/priority, 사용자 문구, 공모전 claim과 최종 go/no-go를
승인한다. PM은 별도의 세 번째 구현자나 우회 code reviewer가 아니며, 계약을 작성한
사람이 혼자 승인하지 않는다.

### Figma 그룹별 delivery 책임

| 그룹/화면 | Milestone | Frontend 담당 산출물 | Backend/AI 담당 산출물 | 병렬 착수용 계약·mock | 공동 acceptance |
| --- | --- | --- | --- | --- | --- |
| A 시작·언어·소개 | M1 | splash, KO/EN UI, JA/ZH 준비 중, intro, bootstrap/redirect | session bootstrap, locale preference, capability/readiness | `SessionBootstrap` 예시, 401/CSRF/timeout MSW | KO/EN 전환·신규·재방문·만료·다중 tab에서 loop 없음 |
| B Feed·게시물·후보 저장 | M2 | feed/card/detail, 여행 picker와 저장 결과 4종 | cursor feed, post/place read, candidate 멱등 저장 | feed/post/candidate success·duplicate·error fixtures | `+`가 후보만 만들고 일정/version을 바꾸지 않음 |
| C 여행 만들기 | M1 | wizard, 복구, 붙여넣기 review, 최종 확인 | place search, import parse/remap/confirm, trip 생성 | step별 draft fixture와 validation Problem examples | 원문 비저장, 멱등 생성, 날짜/장소 server 재검증 |
| D/E 내 여행 보기·편집 | M3 | view/edit buffer, 후보 panel, 검색·추가·이동·교체·잠금 dialog | trip aggregate, ETag, item command, typed constraint, revision | complete trip + 각 lock/conflict examples | 한 command당 원자 변경, stale 복구, keyboard 동등 기능 |
| F 최적화 | M5 | setup/loading/preview/applied/keep/revert와 오류 6종 | run state machine, 결정적 후보/검증/score, apply/revert | run 상태·before/after·오류별 fixture | 승인 전 무변경, lock 보존, apply 실패 시 부분 반영 0 |
| G Live | M4 | list-first/detail/대안/none/replay/degraded, 승인 시 map | source registry/collector, live/forecast/replay API, 비교 적격성 | DataState와 relation의 전체 조합 fixture | map OFF 목록·map ON attribution, 비교 불가 시 delta/rank 없음 |
| H 프로필·데이터 안내·알림 | M1/M4/M5/P1 | guest/login 준비 중, 여행·관심사·최적화 이력, 데이터 안내, 알림 state | preference·삭제 job, trip/history projection, source 설명, 알림 API | profile/deletion/history/data-guide/notification fixture | P0 profile 전체와 P1 알림 capability가 일치하고 삭제 추적 가능 |
| I 개발 reference state | 각 milestone | Storybook/MSW error·stale·replay story | 재현 가능한 Problem/fixture generator | 고정 seed와 schema-valid JSON | 모든 reference state가 자동 visual/contract test에 존재 |

위 표는 화면을 한 담당자에게 통째로 넘긴다는 뜻이 아니다. 같은 행에서 FE는 mock으로, BE/AI는 contract test와 domain으로 동시에 시작하고, `integration-ready` 조건을 충족할 때 실제 API로 교체한다. P1 화면도 정본에서 삭제하지 않으며 capability OFF 상태와 backlog acceptance까지 정의한다.

## 2. 작업 단위

한 ticket은 가능한 한 다음을 함께 닫는다.

```text
Figma state → OpenAPI/event diff → DB/domain → API → generated client
→ UI default/loading/empty/error → tests → observability → docs
```

예: “후보 저장” slice에는 button만이 아니라 여행 picker, 201/200 duplicate, failure retry, idempotency, candidate unique constraint, analytics, E2E가 포함된다.

### Ticket Definition of Ready

- 연결된 Figma node와 P0/P1이 있다.
- 사용자 action 전/후 domain state가 적혀 있다.
- API request/response/error 초안이 있다.
- 개인정보·출처·접근성 영향이 검토됐다.
- mock/fixture와 acceptance test 예시가 있다.
- 미결정 외부 의존성이 있으면 feature flag/fallback이 정해졌다.

### Ticket Definition of Done

- OpenAPI/event/ERD가 구현과 일치한다.
- FE와 BE/AI 각각 해당 test를 통과한다.
- loading/empty/error/offline/stale 중 적용 가능한 상태가 구현됐다.
- keyboard와 360px viewport 검증을 했다.
- 로그/metric에 필요한 식별자와 실패 code가 있다.
- 새 env/secret/migration/runbook 변경이 문서화됐다.
- reviewer가 acceptance를 재현했다.

### Contract packet과 상태 전이

모든 slice는 issue 또는 PR에 다음 packet을 같은 revision으로 묶는다.

| 항목 | 작성 DRI | 상대가 확인할 내용 | 저장 위치/증거 |
| --- | --- | --- | --- |
| Figma 범위 | FE | 누락된 server state/action 여부 | node URL, 화면·variant checklist |
| 기능 ID·acceptance | 공동 | P0/P1, 정상·실패 후 domain state | 기능 인벤토리 ID와 Given/When/Then |
| OpenAPI/Problem | BE/AI | 화면이 필요한 모든 field/error/retry 정보 | OpenAPI diff와 Redocly 결과 |
| example/fixture | BE/AI | null/empty/stale/duplicate/conflict 표현 | schema-valid JSON example |
| 생성 client/MSW | FE | spec 이외 hand-written type 없음 | generated diff와 MSW handler |
| DB/불변식 | BE/AI | UI action의 실제 효과가 문구와 일치 | ERD/Flyway/transaction test |
| event/관측 | FE emit, BE/AI validate | PII 없음, 실패 code와 requestId 연결 | event schema와 dashboard query |
| acceptance evidence | 구현 DRI | 상대 담당자가 재현 가능 | test report, mobile screenshot/video |

작업 상태는 `draft → contract-ready → parallel-build → integration-ready → staging-accepted → done`으로만 이동한다.

- `contract-ready`: operationId, request/response, error, Figma state와 mock example이 review됐다.
- `parallel-build`: FE는 generated type+MSW만, BE/AI는 같은 spec+fixture로 구현한다.
- `integration-ready`: spec/generated client가 clean하고 provider contract test와 FE component test가 통과했다.
- `staging-accepted`: 상대 담당자가 실제 API로 mobile journey와 실패 분기를 재현했다.
- `done`: 문서·관측·rollback까지 닫혔다. merge만 된 상태는 done이 아니다.

계약 변경이 생기면 BE/AI는 먼저 OpenAPI/example을 갱신하고 FE는 generated client와 mock을 같은 PR 또는 연결 PR에서 갱신한다. FE가 화면 구현 중 필요한 필드를 발견하면 임시 필드를 만들지 않고 contract issue를 연다. 병렬 branch 사이의 contract SHA가 다르면 통합하지 않는다.

### Slice acceptance 작성 형식

```text
Given: session/data/trip version과 사용 중인 Figma variant
When: 사용자의 한 가지 명시적 행동
Then UI: 화면, focus, loading/empty/error, 재시도 결과
Then domain: 생성/변경/미변경 row와 version
Then contract: status, headers, operationId, Problem code
Then evidence: FE test, BE/AI test, E2E ID, metric/log(redacted)
```

특히 후보 저장, 일정 편집, 최적화는 `변경되지 않아야 하는 것`을 Then에 반드시 쓴다.

## 3. 주간/일일 리듬

### 매주 시작 45분

1. 이번 주 vertical slice 1–2개만 WIP로 선택한다.
2. 먼저 OpenAPI와 Figma 상태를 15분 내 합의한다.
3. FE는 mock/client, BE/AI는 contract/domain으로 병렬 착수한다.
4. 외부 API/계정 blocker를 결정 대장에 갱신한다.
5. 공모전 제출 전에는 준수 매트릭스의 미충족 gate와 남은 공식 일정도 확인한다.

### 매일 10분 interface sync

- 어제 닫힌 contract diff
- 오늘 바뀔 request/response/error
- 필요한 fixture와 통합 시점
- blocker와 누가 언제 해결하는지

### 주 2회 integration slot

staging에서 두 사람이 같은 시나리오를 함께 실행한다. 긴 “통합 주간”을 마지막에 따로 두지 않는다.

### 주말/마일스톤 review

- P0 funnel과 error dashboard
- source freshness/quota
- flaky test와 접근성 회귀
- 다음 slice를 막는 미결정

## 4. Git/PR 규칙

### Branch

- Frontend 담당 장기 브랜치: `frontend`
- Backend/AI 담당 장기 브랜치: `backend`
- 통합·배포 기준선: `main`

역할 브랜치는 삭제·force push하지 않고 각각 `main`에만 PR을 만든다. 사람별 WIP는 역할 브랜치에 미병합 vertical slice 1개다. 병합은 상대 승인과 `docs-contract`·`docker-integration` 뒤 merge commit으로 하고, 병합 후 두 역할 브랜치를 최신 `main`으로 동기화한다. contract가 양쪽에 걸리면 additive contract PR을 먼저 `backend → main`으로 병합한 뒤 양 브랜치를 동기화한다. Frontend merge만으로 server capability를 켜지 않고 별도 `backend → main` flag/config PR과 상대 승인·staging acceptance를 거친다. 전체 명령·충돌·capability 순서는 [브랜치·Docker 통합 계약](./BRANCH_AND_INTEGRATION.md)이 정본이다.

### Commit

Conventional Commits를 사용한다.

```text
feat(candidates): FR-CAN-02 persist trip-scoped place candidates
fix(optimization): FR-OPT-07 reject stale preview before apply
docs(api): FR-CAN-02 define duplicate candidate response
```

### PR 크기와 승인

- 목표: reviewer가 30분 안에 이해할 수 있는 크기.
- contract/DB migration/보안/배포 변경은 상대 담당자 승인이 필수다.
- 순수 문구나 독립 test fixture도 CI는 통과해야 한다.
- 자기 승인 merge는 긴급 복구 외에는 금지한다.
- 긴급 복구는 다음 영업일에 상대 검토와 회고를 남긴다.
- 역할 브랜치에서 다른 사람의 미병합 작업을 덮거나 rebase/force-push하지 않는다.

PR 설명에는 목적, Figma node, contract diff, 데이터/migration, 테스트, screenshot, 운영/rollback을 채운다. template은 `.github/pull_request_template.md`를 사용한다.

공모전 제출 후보 변경은 실제 KTO operation·비밀값 없는 call-audit·화면 출처·익명 외부망 smoke·기능설명서 영향도 함께 기록한다. mock/P1/disabled 기능을 배포 완료로 표현하지 않는다.

경로별 review 책임과 GitHub 외부 설정은 [OWNERSHIP_MATRIX.md](./OWNERSHIP_MATRIX.md)와 [GITHUB_RELEASE_OPERATIONS.md](../operations/GITHUB_RELEASE_OPERATIONS.md)를 따른다. 실제 GitHub handle이 정해지기 전 임의 계정으로 CODEOWNERS를 만들지 않는다.

## 5. 계약 변경 프로토콜

### Additive change

1. optional response field 또는 새 endpoint를 spec에 추가한다.
2. BE/AI가 구/신 client에 호환되게 배포한다.
3. FE가 사용하기 시작한다.
4. 관측 후 required 전환이 필요하면 별도 version/change로 진행한다.

### Breaking change

- 가능하면 새 field/endpoint로 expand-and-contract한다.
- 제거 예정은 `deprecated: true`와 제거 milestone을 둔다.
- FE production 사용이 0임을 확인한 뒤 제거한다.
- 한 PR에서 backend와 frontend를 서로 깨뜨리는 순차 배포를 만들지 않는다.

### Schema migration

- app rollback이 가능한 기간 동안 구 column/read path를 유지한다.
- migration과 app deploy 순서를 PR에 명시한다.
- backup/restore 또는 downgrade가 실제로 가능한지 staging에서 검증한다.

## 6. Feature flag

외부 data나 P1 기능은 server-owned flag로 보호한다.

- 기본값 OFF, environment별 명시.
- 사용자 식별 정보를 flag key로 외부 SaaS에 보내지 않는다.
- UI는 disabled capability를 숨기거나 명확한 대체 상태를 제공한다.
- 만료일/제거 ticket 없는 영구 flag를 만들지 않는다.
- 안전 불변식(승인 전 미변경, owner 격리)을 flag로 끌 수 없다.

## 7. Claude Code 사용 방식

- 공모전 공식 FAQ는 생성형 AI와 AI 코딩 보조 도구 사용을 허용하지만, 평가 핵심은 안정적으로 구동되는 완성 서비스다. 도구 사용을 test·human review·구현 완료의 대체 증거로 삼지 않는다.
- repository root의 `CLAUDE.md`를 먼저 읽게 한다.
- 한 prompt에는 ticket, Figma node, 허용 범위, acceptance, 실행할 검증을 포함한다.
- 큰 작업은 먼저 읽기 전용 분석과 변경 계획을 받고, 승인된 범위만 구현한다.
- 생성된 migration/OpenAPI/security 코드는 사람이 반드시 검토한다.
- Claude가 바꾼 파일 목록과 실행한 test를 PR에 적는다.
- secret, production data, 비식별되지 않은 사용자 입력을 prompt/context에 넣지 않는다.
- 실패한 test를 삭제하거나 gate를 완화해 통과시키지 않는다.

권장 ticket prompt 예시는 다음과 같다.

```text
CLAUDE.md와 docs/design/FIGMA_HANDOFF.md의 S03-C1~C4,
docs/api/openapi.yaml의 addTripCandidate를 읽어라.
후보 저장 vertical slice만 구현하라. TripItem을 만들거나 trip version을
올리면 안 된다. 201/200 duplicate/오류 재시도, owner 격리, idempotency,
keyboard sheet, component/API/E2E test를 포함하라. 먼저 변경 계획과
계약 불일치를 보고하고, 승인 전에는 범위를 넓히지 마라.
```

## 8. Blocker 처리

- 30분 이상 같은 문제에 머무르면 사실, 시도, 필요한 결정을 짧게 공유한다.
- 외부 계정/쿼터/법무처럼 코드로 풀 수 없는 문제는 `DECISIONS_AND_RISKS.md`에 owner와 due condition을 적는다.
- blocker가 있는 slice를 우회 구현해 숨기지 말고, mock/replay/feature flag로 명시적 degradation을 만든다.
- WIP limit은 사람당 1개 main slice + 긴급 bug 1개다.

## 9. Release 방식

1. main merge마다 자동 staging deploy.
2. staging smoke/E2E/source readiness 통과.
3. release candidate tag와 변경 내역 작성.
4. production DB migration compatibility 확인.
5. backend rolling deploy → health 확인 → frontend deploy.
6. 핵심 synthetic journey와 dashboard 확인.
7. 실패하면 frontend 이전 object version 또는 ECS 이전 task definition으로 rollback. destructive DB rollback은 runbook 판단 후 수행.

production 배포자는 한 명, 관찰·rollback 판단자는 다른 한 명으로 역할을 나눈다.

공모전 release에서는 2026-09-19 code freeze, 2026-09-20 16:00 내부 제출 목표, 2026-09-21 16:00 공식 마감 순으로 운영한다. 내부 제출 후에는 제출 URL·기능설명서 정합성, 실제 KTO call, 출처, 위치 OFF, 접수 완료 증거를 제외한 범위 확대를 하지 않는다.

GitHub ruleset/OIDC/version/artifact policy는 [GITHUB_RELEASE_OPERATIONS.md](../operations/GITHUB_RELEASE_OPERATIONS.md), AWS 절차는 [AWS_DEPLOYMENT.md](../operations/AWS_DEPLOYMENT.md), SEV0–3와 security/privacy SLA는 [INCIDENT_RESPONSE.md](../operations/INCIDENT_RESPONSE.md)를 따른다. 실제 alarm contact가 없거나 상대 승인·rollback target이 확인되지 않으면 production 배포하지 않는다.
