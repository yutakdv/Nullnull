// Turns a Problem into the text a screen shows.
//
// Ownership, which this file exists to keep straight:
//
// - The message is the server's. `Problem.detail` is a required field and
//   docs/api/README.md §9 requires backend to put user-safe text there. FE does
//   not invent wording for codes it has no copy for.
// - Six optimization codes are the exception: docs/design/FIGMA_HANDOFF.md is
//   the 문구 정본 for those, so its copy overrides `detail`.
// - The CTA label is FE's, one per code, from the README UI mapping table.
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
 * Falls back to `detail` whenever Figma has not fixed the wording, so a code
 * with no FE copy still shows the server's sentence rather than a blank or an
 * invented one.
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
