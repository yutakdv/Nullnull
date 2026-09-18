import type { components } from '@nullnull/api-client';

// Derivations for the applied/undo panel on the trip screen (S09-3, FE-504).
//
// Pure so each rule can be tested without a render, the same reason
// `trip-view.ts` and `optimize/preview.ts` are separate modules.
//
// The rule that matters here is which numbers the panel shows, and it is not
// the same pair in every state. A revert does not rewind the trip to the
// version before the apply — the contract writes the recorded changes back as
// a NEW revision ("history is never overwritten"), and Figma's reverted frame
// says `v8 → v9 · v7과 같은 일정이에요` because of it. Reading `from`/`to` off
// the APPLY decision in every state would print `v7 → v8` on a panel whose own
// third clause says the itinerary is now v9.

type OptimizationRun = components['schemas']['OptimizationRun'];
type OptimizationHistoryItem =
  components['schemas']['OptimizationHistoryPage']['items'][number];

// `decisions` is a discriminated union on `decision`, and the variants differ
// in more than a label: only APPLY carries `resultingTripVersion` and
// `revertUntil`, because only an APPLY moved the trip. Narrowing by the
// discriminator rather than by testing for a property is what keeps that
// difference visible — a KEEP has no version to print, and the type says so.
type ApplyDecision = Extract<OptimizationRun['decisions'][number], { decision: 'APPLY' }>;
type RevertDecision = Extract<
  OptimizationRun['decisions'][number],
  { decision: 'REVERT' }
>;

/**
 * The decision list, which is all these rules read from a run.
 *
 * Taken as its own parameter rather than as the whole `OptimizationRun` for
 * the reason `preview.ts` takes a proposal: the rules here are about the
 * decisions, and a function that asked for the entire run would make its
 * callers prove they had one.
 */
type Decisions = OptimizationRun['decisions'];

/** The APPLY entry, or null when the run was kept or never decided. */
export function appliedDecision(decisions: Decisions): ApplyDecision | null {
  return decisions.find((entry) => entry.decision === 'APPLY') ?? null;
}

/** The REVERT entry, present only once an undo has succeeded. */
export function revertDecision(decisions: Decisions): RevertDecision | null {
  return decisions.find((entry) => entry.decision === 'REVERT') ?? null;
}

export interface PanelVersions {
  /** Left of the arrow. */
  from: number;
  /** Right of the arrow — the version the trip is on now. */
  to: number;
  /**
   * The version whose itinerary the revert restored.
   *
   * Only meaningful once reverted, where Figma renders "v{restored}과 같은
   * 일정이에요". It is the run's own input version: the revert put back what
   * was there before the apply.
   */
  restored: number;
}

/**
 * The two (or three) numbers the panel prints, for whichever state it is in.
 *
 * Returns null when there is no APPLY to describe — a kept or undecided run
 * has no panel at all.
 */
export function panelVersions(
  decisions: Decisions,
  inputTripVersion: number,
): PanelVersions | null {
  const apply = appliedDecision(decisions);
  if (!apply) return null;

  const revert = revertDecision(decisions);
  if (revert) {
    // v8 → v9: the revert's own revision, not a rewind to v7.
    return {
      from: apply.resultingTripVersion,
      to: revert.resultingTripVersion,
      restored: inputTripVersion,
    };
  }

  // v7 → v8: what the apply did.
  return {
    from: inputTripVersion,
    to: apply.resultingTripVersion,
    restored: inputTripVersion,
  };
}

/** Milliseconds in the contract's revert window. */
const REVERT_WINDOW_MS = 24 * 60 * 60 * 1000;

/**
 * Whether the trip screen should spend a second request reading this run.
 *
 * THE 24H CHECK HERE DECIDES WHETHER TO SEND THE SECOND REQUEST, NEVER WHETHER
 * TO ENABLE THE BUTTON. The contract is explicit that absence of
 * `revertAvailability` is "never permission to enable undo using client time"
 * — so the panel renders from the server's value alone, and this clock only
 * avoids a request that would come back NOT_APPLICABLE anyway.
 *
 * Concretely: widening this function cannot turn an undo on, because nothing
 * downstream reads it. Narrowing it wrongly only costs a panel that should
 * have appeared. That asymmetry is the point — the expensive direction is
 * unreachable from here.
 *
 * `now` is a parameter rather than a `Date.now()` call so the boundary is
 * testable without freezing the clock.
 */
export function shouldReadRun(
  latest: OptimizationHistoryItem | undefined,
  now: number,
): boolean {
  if (!latest) return false;
  // Only an APPLY can be undone. A KEEP changed nothing, and a run already
  // reported as REVERT has had its undo spent — the server would answer
  // REVERTED either way, so the request would buy nothing.
  if (latest.decision !== 'APPLY') return false;
  if (!latest.decidedAt) return false;
  const decidedAt = new Date(latest.decidedAt).getTime();
  if (Number.isNaN(decidedAt)) return false;
  return now - decidedAt < REVERT_WINDOW_MS;
}

/**
 * An instant as the panel reads it: "10/7 14:35".
 *
 * A new helper rather than `ProfileScreen`'s `runDate`, which formats the date
 * alone. The two screens want different things from the same kind of value —
 * the history row names a day, this panel names a deadline — and sharing one
 * function would mean the next change to either requirement moves the other
 * screen with it.
 *
 * Returns null for an unparseable value rather than a guess, as `formatTime`
 * does: a wrong deadline on an undo is worse than a visibly missing one.
 */
export function formatInstant(
  iso: string | null | undefined,
  locale: string,
): string | null {
  if (!iso) return null;
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return null;
  return new Intl.DateTimeFormat(locale, {
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(at);
}
