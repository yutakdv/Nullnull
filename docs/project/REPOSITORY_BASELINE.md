# 저장소 기준선과 current-only manifest

- 감사일: 2026-09-05
- 목표 작업 폴더: `~/Desktop/Nullnull`
- 원격: `https://github.com/yutakdv/Nullnull.git`
- 기준 `main`: `35460869369112c2ca3c149192e42885572213f1`
- 원칙: 원격 Git 이력을 보존하고 현재 Nullnull 목표 서비스의 문서·계약·검증 파일만 유지

## 1. 확인된 저장소 상태

`~/Desktop/Nullnull`은 `main...origin/main`이 일치하는 정상 Git 저장소다. 기준 commit에는 기존 README, hero asset, 공모전 기준 원문과 오래된 기획/디자인 문서가 있었다. 별도 `/Users/yutak/Documents/한국관광콘텐츠랩 공모전` 작업공간에는 과거 prototype과 새 문서 초안이 섞여 있고 Git 기준선이 없으므로 그 directory 전체를 commit하거나 원격과 강제 merge하지 않는다.

이 문서 작업은 다음 원칙으로 Desktop 저장소에 선별 반영한다.

1. `origin/main`의 `.git`과 history를 그대로 유지한다.
2. 아래 current-only allowlist만 복사한다.
3. 기준 commit의 hero와 실제 공모전 기준 원문은 보존·갱신한다.
4. 오래된 v6 기획안과 별도 Figma 수정 메모는 목표 문서로 대체하고 삭제 상태로 둔다. Git history에서 복구할 수 있다.
5. 과거 app/runtime/workflow는 복사하지 않는다.
6. commit, push, PR, GitHub ruleset 변경은 별도 명시적 실행 단계다.

## 2. current-only allowlist

| 영역 | 포함 경로 | 이유 |
| --- | --- | --- |
| 시작/정책 | `README.md`, `AGENTS.md`, `CLAUDE.md`, `CONTRIBUTING.md`, `SECURITY.md` | 제품·사람·Claude Code 규칙 |
| 설정 | `.editorconfig`, `.gitattributes`, `.gitignore`, `.markdownlint-cli2.jsonc`, `redocly.yaml` | 일관된 source/문서 검증 |
| Claude | `.claude/rules/**`, `.claude/skills/**` | 역할별 안전 규칙과 vertical slice skill |
| GitHub | `.github/ISSUE_TEMPLATE/**`, pull request template, `dependabot.yml`, `docs-contract.yml`, `integration.yml` | 현재 branch/contract/Docker gate만 유지 |
| 제품 문서 | `docs/product/**`, `docs/design/FIGMA_HANDOFF.md`, `docs/design/COMPONENT_CATALOG.md` | 최신 Figma 52 frame/49 component 계약 |
| 시스템 계약 | `docs/api/**`, `docs/contracts/**`, `docs/architecture/**`, `docs/data/**` | OpenAPI/event/ERD/source 정본 |
| 개발 운영 | `docs/engineering/**`, `docs/roles/**`, `docs/operations/**` | 역할 분담·branch·Docker·AWS 실행서 |
| 준수/보안 | `docs/contest/**`, `docs/security/**`, `docs/decisions/**`, `docs/project/**` | 심사 제외 방지, privacy, 결정/위험 |
| 검증 | `scripts/validate_docs.py`, `scripts/integration-test.sh`, `scripts/verify_target_stack.py`, `compose.integration.yml` | M0 전 baseline과 M0 후 digest/network/full Docker gate |
| 대표 자료 | `docs/assets/nullnull-readme-hero.png`, `과제2_널널_웹앱구현_기획서_Final.md` | README image와 최신 통합 기획안 |

새 목표 app은 M0 PR이 아래 경로로 추가한다. 아직 없는 경로를 문서 기준선에 빈 scaffold로 만들지 않는다.

```text
apps/web/
apps/api/
packages/api-client/
packages/contracts/
packages/design-tokens/
infra/
.nullnull-target-stack
```

## 3. 명시적 제외 목록

다음은 현재 목표 저장소에 복사하거나 되살리지 않는다.

- 과거 `app/**`, `tests/**`, FastAPI/SQLite/Python runtime와 dependency 파일
- 과거 `nullnull-travel-webapp/**`, React JavaScript build/dependency output
- 과거 root `Dockerfile`, `docker-compose.yml`, `.env.example`, `requirements.txt`, `weights.yaml`
- `docs/modernization/**`, `docs/superpowers/**`, `docs/backtest/**`와 이전 작업 요약
- 과거 `daily-batch.yml`, prototype CI/deploy workflow
- 대체된 v6 기획안과 `docs/design/피그마_디자인_틀_수정.md`
- `.DS_Store`, IDE/cache/build output, database, 실제 `.env`/key/token/certificate
- 외부 API 원문 dump, 실제 사용자 일정/위치, 임시 Figma export

과거 자료가 필요하면 Git history 또는 별도 작업공간에서 읽기 전용으로 확인하고, 검증된 지식만 새 OpenAPI·source contract·test로 다시 작성한다.

## 4. Git과 branch 기준

현재 local working tree의 문서 기준선은 `main`에 직접 commit하지 않는다. repository admin이 `origin/main`에서 장기 `backend`를 만들고 이 allowlist 변경을 첫 `backend → main` PR로 올린다. 상대 담당자가 file list/checksum과 두 required check를 확인해 merge한 뒤 `backend`를 새 `main`으로 동기화하고, 그 병합 commit에서 장기 `frontend`를 생성한다. 이후 Frontend는 `frontend → main`, Backend/AI는 `backend → main` PR만 사용한다. direct/force push와 역할 브랜치 삭제를 차단하고 상대 1명 승인, 정확히 `docs-contract`, `docker-integration`을 required로 둔다.

이관 파일을 local에 놓는 것과 다음 외부 변경은 구분한다.

- commit 생성
- 원격 push와 PR 생성
- branch 생성/보호와 CODEOWNERS
- GitHub environment·secret·OIDC
- AWS 배포

사용자가 명시적으로 요청하기 전 위 외부 변경을 완료됐다고 표시하지 않는다. 세부 merge 순서는 [브랜치·Docker 통합](../engineering/BRANCH_AND_INTEGRATION.md)을 따른다.

## 5. M0 전후 통합 gate

M0 전 `bash scripts/integration-test.sh`는 문서 추적성을 검사하고 `integration_mode=baseline-only`를 출력한다. 이는 앱 또는 Docker 통합 통과가 아니다.

M0 PR은 `.nullnull-target-stack`과 web/API scaffold, Dockerfile stage, npm/Gradle task, 검증된 image digest를 같은 commit에 추가한다. 앱 directory만 생기고 marker가 없거나 marker 뒤 필수 artifact가 빠지면 hard fail한다. `scripts/verify_target_stack.py`가 tag-only image, 누락 stage/task/lock/checksum과 non-internal Compose network를 거부한 뒤 모든 역할 브랜치 PR이 PostgreSQL, API, web, client diff, security, infra, egress-denied, mobile E2E의 full Docker gate를 통과한다.

## 6. 반영 전후 검증

```bash
python3 scripts/validate_docs.py
npx --yes markdownlint-cli2@0.23.2
npx --yes @redocly/cli@2.51.1 lint docs/api/openapi.yaml
npx --yes --package ajv-cli@5.0.0 --package ajv-formats@3.0.1 \
  ajv validate --spec=draft2020 -c ajv-formats \
  -s docs/contracts/events.schema.json -d docs/contracts/events.example.json
bash -n scripts/integration-test.sh
python3 -m py_compile scripts/validate_docs.py scripts/verify_target_stack.py
bash scripts/integration-test.sh
git diff --check
```

최종 file list는 allowlist와 대조하고, secret high-risk pattern과 제외 경로가 없는지 확인한다. 실행하지 못한 항목은 통과로 쓰지 않는다.

## 7. 완료 판정

현재 문서 이관 완료와 실제 개발/배포 완료를 혼동하지 않는다.

- 문서 기준선 완료: current-only 파일이 Desktop repo에 있고 문서/OpenAPI/event/baseline integration 검증이 통과
- Git 운영 완료: 문서 PR 병합, 역할 branch/ruleset/CODEOWNERS를 실제 설정하고 test PR로 확인
- M0 완료: target app scaffold와 full Docker gate가 존재
- 공모전 제출 준비 완료: 외부망 익명 journey, 실제 KTO call-audit/화면 출처, 위치 OFF, 공식 PDF와 접수 증거가 모두 있음

공모전 요구·증거는 [준수 매트릭스](../contest/COMPETITION_COMPLIANCE_MATRIX.md), 상태 기록 형식은 [evidence ledger template](../contest/EVIDENCE_LEDGER_TEMPLATE.md), 제출 절차는 [제출 runbook](../contest/SUBMISSION_RUNBOOK.md)이 정본이다.
