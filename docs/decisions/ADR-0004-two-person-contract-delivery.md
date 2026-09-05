# ADR-0004: 2인 contract-first 화면 개발 방식

- 상태: Accepted
- 날짜: 2026-09-04

## Context

Nullnull은 Figma의 52개 frame, 여러 overlay/state, OpenAPI, 외부 데이터와 일정 최적화 불변식을 두 사람이 구현한다. Frontend와 Backend를 긴 기간 따로 개발하면 hand-written mock, 누락된 error state, 마지막 통합 병목과 승인 없는 일정 변경 위험이 커진다.

## Decision

- 역할은 Frontend 담당 1명과 Backend/AI 담당 1명으로 고정한다.
- 작업 단위는 layer가 아니라 Figma node와 기능 ID를 끝까지 연결한 vertical slice다.
- slice 착수 전 Figma state, operationId, schema-valid example, Problem code, domain 변화/미변화와 acceptance를 contract packet으로 승인한다.
- Frontend는 generated client와 canonical example 기반 MSW로, Backend/AI는 같은 OpenAPI/example 기반 provider contract test로 병렬 구현한다.
- 상태는 `contract-ready → parallel-build → integration-ready → staging-accepted` gate를 통과한다.
- 모든 P0 화면과 reference variant는 automated fixture/test를 갖춘다. P1 화면은 capability OFF 상태부터 구현하고 조건 충족 전 기능을 활성화하지 않는다.
- contract/product/security/infra 변화는 상대 담당자 승인을 요구한다. 작성자 자신의 승인만으로 완료하지 않는다.
- Frontend는 장기 `frontend`, Backend/AI는 장기 `backend`에서 작업하고 각각 `main`에 PR을 만든다. 상대 승인과 `docs-contract`·`docker-integration` 뒤 merge commit하고 역할 브랜치를 삭제하지 않는다.
- 교차 변경은 additive contract를 먼저 병합하고 양 역할 브랜치를 `main`으로 동기화한 뒤 호환 Backend, Frontend 순으로 진행한다. 새 capability는 양쪽 통합 전 OFF다.
- ownership과 handoff는 `OWNERSHIP_MATRIX.md`, 역할별 실행은 `docs/roles/`, branch/Docker 순서는 `BRANCH_AND_INTEGRATION.md`, 세부 rhythm은 `WORKFLOW.md`를 따른다.

## Consequences

- BE/AI 구현이 늦어도 FE가 합의된 mock으로 시작할 수 있고, FE 화면이 늦어도 API contract test를 먼저 완성할 수 있다.
- OpenAPI example과 screen manifest 관리 비용이 생기지만 마지막 통합과 의미 불일치를 줄인다.
- 두 명 모두 상대 영역의 계약을 review해야 하므로 WIP를 사람당 main slice 1개로 제한한다.
- merge와 done을 구분하며 실제 staging acceptance가 milestone 완료 조건이 된다.
- 역할 브랜치 수는 단순하지만 한 브랜치의 WIP가 길어지면 다음 slice를 시작할 수 없으므로 작은 병합 단위를 강제한다.

## Rejected alternatives

- Frontend 전체 완료 후 Backend 연동: mock/API drift와 integration big-bang 위험이 크다.
- Backend endpoint 전체 완료 후 화면 구현: Figma에 필요한 상태 누락을 늦게 발견한다.
- hand-written TypeScript API type: OpenAPI 정본과 쉽게 갈라진다.
- 모든 파일 공동 소유: 두 사람 모두 상대가 처리할 것으로 오해한다.
- slice마다 단기 feature branch: 2인 팀에서는 branch 간 contract SHA와 배포 순서를 추적하는 비용이 더 커 장기 역할 브랜치를 선택한다.

## Review trigger

- 팀 인원이 늘거나 역할이 바뀜
- contract-ready 대기 시간이 milestone의 20%를 반복 초과함
- mock과 production API 불일치 incident가 발생함
- required review가 병목이 되어 PR lead time이 2영업일을 반복 초과함
- 별도 mobile/native client 또는 public API consumer가 추가됨

검토 DRI는 공동이며 각 milestone 회고와 팀 구성 변경 시 확인한다.
