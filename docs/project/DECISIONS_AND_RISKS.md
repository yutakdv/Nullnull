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
| A-028 | staging은 **오너 개인 AWS 계정 하나**로 운영하며 production account/environment는 만들지 않는다. 단 deploy, ECS execution, api task, migration 역할은 최소 권한으로 분리한다 | 2026-09-14 정정. 이전의 "role 분리도 두지 않는다"는 계정·환경 분리를 생략한다는 뜻으로만 유지한다. 하나의 광범위 role을 공유하면 BA-006/071의 OIDC·runtime 최소 권한 조건을 만족하지 못하므로 실행 책임별 IAM role은 분리한다 |
| A-029 | staging 총비용 상한은 **$200**, 운영 종료일은 **2026-10-25**다 | 2026-09-14 오너가 기존 $150/약 1개월 결정을 교체했다. 서울 region에서 CloudFront VPC origin, ECS api 1(ai 1), RDS Multi-AZ를 기본으로 하고 심사 기간에만 api 2로 확장한다. 계획치는 $170~185다. **완료 증거에서 Budget 알림 절은 `A-050`이 걷어냈다** — 조직 SCP가 `budgets:*`와 Cost Explorer를 거부해 만들 수 없다. 남는 증거는 **종료일 teardown gate([#307](https://github.com/yutakdv/Nullnull/issues/307))와 오너의 조직 billing 화면 확인** 둘이고, 그래서 이 상한은 기계가 아니라 사람이 지킨다 |
| A-030 | 실제 서비스 domain은 정하지 않고 placeholder로 두며 production deploy를 하지 않는다 | 2026-09-13 오너 결정, D-001. 제출은 staging URL로 완결되고, 실제 domain은 Route53/ACM 검증을 요구해 지금 잡으면 제출과 무관한 비용만 만든다 |
| A-037 | staging alarm의 **primary 수신자는 확정**했고 주소는 Git이 아닌 로컬 ignored 설정과 AWS/GitHub 보호 설정으로만 주입한다. secondary와 실제 수신/tabletop은 아직 열려 있다 | 2026-09-14 오너 결정. 개인 연락처를 release artifact·CDK context·문서에 복제하지 않는다. `NULLNULL_ALARM_PRIMARY_EMAIL` 존재와 SNS 구독 확인은 자동 검사하되 값은 출력하지 않는다. D-019는 secondary·수신 test까지 끝날 때 닫는다 |
| A-031 | P0 feed의 큐레이션 게시물은 **운영 스크립트**로 만든다. migration에도 넣지 않고 내부 작성 operation도 만들지 않는다 | 2026-09-13 오너 결정, #183. migration에 넣으면 스키마 변경마다 콘텐츠가 따라다니고, 작성 operation은 P1인 `BA-082`를 P0로 당기는 것이 된다. 표지의 권리 경로는 A-024·`V021`이 이미 만들었다 |
| A-044 | staging KTO 예보 재적재를 **AWS 스케줄(12시간 간격)** 로 돌린다. 그 스케줄에 고정되는 `NULLNULL_KTO_FORECAST_SMOKE_APPROVED`는 **오너의 상시 승인**이다 | 2026-09-19 오너 결정. 예보 set은 `PT24H` 뒤 stale이고 심사 기간이 2026-10-25까지라, 사람이 12시간마다 손으로 돌리는 운영은 유지되지 않는다(O-8의 권고 간격). 조건 셋을 함께 둔다 — 운영 종료일(`staging_operator.py`의 `EXPIRY` 상수, 2026-10-25T14:59:59Z) 뒤 실행하지 않음, 실패·미실행이 primary 수신자에게 닿는 alarm, 장소 목록과 호출량(하루 4건) 고정. 로컬 스케줄(launchd)이 아니라 AWS에 두는 이유는 노트북 상태에 의존하지 않고 승인값이 오너 셸이 아닌 운영 정의에 남기 때문이다 |
| A-039 | **A-056이 2026-09-20에 이 행의 화면 정의를 좁혔다 — 미리 채워진 자격 증명과 `createDemoSession` 절은 내렸고 둘 다 구현된 적이 없다.** 아래는 그때의 기록이다. 공개 API edge(`TrafficEnabled`)는 **FE의 로그인 흉내 화면이 들어온 뒤** 연다. 그 화면은 공지된 ID·비밀번호가 미리 채워진 채 뜨고, 로그인을 누르면 **자격 증명을 어디에도 보내지 않고** 기존 익명 session(`createDemoSession`)으로 들어간다. 여는 시점에 **삭제 원장 없이** 연다 — 심사 기간(2026-10-25까지)에는 DB snapshot 복원을 하지 않고, 복원이 필요하면 edge를 먼저 닫는다 | 2026-09-19 오너 결정. 로그인 API는 P0에 없으므로 이 화면은 인증이 아니라 표지이고, 원칙 13(P0 로그인은 요청을 보내지 않는다)과 불변식 11(owner는 cookie session에서 유도)을 그대로 지킨다. 삭제 원장(`STAGING_DEPLOYMENT_RUNBOOK` §10)이 막는 것은 복원이 접수된 삭제를 되감는 경로 하나이고, 복원을 하지 않는 동안 그 경로는 열리지 않는다. 여닫는 운영 명령은 `staging_operator.py edge --state open` 또는 `--state closed`이고, 위 두 전제(배포된 release에 로그인 흉내 화면이 있을 것, 열려 있는 동안 복원 금지)는 명령이 검사하지 않으므로 운영자가 지킨다. 배포는 매번 edge를 다시 닫는다 |
| A-043 | staging alarm의 **secondary 수신자는 두지 않는다**. D-019를 이것으로 닫는다 | 2026-09-19 오너 결정. `BA-072-T7`의 *"primary/secondary 수신자에게 도달"* 은 primary만으로 증명하고 secondary 절은 이 결정으로 범위에서 뺀다. 부재 escalation은 수신자 한 명이 전부다 |
| A-045 | `BA-006-T2`의 범위를 **operator가 읽을 수 있는 secret(KTO key·verifier token)** 으로 좁힌다. DB 계정·비밀번호, cursor·deletion token secret은 이 절이 증명하지 않는다 | 2026-09-20 오너 결정. 그 값들은 operator 역할이 읽을 수 없고, 노트북으로 읽어 와 새지 않았음을 증명하는 것 자체가 노출이다(`infra/iam/operator.json`도 크기 한도에 닿아 있다). 증거는 `staging_operator.py secret-scan`의 `clean-partial`(release `v0.1.0-rc.9`: release 6개·log event 17,848건·inflate한 blob 5,910개, 누출 0)이다. 증명하지 않는 쪽의 **log 경로**는 `BA-070-T2`(`RedactionAndDenylistIT`·`DatasourceUrlLogRedactionIT` — datasource URL의 비밀번호가 기동 출력에 없다)가 값이 아니라 통로로 막는다. **잔여 위험**: 알려진 통로 밖(제3자 라이브러리의 기동 로그 등)에 그 값이 실제로 찍히면 이 절은 잡지 못한다. 막는 방법은 값을 계정 밖으로 가져오지 않고 계정 안에서 대조해 판정만 내는 스캐너이고, 이 제출에서는 만들지 않는다 |
| A-046 | `BA-006-T3`의 범위를 **다른 environment의 OIDC subject** 로 좁힌다. 다른 repository의 subject는 실제 AssumeRole로 재현하지 않는다 | 2026-09-20 조율자 제안, 이 결정을 담은 PR의 오너 merge로 확정. GitHub는 token을 **호출한 repository의 subject로만** 발급하므로 이 저장소 안에서는 다른 repository의 token을 만들 수 없다. 그 절은 immutable subject(owner·repository ID)만 신뢰하는 구조 test와 `validate-oidc-trust.mjs`의 거부 case가 남는다. environment 쪽은 `staging-oidc-negative` workflow가 실제로 잰다 — 각 job이 자기 role로 받아들여지는 대조군 뒤에 다른 environment의 role에서 `AssumeRoleWithWebIdentity`의 `AccessDenied`를 받아야 통과한다. **잔여 위험**: 이 test는 environment **사이**를 잰다. 같은 environment의 token을 main이 아닌 브랜치가 받는 것을 막는 것은 GitHub environment의 배포 브랜치 정책이고, AWS trust는 그것을 볼 수 없다(subject에 브랜치가 없다). 그 정책이 느슨해지면 이 test는 초록인 채로 남는다. workflow 자신은 기본 브랜치에서만 뜬다 |
| A-047 | `BA-071-T3`의 범위를 **staging 배포는 release manifest에 묶인 산출물 전체(api·ai image digest, OpenAPI·event 계약, web artifact, Flyway checksum 목록)와 승인된 CDK assembly만 실행한다** 로 좁힌다. staging→production 승격은 이 제출에서 증명하지 않는다 | 2026-09-20 오너 결정. production 환경이 없고(카드의 실패·안전 경계가 승인 없는 production 배포를 금지한다) 만들 계획도 제출 범위에 없다. 뺀 것은 **승격** 하나이고 산출물 집합은 줄이지 않는다 — 처음 초안이 *"digest만"* 으로 좁혔다가 Codex 검토가 web·계약·migration을 잃는다고 지적해 되돌렸다. 대조는 `staging_operator.check_artifacts`·plan 검증(`assembly-changed`·`release-changed`)이 하고, 계약·web·Flyway 대조에는 test가 없어서 이 결정과 함께 더했다. production을 만드는 날 원래 절을 되살린다 |
| A-048 | `BA-072-T1`(삭제 이전 backup 복원 뒤 owner 비노출)을 **제출 범위에서 뺀다** | 2026-09-20 오너 결정, A-039의 귀결. 삭제 원장이 없고 심사 기간에는 복원하지 않으므로 복원이 접수된 삭제를 되감는 경로가 열리지 않는다. **위험은 남는다** — 복원이 필요해지면 edge를 먼저 닫고, 삭제 원장(`STAGING_DEPLOYMENT_RUNBOOK` §10)을 구현해 재적용을 증명하기 전에는 다시 열지 않는다. 절은 카드에 남기고 증명된 것으로 쓰지 않는다 |
| A-049 | `BA-072-T3`(수신자 부재 escalation·예산/쿼터 경보·rollback 판단)은 **문서 tabletop** 으로 증명한다. 증거 등급은 *실제 재현이 아니라 tabletop* 이다 | 2026-09-20 오너 결정. 수신자는 A-043으로 한 명이라 부재 escalation은 그 사람의 부재 대응 절차가 전부이고, 예산 경보는 조직 SCP가 `budgets:*`를 거부해 만들 수 없어 오너가 조직 billing 화면에서 확인한다. 쿼터는 KTO 호출량 줄(`KTO_DEMO_REFRESH_QUOTA`)과 `DemoRefreshFailed` alarm이 실제 신호이고, rollback 판단은 이 날 세 번의 배포 실패(grace·schedule 입력·stateful 가드)가 실제 사례다. 카드는 이 등급을 적고 *재현했다* 로 쓰지 않는다 |
| A-050 | staging **비용 경보는 두지 않고** `BA-072-T3`의 예산 절을 tabletop에서 뺀다. 대신 운영 종료일에 **teardown 이슈(#307)** 로 과금을 끝낸다 | 2026-09-20 오너 결정. 조직 SCP가 `budgets:*`·Cost Explorer를 거부해 오너가 비용 알림을 받을 수 없고 관리할 수단도 없다. 비용은 고정비가 대부분이고(§9 42일 계획) API task가 1개로 고정·WAF가 `/api/`를 IP당 5분 2,000건으로 막아 공개 트래픽이 비용을 키우지 않는다. **남는 위험은 종료 뒤다** — schedule은 `EXPIRY`에 멈추지만 RDS·ECS·ALB는 계속 과금되므로 teardown을 잊으면 비용이 이어진다 |
| A-051 | 지도·경로 provider는 **카카오 계열 API**로 확정한다. `D-002`의 provider 선택 절을 닫는다 | 2026-09-20 오너 결정. **남은 것은 제품 구분이고 그것이 ADR의 첫 단계다** — 지도·장소(카카오맵)와 길찾기·이동시간(카카오모빌리티)은 서비스·키·약관이 다를 수 있는데 저장소에는 **두 제품 중 어느 쪽이 route matrix를 주는지 적힌 곳이 없다**(`SOURCE_CATALOG` §의 `ROUTE_PROVIDER`가 `미정`인 한 줄이 전부다). `BA-083`이 1차 출처로 그것을 확인하고 D-002가 요구하던 가격·쿼터·약관·SDK 비교를 같은 ADR에 담는다. **확인 전에는 어느 쪽도 가격·쿼터를 단정하지 않는다** — 두 제품을 한 이름으로 부르면 한쪽의 무료 한도를 다른 쪽 근거로 쓰게 된다. **저장 여부는 A-055가 닫았다(저장하지 않는다).** P0은 그대로 route matrix 없이 목록 UI이고 이 결정은 P1-Route에서만 효력이 있다. 키는 env에서만 읽고 `.env` 값은 비워 둔다 |
| A-052 | 장기 계정 로그인은 **이메일 매직링크 · 계정 1개 · 익명 session 승계**로 한다. `D-004`를 닫는다 | 2026-09-20 오너 결정. 구현은 `BA-081`(P1)이고 **제출 profile에서는 로그인 CTA가 `준비 중`으로 남는다**(원칙 13 — P0 로그인은 요청을 보내지 않는다). **심사용 공용 계정을 만들지 않는다**: `owners.account_id`는 null이 아닐 때만 unique이므로(`V002__owners.sql:27`의 partial unique index) 여러 사람이 한 계정으로 들어오면 **같은 owner 행을 공유**하게 되고, 불변식 11이 지키려는 격리가 기술이 아니라 사람 단위에서 깨진다. 심사위원이 보는 로그인 표지의 정의는 **A-056**이다(이 행의 최초 문안은 `A-039`를 인용하면서 그 행의 *미리 채워진 자격 증명* 절을 빠뜨렸다). 익명 승계는 불변식 5의 원자적 transaction이며 승계 실패가 기존 익명 session의 data를 잃게 하지 않는다 |
| A-053 | P1 게시물은 **S3 격리 업로드 + 오너 수동 승인**으로 한다 **(2026-09-20 같은 날 `A-058`이 승인 게이트를 내렸다 — 격리는 남는다)**. 자동 moderation SaaS를 도입하지 않는다. `D-008`을 닫는다 | 2026-09-20 오너 결정. 구현은 `BA-082`(P1)이고 P0에서는 작성 기능이 그대로 OFF다. 업로드는 공개 경로에 직접 닿지 않고 격리 위치에 들어간 뒤 **오너 승인으로만** 공개로 옮겨진다 — 승인 전 자산이 `MediaAsset`으로 노출되지 않는 것이 이 결정의 검사 지점이다. 표지 자산의 권리 경로는 A-024가 이미 정했고 이 결정이 그것을 바꾸지 않는다. **자동 moderation이 없다는 것은 금지 콘텐츠 판단이 사람 한 명에게 남는다는 뜻이고**, 신고·삭제 경로와 그 SLA는 공개 출시 전에 따로 정해야 한다(이 제출 범위 밖). **2026-09-20 개정(`A-058`)**: 오너 수동 승인 게이트를 내린다. 위 *"격리 위치에 들어간 뒤 오너 승인으로만 공개로 옮겨진다"* 와 그 검사 지점 *"승인 전 자산이 `MediaAsset`으로 노출되지 않는 것"* 은 승인 단계가 없어져 **발화할 수 없는 단언**이 되므로 `A-058`의 검사 지점으로 대체된다. **격리 저장 자체는 남는다** — 승인을 기다리는 대기열이 아니라 **자동 기술 검증의 작업 공간**이고, 검증을 통과한 정제본만 공개 위치로 간다. 자동 moderation SaaS를 도입하지 않는다는 절도 그대로다. **그래서 위 마지막 문장의 무게가 커진다**: 승인이 없으면 신고·삭제가 유일한 사후 수단인데 그것이 여전히 범위 밖이다 |
| A-054 | Live(`BA-090`·`BA-091`·`BA-092`)를 **제출 전에 전면 구현**한다. `A-033`의 *"구현하지 않고 목업으로 처리할 수 있다"* 와 [#64](https://github.com/yutakdv/Nullnull/issues/64)의 2026-09-19 오너 코멘트 *"제출(09-21) 뒤로 미룹니다"* 를 대체한다 | 2026-09-20 오너 결정. **두 세션에서 각각 오너에게 직접 확인했다** — 어제 코멘트와 정반대라 릴레이만으로 진행하지 않았고, 작업자가 세 선택지(example만·전면 착수·중단)를 마감과 함께 다시 제시해 같은 답을 받았다. **이 순서를 가능하게 한 사실은 같은 날 서울 실시간 도시데이터 실 키가 발급된 것**이다 — `SOURCE_CATALOG` §5의 *"sample key는 제한된 장소만 조회할 수 있다"* 제약이 해당 없어졌다. 남은 것은 셋 다 우리 쪽 배선이다: `.env.example`·compose의 키 자리(값은 비우고 env에서만 읽는다), `SEOUL_CITYDATA`·`DEMO_REPLAY`의 `approval_state` `DISABLED` 승격, 그리고 그 둘이 선 뒤에야 켤 수 있는 `FEATURE_LIVE_DATA`(source registry·key·readiness 없이 ON이면 startup이 실패하는 fail-closed가 이미 걸려 있다). **`A-033`의 `BA-092-T3` 절은 뒤집힌 것이 아니라 이제 원래 의미로 발동한다** — 목업이 아니므로 그 절이 검사하는 것은 Live를 포함한 전체 P0 게이트 그대로다. **마감은 2026-09-21 16:00이고 `BA-090`의 선행 `BA-073`(제출 스트림)이 아직 `planned`다** — 구현은 진행하되 승격과 배포 순서는 제출 증거를 깨지 않는 자리에 둔다(#305의 4~6은 재배포하면 다시 한다) |
| A-055 | 카카오 경로 API 응답을 **DB에 저장하지 않는다.** `BA-083`의 `route_matrix_snapshots`를 만들지 않고, 캐싱 허용 범위를 카카오에 **서면 질의하지 않는다** | 2026-09-20 오너 결정. 근거는 카카오 **운영정책 제5조 제20항**(*"앱에서 사용자 환경을 개선하기 위한 목적 외 다른 목적으로 카카오에서 받은 데이터를 캐시하거나 캐시 후 최신 데이터로 유지하지 않는 행위"* 금지)과 공식 답변 둘(2026-08-18 *"API 응답 결과는 저장하여 사용하실 수 없습니다"*, 2026-09-17 *"장소ID와 URL은 저장하여 활용 가능하며, 이 외 데이터는 DB저장이 불가"*·*"실시간이 아닌 임시 캐싱 등의 방법으로 API 호출 및 이용은 금지"*)이다. **한계를 함께 적는다**: 그 답변들은 카카오맵/Local에 대한 것이고 **모빌리티 길찾기를 명시적으로 다룬 공식 답변은 찾지 못했다**(찾아본 곳은 `developers.kakaomobility.com`의 guide·price·affiliate 경로 전부와 약관·캐싱 웹 검색 3회다). 운영정책이 지배한다는 것은 *키가 Kakao Developers에서 나오고 그 조항에 제휴 예외가 없다*는 근거의 **추론**이고, 질의하지 않기로 했으므로 이 추론 위에서 간다. **AGENTS 원칙 9(외부 record의 `source`·`observed_at`·freshness 보존)와 부딪히는 자리가 여기다** — 근거의 이력이 남지 않으므로 경로 값은 *저장된 근거*가 아니라 *요청 시점에 계산된 값*으로만 화면에 나타나고, **저장된 근거처럼 비교하거나 인용하지 않는다.** 빈 snapshot을 만들어 근거가 있는 것처럼 보이게 하지 않는 쪽이 no-silent-fallback과 맞는다. 도보·자전거는 제휴 전용이라(`/affiliate/walking/…`, 사전 계약 필수) 자동차 ETA만 쓰고 **도심 인접 구간에서 그 값이 실제 도보 이동과 다르다는 것을 화면이 주장하지 않는다.** `BA-083` 카드의 `route_matrix_snapshots` entity와 `BA-083-T3`의 *"route stale race"* 절은 이 결정에 맞춰 조율자가 고친다 |
| A-056 | 로그인 화면은 **껍데기로만 존재한다** — 화면이 뜨고 누르면 Feed로 바로 들어가며 **기능은 없다.** `A-039`의 세 절 중 *"공지된 ID·비밀번호가 미리 채워진 채"* 와 *"`createDemoSession`으로 들어간다"* 를 **내린다**. 남는 절은 *"자격 증명을 어디에도 보내지 않는다"* 하나다 | 2026-09-20 오너 결정. **그 두 절은 구현된 적이 없다** — `SignInScreen.tsx`는 최초 커밋 `6fedae4`부터 `useState('')` 둘이었고 `defaultValue`가 0건이며, `createDemoSession`은 `apps/web/src` 전체에 **0건**이다(submit은 `preventDefault` 뒤 `navigate('/feed')`뿐이고 test 여섯이 그것을 고정한다). 그리고 **`A-039`가 원장에 들어간 시점**(`285ce94`, 2026-09-19 20:22)이 **오너가 로그인을 P1로 되돌린 커밋**(`1768f8a`, 같은 날 10:41)보다 10시간 뒤였다 — 그 revert의 사유(`owners.account_id`가 null이 아닐 때 unique라 여러 심사위원이 한 owner 행을 공유한다)는 공용 심사 로그인과 정면으로 어긋난다. 즉 `A-039`의 두 절은 **이미 배제된 설계를 전제로 쓰였다.** **귀결**: `#305` step 3(edge open)의 전제가 **현재 배포본으로 이미 충족된다** — 새 FE 작업이 필요 없다. **남은 확인 둘은 FE 몫이고 `#310`에 올린다**: (1) 오너 문장의 경로는 `/login`인데 현재 등록된 route는 `/sign-in`이다, (2) 그 화면으로 **가는 경로가 하나도 없다**(`ProfileScreen`의 행이 `준비 중` 비활성 텍스트다) — *"존재만 한다"* 를 문자 그대로 읽으면 도달 경로는 필요 없고, 심사위원이 그 화면을 봐야 한다면 필요하다. **이 결정이 원칙 13을 완화하지 않는다** — 요청을 보내지 않는 것은 그대로다 |
| A-057 | 게시물 미디어 입력은 **표준 파일 입력 `<input type="file" accept="image/*">` 하나로 한다** — 앱이 카메라 스트림(`getUserMedia`)을 직접 열지 않고 `capture` 속성으로 카메라를 강제하지도 않는다 | 2026-09-20 오너 결정. 구현은 `BA-082`(P1)이고 P0에서는 작성 기능이 그대로 OFF다(`A-053`과 같은 범위). 누르면 **OS 파일 선택기**가 뜨고 모바일에서는 시스템이 *사진 보관함 / 사진 찍기 / 파일 선택*을 알아서 띄우므로 **입력 경로 셋을 화면이 각각 만들지 않는다.** 권한 프롬프트도 브라우저가 처리하므로 앱이 권한 상태를 들고 있지 않는다 — `getUserMedia`를 쓰면 그 프롬프트의 수신자가 우리가 되고 거절·재요청 상태를 화면이 관리해야 한다. **이 결정은 위치 규칙과 무관하다** — 파일 선택기는 geolocation 권한을 요구하지 않으므로 제출 profile의 위치 OFF(`CMP-LOC-002`)를 건드리지 않는다. 다만 **`사진 찍기`로 들어온 파일은 EXIF에 GPS가 실릴 수 있으므로** `BA-082` 구현 순서 ③의 EXIF 절이 그 입력을 전제로 쓰여야 한다(불변식 10). **이 결정이 정하지 않는 것**: 다중 선택 허용 여부, 크기·형식 상한, 업로드 전송 방식(presign 직접 업로드인지 서버 경유인지) — 격리·승인 경계는 `A-053`이 정한 데까지이고 나머지는 `BA-082`가 연다. **출처 구분(2026-09-20 추가)**: 이 칸의 설명 문안은 `W-UPLOAD` 세션이 쓴 것을 **오너가 그대로 복사해 붙여넣으며 기록을 지시한 것**이다. 결정의 유효성에는 문제가 없으나 — 오너가 읽고 의도적으로 채택했다 — **오너의 독립 판단을 기록한 것은 아니다.** 이 구분을 적는 이유는 원장이 제시된 문안을 오너 원문으로 인용하면 **같은 근거가 두 출처처럼 읽히기** 때문이다(`A-058`도 같은 모양이라 거기에도 적었다) |
| A-058 | 게시물 작성은 **제출 범위**이고, 게시물은 **자동 기술 검증을 통과하면 즉시 공개**된다 — `A-053`의 오너 수동 승인 게이트를 내린다. 작성자는 **익명 session**이어도 되며 범위는 작성 화면 전체(표지 사진·`body`·본문 장소 연결)다 | 2026-09-20 오너 결정. **결정은 오너의 것이고 아래 문안의 저자는 대부분 `W-UPLOAD` 세션이다** — 이 구분을 적는 이유는 `A-057`과 같다(제시된 선택지를 오너 원문으로 인용하면 한 출처가 둘로 보인다). 오너가 자유 입력한 축자는 넷이다: *"오너 승인이 아니라 그냥 올리게 할거야 2번이 맞는거 같아"*, *"본문 장소 연결도 해야해"*, *"제출용"*, *"2번으로 해서 FE한테 전달할 이슈 생성해"*. 익명 작성(`2번`)·`USER_UPLOAD` source·자동 검증 유지는 **W-UPLOAD가 제시한 선택지를 오너가 고른 것**이다. **검사 지점**: *"자동 기술 검증을 통과하지 못한 asset은 `MediaAsset`으로 노출되지 않는다"* — 검증은 (a) 선언된 MIME이 아니라 **실제 바이트(magic bytes)** 로 형식을 판정하고, (b) 크기·치수 상한을 적용하며, (c) **EXIF를 전량 제거**한다. (a)가 가설이 아니라는 증거는 저장소 안에 있다 — `docs/contest/covers/`의 표지 **5장 전부 `.jpg` 이름인데 실제 바이트는 PNG**다(`file`로 실측). (c)가 불변식 10의 자리다: `A-057`이 정한 표준 파일 입력에서 *사진 찍기*로 들어온 파일은 촬영 좌표를 EXIF에 싣고, 그것을 공개 CDN에 올리는 것은 정밀 위치를 서버에 보내지 않는다는 절을 **우회한다**. 익명 작성은 **불변식 11을 깨지 않는다** — owner는 여전히 authenticated cookie session에서 유도하고 client가 보낸 ID를 신뢰하지 않는다. **이 결정이 정하지 않는 것**: `A-052`의 익명 승계 시 게시물이 계정으로 따라가는지(`BA-081` 미설계). **잔여 위험 셋**: (1) 사람 검토도 자동 moderation도 없으므로 기술 검증이 유일한 방어선인데 그것은 파일이 *무엇인지*만 보고 *무엇을 담고 있는지*는 보지 않는다 — 유효한 JPEG인 불법·권리침해·타인 촬영 이미지는 전부 통과한다. (2) 심사 기간 동안 제출본은 로그인 없이 열려 있고 작성이 익명이므로 **계정 단위 차단 수단이 없다**. (3) `A-053`이 신고·삭제를 범위 밖으로 둔 것은 **승인 게이트가 있다는 전제 위에 있었다** — 승인이 없으면 신고·삭제가 유일한 사후 수단인데 그것도 범위 밖이고, 사건이 나면 남는 수단은 **capability를 꺼서 기능 전체를 내리는 것**뿐이며 그것은 사건 인지에 의존한다. **(4) 계정 삭제가 공개된 이미지 객체를 지우지 못한다** — eraser 는 transaction 안에서 외부 호출을 할 수 없다(저장소 규칙). 게시물 행이 지워지면 객체는 **도달 불가**가 된다(어떤 행도 URL 을 들고 있지 않고 key 는 무작위 id 다). **그러나 도달 불가는 삭제가 아니다** — `docs/security` 의 *"삭제는 즉시 revoke·status·tombstone/manifest·backup 복구 후 재삭제까지 설계한다"* 를 객체에 대해서는 충족하지 못한다. 닫으려면 published prefix 에 대한 job 이나 lifecycle rule 이 필요한데 **job 은 `JobConnectionBudget` 예산이 막고**(type 셋째를 들이면 여유 0), lifecycle 을 published prefix 에 걸면 멀쩡한 표지가 사라진다. 앞의 셋과 같은 성질이다 — 기제가 없고 이름을 붙여 두는 것이 지금 할 수 있는 전부다 |
| A-059 | LLM 월 상한 **$15는 OpenAI 대시보드 하드 리밋으로만 강제된다.** 앱은 강제하지 않는다 — 한 호출을 묶고(`MAX_OUTPUT_TOKENS=200`·`TIMEOUT_SECONDS=2.0`) 승인값을 상수로 들고 있을 뿐이다 | 2026-09-20 오너가 대시보드에 하드 리밋을 **실제로 설정했다**(오너 보고: *"15달러 하드리밋 걸어놨어"*). **이 행이 존재하는 이유는 강제 주체가 코드 밖에 있기 때문이다.** `apps/ai`는 ADR-0006에 따라 재시작을 넘는 state가 없으므로 메모리 counter는 배포마다 0으로 돌아가면서 **예산을 지키고 있다고 보고한다** — 실패하는 순간에 조용한 가드이고, 이 저장소가 반복해서 금지한 모양이다(`W-AI` 세션이 구현 불가를 측정으로 보고했다). AWS 쪽 대체 수단도 없다: org SCP가 `budgets:*`를 거부해 Budget 알람을 만들 수 없다(`A-050`). **그래서 `MONTHLY_BUDGET_USD = Decimal("15")` 상수는 승인값의 집이지 강제 장치가 아니고**, 그 docstring이 그렇게 적는다. 코드를 읽고 *"앱에 상한이 있다"* 로 읽으면 안 된다. provider가 quota로 거절하면 `ProviderBudgetExceededError`가 되고 `BA-084①`의 fallback template으로 끝난다 — **초과가 사용자에게 보이는 실패가 아니라 근거 없는 설명의 부재로 나타난다.** **되돌아가는 조건**: 대시보드 설정이 풀리거나 계정이 바뀌면 이 행의 전제가 사라진다. 그때 남는 방벽은 호출 단위 상한뿐이고 그것은 월 합계를 보지 않는다 |
| A-038 | 관련 장소(`listRelatedPlaces`)의 병합·정렬은 **Spring이 한다** — ADR-0006의 예외다. P0 순서는 (tier, placeId)이고 categoryMatch 항은 적용하지 않는다. 이 경로의 상한은 writer의 `CatalogRelationDeriver.MAX_PER_SOURCE`(= policy-v1 `relatedPerChannel`, 100) 하나다 | 2026-09-19 오너 결정, 조율자 경유. 정본은 [ADR-0006 · 예외](../decisions/ARCHITECTURE_DECISIONS.md#adr-0006--예외)이다. `apps/ai` `related/rank`에는 production 호출자가 없었고, Spring converge가 이미 순서를 정하고 있었다. 연결하려면 `places`에 없는 parent category·taxonomy version이 있어야 하고, source↔channel 대응과 ranker `NONE` 덮어쓰기도 정해야 한다. 공모전 제출 뒤, 또는 두 번째 관계 source를 승인할 때 다시 본다 |
| A-036 | acceptance 절 하나를 **상대 역할이 소유**한다고 표시할 수 있고, 그 절은 집계기가 JUnit testcase를 요구하지 않는다(`tests[].externalOwner = {role, reason, issue}`) | 2026-09-14 결정. `BA-040-T4`와 `BA-070-T5`는 `apps/web`의 keyboard/focus E2E이고, `integration-test.sh`가 `--e2e-junit-dir`를 넘기지 않아 **FE가 아무리 잘 구현해도 그 ID는 JUnit 이름에 나타날 수 없다.** 두 카드는 나머지 절이 전부 증명됐는데도 **영구히 승격 불가**였다. frontend-plan으로 옮기는 것은 답이 아니다 — `validate_frontend_plan.py`는 report를 열지 않으므로 *"집계기가 못 보는 ID"* 가 *"아무것도 검증하지 않는 ID"* 가 된다. **oasdiff 예외와 같은 모양으로 만들었다**: `reason`과 추적 이슈([#233](https://github.com/yutakdv/Nullnull/issues/233))가 없으면 예외가 아니라 구멍이고, `validate_backend_plan.py`가 그 형태를 강제한다. 그리고 집계기가 게이트 로그에 `acceptance_ids_owned_elsewhere=`로 **찍는다** — 아무도 보지 않는 면제는 게이트가 조용히 질문을 그만두는 방식이다. 절은 카드에 그대로 남고 사람이 읽는 자리에 이유가 적혀 있다. 배선(`--e2e-junit-dir`, 집계를 e2e 뒤로)과 E2E가 서면 표시를 지우고 승격 조건으로 되돌린다 |
| A-035 | cursor가 **응답 유래 정렬 키**(여행 시작일·후보 저장 시각·장소 이름)를 나르는 것을 허용한다 — **단 access log가 query string을 남기지 않는다는 전제 위에서다** | 2026-09-14 결정(BA-027). base64url은 **은닉이 아니라 구분자 회피**다: cursor를 가진 사람은 내용을 그대로 읽는다. `BA-022-T2`가 지키는 opaque는 *"owner id와 검색어를 담지 않는다"* 이지 *"내용을 못 읽는다"* 가 아니며, 그것은 서명과 owner binding이 한다. 담기는 값은 **같은 응답이 방금 돌려준 것**뿐이라 새 정보가 아니고, 원문·좌표·owner id는 아니다. `listFeed`·`listTrips`·`listTripCandidates`의 cursor는 **query parameter**라 예전 정수와 달리 날짜·시각이 URL에 실리는데, `BA-070-T2`(`RedactionAndDenylistIT`)가 access log가 query string을 남기지 않음을 test로 고정하므로 받아들인다(`searchPlaces`는 read-only POST라 해당 없다). **뒤집히는 조건**: 그 test는 Spring 안쪽만 본다 — ALB·CloudFront·nginx의 access log는 query string을 그대로 적는다. **`BA-071`에서 ALB access log가 query string을 남기도록 설정되면 이 결정은 무효이고**, 그때는 cursor를 header로 옮기거나 payload를 암호화해야 한다. AWS 카드가 이 줄을 확인 항목으로 읽는다 |
| A-034 | `catalogVersion`과 `taxonomyVersion`은 **같은 개념이고 같은 값**이다 — catalog source의 **registry revision**을 `<sourceCode>:<revision>` 형식으로 쓴다(현재 `KTO_KOR_SERVICE_2:4`) | 2026-09-14 결정(오너가 계약·설계 결정을 이 세션에 위임). 둘 다 생산자가 0건이었고 유일한 값이 test fixture였다(`"catalog-1"`·`"taxonomy-test-1"`). 따로 정하면 **두 호출자가 서로 다른 판본 개념을 쓰게 되므로** 한 결정이다 — `RecommendationContext`의 javadoc이 이미 *"catalog/**taxonomy** version the candidate facts were hydrated from"* 으로 둘을 한 문장에 묶어 둔다. **새 상수나 열을 만들지 않는다**: registry revision은 **생산자가 이미 있고**(`CatalogRelationStore.currentRevision`이 운영 경로에서 읽힌다) crowd 쪽 `sourceRegistryVersions`와 대칭이며, fingerprint의 목적이 *"입력이 바뀌면 해시가 바뀐다"* 인데 **catalog 사실이 바뀌는 실제 계기가 revision**이다. 값을 새로 정하는 쪽은 `mapping_type`과 같은 자리가 되고 그것은 출처가 없을 때만 쓴다. `<sourceCode>:`를 앞에 두는 이유는 catalog source가 둘이 되는 날 숫자만으로는 무엇의 4인지 말할 수 없기 때문이다. **provider가 둘이 되면 place별로 읽어야 한다** — 어휘의 진짜 출처는 그 place가 수집된 `place_external_refs`의 `(source_code, source_registry_version)`이고, 오늘 한 값인 것은 ingest 경로가 KTO 하나이기 때문일 뿐이다(`KtoSnapshotCatalogIngest`가 `places.category_code`를 쓰는 유일한 production writer다). 둘째 provider가 생기는 날 place마다 값이 갈리고 그때 ranker의 `TAXONOMY_MISMATCH`가 실제로 발화한다. `4`를 상수로 굳히지 말고 `currentRevision()`으로 읽어라 — 다음 `V0xx`가 5로 올리면 하드코딩한 쪽만 낡는다(현재 값의 내력: `V008:49`가 2, `V009:5`가 3, `V012:8`이 4로 올렸다) |
| A-033 | **A-054가 2026-09-20에 이 결정을 대체했다 — Live는 제출 전에 전면 구현한다.** 아래는 그때의 기록이다. Live는 **제출에서 OFF**로 둔다. B10(`BA-090`·`BA-091`·`BA-092`)은 **나머지 P0가 끝난 뒤 마지막에** 다루며, **구현하지 않고 목업으로 처리할 수 있다** | 2026-09-14 오너 결정. `capabilities.live` 기본값이 이미 `false`이고 그 주석이 *"서버 측 source가 없으므로 ON은 startup을 실패시킨다"* 이므로 OFF는 코드 변경이 아니라 현 상태다. `SEOUL_CITYDATA`·`DEMO_REPLAY`의 `approval_state`를 올릴 필요가 없어지고, `SOURCE_CATALOG` §5가 고정해 둔 라이선스·출처 값도 registry로 옮길 시점이 미뤄진다. **`BA-092-T3`가 최종 P0 게이트라 B10을 목업으로 가면 그 절이 무엇을 검사하는지 다시 정해야 한다** — 목업 확정 시 카드 셋의 범위를 함께 고친다. 이 결정은 geolocation과 무관하다: 제출 profile의 위치 OFF는 `BA-093`(P1)이고 Live area 관측과 다른 것이다 |
| A-032 | 사람이 검토한 영업시간의 `stale_after_seconds`는 **P30D**로 둔다 | 2026-09-13 오너 결정, `BA-022`. 측정이 아니라 판단이며, 평가 기간이 한 달이라 사실상 만료가 걸리지 않는 값이다. 값을 늘릴 근거가 생기면 다시 정한다 |
| A-027 | KTO `detailIntro2`를 **1회 탐색 호출**하여 응답 shape를 관찰하는 것을 승인한다. **채택이 아니다** — `SOURCE_CATALOG`의 승인 범위는 `detailCommon2` 그대로이고, 채택은 응답을 본 뒤의 별도 결정이다 | 2026-09-13 오너 승인, #181. `usetime`·`restdate`가 자유 텍스트인지가 지금 **추측**이고, 그 추측이 SLOT·ITEM을 영구 `UNKNOWN`으로 둘지를 가른다. 개발 쿼터 1,000 중 1건이면 측정이 된다. 자유 텍스트로 판명되면 파싱하지 않고 큐레이션 영업시간으로 간다(불변식 9) |
| A-025 | inbound rate limiting은 edge에만 두고 application은 429를 발행하지 않는다. P0 제출 범위에 포함하지 않는다 | 2026-09-13 결정, #148과 D-033. 익명 전용 P0에서 owner 축 제한은 cookie를 버리면 우회되고, IP 축은 심사 환경의 공유 NAT에서 오탐이 크다. 심사위원을 막는 것이 데모의 최악 실패다 |
| A-024 | post 표지는 팀이 직접 만든 1st-party 자산만 쓰고 provider 사진을 재배포하지 않는다. `MediaAsset`은 `attributionRequired=false`·`redistributionAllowed=true`로 채운다. **표현 형식은 둘 중 하나다 — 팀이 직접 촬영한 사진, 또는 명시적 일러스트. 실제 장소를 사진처럼 묘사한 합성 이미지는 금지한다** | 2026-09-13 오너 결정(D-007의 post 절반), **2026-09-18 오너가 직접 촬영한 사진을 허용하도록 개정**, **2026-09-20 사용자가 업로드한 표지를 허용하도록 다시 개정**(`A-058`의 제출 범위). 사용자 업로드의 권리 경로는 `source_registry`의 새 source **`USER_UPLOAD`** 와 `asset_licenses` 행이고, `media_assets.asset_license_id`가 NOT NULL로 그것을 강제한다. **(2026-09-20 정정)** 이 줄은 처음에 업로더의 권리 확인을 `licenseAttested` 요청 필드로 적었으나 **그 필드는 계약에 들어가지 않았다** — 오너가 동의 체크박스를 배제한 뒤 client가 항상 `true`를 보내게 되어 **발화할 수 없는 값**이 됐기 때문이다(계약에서는 사용자 행위처럼 보이고 실제로는 모든 요청의 상수). **동의의 증거물은 작성 화면의 문구**([#312](https://github.com/yutakdv/Nullnull/issues/312))이고 스키마가 강제하는 것은 위 두 줄뿐이다 — 즉 권리 경로 없는 표지는 스키마가 받지 않는다. **팀이 직접 만든 자산만 쓴다는 절은 이 개정으로 좁혀진다**: 1st-party는 `NULLNULL_FIRST_PARTY`, 사용자 업로드는 `USER_UPLOAD`이고 **provider 사진 금지는 그대로다.** 합성 이미지 금지도 그대로이나 **사용자 업로드에 대해서는 기계가 강제하지 못한다** — 자동 기술 검증은 파일이 무엇인지만 보고 무엇을 담고 있는지는 보지 않는다(`A-058` 잔여 위험 1). provider 사진은 record별 공공누리 유형 심사가 필요하고 `PostSummary`에 credit 경로가 없어 계약 breaking이 된다 — 이 부분은 그대로다. 원래 문구가 *"사진처럼 묘사하지 않는 일러스트"* 로 형식을 좁혔던 이유는 **실사풍 합성**이 불변식 6의 합성·관측 구분을 깨기 때문인데, **직접 촬영한 사진은 합성이 아니라 관측이므로 그 이유가 적용되지 않는다.** 금지 대상은 *사진 형식*이 아니라 *실제 장소를 사진처럼 지어낸 이미지*다. `V021`의 주석과 `NULLNULL_FIRST_PARTY`의 `metric_definition`이 *"일러스트"* 만 적고 있으나 migration은 적용 후 고칠 수 없다(checksum 고정) — 살아 있는 정본은 이 줄이다 |

## 2. 열린 결정

결정되지 않은 항목은 아래 “안전한 기본값”으로 개발을 계속할 수 있지만, `필요 시점` 전에는 반드시 닫는다.

| ID | 질문 | DRI | 필요 시점 | 안전한 기본값 | 완료 증거 |
| --- | --- | --- | --- | --- | --- |
| D-003 | 개발 계정 쿼터의 단위(인증키별인가 활용신청별인가)와 KTO 이미지 재배포 조건은 무엇인가? production key는 **신청하지 않기로 확정**(2026-09-13 오너, PM-023)했고 제출은 개발 계정으로 간다 | BE/AI | 법정동코드 등 새 operation을 같은 키에 추가하기 전 | 쿼터 guard를 미리 조이지 않는다 — per-API가 맞을 때 용량 절반을 버린다. 이미지는 재배포하지 않고 post 표지는 1st-party만 쓴다(A-024) | 포털 마이페이지 활용신청 상세가 API별 트래픽을 따로 보이는지 확인. 실호출 증거는 확보됨(KTO smoke, `api_ingest_logs`·`collector_runs` COMPLETED). **2026-09-20 절반 답이 왔다**: 오너가 `EngService`(data.go.kr 15101753, 개발계정) 활용신청을 제출·승인받았는데 **키는 기존 `KTO_SERVICE_KEY` 에 통합된다** — 즉 **인증키는 계정 단위**이고 활용신청마다 새 키가 나오지 않는다(오너 확인, 슬롯 길이 64 무변화로 재확인). **그래서 남은 절반이 더 중요해졌다**: 쿼터가 **키 단위로 합산**되면 영문 호출이 **P0 흐름을 떠받치는 국문 쿼터를 먹는다.** 활용신청 단위로 따로 세면 무관하다. **확인 전에는 영문 수집을 상시로 돌리지 않는다** — 확인 방법은 위와 같다(마이페이지 활용신청 상세의 API별 트래픽). 부수로 확인된 것: 코드가 `/B551011/KorService2` 를 **상수로 고정**하므로(`KtoKorServiceProperties.DETAIL_OFFICIAL_PATH`) 영문은 같은 키에 **다른 경로**가 필요하고, `ProviderHostPolicy` 의 host 목록도 같이 본다 |
| D-005 | production RDS Multi-AZ/ECS 2 task 비용을 승인할 수 있는가? | 공동 | B08/최종 검수 | staging Single-AZ; 실제 사용자 출시 전 go/no-go | AWS calculator + downtime 기준 |
| D-006 | 오류 추적 SaaS를 추가할 것인가? | FE | B08/최종 검수 | CloudWatch와 client-safe event만 | 개인정보/DPA/비용 검토 |
| D-007 | POI/place 이미지·문구의 사용 권리가 확인됐는가? post 표지 절반은 A-024로 닫혔고 provider 사진 쪽만 남았다 | 공동 | B04 완료 | 직접 제작/공공누리 허용 자산만 | asset ledger와 license link |
| D-009 | 개인정보 처리방침상 최종 보존 기간은? | 공동 | B08/최종 검수 | 문서의 짧은 기술 기본값 | 공개 정책/삭제 test |
| D-010 | 두 팀원의 GitHub handle과 CODEOWNERS 경로는? | 공동 | B01 | CODEOWNERS 생성 보류 | branch protection reviewer 동작 |
| D-011 | icon export 방식과 visual diff는 무엇인가? **variable/token 쪽은 닫혔다** — `tokens.json`이 Figma local variables export(6 collection)이고 `tokens:check`가 `verify:ci` 첫 단계로 drift를 실패시킨다 | FE | FE-002 | 수동 수치 복제 금지 — 이제 기계가 강제한다 | icon export 경로(현재 `src/design/`에 icon 자산 0건)와 visual diff |
| D-014 | 사용자 삭제 시 최적화 감사 record를 얼마나 보존할 수 있는가? | BE/AI | B06 | trip 삭제와 함께 제거 | 개인정보/운영 합의 |
| D-016 | repository와 서비스 코드의 license는 무엇인가? | 공동 | 외부 기여/공개 배포 전 | 명시 license 없음, 재사용 허용을 가정하지 않음 | LICENSE 파일과 의존성 호환 검토 |
| D-019 | primary는 A-037로 확정됐다. secondary 수신자와 부재 escalation은 누구인가? | 공동 | staging 공개/B08/최종 검수 | primary만으로 인프라 bootstrap은 허용하되 release-ready 표시는 금지 | primary/secondary SNS 구독 확인과 두 사람 test alarm/tabletop |
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
| D-034 | LLM provider(OpenAI)를 **언제, 어떤 키와 월 상한으로** 켜는가? 도입 자체는 2026-09-20 오너가 **연다**로 결정했고 adapter는 마지막 wave다 | BE/AI | `BA-084②` 착수 전 | `AI_PROVIDER=NONE`. `apps/ai/src/nullnull_ai/settings.py`의 `ai_provider: Literal["NONE"]`이 `OPENAI`를 **startup에서 거부**하므로 adapter가 없는 동안 그 값이 조용히 template로 떨어지지 않는다(silent fallback 금지). 선호 해석·설명의 정본은 template이고 LLM이 실패해도 template이 답한다 | 오너가 준 **키와 월 상한 두 값**, `AI_MODEL_ID`·timeout·예산이 설정에 고정된 것, 그리고 외부 전송에 대해 불변식 9(LLM은 사실·경로·적용 가능성의 최종 판정자가 아니다)와 불변식 10(원문·정밀 위치 비전송)을 다시 증명하는 test. `BA-084①`의 거부·fallback·mutation 0 장치는 provider와 무관하게 먼저 선다 |

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
| R-039 | **`PostStatus` 가 Java 에 둘이고 값 집합이 다르다.** `social.domain.PostStatus`는 `{DRAFT, PUBLISHED, HIDDEN}`(DB `posts_status_check` 가 강제하는 것)이고 `recommendation.domain.feed.FeedCandidateIn.PostStatus`는 `{PUBLISHED, DRAFT, WITHDRAWN, DELETED}`(내부 계약 wire)다. **둘을 잇는 변환이 없다** | M | H | 지금은 무해하다 — `FeedService` 가 `rankFeed` 를 부르지 않으므로 변환이 일어나는 지점 자체가 없다. **`InternalContractParityTest` 는 이것을 볼 수 없다**: 그것은 계약 ↔ `recommendation` DTO 를 맞추고 둘은 일치한다. 어긋난 쌍은 `social` ↔ `recommendation` 이고 그건 계약 test 가 볼 이유가 없는 축이다 | **`rankFeed` 배선(P2 재정렬, 또는 ADR-0006 ①해석의 `BA-080`)을 시작하는 순간 이 행을 먼저 읽는다.** Spring 은 `WITHDRAWN`·`DELETED` 를 만들 수 없고(CHECK 가 막는다), Spring 이 실제로 만들고 *"보여주지 마라"* 를 뜻하는 **`HIDDEN` 은 계약 enum 에 없다** — 변환이 throw 하거나 조용히 다른 값으로 라벨링된다. 계약의 그 schema 는 *"One curated post as Spring sees it"* 라고 적는데 그 문장이 지금 참이 아니다 | BE/AI | M |

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

지도/경로 provider(A-051), 계정 로그인(A-052)은 2026-09-20에 결정됐으나 P1이므로 P0 blocker가 아니며 기능을 OFF로 유지한다. **게시물 작성은 같은 날 제출 범위로 들어왔다(A-058)** — moderation·미디어 입력·표지 권리 결정(A-053·A-057·A-024)이 그 구현 경계이고 자동 기술 검증을 통과하면 즉시 공개된다. P0 blocker는 아니지만 **OFF로 유지하는 목록에서는 빠진다.**

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
