import type { components } from '@nullnull/api-client';

// Optimization history rules for S14 `422:2925` (FE-506).
//
// Status and decision are two different things and the screen must not collapse
// them. `status` is where the run got to (QUEUED … FAILED); `decision` is what
// the user chose when it finished, and it is null until they choose. A run can
// be READY with no decision — the result is waiting — and that is exactly the
// row a user needs to find again.
//
// What this module deliberately does NOT do: reconstruct what the run changed.
// CLAUDE.md forbids duplicating itinerary content for history, and the contract
// carries none — an OptimizationHistoryItem has a trip title and a link, not a
// before/after. The row says status, scope and decision, and the link goes to
// the run.

type HistoryItem = components['schemas']['OptimizationHistoryItem'];
type Status = components['schemas']['OptimizationStatus'];

/** Runs still working. The row offers no decision for these. */
const IN_PROGRESS: Status[] = ['QUEUED', 'RUNNING'];

export function isInProgress(item: HistoryItem): boolean {
  return IN_PROGRESS.includes(item.status);
}

/**
 * True when the run finished and is waiting on the user.
 *
 * READY with no decision is the one actionable row in the list: the result
 * exists and nobody has said yes or no to it yet.
 */
export function awaitsDecision(item: HistoryItem): boolean {
  return item.status === 'READY' && !item.decision;
}

/**
 * What to show as the row's secondary line.
 *
 * Prefers the decision when there is one, because "적용함" tells the user more
 * than "적용됨" — it says they chose it. Falls back to the status otherwise,
 * and never invents a decision for a run that has none.
 */
export function rowState(item: HistoryItem): {
  kind: 'decision' | 'status' | 'pending';
  value: string;
} {
  if (item.decision) return { kind: 'decision', value: item.decision };
  if (awaitsDecision(item)) return { kind: 'pending', value: 'READY' };
  return { kind: 'status', value: item.status };
}

/**
 * Whether the row should link to the run.
 *
 * A queued or running run has no result to open yet, so the row states its
 * status rather than offering a link that lands on nothing.
 */
export function hasResult(item: HistoryItem): boolean {
  return !isInProgress(item);
}

/**
 * Where the row's link points.
 *
 * NOT `item.runLink`, and that is deliberate. The contract's runLink pattern is
 * `/trips/{uuid}/optimizations/{uuid}` (plural) while the only route this app
 * registers is `/trip/:tripId/optimizations/:runId` (singular), so the server's
 * value renders the not-found screen on every click. The singular spelling is
 * also the one the analytics route enum accepts.
 *
 * Built from runId and tripId, which are both required on the item, so this
 * needs no contract change. PM-016 is open on which spelling is canonical; when
 * it is settled this either becomes `item.runLink` or stays as is.
 */
export function runHref(item: HistoryItem): string {
  return `/trip/${item.tripId}/optimizations/${item.runId}`;
}
