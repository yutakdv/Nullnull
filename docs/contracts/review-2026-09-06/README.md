---
aliases:
  - "이슈 11 계약 보강과 PM 재현 자료"
doc_type: reference
status: draft
area: contracts
tags:
  - nullnull/reference
  - nullnull/contracts
---

# 이슈 #11 · 계약 보강과 재현 자료

[GitHub #11](https://github.com/yutakdv/Nullnull/issues/11)의 A1~A6를 OpenAPI 0.2.1-rc.1 제안으로 반영했다. 이전 기준은 0.2.0이며 Frontend 생성 client/fixture 검토 전에는 release 계약으로 동결하지 않는다. 기존 이벤트 shape, DB migration과 앱 구현은 변경하지 않았다.

## 반영 사항

| 요구 | 반영한 계약 | 현재 검증 / 남은 일 |
| --- | --- | --- |
| A1 KTO 예시 | DataProvenance의 KTO_KOR_SERVICE_2 QUALITATIVE, KTO_CONCENTRATION_FORECAST FORECAST | officialUrl/licenseUrl 공식 페이지 확인·schema 검사; 실제 호출 증거 아님 |
| A2 좁은 카드 출처 | 선택 nullable attributionShort, 1~160자 | 생략/null fallback·길이 오류 검증; FE 카드/접근성 확인 |
| A3 서버 기준 undo | 선택 revertAvailability 4-state | 기존 응답 호환·enum 검사; 서버 시계/경합 실행은 BA-051/053 |
| A4 출처 링크 정책 | source-link-policy.json exact HTTPS host 목록 | 예시 URL 허용·위장 host/계정정보/port 거부 fixture; FE wrapper 반영 |
| A5 최초 결정 union | decideOptimization 200 → InitialOptimizationDecision(APPLY/KEEP) | REVERT 거부·KEEP 필드 접근 TS 오류; history/revert 응답 유지 |
| A6 교차 검증 | canonical examples·46개 schema/policy check·TS strict | FE 재현/승인, FCR 디자인·구현 증거 필요 |

선택 필드 추가와 예시는 기존 wire payload를 계속 허용한다. A5는 원래 허용된 최초 action만 응답하도록 generated union을 좁히므로 타입을 소비하는 코드의 검토가 필요하다. 이를 영향 없는 변경으로 취급하지 않는다. 또한 구버전 additionalProperties=false 검증기는 새 응답 필드를 거부할 수 있으므로 FE mock/client/validator를 같은 계약 SHA로 갱신한 뒤 응답을 활성화한다.

이슈의 과거 node 392:368/399:613/418:5199를 현재 디자인 위치로 자동 승인하지 않는다. 이번 52개 화면 대조에서 확인한 feed 391:310, post 398:611, applied 417:2412, Live 418:2523 및 [화면 확인표](../../design/SCREEN_REVIEW_2026-09-06.md)를 검토 출발점으로 쓴다. FCR-010/011/015는 Open 상태다.

## 출처 표시

서버가 내려준 attributionShort가 유효하면 좁은 카드에 그대로 표시하고, 없거나 null이면 attribution을 사용한다. 긴 출처를 임의로 줄이거나 provider code에서 문구를 새로 만들지 않는다. full attribution·공식 링크·이용조건은 같은 카드의 키보드/스크린리더로 접근 가능한 출처 상세에서 제공한다. hover만으로 열리는 정보에 숨기지 않는다. 다른 provider의 출처를 하나로 합치지 않는다.

KTO 두 공식 상세의 이용허락범위 영역은 공공데이터포털 정책 링크를 제공한다. 관광정보 텍스트 예시는 그 안내를 반영했으며 개별 이미지의 1/3유형 조건은 별도 asset 검토 대상이다. 예시 license를 모든 사진의 이용허락으로 상속하지 않는다. 정책/표시/host의 정본은 [Source catalog](../../data/SOURCE_CATALOG.md)와 [링크 정책](source-link-policy.json)이다.

forecast 예시는 일별 relative index다. 날짜 경계를 나타내는 targetAt을 시간대별 예측으로 표시하지 않는다. freshness/observedAt/confidence가 미확인인 상태를 null/UNKNOWN으로 보존했다. 비교에 필요한 freshness/group metadata가 미확정이므로 기존 MISSING_PROVENANCE 사유로 비교를 막으며 새 reason code를 임의로 만들지 않는다. 비교 불가 예시이며 실제 개선폭이나 KTO 호출 실증을 뜻하지 않는다. 양성 최적화 fixture는 PM-014/B03에서 별도로 검증해야 한다.

## 되돌리기 상태

revertAvailability는 저장 column이 아니라 owner-scoped 조회 시점의 서버 계산값이다. 다음 순서로 하나를 정한다.

| 우선순위 | 조건 | 값 |
| --- | --- | --- |
| 1 | APPLY 없음 | NOT_APPLICABLE |
| 2 | 해당 APPLY가 이미 되돌려짐 | REVERTED |
| 3 | serverNow ≥ APPLY.revertUntil | EXPIRED |
| 4 | 현재 trip version ≠ APPLY.resultingTripVersion | NOT_APPLICABLE |
| 5 | 나머지 | AVAILABLE |

Frontend는 AVAILABLE에서만 undo를 활성화하고 최신 응답으로 상태를 갱신한다. 필드가 없는 구버전 응답은 지원 미확인 상태다. 기기 시계만으로 활성화하지 않는다. APPLY/KEEP/REVERT 성공 후 getOptimization을 재조회하고, 창 복귀·버전 변경·만료 표시 시에도 상태를 갱신한다. 409 TRIP_CHANGED나 410 REVERT_WINDOW_EXPIRED는 API 규칙에 따라 현재 상태를 보여준다.

preview TTL은 미결정 proposal의 신규 결정 기한이다. 이미 APPLIED인 run의 읽기를 그 TTL만으로 410 처리하면 24시간 undo 복구가 깨지므로, 기존 owner/보존 정책 안에서 decision metadata와 상태를 조회한다. 만료돼도 run.status는 APPLIED이며 revertAvailability만 EXPIRED다. 이력을 위해 snapshot을 복제하거나 보존을 연장하지 않는다.

조회 직후 상태가 바뀔 수 있으므로 AVAILABLE은 mutation 성공 보장이 아니다. 서버는 owner·trip version·기한·중복 REVERT를 transaction에서 다시 검사한다. 후보 독립 version, 전체 상태별 run schema, 취소/뒤로 가기, KEEP 대상 부재 등 PM-009/015의 나머지 문제는 이 선택 필드만으로 닫히지 않는다.

## 재현 방법

이 폴더의 package.json/lock은 격리된 검토 도구다. root 앱 scaffold가 아니며 B01의 packages/api-client를 대체하지 않는다. Node 24.20.0/npm 11.19.0 기준으로 실행한다.

~~~bash
cd docs/contracts/review-2026-09-06
npm ci --ignore-scripts
node verify.cjs > results.local.json
npx --no-install openapi-typescript ../../api/openapi.yaml --default-non-nullable=false --output schema.d.ts
npx --no-install tsc --project tsconfig.json
npx --no-install openapi-typescript ../../api/openapi.yaml --default-non-nullable=false --output schema.d.ts --check
~~~

46개 check는 정본에 담긴 모든 inline example, 잘못된 scope/decision, optional/nullable 호환성, URL 정책을 검사한다. typecheck.ts의 @ts-expect-error는 오류가 발생하지 않아도 실패하므로 discriminator 회귀를 잡는다. 날짜·UUID의 실제 형식은 TS string으로 보장되지 않으며 AJV/server가 검사한다.

[probes.json](probes.json)의 8개 입력은 별도 미해결 제품 의미 검사다. 현재 8개 모두 제품 기대와 계약이 다르게 동작한다. 이 결과를 pass로 합산하지 않는다. 같은 관심사 code 중복은 추가 domain 검증이 가능하므로 schema 허용만으로 DB 버그라고 단정하지 않는다. 결과와 환경은 [verification.json](verification.json)에 기록한다.

cdk-smoke.cjs는 #10의 패키지 조합만 확인하는 최소 Stack이며 배포하지 않는다. 앱의 infra:check에서 이 파일로 실제 infra app 검사를 대체할 수 없다.

## FE 검토 항목

- 동일 OpenAPI SHA로 생성하고 attributionShort·nullable fallback을 Storybook/MSW에서 확인한다.
- SourceBadge/Attribution/DataGuide에서 full credit·두 provider·URL 거부·keyboard 동작을 확인한다.
- applied 새로고침·구버전 필드 부재·기기 시계 오차·24시간 경계·동시 수정·중복 REVERT를 확인한다.
- InitialOptimizationDecision의 APPLY/KEEP만 사용하는 소비 코드와 history의 3-way union을 확인한다.
- FCR-010/011/015의 node/state와 구현 test가 연결되면 Ready/Closed 기준에 따라 별도로 승인한다.

현재는 검토 가능한 계약 diff와 재현 자료까지 작성했다. Frontend 승인을 대신하거나 원격 이슈를 닫지 않았다.
