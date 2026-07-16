# 프론트엔드 E2E 리팩토링 보고서

> 대상: `nullnull-travel-webapp` (React 19 + Vite 7)
> 원칙: **기능·UI 무변경, 코드 품질만 개선** — 모든 화면을 실제 백엔드와 연결해 리팩토링 전/후 동작 동일함을 검증했다.

---

## 1. 리팩토링 전 구조 분석과 문제점

### 1-1. 기존 구조

```
src/
├── main.jsx          # 3,538줄 — 앱의 거의 전부
├── PointsMap.jsx     # SVG 경로 지도 (60줄)
├── SeoulMap3D.jsx    # three.js 3D 지도 (182줄, 미사용)
├── styles.css
└── assets/seoul-districts.geo.json
```

### 1-2. 발견한 문제점

| # | 문제 | 상세 |
|---|------|------|
| 1 | **단일 파일 비대화** | `main.jsx` 한 파일에 API 클라이언트, 유틸, 상수, 8개 화면, 30여 개 컴포넌트, 진입점이 전부 존재. 탐색·리뷰·병합 충돌 비용이 큼 |
| 2 | **API 호출과 UI 결합** | `apiFetch('/api/...')` 문자열이 App 컴포넌트와 `SpotSearch`, `AdminScreen` 내부에 흩어져 있어 엔드포인트 변경 시 화면 코드를 수정해야 함 |
| 3 | **중복 코드** | 북마크 토글(span+role+키보드 핸들러) 3곳 중복, localStorage `try/catch` 로더 4곳 중복, 슬롯 편집 헬퍼 2곳 중복 |
| 4 | **죽은 코드** | 홈 화면에 130줄짜리 주석 처리된 구 검색 카드(+그 블록만 쓰는 헬퍼 3개), 어디서도 안 쓰는 `districtOf()`, 미사용 `SeoulMap3D` |
| 5 | **불필요한 렌더링** | 홈 히어로 축소 상태(`heroCollapse`)가 HomeScreen 최상위 state라 **스크롤 픽셀마다 홈 화면 전체**(캐러셀 4개 포함)가 리렌더 |
| 6 | **과도한 props drilling** | HomeScreen이 27개 props를 받지만 실제 사용은 14개 — 나머지는 죽은 주석 블록용 |
| 7 | **하드코딩 산재** | 스토리지 키 6종, 토스트 시간, 슬롯 개수 한계(2~4), 시작 시각 등이 사용처에 매몰 |
| 8 | **린트/포맷 규칙 부재** | 코드에 `eslint-disable` 주석은 있으나 ESLint/Prettier 설정 파일이 없어 규칙이 실행된 적 없음 |
| 9 | **의존성 분류 오류** | `vite`, `@vitejs/plugin-react`가 `dependencies`에 위치 |

---

## 2. 새 폴더 구조

레이어드 아키텍처(진입점 → 화면 → 컴포넌트 → 서비스/유틸 → API)로 재편성했다.

```
src/
├── main.jsx                 # 진입점 (4줄: createRoot만)
├── App.jsx                  # 루트 — 화면 전환·상태 오케스트레이션
├── api/
│   ├── client.js            # apiFetch (base URL·에러 규약)
│   └── endpoints.js         # 22개 백엔드 엔드포인트 함수
├── constants/index.js       # 테마·혼잡레벨·자치구·AI 옵션·네비 매핑 등
├── utils/
│   ├── datetime.js          # 서울 시간대 날짜·슬롯 계산 (순수 함수)
│   ├── image.js             # 플레이스홀더 SVG·이미지 URL 정규화
│   └── mappers.js           # API 응답 → 화면 데이터 변환 (순수 함수)
├── services/
│   ├── storage.js           # localStorage 보관함 (STORAGE_KEYS + readJson/writeJson)
│   └── wikiImage.js         # 위키백과 이미지 폴백 (캐시·중복요청 합치기)
├── components/              # 재사용 컴포넌트
│   ├── common.jsx           # Button·Card·Tag·SectionHeader·StarRating·BookmarkToggle 등
│   ├── crowd.jsx            # CrowdBadge·CrowdLegend·TimeCard·WeekdayHeat·CalendarHeat
│   ├── cards.jsx            # SpotCard·PopularCourseCard·RegionSpotCard·AiCourseCard 등
│   ├── layout.jsx           # Header·BottomNavigation·ConnectionBanner
│   ├── SmartImage.jsx / SpotSearch.jsx / HeroScene.jsx
│   ├── CourseFinder.jsx / ReasonModal.jsx
│   ├── PointsMap.jsx        # (이동, git mv로 히스토리 보존)
│   └── SeoulMap3D.jsx       # (이동 — 미사용, 3-6 참고)
└── screens/                 # 화면 단위 (탭·딥링크 1:1)
    ├── HomeScreen.jsx / RegionScreen.jsx / AiCourseScreen.jsx
    ├── DetailScreen.jsx / AlternativesScreen.jsx / CourseScreen.jsx
    ├── MyPageScreen.jsx / AdminScreen.jsx
```

3,538줄 1개 파일 → **29개 모듈(최대 809줄 App.jsx)**. 화면은 화면 폴더에, 2곳 이상에서 쓰는 것만 `components/`에 두는 규칙이다.

---

## 3. 단계별 변경 내역 (변경 전 → 변경 후 + 이유)

리팩토링은 아래 8단계로 진행했고, 각 단계마다 빌드가 깨지지 않는 상태를 유지했다.

### 3-1. API 계층 분리 (`api/client.js`, `api/endpoints.js`)

- **전**: `apiFetch(\`/api/spots/${spotId}/congestion?...\`)` 같은 호출이 App·SpotSearch·AdminScreen에 직접 작성.
- **후**: URL·쿼리스트링 조립을 전부 `endpoints.js`의 이름 있는 함수(`fetchSpotCongestion`, `swapCourseSpot`, …)로 이동. 화면 코드는 "무엇을 가져오는가"만 표현.
- **이유(원칙 9)**: API 명세 변경 시 수정 지점이 한 파일로 수렴하고, 화면 코드가 테스트·모킹 가능해진다. 상세 화면이 쓰는 5개 API 병렬 호출(`fetchSpotContext`)도 여기로 모았다.

### 3-2. 상수·유틸·서비스 분리

- **전**: 테마 목록·혼잡 5단계·서울 25개 구·AI 폼 옵션·스토리지 키가 main.jsx 곳곳에 정의.
- **후**: 표시용 상수는 `constants/`, 순수 계산은 `utils/`(datetime·image·mappers), 부수효과 있는 것은 `services/`(storage·wikiImage)로 분리.
- **이유(원칙 3·10)**: localStorage 접근이 `readJson/writeJson` 2개 함수로 통일되어 4곳의 중복 `try/catch`가 사라졌고, 키 오타를 `STORAGE_KEYS` 상수가 방지한다. 날짜·매핑 함수는 순수 함수라 이후 단위 테스트를 붙이기 쉽다.

### 3-3. 공용 컴포넌트 분리 + DRY

- **전**: 북마크 토글 마크업(role="button"+tabIndex+Enter/Space 핸들러+stopPropagation)이 `SpotCard`·`PopularCourseCard`·`AiCourseCard`에 3벌 복제.
- **후**: `BookmarkToggle` 하나로 통합(클래스명·크기·라벨은 props). 홈 '내 코스' 카드도 인라인 JSX에서 `MyCourseCard` 컴포넌트로 승격.
- **이유(원칙 3·5)**: 접근성 수정(예: 키 핸들링)이 한 곳에서 끝난다. 각 컴포넌트가 "카드 열기"와 "저장 토글"이라는 책임을 명확히 나눈다.

### 3-4. 화면 분리 + 죽은 코드 제거

- **전**: 8개 화면이 main.jsx 안에 연속 정의. HomeScreen에는 1417~1546줄의 **주석 처리된 구 검색 카드**와 그 블록에서만 쓰던 `updateSlot/removeSlot/addSlot`·`busy`·`isFree`가 남아 있었음.
- **후**: 화면당 1파일. 죽은 주석 블록(동일 UI가 이미 `CourseFinder`로 대체됨)·미사용 `districtOf()` 제거. HomeScreen props 27개 → **14개**로 축소(제거분은 전부 죽은 블록 전용이었음).
- **이유(원칙 4·5·8)**: 죽은 코드는 "이게 아직 쓰이나?"라는 탐색 비용을 만든다. 실코드는 전혀 삭제하지 않았고, 삭제분은 주석·미사용 심볼뿐이라 동작 변화가 없다.

### 3-5. App 정리 + 진입점 축소

- **전**: `main.jsx`가 App 정의와 `createRoot`를 겸함. `findAlternatives`가 이를 참조하는 effect보다 **뒤에 선언**되어 있었음(런타임엔 문제없지만 linter가 잡는 코드 냄새).
- **후**: `main.jsx`는 4줄 진입점, `App.jsx`는 상태 오케스트레이션 전담. 선언 순서 정리, `maxVisitDate`는 `useMemo`로 1회 계산(기존엔 렌더마다 4번 `dateAfter` 호출), 토스트 시간은 `TOAST_DURATION_MS` 상수화.
- **이유(원칙 5·8·10)**: App은 여전히 전역 상태의 소유자지만, "데이터를 어떻게 가져오고 어디에 저장하는지"는 하위 계층으로 위임되어 809줄로 줄었다.

### 3-6. React 성능 최적화 (필요한 곳만)

- **전**: `heroCollapse` state가 HomeScreen 소속 → 스크롤 이벤트마다 홈 화면 전체(카드 캐러셀 4개, 이미지 수십 장) 리렌더.
- **후**: 히어로 패널을 `HomeHero` 하위 컴포넌트로 분리해 **스크롤 리렌더 범위를 히어로 하나로 격리**. `withMockDistance`(map+sort)는 `useMemo(…, [homeSpots])`로 캐시.
- **이유(원칙 6)**: `React.memo`/`useCallback`을 전면 살포하는 대신, 실측 가능한 병목(스크롤당 전체 리렌더)만 구조적으로 해결했다. 카드들은 inline 핸들러를 받아 memo 효과가 없으므로 의도적으로 적용하지 않았다(과최적화 방지).
- 참고: 미사용이던 `SeoulMap3D`(three.js)는 import가 없어 번들에 포함되지 않음을 확인하고, 기능 보존 원칙에 따라 삭제 대신 `components/`로 이동만 했다. 삭제 여부는 후속 과제(§5)로 남긴다.

### 3-7. ESLint + Prettier 도입

- **추가**: `eslint.config.js`(flat config: `@eslint/js` + `eslint-plugin-react` + `react-hooks` v7 + `eslint-config-prettier`), `.prettierrc.json`, `.prettierignore`, npm 스크립트 `lint`/`format`/`format:check`.
- **결과**: `npm run lint` **0 error / 0 warning**, 전 소스 Prettier 포맷 적용.
- 린트가 잡아낸 실제 문제 2건 수정: `findAlternatives` 선언 전 참조(3-5), `CourseFinder`의 미사용 `apiReady` prop.
- 예외 1건: react-hooks v7의 신규 규칙 `set-state-in-effect`는 기존 데이터 페칭 패턴(effect 안 setState) 전반을 지적하는데, 이를 고치면 로딩 흐름이 실제로 바뀌므로 **동작 보존 원칙에 따라 off** 하고 후속 과제(§5, react-query 도입)로 연결했다.
- **정리**: `vite`·`@vitejs/plugin-react`를 `devDependencies`로 이동(빌드 도구는 런타임 의존성이 아님).

### 3-8. 접근성·코드 스타일

- 북마크 토글의 키보드 조작(Enter/Space)·`aria-pressed`·`aria-label`이 공용 컴포넌트로 통합되어 3개 카드에서 일관 동작.
- 기존에 잘 되어 있던 부분(role="table" 히트맵, aria-label 붙은 아이콘 버튼, `aria-hidden` 장식 요소)은 그대로 유지.
- 파일 상단 주석으로 각 모듈의 책임과 데이터 계약(백엔드 명세 참조)을 문서화.

### 타입 안전성에 대하여 (원칙 7)

이 프로젝트는 TypeScript가 아닌 **순수 JSX**라서 "any 제거" 대상 자체가 없다. 이번 리팩토링에서는 (a) API 응답 → 화면 데이터 변환을 `utils/mappers.js`의 명시적 매퍼로 모으고, (b) 매직 문자열을 상수로 승격해 타입 실수 여지를 줄이는 것까지 수행했다. **TS 전환은 동작 불변을 보장하기 어려운 큰 변경이라 최우선 후속 과제(§5-1)로 분리**했다 — 지금의 모듈 경계(api/utils/services)가 그대로 `.ts` 전환 단위가 된다.

---

## 4. 동작 검증 (기능 무변경 확인)

| 검증 항목 | 결과 |
|-----------|------|
| `npm run build` | ✅ 성공 (리팩토링 전과 동일하게 통과) |
| CSS 번들 | ✅ **해시까지 동일** (`index-9NU-cd8w.css` 61.59kB) — 스타일 무변경 증명 |
| JS 번들 | ✅ 308.48kB → 307.80kB (죽은 코드 제거분만 감소, gzip 93.68→94.88kB*) |
| `npm run lint` | ✅ 0 error / 0 warning |
| 홈 화면 (Docker 백엔드 연결) | ✅ 히어로·검색·내 주변 캐러셀·통계 렌더 |
| 검색 탭 (`#tab/region`) | ✅ 자치구/카테고리 필터·883곳 목록·혼잡 배지 |
| AI 코스 탭 (`#tab/course-ai`) | ✅ 코스 길이·이동 방식·테마 폼 전체 렌더 |
| 마이페이지 (`#tab/mypage`) | ✅ 프로필·저장 목록·메뉴 |
| 관광지 상세 (`#spot/162` 명동) | ✅ 5개 API 병렬 로드·소개·널널도 카드·시간 이동 칩 |
| 코스 상세 (`#course/13`) | ✅ 타임라인 도착 시각(14:00→15:10→16:19)·SVG 동선 지도·요약 지표 |

\* gzip이 1.2kB 는 것은 모듈 분리로 코드 배치가 바뀐 압축률 차이이며 원본 크기는 감소.

검증 방법: 로컬 Docker 백엔드(`nullnull-backend`)에 vite dev 서버를 프록시로 연결하고, 헤드리스 크롬(500×900)으로 딥링크 6개 화면을 캡처해 확인.

---

## 5. 후속 개선 제안 (우선순위순)

| 순위 | 제안 | 근거·기대 효과 |
|------|------|----------------|
| **P1** | **TypeScript 전환** | 이번에 만든 모듈 경계(api → utils → components → screens) 순서로 점진 전환 가능. API 응답 타입을 `api/`에 정의하면 백엔드 명세와 계약이 코드로 고정됨 |
| **P1** | **서버 상태 라이브러리(TanStack Query) 도입** | App.jsx의 20여 개 서버 상태 useState·수동 로딩 플래그·effect 페칭을 선언적 쿼리로 대체. 캐싱·재시도·중복 요청 제거를 공짜로 얻고, off 해둔 `react-hooks/set-state-in-effect` 규칙을 다시 켤 수 있음 |
| **P2** | **라우터 도입(react-router 등)** | 현재 `screen` state + 해시 정규식 3종의 수제 라우팅을 표준화. 뒤로가기 미지원 문제도 함께 해결됨 |
| **P2** | **App.jsx 추가 분해** | 보관함 로직(저장 관광지·코스·여행 중 코스)을 `useSavedSpots`/`useSavedCourses` 커스텀 훅으로, 토스트를 `useToast` + Context로 추출하면 App이 ~400줄까지 감소 |
| **P3** | **미사용 SeoulMap3D·three.js 제거 결정** | 재사용 계획이 없다면 파일 삭제 + `three` 의존성 제거로 `npm install` 시간·lockfile 축소 (번들 크기는 이미 무영향) |
| **P3** | **CrowdBadge 방어 코드** | `level`이 1~5 밖이면 현재 throw — 데이터 이상 시 화면 전체가 죽지 않게 폴백 필요(동작 변경이라 이번에 미적용) |
| **P3** | **테스트 추가** | `utils/`(datetime·mappers)는 순수 함수라 Vitest 단위 테스트를 바로 붙일 수 있음. 스크린샷 검증을 Playwright 시각 회귀 테스트로 자동화 |
| **P4** | **토스트 큐잉** | 연속 토스트 시 이전 타이머가 새 토스트를 조기 종료시키는 기존 동작이 있음(이번엔 보존). 타이머 clear 또는 큐로 개선 |
| **P4** | **이미지 최적화** | `public/assets`의 PNG를 WebP로 변환, 히어로 GIF(수 MB)를 `<video>` 루프로 대체하면 초기 로드 대폭 개선 |

---

## 부록: 변경 파일 요약

- **이동(git mv)**: `src/PointsMap.jsx`, `src/SeoulMap3D.jsx` → `src/components/`
- **신규**: `src/App.jsx`, `src/api/*`, `src/constants/*`, `src/utils/*`, `src/services/*`, `src/components/*`(9개), `src/screens/*`(8개), `eslint.config.js`, `.prettierrc.json`, `.prettierignore`
- **수정**: `src/main.jsx`(3,538줄 → 4줄), `package.json`(dev 의존성 정리, lint/format 스크립트)
- **불변**: `src/styles.css`, `index.html`, `vite.config.js`, `vercel.json`, `public/*` — UI에 닿는 자원은 손대지 않음
