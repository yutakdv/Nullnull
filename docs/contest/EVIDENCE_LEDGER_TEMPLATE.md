# 공모전 Evidence Ledger Template

- 상태: secret 없는 원장 스키마
- 용도: 공모전 requirement와 실제 release 증거의 1:1 상태 추적
- 주의: 이 파일은 예시다. 실제 원장은 두 담당자만 접근할 수 있는 보호 저장소에 복사해 운영한다.

## 1. 기록 금지

실제 원장에도 다음 원문은 넣지 않는다.

- OpenAPI service key와 encoded/decoded credential
- 비밀번호, cookie, token, AWS account/role secret
- 사용자 입력 원문, 개인 위치, provider request/response 원문
- 공개하면 안 되는 접수 번호나 개인 연락처

인증키는 제출 화면에 입력했는지를 boolean으로만 기록한다. 화면 캡처가 필요하면 key field를 완전히 가리고 원본의 접근·보존 정책을 별도로 둔다.

## 2. 원장 메타데이터

```text
ledgerVersion:
protectedStorageLocationId:
releaseVersion:
gitSha:
contractSha:
submissionLeadRole:
independentCheckerRole:
lastReviewedAt:
```

`protectedStorageLocationId`는 팀이 합의한 저장 위치의 별칭만 기록하며 실제 URL·credential을 공개 저장소에 넣지 않는다.

## 3. Requirement 상태표

아래 CSV를 보호 저장소로 복사한다. requirement ID를 삭제하지 않고 적용되지 않는 경우에도 `NOT_APPLICABLE`과 근거를 남긴다. `VERIFIED`는 artifact와 독립 검토 시각이 모두 있을 때만 허용한다.

```csv
requirementId,status,evidenceId,owner,reviewer,notes
CMP-SUB-001,NOT_STARTED,EV-SUB-01,공동,공동,
CMP-SUB-002,NOT_STARTED,EV-SUB-01,공동,공동,
CMP-SUB-003,NOT_STARTED,EV-SUB-01,공동,공동,"serviceOverview·exact 부문/유형/과제 포함"
CMP-SUB-004,NOT_STARTED,EV-URL-01,FE,BE/AI,
CMP-SUB-005,NOT_STARTED,EV-URL-01,FE,BE/AI,
CMP-SUB-006,NOT_STARTED,EV-PDF-01,공동,공동,
CMP-SUB-007,NOT_STARTED,EV-PDF-01,FE,BE/AI,
CMP-SUB-008,NOT_STARTED,EV-PDF-01,공동,공동,
CMP-SUB-009,NOT_STARTED,EV-SUB-01,공동,공동,"exact 지정과제 label 포함"
CMP-SUB-010,NOT_STARTED,EV-ELG-01,공동,공동,
CMP-SUB-011,NOT_STARTED,EV-KTO-01,BE/AI,FE,"credential 입력 boolean·운영계정 exact 상태"
CMP-AI-001,NOT_STARTED,EV-AI-01,공동,공동,"허용 정보; 가점·완료 증거로 과장 금지"
CMP-KTO-001,NOT_STARTED,EV-KTO-02,BE/AI,FE,
CMP-KTO-002,NOT_STARTED,EV-KTO-02,BE/AI,FE,
CMP-KTO-003,NOT_STARTED,EV-KTO-02,BE/AI,FE,
CMP-KTO-004,NOT_STARTED,EV-KTO-02,BE/AI,FE,
CMP-KTO-005,NOT_STARTED,EV-KTO-02,BE/AI,FE,
CMP-KTO-006,NOT_STARTED,EV-KTO-02,BE/AI,FE,
CMP-KTO-007,NOT_STARTED,EV-PRV-01,BE/AI,FE,
CMP-KTO-008,NOT_STARTED,EV-PRV-01,BE/AI,FE,
CMP-ATT-001,NOT_STARTED,EV-ATT-01,FE,BE/AI,
CMP-ATT-002,NOT_STARTED,EV-ATT-01,FE,BE/AI,
CMP-ATT-003,NOT_STARTED,EV-ATT-01,FE,BE/AI,
CMP-ATT-004,NOT_STARTED,EV-ATT-01,FE,BE/AI,
CMP-ATT-005,NOT_STARTED,EV-ATT-01,BE/AI,FE,
CMP-ATT-006,NOT_STARTED,EV-ATT-01,공동,공동,
CMP-LOC-001,NOT_STARTED,EV-PRV-01,BE/AI,FE,
CMP-LOC-002,NOT_STARTED,EV-PRV-01,FE,BE/AI,
CMP-LOC-003,NOT_STARTED,EV-URL-01,FE,BE/AI,
CMP-PRV-001,NOT_STARTED,EV-PRV-01,BE/AI,FE,
CMP-ACC-001,NOT_STARTED,EV-URL-01,FE,BE/AI,
```

## 4. 제출 값 대조표

값 원문에 개인정보나 secret이 없을 때만 적는다. 인증키는 절대 적지 않는다.

```text
teamName:
serviceName:
serviceOverview:
divisionExactLabel: ②-2 웹·앱 구현 부문  # 제출 화면과 재대조
serviceTypeExactLabel:
designatedTaskExactLabel:
publicUrl:
loginMode:
ktoApplicant:
ktoOperationAccountState:
ktoCredentialEntryVerified: false
ktoOperationsActuallyUsed:
functionPdfChecksum:
submissionReceiptVerified: false
```

## 5. Evidence artifact 기록

각 `Evidence ID`는 아래 field를 가진다.

```text
evidenceId, requirementIds, environment, releaseVersion, gitSha,
contractSha, capturedAt, artifactLocator, checksum,
containsSensitiveData, retentionUntil, ownerRole, reviewerRole, notes
```

공개 가능한 결과와 보호해야 하는 원본을 분리한다. 동일 증거가 여러 requirement를 지원할 수 있지만 각 requirement 행에는 사용한 evidence ID를 빠짐없이 연결한다.

## 6. 상태 전이와 go/no-go

```text
NOT_STARTED → IN_PROGRESS → VERIFIED
NOT_STARTED → NOT_APPLICABLE
IN_PROGRESS → NOT_APPLICABLE
VERIFIED → IN_PROGRESS  # release, 계약, 공식 양식 또는 제출값 변경 시 재검증
```

- `VERIFIED`: 같은 release의 artifact, checksum, 검토자와 검토 시각이 모두 존재한다.
- `NOT_APPLICABLE`: 공식 요구가 적용되지 않는 이유와 두 사람의 확인 시각이 존재한다.
- EXCLUSION/REQUIRED 행이 `VERIFIED`가 아니면 제출은 `NO-GO`다.
- `ktoCredentialEntryVerified=true`여도 인증키 원문을 원장에 적지 않는다.
- 제출 후 변경으로 release/PDF/API 목록이 달라지면 영향받은 행을 즉시 `IN_PROGRESS`로 되돌린다.
