# @nullnull/web

널널(Nullnull)의 모바일 PWA.
React 19 · Vite 7 · react-router 7 · TanStack Query 5 · CSS Modules.

## 개발 서버

```bash
npm install        # 저장소 루트에서 (workspace)
npm run dev        # http://localhost:5173
```

`npm run dev`는 실제 API를 사용한다. Vite가 `/api`를 `API_INTERNAL_BASE_URL`로 proxy하며,
값이 없으면 `http://localhost:8080`을 사용한다. 배포 API를 로컬 UI에서 확인할 때는 브라우저가
아닌 Vite proxy 대상만 바꾼다. 세션 cookie와 CSRF 요청은 계속 같은 origin으로 보인다. 외부
배포 주소를 지정하면 Vite가 upstream Host를 사용하며, 브라우저에서 넣은 배포 게이트 헤더도
값을 저장하지 않고 그대로 전달한다.

승인된 fixture로 화면만 독립 실행해야 할 때에만 mock을 명시적으로 켠다.

```bash
API_INTERNAL_BASE_URL=https://example.invalid npm run dev  # 배포 API proxy
npm run dev:mock                                      # MSW fixture
```

`npm run dev:api`는 기존 로컬 작업 호환을 위한 `npm run dev`의 별칭이다.

## 라이브 지도 (카카오맵)

라이브 탭은 카카오맵 Web SDK로 지도를 표시하고, `/live/areas`의 좌표가 있는 권역을
지도 위 선택 버튼으로 표시한다. SDK를 사용할 수 없어도 검색과 권역 목록은 이용할 수 있다.

1. [카카오 개발자 문서](https://developers.kakao.com/docs/ko/kakaomap/common)에 따라 카카오맵 API를 활성화하고 **JavaScript 키**의 JavaScript SDK 허용 도메인에 현재 웹 origin을 등록한다. 로컬 확인용 서버가 `http://127.0.0.1:5175`라면 해당 origin도 등록한다. REST API 키는 지도 SDK에 사용할 수 없다.
2. `apps/web/.env.local`에 `VITE_KAKAO_MAP_APP_KEY=발급받은_JavaScript_키`를 설정한다. `.env.local`은 Git에서 제외되며, 키를 소스나 테스트 fixture에 넣지 않는다. 브라우저에서 쓰는 JavaScript 키이므로 공개 노출을 전제로 허용 도메인을 제한한다.
3. 개발 서버를 재시작한 뒤 `/live`에서 실제 지도 타일과 권역 버튼이 나타나는지 확인한다. 지도가 표시되지 않으면 카카오맵 API 활성화, 허용 도메인과 브라우저의 SDK 요청 오류를 확인한다.

mock worker는 `public/`이 아니라 `mocks/mockServiceWorker.js`에 있고 Vite dev 미들웨어가
서빙한다(`vite.config.ts`). `public/`에 두면 `dist/`로 복사돼 production 이미지가 mock을
서빙할 수 있고, `scripts/integration-test.sh`가 그 이미지로 E2E를 돌리기 때문에 게이트가
아무것도 증명하지 못한 채 통과한다. **worker를 `public/`으로 옮기지 않는다.**

## 검증

```bash
npm run verify:ci  # tokens drift · lint · format · tsc · packages · vitest · build · bundle budget
npm run test       # vitest만
npm run test:e2e   # Playwright. dev:mock 서버를 직접 띄운다
```

`e2e/session.spec.ts` 3건은 `apps/api`(:8080)를 직접 호출하므로 백엔드 없이는
실패한다. 브라우저를 쓰지 않는 transport 테스트라서 MSW가 가로채지 않는다.
나머지 E2E는 명시적인 `dev:mock` 서버로 돈다.

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
