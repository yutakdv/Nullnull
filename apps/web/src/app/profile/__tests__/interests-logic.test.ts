// FE-106 interest editing rules, without a render or a server.
//
// These are the rules the conflict recovery leans on, so they are worth
// pinning independently of the component that uses them.
import { describe, expect, it } from 'vitest';
import type { components } from '@nullnull/api-client';
import {
  CATALOGUE_CODES,
  MAX_INTERESTS,
  isDirty,
  toReplaceBody,
  toSelection,
  toggle,
  unknownCodes,
} from '../interests.js';

type TripInterest = components['schemas']['TripInterest'];

const saved: TripInterest[] = [
  { code: 'FRIENDS', weight: 3 },
  { code: 'FOOD', weight: 5 },
];

describe('toggle respects the contract cap', () => {
  it('adds and removes', () => {
    expect(toggle(['FOOD'], 'NATURE')).toEqual(['FOOD', 'NATURE']);
    expect(toggle(['FOOD', 'NATURE'], 'FOOD')).toEqual(['NATURE']);
  });

  it('refuses a 21st interest but still allows removal', () => {
    const full = Array.from({ length: MAX_INTERESTS }, (_, i) => `C${String(i)}`);
    expect(toggle(full, 'EXTRA')).toHaveLength(MAX_INTERESTS);
    expect(toggle(full, 'C0')).toHaveLength(MAX_INTERESTS - 1);
  });
});

describe('isDirty compares sets, not arrays', () => {
  it('is clean when the same codes arrive in another order', () => {
    expect(isDirty(['FOOD', 'FRIENDS'], saved)).toBe(false);
  });

  it('is dirty on a real change', () => {
    expect(isDirty(['FOOD'], saved)).toBe(true);
    expect(isDirty(['FOOD', 'FRIENDS', 'NATURE'], saved)).toBe(true);
  });

  it('treats clearing everything as a change', () => {
    expect(isDirty([], saved)).toBe(true);
  });
});

describe('toReplaceBody keeps weights the trip already had', () => {
  it('preserves an existing weight rather than flattening it to the default', () => {
    const body = toReplaceBody(['FOOD', 'NATURE'], saved);
    expect(body.interests).toEqual([
      { code: 'FOOD', weight: 5 },
      { code: 'NATURE', weight: 3 },
    ]);
  });

  it('produces an empty array for an empty selection, which is a real value', () => {
    expect(toReplaceBody([], saved).interests).toEqual([]);
  });
});

describe('codes this build does not know are preserved, not dropped', () => {
  it('reports them', () => {
    const withUnknown: TripInterest[] = [...saved, { code: 'FUTURE_CODE', weight: 2 }];
    expect(unknownCodes(withUnknown)).toEqual(['FUTURE_CODE']);
    // Still part of the selection, so a save round-trips it instead of
    // silently deleting a value the server owns.
    expect(toSelection(withUnknown)).toContain('FUTURE_CODE');
    expect(toReplaceBody(toSelection(withUnknown), withUnknown).interests).toContainEqual(
      {
        code: 'FUTURE_CODE',
        weight: 2,
      },
    );
  });

  it('reports nothing for a catalogue-only set', () => {
    expect(unknownCodes(saved)).toEqual([]);
    expect(CATALOGUE_CODES).toContain('FOOD');
  });
});
