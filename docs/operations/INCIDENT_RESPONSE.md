# 장애·보안 사고 대응

- 상태: Production readiness baseline
- 대상: Frontend 담당 1명, Backend/AI 담당 1명
- 원칙: 사용자 영향 차단과 데이터 보존을 먼저 하고, 원인 규명은 그다음 수행한다.

이 문서의 시간은 팀 내부 대응 목표이며 법적 통지 기한을 대체하지 않는다. 개인정보·계약·공공데이터 이용조건에 따른 더 짧은 기한이 있으면 그것을 따른다. 실제 전화/메일/채널은 공개 repository가 아닌 보호된 contact registry에 둔다.

## 1. Severity

| Severity | 기준/예 | Ack 목표 | 영향 차단 목표 | Update 주기 |
| --- | --- | ---: | ---: | ---: |
| SEV0 | 다른 owner 데이터 노출, 승인 없는 일정 변경, active secret 탈취, 광범위 삭제/손상 | 15분 | 30분 이내 kill switch/rollback/degrade | 30분 |
| SEV1 | P0 생성·저장·편집·apply 전체 장애, DB 가용성/데이터 손실 위험, production deploy 실패 지속 | 15분 | 1시간 | 60분 |
| SEV2 | Live/source/일부 화면 장애, 높은 오류·지연, 우회 가능 | 4업무시간 | 1영업일 | 상태 변경 시/일 1회 |
| SEV3 | 경미한 UI/문구/내부 운영 문제, 사용자 안전 영향 없음 | 1영업일 | backlog SLA | ticket 기준 |

의심 단계에서도 높은 severity로 시작하고 사실이 확인되면 낮춘다. 혼잡 데이터가 틀렸거나 source freshness를 알 수 없으면 기능을 `STALE`/`UNAVAILABLE`/`REPLAY`로 내리고 “정상처럼” 유지하지 않는다.

## 2. 2인 역할

첫 응답자가 incident lead가 되고 상대가 communications/scribe와 independent verifier가 된다. 전문 영역 DRI가 lead를 인계할 수 있지만 인계 시각과 현재 상태를 기록한다.

| 사고 | Primary technical responder | Independent verifier |
| --- | --- | --- |
| web/PWA/route/client release | FE_DRI | BE_AI_DRI |
| API/DB/session/deletion | BE_AI_DRI | FE_DRI |
| source/AI/optimizer | BE_AI_DRI | FE_DRI |
| AWS/IAM/OIDC/deploy | BE_AI_DRI | FE_DRI |
| privacy/security | 먼저 확인한 사람이 영향 차단 | 상대가 증거·범위·복구 확인 |

한 명만 응답 가능한 SEV0/SEV1에서는 새 변경을 최소화하고 이미 검증된 rollback/feature kill switch를 우선한다. production 전 실제 external escalation contact와 부재 대응자를 contact registry에 지정한다. contact가 `TBD`이면 launch gate 실패다.

## 3. Alarm routing

| Signal | Primary route | Secondary route | 첫 확인 |
| --- | --- | --- | --- |
| web error/core journey | FE_DRI | BE_AI_DRI | release/version, API 상태, service worker |
| API 5xx/latency/task/DB | BE_AI_DRI | FE_DRI | deploy, health, connection, migration |
| source freshness/quota/drift | BE_AI_DRI | FE_DRI | source state, collector, last-known-good |
| unauthorized/CSRF/WAF/secret | 두 사람 동시 critical | 지정 external contact | 세션/키/노출 범위 |
| billing/drift | BE_AI_DRI | FE_DRI | 신규 resource, tag, retention |

production 전 synthetic alarm을 발생시켜 두 사람이 mobile에서 수신·acknowledge·escalate할 수 있는지 확인한다. 개인 연락처를 Git에 넣지 않고 alarm destination ARN/주소도 문서 예시에 넣지 않는다.

## 4. 최초 15분 checklist

1. incident ID, 최초 시각, 감지 source, 현재 release version을 만든다.
2. 안전 불변식 위반, owner 간 노출, 삭제/손실, secret 유출 가능성을 확인한다.
3. severity와 lead/scribe를 선언하고 production 변경을 동결한다.
4. rollback, feature flag, source circuit, maintenance/degraded UI 중 가장 작은 영향 차단을 선택한다.
5. request ID/metric/deploy manifest로 범위를 좁힌다. 민감 request body를 복사하지 않는다.
6. 사용자에게 보이는 상태가 사실과 일치하는지 확인한다.
7. 다음 update 시각과 escalation 필요 여부를 기록한다.

## 5. 공통 대응 흐름

```text
detect → declare → contain → preserve evidence → diagnose
→ recover → independently verify → monitor → close → learn
```

### Contain

- web regression: 직전 S3/web manifest로 전환하고 service worker/cache 동작을 확인한다.
- API regression: 직전 ECS image digest/task definition으로 rollback한다.
- migration 문제: app deploy를 중단한다. destructive down을 자동 실행하지 않는다.
- source 문제: circuit/feature를 내려 stale/replay/unavailable로 표시한다.
- optimizer 문제: create/apply endpoint kill switch를 사용하되 trip CRUD를 유지한다.
- session/권한 문제: 영향 endpoint 차단, session revoke/rotation, WAF/rate 제한을 적용한다.

### Recover와 verify

- 최초 실패와 같은 request/journey뿐 아니라 인접 안전 invariant를 검증한다.
- 작성자가 아닌 상대 담당자가 회복 상태와 rollback target을 확인한다.
- session, candidate, trip version, revision, deletion job 같은 비동기/상태 data를 샘플링하되 사용자 내용을 노출하지 않는다.
- 30분 이상 error/latency/safety metric을 관찰한 뒤 종료를 선언한다.

## 6. 보안·개인정보 SLA

| 단계 | SEV0/SEV1 내부 목표 | 완료 증거 |
| --- | ---: | --- |
| triage/incident 선언 | 감지 후 15분 | ID, severity, lead, release |
| credential/session containment | 확인 즉시, 목표 30분 | revoke/rotate/deny evidence |
| 노출 endpoint/기능 차단 | 목표 30분 | WAF/flag/rollback 상태 |
| 영향 범위 1차 평가 | 4시간 | data type, owner, 기간, source |
| 개인정보/법적 통지 필요성 판단 시작 | 4시간 내 owner/escalation | 적용 법·정책 검토 기록 |
| 경영/대회/외부 provider escalation | 24시간 내 또는 계약상 더 빠른 기한 | 수신/결정 기록 |
| 재발 방지 owner/due 지정 | 복구 후 2영업일 | action items |
| post-incident review | 복구 후 5영업일 | 승인된 review 문서 |

secret 노출은 commit 삭제만으로 닫지 않고 revoke/rotate, 사용 log 확인, 모든 runtime 재배포, history/artifact/cache 처리와 scanner 개선을 수행한다.

다른 owner 접근 또는 개인정보 노출 가능성이 있으면:

- 관련 log/artifact의 보존과 접근을 제한한다.
- 원본 데이터를 일반 chat/issue에 복사하지 않는다.
- 영향을 받은 데이터 종류·기간·사용자 범위를 최소 쿼리로 산출한다.
- backup/replay/cache/analytics 사본까지 범위를 확인한다.
- 통지 내용과 시점은 적용 법·정책 책임자의 판단을 받아 기록한다.

## 7. 데이터·AI 특화 사고

### Source 오류/schema drift

1. 해당 source를 quarantine하고 last-known-good의 freshness를 재평가한다.
2. 오래된 값을 live로 표시하지 않고 `STALE`/`UNAVAILABLE`로 전환한다.
3. 비교 적격성을 false로 바꿔 delta/rank/최적화 evidence 사용을 막는다.
4. provider 공지와 수집 run/snapshot set을 연결해 영향 window를 기록한다.
5. 수정 fixture/normalizer를 staging replay로 검증한 뒤 다시 연다.

공모전 제출 기간에는 replay로 서비스 가용성을 유지할 수 있지만 이를 실제 KTO 활용 증거로 대체하지 않는다. 실제 KTO call/readiness가 회복되지 않거나 call-audit가 release에 연결되지 않으면 제출 go/no-go를 실패로 유지한다. key 노출이 의심되면 먼저 rotate하고 새 key의 실제 호출을 재검증한다.

### 최적화/LLM 오류

- 승인 없이 변경됐거나 lock이 깨지면 SEV0로 취급하고 apply를 차단한다.
- proposal, input trip version, data fingerprint, decision/revision을 불변 evidence로 보존한다.
- LLM 출력 자체를 사실 evidence로 사용하지 않는다. 결정적 validator가 왜 통과했는지 조사한다.
- affected revision은 검증된 revert로 복구하고 감사 record를 삭제하지 않는다.

## 8. 삭제 job·backup 특화 사고

- session revoke와 비동기 삭제 완료를 별도 확인한다.
- 실패 job을 무한 재시도하지 않고 상태/attempt/next retry를 기록한다.
- backup restore가 수행되면 tombstone/deletion ledger를 재적용해 삭제 data가 다시 서비스되지 않게 한다.
- 삭제 receipt를 완료로 잘못 표시한 경우 사용자 영향과 정책 위반 가능성을 평가한다.

## 9. Communication 원칙

- 확인된 사실, 사용자 영향, 현재 완화, 다음 update 시각만 전달한다.
- 원인 추측이나 “데이터가 안전하다”는 미검증 단정을 피한다.
- 외부 상태 문구는 FE_DRI가 명료성을 검토하고 BE_AI_DRI가 기술 사실을 검증한다.
- incident issue에는 secret, cookie, raw itinerary, 정밀 위치, 사용자 원문을 넣지 않는다.
- 공모전 demo 중 replay/degraded 전환은 화면에 명시하고 live처럼 설명하지 않는다.

### 제출 직전 장애 운영

- 2026-09-19 code freeze 이후에는 사용자 데이터 무결성·접속·실제 KTO 활용·출처·secret 문제만 긴급 변경 후보로 본다.
- Frontend 장애는 FE가 `frontend`, Backend/KTO/AWS 장애는 BE/AI가 `backend`에서 최소 수정하고 상대가 재현한 뒤 `main`에 병합한다. required check를 생략하지 않는다.
- 2026-09-20 16:00 내부 제출 뒤 기능 확장을 하지 않는다. rollback target, 제출 URL, PDF 기능 목록이 달라지면 모두 다시 대조한다.
- 공식 마감 1시간 전인 2026-09-21 15:00부터는 현재 정상 release를 고정하고 접수 확인·증거 보존만 수행한다.

## 10. Incident record template

```text
Incident ID / severity / status:
Detected at / source:
Lead / scribe / verifier:
Affected release and environment:
User impact and safety/privacy assessment:
Containment actions and timestamps:
Evidence references (restricted where needed):
Recovery and independent verification:
External/legal/provider decisions:
Timeline:
Root cause and contributing factors:
What detected / what failed to detect:
Actions with owner/due/test:
ADR/runbook/contract changes:
```

## 11. 종료와 학습

사고는 service가 잠시 정상인 것만으로 닫지 않는다.

- safety/privacy invariant와 핵심 synthetic journey가 통과했다.
- 상대 담당자가 회복을 확인했다.
- rollback/temporary console change/flag의 후속 상태가 정해졌다.
- root cause 또는 조사 계획과 action owner/due가 있다.
- contract, test, alert, runbook, ADR review trigger가 필요한지 검토했다.
- 사용자/외부 통지가 필요하면 담당과 기한이 기록됐다.

production 전 최소 한 번, 이후 분기마다 session 탈취 또는 optimizer 무단 변경 시나리오로 30분 tabletop을 수행한다. 결과는 contact registry, alarm, rollback과 이 문서를 갱신하는 입력이다.
