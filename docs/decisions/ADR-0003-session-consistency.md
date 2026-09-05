# ADR-0003: 익명 소유권, 일정 version, 승인형 변경

- 상태: Accepted
- 날짜: 2026-09-04

## Context

로그인 장벽 없이 데모를 제공하면서도 사용자별 여행을 격리해야 한다. 모바일의 재시도·중복 tap, 여러 tab, 오래 열린 최적화 preview는 일정 중복이나 덮어쓰기를 만들 수 있다.

## Decision

- 익명 사용자도 server-side `Owner`와 `DemoSession`을 가진다.
- browser는 opaque session id를 Secure/HttpOnly cookie로만 가진다.
- 모든 사용자 도메인 row는 owner 또는 owner가 소유한 trip을 통해 권한 검증한다.
- trip 확정 상태에는 단조 증가 `version`과 immutable `TripRevision`을 둔다.
- GET trip의 ETag와 mutation의 If-Match를 사용한다.
- 생성/apply는 Idempotency-Key를 요구한다.
- optimization proposal은 immutable하며 승인 전 trip을 수정하지 않는다.
- apply/revert는 transaction 하나와 새 revision으로 처리한다.
- 첫 session bootstrap에서 owner와 session을 한 transaction으로 만들고 mutation 전에 idempotency owner scope가 존재해야 한다.
- session revoke/사용자 삭제 요청은 즉시 접근을 차단하고, 비동기 삭제 receipt 상태와 tombstone을 추적해 backup restore 뒤에도 삭제를 재적용한다.
- 공모전 제출은 `로그인 불필요` 방식을 사용하고 운영자 seed·개인 계정 없이 anonymous owner가 핵심 흐름을 완결한다.

## Consequences

- 로그인 도입 시 anonymous owner를 account owner로 병합하는 migration이 필요하다.
- FE는 409를 일반 실패가 아닌 최신 상태 복구 흐름으로 다룬다.
- 서버에는 idempotency record와 revision 저장 비용이 생기지만 감사·복구가 가능하다.
- 후보 저장은 trip 일정 version을 올리지 않는다.

## Rejected alternatives

- localStorage만으로 여행 보관: 기기 종속, 권한/동시성/복구 불가.
- last-write-wins: 다른 tab 또는 stale preview가 최신 변경을 잃게 한다.
- AI 결과 즉시 적용: Figma의 사용자 승인 계약과 안전 원칙을 위반한다.

## Review trigger

- 장기 계정 로그인/anonymous owner 병합을 도입함
- cookie/CSRF/session rotation 정책 또는 public origin이 바뀜
- offline mutation queue나 여러 기기 동기화를 도입함
- trip command 처리량 때문에 version aggregate 경계를 바꿀 필요가 수치로 확인됨
- 삭제/복구 incident, owner 간 접근 또는 stale apply safety violation이 발생함

검토 DRI는 Backend/AI 담당이며 conflict·recovery·삭제 UX는 Frontend 담당 승인이 필요하다. M1 session/delete, M3 editor, M5 apply와 production security review에서 확인한다.
