---
aliases:
  - "Nullnull 문서 홈"
doc_type: map
status: active
area: workspace
tags:
  - nullnull/map
  - nullnull/workspace
---

# Nullnull 문서 홈

**시작은 이 노트 하나다.** Backend와 AI는 `~/Desktop/Nullnull`의 `backend` 브랜치에서 작업한다. `apps/api`(Spring) scaffold와 추천 계산 서비스 `apps/ai`(Python, [ADR-0006](decisions/ARCHITECTURE_DECISIONS.md#adr-0006))가 추가됐다. 웹·생성 client·전체 통합은 아직 완료되지 않았다.

## 자주 여는 문서

| 보고 싶은 것 | 열 문서 |
| --- | --- |
| Figma부터 한 화면씩 검수할 때 | [09-06 PM 점검](project/PM_REVIEW_2026-09-06.md), [52개 화면 확인표](design/SCREEN_REVIEW_2026-09-06.md) |
| GitHub #10·#11 검토 | [기반 결정안](engineering/FOUNDATION_DECISIONS.md), [추가 계약·재현 자료](contracts/review-2026-09-06/README.md) |
| 다음에 무엇부터 개발할지 | [우선순위와 실행 순서](engineering/IMPLEMENTATION_PLAN.md) |
| 기능별 구현·API·DB·CI·FE 인계 | [Backend/AI 45개 상세 작업](roles/BACKEND_AI_PLAYBOOK.md) |
| 첫 추천 알고리즘과 X 참조 | [추천 상세 설계](architecture/RECOMMENDATION_ALGORITHM.md), [추천 서비스 구현 계획](superpowers/plans/2026-09-07-recommendation-python-service.md) |
| 단계 간 연결을 한눈에 보기 | [개발 순서 Canvas](BACKEND_ROADMAP.canvas) |
| 현재 막힌 계약·외부 결정 | [결정·위험·문서 검토 기록](project/DECISIONS_AND_RISKS.md) |

추천 설계→기반/CI→세션→공통 KTO/장소/비교→여행/후보→편집→최적화→import→운영/핵심 검수→선정 확장→**Live 마지막**. P1/P2를 선정하지 않으면 건너뛰며 Live의 선행 조건으로 모델 학습을 강제하지 않는다.

## Obsidian 사용법

1. Obsidian의 기존 폴더를 vault로 열기에서 `~/Desktop/Nullnull`을 선택한다. root 정책·docs·Canvas의 상대경로가 같은 vault 안에 있어야 한다.
2. 이 노트 `docs/README.md`를 북마크한다. Outline에서 큰 절을, 내부 링크에서 BA 작업과 API/ERD를 따라간다.
3. `docs/BACKEND_ROADMAP.canvas`를 열면 단계별 파일 카드가 나온다. B00~B10은 개발 단계이고 Figma의 S번호와 다르다.
4. 기본 Properties·Backlinks·Outline·Canvas·Search만 사용한다. Dataview/Kanban 등 community plugin 설치는 필요하지 않다.
5. 문서는 일반 상대 Markdown 링크를 사용한다. Obsidian 파일 이동 시 링크 갱신을 켜고, 이동 후 `python3 scripts/validate_docs.py`로 확인한다. 같은 내용을 새 노트로 복사하지 않는다.

노트 상단에는 `aliases`, `doc_type`, `status`, `area`, `tags`를 둔다. 예: `tag:#nullnull/plan`, `path:docs/roles`, `"BA-051"`로 검색한다. `status: draft`는 문서 승인 상태이고 카드의 `planned`는 기능 구현 상태다. 완료 checkbox나 파일 수로 기능 완성도를 계산하지 않는다. Obsidian은 [YAML Properties](https://obsidian.md/help/properties)와 [JSON Canvas 1.0](https://jsoncanvas.org/spec/1.0/)의 기본 형식을 사용한다.

`.obsidian/workspace*.json`·cache·개인 plugin state는 Git 제외다. 개인 창 배치나 기존 Obsidian 설정은 이 개편에서 덮어쓰지 않는다. 새 API·event 계약은 YAML/JSON 원문을 편집하고 생성 client를 직접 수정하지 않는다.

## 필요할 때 찾는 정본

| 영역 | 문서 | 책임 |
| --- | --- | --- |
| 제품 | [제품 요구사항](product/PRODUCT_SPEC.md), [127개 기능·NFR 인벤토리](product/FUNCTIONAL_INVENTORY.md) | 기능 의미·P0/P1/P2 |
| 화면 | [Figma 핸드오프](design/FIGMA_HANDOFF.md), [열린 FCR](design/FIGMA_CHANGE_REQUESTS.md), [49개 컴포넌트](design/COMPONENT_CATALOG.md) | 시각·문구·node/state; FE |
| 시스템 | [아키텍처·Backend 내부 설계](architecture/SYSTEM_ARCHITECTURE.md), [ERD](architecture/ERD.md), [추천](architecture/RECOMMENDATION_ALGORITHM.md), [apps/ai 경계 ADR-0006](decisions/ARCHITECTURE_DECISIONS.md#adr-0006) | 경계·데이터·알고리즘; BE/AI |
| 데이터 | [Source catalog](data/SOURCE_CATALOG.md) | 출처·license·시각·비교 적격성 |
| API·이벤트 | [API 규칙](api/README.md), [OpenAPI 0.2.1-rc.1 제안](api/openapi.yaml), [event schema](contracts/events.schema.json), [example](contracts/events.example.json) | FE·BE 공유 계약 |
| 실행 | [실행 순서](engineering/IMPLEMENTATION_PLAN.md), [BE/AI 작업](roles/BACKEND_AI_PLAYBOOK.md), [FE 실행서](roles/FRONTEND_PLAYBOOK.md), [FE Claude Code 시작 안내](roles/FRONTEND_CLAUDE_CODE_START.md) | 중복 없는 작업 계획 |
| 개발·검토 | [브랜치·계약 인계](engineering/BRANCH_AND_INTEGRATION.md), [소유권](engineering/OWNERSHIP_MATRIX.md), [로컬 개발](engineering/LOCAL_DEVELOPMENT.md), [테스트](engineering/TEST_STRATEGY.md) | 두 역할 브랜치·CI·DoR/DoD |
| 보안 | [Privacy](security/PRIVACY_REQUIREMENTS.md), [위협 모델](security/THREAT_MODEL.md), [신고 정책](../SECURITY.md) | 수집 최소화·권한·삭제 |
| 배포·운영 | [AWS](operations/AWS_DEPLOYMENT.md), [환경](operations/ENVIRONMENT.md), [GitHub/릴리스](operations/GITHUB_RELEASE_OPERATIONS.md), [사고 대응](operations/INCIDENT_RESPONSE.md) | 비용·배포·restore·alarm |
| 공모전 | [공식 기준](contest/2026-관광데이터-활용-공모전-공지-심사기준.md), [준수 매트릭스](contest/COMPETITION_COMPLIANCE_MATRIX.md), [증거 원장](contest/EVIDENCE_LEDGER_TEMPLATE.md), [제출 절차](contest/SUBMISSION_RUNBOOK.md) | 실제 기능/호출/출처/접수 증거 |
| 결정 | [결정·위험·현재 상태](project/DECISIONS_AND_RISKS.md), [통합 ADR](decisions/ARCHITECTURE_DECISIONS.md) | 열린 질문·설계 근거·재검토 trigger |
| 보관 | [이전 통합 기획안](archive/PRODUCT_BRIEF.md) | 배경 자료, 현재 실행 정본 아님 |

## 정본 충돌을 해결하는 방법

공모전 자격·제출·필수 데이터는 최신 공식 공지, Figma는 시각·화면·문구, OpenAPI/이벤트/ERD는 데이터 의미와 상태 전이를 담당한다. 어느 문서가 무조건 이기는 숫자 순서를 두지 않는다. 충돌하면 같은 기능 ID에서 화면·schema/example·ERD·test를 함께 고친다. Backend/AI가 계약을 제안하고 FE가 생성 client와 화면 소비성을 검토한다.

```text
기능 ID → Figma node/state → operationId/schema → entity/transition
→ BA 작업/실제 test ID → 구현 담당/검토자 → staging evidence
```

Accepted는 현재 구현 기준, Draft는 검토할 설계, Conditional/Open은 관련 조건 미해결, Archived는 보관 자료다. Figma의 열린 P0 FCR을 문서 개편만으로 닫지 않는다. 정확한 과거 조사 시각과 공식 마감은 보존하되 개발 계획에 날짜별 일정을 복제하지 않는다.

## 개편과 검증

WORKFLOW는 브랜치·인계 문서로, 5개 ADR은 한 기록으로, 저장소 기준선/PM 감사는 결정·위험 대장으로 합쳤다. 역할별로 중복된 52개 화면/50개 API 표는 기능·소유권·OpenAPI 정본을 링크한다. 이전 기획안은 보관 폴더로 옮겼고, 추천/X 참조와 CI 초안은 추천/테스트 정본 안에 통합했다. 기존 각 문서의 검토·처리 내역은 [문서 전수 검토](project/DECISIONS_AND_RISKS.md#문서-전수-검토와-개편-기록)에 기록했다.

`docs-contract`는 Markdown·링크·Properties/Canvas·API·이벤트·기능/작업 coverage를 확인한다. 최초 검토 snapshot은 `baseline-only`였지만 현재 `apps/api`가 추가되고 marker가 없어 `docker-integration`이 의도대로 실패한다. 웹/scaffold 통합 전의 상태이며 제품 실행 성공이 아니다. B01의 marker 뒤에는 실제 API/DB/web·추천/중요 기능 테스트를 건너뛰지 못한다. 전체 검증 방법은 [테스트 전략](engineering/TEST_STRATEGY.md)을 따른다.
