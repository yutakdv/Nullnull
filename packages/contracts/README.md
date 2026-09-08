# @nullnull/contracts

MSW handler, Storybook story, vitest 테스트가 함께 import하는 계약 fixture다.
`docs/api/openapi.yaml`이 정본이고 이 패키지는 그 정본을 만족하는 JSON만 담는다.

## 현재 상태 — 잠정

`TEST_STRATEGY.md:113`은 "BE/AI 담당이 schema-valid canonical JSON을 제공하고 FE
담당이 같은 파일을 MSW/Storybook에서 import한다"고 정하고,
`OWNERSHIP_MATRIX.md:108`은 이 경로를 "BE/AI fixture, FE consumer"로 지정한다.

**여기 있는 26개는 FE가 작성했다.** FE-003(에러 처리 기반)이 fixture 없이는
시작할 수 없고, 제공된 것은 `docs/contracts/review-2026-09-06/fixtures.json`의
`OptimizationRun` 4건뿐이었기 때문이다.

규칙의 취지는 "FE가 계약과 어긋난 모델을 손으로 만들지 말라"이다. 그 취지는
`__tests__/fixtures.test.ts`가 지킨다. 모든 fixture를 `docs/api/openapi.yaml`의
component schema에 대해 ajv로 검증하며
(`docs/contracts/review-2026-09-06/verify.cjs`와 같은 방식), 어긋나면
`npm test`가 실패한다.

`fixtures/manifest.json`이 `synthetic`, `provisional`, `schemaVersion`을 기록한다.
Problem·session 스키마가 `additionalProperties: false`라 fixture 안에 넣으면
검증에 실패하므로 옆에 둔다.

## 무엇이 검증되고 무엇이 안 되나

ajv는 **형태**를 본다. 필드 존재, 타입, enum 소속, 포맷, 상한을 잡는다.

ajv가 못 보는 것은 **의미**다. "이 상태 조합이 실제 구현에서 나올 수 있는가"는
백엔드만 안다.

**PR #17 리뷰에서 이 한계가 실제로 드러났다.** 초판 fixture 23개가 전부 ajv를
통과했는데도 `type`은 23/23, `title`은 21/23이 서버 값과 달랐다. `type`을 절대
URL로, `title`을 HTTP status phrase로 지어낸 탓이다. `INTERNAL_ERROR`의
`retryable`도 반대였다.

지금은 `docs/api/openapi.yaml`의 Problem example 6개에서 `type`·`title`·
`detail`·`retryable`을 그대로 가져왔고, 나머지 17개도 그 6개가 세운 규칙
(`type`은 `/problems/<kebab-code>`, `title`은 코드별 고유 문구)을 따른다.

`detail`은 **영어**다. `docs/api/README.md` §12와 #16 R2에 따라 서버는 locale과
무관하게 영어를 보낸다. 한국어 문구는 FE 소유이고 `apps/web/src/i18n/messages.ts`에
있다. fixture에 한국어를 넣으면 서버가 하지 않는 일을 흉내내게 된다.

## 교체 방법

백엔드가 canonical fixture를 제공하면 같은 경로의 파일을 덮어쓰면 된다.
`src/index.ts`의 export 이름과 검증 테스트는 그대로 둔다. FE 코드는 fixture
파일이 아니라 이 패키지의 export를 참조하므로 호출부 수정이 필요 없다.

교체 시 `fixtures/manifest.json`의 `provisional`을 지우고 `source`를
실제 출처로 바꾼다.

## 범위

FE-003이 실제로 쓰는 것만 있다.

- `fixtures/problems/` — `Problem.code` enum 23개 전부
- `fixtures/session/` — bootstrap 3종 (401 재시도·CSRF 재발급 경로 증명용)

`TEST_STRATEGY.md:115-123`의 7종 매트릭스(collection, mutation, trip, async,
data, deletion, capability)는 **여기 없다.** 그건 화면 slice(`FE-101` 이후)의
게이트이고, 지금 만들면 ajv가 못 잡는 의미론적 추측이 들어간다. 화면 작업이
시작될 때 백엔드 제공분으로 채운다.
