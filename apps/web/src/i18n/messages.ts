// Message catalogue for the shell only. Screen copy arrives with each
// FE slice; the Korean strings here are the canonical source and the
// English ones are real translations, not placeholders.

import type { SupportedLocale } from './locales.js';

export const messages = {
  'ko-KR': {
    'app.name': '널널',
    'app.loading': '불러오는 중이에요',
    'app.error.title': '문제가 생겼어요',
    'app.error.retry': '다시 시도',
    'app.notFound.title': '없는 주소예요',
    'app.notFound.back': '처음으로 돌아가기',
    'nav.home': '홈',
    'nav.trip': '내 여행',
    'nav.live': '라이브',
    'nav.profile': '내 정보',
  },
  'en-US': {
    'app.name': 'Nullnull',
    'app.loading': 'Loading',
    'app.error.title': 'Something went wrong',
    'app.error.retry': 'Try again',
    'app.notFound.title': 'Page not found',
    'app.notFound.back': 'Back to start',
    'nav.home': 'Home',
    'nav.trip': 'My trip',
    'nav.live': 'Live',
    'nav.profile': 'Profile',
  },
} as const satisfies Record<SupportedLocale, Record<string, string>>;

export type MessageKey = keyof (typeof messages)['ko-KR'];
