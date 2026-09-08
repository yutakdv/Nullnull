// Turns a Problem into the text a screen shows.
//
// Ownership, per docs/api/README.md §12 (confirmed on issue #16 R2):
//
// - User-facing copy is FE's. `Problem.detail` is written by the server in
//   English regardless of the requested locale, so it is a fallback, not the
//   message a localised screen should show.
// - Six optimization codes have confirmed copy in both locales from
//   docs/design/FIGMA_HANDOFF.md, and those render localised today.
// - The other 17 still fall through to the English `detail`. That is a known
//   gap, not the intended end state: Korean copy for them needs PM and design
//   sign-off because CLAUDE.md requires a Figma state alongside each message,
//   so FE does not invent it here. Tracked as follow-up to FE-003.
// - The CTA label is FE's, one per code, from the README UI mapping table, and
//   is already localised for all 23.
//
// `detail` is returned as a plain string and must be rendered as a text node.
// docs/api/README.md:214 — "FE는 detail을 HTML로 렌더링하지 않는다."
import type { MessageKey } from '../../i18n/messages.js';
import type { Problem, ProblemCode } from './problem.js';
import { PROBLEM_POLICY, type ProblemPolicy } from './problem-policy.js';

/** Codes whose Korean wording FIGMA_HANDOFF.md fixes. */
const FIGMA_COPY_CODES = [
  'TRIP_CHANGED',
  'DATA_CHANGED',
  'LOCK_CONFLICT',
  'ROUTE_UNAVAILABLE',
  'NO_IMPROVEMENT',
  'APPLY_FAILED',
] as const satisfies readonly ProblemCode[];

const HAS_FIGMA_COPY = new Set<string>(FIGMA_COPY_CODES);

export interface ProblemPresentation {
  readonly message: string;
  readonly ctaLabel: string;
  readonly policy: ProblemPolicy;
  /** Present only when the policy says to show it. */
  readonly requestId?: string;
}

type Translate = (key: MessageKey) => string;

/**
 * Resolve what to show for a parsed Problem.
 *
 * Falls back to the server's English `detail` when FE has no confirmed copy
 * for the code, so a screen shows a real sentence rather than a blank or an
 * invented one. Showing English on a Korean screen is the lesser failure, and
 * it is visible, which is why the gap is not hidden behind a generic string.
 */
export function problemPresentation(problem: Problem, t: Translate): ProblemPresentation {
  const policy = PROBLEM_POLICY[problem.code];
  const message = HAS_FIGMA_COPY.has(problem.code)
    ? t(`error.${problem.code}.message` as MessageKey)
    : problem.detail;

  return {
    message,
    ctaLabel: t(`error.${problem.code}.cta` as MessageKey),
    policy,
    ...(policy.showRequestId ? { requestId: problem.requestId } : {}),
  };
}

/**
 * What to show when a request failed but produced no readable Problem.
 *
 * Deliberately has no code: a network failure or a proxy's HTML page is not a
 * contract error, and labelling it with one would put a value on screen the
 * contract never defined.
 */
export function unknownFailurePresentation(t: Translate): ProblemPresentation {
  return {
    message: t('error.unknown.message'),
    ctaLabel: t('app.error.retry'),
    policy: {
      retry: 'none',
      recovery: 'retry',
      severity: 'inline',
      showRequestId: false,
    },
  };
}
