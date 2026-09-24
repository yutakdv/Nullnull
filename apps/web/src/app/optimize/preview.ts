import type { components } from '@nullnull/api-client';

// Derivations for the optimization preview and its decision bar (S12, FR-OPT-*).
//
// Pure so each rule can be tested without a render, the same reason
// `trip/trip-view.ts` and `trip/replace.ts` are separate modules. The rules
// here are invariants, not formatting: a comparison that must not be drawn and
// a KEEP that must not read as an APPLY are both wrong in ways a screenshot
// does not show.

type OptimizationProposal = components['schemas']['OptimizationProposal'];
type OptimizationChange = components['schemas']['OptimizationChange'];
type OptimizationStatus = components['schemas']['OptimizationStatus'];
type DataProvenance = components['schemas']['DataProvenance'];
type TripItemState = components['schemas']['TripItemState'];
type TripDetail = components['schemas']['TripDetail'];
type PlaceSummary = components['schemas']['PlaceSummary'];

/**
 * Whether this proposal's crowd delta may be drawn, and the credit that must
 * go beside it.
 *
 * Invariant 8 — "provenance 와 pair/context 비교 자격 없이 crowd·대안을 수치
 * 비교하지 않는다" — is held by the SHAPE here, not by a condition at the call
 * site. `shown` carries its own `provenance`, so a caller cannot take the
 * number without also holding the attribution it has to print. A boolean plus a
 * loose `delta` would let someone render the figure and forget the credit, and
 * the next person to touch that JSX would not see anything missing.
 *
 * `blocked` deliberately has no `delta` field at all rather than a null one:
 * there is no value to reach for, so there is nothing to accidentally render.
 */
export type CrowdComparison =
  | { kind: 'shown'; delta: number; provenance: DataProvenance }
  | { kind: 'blocked'; reasonCode: string | null };

/**
 * The comparison this proposal is allowed to show.
 *
 * Two layers have to agree, which is what `trip/replace.ts:56-66` already does
 * for the replace sheet: the proposal's own `metrics.comparisonEligible`, and
 * every provenance record behind it. Checking only the metrics layer would take
 * the server's summary on trust while the records it summarises say otherwise.
 *
 * Order matters — the first rule that rejects wins, and the reason code comes
 * from whichever layer rejected. A later rule never overwrites an earlier
 * reason, because the earlier one is the one the user is actually blocked by.
 */
export function crowdComparison(proposal: OptimizationProposal): CrowdComparison {
  const metrics = proposal.metrics;

  // 1. The server's own verdict on the pair.
  if (metrics.comparisonEligible !== true) {
    return { kind: 'blocked', reasonCode: metrics.comparisonReasonCode ?? null };
  }

  // 2. A missing delta is missing, not zero. Filling it with 0 would draw
  //    "no change" for a comparison that was never measured, which is the
  //    "결측값을 0/보통으로 채우지 않는다" rule in CLAUDE.md's contract section.
  const delta = metrics.crowdDelta;
  if (delta === null || delta === undefined) {
    return { kind: 'blocked', reasonCode: metrics.comparisonReasonCode ?? null };
  }

  // 3. Something has to be credited. The schema says `minItems: 1` and every
  //    record carries `attribution`, but that is the schema's promise and this
  //    is the client's observation — a provider drift or a hand-built fixture
  //    can still arrive empty, and drawing an uncredited number is the failure
  //    invariant 8 exists to stop.
  const credited = proposal.dataProvenance.filter(
    (record) => record.attribution !== null && record.attribution !== '',
  );
  // Destructured rather than checked by length and indexed later. Both spellings
  // reject the same input, and having both meant neither was load-bearing:
  // deleting the length check left `credited[0]` undefined, the guard further
  // down returned the same blocked result, and the mutation measured red=0. One
  // check, in one place, so removing it actually breaks something.
  const [provenance] = credited;
  if (!provenance) return { kind: 'blocked', reasonCode: null };

  // 4. Every record must permit the comparison, not just the summary above.
  const ineligible = credited.find((record) => record.comparisonEligible !== true);
  if (ineligible) {
    return { kind: 'blocked', reasonCode: ineligible.comparisonReasonCode ?? null };
  }

  // 5. Two numbers from different axes are not a comparison — `replace.ts`
  //    says the same thing about a pair. TEMPORAL against SPATIAL would be a
  //    subtraction with no meaning, so it is blocked rather than shown with a
  //    caveat. No reason code: the server did not reject this one, we did, and
  //    inventing a code here would put a string in the UI that no contract
  //    defines.
  const axes = new Set(credited.map((record) => record.comparisonAxis));
  if (axes.size > 1) return { kind: 'blocked', reasonCode: null };

  return { kind: 'shown', delta, provenance };
}

/**
 * Whether the trip has moved since this run read it.
 *
 * ADVISORY ONLY. A `false` here does not make an APPLY safe: the request still
 * carries If-Match and the server can still answer 409 `TRIP_CHANGED`. The
 * guard is the ETag; this only keeps the UI from offering a decision it can
 * already tell is out of date. Neither one is evidence for the other, and a
 * screen that skipped If-Match because this said `false` would be relying on a
 * version number it read some time ago.
 *
 * `undefined` means the trip has not been read yet, which is not staleness — it
 * is ignorance. Folding the two together puts "recalculate" on screen while the
 * trip query is still in flight, which is a wrong answer that then corrects
 * itself, and the user has already read it.
 *
 * `!==` rather than `>`: a current version BELOW the run's input is not "fresh
 * enough", it is a state nobody predicted (a restore, a replayed fixture, a
 * bug). Different is different, and the honest response to an unexplained
 * difference is the same as to a newer one.
 */
export function isStale(
  runInputVersion: number,
  tripVersion: number | undefined,
): boolean {
  if (tripVersion === undefined) return false;
  return tripVersion !== runInputVersion;
}

export type DecisionState = 'preview' | 'applying' | 'applied' | 'stale' | 'failed';
export type DecisionPhase = { kind: 'none' } | { kind: 'bar'; state: DecisionState };

/**
 * Which decision bar, if any, this run should show.
 *
 * Two names collide here and they are not the same thing:
 *
 *   - `status: 'FAILED'`  — the RUN failed. There is no proposal to decide on,
 *                           so there is no bar at all.
 *   - `state: 'failed'`   — an APPLY request failed. The run is still READY and
 *                           the user can press the button again.
 *
 * Mapping the first onto the second would offer a retry for something that
 * cannot be retried from this screen.
 *
 * `KEPT` is `none`, not `applied`. The bar's `applied` state says the itinerary
 * was updated, and a KEEP updates nothing — the contract is explicit that it
 * "records intent but does not increment trip version". Showing `applied` there
 * would state the opposite of invariant 4 in the one place the user looks to
 * find out what happened. `statusMessage()` in OptimizationRunScreen already
 * says '현재 일정을 유지했어요' for that case, which is the true sentence.
 *
 * A `switch` over the status rather than a lookup table: the contract has eight
 * values today, and a ninth should fail to compile here rather than fall
 * through to whatever the `default` happened to be.
 */
/**
 * Refusals that mean "recompute", not "try again" (#279 중1).
 *
 * Both are the preview having gone out of date: DATA_CHANGED is the inputs
 * moving under it, PREVIEW_EXPIRED is the 15-minute window lapsing. Neither is
 * retryable and the contract says so, so the bar sends the user to a fresh
 * calculation instead of back to the same request.
 */
const STALE_REFUSALS = new Set(['DATA_CHANGED', 'PREVIEW_EXPIRED']);

export function decisionPhase(input: {
  status: OptimizationStatus;
  hasProposals: boolean;
  stale: boolean;
  pending: boolean;
  /**
   * The code the decision was refused with, or null when it was not refused.
   *
   * A code rather than a boolean, and that is #279 중1. The caller used to pass
   * `errored: decide.isError`, so every refusal became `failed` — whose copy
   * names a network problem and whose button repeats the request. A 409
   * DATA_CHANGED is neither: nothing is wrong with the network and pressing
   * again returns 409 forever. BE walked into that dead end rehearsing the
   * judging flow with a lapsed preview.
   */
  refusedWith: string | null;
}): DecisionPhase {
  switch (input.status) {
    case 'QUEUED':
    case 'RUNNING':
      return { kind: 'none' };
    case 'READY': {
      // Nothing to decide on. A bar with no proposal behind it would offer to
      // apply an empty change set.
      if (!input.hasProposals) return { kind: 'none' };
      // Order is the point. A stale run that is also mid-request must read as
      // stale: applying it is the thing we are trying to stop, so that state
      // outranks the spinner. A refusal comes last of the three because a
      // failure against a stale run is still, first, a stale run.
      if (input.stale) return { kind: 'bar', state: 'stale' };
      if (input.pending) return { kind: 'bar', state: 'applying' };
      if (input.refusedWith !== null) {
        // Two refusals say the same thing to the user — what you are looking at
        // is out of date — and `stale` already carries that sentence and the
        // 다시 계산 button the matrix asks for. Routing them there rather than
        // growing a fourth state keeps one copy of one message.
        //
        // The other refusals keep `failed`, and that distinction is the fix
        // rather than a detail of it: APPLY_FAILED is `retryable: true` in the
        // contract, so it must keep the retry it has. Sending every refusal to
        // `stale` would fix the dead end by taking recovery away from the one
        // case that can genuinely be retried.
        return STALE_REFUSALS.has(input.refusedWith)
          ? { kind: 'bar', state: 'stale' }
          : { kind: 'bar', state: 'failed' };
      }
      return { kind: 'bar', state: 'preview' };
    }
    case 'APPLIED':
      return { kind: 'bar', state: 'applied' };
    case 'KEPT':
    case 'REVERTED':
    case 'FAILED':
    case 'EXPIRED':
      return { kind: 'none' };
  }
}

/**
 * One row per change, with the sides that row actually has.
 *
 * The contract splits five `operation` values across three schemas, and the
 * split is about which sides exist: MOVE/REORDER/REPLACE carry both `before`
 * and `after`, ADD has no before, REMOVE has no after. Those are the three
 * shapes a row can draw, so they are the three shapes here — an `add` row
 * cannot reach for a `before` that the contract fixes at `null`.
 */
export type ChangeRow =
  | { kind: 'move'; itemId: string; before: TripItemState; after: TripItemState }
  | { kind: 'add'; itemId: string; after: TripItemState }
  | { kind: 'remove'; itemId: string; before: TripItemState };

/** Which chip a `move` row is labelled with. */
export type MoveKind = 'moveDate' | 'moveTime' | 'moveOrder';

/**
 * What a move row actually moved, so the chip can say so (#279 하3).
 *
 * All three of MOVE, REORDER and REPLACE become one `move` row because they
 * share a SHAPE — both sides present — but the chip was reading that shape as a
 * meaning and calling every one of them "시간 변경". A rehearsal moved a stop to
 * another DAY and the card said the time had changed.
 *
 * Decided from the two sides rather than from `operation`: the server may send
 * MOVE for a change of day, of time, or of both, so the operation name cannot
 * answer this. `date` is required on TripItemState and `startTime` is not, so
 * the date comparison is total and the time one has to treat absent and null
 * alike — "no time set" on both sides is not a time change.
 *
 * Date wins when both moved: the day is the bigger fact, and the row already
 * prints both sides in full underneath, so the chip is a summary rather than
 * the whole account.
 */
export function moveKind(before: TripItemState, after: TripItemState): MoveKind {
  if (before.date !== after.date) return 'moveDate';
  if ((before.startTime ?? null) !== (after.startTime ?? null)) return 'moveTime';
  // Same day, same clock time: what is left is where it sits in that day.
  return 'moveOrder';
}

/**
 * The rows to draw for a proposal's changes.
 *
 * An unknown `operation` is SKIPPED, not thrown on. A server that adds a sixth
 * value would otherwise take the whole screen down, and `failureMessage()` in
 * OptimizationRunScreen made the same choice for the same reason.
 *
 * TRADE-OFF, UNRESOLVED: skipping means the user can press APPLY while one
 * change is not on screen. That is a real cost — the preview stops being a
 * complete account of what APPLY will do — and it is not obviously better than
 * failing loudly. The right answer is probably a visible "이 제안에는 표시할 수
 * 없는 변경이 있어요" state that blocks the decision, but no such copy or state
 * exists in Figma or the contract, so inventing one here would put an
 * unreviewed sentence in front of the user. Left for the owner to decide.
 */
export function changeRows(changes: OptimizationChange[]): ChangeRow[] {
  const rows: ChangeRow[] = [];
  for (const change of changes) {
    switch (change.operation) {
      case 'MOVE':
      case 'REORDER':
      case 'REPLACE':
        rows.push({
          kind: 'move',
          itemId: change.itemId,
          before: change.before,
          after: change.after,
        });
        break;
      case 'ADD':
        rows.push({ kind: 'add', itemId: change.itemId, after: change.after });
        break;
      case 'REMOVE':
        rows.push({ kind: 'remove', itemId: change.itemId, before: change.before });
        break;
      default:
        // See the trade-off above. Not a silent drop in spirit: the caller
        // renders fewer rows than the proposal has changes, and that difference
        // is what a future guard would key on.
        break;
    }
  }
  return rows;
}

/** The places one proposal names, and how many of them the trip cannot supply. */
export interface ProposalPlaces {
  /** Named places the trip holds, each once, in the order the changes name them. */
  places: PlaceSummary[];
  /** Named places it does not hold. Never dropped: the card says so (CMP-ATT-001). */
  missing: number;
}

/**
 * The places a proposal's changes name, taken from the trip the screen holds.
 *
 * The server's `summary` sentence names a place ("인사동 방문을 …"), so the card
 * owes that place's credit. The proposal carries only `placeId`s; the places
 * themselves — and their credits — come from the trip, which the run screen
 * already reads. That covers every change produced today: ItemProposalMapper
 * emits MOVE only, and a move's place is in the trip by definition.
 *
 * A place the trip does not hold is COUNTED, not skipped: an ADD or REPLACE
 * target (the contract allows both), a stop removed since the run started, or
 * a trip that could not be read at all (`null`). Skipping it would draw a card
 * that names a place and credits nothing for it, and nobody would see the gap.
 */
export function proposalPlaces(
  proposal: OptimizationProposal,
  trip: TripDetail | null,
): ProposalPlaces {
  const held = new Map<string, PlaceSummary>();
  for (const day of trip?.days ?? []) {
    for (const item of day.items) held.set(item.place.id, item.place);
  }

  const named = new Set<string>();
  for (const change of proposal.changes) {
    for (const side of [change.before, change.after]) {
      if (side) named.add(side.placeId);
    }
  }

  const places: PlaceSummary[] = [];
  let missing = 0;
  for (const placeId of named) {
    const place = held.get(placeId);
    if (place) places.push(place);
    else missing += 1;
  }
  return { places, missing };
}
