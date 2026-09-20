---
aliases:
  - "FE 인계 2026-09-18 Figma 공백"
doc_type: reference
status: draft
area: engineering
tags:
  - nullnull/reference
  - nullnull/engineering
---

# FE 인계 2026-09-18 — Figma node 감사와 남은 화면 둘

이 문서는 다음 세션이 **읽고 바로 이어서 작업할 수 있게** 쓴 것이다. 배경 설명이 아니라
작업 지시서로 읽어라. 마감은 `2026-09-21 16:00`이고 이 글을 쓴 시점에 3일 남았다.

## 한 줄 요약

`a425e71`이 "확인 필요"로 남긴 Figma node 넷을 전부 열어 확인했고, 그중 **둘을 고쳤고 하나는
이슈로 올렸고 하나가 남았다.** 남은 하나(`438:3199` 직접 입력)와 그 다음 화면(`438:3259` 확인)이
이 문서의 작업 대상이다.

## 시작 전 확인 — 이 상태가 맞는지 먼저 본다

```bash
git -C /Users/youngjun/Documents/Nullnull log --oneline -1
# f7cf639 feat(frontend): S02-4C 입력 방법 선택을 wizard 의 한 단계로 만든다
git -C /Users/youngjun/Documents/Nullnull status --porcelain --untracked-files=all   # 비어 있어야 한다
git -C /Users/youngjun/Documents/Nullnull rev-list --left-right --count origin/frontend...HEAD  # 0 0
```

다르면 그 사이 누가 밀었다는 뜻이다. **`git log --oneline f7cf639..HEAD`로 무엇이 들어왔는지
먼저 읽어라.** 이 저장소는 세션이 여럿이고 branch를 공유한다.

## 절대 하지 말 것 (사용자 지시)

1. **`frontend-plan.json`·`backend-plan.json`을 읽지도 고치지도 않는다.** 두 파일이 코드보다
   낡아서 판단을 틀리게 만든 전력이 있다. 무엇이 되어 있는지는 **코드·계약·git tree**로 본다.
   (실측: plan이 BA-052/053/054를 `planned`로 적는데 셋 다 머지돼 있었다.)
2. **commit·push·이슈·PR은 사용자 승인 먼저.** "돌려봐"는 push 허가가 아니다.
3. **표지 사진(`docs/contest/covers/`)은 건드리지 않는다.** 오너가 직접 찍은 사진이고 그대로
   쓰기로 정했다. C2PA에 AI 표식이 있지만 보정 과정에서 붙은 것이고, 이 논의는 끝났다.

## 이번 세션이 한 것

| 커밋 | 내용 |
| --- | --- |
| `fabc1d1` | `wizard.ts`·`MustVisitScreen.tsx` 주석이 기각된 #180 경로를 가리키던 것 정정 |
| `7dcaca6` | S03-C2·C3·C4를 Figma대로 Toast로 (#FE-203) |
| `f8e4ef9` | 후보 일정화 날짜 선택을 bottom sheet로 (#FE-305) |
| `f7cf639` | S02-4C 입력 방법 선택을 wizard 단계로 (#FE-103, FR-TRC-05) |

이슈: [#247](https://github.com/yutakdv/Nullnull/issues/247) — `REC-CON-04`가 P0 화면
`384:5673`을 막고 있다, 3일 안에 가능한지 질문. **BE 답을 기다리는 중이고 FE가 할 일은 없다.**
[#81](https://github.com/yutakdv/Nullnull/issues/81)에 FE-203 경위와 결과를 코멘트로 남겼다.

## 네 node의 최종 판정 (전부 Figma 원본을 열어 확인함)

| node | Figma 실제 | 판정 |
| --- | --- | --- |
| `527:4695` | `어느 날에 추가할까요?` bottom sheet | **고침** (`f8e4ef9`) |
| `400:1201` | `어떤 방법으로 옮길까요?` 두 선택지 | **고침** (`f7cf639`) |
| `438:3199` | `어떤 일정이 있으세요?` 날짜별 직접 입력 | **남음 — 이 문서의 작업 1** |
| `438:3259` | `이렇게 입력하셨어요` 확인 + 필수 장소 Pick | **남음 — 이 문서의 작업 2** |
| `384:5673` | `이렇게 채워봤어요` 추천 초안 | **막힘** — #247, BE 계약 대기 |

넷 다 **REF 프레임이 아니라 실제 구현 대상 화면**이다. `FUNCTIONAL_INVENTORY.md:49-54`가
`FR-TRC-05`·`08`·`09`·`10`을 전부 **P0**로 적는다.

## 배운 것 — 다음 사람이 같은 실수를 하지 않도록

**① `FIGMA_HANDOFF.md`의 형태 서술을 믿지 마라.** 두 번 틀렸다.

- `111-113`이 S03-C2/C3/C4를 `sheet/result`·`sheet/error`로 적었는데 실제로는 **Toast**다.
- `183`이 `527:4695`를 `후보 일정화 날짜 선택`으로 적었고 이건 **맞았는데**, frame 이름이
  `S07-10 / move-date`라 내가 "기존 item 이동"으로 잘못 읽었다.

**정본은 Figma 파일이다. 열어라.** fileKey는 `C3tTNClo9JH8tb4qpQgP61`.

**② frame 이름도 정본이 아니다.** `527:4695`의 이름은 `move-date`인데 내용은 후보 일정화다.
이름으로 판단하지 말고 **렌더된 화면과 문구를 봐라**(`get_screenshot` 또는 `get_design_context`).

**③ node id가 코드에 없다 ≠ 미구현.** `a425e71`이 FE-504를 이 이유로 강등할 뻔했다가
REF 프레임인 걸 확인하고 무죄 판정했다. **한국어 본문으로 grep해라**, node id 말고.

**④ 핸드오프 표는 형태만이 아니라 내용도 빠뜨린다.** `154`가 `438:3259`를
`구조화 결과 최종 확인`이라고만 적어서 **읽기 전용 요약처럼 읽히는데**, 실제로는 거기서
**필수 장소를 고르는 입력 화면**이다. 그 한 단어가 빠져서 아무도 Pick 토글을 작업으로 세지
않았다. 형태만 대조해서는 안 잡힌다.

## 작업 1 — `438:3199` S02-4C-C 직접 입력

### 어디에 붙는가

`f7cf639`가 `InputMethodStep`을 만들면서 **`onManual`이 `setStep(5)`를 부르게 해 뒀다.**
지금 step 5를 렌더하는 코드가 없으므로 **직접 입력을 고르면 빈 화면이 된다.**

```text
apps/web/src/app/trip-create/TripWizardScreen.tsx
  step === 4 && nextAfterPlanning(draft) === 'method'  →  <InputMethodStep onManual={() => setStep(5)} …/>
  step === 5                                           →  없음  ← 여기를 만든다
```

**이것이 이 작업의 우선순위를 정한다.** 다만 **얼마나 급한지는 재 보고 적는다** — 처음에 나는
이걸 "도달 가능한 dead end"라고 썼는데, 그건 측정하지 않은 추정이었다. 실제로 그 경로를 끝까지
눌러 보면 이렇다:

```text
STEP 5 화면에 남은 것: "STEP 5"
버튼 목록:             "Previous step"
```

**빈 화면이지만 갇히지 않는다.** `goBack`이 `step > 1`이면 `setStep(step - 1)`을 하므로 step 4로
정상 복귀한다. `#185`가 고발한 *"입력이 조용히 사라진다"* 와는 다른 부류다 — 여기서는 잃는 것이
없고 돌아갈 수 있다.

그래서 `onManual`을 임시로 비활성화하지 않았다. 비활성 버튼과 빈 화면은 **둘 다 미완성이라는
사실이 같고**, 비활성화 커밋은 다음 세션이 되돌리는 것부터 시작하게 만든다. 게다가 이 상태는
지금 아무에게도 보이지 않는다 — `docker-integration`이 [#240](https://github.com/yutakdv/Nullnull/issues/240)으로
막혀 `main`에 못 가고 배포 URL도 없다(`BA-006`이 `deferred`).

`f7cf639`가 미완성 경로를 남긴 것은 맞다. 그 커밋이 컨텍스트 한계로 여기서 끊겼고, 메시지에
*"수동 분기는 step 5로 이어진다(다음 커밋)"* 이라고만 적혀 있다. 이 문단이 그 "다음 커밋"이다.

### Figma가 그리는 것

`get_design_context`로 `438:3199`를 열어라. 요지:

- 제목 `어떤 일정이 있으세요?` / 리드 `이미 정한 일정을 옮겨 담아드릴게요.`
- 여행 기간의 **모든 날**에 대해 `day 1 / 10.4/일` 헤더 + spine
- 각 날에 넣은 장소마다 카드: 장소명, **`오전 ▾` 시간대**, **`✕` 삭제**
- 각 날 끝에 `+ 장소 추가`
- 하단 Bottom CTA

### 계약 — 막힌 것 없다

`SeedTripItem`(`openapi.yaml:4899`)이 필요한 걸 전부 갖고 있다:

```yaml
required: [placeId, date, position]
startTime:  type: [string, "null"]  pattern: "^([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$"
constraints: maxItems 4, SetConstraintInput[]
```

`startTime`은 [#145](https://github.com/yutakdv/Nullnull/issues/145)로 offset 없는 local time으로
확정됐다. **`BA-030`이 `integration-ready`이고 이 화면을 소유한다.** BE를 기다릴 이유가 없다.

### 만들 때 쓸 것

- 장소 검색은 `MustVisitScreen.tsx`가 이미 `searchPlaces`로 한다. 그 패턴을 본떠라
  (`SearchField`, 결과 목록, `PlaceThumbnail`, `DataAttribution`).
- draft는 `wizard.ts`의 `WizardDraft`에 날짜별 항목을 더하는 형태가 자연스럽다. 순수 함수로
  두면 렌더 없이 test할 수 있다 — 이 파일의 기존 함수들이 그렇게 돼 있다.
- `toCreateRequest`가 `seedItems`를 만들도록 확장한다. **지금은 `mustVisit`를 의도적으로 빼고
  있고 그 이유가 주석에 있다** — 그건 날짜 없는 장소 얘기이고, 이 화면은 날짜가 있으므로
  `seedItems`로 보낼 수 있다. 주석을 읽고 구별해라.
- 시간대(`오전`/`오후`)를 `startTime`으로 어떻게 매핑할지는 **정해진 바 없다.** 임의로 정하지
  말고, 오전=`09:00:00` 같은 값을 지어내는 대신 사용자에게 물어보거나 `null`로 두는 쪽을
  검토해라. 지어낸 시각은 서버가 그대로 저장하고 일정에 박힌다.

## 작업 2 — `438:3259` S02-5C 최종 확인

### Figma가 그리는 것 (확인 화면)

- 제목 `이렇게 입력하셨어요` / 리드 `맞는지 확인하고, 꼭 가고 싶은 곳을 골라주세요.`
- Hint 박스 2줄: `고른 곳은 다른 장소로 바꾸자고 하지 않아요. / 대신 덜 붐비는 날짜를 알려드려요.`
- 날짜별 그룹 + stop 카드(장소명, 시간대, 분류·구, **CrowdBar + 등급**)
- 각 카드 우측에 **`Pick · on/off` 필수 장소 토글** ← 핸드오프가 빠뜨린 것
- 하단 `혼잡도는 공식 혼잡 예측이에요 · 출처: ⓒ한국관광공사`
- CTA primary `이 일정으로 시작하기` + secondary `다시 고칠래요`

### 만들 수 있는 것과 없는 것

| 부분 | 가능? |
| --- | --- |
| 되보여주기(날짜별 그룹, 장소, 시간대) | **가능** |
| Pick 토글 → `seedItems[].constraints`의 `MUST_VISIT` | **가능** — 한 transaction |
| `다시 고칠래요`(이전 단계로) | **가능** |
| **stop별 혼잡도(`4 · 혼잡`)** | **만들지 마라** |

**혼잡도를 만들지 않는 이유**: 그 값을 줄 계약이 이 경로에 없다. 한 장소의 예측을 하루로
쓰는 것은 아무도 재지 않은 숫자다(**불변식 8**). `MoveDaySheet.tsx:21-25`와
`ScheduleCandidateSheet.tsx`가 같은 줄을 같은 이유로 비워 두었고
[#105](https://github.com/yutakdv/Nullnull/issues/105)가 추적한다. **같은 선례를 따르고 주석에
이유와 추적 이슈를 적어라.**

Pick 토글이 `mustVisit`(후보, #180 B안)가 아니라 `seedItems[].constraints`인 것이 중요하다 —
이 화면의 장소는 **이미 날짜가 있으므로** lock을 걸 수 있다. #185의 부분 실패 질문은 날짜 없는
후보를 N번 POST하는 경로 얘기이고, 여기는 `createTrip` 한 번이라 해당하지 않는다.

## 이 저장소에서 일하는 방식 (매번 지켜야 하는 것)

### 검증

```bash
cd apps/web && npm run verify:ci      # 8종: tokens·lint·format·tsc·packages·test·build·budget
cd /Users/youngjun/Documents/Nullnull && python3 scripts/validate_docs.py
python3.13 -m unittest discover -s scripts/tests -p 'test_*.py'   # 기본 python3는 3.9라 5건을 건너뛴다
```

`docker-integration`은 로컬에서 돌리지 않는다. **돌리지 않은 검사를 통과로 쓰지 마라** —
커밋 메시지에도 그렇게 적는다.

### 변이 검증 (AGENTS.md 규칙 7②) — 빠뜨리지 마라

새 test를 넣으면 **그것이 실제로 발화하는지** 확인한다. 가드를 지우거나 값을 뒤집고 빨개지는지
본다. 반경도 본다 — 1건만 빨개져야 정상이고, 여러 건이 빨개지면 *"동작을 바꾼 게 아니라 코드를
못 돌게 만든 것"*일 수 있다.

```bash
cp <파일> /tmp/x.bak && python3 - <<'PY'
# 변이 적용
PY
npx vitest run <경로> --reporter=dot | grep -E "FAIL|Tests "
cp /tmp/x.bak <파일> && rm -f /tmp/x.bak
# 복원됐는지 grep 으로 확인 — "복원했다"를 믿지 말고 본다
```

이번 세션 실측: Toast 제거 → red=4, `nothingRead` false → red=1, `method`→`create` → red=3.
전부 의도한 반경이었다.

### 커밋

- Work ID 필수(`#FE-xxx`, `FR-xxx`).
- `git add`와 `git commit`을 **한 명령으로 묶어라.** 사이에 확인을 끼우면 다른 세션의 commit이
  내 staged 파일을 가져간다(실제로 `f96cd6f`에서 일어났다). 확인은 commit **뒤에**
  `git show --stat`으로 한다.
- 새 파일은 `git commit -- <경로>`가 안 먹으므로 `git add` 후 같은 명령에서 커밋한다.
- AI co-author trailer 금지 — `.git/hooks/commit-msg`가 거부한다.
- push 전에 `git log --oneline origin/frontend..HEAD`로 **밀 커밋 목록을 본다.** 남의 커밋이
  섞였으면 그 세션에 알린다.

### 커밋 메시지

이 저장소는 메시지에 **왜**를 적는다. 무엇을 바꿨는지가 아니라 무엇이 틀렸었고 왜 그렇게
고쳤는지, 만들지 않은 것과 그 이유, 변이 결과, 실행한 검증과 실행하지 않은 검증. 최근 커밋
넷을 읽어보면 형식을 알 수 있다.

## 열려 있는 것 (FE가 기다리는 것)

| 이슈 | 내용 | 주인 |
| --- | --- | --- |
| [#247](https://github.com/yutakdv/Nullnull/issues/247) | `REC-CON-04` 추천 draft 계약 → `384:5673` | **BE/AI** |
| [#240](https://github.com/yutakdv/Nullnull/issues/240) | e2e 13건 실패, 쿠키 없는 딥링크 → A-1/A-2/A-3 결정 | **BE/오너** |
| [#81](https://github.com/yutakdv/Nullnull/issues/81) | FE-203 status 모순 (내가 `a425e71`을 근거 없이 되돌림) | 판정 대기 |
| [#105](https://github.com/yutakdv/Nullnull/issues/105) | 일자별 혼잡 표시 (FCR-029) | BE/AI |

**#240이 required 게이트를 막고 있다.** `docker-integration`이 실패 중이고 FE가 단독으로
안전하게 고칠 수 없다는 것이 측정으로 확인됐다(httpOnly 쿠키라 클라이언트가 "쿠키 없음"과
"만료"를 구별 못 한다). 이걸 우회하려고 시도하지 마라 — 만료 사용자의 여행이 유실된다.

## 내가 제안했지만 하지 않은 것

**`FIGMA_HANDOFF.md` 두 줄 수정** — `183`(`527:4695` 서술)과 `154`(`438:3259`의 Pick 누락).
두 역할이 동시에 고치는 파일이라 단독으로 바꾸지 않았다. 고칠 거면 `FIGMA_CHANGE_REQUESTS.md`
등록 규칙을 먼저 읽어라(본문에 `FCR-0XX`를 쓰려면 표에 먼저 등록해야 `validate_docs.py`가
통과한다).
