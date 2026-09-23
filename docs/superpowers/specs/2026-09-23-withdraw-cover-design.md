---
aliases:
  - "게시물 표지 회수 설계"
doc_type: plan
status: draft
area: operations
tags:
  - nullnull/operations
  - nullnull/security
---

# #338 게시물 회수 시 사용자 표지 회수 설계

## 목적과 경계

권리 철회 등으로 운영자가 `withdraw-post`를 실행하면, 게시물은 기존처럼 모든 서버 읽기에서 즉시 숨기고 그 게시물의 `covers/user/` 표지도 현재 버전과 이전 버전까지 회수한다. 같은 명령의 재실행으로 부분 실패를 복구할 수 있어야 한다. 다른 게시물의 표지, 첫째 당사자 큐레이션 이미지, 웹 번들, 격리 업로드는 삭제 대상이 아니다. 이미 브라우저·사용자 기기에 전달된 사본을 원격으로 지울 수 있다는 주장은 하지 않는다.

기존 피드·작성 계약을 바꾸지 않고 운영자 승인·대상 DB·릴리스 바인딩 검사를 유지한다. 신규 업로드의 `Cache-Control: no-store`, 서비스 워커의 사용자 표지 캐시 우회, 회수된 게시물의 신규 후보 출처 차단은 함께 반영한다. React Query 피드와 상세는 기본 stale/refetch 동작으로 다음 요청 때 서버의 숨김 상태를 받지만, 이미 화면에 표시된 응답과 과거 브라우저 캐시는 되돌릴 수 없다.

## 검토한 경로

1. **기존 ops task에서 DB 숨김 후 S3 회수(선택).** 운영자의 단일 승인 명령과 비밀·DB 경계를 재사용한다. 삭제 권한은 이 task의 `covers/user/*`에만 주고 API 상시 실행 역할에는 주지 않는다. DB와 S3 사이에 원자 트랜잭션은 없으므로 부분 완료를 명시하고 재시도한다.
2. 운영자 로컬 AWS 명령으로 삭제. 게시물의 표지 키를 운영자 단말로 추출해야 하고, 별도 명령·권한·감사 흐름이 생겨 대상 오인과 누락 위험이 크다.
3. 공개 API 요청에서 즉시 삭제. 상시 실행 API 역할에 삭제 권한을 주고 사용자 요청 경로에 실패·지연을 추가한다. 현재 운영자 회수 결정을 공개 API 권한으로 확대하므로 선택하지 않는다.

## 처리 순서와 불변식

`PostWithdrawalService`는 게시물 상태 변경과 표지 참조 읽기를 같은 DB 트랜잭션에서 수행한다. `PUBLISHED → HIDDEN` 또는 이미 `HIDDEN`인 경우에만 삭제 대상으로 진행한다. `DRAFT`·없는 ID는 현재와 같이 거절한다. 트랜잭션이 성공적으로 끝난 뒤 `PostWithdrawMain`이 S3 회수를 실행한다. 따라서 S3 실패가 DB 숨김을 되돌리지 않는다. 재실행의 `ALREADY_HIDDEN`도 동일 표지 회수를 반복한다.

표지는 DB의 URL을 그대로 S3 키로 쓰지 않는다. URL의 origin이 배포된 공개 origin과 정확히 같고, 경로가 `covers/user/<upload UUID>.<허용 확장자>` 형식일 때만 키를 추출한다. URL 인코딩, query, fragment, 상위 경로, 다른 host, 큐레이션 표지는 거절하거나 삭제 대상 없음으로 분류하되 **알 수 없는 사용자 표지를 삭제 성공으로 오인하지 않는다**. 삭제 키와 이미지 URL은 공개 로그에 남기지 않는다. DB의 `cover_asset_id`와 게시물 참조는 감사·재시도를 위해 유지한다.

S3는 bucket versioning을 사용하므로 정확히 한 키로 `ListObjectVersions`를 페이지 끝까지 조회하고, 응답 중 키가 정확히 일치하는 버전과 delete marker만 `versionId`를 지정해 삭제한다. 결과를 다시 조회해 해당 키의 버전·marker가 0개임을 확인한다. 빈 목록은 이미 회수된 멱등 성공이다. 목록 조회 실패, 부분 삭제 오류, 검증 실패는 작업을 실패로 끝내며 다음 실행이 남은 버전을 재시도한다. prefix 인접 키는 삭제하지 않는다.

성공 로그는 게시물 ID, DB 결과(`WITHDRAWN` 또는 `ALREADY_HIDDEN`), 표지 결과(`DELETED`, `ALREADY_ABSENT`, `NOT_USER_UPLOAD`)와 삭제 버전 **개수**만 남긴다. 실패 로그는 고정된 오류 코드와 `cover_cleanup=pending`을 남기고 성공 문구를 출력하지 않는다. 운영자 명령은 task exit code와 정확한 post ID·결과를 검증한다. 기존의 무조건적인 `cover-object-not-deleted` 문구는 성공/실패 결과로 교체한다.

## 권한·캐시·운영

Migration 스택의 `nullnull-stg-ops` task role에만 `s3:ListBucketVersions`(web bucket, `s3:prefix`가 `covers/user/*`)와 `s3:DeleteObjectVersion`(객체 ARN `covers/user/*`)을 추가한다. API service task role의 사용자 표지 권한은 `PutObject`에 머문다. operator IAM은 ECS task 실행을 유지하며 S3 삭제 권한을 직접 받지 않는다. task에는 bucket·region·공개 origin만 환경변수로 전달하고, S3 key를 CLI 인자로 받지 않는다. 배포 diff에서 웹 bucket 정책·CloudFront 설정·API task 권한이 변하지 않았는지 검증한다.

새 표지의 `no-store`와 서비스 워커 우회는 앞으로의 사본 생성을 줄인다. CloudFront 기본 동작은 이미 `CACHING_DISABLED`라 별도 무효화를 전제로 하지 않는다. 과거의 `immutable` 응답을 저장한 브라우저·프록시·기기 사본은 서버에서 원격 삭제할 수 없음을 권리 철회 runbook에 적는다. 운영 결과는 S3 원본 회수와 공개 URL의 새 요청이 실패하는지로만 확인한다.

## 검증과 도입

- DB 상태별 단위·PostgreSQL 통합 검사: `PUBLISHED`, `ALREADY_HIDDEN`, `DRAFT`, 없는 ID, 동시 회수, 표지 없는 큐레이션 게시물, 잘못된 URL.
- S3 버전 API 검사: 여러 버전과 delete marker, 페이지 경계, 동일 prefix의 다른 키, 삭제 중 일부 오류, 조회 실패, 재시도 후 0개. AWS SDK의 실제 요청 필드와 IAM synth의 정확한 리소스·prefix 조건을 검증한다.
- 운영자 검사: 승인·대상 DB·release binding 실패는 S3를 호출하지 않음; DB 숨김 후 S3 실패는 부분 완료로 남고 같은 post ID 재실행에서 복구됨; 원문 URL·key·이미지 바이트를 로그에 출력하지 않음.
- FE 검사: 사용자 표지 응답을 service worker가 캐시하지 않음, 기존 피드·상세 서버 재조회는 HIDDEN을 재노출하지 않음. 기존 후보 출처 패치와 OpenAPI·생성 client를 함께 검증한다.
- 배포 전에 release record와 실제 이미지 digest의 불일치를 무중단 방식으로 정합화한다. 이전 버전을 거짓으로 덮어쓰지 않는다. 운영 버킷의 `covers/user/`에는 현재 대상 객체가 0개이므로 실제 사용자 게시물 삭제를 성공 사례로 주장하지 않는다. 테스트용 버전 객체를 별도 검증한 후 실제 회수는 게시물별 소유자 승인 절차로만 실행한다.
