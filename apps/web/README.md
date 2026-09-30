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

## 운영 빌드와 게시물 업로드

`npm run build`는 모의 API를 제외한 운영 번들을 만든다. `npm run preview`는 `serve.mjs`로
이 번들을 제공하며 `/feed`, `/live`, `/profile`, `/posts/new` 같은 직접 접근 경로도 지원한다.
이 서버를 사용할 때는 `API_INTERNAL_BASE_URL`에 내부 API 주소를 설정해야 한다. 별도 정적
호스팅에서는 화면 경로의 SPA fallback과 같은 origin의 `/api` 전달이 필요하다. 브라우저의
API 주소는 현재 사이트의 `/api/v1`이며 로컬 개발 주소를 운영 번들에 넣지 않는다.

사진 선택 시 `createPostImageUpload`로 업로드를 예약하고, 응답의 `UploadTicket`에 담긴
주소·메서드·헤더로 사진을 보낸다. 저장소 요청에는 API 세션 cookie나 CSRF를 보내지 않는다.
사진 전송 성공과 글·장소 입력이 확인되어야 게시 전 확인의 업로드 버튼을 사용할 수 있다.
운영에서는 HTTPS, 실제 API 세션·CSRF, 저장소의 업로드 권한·CORS 설정이 필요하다.

`dev:mock`에는 업로드 예약·게시 요청의 성공 handler가 없다. 사진 미리보기만으로 서버에
사진이 저장됐다고 보지 않는다. `post-create.mock.spec.ts`의 업로드·게시 성공은 테스트용
응답과 임시 업로드 서버로 검증한 결과다. 실제 배포 확인에서는 사진 예약 → 저장소 전송 →
게시 생성 → 게시물 상세·피드 표시까지 확인해야 하며, mock E2E 통과로 대신하지 않는다.

## 라이브 화면

`/live`는 카카오 지도와 `queryLiveAreas`의 권역 목록·검색을 함께 제공한다.
키가 없거나 SDK를 불러오지 못해도 목록과 상세 링크는 계속 이용할 수 있다.
권역 좌표가 없는 응답은 지도 위치를 추정하지 않는다. 장소의 `지도에서 보기`를 선택하면
`getPlace`의 실제 장소 좌표로 표시하며, 권역 혼잡은 개별 장소의 측정값으로 바꾸지 않는다.

로컬 개발은 `VITE_KAKAO_MAP_APP_KEY`에 공개 JavaScript SDK 키를 설정한다. 배포는
같은 이름의 GitHub repository variable을 web build에 전달한다. Vite는 build 시 값을
포함하므로 runtime 환경변수만 바꾸어서는 적용되지 않는다. 카카오 개발자 콘솔에 실제
웹 origin을 등록하고, 서버용 REST API 키를 이 값에 넣지 않는다.

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

`e2e/session.integration.spec.ts` 3건은 `apps/api`(:8080)를 직접 호출하는 transport
테스트라 MSW가 가로채지 않는다. 그래서 `*.integration.spec.ts` 이름으로 두어 mock 실행은
수집하지 않고(`playwright.config.ts`의 `testIgnore`) 게이트의 합성 스택에서만 돈다.
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
