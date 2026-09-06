# Nullnull 개발 문서 지도

- 기준일: 2026-09-05
- 적용 범위: Figma 기반 모바일 PWA의 P0/P1 제품·개발·배포 준비
- 팀: Frontend 1명, Backend/AI 1명

## 정본과 충돌 해결

서로 다른 자료가 충돌하면 아래 순서로 판단한다.

1. 공모전 자격·부문·필수 데이터는 [투어라즈 공식 공고](https://touraz.kr/announcementList/pssrpView?curPage=1&pssrpSeqEnc=tCHutZnHQt3WheOzQ7OdTQ%3D%3D), 세부 마감·제출은 [참가자 공식 공지](https://lowly-polyanthus-1fb.notion.site/2026-3a75dce406e38034a9a8d058a1b55596)와 최신 제출 매뉴얼
2. Figma `02 UI Design`의 최신 화면·우선순위·시각 상태
3. `docs/api/openapi.yaml`과 `docs/contracts/`의 기계 판독 계약
4. 제품·아키텍처·데이터·보안·운영 문서와 [공식 공지 요약](contest/2026-관광데이터-활용-공모전-공지-심사기준.md)
5. 저장소 루트의 [통합 기획안](../과제2_널널_웹앱구현_기획서_Final.md)

Figma는 **무엇을 보여 주는지**, OpenAPI/이벤트/ERD는 **어떤 상태 변화와 데이터가 가능한지**를 정의한다. 둘이 충돌하면 임의 구현하지 않고 같은 PR에서 화면 핸드오프, 기능 ID, 계약, 테스트를 함께 조정한다.

## 담당자별 첫 읽기

### Frontend 담당

1. [Frontend Claude Code 시작 안내서](roles/FRONTEND_CLAUDE_CODE_START.md)
2. [Frontend 실행서](roles/FRONTEND_PLAYBOOK.md)
3. [제품 요구사항](product/PRODUCT_SPEC.md)과 [기능 인벤토리](product/FUNCTIONAL_INVENTORY.md)
4. [Figma 핸드오프](design/FIGMA_HANDOFF.md)와 [컴포넌트 카탈로그](design/COMPONENT_CATALOG.md)
5. [API 규칙](api/README.md)과 [OpenAPI](api/openapi.yaml)
6. [브랜치·Docker 통합](engineering/BRANCH_AND_INTEGRATION.md), [테스트 전략](engineering/TEST_STRATEGY.md), 공모전 준수 문서

### Backend/AI 담당

1. [Backend/AI 실행서](roles/BACKEND_AI_PLAYBOOK.md)
2. [제품 요구사항](product/PRODUCT_SPEC.md), [기능 인벤토리](product/FUNCTIONAL_INVENTORY.md), Figma의 연결 화면
3. [시스템 아키텍처](architecture/SYSTEM_ARCHITECTURE.md), [ERD](architecture/ERD.md), [외부 데이터 카탈로그](data/SOURCE_CATALOG.md)
4. [OpenAPI](api/openapi.yaml), [API 규칙](api/README.md), [이벤트 계약](contracts/events.schema.json)
5. [브랜치·Docker 통합](engineering/BRANCH_AND_INTEGRATION.md), 개인정보·AWS·릴리스·공모전 준수 문서

## 전체 문서와 책임

| 영역 | 문서 | 답하는 질문 | 주 DRI |
| --- | --- | --- | --- |
| 기획 | [통합 기획안](../과제2_널널_웹앱구현_기획서_Final.md) | 왜 만들고, 무엇을 P0/P1로 출시하는가? | 공동 |
| 공모전 | [공지·심사 기준](contest/2026-관광데이터-활용-공모전-공지-심사기준.md), [준수 매트릭스](contest/COMPETITION_COMPLIANCE_MATRIX.md), [증거 원장 template](contest/EVIDENCE_LEDGER_TEMPLATE.md), [제출 runbook](contest/SUBMISSION_RUNBOOK.md) | 무엇을 언제 제출하고 어떤 증거로 심사 제외를 막는가? | 공동 |
| 저장소 | [Repository baseline](project/REPOSITORY_BASELINE.md) | 기존 코드와 Git 이력을 어떻게 안전하게 다루는가? | 공동 |
| PM 감사 | [정합성·완성도 감사](project/PM_CONSISTENCY_AUDIT.md) | 현재 무엇이 승인됐고 출시를 막는 gap은 무엇인가? | 총괄 PM |
| 제품 | [제품 요구사항](product/PRODUCT_SPEC.md) | 사용자 결과와 제품 불변식은 무엇인가? | 공동 |
| 추적성 | [기능 인벤토리](product/FUNCTIONAL_INVENTORY.md) | 기능 ID가 어느 화면/API/test로 이어지는가? | 공동 |
| 디자인 | [Figma 핸드오프](design/FIGMA_HANDOFF.md), [Figma 수정 요청](design/FIGMA_CHANGE_REQUESTS.md) | 현재 52개 화면의 route·상태·CTA와 열린 디자인 gap은 무엇인가? | FE, PM 승인 |
| UI | [컴포넌트 카탈로그](design/COMPONENT_CATALOG.md) | 49개 최상위 컴포넌트를 어떤 contract로 구현하는가? | FE |
| 시스템 | [시스템 아키텍처](architecture/SYSTEM_ARCHITECTURE.md) | FE/BE/AI/외부 연동 경계는 어디인가? | BE/AI |
| 데이터 | [ERD](architecture/ERD.md), [Source catalog](data/SOURCE_CATALOG.md) | 무엇을 어떤 무결성·출처·보존 규칙으로 저장하는가? | BE/AI |
| API | [API 규칙](api/README.md), [OpenAPI](api/openapi.yaml) | 두 담당자가 공유하는 요청·응답·오류 계약은 무엇인가? | BE/AI, FE 승인 |
| 이벤트 | [JSON Schema](contracts/events.schema.json), [예시](contracts/events.example.json) | 분석 이벤트 allowlist와 개인정보 경계는 무엇인가? | BE/AI, FE producer |
| 역할 | [Frontend Claude Code 시작 안내서](roles/FRONTEND_CLAUDE_CODE_START.md), [Frontend 실행서](roles/FRONTEND_PLAYBOOK.md), [Backend/AI 실행서](roles/BACKEND_AI_PLAYBOOK.md), [Ownership matrix](engineering/OWNERSHIP_MATRIX.md) | 담당자가 무엇을 어떤 순서와 완료 증거로 맡으며 Claude Code에 무엇을 전달하는가? | 공동 |
| 브랜치 | [브랜치·Docker 통합](engineering/BRANCH_AND_INTEGRATION.md), [Workflow](engineering/WORKFLOW.md) | 역할 브랜치 PR을 어떻게 main에서 검증·병합하는가? | 공동 |
| 실행 | [구현 계획](engineering/IMPLEMENTATION_PLAN.md), [로컬 개발](engineering/LOCAL_DEVELOPMENT.md) | 어떤 순서와 명령으로 개발하는가? | 공동 |
| 품질 | [테스트 전략](engineering/TEST_STRATEGY.md) | 기능·계약·DB·접근성을 어디서 검증하는가? | 공동 |
| 보안 | [위협 모델](security/THREAT_MODEL.md), [개인정보](security/PRIVACY_REQUIREMENTS.md) | 무엇을 수집하지 않고 어떻게 격리·삭제하는가? | BE/AI, FE 협력 |
| 배포 | [AWS](operations/AWS_DEPLOYMENT.md), [환경](operations/ENVIRONMENT.md) | 어떤 계정·stack·secret으로 배포하는가? | BE/AI |
| 운영 | [GitHub/릴리스](operations/GITHUB_RELEASE_OPERATIONS.md), [사고 대응](operations/INCIDENT_RESPONSE.md) | 어떻게 승인·릴리스·롤백·대응하는가? | 공동 |
| 결정 | [결정·위험 대장](project/DECISIONS_AND_RISKS.md), [ADR](decisions/) | 무엇이 확정됐고 무엇이 아직 열려 있는가? | 공동 |

현재 승인된 구조 결정은 [목표 stack](decisions/ADR-0001-target-stack.md), [데이터 진실성과 AI 경계](decisions/ADR-0002-data-truth-and-ai.md), [세션·동시성](decisions/ADR-0003-session-consistency.md), [2인 계약 전달](decisions/ADR-0004-two-person-contract-delivery.md), [AWS·릴리스 경계](decisions/ADR-0005-aws-release-boundaries.md)다.

## 기능 작업의 연결 단위

모든 issue와 PR은 최소 다음 한 줄을 가져야 한다.

```text
기능 ID → Figma node/state → route/component → operationId/schema → entity/transition → test ID → 담당/검토자
```

화면만 구현하거나 API만 먼저 추측해 만들지 않는다. API가 아직 없어도 FE는 승인된 OpenAPI example 기반 mock으로 진행하고, BE/AI는 같은 example을 contract test fixture로 사용한다.

## 문서 상태

- `Accepted`: 현재 구현 기준이다. 의미 변경은 계약/ADR 검토가 필요하다.
- `Draft`: 담당자 검토 후 구현할 수 있다.
- `Open`: 결정 주체·기한·안전한 기본값이 필요하다.
- `Legacy`: 근거 자료로만 사용하며 새 구현 정본이 아니다.

별도 표시가 없으면 현재 문서는 `Accepted for P0 implementation`이다. 단,
[Figma 핸드오프](design/FIGMA_HANDOFF.md)는 P0 수정 요청이 열린 `Conditional` 상태이며
영향 slice는 해당 FCR이 닫히기 전 착수하지 않는다. 외부 계정·가격·약관·팀원
handle처럼 저장소에서 확정할 수 없는 항목은 결정 대장에 `Open`으로 남기며, 승인 전
capability는 기본 OFF다.

## 계약 변경 순서

1. 기능 ID와 Figma node/state를 식별한다.
2. OpenAPI 또는 이벤트 JSON Schema와 example을 먼저 변경한다.
3. ERD·상태 전이·마이그레이션·보존 영향을 기록한다.
4. FE 생성 client/mock과 BE contract test를 같은 계약에서 갱신한다.
5. success뿐 아니라 loading/empty/error/offline/stale/concurrency/accessibility를 검증한다.
6. staging smoke, 관측, migration/deploy 순서와 rollback을 남긴다.

## 자동 검증

모든 `frontend → main`, `backend → main` PR은 `docs-contract`와 `docker-integration`을 통과해야 한다.

- Markdown lint와 로컬 링크 확인
- OpenAPI 3.1 lint, 내부 `$ref`, operationId 중복 확인
- 이벤트 JSON Schema에 대한 예시 검증
- 기능 인벤토리의 operationId가 OpenAPI에 존재하는지 확인

로컬 명령은 루트 [README](../README.md)와 [로컬 개발 문서](engineering/LOCAL_DEVELOPMENT.md)를 따른다.

M0 전 `docker-integration`은 문서 기준선만 검증하고 `baseline-only`라고 표시한다. `.nullnull-target-stack`이 생긴 뒤 앱 또는 Docker artifact가 빠지면 hard fail한다. `scripts/verify_target_stack.py`가 image digest·Docker stage·quality task·internal-only network를 검증한 뒤에만 full integration을 실행한다.
