# AWS staging · CD 인계 정리

작성 2026-09-19, 대상 저장소 `/Users/yutak/Desktop/Nullnull`(branch `backend`). 오너 지시에 따라 커밋·push·PR·merge는 하지 않았고 production은 건드리지 않았다.
이전 배포 문서(`AWS_IMPLEMENTATION_SUMMARY.md`, `docs/operations/AWS_CREDENTIAL_READY_DESIGN.md`, `docs/operations/ASTRA_AWS_FINAL_REVIEW_PROMPT.md`)는 오너 요청으로 정리했다. 아직 유효한 내용(운영 기간 결정, 삭제 원장 요건)은 runbook §9·§10으로 옮겼다. 계정 번호는 `****6013`으로 가린다.

## 1. 상태 한눈에

| 구분 | 상태 |
| --- | --- |
| 구현됨 | CDK 9개 stack, 운영 스크립트(OIDC 호환·분류·검토 diff·잠금·rollback·ops task·쓰기 대상 DB 확인), IAM 정책 5개와 operator trust 문서, CDK bootstrap 사용자 정의 template, CD workflow 2개와 pinned AWS CLI action |
| 로컬 검증됨 | `scripts/tests` 414개(작업 트리 기준), infra 21개, 새 가드 변이 검사, 배포 후보 이미지 로컬 리허설(§7) |
| 실제 배포됨(AWS) | 9개 stack과 release `v0.1.0-rc.1001`. <https://d54awmnmi4c3z.cloudfront.net>은 `DEPLOYED_EDGE_CLOSED` 상태다(웹은 공개, API는 verifier token 요청만 통과) |
| 실제 반영됨(GitHub) | environment `staging-build`·`staging`·`staging-infra`(셋 다 `main` branch만, reviewer는 `staging-infra`만)와 secret, repository variable `STAGING_AUTO_DEPLOY=false` |
| CD 실행 검증됨 | 아니오. workflow가 `main`에 없어 Actions에서 실행할 수 없다. **CD는 활성화되지 않았다** |
| KTO 실제 호출 | 경복궁(126508/12) 1곳을 실제로 호출하고 적재했다(2026-09-19 05:29Z, 오너가 직접 실행). CMP-KTO-003 증거가 `verified`다(rc.1001). catalog 공개는 아직 꺼져 있다(`SOURCE_UNAVAILABLE`) |

## 2. 배포된 release

| 항목 | 값 |
| --- | --- |
| URL | <https://d54awmnmi4c3z.cloudfront.net> |
| release | `v0.1.0-rc.1001`, build `local-20260919-925b2c0-2` |
| source | `main` `925b2c07d08bc310193bdd4f5519c4211f71ca00` + 로컬 overlay 58파일(`sha256:ff9b2353c9e83f8379553c87bd6936d41d53db12b66828d29dbfe84554ff21e8`) |
| 필수 CI | 같은 SHA의 `docs-contract` [35379186870](https://github.com/yutakdv/Nullnull/actions/runs/35379186870)·`docker-integration` [35379189880](https://github.com/yutakdv/Nullnull/actions/runs/35379189880) 둘 다 success |
| API image | `sha256:ed8da1c2702950df0238d95a8283e2fbf3f1f6e803132ad85bc506c4c4ae7ebb` |
| AI image | `sha256:b656fe262e3392e5bd1c945e46b3c2f7f14feb96ace9e78f3e2f1a87daa97203` |
| web artifact | `sha256:c5433ae79cb605de6e3c389294a1adb9f76f0a3abbdf7e94c4a34587e34d050b` |
| migration | Flyway 34개, catalog version `KTO_KOR_SERVICE_2:4` |
| 승인한 plan | `437a73077547b936f5409cdb275bc223287ee0e4ac35b1fa89831a6682fba574`(분류 `infra`) |
| 배포 기록 | release bucket의 `releases/437a73077547b936f5409cdb275bc223287ee0e4ac35b1fa89831a6682fba574/plan.tgz` |

overlay는 GitHub에 없는 로컬 파일이다. 그래서 이 release는 어느 커밋으로도 재현되지 않는다. 오너가 §9의 파일을 커밋한 뒤의 release부터 `sourceState=clean`이 된다. 그 전에는 이 release로 되돌리는 rollback이 reviewer 경로로 분류된다.

## 3. 배포 뒤 검증 (CloudFront를 거친 실제 호출)

| 검증 | 결과 |
| --- | --- |
| `staging-smoke.sh` | `staging_smoke=pass public_https=true public_api_edge=closed verifier_path_checked=true alb_internal=true s3_private=true rds_private_multi_az=true`. GitHub role trust가 deploy 2개·publish 1개 subject와 정확히 일치한다 |
| `staging-flows.mjs` | 23/23 통과. 웹 index·SPA route·HSTS, 닫힌 edge 503, ready(DB `READY`)와 AI 도달, 외부 origin 403, session cookie 속성, CSRF 없는 쓰기 403, trip 생성·조회·수정·삭제, 낡은 `If-Match` 409, catalog 닫힘 503 `SOURCE_UNAVAILABLE`, session 삭제 202 뒤 401 |
| 웹 응답 헤더 | HSTS·`X-Frame-Options`·`Referrer-Policy`·`X-Content-Type-Options`가 있다. **CSP와 `Permissions-Policy`는 없다**(§8) |
| toolkit 재적용 뒤 smoke | 다시 pass |
| KTO ktoSmoke(경복궁, 오너 실행) | `KTO_SMOKE_OK` snapshot `1da6c26e-8374-420c-a5e8-ebb3744ddc10`, payloadHash `3a59a84a…`. `check_actual_call_evidence.py --require-verified`를 통과했다(`actual_call=verified release=v0.1.0-rc.1001`). release bucket `evidence/actual-call-v0.1.0-rc.1001.json`(612B)이 로컬 파일과 같은 크기다 |
| KTO canonical ingest(경복궁, 오너 실행) | `KTO_CANONICAL_INGEST_OK` place `01a0b825-4f15-7e7b-b30c-87cf71861c9c`. smoke와 같은 snapshot을 썼다. 두 task 뒤 배포 잠금은 풀려 있다 |

사용자 흐름 검증(23/23)은 KTO 호출 전, catalog가 닫힌 상태에서 했다.

## 4. AWS 쓰기와 승인

| 쓰기 | 오너 승인(세션 안에서 직접) | 결과 |
| --- | --- | --- |
| IAM 정책·operator role | "승인 — 실행"(plan `60807de8…`, 크기 초과로 실패) 뒤 "승인 — 새 plan 적용"(plan `5551b33f8afc6c515c2ae1b0ac63aafac42c4192dcb772cfd06866776e25d4ee`) | RoleBoundary v2, CfnExecution v2, CfnExecutionNetworkGuards v1, CfnExecutionServiceGuards v1, Operator v2 |
| CDK toolkit 사용자 정의 template(서울·us-east-1), 후보 이미지 push | IAM과 같은 "승인 — 실행" | toolkit 교체, ECR 이미지 2개 |
| 첫 runtime 배포 | "승인 — 배포 실행"(plan `543a6e23de4029c3e2e1253179fc1f091a6e82fbdeecef01874b98b595748bce`) | 세 번 실패했다(§5). 그 사이 7개 stack이 만들어지고 Foundation이 갱신됐으며, Services 생성은 실패했다 |
| 배포(Migration 갱신, Services 생성) | "승인 — 실행"(plan `437a73077547b936f5409cdb275bc223287ee0e4ac35b1fa89831a6682fba574`) | 성공(`deployment_action=executed state=DEPLOYED_EDGE_CLOSED kind=infra`) |
| toolkit variant 고정(template `f50fe3c6ad87c9fc5050fc6fde35bbd923f67b9a881af736b46b4440eb4b78a9`) | "배포 뒤 고침". hash를 먼저 보이고 적용한다는 조건이었다 | 적용했다. **hash는 적용한 뒤에 보고했다**. 두 region의 live template이 이 파일과 구조가 같고, `BootstrapVariant`도 우리 값이다 |
| operator 정책에 alarm topic 한정 SNS 권한(목록·발송·email 구독) | "승인 — 적용 후 구독"(plan `3d8ce0e0e2d96918b7db2887f6886a00ac43a498846374a6f64ede9b56c7f5cd`) | Operator v3. 나머지 정책 4개와 role은 바뀌지 않았다 |
| alarm 이메일 구독 요청 | "구독 요청" | primary 1건이 `PendingConfirmation`이다. 오너가 메일의 확인 링크를 눌러야 alarm이 전달된다 |

## 5. 배포 중 실패와 수정

1. IAM `create-policy-version`이 거부됐다(정책 13,981자 > managed policy 한도 6,144자). 정책을 셋으로 나누고, `staging-iam.py`가 plan 단계에서 크기를 막게 했다. 새 plan은 오너가 다시 승인했다.
2. `staging-iam.py` execute가 정책 이름 3개를 하드코딩하고 있어 guard 정책 2개가 만들어지지 않았다. plan의 정책 집합 전체를 돌도록 고쳤고, 같은 승인 plan을 다시 실행해 생성을 확인했다.
3. toolkit variant: CloudFormation이 parameter만 바뀐 update를 no-op으로 처리해 variant가 표준으로 남았다. 그래서 표준 template으로 bootstrap해도 CLI가 막지 않았다. SSM parameter 설명이 variant를 참조하게 template을 고쳤고, 배포 뒤 적용했다. 이제 `--force` 없는 표준 bootstrap은 CLI가 거부한다(pinned CLI 소스로 확인).
4. 배포 1회차: ECS `describe-task-definition`에 PascalCase `TaskDefinition`을 넘겨 CLI가 거부했다. AWS 호출 전체의 parameter 이름을 `--generate-cli-skeleton`과 대조했고, 서비스별 대소문자를 AST 검사로 고정했다.
5. 배포 2회차: CDK CLI가 검증을 마친 assembly 안에 `.cache/*.zip`을 써서 digest 검사가 `assembly-changed`로 멈췄다. 이제 검증한 사본(임시 디렉터리)으로 배포한다.
6. 배포 3회차: Services가 circuit breaker로 실패했다. Tomcat이 `/tmp`에 tempDir을 만들지 못했기 때문이다. Fargate bind mount는 root 소유 0755이고 image는 uid 999이며 root filesystem은 read-only다. API task와 ops task에 init container(`tmp-permissions`)를 넣었다. root로 `chmod 1777 /tmp`를 하고, 성공해야 app이 시작한다. 로컬 리허설이 `--tmpfs /tmp`로 이 차이를 가리고 있었다. Fargate처럼 root 소유의 빈 volume(`volume-nocopy`)으로 실패를 재현했고, 수정 뒤 흐름 18/18을 확인했다. 그다음 같은 image digest로 새 release `rc.1001`을 만들어 배포했다.

## 6. 무엇을 바꿨고 왜

첫 적대적 검토는 **block**(F1~F13)이었고 아래와 같이 고쳤다. 두 번째 독립 검토는 **fix-then-ship**이었다. F2~F8·F10·F11은 실행으로 확인돼 고쳤고, 첫 infra 배포를 새 Deny가 막는 경로와 새 유출 경로는 찾지 못했다. 그 뒤 실제 배포에서도 새 Deny는 우리 자원을 막지 않았다. 추가 지적 가운데 N4(role 전달 서비스 제한)와 N5(`classify` 분기 test)는 고쳤다. N1(`environment.deployment` 문법)은 actionlint 1.7.12 schema와 GitHub 문서 원문으로 유효함을 확인했다. N2·N3는 §8의 잔여로 남긴다.

### 권한 상한

- `infra/iam/cfn-execution*.json`(3개, execution role에 함께 붙는다)
  - 서울·us-east-1 밖 요청을 거부한다.
  - IAM 쓰기는 CDK가 만드는 role(`NullnullStg*`, `nullnull-stg-github-*`)에만 허용한다. 손으로 만든 operator role은 CloudFormation으로 바꿀 수 없다.
  - `Project=Nullnull` 태그가 없는 자원의 보안그룹 규칙·route·subnet 연결·gateway 부착과 RDS/ALB/CloudFront/WAF/Cloud Map 변경을 거부한다. 태그는 생성할 때만 붙일 수 있어 남의 자원을 Nullnull로 재태그할 수 없다.
  - 스냅샷 공유·복사·복원·export, 인스턴스·볼륨, traffic mirroring, VPC peering은 조건 없이 거부한다.
- `infra/bootstrap/nnstg-bootstrap.yaml`
  - 표준 CDK deploy role은 계정의 **모든** stack을 그 stack에 저장된 role로 바꾸거나 지울 수 있다. 이 template은 쓰기를 `NullnullStg*` stack으로 좁히고, resource import와 toolkit stack 자체 변경을 막는다.
  - pinned CLI의 template에 `infra/bootstrap/customize.mjs`의 편집만 더한 것이며, test가 이 동일성을 고정한다.
- CDK lookup role은 계정 전체 `ReadOnlyAccess`라서 GitHub deploy role과 app role 모두 deploy·file-publishing role만 assume하도록 좁혔다(`infra/src/staging.ts`, `infra/iam/role-boundary.json`).

### 운영 스크립트 `scripts/aws/staging_operator.py`

- F2: CI rollback이 이번 run의 SHA를 기록된 release의 SHA와 비교해 항상 실패하던 것을 고쳤다.
- F5: 분류 정규화를 구조 기반으로 바꿨다. task definition image의 `@sha256:` 꼬리와 `APP_RELEASE_VERSION`만 가린다. infra 승인은 **원본** live template hash에 묶여, 분류 뒤 무엇이 배포되든 무효가 된다.
- F7: rollback도 분류한다. overlay release로 돌아가거나, 더 새로운 schema를 수용하거나, 리뷰된 template 변경을 되돌리면 reviewer 경로다.
- F8: verifier token이 없으면 release·rollback plan을 만들지 않는다.
- F3: `classify`가 검토자용 diff(정규화한 template과 migration 목록, 12자리 숫자 가림)를 만들고 workflow가 plan job summary에 싣는다.
- 첫 AWS 쓰기 전에 실패하면 잠금을 풀고, 쓰기 뒤에 실패하면 잠금을 남긴다.
- ops task는 호출자가 지정한 쓰기 대상 DB를 `NULLNULL_OPERATIONS_TARGET`으로 전달한다. 값이 RDS가 보고하는 DB와 다르면 task를 띄우기 전에 거부한다. 앱의 확인(#183, PR #277)이 들어간 release부터 이 값이 없으면 앱이 쓰기를 거부하고, 그 전 image는 이 값을 무시한다. 앱의 target 줄은 log allowlist에 추가했다.
- `staging-alarm-subscribe.sh`는 operator role로 돌게 돼 있는데, 그 role에 SNS 권한이 없어 첫 호출에서 거부됐다. alarm topic(`NullnullStgObservability-*`) 한정으로 구독 목록 조회·발송과 email 구독만 더했다.

### auto-merge 게이트 `.github/workflows/auto-merge.yml`

- N2에 대한 오너 결정 (a): PR 파일 목록에 AWS·CD 경로가 있으면 auto-merge를 켜지 않고, 이미 켜진 것은 끈다. rename은 두 이름 모두로 보고, 3,000파일을 넘는 PR도 게이트 대상이다.
- `pull_request_target`으로 main에 있는 판본이 돌아서 PR이 자기 게이트를 지울 수 없다. PR 코드는 checkout하지도 실행하지도 않는다.

### CD workflow `.github/workflows/staging-release.yml`

- web bundle은 cloud 자격 증명이 없는 `web` job에서 만든다. npm lifecycle script가 role 옆에서 돌지 않는다.
- F11: artifact와 pending plan 이름을 job output으로 넘겨 "실패한 job만 재실행"이 동작한다.
- rollback도 분류해 `deploy-infra`(reviewer)로 보낼 수 있다.
- F13: reviewer 대기 중인 run이 concurrency group을 잡는다는 점을 workflow와 runbook에 적었다.

### 그 밖

- F4: RDS `maxAllocatedStorage`(= allocatedStorage)를 지웠다.
- F6: wrapper script의 텍스트만 보던 test를 실제 동작 test로 바꿨다.

## 7. 로컬 검증과 등급

| 검증 | 결과 | 등급 |
| --- | --- | --- |
| `python3 -m unittest discover -s scripts/tests -p 'test_*.py'` | 414 OK(작업 트리: `backend` 58ae2a5 + 미커밋 변경) | 로컬 |
| `cd infra && npm run check` | 21 pass(tsc 포함) | 로컬 |
| `validate_docs.py`·markdownlint | 통과·0건 | 로컬 |
| 새 가드 변이 검사 | operator·IAM·infra 가드를 하나씩 지우면 해당 test만 빨개진다. 쓰기 대상 DB 확인은 변이 3개, operator SNS 범위는 변이 2개가 각자의 test를 빨갛게 했다 | 로컬 |
| auto-merge 게이트 | workflow의 실제 `run:` 스크립트를 가짜 `gh`로 11가지 경우에 돌려 모두 기대대로였다(게이트 경로·rename·3,000파일 초과·이미 켜진 auto-merge 해제·API 실패). 하네스 변이 2개와 정적 test 변이 3개가 모두 잡혔다 | 로컬 |
| actionlint(pinned 1.7.12, shellcheck 포함) | `auto-merge.yml` 0건. 음성 대조군은 잡혔다 | 로컬 |
| IAM 정책 시뮬레이터(`simulate-custom-policy`, 읽기 전용) | execution·boundary 91건, 사용자 정의 deploy role 15건, operator SNS 10건 모두 기대대로 | AWS 평가기(요청 1건당 자원 1개로 근사) |
| 배포 후보 로컬 리허설 | migration 34/34, 흐름 18/18, AI 중단 시 ready `DEGRADED`(200), DB 중단 시 ready 503·live 200 | 로컬 Docker(linux/amd64) |
| required CI on 925b2c0 | 둘 다 success | GitHub Actions 실제 실행 |

mock과 실제의 구분은 이렇다. 사용자 흐름은 로컬 리허설과 실제 배포 환경 양쪽에서 돌렸다. KTO는 staging의 ops task가 Secrets Manager의 key로 실제 호출에 성공했다(경복궁 1건). catalog는 아직 닫혀 있다.

## 8. 검증하지 못한 것과 잔여

- CD 실행: workflow가 `main`에 없어 한 번도 돌지 않았다. `environment.deployment: false` job의 OIDC `sub`는 첫 실행에서 확인된다. 틀리면 AssumeRole이 거부되는 시끄러운 실패다.
- rollback drill, restore drill: 실행하지 않았다. rollback 경로는 로컬 test와 분류까지만 검증했다. DB schema는 rollback으로 되돌아가지 않는다.
- KTO 실제 호출과 CMP-KTO-003 증거: 경복궁 1곳은 `verified`다. 이 세션의 실행 도구가 외부 호출을 거부해서 오너가 직접 실행했다. 증거 파일은 release별(`actual-call-<release>.json`)이라 다음 release에서 한 번 더 실행해야 한다. 나머지 4곳은 contentId가 없어 뒤로 미뤘고, 저장소에는 KTO 키워드 검색 구현이 없다. forecast 호출은 아직 승인 범위 밖이다. 대화형 zsh는 기본으로 `#` 줄을 주석으로 읽지 않으니, 붙여 넣을 명령에는 주석 줄을 넣지 않는다.
- alarm 이메일 구독: primary 1건을 요청했고 `PendingConfirmation` 상태다. 확인 링크를 누르기 전에는 alarm이 아무에게도 전달되지 않는다. secondary 연락처는 비어 있다.
- operator 정책이 6,013자로 IAM 한도 6,144자에 가깝다. 권한을 더 넣으려면 먼저 정책을 나눠야 한다.
- 웹 응답에 CSP와 `Permissions-Policy`가 없다. CloudFront response headers policy에서 추가하는 것이 다음 infra 변경 후보다.
- IAM으로 막지 못하는 잔여: ECS service·ALB·CloudFront VPC origin을 다른 프로젝트 subnet에 두는 것은 막을 수 없다. Network stack이 생겼으므로 이제 `ecs:subnet`을 우리 subnet으로 고정할 수 있다(아직 안 함).
- N2(결정됨, 로컬 구현): deploy role은 reviewer 없는 `staging`도 신뢰해서, main에 병합된 코드는 `staging-infra` 승인 없이 infra 배포까지 할 수 있다. 오너가 (a)를 골라 AWS·CD 경로 PR은 auto-merge하지 않는다. 이 게이트는 main에 병합된 뒤부터 효력이 있다. 남는 자동 경로로 들어온 app 코드의 상한은 §6의 권한 경계다. required check workflow 자체를 바꾸는 PR은 게이트 밖이다.
- N3: 같은 경로로 GitHub role trust에 외부 주체가 추가되면 커밋을 되돌려도 남는다. 배포마다 smoke가 두 role trust를 정확한 subject 집합으로 검사해 탐지한다.

## 9. 오너가 커밋해야 할 것

CD는 아래 파일이 `main`에 들어가기 전에는 실행될 수 없다. 대상은 지금 작업 트리 기준 68개 경로다. 배포한 overlay(58파일)가 아니라 이 트리를 커밋한다. 배포 뒤에 고친 bootstrap variant, 쓰기 대상 DB 확인, operator SNS 권한, auto-merge 게이트, 문서가 여기 들어 있다.

- AWS·CD(49)
  - `.github/workflows/staging-release.yml`, `.github/workflows/staging-reconcile.yml`, `.github/actions/setup-aws-cli/action.yml`, `.github/workflows/auto-merge.yml`(기존 파일 수정)
  - `infra/` 21파일(CDK·IAM 문서 6개·bootstrap·cost basis·test)
  - `scripts/aws/` 19파일
  - `scripts/tests/test_aws_ci_reconcile.py`, `test_aws_iam.py`, `test_aws_operator.py`, `test_aws_staging_scripts.py`, `test_auto_merge_gate.py`
- 문서(4): `docs/operations/STAGING_DEPLOYMENT_RUNBOOK.md`, 이 문서, `.markdownlint-cli2.jsonc`의 한 줄, `docs/engineering/BRANCH_AND_INTEGRATION.md`의 게이트 예외 한 줄
- 배포 image가 의존하는 이전 Codex 작업(오너 보류분, 8): `MigrationRunner` 3파일(main·test·IT), `NullnullApplication.java`, `.dockerignore`, `compose.integration.yml`, `scripts/infra-check.mjs`, `scripts/tests/test_infra_check.py`
- 이전 Codex 문서 편집(오너 보류분, 7): `README.md`, `docs/README.md`, `docs/engineering/backend-plan.json`, `docs/operations/AWS_DEPLOYMENT.md`, `docs/operations/STAGING_BRINGUP_PLAN.md`, `docs/project/DECISIONS_AND_RISKS.md`, `docs/roles/BACKEND_AI_PLAYBOOK.md`. 이번 작업에서는 `docs/README.md`의 hunk만 runbook 링크 한 줄로 좁혔다(정리한 문서 3개의 링크 제거).
  - 옆 세션이 전한 오너 결정(2026-09-19): 보류 hunk도 이번 커밋에 넣는다. 카드 두 파일의 BA-006 부분(`backend-plan.json` 4+/4-, `BACKEND_AI_PLAYBOOK.md` 7+/6-)과 DECISIONS의 A-028·029·037, D-019이다. 크기와 바뀐 줄은 직접 확인했다.
  - 새 A-029 문구는 Budget 알림을 적고 있다. 그러나 이 계정은 SCP 때문에 Budget을 만들 수 없다(§12). 문구를 고칠지는 오너가 정한다.

커밋 전 검증(측정: 격리 worktree = `backend` 58ae2a5 + 이 68경로. 공유 checkout과 index는 건드리지 않았다):

- `docs-contract` 단계를 그대로 돌렸다: `validate_docs`, `run_script_tests`(414), `check_test_row_ownership`, `check_container_test_inputs`, `check_cited_tests`, `check_env_example_parity`, `check_actual_call_evidence`, markdownlint(68파일).
- 처음 돌렸을 때 `test_markdown_lint_scope`가 빨갛게 나왔다. 커밋되면 새로 추적될 `infra/cost-basis.md`와 `scripts/aws/README.md`가 lint 대상 밖이었기 때문이다. 공유 checkout에서는 두 파일이 untracked라 드러나지 않았다. `.markdownlint-cli2.jsonc`에 두 파일을 넣은 뒤 모두 통과했다.
- `docker-integration`의 infra-plan 단계는 같은 compose 서비스를 no-egress 네트워크에서 재현했다. tsc와 21 test가 통과했고, 판정기가 `infra_check=pass`를 받아들였다.
- `apps/api` suite(Testcontainers): test 553, integrationTest 532, openapiContractTest 48, recommendationTest 21. 모두 실패 0이다.

커밋 뒤 CD를 켜는 순서:

1. PR을 머지한다. 필수 CI 둘이 green이어야 한다. 이 PR은 자동 병합되지 않으니 오너가 직접 merge한다(아래 게이트).
2. Actions → Staging release → `deploy`를 실행하고, `expected_sha`에 main HEAD를 넣는다. 이것이 첫 CD 실행 검증이다.
3. 결과를 확인한 뒤 자동 배포를 켤지 정한다(`STAGING_AUTO_DEPLOY=true`).

N2 게이트는 오너가 (a)로 정했다(2026-09-19). AWS·CD 경로를 바꾸는 PR은 자동 병합하지 않고 오너가 직접 merge하며, 오너가 PR 작성자여도 된다. 이 커밋 묶음에 그 게이트가 들어 있다. 새 `auto-merge.yml`은 `pull_request_target`에서만 돌고 main의 옛 판본은 `pull_request`에서만 돌기 때문에, 이 PR에는 어느 쪽도 auto-merge를 켜지 않는다.

## 10. 절차

- 수동 배포(local): runbook §11 순서를 따른다. plan → classify(검토 diff) → `--execute --kind app|infra`. 승인 단위는 plan SHA-256이고 24시간 뒤 만료된다. 비용 추정 상한은 180에 예비 20이다.
- 수동 배포(CI, workflow가 main에 들어간 뒤): Actions → Staging release → `deploy`를 실행하고 `expected_sha`에 main HEAD를 넣는다. infra 판정이면 `staging-infra` reviewer가 plan summary의 diff를 보고 승인한다.
- 자동 배포: repository variable `STAGING_AUTO_DEPLOY=true`로 켠다. reconciler가 15분마다 main HEAD의 required 검사를 dispatch하고, 둘 다 성공한 그 SHA만 release한다.
- rollback: 대상을 비우면 `deployed/previous.json`을 쓴다. DB는 되돌리지 않는다. 더 새로운 schema에서 옛 binary를 돌리려면 `accept_newer_schema`가 필요하고, 그 경우 reviewer 경로다.
- ops task(local 전용): 승인 변수와 `NULLNULL_OPERATIONS_TARGET`을 호출자 환경에 두고 실행한다. 명령은 runbook §11에 있다. task는 `kto-smoke`·`kto-ingest`·`kto-forecast-smoke`·`kto-demo-detail`·`kto-demo-forecast`·`curate-hours`다. `kto-smoke`와 `curate-hours`는 `deployed/current.json`의 release와 ops 정의(image digest, `APP_RELEASE_VERSION`)가 같을 때만 뜬다.
- 잠금 복구: 쓰기 뒤에 실패하면 잠금이 남는다. CloudFormation·ECS 종료를 확인한 뒤 local에서 `staging_operator.py unlock --owner <id>`를 실행한다.

## 11. 다음 release에 필요한 것

PR #277이 main에 머지됐다(2026-09-19T04:09Z, main `1c94ec8`). 다음 release를 main HEAD로 만들면 아래가 함께 들어가야 한다. AWS 쓰기가 필요한 것은 전부 오너 승인이 필요하다. 그 release를 배포한 뒤 `rc.1001`로 되돌리는 rollback drill을 한다.

- 쓰기 대상 DB 확인: operator 쪽은 구현됐다(§6). 예정된 EventBridge schedule을 만들 때는 task override에 `NULLNULL_OPERATIONS_TARGET`을 고정값으로 넣는다. ops task는 지금처럼 `SPRING_FLYWAY_ENABLED=false`(`schema=unchecked`)로 둔다.
- `FEATURE_OPTIMIZATION_ITEM`: `infra/src/staging.ts`의 API task env에 고정으로 켠다(`staging.config.json`이 아니다). infra 경로이므로 reviewer 경로를 탄다. rc.1001로 rollback하면 다시 꺼진다.
- 데모 장소 갱신 schedule은 **보류**한다. 옆 세션이 전한 오너 결정(2026-09-19)은 이렇다: 데모 장소 5곳을 쓰지 않고 실사용으로 가며(임의 장소를 KTO 공공데이터로), Live는 제출 뒤로 미룬다. 실사용 설계가 정해지면 ops task, schedule, quota 변경을 받아 반영한다. `kto-demo-*` ops task 두 개는 operator에 남아 있지만 실행 계획은 없다.
- BA-072 T7: 삭제 실패·만료 미완료·삭제 job dead letter는 1건 alarm으로, 부분 실패와 lease 재획득은 metric만 둔다.

옆 세션이 알려 온 운영 일정이다. 등급은 코드 읽기와 로컬 리허설이며, staging에서는 재지 않았다.

- 영업시간 만료: `ops/curated-hours.json`의 영업시간이 2026-10-13T18:40Z에 일괄 만료된다. 그 뒤로는 모든 최적화가 `DATA_INSUFFICIENT`다. 심사가 10-25까지 이어지므로 10-13 전에 재관측·재적재해야 한다. 재적재는 `curate-hours` ops task로 하며 release가 필요 없다.
- 최적화 시연 조건: READY 제안이 나오려면 대상 장소의 예보가 여행 안 빈 날보다 25 넘게 높아야 한다(Δ25는 `NO_IMPROVEMENT`, Δ26은 READY). forecast를 적재한 뒤, 데모 장소별로 향후 30일 예보에 그런 날짜 쌍이 있는지 읽어 봐야 한다. `staging-flows.mjs --survey`가 향후 29일의 예보와 영업 여부를 찍고 날짜 쌍을 제안한다. `--optimize-item`은 INT-04(KEEP, APPLY→REVERT)를 verifier 경로로 확인한다. 예보는 PT24H 뒤 stale이므로 확인은 예보 적재 뒤 24시간 안에 한다.

## 12. 비용과 종료

`infra/cost-basis.md` 추정(모두 세금 포함 USD)은 이렇다.

- 14일: 70.55
- 2026-10-25까지: 171.63
- 심사 7일 동안 API 2개: +6.17
- 종료 뒤 보관: 월 3.45

배포 뒤 실제 구성을 읽기 전용으로 대조한 결과는 가정과 같다.

- API 0.5 vCPU/1 GB 1개, AI 0.25 vCPU/0.5 GB 1개(둘 다 public IPv4)
- RDS `db.t4g.micro` Multi-AZ, gp3 20 GB, 백업 14일
- secret 6개, alarm 3개, WAF rule 2개, ALB 1개
- NAT·EIP·VPC endpoint 0개, log 보존 30일

그래서 추정은 그대로 유지한다. 계정이 조직 멤버라 SCP가 `budgets:*`·`ce:GetCostAndUsage`를 거부한다. 따라서 실제 청구액은 이 계정에서 볼 수 없고, Budget 알림도 없다. 오너가 조직 billing 화면에서 직접 확인한다.

종료할 때 남는 것:

- RDS: 종료 보호와 `DeletionPolicy: Snapshot` 때문에 final snapshot이 남는다.
- Secrets Manager: secret 6개는 삭제해도 복구 대기 기간 동안 과금된다.
- ECR 이미지, release S3 bucket, CloudWatch log는 RETAIN이다.
- stack 종료 보호는 Foundation·Data에만 걸려 있다. 그 둘은 삭제 전에 해제해야 한다.
- 공개 API edge를 여는 조건(삭제 원장, BA-072 증거, secondary alarm 연락처)은 아직 충족되지 않았다.
