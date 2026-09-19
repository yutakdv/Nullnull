import { EMPTY_DRAFT, type WizardDraft } from './wizard.js';

// Recovering the wizard across a reload or a browser Back (FR-TRC-12).
//
// The steps are component state rather than routes, so leaving /start threw the
// draft away: TripWizardScreen's own `goBack` comment records the repro —
// picking 9/15-9/18, continuing, then pressing Back landed on the previous page,
// and returning to /start showed step 1 with no dates. A judge who reloads is
// back at the beginning.
//
// sessionStorage, not localStorage, and that is FIGMA_HANDOFF:165's wording:
// "step별 입력은 sessionStorage/local state에 복구 가능하게 저장한다. 붙여넣기
// 원문은 persistence 대상에서 제외한다." A half-finished trip belongs to the tab
// it was started in — localStorage would surface someone else's abandoned draft
// on the next visit, on a device the product treats as shared (P0 has no login).
//
// WHAT IS NOT STORED, and why the type system does not have to be trusted for
// it: the pasted itinerary text. It never enters WizardDraft at all — it lives
// in ImportPasteScreen's own `raw` state, on a different route, and that file
// says so at its top. So this module cannot leak it by accident. The canary
// test asserts that anyway, across every Storage the page has, because
// "the shape does not contain it today" is a fact about today.

const KEY = 'nullnull.wizard.v1';

/** What survives a reload: the step the user reached, and the draft itself. */
export interface WizardSnapshot {
  step: number;
  draft: WizardDraft;
}

/** Highest step the wizard defines; a stored value past it cannot be rendered. */
const MAX_STEP = 6;

/**
 * Reads a snapshot back, or null when there is nothing usable.
 *
 * Every failure is the same answer — start fresh — because a draft is a
 * convenience, not a record: blocked storage (private windows and "block
 * cookies" both throw on access), absent key, invalid JSON, or a shape written
 * by an older build. Returning EMPTY_DRAFT's worth of nothing is always safe,
 * and never throwing means a corrupt value cannot make /start unreachable.
 */
export function readSnapshot(): WizardSnapshot | null {
  let stored: string | null;
  try {
    stored = sessionStorage.getItem(KEY);
  } catch {
    // Blocked storage: the wizard still works, it just does not recover.
    return null;
  }
  if (!stored) return null;

  let parsed: unknown;
  try {
    parsed = JSON.parse(stored);
  } catch {
    return null;
  }
  if (typeof parsed !== 'object' || parsed === null) return null;

  const { step, draft } = parsed as Partial<WizardSnapshot>;
  if (typeof step !== 'number' || !Number.isInteger(step)) return null;
  if (step < 1 || step > MAX_STEP) return null;
  if (typeof draft !== 'object' || draft === null) return null;

  // Field-by-field rather than a cast: this value crossed a reload, and an
  // older build's shape would otherwise reach the screen as a valid draft and
  // fail somewhere further in, where the cause is no longer visible.
  const d = draft as Partial<WizardDraft>;
  const dates = [d.startDate, d.endDate].every(
    (v) => v === null || typeof v === 'string',
  );
  if (!dates) return null;
  if (!Array.isArray(d.interests) || !d.interests.every((i) => typeof i === 'string')) {
    return null;
  }
  if (!Array.isArray(d.stops) || !Array.isArray(d.mustVisit)) return null;
  if (d.planningLevel !== null && typeof d.planningLevel !== 'string') return null;

  return {
    step,
    draft: {
      ...EMPTY_DRAFT,
      startDate: d.startDate ?? null,
      endDate: d.endDate ?? null,
      interests: d.interests,
      planningLevel: d.planningLevel ?? null,
      stops: d.stops as WizardDraft['stops'],
      mustVisit: d.mustVisit as WizardDraft['mustVisit'],
    },
  };
}

/** Writes the snapshot, silently doing nothing when storage is unavailable. */
export function writeSnapshot(snapshot: WizardSnapshot): void {
  try {
    sessionStorage.setItem(KEY, JSON.stringify(snapshot));
  } catch {
    // Blocked storage, or a quota a draft this size should never reach. The
    // wizard keeps working from memory; only recovery is lost.
  }
}

/**
 * Drops the snapshot once the trip exists.
 *
 * Without this, creating a trip and starting another one would reopen the
 * finished draft, and the second trip would inherit the first one's dates.
 */
export function clearSnapshot(): void {
  try {
    sessionStorage.removeItem(KEY);
  } catch {
    // Nothing to do: if it cannot be removed it was never written either.
  }
}
