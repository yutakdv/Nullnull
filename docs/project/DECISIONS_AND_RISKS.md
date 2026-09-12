---
aliases:
  - "결정·위험 대장"
doc_type: reference
status: baseline
area: project
tags:
  - nullnull/reference
  - nullnull/project
---

# 결정·위험 대장

- 상태: Living document
- 갱신 규칙: 작업 선택·계약 변경·gate 종료 시 검토, 결정되면 ADR/문서/계약에 반영
- 역할 표기: FE, BE/AI, 공동

## 1. 확정된 핵심 결정

| ID | 결정 | 근거 |
| --- | --- | --- |
| A-001 | Figma는 시각 정본, OpenAPI·이벤트·제품 문서는 동작/데이터 정본으로 사용하고 충돌은 같은 FCR/PR에서 해결 | 문서 충돌을 숨기지 않음 |
| A-002 | React/TypeScript/Vite PWA + Spring Boot/Java 21 + PostgreSQL | [ADR-0001](../decisions/ARCHITECTURE_DECISIONS.md#adr-0001) |
| A-003 | SavedPost/TripCandidate/TripItem을 분리 | 후보 저장이 일정 변경이 아님 |
| A-004 | AI/optimizer는 preview 후 명시적 승인으로만 apply | 사용자 통제/안전 |
| A-005 | provenance와 comparison eligibility를 API 필수 계약으로 사용 | [ADR-0002](../decisions/ARCHITECTURE_DECISIONS.md#adr-0002) |
| A-006 | 익명 owner/session + trip version + idempotency | [ADR-0003](../decisions/ARCHITECTURE_DECISIONS.md#adr-0003) |
| A-007 | P0 정밀 위치 서버 미수집, 붙여넣기 원문 비저장 | 개인정보 최소화 |
| A-008 | Redis/Kafka/분리 worker는 측정 trigger 전 도입하지 않음. 추천 계산 서비스 `apps/ai`만 A-022로 예외 | 2인 운영 복잡도 |
| A-009 | AWS CDK, CloudFront/S3, ECS/ALB, RDS 기본 구조 | 배포 재현성 |
| A-010 | Frontend 1명과 Backend/AI 1명이 contract packet으로 화면별 병렬 개발 | [소유권 매트릭스](../engineering/OWNERSHIP_MATRIX.md) |
| A-011 | exact tool version은 B01 compatibility test 뒤 lock하고 floating latest를 쓰지 않음 | [로컬 개발 계약](../engineering/LOCAL_DEVELOPMENT.md) |
| A-012 | `~/Desktop/Nullnull`의 `origin/main` 이력을 유지하고 목표 서비스 allowlist만 반영 | [저장소 baseline](DECISIONS_AND_RISKS.md) |
| A-013 | staging 자동, production 상대 승인·동시 배포 1개·immutable artifact 승격 | [GitHub/릴리스 운영](../operations/GITHUB_RELEASE_OPERATIONS.md) |
| A-014 | 장소 검색/Live viewport는 side-effect 없는 POST body로 보내고 원문 access log를 금지 | OpenAPI와 security/privacy 계약 |
| A-015 | P0 앱 UI는 한국어·English를 지원하고 日本語·中文은 disabled `준비 중`으로 표시 | 제품 요구사항과 `FCR-001` 목표 계약 |
| A-016 | S14 P0은 guest/login 준비 중, 여행·관심사·최적화 이력, locale, 데이터 안내, 삭제를 포함 | 제품 요구사항과 profile API 계약 |
| A-017 | Frontend는 장기 `frontend`, Backend/AI는 장기 `backend`에서 작업하고 각각 `main`에 merge-commit PR | [브랜치·Docker 통합](../engineering/BRANCH_AND_INTEGRATION.md) |
| A-018 | 모든 main PR은 `docs-contract`와 `docker-integration`; B01 marker 후 full Docker를 생략할 수 없음 | CI와 테스트 전략 |
| A-019 | 개발 일정은 날짜 없이 우선순위·의존성 B00~B10으로 관리하고 Live를 마지막에 구현한다. 공식 제출 마감은 공모전 공지 정본을 따른다 | [실행 순서](../engineering/IMPLEMENTATION_PLAN.md) |
| A-020 | 제출은 로그인 불필요·위치 OFF이며 실제 KTO OpenAPI 호출/이력/텍스트 출처가 필수 | 공모전 준수 매트릭스 |
| A-021 | 총괄 PM은 scope·문구·공모전 claim·최종 go/no-go를 승인하되 두 기술 DRI의 safety veto와 필수 review를 대신하지 않음 | [현재 상태와 검수 gate](DECISIONS_AND_RISKS.md) |
| A-022 | 추천 계산 전체(feed 순서·관련 장소·slot·ITEM·설명 template)는 Python 서비스 `apps/ai`가 담당하고 Spring은 hydration·gateway·재검증·저장을 담당. 공개 OpenAPI는 변경 없음 | [ADR-0006](../decisions/ARCHITECTURE_DECISIONS.md#adr-0006), 2026-09-07 결정 |
| A-023 | D-015 stale threshold는 KTO forecast `PT24H`, KTO place detail 및 내부 catalog rule `P7D`로 고정한다. threshold가 없는 source는 collection하지 않는다 | 2026-09-07 팀 결정; C1 source registry v1 |
| A-026 | `main` branch protection이 문서의 규칙과 일치함을 API로 확인했다 — required check는 `docs-contract`·`docker-integration` 둘뿐, strict(최신 base 요구) on, PR 필수이나 승인 0건 요구, admin 포함 direct push 금지, force push·삭제 금지, linear history off(merge commit 허용) | 2026-09-13 `repos/.../branches/main/protection` 실측. D-022를 닫는다. 승인 0건 + PR 필수가 *"auto-merge하되 상대 승인을 대기 조건으로 두지 않는다"*, linear history off가 *"merge commit을 사용한다"* 에 각각 대응한다 |
| A-025 | inbound rate limiting은 edge에만 두고 application은 429를 발행하지 않는다. P0 제출 범위에 포함하지 않는다 | 2026-09-13 결정, #148과 D-033. 익명 전용 P0에서 owner 축 제한은 cookie를 버리면 우회되고, IP 축은 심사 환경의 공유 NAT에서 오탐이 크다. 심사위원을 막는 것이 데모의 최악 실패다 |
| A-024 | post 표지는 팀이 직접 만든 1st-party 자산만 쓰고 provider 사진을 재배포하지 않는다. `MediaAsset`은 `attributionRequired=false`·`redistributionAllowed=true`로 채우며, 실제 장소를 사진처럼 묘사하지 않는 명시적 일러스트로 제한한다 | 2026-09-13 오너 결정, D-007의 post 절반. provider 사진은 record별 공공누리 유형 심사가 필요하고 `PostSummary`에 credit 경로가 없어 계약 breaking이 된다. 실사풍 합성은 불변식 6의 합성·관측 구분을 깬다 |

## 2. 열린 결정

결정되지 않은 항목은 아래 “안전한 기본값”으로 개발을 계속할 수 있지만, `필요 시점` 전에는 반드시 닫는다.

| ID | 질문 | DRI | 필요 시점 | 안전한 기본값 | 완료 증거 |
| --- | --- | --- | --- | --- | --- |
| D-001 | 실제 서비스 domain은 무엇인가? | 공동 | B01 staging | placeholder, production deploy 금지 | Route53/ACM validation |
| D-002 | 지도·경로 provider는 무엇인가? | BE/AI | P1-Route | P0 route matrix 없음, 목록 UI | 가격/쿼터/약관/SDK 비교 ADR |
| D-003 | 개발 계정 쿼터의 단위(인증키별인가 활용신청별인가)와 KTO 이미지 재배포 조건은 무엇인가? production key는 **신청하지 않기로 확정**(2026-09-13 오너, PM-023)했고 제출은 개발 계정으로 간다 | BE/AI | 법정동코드 등 새 operation을 같은 키에 추가하기 전 | 쿼터 guard를 미리 조이지 않는다 — per-API가 맞을 때 용량 절반을 버린다. 이미지는 재배포하지 않고 post 표지는 1st-party만 쓴다(A-024) | 포털 마이페이지 활용신청 상세가 API별 트래픽을 따로 보이는지 확인. 실호출 증거는 확보됨(KTO smoke, `api_ingest_logs`·`collector_runs` COMPLETED) |
| D-004 | 장기 계정 로그인 provider가 필요한가? | 공동 | P1 또는 공개 출시 | 익명 session만 | 사용자 요구/계정 복구 정책 ADR |
| D-005 | production RDS Multi-AZ/ECS 2 task 비용을 승인할 수 있는가? | 공동 | B08/최종 검수 | staging Single-AZ; 실제 사용자 출시 전 go/no-go | AWS calculator + downtime 기준 |
| D-006 | 오류 추적 SaaS를 추가할 것인가? | FE | B08/최종 검수 | CloudWatch와 client-safe event만 | 개인정보/DPA/비용 검토 |
| D-007 | POI/place 이미지·문구의 사용 권리가 확인됐는가? post 표지 절반은 A-024로 닫혔고 provider 사진 쪽만 남았다 | 공동 | B04 완료 | 직접 제작/공공누리 허용 자산만 | asset ledger와 license link |
| D-008 | P1 게시물 moderation 정책/도구는 무엇인가? | 공동 | P1-CreatePost | 작성 기능 OFF | 신고/삭제/금지 콘텐츠 정책 |
| D-009 | 개인정보 처리방침상 최종 보존 기간은? | 공동 | B08/최종 검수 | 문서의 짧은 기술 기본값 | 공개 정책/삭제 test |
| D-010 | 두 팀원의 GitHub handle과 CODEOWNERS 경로는? | 공동 | B01 | CODEOWNERS 생성 보류 | branch protection reviewer 동작 |
| D-011 | Figma variable/token과 icon export 방식은? | FE | FE-002 | 수동 수치 복제 금지 | token pipeline + visual diff |
| D-014 | 사용자 삭제 시 최적화 감사 record를 얼마나 보존할 수 있는가? | BE/AI | B06 | trip 삭제와 함께 제거 | 개인정보/운영 합의 |
| D-016 | repository와 서비스 코드의 license는 무엇인가? | 공동 | 외부 기여/공개 배포 전 | 명시 license 없음, 재사용 허용을 가정하지 않음 | LICENSE 파일과 의존성 호환 검토 |
| D-017 | staging/production AWS account를 분리할 수 있는가? | BE/AI | B01 staging/B08/최종 검수 | 별도 account 권장; 불가 시 role/VPC/KMS/secret/stack 완전 분리 | account/stack manifest 또는 예외 ADR |
| D-018 | staging 월 비용 상한과 운영 시간은? | 공동 | INF-001 전 | 무제한 상시 운영 금지, replay/local 우선 | 승인 금액·Budget 50/80/100% 수신 test |
| D-019 | alarm/incident 실제 수신자·부재 escalation은? | 공동 | staging/B08/최종 검수 | role key만 문서화, contact 없으면 production 금지 | 두 사람 test alarm/tabletop |
| D-020 | release/artifact/log의 최종 보존 기간은? | BE/AI | B01 CI/B08/최종 검수 | 운영 문서의 초기 보존값, active/rollback 보호 | lifecycle dry-run과 release manifest |
| D-021 | exact Node/npm/Java patch, Gradle/Spring/PostgreSQL/generator version은? | 공동 | B01 종료 | Node LTS/npm, Java 21, wrapper; floating 금지 | lock 파일+local/CI/container version test |
| D-023 | production security/privacy external escalation 책임자는? | 공동 | B08/최종 검수 | contact 없으면 production 금지 | 보호된 contact registry와 tabletop |
| D-025 | 삭제 receipt와 tombstone/backup 재적용 보존은? | BE/AI | B02 삭제 구현 / B08 복원 검수 | revoke 즉시, 완료 전 완료 표시 금지 | 정책·ERD·job/recovery test |
| D-026 | P1 알림 type/deep-link/read-all/보존 정책은? | 공동 | P1-Notifications | capability OFF, 내부 allowlist만 | OpenAPI/ERD/security/E2E |
| D-027 | 최종 지정과제·팀명·서비스명이 제출처와 일치하는가? | 공동 | 기능설명서 동결 전 | 제출 금지 | 콘텐츠랩 화면·PDF·서비스 대조 |
| D-028 | KTO 데이터를 장기/전체 로컬 저장할 필요가 있는가? persistence는 **안전한 기본값 안에서 이미 구현**됐다(V007~V012, TTL read-through, A-023의 `PT24H`/`P7D`). 전체 mirror가 필요해지면 이 결정을 다시 연다 | BE/AI | 전체 mirror가 필요해지는 시점 | 최소 TTL/read-through만, 전체 mirror 금지 | 공식 문의 답변·별도 신청 승인 |
| D-029 | 공식 기능설명서 최신 양식/필수 field가 그대로 유지됐는가? | 공동 | 기능설명서 동결·제출 직전 | 양식 변경·제출 금지 | 원본 checksum·PDF render·2인 대조 |
| D-030 | Figma `FCR-001~015`가 실제 디자인 파일에 반영됐는가? | FE, PM 승인 | 영향 slice 착수 전 | 기존 충돌 화면 구현 금지 | 수정 node URL·전후 screenshot·계약 검토 |
| D-031 | `apps/ai` ECS 배포 경로(ECR·service·내부 DNS·SG·`NULLNULL_AI_BASE_URL`)를 제출 빌드 전에 만들 것인가? | BE/AI | B08/제출 빌드 전 | 미배포 시 feed는 Spring 고정 순서, related/slot은 `UNKNOWN`, ITEM run은 `FAILED`(fallback-only); ITEM 최적화 제출 제외는 별도 범위 결정 | staging `getReadiness`의 recommendation `READY`, release manifest `aiImageDigest` |
| D-032 | Spring→`apps/ai` 내부 호출 인증(token/mTLS)이 필요한가? | BE/AI | staging 배포 전 | internal network·security group 격리만, 공개 노출 금지 | SG/compose `internal: true` 검증과 인증 ADR 또는 예외 기록 |
| D-033 | edge rate limiting의 축·한도·`Retry-After` 산출식은 무엇인가? 계층은 A-025로 edge 확정이고 application은 429를 발행하지 않는다 | BE/AI | edge를 켜기 전 | application은 429를 발행하지 않는다. 계약의 `RATE_LIMITED`는 edge가 돌려줄 수 있는 응답을 client가 처리할 수 있게 선언해 둔 것이고, `apps/api`에는 producer가 없다(#148) | edge 429가 계약 `Problem`임을 실제 응답으로 확인한다 — `application/problem+json` 콘텐츠 타입, client가 이미 아는 `code`, `requestId`가 모두 필요하다. 하나라도 빠지면 FE `toProblem`이 null을 반환해 재시도 정책이 조용히 적용되지 않는다(`problem.test.ts`로 확인) |

## 3. 위험 대장

확률/영향: L(낮음), M(중간), H(높음). `잔여`는 대응 후에도 남는 위험이다.

| ID | 위험 | 확률 | 영향 | 예방/완화 | Trigger/대응 | DRI | 잔여 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| R-001 | Figma가 계약 작성 후 크게 변경 | M | H | node traceability, contract-first, slice별 계약 동결 | P0 frame 변경 시 영향 PR/재추정 | FE | M |
| R-002 | 과거 prototype이 목표 저장소에 다시 유입 | M | M | current-only manifest, path review | unexpected legacy path/workflow면 PR 차단 | 공동 | L |
| R-003 | 외부 API quota/승인 지연 | H | H | B03 KTO·B10 서울 착수 전 계정 확인, cache/replay | 80% alert 또는 승인 지연 시 live flag OFF | BE/AI | M |
| R-004 | 외부 schema/enum 변화 | M | H | validator, quarantine, last-known-good | drift 감지 시 degraded + fixture 갱신 | BE/AI | L |
| R-005 | 서로 다른 혼잡 source를 잘못 비교 | M | H | comparison policy/server field/property test | eligible 없는 delta 발견 시 즉시 기능 차단 | BE/AI | L |
| R-006 | AI/optimizer가 lock을 위반 | M | H | deterministic validation, immutable preview | invariant failure면 apply endpoint kill switch | BE/AI | L |
| R-007 | stale preview가 최신 일정 덮어씀 | M | H | ETag/If-Match/fingerprint | conflict rate 급증 시 재계산 UX 개선 | 공동 | L |
| R-008 | 모바일 네트워크 재시도로 중복 생성/apply | H | H | idempotency + UI submitting state | key conflict/replay 지표 분석 | 공동 | L |
| R-009 | 익명 session 탈취/CSRF | M | H | secure cookie, CSRF, origin, WAF | anomaly/revocation/rotation | BE/AI | M |
| R-010 | 원문 일정/정밀 위치가 log/analytics에 유출 | M | H | 비수집 설계, schema allowlist, deny scan | 발견 즉시 secret/privacy incident 절차 | 공동 | L |
| R-011 | 2인 팀에서 review가 병목 | H | M | 작은 vertical slice, contract 먼저, WIP 1 | review가 다음 작업의 선행 조건을 막으면 pairing/범위 축소 | 공동 | M |
| R-012 | 새 stack 전환으로 일정 초과 | M | H | B01 hello slice, P0 비범위 엄수 | 단계 완료 gate가 지연되면 P1/장식 축소 | 공동 | M |
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
| R-027 | tool version drift로 두 기기/CI 생성물이 다름 | H | M | exact lock/wrapper/container digest | generated diff면 B01 gate 실패 | 공동 | L |
| R-028 | 검색어가 CDN/ALB/APM access log에 장기 보존 | M | M | body 기반 요청 또는 검증된 log 정책, 길이 제한 | log sample에서 발견 시 route/retention 수정 | BE/AI | L |
| R-029 | 09-21 16:00 마감 또는 공식 양식 누락으로 심사 제외 | M | H | 검수·동결 후 제출, runbook, PDF/접수 증거 | 최종 gate 미통과면 범위 동결·완결 기능만 제출 | 공동 | L |
| R-030 | 실제 KTO 호출 이력 없이 file/replay/mirror만 제출 | M | H | B03 실제 연동, call-audit, staging actual-call gate | 증거 없으면 제출 go 금지·공식 문의 | BE/AI | L |
| R-031 | 출처 누락·TourAPI 단독·무허가 CI/BI 사용 | M | H | 중앙 attribution, DOM/asset audit | 신규 화면 coverage 실패 시 merge 차단 | FE | L |
| R-032 | 위치 capability가 제출 profile에서 활성화되어 신고/심사 위험 | L | H | profile startup invariant, permission/network E2E | 즉시 OFF/rollback, 위치정보 사전 검토 | 공동 | L |
| R-033 | 선행 gate 없이 과도한 P0/P1 범위로 핵심 flow 불완전 | H | H | 핵심 흐름 gate, P1 OFF, 정의된 scope-cut 순서 | 새 기능 중지, 실제 완결 flow만 PDF 기재 | 공동 | M |
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

작은 결정은 이 표의 완료 증거와 관련 문서 diff로 닫는다. 다음 중 하나면 [아키텍처 결정 기록](../decisions/ARCHITECTURE_DECISIONS.md)에 새 ADR-NNNN 절을 추가한다.

- 장기간 되돌리기 어렵다.
- 여러 module/팀 역할/운영비에 영향을 준다.
- 보안·개인정보·데이터 진실성 경계를 바꾼다.
- 대안 사이 trade-off를 나중에 다시 이해해야 한다.

ADR은 Context, Decision, Consequences, Rejected alternatives, Review trigger를 포함한다.

### ADR review trigger 운영

- 모든 ADR은 최소 한 개의 측정 가능한 review trigger와 검토 DRI를 가진다.
- trigger가 발생한 incident, 비용 임계치, provider 계약 변경, Figma scope 변경, scale/SLO 결과를 ADR에 연결한다.
- Accepted는 영구 불변이 아니다. `Proposed → Accepted → Superseded/Deprecated` 상태와 대체 ADR을 기록한다.
- 각 단계 종료에 열린 trigger를 확인하고, production release 전 모든 security/privacy/data/infra ADR을 재검토한다.
- 코드가 ADR과 다르면 코드를 조용히 정본으로 만들지 않고 ADR 또는 구현을 같은 change set에서 수정한다.

## 현재 저장소와 완료 판정

확인한 작업 대상은 `~/Desktop/Nullnull`, 역할 브랜치는 `backend`다. Backend와 AI를 같은 브랜치에서 작업한다. 09-07 문서 개편과 scaffold는 `backend`에 commit했고 push·PR·배포는 별도 지시로만 수행한다. 이후 기능 작업의 Git 정책은 브랜치 운영 문서를 따른다.

09-07 검토 종료 중 `apps/api` scaffold·migration, 추천 계산 서비스 `apps/ai`([ADR-0006](../decisions/ARCHITECTURE_DECISIONS.md#adr-0006))와 local Compose가 추가된 것을 확인했다. 이 PM 검토가 그 코드의 테스트 통과를 대신하지 않는다. `apps/web`, 생성 client, marker와 전체 통합 증거가 없어 **공모전 출시·제출은 NO-GO**다. 현재 wrapper는 부분 scaffold를 hard fail한다. 이 감사에서 확인한 **실행 가능한 제품 gate 0개 완료**이며 개별 계산 suite와 제품 통합 완료를 구분한다. 초기 snapshot의 baseline-only 성공과 현재 상태, 개별 계산 suite와 실제 제품 gate를 구분한다.

기존 PM 감사에서 확인한 title 선택값·빈 관심사·timezone 계약은 유지한다. Figma 52개 frame·49개 component와 FCR-001~009는 과거 감사의 확인 범위다. 새 FCR-010/011/015는 API 0.2.0 정합성 추적을 보완한 기록이고 디자인 반영 여부를 확인한 것은 아니다. 특히 FCR-004 ITEM READY preview의 실제 node 증거는 계속 열려 있다.

| 판정 영역 | 현재 증거 | 다음 완료 조건 |
| --- | --- | --- |
| 문서·계약 | OpenAPI 50 operations, 기능/화면/ERD 기준선 | 계약·링크·계획 자동 검사 |
| 실행 코드 | API scaffold·`apps/ai` 추천 서비스 scaffold 추가, 전체 통합 미완료 | B01 web/marker/full Docker, client 재생성, `apps/ai` 배포 경로(D-031) |
| 데이터·추천 | 설계·합성 예시 | B03 실제 KTO 증거, B06 안전 suite |
| 디자인 | FCR Open | 영향 화면 node·계약·FE 검토 |
| 운영 | runbook과 IaC 설계 | B08 실제 alarm·restore·외부망 증거 |
| Live | 마지막 구현 대상으로 계획 | B10 완료 후 전체 P0 재검증 |

G0 계약/Figma → G1 실행 뼈대 → G2 탐색/후보 → G3 일정 무결성 → G4 승인형 최적화 → G5 제출 후보의 의미를 유지한다. 구체 순서는 [구현 계획](../engineering/IMPLEMENTATION_PLAN.md)이 정본이며 G5는 날짜가 아니라 실제 증거로 닫는다. Live 이전 핵심 흐름 검증은 중간 gate이며 전체 P0 완료 선언이 아니다.

## 저장소 보존 경계

`https://github.com/yutakdv/Nullnull.git`의 Git 이력과 사용자가 변경한 파일을 보존한다. 과거 prototype이 있던 별도 Documents 작업공간 전체를 이 저장소에 복사하지 않는다. 현재 문서·계약·검증 설정과 검토된 hero asset만 기준선이며 새 앱은 B01의 `.nullnull-target-stack`과 실제 실행물로 추가한다.

금지할 재유입: 과거 `app/`, FastAPI/SQLite runtime, `nullnull-travel-webapp/`, 과거 root Docker/requirements/weights, modernization/superpowers/backtest 자료, daily-batch workflow, v6 기획안, 실제 사용자/provider dump, `.env`·key·token·cache·build output. 필요 지식만 읽기 전용으로 검토해 새 계약과 test로 옮긴다.

GitHub ruleset/CODEOWNERS, remote branch 보호, OIDC/secret, 실제 AWS 배포는 로컬 문서에서 설정 완료로 단정하지 않는다. `backend...origin/backend`는 확인한 로컬 tracking 상태이며 원격 최신 상태나 보호 설정 증거가 아니다.

## 문서 전수 검토와 개편 기록

검토 범위는 기존 Markdown 전체와 연결된 OpenAPI/event schema·example·CI·validator·Compose다. 본문·표·계약·링크의 중복과 의존성을 대조했다. 외부 공식 사이트 전체의 최신 내용이나 Figma 변경을 새로 실사한 감사는 아니며, 외부 승인/실측 증거는 계속 Open이다. 추천의 X 공개 코드와 Obsidian 기본 포맷은 별도 출처를 확인했다.

| 기존 문서 | 검토·처리 | 현재 위치 |
| --- | --- | --- |
| `.claude/rules/backend-ai.md` | 역할별 안전 경계·사용 조건 유지; 현재 정본 링크와 B단계로 갱신 | [열기](../../.claude/rules/backend-ai.md) |
| `.claude/rules/frontend.md` | 역할별 안전 경계·사용 조건 유지; 현재 정본 링크와 B단계로 갱신 | [열기](../../.claude/rules/frontend.md) |
| `.claude/skills/nullnull-slice/SKILL.md` | 역할별 안전 경계·사용 조건 유지; 현재 정본 링크와 B단계로 갱신 | [열기](../../.claude/skills/nullnull-slice/SKILL.md) |
| `.github/pull_request_template.md` | PR 기능/계약/test/상대검토 항목 유지; task ID 연결과 scaffold 용어 정렬 | [열기](../../.github/pull_request_template.md) |
| `AGENTS.md` | 사용자 지침 보존; 현재 문서 진입점·정본 경로 확인 | [열기](../../AGENTS.md) |
| `CLAUDE.md` | 역할·계약 우선·검증 지침 유지; 제거 문서 참조 갱신 | [열기](../../CLAUDE.md) |
| `CONTRIBUTING.md` | 검토/CI 규칙 유지; workflow 중복 진입 통합 | [열기](../../CONTRIBUTING.md) |
| `README.md` | Obsidian 홈·새 실행 계획 연결; 내부 날짜 일정 제거 | [열기](../../README.md) |
| `SECURITY.md` | 신고·비밀정보 경계 보존 | [열기](../../SECURITY.md) |
| `docs/README.md` | vault 홈/정본 지도/기본 Obsidian 사용법으로 재구성 | [열기](../README.md) |
| `docs/api/README.md` | API 0.2.0의 scope/decision union·50operations·24시간·null 계약 대조 | [열기](../api/README.md) |
| `docs/architecture/ERD.md` | 테이블·관계·unique/FK/check·전이·TTL·삭제/restore 검토; 공개 계약 변경 없음 | [열기](../architecture/ERD.md) |
| `docs/architecture/SYSTEM_ARCHITECTURE.md` | 기존 backend 내부 초안 통합; 비동기 run 흐름·공통 relation 소유권·Live 분리 | [열기](../architecture/SYSTEM_ARCHITECTURE.md) |
| `docs/contest/2026-관광데이터-활용-공모전-공지-심사기준.md` | 공식 자격/마감·KTO·AI도구·출처·위치·제출/증거 규칙 보존; 내부 날짜 계획을 검수 단계로 변경 | [열기](../contest/2026-관광데이터-활용-공모전-공지-심사기준.md) |
| `docs/contest/COMPETITION_COMPLIANCE_MATRIX.md` | 공식 자격/마감·KTO·AI도구·출처·위치·제출/증거 규칙 보존; 내부 날짜 계획을 검수 단계로 변경 | [열기](../contest/COMPETITION_COMPLIANCE_MATRIX.md) |
| `docs/contest/EVIDENCE_LEDGER_TEMPLATE.md` | 공식 자격/마감·KTO·AI도구·출처·위치·제출/증거 규칙 보존; 내부 날짜 계획을 검수 단계로 변경 | [열기](../contest/EVIDENCE_LEDGER_TEMPLATE.md) |
| `docs/contest/SUBMISSION_RUNBOOK.md` | 공식 자격/마감·KTO·AI도구·출처·위치·제출/증거 규칙 보존; 내부 날짜 계획을 검수 단계로 변경 | [열기](../contest/SUBMISSION_RUNBOOK.md) |
| `docs/data/SOURCE_CATALOG.md` | observedAt null/조회시각 불일치 수정; 공통 KTO 먼저·서울 Live 마지막 | [열기](../data/SOURCE_CATALOG.md) |
| `docs/decisions/ADR-0001-target-stack.md` | Context·Decision·Consequences·대안·review trigger 보존; 단일 ADR 노트에 통합 | [열기](../decisions/ARCHITECTURE_DECISIONS.md) |
| `docs/decisions/ADR-0002-data-truth-and-ai.md` | Context·Decision·Consequences·대안·review trigger 보존; 단일 ADR 노트에 통합 | [열기](../decisions/ARCHITECTURE_DECISIONS.md) |
| `docs/decisions/ADR-0003-session-consistency.md` | Context·Decision·Consequences·대안·review trigger 보존; 단일 ADR 노트에 통합 | [열기](../decisions/ARCHITECTURE_DECISIONS.md) |
| `docs/decisions/ADR-0004-two-person-contract-delivery.md` | Context·Decision·Consequences·대안·review trigger 보존; 단일 ADR 노트에 통합 | [열기](../decisions/ARCHITECTURE_DECISIONS.md) |
| `docs/decisions/ADR-0005-aws-release-boundaries.md` | Context·Decision·Consequences·대안·review trigger 보존; 단일 ADR 노트에 통합 | [열기](../decisions/ARCHITECTURE_DECISIONS.md) |
| `docs/design/COMPONENT_CATALOG.md` | 49개 exact 이름과 UI/data 경계 유지 | [열기](../design/COMPONENT_CATALOG.md) |
| `docs/design/FIGMA_CHANGE_REQUESTS.md` | API에서 참조하지만 누락된 FCR-010/011/015 등록; 실제 node 반영 미확인 명시 | [열기](../design/FIGMA_CHANGE_REQUESTS.md) |
| `docs/design/FIGMA_HANDOFF.md` | 52개 frame/state/API 대조; Live 실행 순서 분리; FCR Open 유지 | [열기](../design/FIGMA_HANDOFF.md) |
| `docs/engineering/BRANCH_AND_INTEGRATION.md` | 브랜치/인계/계약 protocol 정본 통합; stable required check 유지 | [열기](../engineering/BRANCH_AND_INTEGRATION.md) |
| `docs/engineering/IMPLEMENTATION_PLAN.md` | 날짜·공수·중복 ticket 제거; B00~B10 의존 순서와 Live 마지막 정의 | [열기](../engineering/IMPLEMENTATION_PLAN.md) |
| `docs/engineering/LOCAL_DEVELOPMENT.md` | exact lock·seed·reset·실제 task 전환 조건 유지 | [열기](../engineering/LOCAL_DEVELOPMENT.md) |
| `docs/engineering/OWNERSHIP_MATRIX.md` | FE/BE 책임·52노드 공동 인계 정본 유지; 실행 순서 링크 | [열기](../engineering/OWNERSHIP_MATRIX.md) |
| `docs/engineering/TEST_STRATEGY.md` | 추천 CI 초안 통합; 전 기능 상시 safety·실제 구현/계획 test 구분 | [열기](../engineering/TEST_STRATEGY.md) |
| `docs/engineering/WORKFLOW.md` | 고유 DoR/DoD·contract packet·상태 전이·flag protocol을 브랜치 문서로 병합 | [열기](../engineering/BRANCH_AND_INTEGRATION.md) |
| `docs/operations/AWS_DEPLOYMENT.md` | private network·OIDC·migration·artifact·rollback/restore·비용 검토; Live 전후 검수 구분 | [열기](../operations/AWS_DEPLOYMENT.md) |
| `docs/operations/ENVIRONMENT.md` | APP_REVERT_WINDOW PT15M→PT24H 계약 수정; startup/CI invariant 추가 | [열기](../operations/ENVIRONMENT.md) |
| `docs/operations/GITHUB_RELEASE_OPERATIONS.md` | stable 두 required check green auto-merge·production 승인·artifact 보존 유지; 내부 날짜 대신 gate | [열기](../operations/GITHUB_RELEASE_OPERATIONS.md) |
| `docs/operations/INCIDENT_RESPONSE.md` | severity·연락·보안/삭제/데이터 사고 SLA 유지; 내부 freeze 날짜를 단계로 변경 | [열기](../operations/INCIDENT_RESPONSE.md) |
| `docs/product/FUNCTIONAL_INVENTORY.md` | 전체127 FR/NFR와 API 관계를 task manifest에 연결; Live 우선순위는 유지 | [열기](../product/FUNCTIONAL_INVENTORY.md) |
| `docs/product/PRODUCT_SPEC.md` | P0 의미 유지·누락 QUALITATIVE 상태 보정·실행 순서 단일화 | [열기](../product/PRODUCT_SPEC.md) |
| `docs/project/DECISIONS_AND_RISKS.md` | 현재 상태/저장소/문서 감사 통합; 날짜별 결정 기한을 선행 gate로 변경 | [열기](DECISIONS_AND_RISKS.md) |
| `docs/project/PM_CONSISTENCY_AUDIT.md` | NO-GO·실행물 부재·Figma blocker·기술 veto 보존; 날짜/공수 평가 제거 후 결정 대장 통합 | [열기](DECISIONS_AND_RISKS.md) |
| `docs/project/REPOSITORY_BASELINE.md` | 현재 backend 상태 반영; prototype 제외·이력 보존·baseline/full 구분을 결정 대장에 통합 | [열기](DECISIONS_AND_RISKS.md) |
| `docs/roles/BACKEND_AI_PLAYBOOK.md` | 45작업·127요구·50API·135 acceptance 초안으로 상세화; 단일 backend 책임 | [열기](../roles/BACKEND_AI_PLAYBOOK.md) |
| `docs/roles/FRONTEND_PLAYBOOK.md` | 중복 화면/API 표를 정본 링크로 대체; BE 인계 순서·Live 마지막 정렬 | [열기](../roles/FRONTEND_PLAYBOOK.md) |
| `docs/security/PRIVACY_REQUIREMENTS.md` | owner/session/원문/위치/90일event·7일receipt·tombstone 검토; 계획 작업에 반영 | [열기](../security/PRIVACY_REQUIREMENTS.md) |
| `docs/security/THREAT_MODEL.md` | T-01~36 통제와 abuse cases를 session/data/job/CI/Live 작업 검증에 반영 | [열기](../security/THREAT_MODEL.md) |
| `과제2_널널_웹앱구현_기획서_Final.md` | 고유 배경/제품 설명은 archive 보존; 중복 로드맵·내부 날짜 제거, 현행 정본 링크 | [열기](../archive/PRODUCT_BRIEF.md) |

추가 산출물은 추천 상세 설계, 45작업 기계 판독 목록, Obsidian Canvas다. 파일 이동 대응표는 [document-moves.json](../contracts/document-moves.json)에 남긴다. 외부 기존 북마크는 새 경로를 사용하며 과거 파일의 원문/기존 승인 이력은 Git history로 추적한다.

## 2026-09-06 후속 PM 검토

[PM-001~024 상세](PM_REVIEW_2026-09-06.md)와 [52개 화면 확인표](../design/SCREEN_REVIEW_2026-09-06.md)를 영향 slice의 DoR에 포함한다. 특히 추천 초안/혼합 편집/import/시간/후보 복원/일반 콘텐츠 출처/Live와 세션 삭제 계약을 파일 수·operation coverage로 완료 처리하지 않는다.

FCR의 디자인·계약 검토 완료(Ready for implementation)와 실제 구현·회귀 완료(Closed)를 구분한다. 이번 문서 점검만으로 FCR 또는 BA 작업의 구현 상태를 올리지 않았다.

## 이슈 #10·#11 후속 제안

[#10 기반 결정안](../engineering/FOUNDATION_DECISIONS.md)은 D-021의 도구/DB·첫 scaffold host/최소 통합 범위를 구체화한다. [#11 계약 packet](../contracts/review-2026-09-06/README.md)은 FCR-010/011/015의 출처·undo·응답 union을 0.2.1-rc.1로 제안한다. 검사 통과와 FE 승인·실제 구현·원격 이슈 종료를 구분하며 PM-009/010/014/015/022의 남은 조건을 유지한다.
