import type { components } from '@nullnull/api-client';

type PlaceSummary = components['schemas']['PlaceSummary'];

export interface PendingPick {
  place: PlaceSummary;
  key: string;
}

/** A created trip is not a draft. Keep its unfinished writes separately. */
export interface WizardAttempt {
  fingerprint: string;
  key: string;
  phase: 'creating' | 'create-failed' | 'saving' | 'failed';
  picks: PendingPick[];
  pending: PendingPick[];
  tripId: string | null;
}

const KEY = 'nullnull.wizard.attempt.v1';
export const ATTEMPT_CHANGED = 'nullnull:wizard-attempt-changed';
let memoryAttempt: WizardAttempt | null = null;
let storageWriteFailed = false;
let creatingKey: string | null = null;
let savingTripId: string | null = null;

export function readAttempt(): WizardAttempt | null {
  if (storageWriteFailed) return memoryAttempt;
  let raw: string | null;
  try {
    raw = sessionStorage.getItem(KEY);
  } catch {
    return memoryAttempt;
  }
  if (!raw) {
    memoryAttempt = null;
    return null;
  }
  try {
    const value: unknown = JSON.parse(raw);
    if (typeof value !== 'object' || value === null) return null;
    const attempt = value as Partial<WizardAttempt>;
    const validPicks = (picks: unknown): picks is PendingPick[] =>
      Array.isArray(picks) &&
      picks.every(
        (pick: unknown) =>
          typeof pick === 'object' &&
          pick !== null &&
          typeof (pick as PendingPick).key === 'string' &&
          typeof (pick as PendingPick).place?.id === 'string' &&
          typeof (pick as PendingPick).place?.name === 'string',
      );
    if (
      typeof attempt.fingerprint !== 'string' ||
      typeof attempt.key !== 'string' ||
      !['creating', 'create-failed', 'saving', 'failed'].includes(attempt.phase ?? '') ||
      !validPicks(attempt.picks) ||
      !validPicks(attempt.pending) ||
      !(attempt.tripId === null || typeof attempt.tripId === 'string')
    ) {
      return null;
    }
    memoryAttempt = attempt as WizardAttempt;
    return memoryAttempt;
  } catch {
    return null;
  }
}

export function writeAttempt(attempt: WizardAttempt | null): void {
  memoryAttempt = attempt;
  try {
    if (attempt) sessionStorage.setItem(KEY, JSON.stringify(attempt));
    else sessionStorage.removeItem(KEY);
    storageWriteFailed = false;
  } catch {
    // Keep the in-memory guard for this document when storage is blocked.
    storageWriteFailed = true;
  }
  window.dispatchEvent(new Event(ATTEMPT_CHANGED));
}

export function claimCreate(key: string): boolean {
  if (creatingKey !== null) return false;
  creatingKey = key;
  return true;
}

export function releaseCreate(key: string): void {
  if (creatingKey === key) creatingKey = null;
}

export function isCreating(key: string): boolean {
  return creatingKey === key;
}

export function claimSave(tripId: string): boolean {
  if (savingTripId !== null) return false;
  savingTripId = tripId;
  return true;
}

export function releaseSave(tripId: string): void {
  if (savingTripId === tripId) savingTripId = null;
}

export function isSaving(tripId: string): boolean {
  return savingTripId === tripId;
}
