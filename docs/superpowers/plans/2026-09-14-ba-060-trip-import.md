---
aliases:
  - "BA-060 붙여넣기 parse·remap·confirm 구현 계획"
doc_type: plan
status: draft
area: engineering
tags:
  - nullnull/plan
  - nullnull/engineering
---

# BA-060 붙여넣기 parse·remap·confirm 구현 계획

**역할**: Backend/AI · **기능 ID**: `FR-TRC-06`, `FR-TRC-07`, `NFR-PRV-01` · **Figma**: `401:1221`
**operationId**: `parseTripImport`, `remapTripImport`, `confirmTripImport` · **migration**: `V028`

## 1. 착수 시점에 측정한 상태

| 항목 | 값 | 근거 |
| --- | --- | --- |
| `importer` module | 파일 0개 (greenfield) | `find apps/api/src -path '*importer*'` |
| 마지막 migration | 착수 시 `V027__place_relations.sql`, 이 slice가 `V028` | migration 디렉터리 |
| `ProblemCode` | `IMPORT_DRAFT_EXPIRED`(410)·`IMPORT_DRAFT_CHANGED`(409) 이미 존재 | `ProblemCode.java:28-29` |
| 계약 | 세 operation·`ImportDraft`·`UnresolvedImportToken`·요청 3종 모두 게시됨 | `openapi.yaml:2019-2160`, `4914-5100` |
| ERD | 표·상태기계·보존기간 확정 | `ERD.md:260`, `:767`, `:852` |
| import analytics event | **없음** | `events.schema.json`에 `import` 0건 |

## 2. 선행 경계 — step 4의 판정

- **`PM-008`(local time ↔ `format: time`)은 해결됐다.** `docs/api/openapi.yaml`의 `format: time`이 **0건**이고
  `ImportDraftItem.startTime`을 포함해 전부 `^([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$` pattern이다. 이 경계는 확정해도 된다.
- **`PM-005`(= `FCR-019`)는 여전히 `Open`이다.** `FIGMA_CHANGE_REQUESTS.md:52`에서 P0 blocker, 담당 `FE / BE·AI·PM`.
  그래서 아래 넷은 **이 slice에서 확정하지 않는다**:
  1. 연도 없는 날짜의 기준 연도
  2. 오전/오후가 모호한 시각
  3. `RemapImportRequest`에 **item 제거 표현이 없다**
  4. `ParseImportRequest`에 사용자가 고른 start/end가 없다

  1·2는 **확정하지 않는 방법이 계약에 이미 있다** — `UnresolvedImportToken(kind: DATE|TIME)`으로 내보내고 remap에서 사람이 정한다.
  추측하지 않는 것이 원칙 3(LLM/parser는 사실 판정자가 아니다)과 `PM-005`의 *"임의 확정하면 안 된다"* 를 동시에 만족한다.
  3·4는 **계약 추가가 필요하므로 제안만 하고 이 slice에서 구현하지 않는다**(2인 계약: BE가 제안, FE가 승인).

  3의 결과로 **오인식 item이 남은 draft는 READY에 도달하지 못하고 confirm이 막힌다.** 이는 막다른 길이지만
  카드의 *"수동 입력 fallback은 계속 유지한다"* 와 24시간 TTL이 받는다. 이 상태를 **알고 내는 것**이지 발견하지 못한 것이 아니다.

## 3. 설계 결정

### 3.1 원문은 저장할 자리를 만들지 않는다

ERD가 이미 그렇게 적는다 — *"raw text column 자체를 만들지 않음"*(`ERD.md:852`). 다만 **부재가 그 자체로 보증은 아니다**: `structured_draft`·`unresolved_tokens`가 `jsonb`라 응용이 넣기로 하면 무엇이든 받는다. 열이 없어서 얻는 것은 **원문 저장이 기본값이 아니라 의도적 행위가 된다**는 것이고, schema가 검사할 수 없는 나머지를 `T1`의 canary가 본다.
`V028`의 `itinerary_import_drafts`는 ERD `:260`의 열만 만든다:

```text
id · owner_id · status · version · structured_draft(jsonb)
unresolved_tokens(jsonb) · confirmed_trip_id · confirmed_at · expires_at · created_at
```

`rawText`는 `ParseImportRequest`에서 받아 parser에 넘기고 **요청 메모리 밖으로 나가지 않는다.** 그것을 강제하는 것:

- **DB**: 전용 열이 없다. `jsonb` 두 열은 `T1`이 저장된 행을 **열 전수**로 읽어 canary를 찾는다.
- **응답**: `ImportDraft`가 `additionalProperties: false`이고 원문 필드가 없다.
- **log/exception**: 원문을 담은 값이 `toString`/exception message에 닿지 않도록 parser 입력을 전용 wrapper로 감싼다.
- **외부**: `apps/ai`·LLM·provider로 나가는 경로가 **구조적으로 없음**을 ArchUnit으로 고정한다
  (`BA-033-T3`의 `analyticsIsNeverOnAProductCommandsPath` 선례 — *"닿을 경로 자체가 없음"*).

`originalLabel`·`UnresolvedImportToken.label`은 **의도된 예외**다(계약이 *"Short parsed token only"* 라고 적는다).
그래서 canary test는 **자유 메모·연락처 모양의 문자열**을 쓴다 — 장소 토큰 모양 canary는 `originalLabel`에 정당하게
나타나므로 그것으로 재면 test가 설계를 오판한다.

### 3.2 confirm의 원자성

`TripService.create(context, idempotencyKey, CreateTripCommand)`가 이미 `seedItems`를 받고
`TripScheduleRules`로 범위·상한·중복 position을 **쓰기 전에** 검증한다(`CreateTripCommand.java:21-30`).
그래서 confirm은 새 trip 생성 로직을 복제하지 않고 그 서비스를 부른다.

**위험**: `create`는 내부에 `IdempotencyGuard.execute`를 갖는다. draft를 `CONFIRMED`로 표시하는 쓰기와
trip 생성이 **한 unit of work**여야 하는데(불변식 5), guard의 transaction 경계와 겹친다.
`OptimizeItemHandler`에서 같은 모양으로 한 번 데었으므로 **이것을 가장 먼저 붙여 실패시켜 본다.**

### 3.3 TTL

24시간. 만료된 draft의 remap/confirm은 `IMPORT_DRAFT_EXPIRED`(410). 물리 삭제는 기존 sweep 방식
(`ExpiredIdempotencyRecordEraser` 선례)을 따르고 job payload에는 **id만** 넣는다.

## 4. acceptance 쪼개기 제안 (현 `T1`~`T3` → 11개)

한 ID에 한 절, 그리고 **절을 증명하는 기제가 서로 다를 때만** 나눈다.

| 새 ID | 절 | 왜 따로인가 |
| --- | --- | --- |
| `T1` | 원문 canary가 **저장된 draft의 어느 column에도** 없다 | column 부재가 기제 |
| `T2` | 원문 canary가 **세 operation의 응답 본문**에 없다 | closed schema가 기제 |
| `T3` | 원문 canary가 **log·exception message**에 없다 | wrapper/`toString`이 기제 |
| `T4` | 원문이 **module 밖으로 나가는 경로가 없다** | ArchUnit 구조 고정 |
| `T5` | **stale** remap/confirm(If-Match 불일치)은 draft도 trip도 바꾸지 않는다 | version/ETag |
| `T6` | **만료** draft의 remap/confirm은 410이고 trip을 만들지 않는다 | TTL |
| `T7` | **미해결 매핑**이 남은 draft의 confirm은 거절되고 trip을 만들지 않는다 | READY 판정 |
| `T8` | **같은 Idempotency-Key 재시도**는 trip을 하나만 만든다 | 응용 guard(멱등 replay) |
| `T8b` | **다른 key의 동시 confirm**은 trip을 하나만 만든다 | DB `confirmed_trip_id` UNIQUE |
| `T9` | 연도 없는 날짜·모호한 시각을 **확정하지 않고** unresolved token으로 낸다 | parser 정책(= `FCR-019` 비확정) |
| `T10` | 한국어/영어 장소 토큰이 canonical place로 해결된다 | catalog 조회 |
| `T11` | 20000자·100 item·10 suggestion 상한 경계 | 상한 |

기존 `T1`은 6개 sink를 한 절에 묶고 있었다. `T2`는 **기제가 넷**(ETag·TTL·READY·idempotency)이라
하나를 증명하는 test가 나머지를 증명하지 않는다 — `BA-034-T1`이 걸린 것과 같은 모양이다.
**analytics event sink는 절로 만들지 않는다**: `events.schema.json`에 import event가 **없어서** 생산자가 없고,
그런 절은 영원히 초록인 단언이 된다(규칙 7②). `T4`의 구조 고정이 그 자리를 대신한다.

## 5. 실행 순서

1. `V028` + `FlywayMigrationIT` 두 곳(`populateEveryTable`에 **`V027`의 `place_relations` 대표 행**, 행 수 단언)
2. domain: `ImportDraft` aggregate·상태기계(`NEEDS_REVIEW ↔ READY → CONFIRMED`, 비terminal → `EXPIRED`)
3. parser: 요청 메모리 전용, allowlist 추출, 확정 불가 → unresolved token
4. `parseTripImport` → `remapTripImport` → `confirmTripImport` 순으로 controller/service
5. 3.2의 원자성을 **가장 먼저** 실패시켜 확인
6. test 11개, 각각 **되돌리면 빨개지는지** 확인(규칙 7②)

## 6. 검증

`AGENTS.md#필수-검증`의 목록을 **다시 읽고** 실행한다(규칙 7④). 이 변경 경로는 계약·`apps/api`·문서 셋 다 건드린다.

- `cd apps/api && ./gradlew test integrationTest openapiContractTest recommendationTest` (Temurin 21)
- `python3 scripts/validate_docs.py` · `python3 -m unittest discover -s scripts/tests`
- `npx --yes markdownlint-cli2@0.23.2` · `npx --yes @redocly/cli@2.51.1 lint docs/api/openapi.yaml`
- 계약을 고치면 `npm --workspace @nullnull/api-client run check`
- 검증은 **격리 worktree**에서 한다(세 세션이 한 checkout을 공유한다, 규칙 6)

## 7. 남는 blocker

- `FCR-019` 미해결 → item 제거·parse 시 날짜 선택은 이 slice 밖. 제안을 이슈로 올린다.
- `V027` 갚기 완료 — `2b`가 제약을 실제로 돌려 확인한 행을 `populateEveryTable`에 넣었다. `V028` 자신의 대표 행은 `V029`의 주인(`2b`)이 갚는다.
