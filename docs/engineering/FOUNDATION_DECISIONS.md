---
aliases:
  - "이슈 10 첫 통합 기반 결정안"
doc_type: decision
status: draft
area: engineering
tags:
  - nullnull/decision
  - nullnull/engineering
---

# 이슈 #10 · 첫 통합 기반 결정안

[GitHub #10](https://github.com/yutakdv/Nullnull/issues/10)의 D1~D5를 현재 B01/BA 작업에 연결한 Backend/AI 결정 제안이다. 구체 값과 재현 검증을 준비했으며 Frontend 교차 승인은 아직 없다. 이 문서만으로 GitHub 이슈, B01 또는 full Docker 완료를 선언하지 않는다.

이슈의 ARC-001/M0는 현재 B01 통합 slice, CON-001은 BA-000/004, DX-001/QA-002는 BA-004/006과 연결한다. 옛 M0 날짜를 새 일정으로 복사하지 않는다. 실제 앱 scaffold·Figma 수정·GitHub ruleset 변경은 이 결정안의 산출물이 아니다.

## D1 · 첫 scaffold는 backend에서 조립

첫 통합 PR의 host는 backend, 작성 책임은 Backend/AI로 제안한다. Frontend는 apps/web·FE test·Docker stage·생성 client wrapper를 검토 가능한 파일 patch로 인계한다.

1. 두 담당자는 같은 main 기준 SHA, OpenAPI SHA와 인계 파일 목록을 적는다.
2. Frontend는 자신의 작업 파일 diff와 검증 기록을 제공한다. 인계물에 기준 SHA, checksum, 작성자를 함께 남긴다.
3. Backend/AI는 인계 범위만 backend에 적용해 API·DB·CI와 조립한다. 상대 역할 브랜치를 직접 push하거나 merge/cherry-pick하지 않는다. 충돌은 해당 파일 작성자가 수정한다.
4. frontend와 backend 사이의 PR 없이 backend → main 하나로 검토한다. Frontend는 자신이 제공한 UI 외에도 API·DB·security·infra 통합을 교차 검토한다.
5. required checks 두 개와 상대 승인을 충족한 뒤 merge commit한다. 양 역할 브랜치는 main으로 동기화한다.

이는 B01 최초 scaffold의 파일 소유권 인계 예외다. 전체 기능을 한 교차 PR로 합치는 일반 규칙이 아니다. FE 인계물의 출처·작성자 기록을 보존하고, 이후 slice는 기존 [브랜치 계약](BRANCH_AND_INTEGRATION.md)을 따른다.

## D2 · type 생성과 HTTP runtime 분리

[Frontend의 #10 검증 기록](https://github.com/yutakdv/Nullnull/issues/10#issuecomment-5558571169)을 채택해 다음 조합을 제안한다. local/CI/tooling image에서 root lockfile의 같은 조합을 쓴다.

| 항목 | 고정 제안 | 근거/실행 상태 |
| --- | --- | --- |
| Node / npm | 24.20.0 / 11.19.0 | 공식 배포본 checksum 확인, Node 24/npm으로 격리 npm ci·생성·strict 검사 실행 |
| openapi-typescript | 7.13.0 | OpenAPI 3.1 const/union/nullable 생성 검증 |
| openapi-fetch | 0.17.0 | 생성 paths를 소비하는 wrapper의 strict 컴파일 검증 |
| TypeScript | 5.9.3 | strict, noUncheckedIndexedAccess, skipLibCheck=false |
| AJV / ajv-formats / js-yaml | 8.20.0 / 3.0.1 / 4.3.1 | 정본 YAML의 schema/example·negative fixture 검증 |

생성 명령은 다음 형태로 고정한다. --default-non-nullable=false가 없으면 request의 default가 있는 선택 필드까지 필수처럼 생성될 수 있다. 계약에서 required인 필드는 이 옵션과 관계없이 필수다.

~~~bash
openapi-typescript docs/api/openapi.yaml --default-non-nullable=false --output packages/api-client/schema.d.ts
openapi-typescript docs/api/openapi.yaml --default-non-nullable=false --output packages/api-client/schema.d.ts --check
~~~

openapi-fetch wrapper 한 곳에서 credentials, CSRF, If-Match, Idempotency-Key, Problem decoding을 처리한다. mutation retry는 [API 규칙](../api/README.md)을 따른다. raw type 재선언이나 any로 생성 문제를 덮지 않는다.

이번 검사에서는 ITEM target 누락, ITEM에 DAY target 전달, KEEP의 revertUntil 접근, 최초 결정 응답에 REVERT 대입, 미등록 ErrorCode를 컴파일 오류로 확인했다. 기본 objective 생략·nullable provenance·선택 attributionShort도 확인했다. 실제 fetch 요청, Spring 직렬화, FE 화면 구현 검증은 B01 이후다.

공식 사용법: [CLI 옵션](https://openapi-ts.dev/cli), [openapi-fetch](https://openapi-ts.dev/openapi-fetch/). 설치 manifest/lock은 [재현 자료](../contracts/review-2026-09-06/README.md)에 있다.

## D3 · tooling stage의 세 명령

모두 apps/web/Dockerfile의 tooling stage 안에서 /workspace를 기준으로 실행한다. B01은 root package script와 아래 실제 도구를 연결하고 exit code를 보존해야 한다. 현재 검토용 CDK smoke를 서비스의 infra:check로 복사해 통과시키지 않는다.

| root 명령 | 실제 수행 | 실패 조건 / 증거 |
| --- | --- | --- |
| api:check | 위 generator --check + AJV 정본 예시/negative fixture + TS strict | 생성 diff·예시/타입 오류는 실패, spec SHA와 실행 수 기록 |
| security:scan | Gitleaks 8.30.1 dir scan + Trivy 0.74.0 filesystem vuln/misconfig scan | secret 또는 미승인 HIGH/CRITICAL은 실패, redacted report |
| infra:check | CDK CLI 2.1140.0 + aws-cdk-lib 2.268.0 + constructs 10.8.1로 실제 infra app typecheck/synth·정책 검사·local template diff | 합성/정책 오류는 실패, template·diff artifact; 정상 변경은 상대 검토 |

CDK CLI와 library의 minor 번호는 같을 필요가 없다. 위 exact pair는 별도로 설치하여 Node 24에서 최소 Stack을 실제 합성해 호환성을 검사했다. 앱의 network/data/API/web 인프라 합성 성공이나 AWS 배포 증거는 아니다. infra:check의 B01 구현은 실제 환경 parameter/stack을 합성하고 공개 storage, wide IAM, DB public access 등 승인된 정책 검사를 실행해야 한다.

Gitleaks는 --redact=100으로 파일만 검사해 git 이력이 Docker image에 없어도 실행한다. 이력 검사는 별도 checkout gate에서 수행하며 파일 검사와 혼동하지 않는다. Trivy는 vuln,misconfig scanner를 명시하고 --offline-scan, --skip-db-update, --skip-java-db-update, --skip-check-update를 적용한다. lockfile·Java dependency inventory와 IaC template이 실제 scan 대상이어야 한다.

도구 binary checksum과 Trivy DB/Java DB/check bundle의 digest·수집 시각은 image build 단계에서 고정한다. runtime에는 다운로드하지 않는다. cache 누락·24시간 초과·scanner 오류는 성공 처리하지 않는다. 도구 설치/캐시 갱신에 필요한 외부망과 runtime의 internal network는 구분한다. 앱 base image의 취약점 검사도 B01 artifact gate에 포함한다.

현재 Gitleaks/Trivy는 공개 release version을 확인했으며 실제 앱/이미지 security scan은 미실행이다. 외부 AWS state와의 diff는 자격증명이 있는 승인 환경의 별도 gate다. PR internal network에서 AWS 조회가 필요한 diff를 실행해 막힌 뒤 skip하지 않는다. local template 입력을 사용하는 diff와 cloud diff의 증거를 별도로 남긴다.

참조: [Gitleaks](https://github.com/gitleaks/gitleaks), [Trivy CLI](https://trivy.dev/docs/latest/references/configuration/cli/trivy_filesystem/), [CDK synth](https://docs.aws.amazon.com/cdk/v2/guide/ref-cli-cmd-synth.html), [CDK diff](https://docs.aws.amazon.com/cdk/v2/guide/ref-cli-cmd-diff.html).

## D4 · PostgreSQL major와 Compose digest

PostgreSQL major는 17로 제안한다. [RDS 공식 버전 문서](https://docs.aws.amazon.com/AmazonRDS/latest/PostgreSQLReleaseNotes/postgresql-versions.html)에서 major 지원을 확인했지만 계정·region의 실제 minor 선택은 B01/배포 시 describe-db-engine-versions로 검증한다. 지역별 사용 가능 상태를 이 문서로 대신하지 않는다.

두 tag의 immutable digest를 registry manifest에서 확인했다. 반영 직전에 다른 작업이 같은 digest와 recommendationTest/외부 DB mode/report volume을 Compose에 추가한 것을 감지했다. 그 변경을 보존하고 이 검토에서는 Compose를 덮어쓰지 않았다.

| 이미지 | 확인한 multi-platform digest |
| --- | --- |
| postgres:17.6-alpine | sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94 |
| curlimages/curl:8.16.0 | sha256:463eaf6072688fe96ac64fa623fe73e1dbe25d8ad6c34404a669ad3ce1f104b6 |

기존 tag를 재현 가능하게 고정한 것이며 해당 minor의 현재 보안 적합성을 승인한 것은 아니다. 앱 base image·PostgreSQL·probe image를 B01에서 scan하고 필요하면 지원 patch로 올려 digest를 다시 잠근다. 운영용 patch는 RDS 지원/보안 수정과 함께 선택하고 같은 major의 local/Testcontainers에서 Flyway·query 검증을 수행한다. 이미지 pull·container 기동·DB 테스트는 아직 미실행이다.

registry/npm metadata와 체크섬: [foundation-toolchain.json](foundation-toolchain.json).

## D5 · 최소 hello의 API와 완료 범위

기존 계약으로 다음 실제 연결을 B01 완료의 최소 요구로 제안한다.

~~~text
360px browser → generated client → createDemoSession
→ HttpOnly cookie + PostgreSQL owner/session
→ refresh → issueCsrfToken → getCurrentOwner
→ 동일 owner·locale 재조회
~~~

BA-010의 session/CSRF와 BA-011의 getCurrentOwner 최소 부분을 BA-004 이후 B01 조립에 앞당겨 포함한다. 두 작업의 기존 선행 조건을 지키며, BA-010/011 전체 완료 표시는 B02의 만료·격리·다중 탭·언어·삭제 연계 검증 뒤에 한다. B01의 BA-006이 미구현 API mock으로 hello를 통과해서는 안 된다.

| 필수 시나리오 | 증거 |
| --- | --- |
| 새 브라우저 bootstrap, valid cookie retry, refresh | 동일 owner, 중복 row 0, memory CSRF 복구 |
| cookie 없는 me, 잘못된 Origin/CSRF, 다른 owner 접근 | 승인 계약의 401/403/404, 요청·token 로그 노출 0 |
| DB 중단 또는 migration 실패 | readiness/통합 gate 실패, 테스트 skip 0 |
| 외부 호출 금지 | internal network + 실제 egress probe 실패 증거 |
| 360px 화면·keyboard | loading/error/retry와 포커스, 실제 backend endpoint 사용 |

B01 seed는 owner/session·locale·disabled capability와 이 hello에 필요한 최소 데이터다. 아직 구현하지 않은 trip/optimizer/Live suite는 별도 planned 상태로 남긴다. 구현한 slice의 필수 suite는 누락/skip을 허용하지 않으며, 이후 각 slice가 병합되면 누적 필수 gate에 포함한다. B10 종료 시 전체 P0 suite가 있어야 한다. full-docker라는 mode는 실제 통합 기반의 실행 방식이지 전체 제품 완성도 표시가 아니다.

검토 중 별도 compose.yml에 local DB의 127.0.0.1:5433 mapping이 추가된 것을 확인했다. local 명령은 docker compose up -d postgres이며 integration Compose와 분리돼 있다. 실제 기동/DB 연결은 해당 B01 작업에서 검증한다. local HTTP의 cookie 이름/Secure 정책은 PM-022 승인·브라우저 검증과 함께 닫는다. 이 두 항목을 결정하지 않고 문서에 있는 개발 명령이 실행 가능하다고 안내하지 않는다.

## 승인과 종료 기록

| 항목 | 현재 | 다음 증거 |
| --- | --- | --- |
| D1 host/인계 경계 | backend 제안 작성 | Frontend 교차 승인 |
| D2 generator/runtime | 기존 FE 제안 채택, BE/AI Node 24 생성·strict 검증으로 승인 | 후속 B01 root lock/client 반영 |
| D3 gate 도구 | exact version 제안, CDK package smoke 확인 | 실제 tooling stage의 세 명령/실패 주입 |
| D4 image/RDS | BE/AI major 17·두 digest 선택, Compose 반영 | 후속 B01 RDS region/minor·scan·DB 실행 |
| D5 hello | FE 이슈 원안 채택, BE/AI endpoint·base seed 포함 결정 | 후속 B01 실제 web/API/DB/E2E |

GitHub 이슈의 결정 합의와 실제 B01 통합 완료를 각각 기록한다. 문서와 재현 자료를 준비했다. 원격 결정 기록과 실제 B01 실행 증거를 별도로 남기며, 이슈의 최신 상태는 연결된 GitHub 기록으로 확인한다. commit/push/merge는 수행하지 않았다.
