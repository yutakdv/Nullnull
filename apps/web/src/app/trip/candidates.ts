import type { components } from '@nullnull/api-client';

// Candidate panel rules for S07-8 `412:1912`
// (FR-CAN-05, FR-CAN-07, FR-ITM-02, FE-303).
//
// Pure so the eligibility and ordering rules can be tested without a render.
//
// Two invariants shape everything here:
//
//   - A candidate is not a scheduled item (invariant 1). Saving one never
//     touches the schedule; only an explicit add does, and that is a separate
//     request the user asks for.
//   - The five match states are not a spectrum with a fallback. NONE, CHECKING
//     and UNKNOWN each mean something different, and UNKNOWN in particular
//     means "not enough evidence" — it must not be shown as "no slots"
//     (FIGMA_HANDOFF candidate relation table).

type TripCandidate = components['schemas']['TripCandidate'];
type CandidateMatchResult = components['schemas']['CandidateMatchResult'];
type CandidateSlot = CandidateMatchResult['slots'][number];
type MatchState = CandidateMatchResult['state'];

/** States in which the server has actually decided the slots are usable. */
const DECIDED: MatchState[] = ['EXACT', 'SIMILAR'];

/**
 * True when the match result is a decision rather than an absence of one.
 *
 * CHECKING and UNKNOWN both have empty slot lists, and so does NONE. Treating
 * them the same would tell the user "no dates work" when the server has not
 * finished looking, or has no basis to say.
 */
export function hasDecision(match: CandidateMatchResult): boolean {
  return DECIDED.includes(match.state);
}

/** The slots a user may actually pick, in date order. */
export function eligibleSlots(match: CandidateMatchResult): CandidateSlot[] {
  if (!hasDecision(match)) return [];
  return match.slots
    .filter((slot) => slot.eligible)
    .sort((a, b) => a.date.localeCompare(b.date));
}

/**
 * Slots the server returned but marked ineligible, with their reason.
 *
 * Shown rather than hidden: a date missing from the list with no explanation
 * reads as a bug, while "이 날은 …" reads as an answer.
 */
export function blockedSlots(match: CandidateMatchResult): CandidateSlot[] {
  if (!hasDecision(match)) return [];
  return match.slots
    .filter((slot) => !slot.eligible)
    .sort((a, b) => a.date.localeCompare(b.date));
}

/** A candidate already placed on the schedule cannot be scheduled again. */
export function isScheduled(candidate: TripCandidate): boolean {
  return candidate.status === 'SCHEDULED';
}

/**
 * Candidates this panel lists.
 *
 * DISMISSED ones are excluded: the contract keeps them so a place is not
 * re-suggested, not so the user sees them again.
 */
export function visibleCandidates(candidates: readonly TripCandidate[]): TripCandidate[] {
  return candidates.filter((candidate) => candidate.status !== 'DISMISSED');
}

/**
 * The position a new item takes on its date.
 *
 * Appended to the end of that day, which is what "일정에 추가" means without a
 * drag: `position` is 0-based, so the next free index is the current count.
 * Reordering afterwards is FR-ITM-05 (FE-305).
 */
export function nextPosition(
  days: components['schemas']['TripDetail']['days'],
  date: string,
): number {
  return days.find((day) => day.date === date)?.items.length ?? 0;
}
