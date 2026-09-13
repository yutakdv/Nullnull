---
aliases:
  - "최소 staging 착수 계획"
doc_type: decision
status: proposed
area: operations
tags:
  - nullnull/decision
  - nullnull/operations
---

# 최소 staging 착수 계획 (승인 대기)

- 상태: **제안**. 오너가 이 문서 하나를 읽고 승인/반려할 수 있도록 쓴다.
- 범위: [AWS_DEPLOYMENT](AWS_DEPLOYMENT.md)의 목표 구조 중 **지금 켜야 하는 최소 부분**만.
- 이 계획으로 **AWS에 아무것도 만들지 않았다.** 계정 생성·리소스 생성·비용 발생은 전부 승인 뒤다.
- 연결: [BA-006](../roles/BACKEND_AI_PLAYBOOK.md#ba-006)(`blocked`) · [열린 결정](../project/DECISIONS_AND_RISKS.md#2-열린-결정) D-001·D-017·D-018·D-019·D-023.

## 1. 왜 지금인가 — 배포가 잠그고 있는 것

배포는 "모든 작업이 끝난 뒤의 마지막 단계"로 계획돼 있었다. 그 전제가 더 이상 성립하지 않는 이유는 일정이 아니라 **의존 방향**이다.

```text
staging 배포 → BA-021-T3(staging 실호출 증거) → NULLNULL_CATALOG_PUBLIC_ENABLED 해제
             → 장소·feed·여행 투영이 실제로 응답 → 제출 screenshot(CMP-SUB-007)
```

- `CMP-SUB-004`(**REQUIRED**)는 외부에서 접속 가능한 웹 URL을 요구하고, 증거는 *"외부망·새 browser profile HTTPS smoke"* 다.
- `CMP-SUB-007`(**REQUIRED**)은 대표 1장 + 상세 3~5장을 **실제 배포 화면**에서 요구한다. mock은 제외다.
- 공식 마감은 `2026-09-21 16:00 KST`다([SUBMISSION_RUNBOOK](../contest/SUBMISSION_RUNBOOK.md)).

지금 `BA-022`·`BA-030`·`BA-031`·`BA-032`·`BA-034`가 전부 `integration-ready`인데 **catalog 공개 게이트가 닫혀 있어 화면으로 보여줄 수가 없다.** 그 게이트를 여는 조건이 staging 실호출 증거이므로, 배포를 미루는 것은 **제출 산출물 두 개를 미루는 것**과 같다.

그리고 **`BA-006`은 45개 카드 중 유일한 `blocked`이고 phase가 B01**이다 — 가장 앞 단계 하나가 막힌 채로 그 뒤가 쌓여 있다.

**수를 세어 보면 이렇다: 공식 `REQUIRED`·`EXCLUSION` 18행 중 11행의 증거가 배포된 URL을 전제한다.** `CMP-SUB-004`·`005`·`007`·`008`·`009`, `CMP-KTO-001`·`002`·`003`·`006`, `CMP-ATT-001`, `CMP-ACC-001`이다(각 행의 증거 칸을 직접 확인했다 — 외부망 smoke, 실제 배포 화면, release call-audit, 배포 화면 DOM coverage, 외부망 judge journey). **§4의 결정 세 건이 그 11행을 푼다.**

## 1.1 범위 축소는 규칙 위반이 아니다

`SUBMISSION_RUNBOOK`이 요구하는 staging 완결 항목 중 `INT-03`(BA-040)은 착수 전이고 `INT-04`(BA-050~053)는 전부 `planned`다. 남은 기간에 전부를 넣는 것은 어렵다.

그런데 매트릭스 §9가 출구를 적어 뒀다 — *"미구현 기능은 제거하거나 명확한 `준비 중`/capability OFF 상태로 두고 기능설명서에서 제외한다."* 즉 **범위를 줄이는 것은 위반이 아니고, 미구현을 구현된 것처럼 적는 것이 위반이다**(`CMP-SUB-008`). 배포 승인과 함께 **무엇을 제출 범위에 넣을지**를 같이 정하면, 남은 기간을 "전부"가 아니라 "보여줄 것"에 쓸 수 있다.

## 2. 최소 구성 — 완성품이 아니라 "URL이 열리고 KTO를 실호출한다"

| 구성요소 | 최소 staging | 목표 구조와 다른 점 |
| --- | --- | --- |
| DNS/TLS | Route 53 + ACM 인증서 1장 | 동일 |
| Edge | CloudFront 1개, behavior 둘: 기본 → S3, `/api/*` → ALB | WAF는 **뒤로 미룬다**(§5) |
| Web | private S3 + OAC, `apps/web` 빌드 산출물 | 동일 |
| API | ALB + ECS Fargate `api` **task 1개** | production은 2개 이상 |
| 추천 | ECS Fargate `ai` **task 1개**, ALB target 아님 | 동일(내부 DNS만) |
| DB | RDS PostgreSQL **Single-AZ, 최소 인스턴스**, 자동 backup 7일 | production Multi-AZ |
| Secret | Secrets Manager(`KTO_SERVICE_KEY` 등) | 동일 |
| 배포 | GitHub OIDC → ECR push → ECS 갱신 | 동일 |

### 2.1 CloudFront는 비용 절감 대상이 아니라 기능 요구다

"FE는 정적 호스팅, API는 ALB"로 나누면 **origin이 달라진다.** 그 순간 session cookie가 깨진다 — `SessionProperties.cookieName()`이 secure일 때 `__Host-` 접두사를 붙이는데, `__Host-`는 `Domain` 속성을 금지하고 path `/`를 요구하므로 **cross-origin으로는 보낼 수 없다.** 그리고 CSRF/Origin 정책과 불변식 11(client가 보낸 owner를 믿지 않고 cookie session에서 유도)이 그 cookie 위에 서 있다.

즉 **same-origin은 선택이 아니라 계약의 전제**다. CloudFront 하나로 두 behavior를 묶는 것이 그것을 만족시키는 가장 싼 방법이고, 이미 목표 구조가 그 모양이다.

### 2.2 NAT gateway를 쓰지 않는 것을 제안한다 (staging 한정)

목표 구조는 task를 private subnet에 두고 NAT로 egress한다. **staging에서는 NAT를 만들지 않고** task를 public subnet에 `assignPublicIp=ENABLED`로 두는 것을 제안한다.

- **이유:** 이 구성에서 NAT gateway는 시간당 요금과 데이터 처리 요금을 **상시** 발생시키는 항목이고, staging이 실제로 필요로 하는 egress는 KTO 호출뿐이다.
- **안전 조건(반드시 함께):** api task의 security group inbound는 **ALB SG에서만** 허용한다. `ai` task는 inbound를 api SG에서만 허용하고 외부 ingress가 없다. RDS는 isolated subnet + public access false를 유지한다.
- **기록:** 이것은 목표 구조와의 **의도된 parity 차이**다. production으로 그대로 올리지 않는다. 승인되면 `AWS_DEPLOYMENT.md` §2에 차이로 적는다.
- 반려해도 계획은 성립한다. NAT를 쓰면 비용 항목이 하나 늘 뿐이다.

## 3. 비용 — 금액이 아니라 **소비 모형**을 먼저 확정한다

`D-018`은 *"staging 월 비용 상한과 운영 시간"* 을 묻는다. 상한을 정하려면 무엇이 시간당 돈을 쓰는지가 먼저다. **수량은 아래가 정확하고, 단가는 이 세션에서 확인하지 못했다.**

**검증 생략: AWS 공개 요금 페이지가 지역별 단가 표를 렌더하지 않아 세션에서 단가를 읽지 못했다.** 숫자를 지어내지 않기 위해 단가 칸을 비워 둔다. 오너가 AWS 요금 계산기(ap-northeast-2)에서 아래 수량을 그대로 넣으면 총액이 나온다.

| 항목 | 상시 운영 시 수량(월) | 단가 | 비고 |
| --- | --- | --- | --- |
| ECS Fargate `api` | 0.25 vCPU · 0.5 GB × 720h | | 측정 후 조정 |
| ECS Fargate `ai` | 0.25 vCPU · 0.5 GB × 720h | | 목표 구조가 정한 시작 크기 |
| ALB | 1개 × 720h + LCU | | LCU는 트래픽 미미 |
| RDS PostgreSQL | 최소 인스턴스 × 720h + storage 20GB | | Single-AZ |
| CloudFront | 요청·전송 수 GB | | 심사 트래픽 규모 |
| ECR | image 약 1GB | | |
| Secrets Manager | secret 3~5개 | | |
| Route 53 | hosted zone 1개 | | |
| **NAT gateway** | **0개(제안) 또는 1개 × 720h + 처리량** | | §2.2 |

**가장 큰 레버는 인스턴스 크기가 아니라 운영 시간이다.** 그런데 여기 함정이 하나 있다 — `D-018`의 안전한 기본값은 *"무제한 상시 운영 금지"* 인데, `CMP-SUB-004`는 **심사 기간에 URL이 살아 있을 것**을 요구한다. 둘은 같이 성립하지 않으므로 구간을 나눠 승인해야 한다.

| 구간 | 기간 | 운영 | 이유 |
| --- | --- | --- | --- |
| A. 착수·증거 | 승인 ~ 제출 전 | **작업 시간대만** (야간·주말 정지) | BA-021-T3 증거와 screenshot을 만드는 데 상시가 필요 없다 |
| B. 제출~심사 | 제출 ~ 심사 종료 | **상시** | `CMP-SUB-004`의 URL이 죽어 있으면 REQUIRED 미충족 |

**승인이 필요한 한 줄:** 구간 B 기준 월 상한 금액. 구간 A는 그 이하로만 쓴다.

## 4. 배포 전에 닫아야 하는 결정 — 각 한 줄로 답할 수 있다

| ID | 질문 | 답이 없으면 | 제안 기본값 |
| --- | --- | --- | --- |
| `D-001` | 서비스 domain은 무엇인가 | ACM 인증서도 CloudFront alias도 만들 수 없다 | 보유 domain의 하위 도메인 하나(예: staging 전용). 없으면 구매 필요 |
| `D-017` | staging/production AWS account를 분리하는가 | stack 경계와 IAM 설계가 갈린다 | **단일 account + stack/role/secret prefix 완전 분리**. 8일 안에 조직 분리는 비용 대비 얻는 것이 적다 |
| `D-018` | 월 비용 상한과 운영 시간 | 착수 자체가 막힌다(`INF-001 전`) | §3의 구간 A/B와 상한 금액 |
| `D-019` | alarm·incident 실제 수신자 | *"contact 없으면 production 금지"* | staging은 오너 본인 이메일 1개로 시작. Budget 50/80/100% 알림 포함 |
| `D-023` | security/privacy 외부 escalation 책임자 | *"contact 없으면 production 금지"* | staging 범위에서는 **불필요**. production 승격 전에 답한다 |

`D-023`은 staging을 막지 않는다는 점을 분명히 한다 — 문구가 `production 금지`이고 이 계획은 production이 아니다.

## 5. 하지 않는 것

- production 환경 전체. 이 계획은 staging 하나다.
- Multi-AZ RDS, task 2개 이상, autoscaling. 트래픽이 아니라 **허용 downtime**으로 정하는 값이고 staging은 downtime을 허용한다.
- WAF. 공개 URL이지만 심사용이고, 규칙 없이 켜면 비용만 늘고 오탐으로 심사를 막을 수 있다. production 승격 조건에 남긴다.
- 알람 SaaS, Cost Anomaly Detection, performance insights. Budget 알림만 켠다.
- `D-005`(최종 검수 항목)은 지금이 아니다.

## 6. BA-021-T3까지 — local runbook과 무엇이 다른가

로컬 3단계 smoke는 [ENVIRONMENT](ENVIRONMENT.md) §7에 이미 있다(`ktoSmoke` → `ktoCanonicalIngest` → `ktoForecastSmoke`). staging에서 달라지는 것만 적는다.

1. **승인 변수를 파일에 두지 않는다.** `NULLNULL_KTO_SMOKE_APPROVED`는 `.env.local`에서 읽히지 않도록 설계돼 있다 — 승인은 명령을 실행하는 사람의 shell이 갖는다. staging에서는 **one-off ECS task의 실행자**가 그 자리다.
2. **0단계(포트 신원 확인)가 다른 것으로 바뀐다.** 로컬에서는 *"`.env.local`이 가리키는 포트를 그 container가 publish하는가"* 였다. staging에서는 *"task가 붙은 RDS endpoint가 이 stack의 것인가"* 다. 같은 질문의 다른 형태이고, **명령이 성공한 것과 의도한 대상에 성공한 것은 다르다**는 규칙은 그대로다.
3. **증거의 위치가 다르다.** 로컬 실행은 `api_ingest_logs`에 남고 그것으로 끝이지만, `BA-021-T3`은 *staging 성공 이력 + 공개 응답 provenance*를 요구한다. 즉 smoke가 성공한 뒤 **공개 endpoint의 응답에 그 provenance가 실려 나오는 것**까지 확인해야 한다.
4. **게이트를 여는 순서.** `NULLNULL_CATALOG_PUBLIC_ENABLED`는 증거를 만든 **뒤에** 켠다. 먼저 켜면 fail-closed 설계가 무의미해진다.

## 7. 승인받고 싶은 것 (한 번에)

1. §2의 최소 구성으로 staging을 만든다 — 예/아니오.
2. §2.2의 NAT 미사용(staging 한정, parity 차이 기록) — 예/아니오.
3. §3의 구간 A/B 운영과 **월 상한 금액** — 금액.
4. §4의 `D-001` domain, `D-017` account 분리 여부, `D-019` 수신 이메일.

승인되면 다음 순서로 간다: **`CMP-KTO-003` 차단 게이트**(아래) → `infra/` CDK scaffold(INF-001) → 계정/OIDC → network·data → api·ai → edge → smoke → `BA-021-T3` 증거 → 게이트 해제.

게이트를 맨 앞에 두는 이유: `CMP-KTO-003`은 **EXCLUSION**(파일 데이터만 쓴 것은 필수 활용으로 불인정)이고 매트릭스가 *"actual-call 없는 release를 배포/제출 차단하는 test"* 를 증거로 적는데 **그 test가 저장소에 없다.** 배포 전에 만들면 게이트가 이미 있는 상태로 배포가 열리고, 배포 후에 만들면 그 사이 release는 검사를 받지 않는다. 이것은 배포 승인을 기다리지 않으므로 먼저 시작한다. 각 단계는 별도 PR이고, **AWS 리소스를 실제로 만드는 단계는 그 단계의 승인을 다시 받는다.**
