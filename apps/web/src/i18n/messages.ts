// Shell copy only. Screen copy arrives with each slice.
import type { SupportedLocale } from './locales.js';

export const messages = {
  'ko-KR': {
    'app.name': '널널',
    'app.notFound.title': '없는 주소예요',
    'app.notFound.back': '처음으로 돌아가기',
  },
  'en-US': {
    'app.name': 'Nullnull',
    'app.notFound.title': 'Page not found',
    'app.notFound.back': 'Back to start',
  },
} as const satisfies Record<SupportedLocale, Record<string, string>>;

export type MessageKey = keyof (typeof messages)['ko-KR'];
