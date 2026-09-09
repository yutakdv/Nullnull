// Shell copy plus the error copy FE owns. Screen copy arrives with each slice.
//
// Error copy is split deliberately (see apps/web/src/shared/api/problem-message.ts):
// the user-facing *message* comes from the server's Problem.detail, except for
// the six optimization codes whose wording docs/design/FIGMA_HANDOFF.md fixes.
// The *CTA label* is FE's, one per code, from the docs/api/README.md UI table.
import type { SupportedLocale } from './locales.js';

export const messages = {
  'ko-KR': {
    'app.name': '널널',
    'app.notFound.title': '없는 주소예요',
    'app.notFound.back': '처음으로 돌아가기',

    // Render-error boundary. Not an API failure.
    'app.error.title': '화면을 표시하지 못했어요',
    'app.error.body': '잠시 후 다시 시도해주세요.',
    'app.error.retry': '다시 시도',
    'app.error.home': '처음으로 돌아가기',

    // Shown when a request fails without a readable Problem body
    // (network loss, proxy HTML). No invented error code.
    'error.unknown.message': '연결에 문제가 있어요. 잠시 후 다시 시도해주세요.',
    'error.requestId': '오류 번호',

    // The six codes whose Korean wording FIGMA_HANDOFF.md fixes. These
    // override Problem.detail because Figma is the 문구 정본.
    'error.TRIP_CHANGED.message': '다른 곳에서 일정이 바뀌었어요.',
    'error.DATA_CHANGED.message': '최신 혼잡 정보에서 개선 방향이 달라졌어요.',
    'error.LOCK_CONFLICT.message': '고정한 일정과 충돌해 적용할 수 없어요.',
    'error.ROUTE_UNAVAILABLE.message': '경로를 확인하지 못했어요.',
    'error.NO_IMPROVEMENT.message': '확인한 후보에서는 더 나은 변경을 찾지 못했어요.',
    'error.APPLY_FAILED.message': '일정은 바뀌지 않았어요.',

    // CTA label per code, from the docs/api/README.md UI mapping table.
    'error.INVALID_REQUEST.cta': '문의하기',
    'error.UNAUTHORIZED.cta': '다시 시작하기',
    'error.FORBIDDEN.cta': '확인',
    'error.NOT_FOUND.cta': '목록으로 이동',
    'error.VALIDATION_FAILED.cta': '입력 확인하기',
    'error.CSRF_INVALID.cta': '다시 시도',
    'error.CURSOR_INVALID.cta': '처음부터 다시 보기',
    'error.CURSOR_EXPIRED.cta': '처음부터 다시 보기',
    'error.TRIP_CHANGED.cta': '최신 일정으로 다시 계산',
    'error.DATA_CHANGED.cta': '다시 계산',
    'error.LOCK_CONFLICT.cta': '고정 조건 확인',
    'error.ROUTE_UNAVAILABLE.cta': '재시도 또는 현재 일정 유지',
    'error.NO_IMPROVEMENT.cta': '내 여행으로 돌아가기',
    'error.APPLY_FAILED.cta': '다시 시도',
    'error.IDEMPOTENCY_KEY_REUSED.cta': '확인',
    'error.IMPORT_DRAFT_EXPIRED.cta': '다시 붙여넣기',
    'error.IMPORT_DRAFT_CHANGED.cta': '최신 내용 보기',
    'error.PREVIEW_EXPIRED.cta': '새로 최적화하기',
    'error.REVERT_WINDOW_EXPIRED.cta': '확인',
    'error.DELETION_STATUS_EXPIRED.cta': '문의하기',
    'error.SOURCE_UNAVAILABLE.cta': '다시 시도',
    'error.RATE_LIMITED.cta': '잠시 후 다시 시도',
    'error.INTERNAL_ERROR.cta': '다시 시도',

    // A-1 splash (388:257). The tagline is two lines in Figma.
    'splash.tagline1': '내 일정을 읽고,',
    'splash.tagline2': '취향으로 이어지는 여행 SNS',
    'splash.retry': '다시 시도',
    'splash.failed': '시작하지 못했어요. 다시 시도해주세요.',

    // A-2 language (388:277). The heading is bilingual in both locales by
    // design: the screen has to be readable before a language is chosen.
    'language.title.en': 'Choose your language',
    'language.title.ko': '언어를 선택해주세요',
    'language.description': '한국어와 English를 지원해요. 日本語와 中文은 준비 중이에요.',
    'language.next': '다음',
    'language.ko.name': '한국어',
    'language.ko.sub': 'Korean',
    'language.en.name': 'English',
    'language.en.sub': '영어',
    'language.ja.name': '日本語',
    'language.ja.sub': 'Japanese · 준비 중',
    'language.zh.name': '中文',
    'language.zh.sub': 'Chinese · 준비 중',
    'language.selected': '선택됨',

    // A-3 intro (388:321).
    'intro.title1': '한국인이 진짜 가는 곳을',
    'intro.title2': '찾아드려요',
    'intro.body1': '유명한 곳은 그대로, 사이사이를',
    'intro.body2': '덜 붐비는 시간과 장소로 채워드려요.',
    'intro.point1.title': '관심사만 고르면',
    'intro.point1.body': '취향에 맞는 곳이 피드에 흘러요',
    'intro.point2.title': '피드를 넘기다 발견하면',
    'intro.point2.body': '내 여행에 담아둘 수 있어요',
    'intro.point3.title': '지금 붐비는 곳은',
    'intro.point3.body': '덜 붐비는 때를 알려드려요',
    'intro.start': '시작하기',
    'intro.noLogin': '로그인 없이 바로 둘러볼 수 있어요',

    // S14 profile (422:2925).
    'profile.title': '내 정보',
    'profile.guest.name': '게스트',
    'profile.guest.note':
      '로그인 없이 시작했어요 · 여행은 이 기기의 익명 세션에 저장돼요',
    'profile.login': '로그인',
    'profile.comingSoon': '준비 중',
    'profile.trips.title': '내 여행 목록',
    'profile.trips.empty': '아직 만든 여행이 없어요',
    'profile.trips.loading': '여행 목록을 불러오는 중이에요',
    'profile.trips.error': '여행 목록을 불러오지 못했어요',
    'profile.history.title': 'AI 최적화 이력',
    'profile.history.empty': '아직 최적화 이력이 없어요',
    'profile.history.loading': '이력을 불러오는 중이에요',
    'profile.history.error': '이력을 불러오지 못했어요',
    'profile.history.note': '상태만 보여드려요 · 일정 내용은 이력에 남기지 않아요',
    'profile.history.APPLIED': '적용됨',
    'profile.history.KEPT': '유지함',
    'profile.history.REVERTED': '되돌림',
    'profile.history.QUEUED': '대기 중',
    'profile.history.RUNNING': '계산 중',
    'profile.history.READY': '결과 준비됨',
    'profile.history.FAILED': '실패함',
    'profile.history.EXPIRED': '만료됨',
    'profile.interests.title': '여행별 관심사 관리',
    'profile.interests.note': '일정에서 읽은 관심사 · 조회·수정·삭제',
    'profile.dataGuide.title': '혼잡도 데이터 안내',
    'profile.dataGuide.note': '실시간 관측 · 공식 예측 · 장기 참고의 차이',
    'profile.location.title': '위치 권한',
    'profile.location.note': '기기 안에서만 사용 · 서버 전송 안 함',
    'profile.location.off': '허용 안 함',
    'profile.retry': '다시 시도',

    // S15 data guide (423:2967). The six state labels live in StateLabel (C07);
    // Figma pins that wording and forbids changing it, so it is not duplicated.
    'dataGuide.title1': '이 앱의 데이터는',
    'dataGuide.title2': '어떻게 동작하나요?',
    'dataGuide.states.heading': '혼잡도 데이터 상태 6가지',
    'dataGuide.state.LIVE':
      '서울 열린데이터로 지금 상태를 관측한 권역이에요. 기준시각을 함께 표시해요.',
    'dataGuide.state.FORECAST':
      '한국관광공사 예측 범위 안이에요. 확정된 혼잡이 아니라 예측이에요.',
    'dataGuide.state.QUALITATIVE':
      '예측이 닿지 않는 기간이에요. 숫자를 만들지 않고 주말·공휴일만 알려드려요.',
    'dataGuide.state.STALE': '최신 데이터를 못 받아 마지막 값을 쓰고 있어요.',
    'dataGuide.state.UNAVAILABLE':
      '쓸 수 있는 근거가 없어요. 임의로 점수를 만들지 않아요.',
    'dataGuide.state.REPLAY':
      '과거 관측을 다시 보여드리는 데모예요. 지금 실시간이 아니에요.',
    'dataGuide.rules.heading': '일정과 AI는 이렇게 동작해요',
    'dataGuide.rule1.title': '담아둔 장소와 내 일정은 달라요',
    'dataGuide.rule1.body':
      '+로 담으면 후보로만 보관해요. 날짜·시간을 정해 배치해야 내 일정이 돼요.',
    'dataGuide.rule2.title': '잠금은 네 가지가 따로 움직여요',
    'dataGuide.rule2.body':
      'Must Visit(장소 유지) · 날짜 고정 · 시간 고정 · 예약 고정(출처 표시, 해제 시 추가 확인)은 각각 독립이에요.',
    'dataGuide.rule3.title': 'AI는 제안까지만 해요',
    'dataGuide.rule3.body':
      '선호와 이유 설명은 AI가, 운영·혼잡·경로·도착 시간 검증은 출처 있는 데이터와 서버 규칙이 해요.',
    'dataGuide.rule4.title': '승인 전에는 일정을 바꾸지 않아요',
    'dataGuide.rule4.body':
      '최적화 변경안은 미리보기일 뿐이에요. 적용을 누르기 전까지 일정은 그대로예요. 적용 후에도 되돌릴 수 있어요.',
    'dataGuide.rule5.title': '모든 혼잡 표시에는 출처가 있어요',
    'dataGuide.rule5.body':
      'Live·예측·REPLAY와 경로 데이터 모두 출처·기준시각을 함께 보여드려요. 근거 없는 숫자를 만들지 않아요.',
    'dataGuide.attribution': '출처: ⓒ한국관광공사 · 서울 열린데이터광장',

    // S02-4B must-visit places (438:3158).
    'mustVisit.step': 'STEP 4',
    'mustVisit.title1': '꼭 가고 싶은 곳을',
    'mustVisit.title2': '알려주세요',
    'mustVisit.body1': '이 장소는 그대로 지켜드리고,',
    'mustVisit.body2': '나머지 시간은 취향에 맞춰 채워드릴게요.',
    'mustVisit.search': '장소 검색',
    'mustVisit.searchLabel': '장소 이름으로 검색',
    'mustVisit.picked': '담은 곳',
    'mustVisit.pickedCount': '곳',
    'mustVisit.pickedEmpty': '아직 담은 곳이 없어요',
    'mustVisit.results': '검색 결과',
    'mustVisit.searching': '찾고 있어요',
    'mustVisit.noResults': '검색 결과가 없어요',
    'mustVisit.searchError': '검색하지 못했어요',
    'mustVisit.add': '담기',
    'mustVisit.remove': '빼기',
    'mustVisit.next': '이대로 채우기',
    'mustVisit.skip': '건너뛰기',
  },
  'en-US': {
    'app.name': 'Nullnull',
    'app.notFound.title': 'Page not found',
    'app.notFound.back': 'Back to start',

    'app.error.title': 'This screen could not be shown',
    'app.error.body': 'Please try again in a moment.',
    'app.error.retry': 'Try again',
    'app.error.home': 'Back to start',

    'error.unknown.message':
      'There was a connection problem. Please try again in a moment.',
    'error.requestId': 'Error ID',

    'error.TRIP_CHANGED.message': 'This trip changed somewhere else.',
    'error.DATA_CHANGED.message': 'Newer crowd data points a different way.',
    'error.LOCK_CONFLICT.message': 'This conflicts with an item you locked.',
    'error.ROUTE_UNAVAILABLE.message': 'The route could not be confirmed.',
    'error.NO_IMPROVEMENT.message':
      'No better arrangement was found among the options checked.',
    'error.APPLY_FAILED.message': 'Your trip was not changed.',

    'error.INVALID_REQUEST.cta': 'Contact support',
    'error.UNAUTHORIZED.cta': 'Start again',
    'error.FORBIDDEN.cta': 'OK',
    'error.NOT_FOUND.cta': 'Go to list',
    'error.VALIDATION_FAILED.cta': 'Check your input',
    'error.CSRF_INVALID.cta': 'Try again',
    'error.CURSOR_INVALID.cta': 'View from the start',
    'error.CURSOR_EXPIRED.cta': 'View from the start',
    'error.TRIP_CHANGED.cta': 'Recalculate with the latest trip',
    'error.DATA_CHANGED.cta': 'Recalculate',
    'error.LOCK_CONFLICT.cta': 'Check locked items',
    'error.ROUTE_UNAVAILABLE.cta': 'Retry or keep the current trip',
    'error.NO_IMPROVEMENT.cta': 'Back to my trip',
    'error.APPLY_FAILED.cta': 'Try again',
    'error.IDEMPOTENCY_KEY_REUSED.cta': 'OK',
    'error.IMPORT_DRAFT_EXPIRED.cta': 'Paste again',
    'error.IMPORT_DRAFT_CHANGED.cta': 'View the latest',
    'error.PREVIEW_EXPIRED.cta': 'Run a new optimization',
    'error.REVERT_WINDOW_EXPIRED.cta': 'OK',
    'error.DELETION_STATUS_EXPIRED.cta': 'Contact support',
    'error.SOURCE_UNAVAILABLE.cta': 'Try again',
    'error.RATE_LIMITED.cta': 'Try again shortly',
    'error.INTERNAL_ERROR.cta': 'Try again',

    // A-1 splash (388:257). Figma has no EN splash frame; these render only
    // when the browser resolves to en-US before a language is chosen.
    'splash.tagline1': 'Reads your itinerary,',
    'splash.tagline2': 'a travel feed that follows your taste',
    'splash.retry': 'Try again',
    'splash.failed': "We couldn't start. Please try again.",

    // A-2 language (643:4088, the EN-selected variant). The heading stays
    // bilingual in both locales: the screen must be readable before choosing.
    'language.title.en': 'Choose your language',
    'language.title.ko': '언어를 선택해주세요',
    'language.description':
      'Korean and English are supported. Japanese and Chinese are coming soon.',
    'language.next': 'Next',
    'language.ko.name': '한국어',
    'language.ko.sub': 'Korean',
    'language.en.name': 'English',
    'language.en.sub': 'English',
    'language.ja.name': '日本語',
    'language.ja.sub': 'Japanese · Coming soon',
    'language.zh.name': '中文',
    'language.zh.sub': 'Chinese · Coming soon',
    'language.selected': 'Selected',

    // A-3 intro (388:321). Figma has no EN intro frame either.
    'intro.title1': 'We find the places',
    'intro.title2': 'Koreans actually go',
    'intro.body1': 'Keep the landmarks, and fill the gaps',
    'intro.body2': 'with quieter times and places.',
    'intro.point1.title': 'Pick your interests',
    'intro.point1.body': 'and your feed follows your taste',
    'intro.point2.title': 'Spot something in the feed',
    'intro.point2.body': 'and save it to your trip',
    'intro.point3.title': 'When a place is busy',
    'intro.point3.body': "we'll tell you when it is not",
    'intro.start': 'Get started',
    'intro.noLogin': 'Browse right away, no sign-in needed',

    // S14 profile (422:2925). Figma has no EN frame; these are translations.
    'profile.title': 'My info',
    'profile.guest.name': 'Guest',
    'profile.guest.note':
      'Started without signing in · trips are stored in this device\u2019s anonymous session',
    'profile.login': 'Sign in',
    'profile.comingSoon': 'Coming soon',
    'profile.trips.title': 'My trips',
    'profile.trips.empty': 'No trips yet',
    'profile.trips.loading': 'Loading your trips',
    'profile.trips.error': "We couldn't load your trips",
    'profile.history.title': 'AI optimization history',
    'profile.history.empty': 'No optimization history yet',
    'profile.history.loading': 'Loading history',
    'profile.history.error': "We couldn't load the history",
    'profile.history.note': 'Status only · itinerary content is never kept in history',
    'profile.history.APPLIED': 'Applied',
    'profile.history.KEPT': 'Kept',
    'profile.history.REVERTED': 'Reverted',
    'profile.history.QUEUED': 'Queued',
    'profile.history.RUNNING': 'Running',
    'profile.history.READY': 'Ready',
    'profile.history.FAILED': 'Failed',
    'profile.history.EXPIRED': 'Expired',
    'profile.interests.title': 'Interests per trip',
    'profile.interests.note': 'Read from your itinerary · view, edit, delete',
    'profile.dataGuide.title': 'About crowd data',
    'profile.dataGuide.note':
      'Live observation · official forecast · long-term reference',
    'profile.location.title': 'Location permission',
    'profile.location.note': 'Used on device only · never sent to the server',
    'profile.location.off': 'Not allowed',
    'profile.retry': 'Try again',

    // S15 data guide (423:2967). Figma has no EN frame; these are translations.
    // The attribution line keeps the Korean source names, which are the
    // approved wording (CLAUDE.md invariant 12).
    'dataGuide.title1': 'How does this app',
    'dataGuide.title2': 'handle its data?',
    'dataGuide.states.heading': 'The six crowd data states',
    'dataGuide.state.LIVE':
      'An area observed right now via Seoul Open Data. The reference time is shown with it.',
    'dataGuide.state.FORECAST':
      'Inside the Korea Tourism Organization forecast range. A forecast, not a confirmed crowd level.',
    'dataGuide.state.QUALITATIVE':
      'Beyond the forecast range. We invent no number and only flag weekends and holidays.',
    'dataGuide.state.STALE': 'No fresh data arrived, so the last value is being used.',
    'dataGuide.state.UNAVAILABLE': 'No usable basis. We do not invent a score.',
    'dataGuide.state.REPLAY': 'A demo replaying past observations. Not live right now.',
    'dataGuide.rules.heading': 'How trips and AI work',
    'dataGuide.rule1.title': 'Saved places and your itinerary are different',
    'dataGuide.rule1.body':
      'Tapping + keeps a place as a candidate. It joins your itinerary only once you give it a date and time.',
    'dataGuide.rule2.title': 'The four locks work independently',
    'dataGuide.rule2.body':
      'Must Visit (keep the place), date lock, time lock and reservation lock (source shown, extra confirmation to release) are each separate.',
    'dataGuide.rule3.title': 'AI only proposes',
    'dataGuide.rule3.body':
      'AI reads preferences and explains reasons; opening hours, crowding, routes and arrival times are verified by sourced data and server rules.',
    'dataGuide.rule4.title': 'Nothing changes before you approve',
    'dataGuide.rule4.body':
      'An optimization is a preview only. Your itinerary stays as it is until you apply, and you can revert afterwards.',
    'dataGuide.rule5.title': 'Every crowd figure has a source',
    'dataGuide.rule5.body':
      'Live, forecast, REPLAY and route data all show their source and reference time. We do not invent numbers.',
    'dataGuide.attribution': '출처: ⓒ한국관광공사 · 서울 열린데이터광장',

    // S02-4B must-visit places (438:3158). Figma has no EN frame.
    'mustVisit.step': 'STEP 4',
    'mustVisit.title1': 'Which places do you',
    'mustVisit.title2': 'want to keep?',
    'mustVisit.body1': "We'll keep these exactly as they are,",
    'mustVisit.body2': 'and fill the rest around your taste.',
    'mustVisit.search': 'Search places',
    'mustVisit.searchLabel': 'Search by place name',
    'mustVisit.picked': 'Kept places',
    'mustVisit.pickedCount': '',
    'mustVisit.pickedEmpty': 'Nothing kept yet',
    'mustVisit.results': 'Results',
    'mustVisit.searching': 'Searching',
    'mustVisit.noResults': 'No matches',
    'mustVisit.searchError': "We couldn't search",
    'mustVisit.add': 'Keep',
    'mustVisit.remove': 'Remove',
    'mustVisit.next': 'Fill the rest',
    'mustVisit.skip': 'Skip',
  },
} as const satisfies Record<SupportedLocale, Record<string, string>>;

export type MessageKey = keyof (typeof messages)['ko-KR'];
