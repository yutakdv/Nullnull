# @nullnull/web

널널(Nullnull)의 모바일 PWA.
React 19 · Vite 7 · react-router 7 · TanStack Query 5 · CSS Modules.

## 개발 서버

```bash
npm install        # 저장소 루트에서 (workspace)
npm run dev        # http://localhost:5173
```

`npm run dev`는 **MSW mock을 켠 채로** 뜬다(`VITE_API_MOCKING=on`). `apps/api`를 띄우지 않아도
모든 화면이 승인된 OpenAPI example 기반 fixture로 동작한다. 이 값이 없으면 Vite가 `/api`를
`localhost:8080`으로 proxy하고, 백엔드가 없으면 모든 화면이 로딩 상태에서 멈춘다.

실제 `apps/api`를 상대로 확인하려면:

```bash
npm run dev:api    # mock 없이. apps/api가 :8080에 떠 있어야 한다
```

mock worker는 `public/`이 아니라 `mocks/mockServiceWorker.js`에 있고 Vite dev 미들웨어가
서빙한다(`vite.config.ts`). `public/`에 두면 `dist/`로 복사돼 production 이미지가 mock을
서빙할 수 있고, `scripts/integration-test.sh`가 그 이미지로 E2E를 돌리기 때문에 게이트가
아무것도 증명하지 못한 채 통과한다. **worker를 `public/`으로 옮기지 않는다.**

## 검증

```bash
npm run verify:ci  # tokens drift · lint · format · tsc · packages · vitest · build · bundle budget
npm run test       # vitest만
npm run test:e2e   # Playwright. dev 서버를 직접 띄운다
```

`e2e/session.spec.ts` 3건은 `apps/api`(:8080)를 직접 호출하므로 백엔드 없이는
실패한다. 브라우저를 쓰지 않는 transport 테스트라서 MSW가 가로채지 않는다.
나머지 E2E는 mock으로 돈다.

## 구조

| 경로                      | 내용                                                   |
| ------------------------- | ------------------------------------------------------ |
| `src/app/`                | route별 화면. 폴더 하나가 기능 slice 하나              |
| `src/shared/api/`         | 생성된 client wrapper, query hook, Problem 정책        |
| `src/shared/ui/`          | `01 Components` 대응 컴포넌트와 Storybook              |
| `src/i18n/`               | ko-KR·en-US 메시지. 일본어·중국어는 disabled `준비 중` |
| `src/shared/testing/msw/` | mock handler. 실제 응답이 열리면 교체한다              |
| `mocks/`                  | MSW service worker (dev 전용, dist 제외)               |

타입과 client는 `docs/api/openapi.yaml`에서 생성해 `packages/api-client`에 있다. 응답 타입을
손으로 복제하거나 `any`로 우회하지 않는다.
