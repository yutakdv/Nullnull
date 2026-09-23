# AWS staging operator scripts

구현·사용법·검증 결과·남은 문제의 현재 정본은 [AWS CD 인계 정리](../../AWS_CD_HANDOFF_SUMMARY.md)다.
실행 계약은 [staging 배포 실행 계약](../../docs/operations/STAGING_DEPLOYMENT_RUNBOOK.md)을 따른다.

- `staging-iam.py`: IAM 정책 3개(`infra/iam/*.json`)와 operator role을 plan → 승인 hash → execute로 적용한다(IAM 권한이 있는 프로필로 1회).
- `staging_operator.py`: `bootstrap`·`deploy`·`rollback`은 로컬 합성 plan을 만들고, `--plan`·`--approved-plan-sha256`·`--execute`가 모두 있어야 실행한다. `classify`·`secrets`·`task`·`unlock`은 보조 명령이다.
- 인증은 `NULLNULL_AWS_AUTH=profile`(로컬 operator role)과 `ambient`(GitHub OIDC 임시 자격 증명) 두 가지다. 각 모드는 정해진 role로만 실행된다.
- 배포와 rollback은 edge를 닫은 상태(`TrafficEnabled=false`)로 끝난다. verifier token 경로로만 API를 검증한다.
- 실패·timeout 뒤의 공유 잠금은 자동으로 탈취하지 않는다. `unlock`은 CloudFormation·ECS 종료 상태를 확인한 뒤에만 푼다.
- `ci_reconcile.py`는 GitHub 쪽 gate(필수 CI가 정확히 이 SHA에서 성공했는가, reconciler 판단)다.

## 서울 프록시 인증키 입력 (#334)

`python3 scripts/aws/staging_operator.py secrets --seoul`은 ignored `apps/api/.env.local`의
`SEOUL_API_KEY`를 읽어 `nullnull-stg/seoul-proxy` JSON의 `apiKey`만 갱신한다.
`proxyToken`과 다른 필드는 보존하고, 같은 키면 새 secret version을 만들지 않는다.
빈 값·손상된 JSON·누락되거나 잘못된 토큰은 덮어쓰지 않고 거절한다.
키 값은 명령행 인자로 넘기지 않으며 결과에는 변경 여부만 출력된다.
옵션 없는 `secrets`는 기존처럼 KTO 키만 갱신한다.

로컬 operator role과 해당 secret의 `secretsmanager:GetSecretValue`·`PutSecretValue` 권한이 필요하다.
이 명령은 IAM 권한을 추가하지 않는다. `AccessDenied` 상태에서는 운영자가 적절한 권한을 준비해야 한다.
프록시의 기존 secret 캐시 갱신은 최대 5분 걸린다. API를 재시작하거나 공개 트래픽을 닫을 필요는 없다.
키 입력 자체는 수집 성공 증거가 아니다. 정상 응답을 확인한 뒤, 이미 격리된 source는
`release-source-quarantine --source-code SEOUL_CITYDATA` 작업으로 검토 기록을 남겨 해제하고
`seoul-live-collect --area-name '서울숲공원'` 작업의 `seoul_live_collect live=true`를 확인한다.
두 작업의 DB 대상·승인 변수는 위 배포 실행 계약을 따른다.
