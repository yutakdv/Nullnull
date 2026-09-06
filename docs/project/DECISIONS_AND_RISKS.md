# 결정·위험 대장

- 상태: Living document
- 갱신 규칙: 매주 시작 시 검토, 결정되면 ADR/문서/계약에 반영
- 역할 표기: FE, BE/AI, 공동

## 1. 확정된 핵심 결정

| ID | 결정 | 근거 |
| --- | --- | --- |
| A-001 | Figma는 시각 정본, OpenAPI·이벤트·제품 문서는 동작/데이터 정본으로 사용하고 충돌은 같은 FCR/PR에서 해결 | 문서 충돌을 숨기지 않음 |
| A-002 | React/TypeScript/Vite PWA + Spring Boot/Java 21 + PostgreSQL | [ADR-0001](../decisions/ADR-0001-target-stack.md) |
| A-003 | SavedPost/TripCandidate/TripItem을 분리 | 후보 저장이 일정 변경이 아님 |
| A-004 | AI/optimizer는 preview 후 명시적 승인으로만 apply | 사용자 통제/안전 |
| A-005 | provenance와 comparison eligibility를 API 필수 계약으로 사용 | [ADR-0002](../decisions/ADR-0002-data-truth-and-ai.md) |
| A-006 | 익명 owner/session + trip version + idempotency | [ADR-0003](../decisions/ADR-0003-session-consistency.md) |
| A-007 | P0 정밀 위치 서버 미수집, 붙여넣기 원문 비저장 | 개인정보 최소화 |
| A-008 | Redis/microservice/별도 ML service는 측정 trigger 전 도입하지 않음 | 2인 운영 복잡도 |
| A-009 | AWS CDK, CloudFront/S3, ECS/ALB, RDS 기본 구조 | 배포 재현성 |
| A-010 | Frontend 1명과 Backend/AI 1명이 contract packet으로 화면별 병렬 개발 | [소유권 매트릭스](../engineering/OWNERSHIP_MATRIX.md) |
| A-011 | exact tool version은 M0 compatibility test 뒤 lock하고 floating latest를 쓰지 않음 | [로컬 개발 계약](../engineering/LOCAL_DEVELOPMENT.md) |
| A-012 | `~/Desktop/Nullnull`의 `origin/main` 이력을 유지하고 목표 서비스 allowlist만 반영 | [저장소 baseline](./REPOSITORY_BASELINE.md) |
| A-013 | staging 자동, production 상대 승인·동시 배포 1개·immutable artifact 승격 | [GitHub/릴리스 운영](../operations/GITHUB_RELEASE_OPERATIONS.md) |
| A-014 | 장소 검색/Live viewport는 side-effect 없는 POST body로 보내고 원문 access log를 금지 | OpenAPI와 security/privacy 계약 |
| A-015 | P0 앱 UI는 한국어·English를 지원하고 日本語·中文은 disabled `준비 중`으로 표시 | 제품 요구사항과 `FCR-001` 목표 계약 |
| A-016 | S14 P0은 guest/login 준비 중, 여행·관심사·최적화 이력, locale, 데이터 안내, 삭제를 포함 | 제품 요구사항과 profile API 계약 |
| A-017 | Frontend는 장기 `frontend`, Backend/AI는 장기 `backend`에서 작업하고 각각 `main`에 merge-commit PR | [브랜치·Docker 통합](../engineering/BRANCH_AND_INTEGRATION.md) |
| A-018 | 모든 main PR은 `docs-contract`와 `docker-integration`; M0 marker 후 full Docker를 생략할 수 없음 | CI와 테스트 전략 |
| A-019 | 공모전 공식 마감은 2026-09-21 16:00, 내부 제출 목표 09-20 16:00, code freeze 09-19 | 공식 공지·구현 계획 |
| A-020 | 제출은 로그인 불필요·위치 OFF이며 실제 KTO OpenAPI 호출/이력/텍스트 출처가 필수 | 공모전 준수 매트릭스 |
| A-021 | 총괄 PM은 scope·문구·공모전 claim·최종 go/no-go를 승인하되 두 기술 DRI의 safety veto와 필수 review를 대신하지 않음 | [PM 감사](./PM_CONSISTENCY_AUDIT.md) |

## 2. 열린 결정

결정되지 않은 항목은 아래 “안전한 기본값”으로 개발을 계속할 수 있지만, `필요 시점` 전에는 반드시 닫는다.

| ID | 질문 | DRI | 필요 시점 | 안전한 기본값 | 완료 증거 |
| --- | --- | --- | --- | --- | --- |
| D-001 | 실제 서비스 domain은 무엇인가? | 공동 | M0 staging | placeholder, production deploy 금지 | Route53/ACM validation |
| D-002 | 지도·경로 provider는 무엇인가? | BE/AI | P1-Route | P0 route matrix 없음, 목록 UI | 가격/쿼터/약관/SDK 비교 ADR |
| D-003 | KTO/서울 API production key·쿼터·재배포 조건이 승인됐는가? | BE/AI | KTO slice/09-10 전 | mock/replay, 제출 go 금지 | 계정/쿼터/출처·실제 호출 체크 |
| D-004 | 장기 계정 로그인 provider가 필요한가? | 공동 | P1 또는 공개 출시 | 익명 session만 | 사용자 요구/계정 복구 정책 ADR |
| D-005 | production RDS Multi-AZ/ECS 2 task 비용을 승인할 수 있는가? | 공동 | M6 | staging Single-AZ; 실제 사용자 출시 전 go/no-go | AWS calculator + downtime 기준 |
| D-006 | 오류 추적 SaaS를 추가할 것인가? | FE | M6 | CloudWatch와 client-safe event만 | 개인정보/DPA/비용 검토 |
| D-007 | 피드/POI seed 이미지·문구의 사용 권리가 확인됐는가? | 공동 | M2 완료 | 직접 제작/공공누리 허용 자산만 | asset ledger와 license link |
| D-008 | P1 게시물 moderation 정책/도구는 무엇인가? | 공동 | P1-CreatePost | 작성 기능 OFF | 신고/삭제/금지 콘텐츠 정책 |
| D-009 | 개인정보 처리방침상 최종 보존 기간은? | 공동 | M6 | 문서의 짧은 기술 기본값 | 공개 정책/삭제 test |
| D-010 | 두 팀원의 GitHub handle과 CODEOWNERS 경로는? | 공동 | M0 | CODEOWNERS 생성 보류 | branch protection reviewer 동작 |
| D-011 | Figma variable/token과 icon export 방식은? | FE | FE-002 | 수동 수치 복제 금지 | token pipeline + visual diff |
| D-014 | 사용자 삭제 시 최적화 감사 record를 얼마나 보존할 수 있는가? | BE/AI | M5 | trip 삭제와 함께 제거 | 개인정보/운영 합의 |
| D-015 | 정확한 congestion source별 stale threshold는? | BE/AI | M4 | source registry에서 미확정 source 비활성 | 공식 갱신 주기+probe 측정 |
| D-016 | repository와 서비스 코드의 license는 무엇인가? | 공동 | 외부 기여/공개 배포 전 | 명시 license 없음, 재사용 허용을 가정하지 않음 | LICENSE 파일과 의존성 호환 검토 |
| D-017 | staging/production AWS account를 분리할 수 있는가? | BE/AI | M0 staging/M6 | 별도 account 권장; 불가 시 role/VPC/KMS/secret/stack 완전 분리 | account/stack manifest 또는 예외 ADR |
| D-018 | staging 월 비용 상한과 운영 시간은? | 공동 | INF-001 전 | 무제한 상시 운영 금지, replay/local 우선 | 승인 금액·Budget 50/80/100% 수신 test |
| D-019 | alarm/incident 실제 수신자·부재 escalation은? | 공동 | staging/M6 | role key만 문서화, contact 없으면 production 금지 | 두 사람 test alarm/tabletop |
| D-020 | release/artifact/log의 최종 보존 기간은? | BE/AI | M0 CI/M6 | 운영 문서의 초기 보존값, active/rollback 보호 | lifecycle dry-run과 release manifest |
| D-021 | exact Node/npm/Java patch, Gradle/Spring/PostgreSQL/generator version은? | 공동 | M0 종료 | Node LTS/npm, Java 21, wrapper; floating 금지 | lock 파일+local/CI/container version test |
| D-022 | GitHub ruleset/required check/environment 설정은? | 공동 | 첫 code PR | direct push 금지, 문서의 stable check 이름 사용 | settings export/screenshot+review test |
| D-023 | production security/privacy external escalation 책임자는? | 공동 | M6 | contact 없으면 production 금지 | 보호된 contact registry와 tabletop |
| D-025 | 삭제 receipt와 tombstone/backup 재적용 보존은? | BE/AI | M1/M6 | revoke 즉시, 완료 전 완료 표시 금지 | 정책·ERD·job/recovery test |
| D-026 | P1 알림 type/deep-link/read-all/보존 정책은? | 공동 | P1-Notifications | capability OFF, 내부 allowlist만 | OpenAPI/ERD/security/E2E |
| D-027 | 최종 지정과제·팀명·서비스명이 제출처와 일치하는가? | 공동 | 09-18 PDF 동결 전 | 제출 금지 | 콘텐츠랩 화면·PDF·서비스 대조 |
| D-028 | KTO 데이터를 장기/전체 로컬 저장할 필요가 있는가? | BE/AI | persistence 구현 전 | 최소 TTL/read-through만, 전체 mirror 금지 | 공식 문의 답변·별도 신청 승인 |
| D-029 | 공식 기능설명서 최신 양식/필수 field가 그대로 유지됐는가? | 공동 | 09-18/제출 직전 | 양식 변경·제출 금지 | 원본 checksum·PDF render·2인 대조 |
| D-030 | Figma `FCR-001~015`가 실제 디자인 파일에 반영됐는가? | FE, PM 승인 | 영향 slice 착수 전/09-06 | 기존 충돌 화면 구현 금지 | 수정 node URL·전후 screenshot·계약 검토 |

## 3. 위험 대장

확률/영향: L(낮음), M(중간), H(높음). `잔여`는 대응 후에도 남는 위험이다.

| ID | 위험 | 확률 | 영향 | 예방/완화 | Trigger/대응 | DRI | 잔여 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| R-001 | Figma가 계약 작성 후 크게 변경 | M | H | node traceability, contract-first, 주간 freeze | P0 frame 변경 시 영향 PR/재추정 | FE | M |
| R-002 | 과거 prototype이 목표 저장소에 다시 유입 | M | M | current-only manifest, path review | unexpected legacy path/workflow면 PR 차단 | 공동 | L |
| R-003 | 외부 API quota/승인 지연 | H | H | M4 전 계정 확인, cache/replay | 80% alert 또는 승인 지연 시 live flag OFF | BE/AI | M |
| R-004 | 외부 schema/enum 변화 | M | H | validator, quarantine, last-known-good | drift 감지 시 degraded + fixture 갱신 | BE/AI | L |
| R-005 | 서로 다른 혼잡 source를 잘못 비교 | M | H | comparison policy/server field/property test | eligible 없는 delta 발견 시 즉시 기능 차단 | BE/AI | L |
| R-006 | AI/optimizer가 lock을 위반 | M | H | deterministic validation, immutable preview | invariant failure면 apply endpoint kill switch | BE/AI | L |
| R-007 | stale preview가 최신 일정 덮어씀 | M | H | ETag/If-Match/fingerprint | conflict rate 급증 시 재계산 UX 개선 | 공동 | L |
| R-008 | 모바일 네트워크 재시도로 중복 생성/apply | H | H | idempotency + UI submitting state | key conflict/replay 지표 분석 | 공동 | L |
| R-009 | 익명 session 탈취/CSRF | M | H | secure cookie, CSRF, origin, WAF | anomaly/revocation/rotation | BE/AI | M |
| R-010 | 원문 일정/정밀 위치가 log/analytics에 유출 | M | H | 비수집 설계, schema allowlist, deny scan | 발견 즉시 secret/privacy incident 절차 | 공동 | L |
| R-011 | 2인 팀에서 review가 병목 | H | M | 작은 vertical slice, contract 먼저, WIP 1 | PR >2일이면 pairing/범위 축소 | 공동 | M |
| R-012 | 새 stack 전환으로 일정 초과 | M | H | M0 hello slice, P0 비범위 엄수 | milestone 20% 초과 시 P1/장식 축소 | 공동 | M |
| R-013 | 지도 SDK/bundle이 mobile 성능 저하 | M | M | lazy load, list fallback, budget | route bundle/LCP 초과 시 static/list 우선 | FE | L |
| R-014 | RDS/ALB/NAT 고정비가 예산 초과 | M | H | budget alarm, environment sizing | 50/80% budget review, staging schedule | BE/AI | M |
| R-015 | migration이 production data/rollback을 막음 | L | H | expand-contract, snapshot/rehearsal | 실패 시 deploy 중지, restore/forward fix | BE/AI | L |
| R-016 | 후보/POI/post의 저작권·라이선스 문제 | M | H | asset/source ledger, 공식 terms 검토 | 불명확 자산 즉시 비노출/대체 | 공동 | M |
| R-017 | 접근성 작업이 마지막에 몰림 | H | M | component acceptance/PR gate | axe/keyboard regression 시 merge 차단 | FE | L |
| R-018 | 데모 당일 live source 장애 | M | H | readiness, replay fixture, 상태 label | 자동 replay/degraded + 명시 banner | BE/AI | L |
| R-019 | FE/BE가 다른 contract/mock revision으로 병렬 구현 | M | H | contract packet SHA, generated fixture | integration-ready에서 mismatch면 중단 | 공동 | L |
| R-020 | staging 작업공간 전체가 Desktop 원격 이력에 섞임 | M | H | allowlist manifest, exact target sync | manifest 밖 파일이면 PR 차단 | 공동 | L |
| R-021 | GitHub required check/CODEOWNERS가 설정되지 않아 단독 merge | M | H | ruleset checklist/review test | 보호 설정 drift면 production deploy 중단 | 공동 | L |
| R-022 | OIDC trust/ref wildcard로 production 권한 확대 | L | H | environment subject 최소 trust/CloudTrail | trust diff는 두 사람 승인·즉시 revoke | BE/AI | L |
| R-023 | 동시에 migration/deploy해 schema 또는 artifact 불일치 | M | H | concurrency 1, DB lock, immutable manifest | 진행 중 deploy 취소 금지/rollback | BE/AI | L |
| R-024 | alarm 수신자가 없거나 한 명 부재 | M | H | primary/secondary test alarm/tabletop | contact TBD면 launch 차단 | 공동 | M |
| R-025 | 삭제 job 실패/backup restore로 삭제 data 재노출 | M | H | receipt state/tombstone/reapply test | privacy incident + job repair | BE/AI | L |
| R-026 | P1 frame이 dead CTA로 먼저 노출 | M | M | capability OFF variant/E2E | flag mismatch면 화면 숨김/안내 | FE | L |
| R-027 | tool version drift로 두 기기/CI 생성물이 다름 | H | M | exact lock/wrapper/container digest | generated diff면 M0 gate 실패 | 공동 | L |
| R-028 | 검색어가 CDN/ALB/APM access log에 장기 보존 | M | M | body 기반 요청 또는 검증된 log 정책, 길이 제한 | log sample에서 발견 시 route/retention 수정 | BE/AI | L |
| R-029 | 09-21 16:00 마감 또는 공식 양식 누락으로 심사 제외 | M | H | 09-20 내부 제출, runbook, PDF/접수 증거 | 09-18 미완료면 범위 동결·완결 기능만 제출 | 공동 | L |
| R-030 | 실제 KTO 호출 이력 없이 file/replay/mirror만 제출 | M | H | 09-10 조기 연동, call-audit, staging actual-call gate | 증거 없으면 제출 go 금지·공식 문의 | BE/AI | L |
| R-031 | 출처 누락·TourAPI 단독·무허가 CI/BI 사용 | M | H | 중앙 attribution, DOM/asset audit | 신규 화면 coverage 실패 시 merge 차단 | FE | L |
| R-032 | 위치 capability가 제출 profile에서 활성화되어 신고/심사 위험 | L | H | profile startup invariant, permission/network E2E | 즉시 OFF/rollback, 위치정보 사전 검토 | 공동 | L |
| R-033 | 16일 안에 과도한 P0/P1 범위로 핵심 flow 불완전 | H | H | 09-16 gate, P1 OFF, 정의된 scope-cut 순서 | 새 기능 중지, 실제 완결 flow만 PDF 기재 | 공동 | M |
| R-034 | Figma의 following/search/bell/follow/filter가 P0 active 기능으로 오인됨 | H | H | FCR-002/003, capability OFF, dead-control E2E | 노출 발견 시 숨김·제출 screenshot/PDF 재촬영 | FE | L |
| R-035 | P0 ITEM 최적화 READY preview가 없어 승인 전 변경 비교를 구현하지 못함 | H | H | FCR-004, APPLY/KEEP decision bar, Storybook/E2E | node/test 없으면 optimizer claim·기능 OFF | 공동 | M |
| R-036 | 언어·guest·data guide 문구가 실제 capability/source state와 충돌 | H | H | FCR-001/006/007, KO/EN·6-state fixture | mismatch면 해당 화면 출시 차단 | FE | L |
| R-037 | Live 검색·거리값이 coverage/기준점 없이 사실처럼 보임 | M | H | canonical lookup, UNAVAILABLE, distance provenance | 근거 없으면 값·ranking 숨김 | 공동 | L |
| R-038 | 문서 완성도를 실제 서비스 구현·배포 완료로 오인 | H | H | PM gate, 저장소 artifact 검사, evidence ledger | 앱/실제 KTO/외부망 증거 없으면 NO-GO | 공동 | L |

## 4. P0 Blocker

다음 항목이 열려 있으면 production 사용자 공개 또는 공모전 최종 시연을 go로 판단하지 않는다.

- D-001 실제 domain/TLS
- D-003 외부 API key·쿼터·이용 조건
- D-005 production availability/예산
- D-007 콘텐츠 사용 권리
- D-009 개인정보 보존·삭제 문구
- D-015 source freshness threshold
- D-016 공개/재사용 license
- D-017 production account 격리 방식
- D-018 staging 비용 상한과 production budget
- D-019/D-023 실제 incident 수신·escalation contact
- D-022 실제 CODEOWNERS/ruleset/required checks
- D-025 삭제·backup 재적용 정책
- D-027 최종 지정과제·팀/서비스명 일치
- D-029 공식 기능설명서 양식/PDF 정합성
- D-030 Figma P0 blocker와 관련 node 증거
- R-005/R-006/R-007/R-010의 자동 safety test
- R-034/R-035/R-036/R-038의 디자인·구현 evidence gate
- R-021/R-022/R-023/R-024/R-025의 운영·보안 gate
- AWS launch checklist 전체
- 실제 KTO call/provider 이력/redacted audit/화면 출처와 익명 외부망 judge journey
- 공모전 profile의 위치/geolocation OFF와 제출 접수 증거

지도/경로 provider(D-002), 계정 로그인(D-004), 게시물 작성 moderation(D-008)은 P0 blocker가 아니며 기능을 OFF로 유지한다.

## 5. 결정 기록 방법

작은 결정은 이 표의 완료 증거와 관련 문서 diff로 닫는다. 다음 중 하나면 `docs/decisions/ADR-NNNN-*.md`를 추가한다.

- 장기간 되돌리기 어렵다.
- 여러 module/팀 역할/운영비에 영향을 준다.
- 보안·개인정보·데이터 진실성 경계를 바꾼다.
- 대안 사이 trade-off를 나중에 다시 이해해야 한다.

ADR은 Context, Decision, Consequences, Rejected alternatives, Review trigger를 포함한다.

### ADR review trigger 운영

- 모든 ADR은 최소 한 개의 측정 가능한 review trigger와 검토 DRI를 가진다.
- trigger가 발생한 incident, 비용 임계치, provider 계약 변경, Figma scope 변경, scale/SLO 결과를 ADR에 연결한다.
- Accepted는 영구 불변이 아니다. `Proposed → Accepted → Superseded/Deprecated` 상태와 대체 ADR을 기록한다.
- 월말/각 milestone 종료에 열린 trigger를 확인하고, production release 전 모든 security/privacy/data/infra ADR을 재검토한다.
- 코드가 ADR과 다르면 코드를 조용히 정본으로 만들지 않고 ADR 또는 구현을 같은 change set에서 수정한다.
