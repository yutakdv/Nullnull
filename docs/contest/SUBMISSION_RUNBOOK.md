# 공모전 제출 Runbook

- 상태: Accepted operational checklist
- 공식 마감: 2026-09-21 16:00(KST)
- 내부 제출 목표: 2026-09-20 16:00(KST), 공식 마감 최소 24시간 전
- code freeze: 2026-09-19
- 제출 방식: 외부 HTTPS 웹 URL, `로그인 불필요`, 공식 기능설명서 PDF

이 문서는 제출을 실제로 수행할 때 순서대로 체크하는 운영 절차다. 공식 제출 화면과 최신 매뉴얼이 바뀌면 공식 자료를 우선하고 [준수 매트릭스](./COMPETITION_COMPLIANCE_MATRIX.md)를 즉시 갱신한다. 체크하지 않은 항목을 완료로 간주하지 않는다.

## 1. 역할

| 역할 | 담당 | 최종 책임 |
| --- | --- | --- |
| Submission lead | 두 사람이 당일 지정 | 콘텐츠랩 입력·PDF upload·접수 완료 보관 |
| Frontend verifier | Frontend 담당 | 외부망/익명/360px 흐름, 화면 출처, screenshot, a11y |
| Backend/AI verifier | Backend/AI 담당 | KTO 실제 호출·audit, readiness, secret, DB/AWS/rollback |
| Independent checker | lead가 아닌 담당자 | 제출 입력·PDF·URL·API 목록을 소리 내어 대조하고 승인 |

한 사람이 제출 입력을 하고 다른 사람이 화면 공유로 모든 값을 읽어 확인한다. 비밀번호·인증키·AWS account 정보가 화면 녹화나 공개 artifact에 들어가지 않게 한다.

## 2. 제출 대상 값표

09/18까지 아래 빈칸을 [evidence ledger template](./EVIDENCE_LEDGER_TEMPLATE.md)에서 만든 비공개 운영 기록에 확정한다. 이 저장소에는 secret·개인 연락처를 적지 않는다. 부문·유형·과제는 아래 주석의 가정을 그대로 제출하지 않고 공식 제출 화면의 exact label로 채운다.

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

## 3. D-7~D-4: 기능과 데이터 동결 준비

### 09/14–09/16

- [ ] `frontend`, `backend`가 최신 `main`을 포함한다.
- [ ] INT-01 익명 session·여행 생성, INT-02 KTO Feed·후보, INT-03 일정화·편집, INT-04 preview·APPLY/KEEP가 staging에서 완결된다.
- [ ] 미완성 P1은 capability OFF이며 로그인·JA/ZH·알림·주변·게시물 작성·DAY/TRIP 최적화 control이 disabled/준비 중이다.
- [ ] 공모전 profile에서 `FEATURE_NEARBY_LOCATION=OFF`이고 browser geolocation 호출이 없다.
- [ ] 승인된 KTO 운영 key와 quota가 Backend runtime에만 주입된다.
- [ ] KTO 인증키 신청자와 운영계정 신청/승인 상태를 확인하고, 제출 원장에는 key 원문 대신 credential 입력 확인 여부만 기록한다.
- [ ] 최종 사용 KTO operation별 실제 호출과 redacted call-audit가 있다.
- [ ] 화면에 실제 KTO 데이터와 `출처: ⓒ한국관광공사`, 기준시각, source state가 함께 보인다.
- [ ] file/replay/mock-only path를 실제 OpenAPI 활용으로 세지 않는다.
- [ ] 전체/장기 로컬 mirror가 없다. 불가피하면 공식 문의·별도 승인 증거가 있다.
- [ ] Claude Code 사용 PR에는 기능 ID, 사람이 검토한 diff와 실제 test 결과만 남기고 secret·사용자 원문·provider payload를 prompt/transcript에 남기지 않는다. AI 도구 사용 자체를 가점이나 구현 완료로 설명하지 않는다.

09/16 종료 시 INT-01~04 중 하나라도 실패하면 새 기능을 중단한다. P1, 지도 시각화, 부가 animation, 고급 설명을 먼저 줄이고 실제 KTO 활용·안전 불변식·외부 접속·출처는 줄이지 않는다.

## 4. D-3: 09/18 기능설명서 동결

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

- [ ] 대표 이미지 1장은 최종 서비스 정체성을 보여 준다.
- [ ] 상세 이미지 3~5장은 최종 release에서 직접 캡처한다.
- [ ] browser 개발 toolbar, key, cookie, request ID, 내부 URL, 개인 데이터가 보이지 않는다.
- [ ] KTO 데이터가 있는 화면에는 승인된 텍스트 출처가 보인다.
- [ ] 승인 없는 한국관광공사 CI·BI logo를 넣지 않는다.
- [ ] 각 이미지의 releaseVersion·route·capture time·license를 ledger에 남긴다.

### 데이터 활용

- [ ] 한국관광공사 API 목록은 call-audit에서 실제 성공한 operation set과 정확히 일치한다.
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

## 5. D-2: 09/19 code freeze

### Git·CI

- [ ] 모든 제출 변경은 `frontend → main` 또는 `backend → main` PR로 병합됐다.
- [ ] 상대 담당자가 승인했고 unresolved conversation이 없다.
- [ ] `docs-contract` 성공.
- [ ] `docker-integration` full mode 성공. `baseline-only`이면 M0 미완료이므로 제출 NO-GO다.
- [ ] web/API quality, PostgreSQL migration, mobile Playwright, a11y, security/secret scan 성공.
- [ ] `main`, `frontend`, `backend`의 예상 SHA와 contract SHA를 기록했다.

### AWS·서비스

- [ ] production/submission release가 immutable version/digest로 배포됐다.
- [ ] DNS, TLS, CloudFront, API readiness가 정상이다.
- [ ] DB backup/PITR와 직전 web/API rollback target이 있다.
- [ ] alarm 수신을 두 사람이 확인했다.
- [ ] 비용/쿼터 alarm과 KTO 429/timeout degradation이 동작한다.
- [ ] 제출 URL에 관리자 allowlist/VPN/Basic auth가 없다.

### Judge smoke

- [ ] 휴대전화 데이터망 또는 팀 네트워크 밖에서 접속한다.
- [ ] 새 incognito/profile, cookie 없음, 360px으로 시작한다.
- [ ] 계정 생성·로그인 없이 일정 생성부터 APPLY/KEEP까지 완료한다.
- [ ] 새 session/active trip 없음/loading/empty/error/refresh를 확인한다.
- [ ] KTO 실제 call과 같은 response의 화면 출처를 연결한다.
- [ ] 위치 permission prompt, GPS/좌표 request가 없다.
- [ ] console/network에 secret·stack trace·개인정보가 없다.
- [ ] replay로 전환하면 지속 badge와 기준시각이 보인다.

## 6. D-1: 09/20 내부 제출

### 12:00까지

- [ ] 공식 공지, 제출 매뉴얼, 기능설명서 양식 URL을 다시 확인한다.
- [ ] 마감·필수 field·업로드 제한이 바뀌지 않았는지 재검증 기록을 남긴다.
- [ ] 콘텐츠랩 참가 계정 로그인과 이메일 인증을 확인한다.
- [ ] 팀원 전원과 최종 팀/서비스/지정과제 정보를 확인한다.
- [ ] 제출 화면의 exact 부문/유형/지정과제 label과 서비스 개요를 비공개 값표에 확정한다.
- [ ] public URL·release와 PDF checksum이 code-freeze 기록과 같다.
- [ ] KTO operation set과 최근 실제 호출을 다시 확인한다.

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

### 16:00 완료 조건

- [ ] 제출 완료/접수 상태가 화면에 보인다.
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

## 10. NO-GO 선언문

다음 중 하나라도 사실이면 제출 준비 완료로 말하지 않는다.

- 공식 양식/필수 field/PDF render가 검증되지 않음
- 외부망·새 anonymous session에서 핵심 흐름이 끊김
- KTO 운영키의 실제 호출 또는 서비스 화면 사용 증거가 없음
- 기능설명서의 API/기능이 최종 release와 다름
- 출처 누락, 무허가 CI·BI, secret 노출 가능성
- 위치 capability/geolocation/좌표 전송이 켜짐
- `docker-integration`이 M0 이후 full mode로 통과하지 않음
- rollback target 또는 상대 verifier가 없음
- 접수 완료 상태를 확인하지 못함

NO-GO이면 미완성 기능을 숨겨 제출하지 않는다. 공식 문의가 필요한 항목은 공모전 운영 또는 OpenAPI 문의처에 질의하고 답변을 비공개 decision evidence로 남긴다.
