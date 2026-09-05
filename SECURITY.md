# Security policy

## Supported version

아직 production release 전이므로 `main`의 최신 release candidate만 보안 수정 대상이다. 첫 production tag 이후 지원 version 표를 갱신한다.

## Reporting a vulnerability

민감한 내용은 public issue에 올리지 말고 이 GitHub repository의 **Security → Report a vulnerability** private advisory를 사용한다. 다음을 포함하되 실제 secret이나 불필요한 사용자 data를 첨부하지 않는다.

- 영향 받는 route/version
- 재현 조건과 최소 단계
- 예상 영향
- 안전하게 scrub한 request/response 또는 screenshot
- 알려진 임시 완화책

별도 보안 메일을 공개하기 전까지 private advisory가 유일한 공식 접수 창구다. 저장소 관리자 두 명이 알림을 받고 Backend/AI 담당이 기술 대응 DRI, Frontend 담당이 사용자 노출·클라이언트 완화 DRI를 맡는다.

Pre-production 기본 응답 목표는 다음과 같다.

- 1영업일 이내 접수 확인
- 2영업일 이내 초기 심각도와 다음 업데이트 시점 공유
- 검증된 Critical은 즉시 배포 중지 여부를 판단하고 24시간 이내 containment/credential revoke 목표
- 해결 전에는 적어도 2영업일마다 제보자에게 상태 업데이트

이는 보상이나 수정 기한을 보장하는 SLA가 아니라 2인 팀의 운영 목표다. 실제 production 공개 전 [사고 대응 runbook](docs/operations/INCIDENT_RESPONSE.md)의 연락망·대체 담당·AWS 계정을 채우고 tabletop rehearsal를 완료한다.

## High-priority incidents

다음은 즉시 배포 중지/완화를 검토한다.

- 다른 owner의 여행·후보·일정 노출/변경
- 승인 없는 일정 변경 또는 apply 부분 반영
- session/CSRF 우회
- API key, cookie, token, raw itinerary, 정밀 위치의 노출
- 외부 source/replay를 실시간 사실로 잘못 표시하는 데이터 무결성 문제
- 한국관광공사 key의 browser/Git/log/PDF 노출 또는 mock/replay를 실제 OpenAPI 활용으로 제출하는 문제
- 공모전 profile에서 위치 capability/geolocation이 우발 활성화된 문제

Secret 노출은 Git에서 문자열을 지우는 것만으로 끝내지 않고 즉시 revoke/rotate한다. 운영 절차는 `docs/operations/ENVIRONMENT.md`와 `AWS_DEPLOYMENT.md`를 따른다.

사고 severity, 역할, 증거 보존, 사용자 통지, 복구 확인은 [INCIDENT_RESPONSE.md](docs/operations/INCIDENT_RESPONSE.md)가 정본이다.

공모전 제출 전에는 [준수 매트릭스](docs/contest/COMPETITION_COMPLIANCE_MATRIX.md)의 secret·KTO 실제 호출·출처·위치 OFF gate와 [제출 runbook](docs/contest/SUBMISSION_RUNBOOK.md)을 두 사람이 함께 확인한다.
