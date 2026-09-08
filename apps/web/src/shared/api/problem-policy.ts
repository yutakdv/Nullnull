// Client policy for each Problem code, transcribed from the UI mapping table in
// docs/api/README.md (§ "UI mapping", 23 rows).
//
// Two rules this file encodes and must not soften:
//
// 1. The wire `retryable` is the server's advice; this table is the ceiling.
//    A code marked retryable here still never auto-retries a mutation.
// 2. Recovery is named, not boolean. A screen needs to know *which* recovery to
//    offer, and a boolean cannot carry "reset the cursor" vs "recompute".
import type { ProblemCode } from './problem.js';

/**
 * What the client may do without the user asking.
 *
 * - `none` — no automatic retry at all.
 * - `safe-get-once` — retry once, GET only. Never a mutation.
 * - `backoff-once` — one delayed retry (docs/api/README.md: "backoff, 최대 1회").
 * - `retry-after` — wait the `Retry-After` header, then one retry.
 * - `reissue-csrf-once` — refresh the tab token once, then re-confirm with the
 *   user. The mutation itself is never replayed automatically.
 */
export type RetryPolicy =
  | 'none'
  | 'safe-get-once'
  | 'backoff-once'
  | 'retry-after'
  | 'reissue-csrf-once';

/** The recovery a screen offers. Names the action, not a yes/no. */
export type RecoveryKind =
  | 'none'
  | 'retry'
  | 'reset-cursor'
  | 'recompute'
  | 'reload-trip'
  | 'navigate-away'
  | 'field-focus'
  | 'restart-session'
  | 'repaste'
  | 'contact-support';

/** How prominent the failure is. Toast alone never carries a recovery action. */
export type Severity = 'toast' | 'inline' | 'screen';

export interface ProblemPolicy {
  readonly retry: RetryPolicy;
  readonly recovery: RecoveryKind;
  readonly severity: Severity;
  /**
   * True when the user should see the requestId to quote in a support message.
   * docs/api/README.md marks INTERNAL_ERROR as "requestId와 재시도".
   */
  readonly showRequestId: boolean;
}

/**
 * Record<ProblemCode, …> is deliberate: when BE adds a code to the enum, this
 * object stops compiling instead of silently falling back to a default.
 */
export const PROBLEM_POLICY: Record<ProblemCode, ProblemPolicy> = {
  INVALID_REQUEST: {
    retry: 'none',
    recovery: 'contact-support',
    severity: 'inline',
    showRequestId: true,
  },
  UNAUTHORIZED: {
    retry: 'safe-get-once',
    recovery: 'restart-session',
    severity: 'screen',
    showRequestId: false,
  },
  FORBIDDEN: {
    retry: 'none',
    recovery: 'none',
    severity: 'inline',
    showRequestId: false,
  },
  NOT_FOUND: {
    retry: 'none',
    recovery: 'navigate-away',
    severity: 'screen',
    showRequestId: false,
  },
  VALIDATION_FAILED: {
    retry: 'none',
    recovery: 'field-focus',
    severity: 'inline',
    showRequestId: false,
  },
  CSRF_INVALID: {
    retry: 'reissue-csrf-once',
    recovery: 'retry',
    severity: 'inline',
    showRequestId: false,
  },
  CURSOR_INVALID: {
    retry: 'none',
    recovery: 'reset-cursor',
    severity: 'inline',
    showRequestId: false,
  },
  CURSOR_EXPIRED: {
    retry: 'none',
    recovery: 'reset-cursor',
    severity: 'inline',
    showRequestId: false,
  },
  TRIP_CHANGED: {
    retry: 'none',
    recovery: 'reload-trip',
    severity: 'inline',
    showRequestId: false,
  },
  DATA_CHANGED: {
    retry: 'none',
    recovery: 'recompute',
    severity: 'inline',
    showRequestId: false,
  },
  LOCK_CONFLICT: {
    retry: 'none',
    recovery: 'none',
    severity: 'inline',
    showRequestId: false,
  },
  ROUTE_UNAVAILABLE: {
    retry: 'backoff-once',
    recovery: 'retry',
    severity: 'inline',
    showRequestId: false,
  },
  NO_IMPROVEMENT: {
    retry: 'none',
    recovery: 'navigate-away',
    severity: 'inline',
    showRequestId: false,
  },
  APPLY_FAILED: {
    // "같은 key로 사용자 CTA" — the user re-confirms; the client never replays
    // an apply on its own.
    retry: 'none',
    recovery: 'retry',
    severity: 'inline',
    showRequestId: true,
  },
  IDEMPOTENCY_KEY_REUSED: {
    retry: 'none',
    recovery: 'none',
    severity: 'inline',
    showRequestId: true,
  },
  IMPORT_DRAFT_EXPIRED: {
    retry: 'none',
    recovery: 'repaste',
    severity: 'inline',
    showRequestId: false,
  },
  IMPORT_DRAFT_CHANGED: {
    retry: 'none',
    recovery: 'reload-trip',
    severity: 'inline',
    showRequestId: false,
  },
  PREVIEW_EXPIRED: {
    retry: 'none',
    recovery: 'recompute',
    severity: 'inline',
    showRequestId: false,
  },
  REVERT_WINDOW_EXPIRED: {
    retry: 'none',
    recovery: 'none',
    severity: 'inline',
    showRequestId: false,
  },
  DELETION_STATUS_EXPIRED: {
    retry: 'none',
    recovery: 'contact-support',
    severity: 'inline',
    showRequestId: true,
  },
  SOURCE_UNAVAILABLE: {
    // "endpoint별" in the table. The safe default is no automatic retry; a
    // screen that has a documented fallback opts in explicitly.
    retry: 'none',
    recovery: 'retry',
    severity: 'inline',
    showRequestId: false,
  },
  RATE_LIMITED: {
    retry: 'retry-after',
    recovery: 'retry',
    severity: 'toast',
    showRequestId: false,
  },
  INTERNAL_ERROR: {
    retry: 'safe-get-once',
    recovery: 'retry',
    severity: 'screen',
    showRequestId: true,
  },
};

/** Methods that may be retried automatically. Everything else is a mutation. */
const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);

export interface RetryContext {
  /** HTTP method of the request that failed. */
  readonly method: string;
  /** Retries already attempted for this request. 0 on the first failure. */
  readonly attempt: number;
}

/** Policies under which the client may silently repeat the same request. */
const SILENT_RETRY_POLICIES = new Set<RetryPolicy>([
  'safe-get-once',
  'backoff-once',
  'retry-after',
]);

/**
 * Whether the client may repeat this request on its own.
 *
 * Mutations always return false: docs/api/README.md marks CSRF_INVALID as
 * "mutation 자동 금지" and non-idempotent replays are invariant 6 territory.
 *
 * `reissue-csrf-once` is deliberately not a silent retry. It means "fetch a
 * fresh tab token, then re-confirm with the user" (docs/api/README.md:
 * "tab token 1회 재발급 후 사용자 action 재확인"). Replaying the request as
 * soon as a token arrives would skip the confirmation the contract requires,
 * so the caller drives that flow instead.
 */
export function shouldRetry(code: ProblemCode, context: RetryContext): boolean {
  if (!SILENT_RETRY_POLICIES.has(PROBLEM_POLICY[code].retry)) return false;
  if (context.attempt >= 1) return false;
  return SAFE_METHODS.has(context.method.toUpperCase());
}

/**
 * Milliseconds to wait before the retry `shouldRetry` allowed.
 *
 * `Retry-After` is in seconds (docs/api/README.md). A malformed or missing
 * header falls back to the backoff delay rather than retrying immediately.
 */
const BACKOFF_MS = 1_000;

export function retryDelayMs(
  code: ProblemCode,
  retryAfterHeader?: string | null,
): number {
  if (PROBLEM_POLICY[code].retry === 'retry-after' && retryAfterHeader) {
    // Number('') and Number(null) are both 0, which would mean "retry now"
    // against a rate limit. Only a non-empty numeric header counts.
    const seconds = Number(retryAfterHeader.trim());
    if (Number.isFinite(seconds) && seconds >= 0) return seconds * 1_000;
  }
  return BACKOFF_MS;
}
