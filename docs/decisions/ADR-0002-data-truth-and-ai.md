# ADR-0002: 데이터 진실성 및 AI 사용 경계

- 상태: Accepted
- 날짜: 2026-09-04

## Context

Nullnull의 핵심 가치는 혼잡 완화이지만 KTO 예보, 서울 실시간, replay, 정성 정보는 범위와 척도가 다르다. 이를 하나의 수치로 합치거나 LLM이 빈 값을 채우면 그럴듯하지만 검증 불가능한 추천이 된다.

## Decision

1. 외부 관측/예측 record에 source, sourceState, observedAt, targetAt, fetchedAt, freshness, confidence, license, normalizationVersion을 보존한다.
2. temporal comparison은 같은 POI와 같은 forecast issue/metric 체계에서만 한다.
3. spatial comparison은 같은 source/scope/comparisonGroup/snapshotSet에서만 한다.
4. 비교 가능 여부와 사유는 backend가 명시한다. UI가 값을 보고 추정하지 않는다.
5. `LIVE`, `FORECAST`, `REPLAY`, `QUALITATIVE`, `STALE`, `UNAVAILABLE`을 별도 state로 유지한다.
6. LLM은 선호 해석과 검증된 근거 설명만 담당한다. POI 존재, 영업, 좌표, 경로, 혼잡 값, 적용 가능성은 결정적 코드가 검증한다.
7. P0 일정 parser는 규칙 기반이며 원문을 보관하지 않는다.
8. 공모전 제출 서비스는 KTO OpenAPI를 실제 server-side 호출하고 redacted call-audit를 화면 provenance에 연결한다. file/replay/local mirror만으로 대체하지 않는다.
9. KTO 화면은 승인된 텍스트 출처를 표시하고 무허가 CI·BI를 사용하지 않는다.

## Consequences

- 데이터가 적을 때 UI가 빈 상태를 더 자주 보여도 허위 precision을 만들지 않는다.
- source adapter와 snapshot metadata가 일반 CRUD보다 복잡해진다.
- 추천 품질 평가는 정확도뿐 아니라 provenance 완전성, stale 비율, 비교 적격률을 포함한다.
- P2 model을 도입해도 같은 evidence contract를 통과해야 한다.
- quota-aware cache를 사용하더라도 실제 KTO 호출·서비스 사용 증거와 replay 증거를 별도로 운영해야 한다.

## Rejected alternatives

- 모든 source를 1–5 혼잡도로 강제 변환
- LLM에게 외부 검색·영업 여부·경로 가능성을 단독 위임
- replay를 현재 실시간처럼 표시
- 결측값을 0 또는 “보통”으로 채움

## Review trigger

- provider가 metric, 갱신 주기, 이용조건, 공간 범위 또는 schema를 변경함
- source quality incident로 잘못된 live/forecast/replay 표시가 발생함
- 서로 다른 source를 교정·비교할 수 있다는 검증 연구와 평가 dataset이 승인됨
- P1/P2 LLM/ML provider 또는 자체 model이 추천 후보·score에 관여하려 함
- provenance 완전성이나 comparison eligibility SLO가 milestone에서 반복 실패함

검토 DRI는 Backend/AI 담당이고 사용자 문구·표현 변경은 Frontend 담당 승인이 필요하다. M4 source onboarding, M5 optimizer, 각 provider 계약 갱신 때 확인한다.
