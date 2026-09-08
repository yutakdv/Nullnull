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
  },
} as const satisfies Record<SupportedLocale, Record<string, string>>;

export type MessageKey = keyof (typeof messages)['ko-KR'];
