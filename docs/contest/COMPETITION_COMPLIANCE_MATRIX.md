# 2026 공모전 준수 매트릭스

- 상태: Accepted submission gate
- 공식 공지 재확인: 2026-09-05
- 대상: 관광데이터 활용 공모전 `②-2 웹·앱 구현 부문` Nullnull 제출본; exact label은 제출 화면에서 재확인
- 역할: Frontend 1명, Backend/AI 1명, 제출 go/no-go는 공동

이 문서는 [공식 공지·심사 기준 요약](./2026-관광데이터-활용-공모전-공지-심사기준.md)을 구현·검증 증거로 바꾼 내부 계약이다. `공식` 행은 외부 요구·공지 내용이고 `팀 결정` 행은 위험을 줄이기 위한 Nullnull 내부 정책이다. 공식 문서가 바뀌면 공식 자료가 우선하며 이 매트릭스와 관련 계약을 같은 PR에서 갱신한다.

## 1. 상태와 차단 수준

| 수준 | 의미 | 미충족 시 처리 |
| --- | --- | --- |
| `EXCLUSION` | 누락·불일치로 심사 제외 또는 기능 확인 불가 가능 | 제출 금지 |
| `REQUIRED` | 필수 활용·기술/운영 요구 | 제출 금지 |
| `SCORE` | 심사 배점에 직접 연결 | 증거가 없으면 완료로 주장하지 않음 |
| `RECOMMENDED` | 공식 권고 또는 불이익 예방 | 예외 사유와 공식 문의 결과 필요 |
| `INFO` | 공식 허용 범위 또는 오해 방지 안내 | 필수·가점으로 과장하지 않음 |
| `INTERNAL` | Nullnull이 정한 더 엄격한 안전 gate | 두 사람 승인 없이는 예외 금지 |

증거 상태는 `NOT_STARTED`, `IN_PROGRESS`, `VERIFIED`, `NOT_APPLICABLE`만 사용한다. 문서가 존재한다는 이유로 구현 증거를 `VERIFIED`로 바꾸지 않는다. 각 requirement의 현재 상태와 evidence ID는 [비밀값 없는 evidence ledger template](./EVIDENCE_LEDGER_TEMPLATE.md)을 복사한 보호 저장소의 원장에 requirement ID별로 정확히 한 번 기록한다.

## 2. 공식 제출 요건

| ID | 성격/수준 | 요구사항 | Nullnull 구현 결정 | 완료 증거 | DRI / 검토 |
| --- | --- | --- | --- | --- | --- |
| CMP-SUB-001 | 공식/EXCLUSION | 1차 자료 제출은 2026-09-21 16:00 정각까지이며 이후 수정 불가 | 09-20 16:00 내부 제출, 09-21 15:00 변경 종료 | 접수 완료 화면·시각, 제출 PDF checksum | 공동 / 공동 |
| CMP-SUB-002 | 공식/EXCLUSION | 참가 신청 계정, 이메일 인증, 팀/서비스 선택으로 제출 | 대표 계정과 팀원 계정을 09-18 전에 확인 | 콘텐츠랩 팀원 화면·인증 상태의 비공개 확인 기록 | 공동 / 공동 |
| CMP-SUB-003 | 공식/EXCLUSION | 최종 팀원, 팀명·서비스명·개요·부문/유형·지정과제 1개를 정확히 입력 | 부문을 포함한 exact label과 값을 서비스/PDF/제출처의 한 표에서 대조 | 3개 위치의 값과 2인 확인 시각 | 공동 / 공동 |
| CMP-SUB-004 | 공식/REQUIRED | 외부에서 접속 가능한 웹 URL 또는 승인된 앱스토어 링크 | 웹 URL만 제출하고 PWA를 앱스토어 앱으로 주장하지 않음 | 외부망·새 browser profile HTTPS smoke | FE / BE·AI |
| CMP-SUB-005 | 공식/REQUIRED | 로그인 방식은 로그인 불필요/SNS/테스트 계정 중 선택 | `로그인 불필요`; anonymous session에서 저장 포함 핵심 흐름 완결 | 신규 session E2E, 제출 화면 선택값 | FE / BE·AI |
| CMP-SUB-006 | 공식/EXCLUSION | 공식 기능설명서 양식과 필수 항목을 유지해 PDF 제출 | 양식의 표·순서·필수 field를 임의 변경하지 않음 | 원본 version/checksum, PDF render, field checklist | 공동 / 공동 |
| CMP-SUB-007 | 공식/REQUIRED | 대표 이미지 1장, 상세 이미지 3~5장 등 공식 구성 준수 | 실제 배포 화면만 사용하고 mock/P1 screenshot 제외 | image ledger, URL/release ID, alt/caption, PDF 확인 | FE / BE·AI |
| CMP-SUB-008 | 공식/REQUIRED | 기능설명서에는 최종 서비스에서 실제 구현·사용한 내용만 기재 | disabled·준비 중·mock-only·계획 기능 제외 | PDF 기능 목록 ↔ release journey/test 대조 | 공동 / 공동 |
| CMP-SUB-009 | 공식/EXCLUSION | 지정과제 문제 해결 기능과 KTO OpenAPI 활용 모두 완성 | 현재 내부 가정은 `지정과제 2`; exact 과제명은 제출처 선택값과 대조한 뒤에만 확정 | 과제 선택 화면, 핵심 journey, KTO 증거 | 공동 / 공동 |
| CMP-SUB-010 | 공식/EXCLUSION | 동일 서비스를 타 부문 중복 출품하지 않으며 제외 이력 조건 확인 | 대표가 팀 이력을 서면 확인 | private eligibility checklist | 공동 / 공동 |
| CMP-SUB-011 | 공식/EXCLUSION | 제출 화면에 실제 KTO OpenAPI, 신청자 인증키 정보, 운영계정 신청 여부를 정확히 입력 | 키 원문은 제출 화면에만 입력하고 원장에는 입력 확인 여부와 운영계정 상태만 기록 | 제출 상세 재확인, credential 입력 확인 boolean, 운영계정 exact 상태 | BE/AI / FE |

## 3. 한국관광공사 OpenAPI 필수 활용

| ID | 성격/수준 | 요구사항 | Nullnull 구현 결정 | 완료 증거 | DRI / 검토 |
| --- | --- | --- | --- | --- | --- |
| CMP-KTO-001 | 공식/REQUIRED | 한국관광공사 OpenAPI를 실제 서비스에서 사용 | browser가 아닌 Backend gateway가 운영키로 호출 | staging/submission actual-call smoke | BE/AI / FE |
| CMP-KTO-002 | 공식/REQUIRED | 제출 인증키의 API별 호출 이력을 확인할 수 있음 | operation·시각·outcome·count·release/provenance를 redacted audit로 연결 | provider 이력과 내부 call-audit 대조 | BE/AI / FE |
| CMP-KTO-003 | 공식/EXCLUSION | 파일 데이터만 사용한 것은 필수 OpenAPI 활용으로 불인정 | file/replay/mock은 test/fallback 전용 | actual-call 없는 release를 배포/제출 차단하는 test | BE/AI / FE |
| CMP-KTO-004 | 공식/RECOMMENDED | 동기화 문제를 줄이기 위해 실시간 호출 권고 | quota-aware read-through/refresh 사용 | TTL/refresh 설정, 실제 call과 기준시각 | BE/AI / FE |
| CMP-KTO-005 | 공식/RECOMMENDED | 전체 로컬 저장으로 호출 이력이 없으면 불이익 가능; 불가피하면 별도 확인 | 최소 정규화 record/TTL만 저장, 전체 mirror 금지 | DB inventory와 retention test | BE/AI / FE |
| CMP-KTO-006 | 공식/REQUIRED | 최종 서비스에서 실제 호출한 OpenAPI만 제출 | operation inventory를 release call-audit에서 생성해 사람 검토 | PDF API 목록 ↔ audit operation set diff 0 | BE/AI / FE |
| CMP-KTO-007 | 팀 결정/INTERNAL | key를 browser/Git/log/PDF에 노출하지 않음 | Secrets Manager/runtime injection, query/body redaction | bundle/source-map/repo/log/PDF secret scan | BE/AI / FE |
| CMP-KTO-008 | 팀 결정/INTERNAL | provider 원문을 운영 증거에 복제하지 않음 | audit는 허용 field만, 원문 body와 전체 URL 제외 | schema allowlist·canary test | BE/AI / FE |

실제 호출 증거와 테스트 fixture를 구분한다.

- PR Docker: 외부망 차단, schema-valid deterministic fixture. 재현성 증거다.
- staging/submission: 승인된 runtime secret, 공식 host, 실제 operation. 필수 활용 증거다.
- replay: source 장애 시 UX를 유지하는 보조 수단. 실제 KTO 활용 증거가 아니다.

### 생성형 AI 개발도구

| ID | 성격/수준 | 요구사항 | Nullnull 구현 결정 | 완료 증거 | DRI / 검토 |
| --- | --- | --- | --- | --- | --- |
| CMP-AI-001 | 공식/INFO | 생성형 AI 기능과 AI 코딩 도구 사용은 허용되며 그 자체에 제한·감점이 없고 완제품 안정성이 평가 핵심 | Claude Code를 계약·테스트 중심 보조 도구로 사용하고 사람의 상대 검토를 유지 | `CLAUDE.md`, 기능 ID가 있는 PR, 실행한 test와 human review | 공동 / 공동 |

AI 도구 사용을 별도 가점이나 구현 완료 증거로 주장하지 않는다. 공개 prompt/transcript에는 secret, 사용자 원문, 실제 provider payload를 넣지 않는다.

## 4. 출처·브랜드·데이터 진실성

| ID | 성격/수준 | 요구사항 | Nullnull 구현 결정 | 완료 증거 | DRI / 검토 |
| --- | --- | --- | --- | --- | --- |
| CMP-ATT-001 | 공식/REQUIRED | 공공데이터 출처 텍스트 표시 | KTO 기본 `출처: ⓒ한국관광공사`; 승인된 경우 동등 문구 | KTO 화면 DOM/visual coverage 100% | FE / BE·AI |
| CMP-ATT-002 | 공식/RECOMMENDED | `TourAPI` 단독 표기 지양 | provider display name과 텍스트 출처를 함께 사용 | forbidden-copy scan | FE / BE·AI |
| CMP-ATT-003 | 공식/REQUIRED | 기관이 직접 운영하는 것처럼 오인시키는 CI·BI/명칭 사용 금지 | 별도 허가 없는 한국관광공사 CI·BI image 미사용 | asset ledger와 image scan | FE / BE·AI |
| CMP-ATT-004 | 팀 결정/INTERNAL | 기준시각과 source state를 숨기지 않음 | LIVE/FORECAST/REPLAY/QUALITATIVE/STALE/UNAVAILABLE 표시 | contract/Storybook/E2E | FE / BE·AI |
| CMP-ATT-005 | 팀 결정/INTERNAL | 비교 불가 source를 수치 비교하지 않음 | server comparison eligibility와 reason 사용 | property/contract/UI test | BE/AI / FE |
| CMP-ATT-006 | 팀 결정/INTERNAL | 이미지별 이용 조건을 확인 | 승인된 asset만 사용, 불명확하면 placeholder | asset license ledger | 공동 / 공동 |

## 5. 위치·개인정보·테스트 접근

| ID | 성격/수준 | 요구사항 | Nullnull 구현 결정 | 완료 증거 | DRI / 검토 |
| --- | --- | --- | --- | --- | --- |
| CMP-LOC-001 | 공식/주의 | 개인 위치를 서버로 전송하면 저장 여부와 무관하게 위치기반서비스 신고 대상일 수 있음 | 제출 profile의 위치 capability OFF | environment/startup readiness | BE/AI / FE |
| CMP-LOC-002 | 팀 결정/INTERNAL | 제출 build에서 위치 데이터 흐름 자체를 제거 | geolocation API/permission prompt/좌표 request 0 | Playwright permission spy, network/log scan | FE / BE·AI |
| CMP-LOC-003 | 팀 결정/INTERNAL | 위치 없이 핵심 기능 사용 가능 | 지역·장소 직접 선택과 다음 일정 기반 | external judge E2E | FE / BE·AI |
| CMP-PRV-001 | 팀 결정/INTERNAL | raw itinerary·cookie/token·key를 저장/로그하지 않음 | browser-first parser와 allowlisted telemetry | DB/log/artifact canary scan | BE/AI / FE |
| CMP-ACC-001 | 공식/REQUIRED | 심사자가 서비스 기능을 실제 확인할 수 있음 | 운영자 seed 조작 없이 새 anonymous session 사용 | 외부망 360px judge journey recording | FE / BE·AI |

위치 기능을 P1에서 열려면 이 제출본과 분리하고 [위치정보지원센터](https://www.lbsc.kr/) 사전 검토, 동의·정밀도·보존·외부 공유 계약을 먼저 닫는다.

## 6. 1차 심사 점수 대응

| 심사항목 | 배점 | 최소 증거 | 강화 증거 | 실패 신호 |
| --- | ---: | --- | --- | --- |
| 서비스 구현성 | 30 | 로그인 없는 URL, INT-01~04, loading/error/empty | Docker 통합, rollback, 모바일/keyboard | dead CTA, 운영자 사전 조작, 기능 중단 |
| 서비스 기획력 | 30 | 오버투어리즘 문제→후보→시간/장소 분산→사용자 승인 논리 | Figma/API/도메인 trace와 독립 잠금 | 기능 나열, AI가 임의 변경 |
| 데이터 활용 적절성 | 20 | 실제 KTO call과 서비스 화면, 출처·기준시각 | provenance/comparison eligibility·degradation | file/replay-only, 호출 이력·출처 없음 |
| 서비스 발전성 | 20 | P1/전국 확장 계획과 안전 gate | modular architecture, source registry, AWS 관측 | 현재 미구현을 이미 완료로 과장 |

## 7. 최종 발표심사 준비

최종 대상 발표는 2026-10-21, 발표심사는 2026-10-28이다. 발표 제한시간은 공식 공지에 확정값이 없으므로 대상자 안내 전 임의로 고정하지 않는다.

| 심사항목 | 배점 | 준비 방향 |
| --- | ---: | --- |
| 서비스 적정성 | 30 | 문제·지정과제·사용자·데이터·개입의 논리적 연결 |
| 서비스 완성도 | 30 | 실제 URL, KTO 활용, 안정성·오류·rollback의 검증 결과 |
| 서비스 실용성 | 25 | 익명 모바일 접근, 기존 일정 보존, 설명 가능한 선택 |
| 발표 점수 | 15 | 실제 화면 중심, 검증되지 않은 효과 수치 금지, 시간은 추후 안내 적용 |

## 8. 증거 ledger

공개 저장소에는 secret·개인정보·provider 원문을 넣지 않는다. 비공개 evidence ledger는 최소 다음 field를 가진다.

```text
evidenceId, requirementId, status, ownerRole, reviewerRole,
environment, releaseVersion, gitSha, contractSha,
capturedAt, artifactPathOrRunUrl, checksum,
containsSensitiveData, retentionUntil, notes
```

| Evidence ID | 내용 | 공개 가능 | 완료 시점 |
| --- | --- | --- | --- |
| EV-ELG-01 | 중복 출품·수상·지원사업 수혜 여부 확인 | 아니오 | 09-18 |
| EV-AI-01 | Claude Code 사용 범위·기능 ID·사람 검토·실행 test 기록 | secret 없는 PR 기록만 | 각 개발 PR |
| EV-URL-01 | 외부망·익명창 360px judge flow | URL/민감정보 제거본만 | 09-19, 09-20 |
| EV-KTO-01 | 활용 신청 API·신청자·운영계정 상태·승인/quota; 키는 입력 확인 boolean만 | 아니오 | KTO slice 전/제출 직전 |
| EV-KTO-02 | provider 호출 이력과 redacted call-audit | redacted 집계만 | 매 staging release |
| EV-ATT-01 | 화면 출처·기준시각·state DOM/screenshot | 예, key/request ID 제거 | UI slice/09-19 |
| EV-TEST-01 | docs/Docker/staging test run | 예 | 모든 main PR/release |
| EV-PDF-01 | 공식 양식 원본·최종 PDF checksum/field diff | 최종 제출 정책에 따름 | 09-18/09-20 |
| EV-SUB-01 | 제출 입력값·접수 완료 화면·시각 | 아니오 | 09-20/09-21 |
| EV-PRV-01 | secret/location/raw text canary 결과 | 집계만 | 09-19 |

## 9. Go/no-go

하나라도 충족하지 못하면 `NO-GO`다.

- `CMP-SUB-001~011`에서 적용 가능한 EXCLUSION/REQUIRED 행이 모두 `VERIFIED`
- 서비스 개요·부문/유형·지정과제 exact label과 KTO credential 입력 확인·운영계정 상태가 제출 상세와 일치
- 실제 KTO operation set과 기능설명서 API 목록의 diff가 0
- call-audit가 같은 release/provenance의 실제 서비스 화면에 연결
- 외부망·새 anonymous session에서 INT-01~04가 운영자 조작 없이 완결
- KTO 화면 텍스트 출처 100%, 무허가 CI·BI/secret 노출 0
- 위치 flag/geolocation/좌표 전송 0
- 공식 PDF 양식/필수 field/이미지 수가 최신 원본과 일치
- `docs-contract`, `docker-integration`, staging judge smoke 성공
- rollback target과 incident 연락 역할 확인
- 두 담당자의 독립 확인 시각과 제출 접수 증거 존재

미구현 기능은 제거하거나 명확한 `준비 중`/capability OFF 상태로 두고 기능설명서에서 제외한다. 제출 마감 때문에 필수 KTO 활용, 사용자 데이터 안전, 일정 무결성, 출처 표시를 예외 처리하지 않는다.

## 10. 공식 자료

- [공식 공지 Notion](https://lowly-polyanthus-1fb.notion.site/2026-3a75dce406e38034a9a8d058a1b55596)
- [한국관광 콘텐츠랩 제출처](https://api.visitkorea.or.kr/)
- [공식 기능설명서 양식](https://drive.google.com/file/d/10fxZ7pK_l1n3TMkWnYQQss8jkM85-hJV/view?usp=drive_link)
- [자료 제출 절차 매뉴얼](https://drive.google.com/file/d/1MUSw3W27-VTI8HHg55urVR38j4fi8TVm/view?usp=drive_link)
- [인증키·운영계정 안내](https://drive.google.com/file/d/1iAy4zLbWT4gWbc7PkW2mujdQBFD-2-BC/view?usp=sharing)
