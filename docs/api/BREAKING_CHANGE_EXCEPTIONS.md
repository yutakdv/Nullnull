---
aliases:
  - "계약 breaking 예외 등록부"
doc_type: decision
status: active
area: api
tags:
  - nullnull/decision
  - nullnull/api
---

# 계약 breaking 변경 승인 예외 등록부

`docs-contract`의 OpenAPI breaking diff는 `fail-on: WARN`이다. 따라서 **의도된 계약 정정이라도 자동으로는 통과하지 못한다.** 이 문서는 그 예외를 한 곳에 모아 감사 가능하게 만든다.

`continue-on-error`나 `ignoreFailures`와는 다르다. 그쪽은 검사 전체를 무력화하지만, 여기서는 **정확히 한 줄의 oasdiff 메시지**만 면제되고 나머지 breaking 변경은 그대로 실패한다. `AGENTS.md`의 CI 등록 규칙 4(skip 금지)가 막는 것은 "검사가 돌지 않는 것"이며, 이 등록부는 검사를 돌린 뒤 개별 결과를 승인하는 경로다.

## 절차

1. 계약을 고치기 전에 **push 없이 로컬에서 먼저 확인한다.**

   ```bash
   git show origin/main:docs/api/openapi.yaml > /tmp/base-openapi.yaml
   docker run --rm -v /tmp:/spec -v "$PWD/docs/api:/rev" \
     tufin/oasdiff breaking /spec/base-openapi.yaml /rev/openapi.yaml --fail-on WARN
   ```

2. 출력된 메시지가 정말 면제해도 되는지 판단한다. **소비자가 실제로 깨질 수 있으면 면제 대상이 아니다.** 계약을 바꾸지 말고 additive 경로를 찾는다.
3. 면제한다면 `oasdiff-ignore.txt`에 한 줄로 넣는다. **출력은 세 줄이고 그중 한 줄을 고르면 동작하지 않는다** — `in API …`로 시작하는 위치 줄과 바로 아래 메시지 줄을 **공백 하나로 이어** 한 줄로 만든다. 예:

   ```text
   warning [request-property-removed] at /rev/openapi.yaml     ← 쓰지 않는다
     in API POST /trips/{tripId}/items/{itemId}/replace        ┐ 이 둘을
       removed the request property `relationId`               ┘ 공백 하나로 잇는다
   ```

   넣은 뒤 `--warn-ignore`(와 `--err-ignore`)로 **실제로 억제되는지 실행해 확인한다.** `scripts/check_oasdiff_exceptions.py`는 공백 정규화 후 **부분 문자열**만 보므로, 짧은 형태를 넣어도 그 script는 통과시키고 게이트만 빨개진다. 두 검사가 다른 질문에 답한다 — 하나가 green인 것을 다른 하나의 답으로 쓰지 않는다.
4. 같은 메시지를 아래 표에 등록한다. 이유·승인자·추적 이슈가 모두 있어야 한다.
5. 정정이 `main`에 반영되고 base가 새 값이 되면 그 줄은 더 이상 매칭되지 않는다. **그때 두 곳에서 함께 지운다.**

   **"그때"는 계약 PR을 머지하는 순간이고, 지우는 사람은 그 PR의 주인이다.** 이 한 줄이 없어서
   **두 번 같은 일이 났다**(`DATA_INSUFFICIENT`·#225, 그리고 `RECOMMENDATION_UNAVAILABLE`·`INTERNAL_ERROR`·#261).
   두 번 다 계약을 넣은 쪽이 아니라 **그 뒤 처음 PR을 연 쪽**이 빨간불을 봤다.

   이유는 타이밍이다: oasdiff diff는 `main` 대상 PR에서만 돌므로, 계약이 머지되는 순간 면제는
   이미 만료됐지만 **그 사실을 드러낼 실행이 없다.** 계약 PR 자신은 머지 전 실행에서 green이다 —
   그때는 base에 새 값이 없어 finding이 실재하고 예외가 제 일을 한다. 그래서 같은 PR에서
   **미리** 비울 수는 없고, **머지 직후 후속 커밋으로** 비우는 것이 유일한 방법이다.
   미루면 그 빨간불은 무관한 사람이 받는다.

`scripts/tests/test_oasdiff_exceptions.py`가 강제한다: ignore 줄은 전부 표에 있어야 하고, 표의 모든 행은 이유·승인자·`#번호`가 있어야 하며, 표에만 있고 ignore에 없는 **stale 행도 실패**다.

## 승인된 예외

**표가 비어 있는 것이 정상 상태다.**

| oasdiff 메시지 | 이유 | 승인자 | 추적 |
| --- | --- | --- | --- |

## 만료된 예외 (기록)

- **`LiveArea.centroid` 가 nullable 이 됐다, 한 줄**
  (승인: **오너 2026-09-20 — [#314](https://github.com/yutakdv/Nullnull/pull/314) 머지가 그 승인이다**, 추적:
  [#64](https://github.com/yutakdv/Nullnull/issues/64) · [#310](https://github.com/yutakdv/Nullnull/issues/310)).
  등록 당시 승인자 칸에는 *"조율자 판단, 오너 확인 대기"* 라고 적었다 — 오너가 그 판단을 아직 보지 않았고
  **승인 기록은 옆에서 대신 켤 수 없기** 때문이다. 오너가 머지한 지금 그 칸이 채워진다.

  **이유**: 우리가 그 값을 모른다. 서울 feed 는 구역 좌표를 어디에도 공개하지 않는다 — 응답 전수 스캔 0건,
  데이터셋 상세 페이지에 출력 필드표 없음, 매뉴얼 v8.5 전문(`pdftotext -layout`, 64KB)에서
  `위도`·`경도`·`좌표`·`WGS`·`경계`·`polygon` 전부 0건. 대안 둘을 배제했다(법정동 코드표는 미신청이고
  **행정동 경계라 관광 구역과 단위가 다르다**; 좌표 계산은 발명이다). 소비자는 0건이었다 —
  `apps/web` 에서 `centroid` 참조 0, 유일한 등장이 같은 저장소에서 재생성되는 생성 client 의 타입 한 줄.

  **`required` 제거와 비용이 같았다** — 둘 다 oasdiff 1 error(`response-property-became-nullable` vs
  `response-property-became-optional`)였고, 비용이 같아 **정확성으로 골랐다**: *"항상 있고 값이 null"* 이
  *"없을 수도 있다"* 보다 client 에게 **모른다는 사실을 강제**한다.

  **정리 시점**: `#314` 머지 직후, 다음 PR 이 열리기 전. 바로 위 항목이 *"이번에는 다음 PR 이 빨개지기 전에
  치웠다"* 라고 적은 그 절차가 **연속 두 번 작동했다.** 앞의 두 기록(`DATA_INSUFFICIENT`·#225,
  `OptimizationFailure.code`·#261)은 둘 다 무관한 사람이 빨간불을 받았다.

- **`Notification.deepLink` pattern의 `/trips/`→`/trip/` 정정, 한 줄**
  (승인: 오너 2026-09-20, 추적: [#310](https://github.com/yutakdv/Nullnull/issues/310)).
  [#313](https://github.com/yutakdv/Nullnull/pull/313)이 `main`에 머지되면서 base가 따라 움직여 만료됐다 —
  CI의 두 단계를 로컬에서 그대로 재현하니 oasdiff가 `No changes detected`이고
  `check_oasdiff_exceptions.py`가 그 줄을 stale로 rc=1이었다.

  **이번에는 다음 PR이 빨개지기 전에 치웠다 — 앞의 두 기록과 다른 점이 그것뿐이다.**
  `DATA_INSUFFICIENT`과 `OptimizationFailure.code` 두 항목이 각각 *"정리를 미루면 그 뒤 처음 열린 PR이
  빨개진다"* 를 경고했고 두 번 다 그대로 났다. 세 번째인 이번에 달라진 것은 **경고를 더 잘 적은 것이 아니라
  머지 직후를 정리 시점으로 절차에 박은 것**이다(절차 5). 기록이 세 번 같은 말을 했다는 것은
  **읽히는 자리에 없었다는 뜻**이지 문구가 약했다는 뜻이 아니다.

- **`OptimizationFailure.code`에 `RECOMMENDATION_UNAVAILABLE`·`INTERNAL_ERROR` 추가, 네 줄**
  (승인: FE 승인을 오너가 조율 세션에 전달, 추적: [#261](https://github.com/yutakdv/Nullnull/issues/261)).
  두 operation이 같은 schema를 내므로 값 하나가 두 줄이었다(`GET /optimizations/{runId}` 200,
  `POST /trips/{tripId}/optimizations` 202). 계약이 `main`에 들어가면서 base가 따라 움직여 만료됐다 —
  로컬에서 CI 단계를 그대로 재현하니 oasdiff가 `No changes detected`이고
  `check_oasdiff_exceptions.py`가 네 줄 전부를 stale로 rc=1이었다.

  **`DATA_INSUFFICIENT` 항목이 경고한 지연이 그대로 재현됐다 — 두 번째다.** `#261`의 계약 PR은
  정리를 하지 않았고, 그래서 **그 뒤 처음 열린 PR**(FE의
  [#267](https://github.com/yutakdv/Nullnull/pull/267))이 빨개졌다. 계약을 넣은 쪽과 실패를 본 쪽이
  다시 갈렸고, 이번에는 정리도 그 PR이 했다.

  **경고가 있는데도 같은 일이 난 이유 둘이 이 기록의 값이다.**

  하나는 **자리**다. 그 경고는 *만료된* 예외 절에 있고, 예외를 **등록하는** 사람이 읽는 것은 위쪽
  `## 절차`와 `## 승인된 예외`다. 등록 시점에 보이는 자리에 없으면 산문은 다음 사람에게 도달하지
  않는다. 그래서 이번에는 `## 절차` 5번에 넣었다.

  다른 하나는 **그 경고의 처방이 실행 불가였다는 것**이다. 원문은 *"계약 PR을 머지한 직후 **같은
  PR에서** ignore 줄을 비우는 것이 유일한 방법"* 이었는데, 같은 PR에서는 비울 수 없다 — 재현해
  쟀다: `00412ca`의 부모를 base, `00412ca`를 revision으로 두면 finding이 4건이고, 빈 ignore로
  oasdiff action을 돌리면 **rc=1로 그 PR이 막힌다**. 그 시점의 면제는 stale이 아니라 실재하는
  finding에 대응하므로 필요하다. `check_oasdiff_exceptions.py`는 같은 조건에서 rc=0을 주는데,
  그 검사는 *"예외가 stale인가"* 만 묻고 *"finding이 억제됐는가"* 는 묻지 않기 때문이다 —
  두 검사가 다른 질문에 답한다(`## 절차` 3번이 같은 비대칭을 적고 있다).

  **그래서 올바른 시점은 "머지 직후 후속 커밋"이고, 그것을 5번에 적었다.** 원문을 지우지 않고
  정정한 이유는, 그 문장을 그대로 따른 사람이 막히고 나서 *"그럼 언제"* 를 다시 유도해야 하기
  때문이다.

  코드 자체의 필요는 승인 당시 기록대로다: run이 dead-letter되면 FAILED로 끝나야 하는데 담을 코드가
  없었고(`V024` CHECK가 FAILED에 코드를 요구한다), 기존 여섯은 전부 여행·근거의 원인이라 서비스
  장애를 그중 하나로 적으면 불변식 6을 깬다. FE는 모르는 코드를 `run.failure.unknown`으로 접고
  CTA를 서버의 `retryable`로 고르므로(FE-502) 배포 순서와 무관하게 깨지지 않는다 —
  `RECOMMENDATION_UNAVAILABLE`만 전용 문구를 받았고 `INTERNAL_ERROR`는 **일부러** 접힌다
  (`apps/web/src/i18n/messages.ts`에 키가 없고 그 접힘을 test가 고정한다).

- **`OptimizationFailure.code`에 `DATA_INSUFFICIENT` 추가** (승인: 오너, 추적: [#225](https://github.com/yutakdv/Nullnull/issues/225)).
  PR [#229](https://github.com/yutakdv/Nullnull/pull/229)로 반영됐고 base가 따라 움직여 만료됐다 — `main`의 `openapi.yaml`이
  이제 그 enum 값을 담고 있으므로 oasdiff가 두 operation(`GET /optimizations/{runId}` 200,
  `POST /trips/{tripId}/optimizations` 202) 어느 쪽에서도 `response-property-enum-value-added`를 내지 않는다.
  **이 정리는 다음 PR이 강제했다**: `check_oasdiff_exceptions.py`가 `stale oasdiff exception (no longer reported, remove it)`로
  `docs-contract`를 빨갛게 만들었고, 그것이 이 장치의 목적이다 — **예외가 자기 수정보다 오래 살 수 없다.**
  `apps/ai`는 비교 자격 없음·관측 없음·신선도 만료·대안 0건 네 자리에서 이 코드를 내고, 그전에는 그 넷이
  `NO_IMPROVEMENT`(더 나은 답이 없다)나 `UNEXPECTED_FAILURE`(우리가 깨졌다)로 접혔는데 **둘 다 거짓**이었다.
  이 코드는 **retryable이다**(오너 결정 2026-09-19, #225) — 근거가 아직 없는 것이라 예보가 들어오면 답이 바뀐다.
  `retryable`은 원인에 대한 진술이고(`OptimizationFailureCode`), 화면은 그것으로 재계산을 켠다. 이 기록은 처음에
  "retryable이 아니다"로 적혔고 코드·FE와 어긋나 있었다.

  **그리고 이 지연은 PR에서만 드러난다.** oasdiff diff는 `main` 대상 PR에서만 돌므로, 계약이 머지된
  순간 면제는 만료됐지만 그 뒤 **처음 열린 PR**([#234](https://github.com/yutakdv/Nullnull/pull/234), FE)에서야
  빨개졌다 — 계약을 넣은 쪽과 실패를 본 쪽이 달라진다. 계약 PR을 머지한 직후 같은 PR에서 ignore 줄을
  비우는 것이 그 지연을 없애는 유일한 방법이다.

  **정정(#261 건에서 측정):** 위 마지막 문장의 *"같은 PR에서"* 는 틀렸다. 같은 PR에서는 비울 수 없다 —
  그 시점의 면제는 실재하는 finding에 대응하므로 비우면 oasdiff action이 rc=1로 그 PR을 막는다.
  올바른 시점은 **머지 직후 후속 커밋**이고 `## 절차` 5번에 적혀 있다. 지연의 진단(*계약을 넣은 쪽과
  실패를 본 쪽이 달라진다*)은 그대로 옳다.

정정이 `main`에 반영되면 base가 새 값이 되어 해당 메시지는 더 이상 보고되지 않는다. 그 시점에 ignore 줄을 지우고 행을 여기로 옮긴다. `scripts/check_oasdiff_exceptions.py`가 CI에서 이 정리를 강제한다 — 매칭되지 않는 ignore 줄이 남아 있으면 실패한다.

- **`relationId` request property 제거** (승인: 오너, 추적: #204). PR #213으로 반영됐고 base가 따라 움직여
  만료됐다 — `main`의 `openapi.yaml`에 `relationId`가 **0건**이므로 oasdiff가 더는 그 메시지를 내지 않는다.
  client가 값을 만들 수 없는 필드였다: 어떤 응답도 relation id를 발급하지 않고 `RelatedPlace`에 id가 없으며
  저장하는 table도 없어서, 보낼 수 있는 값이 `null`과 스스로 지어낸 UUID뿐이었고 후자는 서버가 검증할 방법이
  없었다. FE는 이 필드를 보낸 적이 없어(`replacementPlaceId`만 전송) `apps/web`은 한 줄도 바뀌지 않았다.
  **이 건이 절차의 결함 하나를 더 드러냈다**: 처음 등록한 줄은 oasdiff 렌더링에서 잘라낸 메시지 한 줄이었는데,
  action이 인식하는 것은 `in API …` 위치 줄과 메시지 줄을 **이어붙인 한 줄**이다. `check_oasdiff_exceptions.py`는
  공백 정규화 후 **부분 문자열**만 보므로 짧은 형태도 `live`로 보고했고, **그 green을 억제의 증거로 읽어** 게이트만
  빨갛게 남았다. 절차 3에 이어붙이기와 `--warn-ignore` 확인이 그래서 들어갔다.

- **`runLink` pattern `/trips/` → `/trip/`** (승인: 오너, 추적: #118). PR #122로 반영됐고 base가 따라 움직여 만료됐다. 정정 내용은 `openapi.yaml`의 `runLink` description에 남아 있다.

- **벽시계 시각에서 `format: time` 제거와 `HH:mm:ss` pattern 추가, 81건** (승인: 오너, 추적: #145).
  PR #150으로 반영됐고 base가 따라 움직여 만료됐다. 응답 65건은 `format`이 `time`에서 `none`으로 바뀐
  것이고 요청 16건은 pattern 추가다. 정정 내용은 `openapi.yaml`의 해당 필드와 `docs/api/README.md` §12에
  남아 있으며, `LocalTimeContractTest`가 되돌아가지 못하게 고정한다.

  **이 건에서 예외 경로 자체의 결함 두 개가 드러났다.** 하나, workflow가 `warn-ignore`만 넘겼는데 oasdiff는
  breaking을 거의 전부 `error`로 보고하므로 **등록된 예외가 하나도 적용되지 않았다** — `err-ignore`를 함께
  연결했다. 둘, 이 표의 메시지 셀이 `|`를 담을 수 없어(pattern의 alternation) parser가 조용히 매칭에
  실패했다 — `\|` escape를 쓰고 parser가 복원한다. 예외를 실제로 써 보기 전에는 둘 다 드러나지 않았다.
