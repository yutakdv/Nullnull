# Pull request

## 목적

<!-- 사용자가 얻게 되는 결과를 2~3문장으로 작성 -->

- Ticket:
- Figma node/링크:
- OpenAPI operationId/schema:
- Head → base: `frontend|backend` → `main`
- Contract SHA/example version:

## 변경 범위

- [ ] Frontend
- [ ] Backend
- [ ] OpenAPI/event contract
- [ ] DB migration
- [ ] External data/AI
- [ ] AWS/config/secret
- [ ] Documentation only

## 역할 브랜치 gate

- [ ] PR head는 작업자 역할의 `frontend` 또는 `backend`, base는 `main`이다.
- [ ] PR head는 외부 fork가 아니라 이 repository의 역할 브랜치다.
- [ ] 최신 `main`을 동기화했고 force push/rebase로 상대 이력을 바꾸지 않았다.
- [ ] 작성자가 아닌 상대 담당자를 reviewer로 지정했다.
- [ ] 병합 뒤 양 역할 브랜치를 `main`에 맞추는 순서를 확인했다.

### 주요 변경

-

### 명시적 비범위

-

## 계약·데이터

- [ ] OpenAPI/event schema를 구현보다 먼저 갱신했다.
- [ ] generated client/fixture가 최신이다.
- [ ] SavedPost/TripCandidate/TripItem 경계를 지켰다.
- [ ] ETag/If-Match/idempotency 영향을 검토했다.
- [ ] ERD/migration/retention 영향을 기록했다.
- [ ] source/freshness/confidence/comparison eligibility를 보존했다.
- [ ] 개인정보·원문 일정·정밀 위치·로그 영향을 검토했다.
- [ ] 한국관광공사 데이터이면 실제 호출·호출 증거·승인된 텍스트 출처·키 비노출을 확인했다.

## UI 상태와 접근성

- [ ] default/loading/empty/error/offline/stale 중 해당 상태를 구현했다.
- [ ] 360px, safe-area, virtual keyboard, 긴 텍스트를 확인했다.
- [ ] keyboard, focus 시작/복귀, accessible name을 확인했다.
- [ ] 색만으로 상태를 전달하지 않는다.
- [ ] Before/after screenshot 또는 녹화를 첨부했다.

## 검증

<!-- 실행한 명령과 결과. 미실행은 이유를 명시 -->

- [ ] Frontend lint/format/typecheck/unit/build
- [ ] Backend unit/PostgreSQL integration/contract
- [ ] Playwright 핵심 흐름
- [ ] Accessibility
- [ ] Migration upgrade/rollback compatibility
- [ ] Security/secret/PII scan
- [ ] Staging smoke/readiness
- [ ] `docs-contract`
- [ ] `docker-integration` (`baseline-only`면 M0 전임을 명시)
- [ ] M0 뒤 client diff, web/API quality, security, infra, egress-denied와 mobile E2E가 `docker-integration` 내부에서 모두 실행됐다.

```text
검증 결과:
```

## 배포·관측·롤백

- Feature flag/default:
- 새 env/secret/IAM:
- Metric/log/alarm:
- Migration/deploy order:
- Rollback 방법:

## 위험·남은 결정

-

## 공모전 제출 증거

<!-- 제출 후보 PR만 작성. 미구현/P1은 구현 완료로 표시하지 않는다. -->

- 실제 배포 기능/심사 흐름:
- 실제 KTO OpenAPI operation과 비밀값 없는 call-audit 증거:
- 화면 출처·기준시각 screenshot:
- 외부망·익명창 URL smoke 결과:
- 기능설명서 반영 여부:
- 준수 requirement ID / evidence ID / 상태:
