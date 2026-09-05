---
paths:
  - "apps/web/**/*.{ts,tsx,css,json}"
  - "packages/api-client/**/*.{ts,json}"
  - "docs/design/**/*.md"
---

# Frontend rules

- 먼저 기능 ID, Figma node/state, 연결 operationId를 확인한다.
- `01 Components`의 이름을 임의로 합치지 말고 `COMPONENT_CATALOG.md`의 책임과 variant를 따른다.
- OpenAPI-generated type/client를 사용하며 응답 type을 수동 복제하거나 `any`로 우회하지 않는다.
- 승인된 OpenAPI example로 MSW fixture를 만들고 BE contract test와 같은 의미를 유지한다.
- server state는 query cache, edit/form buffer는 feature-local, 공유 가능한 선택은 URL에 둔다.
- 모든 API 화면에 default/loading/empty/error/offline/stale/background-refresh를 검토한다.
- mutation은 idle/submitting/success/error를 보이고, submitting 중 중복 실행을 막되 idempotency도 유지한다.
- sheet/dialog는 semantic role, accessible title, focus trap, Escape, trigger focus 복귀를 제공한다.
- drag/reorder는 keyboard/button 대안과 live-region 결과를 제공한다.
- map에는 같은 정보·필터의 list view가 있어야 한다.
- 360px, safe-area, virtual keyboard, 200% zoom, ko/en 긴 문구, reduced motion을 검증한다.
- `VITE_*`는 공개값이다. secret, raw itinerary, 정밀 위치, cookie를 analytics/log에 넣지 않는다.
- P1 capability가 OFF면 요청을 보내지 않고 명시적 disabled `준비 중` UI를 사용한다.
- Frontend 작업은 장기 `frontend`에서 하고 `main`에만 PR을 만든다. 상대 승인과 `docs-contract`·`docker-integration` 전에는 merge-ready가 아니다.
- 공모전 KTO 화면은 `출처: ⓒ한국관광공사` 또는 승인 문구와 기준시각/state를 표시한다. `TourAPI` 단독·무허가 CI/BI logo를 금지한다.
- 공모전 profile은 로그인 불필요이며 위치 capability OFF다. geolocation API·permission prompt·좌표 request를 만들지 않는다.
