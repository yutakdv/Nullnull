# GitHub·릴리스 운영 계약

- 상태: M0 external settings/runbook baseline
- 대상: 2인 팀, GitHub repository와 AWS staging/production
- 전제: `~/Desktop/Nullnull`의 원격 `main` 이력을 유지하고 목표 서비스 파일만 반영한다.

이 문서는 workflow 파일만으로 표현되지 않는 ruleset, environment approval, CODEOWNERS, OIDC와 release artifact 정책을 함께 정의한다. 실제 설정을 완료했다는 뜻이 아니며 M0에서 각 checklist에 설정 화면/export evidence를 연결한다.

## 1. 초기 이관 안전 규칙

기준선 문서는 `origin/main` 이력을 유지하는 `~/Desktop/Nullnull`에 선별 반영한다. 작업공간 전체를 add하거나 unrelated history로 강제 merge하지 않는다. 상세 manifest는 [REPOSITORY_BASELINE.md](../project/REPOSITORY_BASELINE.md)를 따른다.

- docs/policy allowlist에 명시된 파일만 복사한다.
- 과거 prototype code·문서와 그 runtime을 가정하는 scheduled/deploy workflow는 목표 저장소에 포함하지 않는다.
- secret, local env, build output, database, cache, Figma export 임시 파일은 포함하지 않는다.
- 이관 PR의 file list와 source/target checksum을 reviewer가 확인한다.

## 2. Branch와 merge

| 대상 | 정책 |
| --- | --- |
| default branch | `main`, direct push 금지 |
| Frontend 역할 | 장기 `frontend`, `main`에만 PR |
| Backend/AI 역할 | 장기 `backend`, `main`에만 PR |
| release candidate | tag로 관리, 장기 release branch 기본 미사용 |

- merge commit을 사용해 역할 브랜치의 검토 단위를 보존한다. PR 제목과 commit은 기능 ID를 포함한 Conventional Commit 형식으로 정리한다.
- `frontend`와 `backend`는 merge 뒤 삭제하지 않고 새 `main`으로 동기화한다.
- force push, history rewrite, bypass는 production incident 승인 외 금지한다.
- contract/DB/security/infra/release 변경은 상대 담당자의 명시적 승인이 필요하다.
- 둘 중 한 명이 작성자이면 다른 한 명이 required reviewer다. 자기 승인 merge는 허용하지 않는다.
- stale approval은 새 commit 또는 contract/generated diff 변경 시 dismiss한다.

교차 slice의 additive contract → 호환 Backend → Frontend 순서, fast-forward/merge 동기화 방법과 M0 marker는 [브랜치·Docker 통합 계약](../engineering/BRANCH_AND_INTEGRATION.md)이 정본이다.

## 3. Required check와 component gate 설계

ruleset에 연결하는 stable required status는 M0 전후 정확히
`docs-contract`, `docker-integration` 두 개다. 이름을 바꾸면 ruleset도 같은 변경
창에서 갱신하고 잠시 gate가 비는 상태를 만들지 않는다.

| Ruleset required status | 적용 경로 | 내용 |
| --- | --- | --- |
| `docs-contract` | 모든 PR(required) | Markdown/link/YAML/JSON, OpenAPI lint/ref, event/example validation |
| `docker-integration` | 모든 PR(required) | M0 전 baseline-only, M0 후 PostgreSQL+API+web+mobile E2E 통합 aggregator |

M0 뒤 `docker-integration` 내부에는 다음 component gate를 둔다. 이 이름들은
ruleset required status가 아니라 aggregator의 필수 service/task다.

| Component gate | 내용 |
| --- | --- |
| `api-client-diff` | exact generator 재생성 후 diff 0, breaking diff |
| `web-quality` | lint/format/type/unit/build |
| `api-quality` | unit/integration/OpenAPI contract/Flyway |
| `mobile-e2e-smoke` | Playwright mobile/keyboard/axe 핵심 journey |
| `security-scan` | secret/SBOM/SCA/image/IaC scan |
| `infra-plan` | CDK synth/diff, broad IAM/stateful replacement detector |
| `egress-denied` | 모든 runtime service의 외부망 도달 실패 assertion |

Path filtering은 실행 시간을 줄일 수 있지만 두 required status는 항상 수행한다.
`docker-integration`은 component gate 하나라도 없거나 skip·실패하면 성공하지 않는다.
`docs/api/**`, `docs/contracts/**`, generator config가 바뀌면 client diff, FE fixture
test와 API contract test를 모두 강제한다. required workflow가 skip이면 성공으로
위장하지 않는다.

## 4. CODEOWNERS 목표

실제 handle이 결정되기 전 placeholder CODEOWNERS를 merge하지 않는다. M0에서 `FE_DRI`, `BE_AI_DRI`를 실제 GitHub handle로 치환해 다음 의미를 구현한다.

| Path | Primary DRI | CODEOWNERS | 상대 review가 필수인 변경 |
| --- | --- | --- | --- |
| `apps/web/**`, design docs | FE_DRI | FE_DRI + BE_AI_DRI | 모든 PR; BE/AI가 의미 경계 확인 |
| `apps/api/**`, DB/source/AI | BE_AI_DRI | FE_DRI + BE_AI_DRI | 모든 PR; FE가 public 동작 확인 |
| `docs/api/**`, `docs/contracts/**`, generated client | 공동 | FE_DRI + BE_AI_DRI | 항상 |
| product/architecture/security/privacy | 공동 | FE_DRI + BE_AI_DRI | 항상 |
| `infra/**`, workflows, operation docs | BE_AI_DRI | FE_DRI + BE_AI_DRI | 항상 |
| dependency lock/toolchain | 해당 DRI | FE_DRI + BE_AI_DRI | runtime/production 영향 |

PR 작성자는 자기 PR을 승인할 수 없으므로 단일 역할 owner만 두지 않는다. 실행 경로에 두 팀원을
함께 CODEOWNER로 지정해 작성자와 반대 역할 모두 승인 자격을 갖게 하고, ruleset에서
code owner review와 최소 1명 approval, conversation resolution, stale approval
dismissal을 함께 켠다. Primary DRI는 단독 승인권이 아니라 구현 책임이다.

## 5. GitHub ruleset 외부 설정 checklist

- [ ] default branch가 정확히 `main`
- [ ] `frontend`, `backend`가 `main`에서 생성됐고 두 역할 브랜치의 force push/deletion이 차단됨
- [ ] pull request 필수, direct push/force push/deletion 차단
- [ ] 최소 승인 1명 + code owner review + stale approval dismiss
- [ ] 모든 conversation resolved
- [ ] 정확히 `docs-contract`, `docker-integration`만 stable required status로 연결됨
- [ ] M0 뒤 모든 component gate가 `docker-integration` 내부에서 fail-closed로 집계됨
- [ ] required check가 관리자/bypass actor에도 기본 적용됨
- [ ] tag `v*` 생성/삭제 권한을 두 팀원/릴리스 workflow로 제한
- [ ] GitHub Actions permission 기본 `read`, job별 최소 상승
- [ ] third-party action은 full commit SHA로 pin하고 dependency update review
- [ ] fork PR에 secret/OIDC/deploy 권한 없음
- [ ] `pull_request_target`에서 untrusted code checkout/실행 없음
- [ ] secret scanning/push protection와 Dependabot alert 활성화 가능 여부 확인
- [ ] ruleset/export 또는 설정 screenshot을 private 운영 기록에 보존
- [ ] 일반 PR head는 같은 repository의 `frontend`/`backend`로 제한되고 dependency bot만 명시적 예외
- [ ] 실행 경로 CODEOWNERS에 두 팀원이 함께 있어 역할 owner가 작성자인 PR도 상대 승인이 가능함

## 6. GitHub environment checklist

### `staging`

- [ ] non-production AWS OIDC role/region/stack prefix만 등록
- [ ] main 성공 run만 deploy 가능
- [ ] environment concurrency 1
- [ ] 실제 source key는 environment secret, replay는 secret 없이 가능
- [ ] deployment URL과 release SHA 기록

### `production`

- [ ] production 전용 AWS OIDC role/account/stack prefix
- [ ] 요청자 외 상대 담당자 required reviewer
- [ ] tag/ref protection과 wait/approval policy
- [ ] concurrency 1, `cancel-in-progress=false`
- [ ] deployment branch/tag 제한
- [ ] rollback role도 최소 권한이며 직전 digest로 제한 가능
- [ ] alarm contact와 incident channel test 완료

GitHub environment reviewer가 한 명뿐이어서 본인 요청을 본인이 승인할 수 있는 설정은 production gate로 인정하지 않는다.

## 7. Workflow security와 OIDC

- workflow는 immutable action SHA를 사용하고 floating major tag만 쓰지 않는다.
- build job과 deploy job을 분리한다. deploy는 검증된 artifact digest를 다시 build하지 않고 승격한다.
- OIDC trust는 repository, environment, ref/tag subject와 audience로 제한한다.
- PR workflow는 `id-token: write`를 가지지 않는다. deploy job만 필요 시 부여한다.
- artifact에서 script를 받아 실행할 때 producer workflow와 checksum을 검증한다.
- workflow input을 shell에 직접 보간하지 않는다.
- secret value와 decoded token을 debug log/artifact에 남기지 않는다.
- self-hosted runner는 별도 결정 전 사용하지 않는다.

## 8. Release version과 manifest

Semantic Versioning을 사용한다.

- production 전 개발: `0.y.z`
- release candidate: `v0.y.z-rc.N`
- production: annotated/protected tag `v0.y.z`
- hotfix: patch 증가
- API breaking change: P0에서는 expand-contract; 불가피하면 version/major ADR

tag는 source 식별자이고 실제 배포 identity는 immutable digest 집합이다. release manifest에는 다음을 포함한다.

```json
{
  "releaseVersion": "v0.1.0",
  "gitSha": "full-sha",
  "apiImageDigest": "sha256:...",
  "webArtifactSha256": "...",
  "openApiSha256": "...",
  "eventSchemaSha256": "...",
  "flywayChecksums": [],
  "cdkAssemblySha256": "...",
  "buildRunId": "...",
  "approvedByRoles": ["FE_DRI", "BE_AI_DRI"]
}
```

manifest에는 secret/account credential를 넣지 않는다. staging에서 검증한 동일 image/web artifact를 production으로 승격한다.

## 9. Artifact retention 초기 정책

M0에서 GitHub plan/AWS lifecycle과 비용을 확인해 자동화한다. 더 짧게 변경하려면 rollback/RCA 요구를 검토하고 결정 대장에 남긴다.

| Artifact | 초기 보존 | 보호 규칙 |
| --- | --- | --- |
| PR test/coverage/Playwright report | 30일 | 열린 incident 연결 시 보존 연장 |
| contract diff, SBOM, security/IaC report | 90일 | production release 연결분 1년 |
| staging API image/web artifact | 30일 또는 최근 10개 중 더 긴 범위 | 현재 staging digest 삭제 금지 |
| production API image/release manifest | 1년 + 최근 5개 production release | active/rollback/tagged digest 보호 |
| production web object versions | 90일 + 최근 5개 production release | 현재/직전 manifest 보호 |
| migration checksum/배포 승인/rollback 기록 | 1년 | release manifest와 함께 보존 |
| security/privacy incident evidence | incident 정책/법적 검토값 | 일반 artifact cleanup에서 제외 |
| 공모전 KTO call-audit·외부망 smoke·접수 완료 evidence | 심사·이의 절차 종료 후 운영 정책에 따라 삭제 | secret·provider 원문·개인정보 제외, 비공개 저장 |

release artifact를 재생성해 과거 digest를 대체하지 않는다. retention job은 dry-run/report 후 삭제하며 active ECS task, current CloudFront manifest와 release tag 참조를 확인한다.

## 10. 배포와 역할 교대

| 단계 | 수행 | 검증/승인 |
| --- | --- | --- |
| release candidate 생성 | 변경 주도 DRI | 상대 담당자 |
| migration/API deploy | BE_AI_DRI | FE_DRI가 사용자 journey 관찰 |
| web deploy | FE_DRI | BE_AI_DRI가 API/error/source 상태 관찰 |
| 30분 집중 관찰 | 두 사람 | release lead가 종료 선언 |
| rollback | 영향 영역 DRI 실행 | 상대가 target digest/회복 확인 |

한 사람이 deployer이면 다른 사람은 observer/rollback confirmer다. 다음 release에서는 역할을 교대해 단일 지식 지점을 만들지 않는다.

## 11. Release gate

1. 모든 required check와 staging full journey 통과
2. OpenAPI/generated client/event/Flyway checksum clean
3. release manifest와 직전 rollback manifest 생성
4. source readiness/quota/license/attribution 확인
5. security/privacy change와 open incident 확인
6. budget/drift/destructive CDK diff 확인
7. production 상대 승인
8. migration → API → web 순서 배포
9. synthetic journey와 alarm 확인, 30분 관찰
10. release note, 결과, anomaly, rollback 가능 여부 기록

### 공모전 제출 추가 gate

1. 2026-09-19 code freeze와 2026-09-20 16:00 내부 제출 목표를 지킨다.
2. 외부망·익명창에서 HTTPS URL과 핵심 journey를 검증하고 `로그인 불필요`로 제출한다.
3. 승인된 운영키로 실제 KTO call → call-audit → 공개 response → 화면 텍스트 출처를 한 release에서 확인한다.
4. 기능설명서는 공식 양식을 변경하지 않고 PDF로 렌더링하며 실제 배포 기능/API만 적는다.
5. 위치 capability/geolocation은 OFF이고 승인 없는 CI·BI logo 및 secret 노출이 없다.
6. 2026-09-21 16:00 공식 마감 전 접수 완료 화면·제출본 checksum을 비공개로 보관한다.

## 12. Hotfix

- severity와 영향이 incident 문서에 기록된 경우에만 최소 patch로 진행한다.
- safety invariant/권한/삭제 문제는 feature flag 또는 rollback으로 먼저 영향 차단한다.
- required security/contract test를 생략하지 않는다. 시간상 full non-impact suite를 뒤로 미뤘다면 명시적으로 기록하고 다음 영업일 완료한다.
- production console 변경은 incident ID와 만료 시점을 남기고 IaC 후속 PR을 연다.
- hotfix 뒤 tag/manifest/rollback target과 post-incident action을 갱신한다.
