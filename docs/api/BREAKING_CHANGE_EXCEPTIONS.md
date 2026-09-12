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
3. 면제한다면 oasdiff가 출력한 메시지를 **한 글자도 바꾸지 말고** `oasdiff-ignore.txt`에 한 줄로 넣는다.
4. 같은 메시지를 아래 표에 등록한다. 이유·승인자·추적 이슈가 모두 있어야 한다.
5. 정정이 `main`에 반영되고 base가 새 값이 되면 그 줄은 더 이상 매칭되지 않는다. **그때 두 곳에서 함께 지운다.**

`scripts/tests/test_oasdiff_exceptions.py`가 강제한다: ignore 줄은 전부 표에 있어야 하고, 표의 모든 행은 이유·승인자·`#번호`가 있어야 하며, 표에만 있고 ignore에 없는 **stale 행도 실패**다.

## 승인된 예외

현재 활성 예외는 없다. 표가 비어 있는 것이 정상 상태다.

| oasdiff 메시지 | 이유 | 승인자 | 추적 |
| --- | --- | --- | --- |

## 만료된 예외 (기록)

정정이 `main`에 반영되면 base가 새 값이 되어 해당 메시지는 더 이상 보고되지 않는다. 그 시점에 ignore 줄을 지우고 행을 여기로 옮긴다. `scripts/check_oasdiff_exceptions.py`가 CI에서 이 정리를 강제한다 — 매칭되지 않는 ignore 줄이 남아 있으면 실패한다.

- **`runLink` pattern `/trips/` → `/trip/`** (승인: 오너, 추적: #118). PR #122로 반영됐고 base가 따라 움직여 만료됐다. 정정 내용은 `openapi.yaml`의 `runLink` description에 남아 있다.

- **벽시계 시각에서 `format: time` 제거와 `HH:mm:ss` pattern 추가, 81건** (승인: 오너, 추적: #145).
  PR #150으로 반영됐고 base가 따라 움직여 만료됐다. 응답 65건은 `format`이 `time`에서 `none`으로 바뀐
  것이고 요청 16건은 pattern 추가다. 정정 내용은 `openapi.yaml`의 해당 필드와 `docs/api/README.md` §12에
  남아 있으며, `LocalTimeContractTest`가 되돌아가지 못하게 고정한다.

  **이 건에서 예외 경로 자체의 결함 두 개가 드러났다.** 하나, workflow가 `warn-ignore`만 넘겼는데 oasdiff는
  breaking을 거의 전부 `error`로 보고하므로 **등록된 예외가 하나도 적용되지 않았다** — `err-ignore`를 함께
  연결했다. 둘, 이 표의 메시지 셀이 `|`를 담을 수 없어(pattern의 alternation) parser가 조용히 매칭에
  실패했다 — `\|` escape를 쓰고 parser가 복원한다. 예외를 실제로 써 보기 전에는 둘 다 드러나지 않았다.
