# AWS staging operator scripts

구현·사용법·검증 결과·남은 문제의 현재 정본은 [AWS CD 인계 정리](../../AWS_CD_HANDOFF_SUMMARY.md)다.
실행 계약은 [staging 배포 실행 계약](../../docs/operations/STAGING_DEPLOYMENT_RUNBOOK.md)을 따른다.

- `staging-iam.py`: IAM 정책 3개(`infra/iam/*.json`)와 operator role을 plan → 승인 hash → execute로 적용한다(IAM 권한이 있는 프로필로 1회).
- `staging_operator.py`: `bootstrap`·`deploy`·`rollback`은 로컬 합성 plan을 만들고, `--plan`·`--approved-plan-sha256`·`--execute`가 모두 있어야 실행한다. `classify`·`secrets`·`task`·`unlock`은 보조 명령이다.
- 인증은 `NULLNULL_AWS_AUTH=profile`(로컬 operator role)과 `ambient`(GitHub OIDC 임시 자격 증명) 두 가지다. 각 모드는 정해진 role로만 실행된다.
- 배포와 rollback은 edge를 닫은 상태(`TrafficEnabled=false`)로 끝난다. verifier token 경로로만 API를 검증한다.
- 실패·timeout 뒤의 공유 잠금은 자동으로 탈취하지 않는다. `unlock`은 CloudFormation·ECS 종료 상태를 확인한 뒤에만 푼다.
- `ci_reconcile.py`는 GitHub 쪽 gate(필수 CI가 정확히 이 SHA에서 성공했는가, reconciler 판단)다.
