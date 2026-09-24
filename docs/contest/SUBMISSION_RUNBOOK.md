---
aliases:
  - "공모전 제출 Runbook"
doc_type: reference
status: baseline
area: contest
tags:
  - nullnull/reference
  - nullnull/contest
---

# 공모전 제출 Runbook

- 상태: Accepted operational checklist
- 공식 마감: 2026-09-21 16:00(KST)
- 배포·제출 전 차단 검사: `python3 scripts/check_actual_call_evidence.py <report> --require-verified`. `CMP-KTO-003`이 EXCLUSION이므로 **actual-call 증거가 없으면 release를 진행하지 않는다.** local 실행 증거는 통과하지 않는다(BA-021-T3은 staging을 요구한다). 제출일의 명령·성공 줄·멈춤 조건은 [제출 release 대조 검사](#제출-release-대조-검사)에 있다.
- 제출 방식: 외부 HTTPS 웹 URL, `로그인 불필요`, 공식 기능설명서 PDF

이 문서는 제출을 실제로 수행할 때 순서대로 체크하는 운영 절차다. 공식 제출 화면과 최신 매뉴얼이 바뀌면 공식 자료를 우선하고 [준수 매트릭스](./COMPETITION_COMPLIANCE_MATRIX.md)를 즉시 갱신한다. 체크하지 않은 항목을 완료로 간주하지 않는다.

> 구현 순서: [B00~B10 실행 계획](../engineering/IMPLEMENTATION_PLAN.md)을 따른다. 공통 KTO·장소·forecast·비교·relation은 B03, Live 전용 서울 연동·area/API/탭은 B10 마지막이다. Live 이전 검수는 핵심 흐름의 중간 gate이며 전체 P0 완료가 아니다.

## 1. 역할

| 역할 | 담당 | 최종 책임 |
| --- | --- | --- |
| Submission lead | 두 사람이 당일 지정 | 콘텐츠랩 입력·PDF upload·접수 완료 보관 |
| Frontend verifier | Frontend 담당 | 외부망/익명/360px 흐름, 화면 출처, screenshot, a11y |
| Backend/AI verifier | Backend/AI 담당 | KTO 실제 호출·audit, readiness, secret, DB/AWS/rollback |
| Independent checker | lead가 아닌 담당자 | 제출 입력·PDF·URL·API 목록을 소리 내어 대조하고 승인 |

한 사람이 제출 입력을 하고 다른 사람이 화면 공유로 모든 값을 읽어 확인한다. 비밀번호·인증키·AWS account 정보가 화면 녹화나 공개 artifact에 들어가지 않게 한다.

## 2. 제출 대상 값표

기능설명서 동결 전에 아래 빈칸을 [evidence ledger template](./EVIDENCE_LEDGER_TEMPLATE.md)에서 만든 비공개 운영 기록에 확정한다. 이 저장소에는 secret·개인 연락처를 적지 않는다. 부문·유형·과제는 아래 주석의 가정을 그대로 제출하지 않고 공식 제출 화면의 exact label로 채운다.

```text
teamName:
serviceName: 널널 / Nullnull  # 제출처 등록값과 대조 후 확정
serviceOverview:
divisionExactLabel: ②-2 웹·앱 구현 부문  # 제출 화면 표시값과 재대조 후 확정
serviceTypeExactLabel:  # 내부 배포 형태는 웹(PWA); 제출 화면 표시값으로 확정
designatedTaskExactLabel:  # 내부 가정은 지정과제 2; 제출처 선택값과 대조 후 확정
publicUrl:
loginMode: 로그인 불필요
releaseVersion:
gitSha:
contractSha:
webArtifactChecksum:
apiImageDigest:
functionPdfVersion:
functionPdfChecksum:
ktoApplicant:
ktoOperationAccountState:
ktoCredentialEntryVerified: false  # 키 원문이 아니라 제출 화면 입력 확인 여부만 기록
ktoOperationsActuallyUsed:
submissionLead:
independentChecker:
```

주석은 제출 시스템에 복사하지 않는다. 팀명·지정과제 등 실제 등록값이 문서 가정과 다르면 제출처 값을 임의 변경하지 말고 기획안/기능설명서의 모든 위치를 함께 바로잡는다.

## 3. 단계 1: 기능과 데이터 동결 준비

### 핵심 흐름 검수

- [ ] `frontend`, `backend`가 최신 `main`을 포함한다.
- [ ] INT-01 익명 session·여행 생성, INT-02 KTO Feed·후보, INT-03 일정화·편집, INT-04 preview·APPLY/KEEP가 staging에서 완결된다.
- [ ] 미완성 P1은 capability OFF이며 로그인·JA/ZH·알림·주변·DAY/TRIP 최적화 control이 disabled/준비 중이다. **게시물 작성은 2026-09-20부터 제출 범위이므로 이 목록에 넣지 않는다**(`A-058`).
- [ ] 공모전 profile에서 `FEATURE_NEARBY_LOCATION=OFF`이고 browser geolocation 호출이 없다.
- [ ] 승인된 KTO 운영 key와 quota가 Backend runtime에만 주입된다.
- [ ] KTO 인증키 신청자와 운영계정 신청/승인 상태를 확인하고, 제출 원장에는 key 원문 대신 credential 입력 확인 여부만 기록한다.
- [ ] 최종 사용 KTO operation별 실제 호출과 redacted call-audit가 있다.
- [ ] 화면에 실제 KTO 데이터와 `출처: ⓒ한국관광공사`, 기준시각, source state가 함께 보인다.
- [ ] file/replay/mock-only path를 실제 OpenAPI 활용으로 세지 않는다.
- [ ] 전체/장기 로컬 mirror가 없다. 불가피하면 공식 문의·별도 승인 증거가 있다.
- [ ] Claude Code 사용 PR에는 기능 ID, 사람이 검토한 diff와 실제 test 결과만 남기고 secret·사용자 원문·provider payload를 prompt/transcript에 남기지 않는다. AI 도구 사용 자체를 가점이나 구현 완료로 설명하지 않는다.

핵심 흐름 검수에서 INT-01~04 중 하나라도 실패하면 새 기능을 중단한다. P1, 지도 시각화, 부가 animation, 고급 설명을 먼저 줄이고 실제 KTO 활용·안전 불변식·외부 접속·출처는 줄이지 않는다.

## 4. 단계 2: 기능설명서 동결

공식 양식의 표, section 순서, 필수 field를 임의로 지우거나 재구성하지 않는다. 저장소의 기획안은 작성 재료이지 공식 양식 자체를 대체하지 않는다.

### 서비스 소개

- [ ] 서비스명·개요·부문/유형 exact label·타깃이 서비스, 기능설명서와 제출처에서 일치한다.
- [ ] 지정과제와 선택 이유가 제출처 선택과 일치한다.
- [ ] 문제→KTO 데이터→분산 개입→사용자 승인 논리가 구체적이다.
- [ ] 해시태그는 실제 기능과 1:1로 연결되고 계획 기능을 완료처럼 표현하지 않는다.

### 핵심 기능과 사용자 흐름

- [ ] 각 기능에 기능 ID, 실제 route/Figma node, 배포 상태와 검증 결과가 있다.
- [ ] `일정 생성 → KTO 기반 탐색 → 여행 후보 → 일정화 → 최적화 preview → 사용자 결정`을 실제 동작 순서로 그린다.
- [ ] 후보 저장과 확정 일정, 게시물 저장을 혼동하지 않는다.
- [ ] AI가 자동으로 일정을 바꾸거나 관광 사실을 만든다고 표현하지 않는다.
- [ ] replay/forecast/stale를 live라고 표현하지 않는다.

### 이미지

촬영 대상은 배포 전에 확정한다. URL이 나온 날 무엇을 찍을지 고르기 시작하면
게이트가 닫힌 화면을 찍거나 mock 화면을 섞게 된다. 아래는 FE가 실제 route와
현재 구현 상태를 확인해 고정한 목록이며, 조건이 바뀌면 이 표를 먼저 고친다.

| # | 종류 | route | 무엇을 보여주는가 | 선행 조건 |
| --- | --- | --- | --- | --- |
| 1 | 대표 | `/feed` | 서비스 정체성. 카드에 KTO 장소·출처와 혼잡 상태 label이 함께 보인다 | catalog 게이트 개방(BA-021-T3), feed 200, **게시물 3~5건**([서식](CURATED_POSTS_TEMPLATE.md), [#183](https://github.com/yutakdv/Nullnull/issues/183)) |
| 2 | 상세 | `/trip/{id}` | 하루 일정과 잠금(필수/날짜/시간)이 독립적으로 표시된다 | 여행 1건과 item 2~3건 |
| 3 | 상세 | `/trip/{id}/candidates` | 후보와 일정이 다른 자원임이 보인다(관계 badge·날짜 선택) | 후보 2건 이상 |
| 4 | 상세 | `/about-data` | 실측·예측·재생·부재를 구분하는 데이터 안내와 출처 | 없음(정적) |
| 5 | 상세 | `/trip/{id}/optimize` | ITEM 범위 선택과 "제안만 만든다"는 preview 고지 | 여행 1건 |

**6번 후보(조건부)**: `/trip/{id}/optimizations/{runId}`의 APPLY/KEEP 결정 화면.
BA-051·BA-052가 열려 FE-503/505가 붙은 뒤에만 촬영한다. 그 전까지 이 route는
"결과 화면 준비 중"을 보여주므로 제출 이미지로 쓰지 않는다.

**찍지 않는 것**: `/live`(준비 중 화면), `/start`의 wizard 단계 전체(진행 중 draft라
개인 입력이 보임), 로그인 CTA·JA/ZH가 보이는 구도. CMP-SUB-008이 "실제 구현·사용한
내용만"을 요구하므로 준비 중 화면은 기능설명서에서도 제외한다.

**촬영 조건**: 360×800 신규 익명 session, 한국어, 개발 toolbar 없음. `/feed`·`/trip`은
catalog 게이트가 열린 뒤여야 실데이터가 나오므로 1·2·3·5번은 그 이후에 찍는다.

- [ ] 대표 이미지 1장은 최종 서비스 정체성을 보여 준다.
- [ ] 상세 이미지 3~5장은 최종 release에서 직접 캡처한다.
- [ ] browser 개발 toolbar, key, cookie, request ID, 내부 URL, 개인 데이터가 보이지 않는다.
- [ ] KTO 데이터가 있는 화면에는 승인된 텍스트 출처가 보인다.
- [ ] 승인 없는 한국관광공사 CI·BI logo를 넣지 않는다.
- [ ] 각 이미지의 releaseVersion·route·capture time·license를 ledger에 남긴다.

### 데이터 활용

- [ ] 한국관광공사 API 목록은 call-audit에서 실제 성공한 operation set과 정확히 일치한다.
  그 set은 staging API와 같은 환경(`SPRING_PROFILES_ACTIVE`·`NULLNULL_ENV=staging`·`SPRING_FLYWAY_ENABLED=false`·datasource, 자격 증명은 URL이 아니라 `SPRING_DATASOURCE_USERNAME`/`PASSWORD`)에서 `NULLNULL_INVENTORY_RELEASE=<releaseVersion> ./gradlew ktoCallInventory`(`apps/api`, call-audit만 읽는다 — job worker를 끄고 배포 환경에서는 migrate하지 않으며, 기동할 때 앱이 늘 하는 삭제 tombstone 재적용만 멱등으로 쓴다. app role은 migration 이력을 읽지 못하므로 Flyway를 끄고 돌며 `schema=unchecked`가 찍힌다)로 뽑고, `counts_as_evidence=true`인 출력의 `kto_` 줄만 `ktoOperationsActuallyUsed`에 옮긴다. **지금 이 명령을 staging DB에 돌릴 경로가 없다**([제출 release 대조 검사](#제출-release-대조-검사)의 ②).
- [ ] 기능 목록과 KTO API 목록을 동결할 때 같은 목록으로 submission ledger를 만든다([ledger 만들기](#ledger-만들기)).
- [ ] API마다 서비스의 어느 기능/화면/field에 사용되는지 적는다.
- [ ] service key, encoded/decoded 인증정보, 전체 호출 URL을 PDF에 넣지 않는다.
- [ ] 기타 데이터도 실제 사용한 것만 적고 source·용도·기준시각·출처를 구분한다.
- [ ] 저장/caching 설명은 사실과 일치하며 전체 mirror를 숨기지 않는다.

### 차별성·발전 계획

- [ ] 현재 구현과 향후 계획을 문장·시제로 명확히 나눈다.
- [ ] 발전 계획은 P1 capability/선행 gate와 연결한다.
- [ ] 아직 측정하지 않은 분산 효과·정확도·사용자 수를 수치로 단정하지 않는다.

### PDF QA

- [ ] 공식 원본 file/version/checksum을 기록했다.
- [ ] 필수 field와 표가 모두 남아 있다.
- [ ] PDF를 새 기기에서 열고 글꼴·한글·image crop·page break·link를 확인했다.
- [ ] document metadata/숨은 comment/revision history에 secret·개인정보가 없다.
- [ ] 최종 PDF checksum과 reviewer 2명의 확인 시각을 기록했다.

## 5. 단계 3: 코드 동결과 회귀 검증

### Git·CI

- [ ] 모든 제출 변경은 `frontend → main` 또는 `backend → main` PR로 병합됐다.
- [ ] 상대 담당자가 승인했고 unresolved conversation이 없다.
- [ ] `docs-contract` 성공.
- [ ] `docker-integration` full mode 성공. `baseline-only`이면 B01 미완료이므로 제출 NO-GO다.
- [ ] web/API quality, PostgreSQL migration, mobile Playwright, a11y, security/secret scan 성공.
- [ ] `main`, `frontend`, `backend`의 예상 SHA와 contract SHA를 기록했다.

### AWS·서비스

- [ ] production/submission release가 immutable version/digest로 배포됐다.
- [ ] DNS, TLS, CloudFront, API readiness가 정상이다.
- [ ] DB backup/PITR와 직전 web/API rollback target이 있다.
- [ ] alarm 수신을 두 사람이 확인했다.
- [ ] 비용/쿼터 alarm과 KTO 429/timeout degradation이 동작한다.
- [ ] 제출 URL에 관리자 allowlist/VPN/Basic auth가 없다.
- [ ] **마지막 배포 뒤에 공개 API edge를 다시 열었다.** `python3 scripts/aws/staging_operator.py edge --state open --plan <배포된 release의 plan.json> --approved-plan-sha256 <그 sha256> --execute` — **`AWS_PROFILE`로 `nullnull-stg-operator`를 타야 하고**, `NULLNULL_VERIFIER_TOKEN`이 env에 있어야 하며(없으면 `edge-open-requires-verifier-token`), plan 디렉터리는 `s3://<ReleaseBucket>/pending/<RUN_ID>-<ATTEMPT>/plan.tgz`에서 받는다.
- [ ] **열린 것을 값으로 확인했다**: `curl -sS -o /dev/null -w '%{http_code} %{content_type}' <PublicUrl>/api/v1/health/live` → **`200 application/json`**. 닫혀 있으면 `503 application/problem+json`이다.

  **이 두 줄이 여기 있는 이유**: `execute`가 **배포와 롤백마다 `TrafficEnabled=false`로 되돌린다**(`staging_operator.py`, *"Preserve the closed edge on every new release/rollback"*). 그래서 단계 3의 배포가 **직전에 열어 둔 것을 다시 닫는다.** 잊으면 아래 `Judge smoke`의 *"휴대전화 데이터망에서 접속한다"* 가 통과하지 못하고, 더 나쁘게는 **화면은 뜨는데 모든 API가 503**이라 사람이 *"느린가 보다"* 로 읽는다 — `/`·`/login`·`/covers/*`는 gate 밖이라 열려 있고 **`/api/*`만 닫힌다**(실측).
  **여는 것은 로컬 전용이다**(`edge-is-local-only`) — CI가 대신 못 한다. 그리고 operator role에는 `cloudformation:UpdateStack`이 **없으므로**(`infra/iam/operator.json`은 읽기 액션만 준다) 스택 parameter를 직접 뒤집는 우회도 없다. 설계된 것이다.

### Judge smoke

- [ ] 휴대전화 데이터망 또는 팀 네트워크 밖에서 접속한다.
- [ ] 새 incognito/profile, cookie 없음, 360px으로 시작한다.
- [ ] 계정 생성·로그인 없이 일정 생성부터 APPLY/KEEP까지 완료한다.
- [ ] 새 session/active trip 없음/loading/empty/error/refresh를 확인한다.
- [ ] KTO 실제 call과 같은 response의 화면 출처를 연결한다.
- [ ] 위치 permission prompt, GPS/좌표 request가 없다.
- [ ] console/network에 secret·stack trace·개인정보가 없다.
- [ ] replay로 전환하면 지속 badge와 기준시각이 보인다.

## 6. 단계 4: 제출 후보 대조와 접수

### 제출 release 대조 검사

제출 release를 고정하고(단계 3의 배포) edge를 연 뒤, 제출 입력 전에 아래 두 검사를 **같은 release**에 돌린다. **이 절의 시작부터 [접수 완료](#접수-완료-조건)까지 배포하지 않는다.** readiness 답에는 release 필드가 없고 API도 자기 release를 응답에 싣지 않는다. 그래서 그 사이 배포가 바뀌면 옛 release의 증거끼리는 서로 맞는데 심사 URL은 새 release를 가리키게 된다. ③을 세 번 돌려 그것을 잡는다: 두 검사 뒤, 제출 화면 입력 직전, 접수 완료 직후. 둘 다 exit 0과 성공 줄이 나와야 다음으로 간다. 아니면 그 release로 제출하지 않는다 — 원인을 해소하고 다시 돌리거나 NO-GO다. 두 성공 줄은 증거 원장에 release와 함께 남긴다. 이 둘이 [BA-073](../roles/BACKEND_AI_PLAYBOOK.md#ba-073)의 `T2`·`T3`을 닫는 증거다.

공통 변수(operator profile로, 값은 기록에 남기지 않는다):

```bash
export AWS_REGION=ap-northeast-2
BUCKET=$(aws cloudformation describe-stacks --stack-name NullnullStgFoundation \
  --query "Stacks[0].Outputs[?OutputKey=='ReleaseBucketName'].OutputValue" --output text)
RELEASE=$(aws s3 cp "s3://$BUCKET/deployed/current.json" - \
  | python3 -c 'import json, sys; print(json.load(sys.stdin)["releaseVersion"])')
DIR=".artifacts/submission/$RELEASE"; mkdir -p "$DIR"   # .artifacts/는 gitignore다
```

#### ① 실제 KTO 호출 증거 (`BA-073-T2`, CMP-KTO-003)

- 입력: `kto-smoke` ops task가 쓴 `.artifacts/aws/evidence/actual-call-$RELEASE.json`. 같은 파일이 release bucket의 `evidence/`에도 올라간다. 다른 기기에서는 `aws s3 cp "s3://$BUCKET/evidence/actual-call-$RELEASE.json" .artifacts/aws/evidence/`로 받는다.
- 파일이 없으면 제출 release에서 `kto-smoke`를 **한 번** 돌린다([staging runbook](../operations/STAGING_DEPLOYMENT_RUNBOOK.md)의 ops task 명령). 거절된 호출은 `KTO_KOR_SERVICE_2` source를 격리하고, 격리되는 동안 그 source의 호출이 멈춘다. 해제는 원인을 확인한 뒤 ops task `release-source-quarantine`(`NULLNULL_SOURCE_RELEASE_APPROVED`, `--source-code`)으로 한다. 그래서 마지막 호출이 통과한 장소로 한 번만 돈다.

```bash
python3 scripts/check_actual_call_evidence.py ".artifacts/aws/evidence/actual-call-$RELEASE.json" \
  --release "$RELEASE" --require-verified
```

- 성공 줄: `actual_call=verified release=<RELEASE> environment=staging operation=KorService2/detailCommon2`
- 멈춤: exit 1과 `actual_call=error …`. 보고서 없음(`--require-verified`라 `blocked`가 아니라 실패다), 다른 release의 보고서, local·test 환경, mock·replay·fixture·file source, 호출 0건, 거절된 호출이 여기에 든다.

#### ② 기능설명서 목록과 release의 대조 (`BA-073-T3`, CMP-SUB-008·CMP-KTO-006)

입력은 셋이다. readiness와 inventory는 release를 고정한 뒤 그 release에서 뽑는다.

- **ledger**: PDF에서 옮긴 [초안](./submission-ledger.draft.json)에 `$RELEASE`를 채워 만든다(아래 "ledger 만들기"). 영문 API(PDF p14 3번)는 오너 결정 (a)에 따라 최종 release가 실제로 부른 뒤에야 이 검사를 통과한다([ledger 초안 §4](./SUBMISSION_INVENTORY_DRAFT.md#4-오너-결정)).
- **readiness**: 제출 release에서 받은 `GET /api/v1/demo/readiness` body다. 답에 release 필드가 없어 검사기가 release에 묶지 못하므로 release를 고정한 **뒤** 그 배포에서 받는다. 두 호출은 `scripts/aws/staging-flows.mjs`의 session·readiness 호출을 curl로 옮긴 것이다. 이 형태로 staging에서 돌려 보지는 않았다. 익명 demo session이 하나 생긴다.

```bash
URL=<publicUrl>; JAR=$(mktemp)
curl -sS --fail-with-body -c "$JAR" -H "Origin: $URL" -H 'Content-Type: application/json' \
    -X POST "$URL/api/v1/demo/sessions" -d '{}' -o /dev/null \
  && curl -sS --fail-with-body -b "$JAR" -H 'Accept: application/json' \
    "$URL/api/v1/demo/readiness" -o "$DIR/readiness.json"; FETCHED=$?; rm -f "$JAR"
[ "$FETCHED" -eq 0 ] \
  && python3 -c 'import json, sys; d = json.load(open(sys.argv[1])); sys.exit(0 if d.get("overall") and d.get("checkedAt") and d.get("capabilities") else "readiness=failed body is not a DemoReadiness")' "$DIR/readiness.json" \
  && echo readiness=captured || { echo readiness=failed >&2; rm -f "$DIR/readiness.json"; false; }
```

- 성공 줄: `readiness=captured`. `--fail-with-body`는 4xx·5xx에서 curl을 실패로 끝낸다(로컬 서버의 401로 exit 22를 쟀다). 그 뒤 body가 `overall`·`checkedAt`·비어 있지 않은 `capabilities`를 가졌는지 본다. 검사기는 capability를 묶은 기능에 대해서만 readiness를 읽으므로, 오류 body가 readiness 자리에 들어가면 `capability: null`뿐인 ledger는 그대로 통과한다. 이 두 단계가 그 구멍을 막는다.

- **KTO call inventory — operator ops task로 뽑는다.** `NULLNULL_OPERATIONS_TARGET=postgresql://<rds-endpoint>:5432/nullnull python3 scripts/aws/staging_operator.py task --task kto-call-inventory`. 배포된 release에 결속돼 돌고(`deployed/current.json`의 release와 image·`APP_RELEASE_VERSION`이 같아야 뜬다), 성공하면 `kto_inventory_file=<경로> release=<rc> operations=<n> counts_as_evidence=true …`를 찍는다. 그 `<경로>`를 `$DIR/kto-inventory.txt`로 복사한다 — 접두어 없는 원문이라 아래 검사기가 그대로 읽는다. 멈춤: `inventory-header-missing`·`inventory-not-for-the-deployed-release`·`inventory-incomplete`·`ops-image-not-the-deployed-release`·`release-version-reused-by-another-artifact`·`task-log-not-fully-read`. **목록은 뽑은 시각까지의 호출이다** — 같은 release가 그 뒤 새 종류의 KTO API를 부르면 옛 파일은 모른다. 기능설명서를 동결하기 직전에 다시 뽑고, 접수 뒤에 한 번 더 뽑아 operation 집합이 같은지 본다. raw `aws ecs run-task`로 `LOADER_MAIN`만 바꾸는 길은 배포 잠금·release 결속·log 회수를 건너뛰므로 제출 증거를 만드는 데 쓰지 않는다.

```bash
python3 scripts/check_submission_inventory.py --ledger "$DIR/ledger.json" \
  --readiness "$DIR/readiness.json" --inventory "$DIR/kto-inventory.txt"
```

- 성공 줄: `submission_inventory=verified release=<RELEASE>`
- 멈춤: exit 1과 `submission_inventory=error …` 한 줄 이상. 다음 경우에 멈춘다.
  - P1 기능을 적었다.
  - capability가 READY가 아닌 기능을 적었다. 예를 들어 `optimization`에 묶은 기능은 그 release의 readiness에서 `optimization`이 READY여야 한다.
  - PDF의 API 목록과 inventory가 어느 방향으로든 다르다.
  - `usedBy`가 목록에 없는 기능을 가리킨다.
  - ledger와 inventory의 release가 다르다.
  - inventory에 `counts_as_evidence=true`가 없다.
- **검사기가 보지 않는 것: `pdfLabel`.** 비어 있어도 통과한다(측정했다). ledger의 문구가 PDF의 실제 문구와 같은지는 independent checker가 PDF를 옆에 두고 읽어서 대조한다.
- **검사기가 믿는 것: 기능마다 적은 `capability`.** `capability`가 `null`인 기능은 readiness를 보지 않는다(`judge()`가 null이 아닐 때만 대조한다). 그래서 capability가 필요한 기능을 `null`로 적으면 그 capability가 꺼져 있어도 통과한다. 기능→capability의 정본 표는 아직 없다(초안은 [ledger 초안 §2](./SUBMISSION_INVENTORY_DRAFT.md#2-기능)). 알려진 것은 [기능 인벤토리](../product/FUNCTIONAL_INVENTORY.md)의 P0 `FR-OPT-*`가 모두 최적화 흐름이라 `optimization`에 묶인다는 것이다(flag가 꺼져 있으면 `createOptimization`이 거절한다). `null`로 둔 기능마다 independent checker가 judge smoke에서 그 기능이 capability 없이 동작하는 것을 확인한다.

#### ③ 같은 release였는지 확인

```bash
NOW=$(aws s3 cp "s3://$BUCKET/deployed/current.json" - \
  | python3 -c 'import json, sys; print(json.load(sys.stdin)["releaseVersion"])')
LOCK=$(aws dynamodb get-item --table-name nullnull-stg-deployment-lock \
    --key '{"LockId": {"S": "staging"}}' --consistent-read --output json \
  | python3 -c 'import json, sys; t = sys.stdin.read().strip(); print("held" if (json.loads(t) if t else {}).get("Item") else "")')
[ -z "$LOCK" ] && [ "$NOW" = "$RELEASE" ] && echo "submission_release=unchanged release=$RELEASE" \
  || { echo "submission_release=changed-or-deploying expected=$RELEASE current=$NOW lock=${LOCK:-free}" >&2; false; }
```

- 성공 줄: `submission_release=unchanged release=<RELEASE>`
- 잠금을 같이 보는 이유: 배포는 WebEdge·Services를 바꾼 **뒤에** `current.json`을 쓴다. 그래서 진행 중인 배포는 `current.json`만 봐서는 보이지 않는다. 배포 전체(그리고 `edge`)는 배포 잠금 안에서 돌므로, 잠금이 비어 있고 `current.json`이 같으면 그 순간 진행 중인 배포도 없다. **그 뒤의 배포까지 막는 장치는 없다.** 접수 완료까지 배포하지 않는 것은 사람이 지키는 규칙이다. 잠금 응답의 해석은 빈 응답·`{}`·`Item`이 있는 응답으로 로컬에서 쟀고, AWS 호출은 여기서 돌려 보지 않았다.
- 멈춤: `changed-or-deploying`(exit 1)이면 `$DIR`의 산출물과 ①·②의 성공 줄을 버리고 새 release로 처음부터 다시 한다. 제출 입력 직전과 접수 완료 직후에도 같은 명령을 다시 돌린다. 접수 뒤에 `changed`가 나오면 제출된 URL이 검사한 release가 아니므로 [긴급 변경](#8-freeze-이후-긴급-변경)으로 다룬다.
- 실패 분기는 `exit`가 아니라 `false`로 끝나므로, 대화형 셸을 닫지 않고 명령 자체가 exit 1이 된다(readiness와 ③ 모두).

#### ledger 만들기

- 무엇: 기능설명서의 기능 목록과 KTO API 목록을 data로 쓴 JSON이다. 형식은 `scripts/check_submission_inventory.py`의 docstring에 있고, 아직 **draft**다(오너·FE 합의 전).
- 초안: [`submission-ledger.draft.json`](./submission-ledger.draft.json). 제출 PDF의 p5~p8 "연계 기능"과 p14 KTO API 목록을 옮겼다. 근거·매핑·남은 결정은 [제출 inventory ledger 초안](./SUBMISSION_INVENTORY_DRAFT.md)에 있다.
- **KTO 목록은 PDF에서 온다. inventory에서 뽑지 않는다.** 검사기는 두 목록을 양방향으로 대조한다. 둘이 같은 출처면 PDF에 있는데 release가 부르지 않은 API가 원장에서 사라지고, 대조는 공허하게 통과한다. 합성 입력으로 쟀다: PDF는 셋을 적고 release는 둘을 불렀는데, inventory에서 뽑은 ledger가 `verified`였다([ledger 초안 §5](./SUBMISSION_INVENTORY_DRAFT.md#5-합성-입력으로-돌린-결과)의 S5).
- `releaseVersion`은 inventory 헤더가 아니라 위 공통 변수 `$RELEASE`에서 채운다. 그래야 검사기의 release 대조가 *"이 inventory가 제출 release의 것인가"* 를 묻는다. 헤더에서 복사하면 그 대조도 공허하다.

```bash
python3 -c '
import json, sys
ledger = json.load(open(sys.argv[1], encoding="utf-8"))
ledger["submissionInventory"]["releaseVersion"] = sys.argv[2]
print(json.dumps(ledger, ensure_ascii=False, indent=2))
' docs/contest/submission-ledger.draft.json "$RELEASE" > "$DIR/ledger.json"
```

- 이 명령은 합성 입력으로만 돌려 봤다. staging release에서는 돌려 보지 않았다.
- 영문 API(p14 3번)는 release가 실제로 불러야 대조를 통과한다. 부르지 않은 release에서는 `the PDF lists KTO_ENG_SERVICE/ENG_SERVICE_2_DETAIL_COMMON_2, which the release never called usably`로 멈춘다([ledger 초안 §5](./SUBMISSION_INVENTORY_DRAFT.md#5-합성-입력으로-돌린-결과)의 S1).
- 매핑이나 결정이 바뀌면 초안 JSON을 고친다. release가 바뀌면 위 명령으로 다시 만든다.
- 초안을 고칠 때 칸마다 지키는 규칙이 있다.
  - `featureIds`: [기능 인벤토리](../product/FUNCTIONAL_INVENTORY.md)의 **P0** ID만 쓴다.
  - `pdfLabel`: PDF 문구를 그대로 쓴다.
  - `capability`: 그 기능이 `live`·`replay`·`optimization` 없이 동작하지 않으면 그 이름을, 아니면 `null`을 쓴다. `FR-OPT-*`는 `optimization`이다.
  - `usedBy`: `features`에 있는 ID만 쓴다.
- 검사기가 통과한 뒤에는 값표의 `ktoOperationsActuallyUsed`가 이 ledger의 `ktoOperations`와 같은 목록이다.

### 제출 입력 전

- [ ] 공식 공지, 제출 매뉴얼, 기능설명서 양식 URL을 다시 확인한다.
- [ ] 마감·필수 field·업로드 제한이 바뀌지 않았는지 재검증 기록을 남긴다.
- [ ] 콘텐츠랩 참가 계정 로그인과 이메일 인증을 확인한다.
- [ ] 팀원 전원과 최종 팀/서비스/지정과제 정보를 확인한다.
- [ ] 제출 화면의 exact 부문/유형/지정과제 label과 서비스 개요를 비공개 값표에 확정한다.
- [ ] public URL·release와 PDF checksum이 code-freeze 기록과 같다.
- [ ] KTO operation set과 최근 실제 호출을 다시 확인한다([제출 release 대조 검사](#제출-release-대조-검사)의 ①·② 성공 줄).

### 제출 화면 입력

1. 참가 신청 계정으로 한국관광 콘텐츠랩에 로그인한다.
2. 이메일 인증 상태와 올바른 팀/서비스를 확인한다.
3. 최종 팀원과 서비스명·개요·부문/유형의 exact label·지정과제 1개를 입력·대조한다.
4. 웹 URL을 입력하고 테스트 방식은 `로그인 불필요`를 선택한다.
5. 최종 서비스가 실제 사용한 KTO OpenAPI, 신청자 인증키 정보와 운영계정 신청 여부를 입력한다. 키 원문은 제출 화면 밖으로 복사하지 않고 원장에는 `ktoCredentialEntryVerified=true`와 exact 운영계정 상태만 기록한다.
6. 공식 양식의 최종 PDF를 업로드한다.
7. independent checker가 입력값·file name/checksum·URL을 다시 읽어 확인한다.
8. 제출하고 완료 화면, 접수 번호/시각과 제출본 정보를 비공개로 보관한다.
9. 다시 제출 상세에 들어가 값과 파일이 저장됐는지 확인한다.

### 접수 완료 조건

- [ ] 제출 완료/접수 상태가 화면에 보인다.
- [ ] 접수 직후 [③](#③-같은-release였는지-확인)이 `submission_release=unchanged`다.
- [ ] submission lead와 independent checker가 완료 시각을 서명했다.
- [ ] PDF·URL·KTO API 목록·팀/서비스 개요/부문/유형/과제·운영계정 상태가 최종 ledger와 일치한다.
- [ ] evidence backup이 두 담당자가 접근 가능한 보호된 위치에 있다.
- [ ] 이후 변경은 아래 긴급 기준만 따른다.

## 7. 09/21 공식 마감일

- 09:00: URL/readiness/TLS/KTO quota와 alarm을 읽기 전용으로 확인한다.
- 12:00: 공식 공지 변경 여부와 제출 완료 상태를 확인한다.
- 14:00: 외부망 익명 smoke 한 번, 실제 KTO call과 attribution 한 번을 재검증한다.
- 15:00: 변경 종료. 현재 정상 release와 제출 화면을 고정한다.
- 15:30: 접수 완료·PDF checksum·URL을 마지막으로 읽어 확인한다.
- 16:00: 공식 마감. 이후 수정 가능성을 가정하거나 재업로드를 시도하지 않는다.

내부 제출이 이미 완료됐다면 마감일에는 불필요하게 재제출하지 않는다. 공식 화면에서 수정이 필요한 문제를 발견하면 두 사람이 영향·마감·rollback을 확인한 뒤에만 처리한다.

## 8. Freeze 이후 긴급 변경

허용 후보:

- URL/TLS/readiness 불능
- 핵심 judge journey 중단
- KTO 실제 호출/출처 불능
- secret·개인정보·위치 또는 일정 무결성 문제

허용하지 않는 변경:

- 새 기능·animation·copy 취향 개선
- P1 capability 활성화
- schema/migration의 비호환 정리
- 검증되지 않은 dependency/tool 대규모 upgrade

절차:

1. incident ID와 영향/현재 정상 rollback target을 적는다.
2. 영향 담당자의 역할 브랜치에서 최소 수정한다.
3. 상대가 review하고 두 required check와 영향 E2E를 통과한다.
4. `main` merge/deploy 뒤 외부망 judge smoke와 PDF 목록 영향 여부를 확인한다.
5. 제출 정보가 달라졌다면 공식 마감 전에 다시 대조·접수 증거를 갱신한다.

## 9. 제출 후 운영

- 심사 기간 중 URL, DNS/TLS, 익명 session, KTO quota/readiness와 핵심 alarm을 유지한다.
- scheduled shutdown이 심사 시간과 충돌하지 않게 한다.
- key rotation, provider drift, outage는 [사고 대응](../operations/INCIDENT_RESPONSE.md)을 따른다.
- replay/degraded 상태는 화면에 그대로 표시하고 실제 KTO 활용 증거와 구분한다.
- 기능심사 중 배포 변경은 release ID·이유·검증·rollback을 기록한다.
- 최종 대상 발표일 2026-10-21 전후 공지를 확인하고, 선정되면 10/28 발표시간·형식·장비를 최신 안내에서 확정한다.
- 공개 evidence 보존은 secret/개인정보를 제외하고, 접수·provider 이력은 보호된 저장소의 retention 정책을 따른다.

### 심사 release 최종 증거(#305·#53)

심사 때 배포돼 있는 release가 증거의 대상이다. 증거는 release 이름에 묶이므로 **마지막 배포 뒤에 한 세션에서 이 순서로 한 번에** 모은다. 중간에 배포가 끼면 처음부터 다시 한다. 각 단계는 성공 줄이 나와야 다음으로 간다. 명령과 멈춤 조건은 가리키는 절에 있고, 여기서는 순서와 성공 줄만 적는다.

시작 전에 오너가 정한다.

- [ ] 최종 release를 동결했다. 수집이 끝날 때까지 다른 배포를 하지 않는다.
- [ ] 최종 release에 #360·#367이 들어 있다. 영문 API(PDF p14 3번)는 오너 결정 A-066(경로 (a))으로 최종 release에서 부른다([ledger 초안 §4](./SUBMISSION_INVENTORY_DRAFT.md#4-오너-결정)).
- [ ] 이 순서의 KTO 실호출을 승인했다: `kto-smoke`, `kto-demo-forecast`, `kto-eng-text-refresh`. 영문 연결 plan의 바이트도 승인했다.

순서는 다음과 같다.

1. [ ] **배포**: 최종 main SHA를 operator로 배포한다([staging runbook](../operations/STAGING_DEPLOYMENT_RUNBOOK.md) §11). 성공 줄은 `deployment_action=executed …`다.
   - operator 배포는 smoke·flows를 스스로 돌리지 않는다. 배포 뒤에 그 둘을 돌리는 것은 CD workflow뿐이다. 그래서 §11의 `staging-smoke.sh`와 `staging-flows.mjs`를 이어서 돌린다. edge가 열려 있으면 flows에 `--expect-edge open`을 준다.
   - 성공 줄: `staging_smoke=pass …`, `staging_flows=pass …`
   - flows 출력에 `pass catalog.search`가 있고 `catalog.closed-is-explicit`가 없어야 한다. flows는 닫힌 catalog 게이트(503 `SOURCE_UNAVAILABLE`)도 `pass`로 센다. readiness도 그 게이트를 보이지 않는다(R-045).
2. [ ] **edge**: 단계 3 "AWS·서비스"의 edge 두 줄을 따른다. 성공 줄은 `edge=open release=<RELEASE> public_health_status=200`이다.
3. [ ] **공통 변수**: [제출 release 대조 검사](#제출-release-대조-검사)의 `RELEASE`·`DIR`을 잡는다.
4. [ ] **KTO 실제 호출**(`BA-021-T3`·`BA-073-T2`): `kto-smoke`를 마지막 호출이 통과한 장소로 한 번 돌린다.
   - operator 성공 줄: `actual_call=verified release=<RELEASE> …`
   - 이어서 ①의 검사를 돌린다.
5. [ ] **예보 호출**: inventory에 p14 2번이 나오도록 이 release에서 예보를 부른다. `task --task kto-demo-forecast`의 성공 줄은 `KTO_DEMO_REFRESH_DONE mode=forecast …`이다. 12시간 schedule도 배포된 release의 ops 정의로 같은 호출을 한다. 배포 뒤 첫 tick 전이면 직접 돌린다.
6. [ ] **영문 호출**(오너 결정 (a)): staging runbook §11의 영문 명령 두 개를 이 순서로 돌린다.
   - `task --task kto-eng-link-import`: 오너가 승인한 연결 plan을 들인다. 성공 줄은 장소마다 `eng_link <placeId> PROCESSED`와 `eng_links_processed=<n>`이다.
   - `task --task kto-eng-text-refresh`: 연결마다 EngService2 detailCommon2를 부른다. 연결마다 `KTO_ENG_TEXT_REFRESH placeId=… outcome=…`이 찍히고, 끝에 `KTO_ENG_TEXT_REFRESH_DONE …`이 찍힌다. 하나라도 실패하면 task가 실패하고 배포 잠금이 남는다.
7. [ ] **KTO 호출 목록**(`BA-073-T3`): `task --task kto-call-inventory`를 돌린다.
   - 성공 줄: `kto_inventory_file=… release=<RELEASE> … counts_as_evidence=true`
   - 그 파일을 `$DIR/kto-inventory.txt`로 둔다.
8. [ ] **readiness**: ②의 readiness 명령을 돌린다. 성공 줄은 `readiness=captured`다.
9. [ ] **ledger 대조**: [ledger 만들기](#ledger-만들기)를 한 뒤 ②의 검사를 돌린다.
   - 성공 줄: `submission_inventory=verified release=<RELEASE>`
   - `… KTO_ENG_SERVICE/ENG_SERVICE_2_DETAIL_COMMON_2, which the release never called usably`로 멈추면 6을 이 release에서 돌렸는지 본다.
10. [ ] **secret 노출**(CMP-KTO-007): `staging_operator.py secret-scan`을 돌린다.
    - 성공 줄: `secret_exposure=clean …` 또는 `clean-partial`. operator가 읽지 못한 secret은 `secret_exposure_partial reason=…`으로 나온다.
    - `leaked`면 멈춘다.
11. [ ] **외부망 익명 완주**(`BA-073-T1`): 위 [Judge smoke](#judge-smoke)를 이 release에서 한다.
    - READY 제안이 나오는 날짜 쌍은 먼저 `staging-flows.mjs --survey`로 찾는다.
    - 기록에서 IP·cookie·개인 입력을 뺀다.
12. [ ] **inventory 다시 뽑기**: 7을 다시 돌려 operation 집합이 같은지 본다. 완주가 새 종류의 KTO 호출을 만들었으면 9를 다시 한다.
13. [ ] **release gate**(`BA-073-T4`·`T5`): 그 release의 main SHA에서 돈 `docker-integration` run URL을 남긴다.
    - T5는 E2E JUnit에 `BA-073-T5` 이름으로 나온다(`location-off.spec.ts`).
    - T4는 E2E JUnit에 `BA-073-T4` 이름으로 나온다(`attribution.integration.spec.ts`, #53). 실제 API의 여행 응답이 출처를 준 장소마다 그 카드가 서버 문구를 그대로 그리는지 본다. web suite의 `FE-603-T4`는 그 장소를 그리는 파일이 `DataAttribution`을 쓰는지 보는 파일 단위 검사다.
14. [ ] **같은 release 확인**: ③을 돌린다. 성공 줄은 `submission_release=unchanged release=<RELEASE>`다.
15. [ ] **묶음**: 아래를 `$DIR`에 모으고 #305·#53에 요약 코멘트를 단다.
    - 1·2의 성공 줄
    - actual-call JSON과 ① 출력
    - inventory 두 벌, readiness, ledger와 ② 출력
    - secret-scan 출력, 완주 기록, gate run URL
    - ③ 출력

## 10. NO-GO 선언문

다음 중 하나라도 사실이면 제출 준비 완료로 말하지 않는다.

- 공식 양식/필수 field/PDF render가 검증되지 않음
- 외부망·새 anonymous session에서 핵심 흐름이 끊김
- KTO OpenAPI의 실제 server-side 호출 또는 서비스 화면 사용 증거가 없음(키 등급과 무관하다 — 제출은 개발 키로 간다, PM-023)
- 기능설명서의 API/기능이 최종 release와 다름
- 출처 누락, 무허가 CI·BI, secret 노출 가능성
- 위치 capability/geolocation/좌표 전송이 켜짐
- `docker-integration`이 B01 이후 full mode로 통과하지 않음
- rollback target 또는 상대 verifier가 없음
- 접수 완료 상태를 확인하지 못함

NO-GO이면 미완성 기능을 숨겨 제출하지 않는다. 공식 문의가 필요한 항목은 공모전 운영 또는 OpenAPI 문의처에 질의하고 답변을 비공개 decision evidence로 남긴다.
