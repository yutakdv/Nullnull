# ADR-0005: AWS 환경 경계와 immutable release

- 상태: Accepted baseline
- 날짜: 2026-09-04

## Context

2인 팀이 AWS에 mobile web, Spring API, PostgreSQL과 외부 data collector를 배포한다. staging/production 권한 혼용, 동시에 실행되는 migration, stateful CDK 삭제와 다시 build한 artifact는 작은 팀에서도 복구 불가능한 사고를 만들 수 있다.

## Decision

- production은 전용 AWS account를 권장한다. 불가능하면 staging과 role/VPC/KMS/secret/stack을 완전히 분리하고 예외를 기록한다.
- CDK stack은 Foundation, Network, Data, Api, WebEdge, Observability 경계로 나누고 stateful Data/Web version/audit resource는 retain/snapshot한다.
- GitHub Actions는 environment별 OIDC role만 사용하고 장기 access key를 저장하지 않는다. trust는 정확한 repository/environment/ref subject로 제한한다.
- staging과 production deploy concurrency는 각각 1이다. production과 migration은 진행 중 run을 취소하지 않는다.
- staging에서 검증한 API image digest와 web artifact checksum을 production으로 그대로 승격한다.
- Semantic Version tag와 release manifest에 source, contract, migration, CDK, artifact digest를 연결한다.
- production은 Frontend 담당과 Backend/AI 담당의 분리된 deployer/approver 또는 observer 역할을 요구한다.
- drift, budget, removal policy, alarm 수신과 rollback rehearsal을 production gate로 둔다.
- 모든 역할 브랜치 PR은 Docker 통합을 거치고, 공모전 release는 익명 외부망·실제 KTO 호출/출처·위치 OFF·공식 PDF 정합성을 추가 gate로 둔다.

## Consequences

- 초기 account/stack/OIDC 설정과 artifact storage 비용이 발생한다.
- 동일 artifact 승격으로 environment별 build-time 값은 최소화하고 runtime/config manifest를 엄격히 분리해야 한다.
- 긴급 console 변경도 incident와 후속 IaC PR이 필요하다.
- production account 분리가 어려우면 출시 전 명시적 위험 수용 결정이 필요하다.

## Rejected alternatives

- staging/production 한 deploy role과 secret 공유: 오배포와 권한 확산 위험이 크다.
- access key를 GitHub secret에 저장: 장기 credential rotation·노출 위험이 있다.
- environment별 재build: 같은 tag가 다른 code/dependency artifact를 가리킬 수 있다.
- stateful resource를 stack destroy 기본값에 맡김: DB/web rollback/evidence 손실 위험이 있다.
- concurrent production deploy: migration/task/web version의 조합을 증명하기 어렵다.

## Review trigger

- AWS Organization/account 구조 또는 domain/region이 바뀜
- 월 비용이 승인 budget 80%를 두 달 연속 넘음
- RTO/RPO rehearsal이 목표를 달성하지 못함
- ECS/Fargate/ALB/NAT 비용이나 SLO가 대안 대비 지속적으로 불리함
- OIDC/IAM drift 또는 배포·artifact 불일치 incident가 발생함
- multi-region, queue/worker 분리 또는 blue/green 배포가 필요해짐

검토 DRI는 Backend/AI 담당이며 Frontend 담당의 release/rollback 검증과 공동 승인이 필요하다. INF-001, M6, production 구조 변경과 위 trigger 발생 시 검토한다.
