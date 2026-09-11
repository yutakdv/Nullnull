// FE-302 edit buffer rules (FR-TRP-02, FR-TRP-03, FR-TRP-05).
//
// FE-302-T1 lives largely here: "no user edit is lost" depends on isDirty being
// exact and on the patch carrying only what changed.
import { describe, expect, it } from 'vitest';
import { tripFixtures } from '@nullnull/contracts';
import {
  MAX_TITLE_LENGTH,
  draftError,
  draftFrom,
  fieldToInput,
  isDirty,
  toPatch,
} from '../trip-edit.js';

const trip = tripFixtures.detailScheduled;

describe('draftFrom takes the editable fields only', () => {
  it('copies what updateTrip can patch', () => {
    expect(draftFrom(trip)).toEqual({
      title: trip.title,
      startDate: trip.startDate,
      endDate: trip.endDate,
      planningLevel: trip.planningLevel,
    });
  });

  it('is clean against the trip it came from', () => {
    expect(isDirty(draftFrom(trip), trip)).toBe(false);
  });
});

describe('isDirty is exact in both directions', () => {
  it('sees a changed title', () => {
    expect(isDirty({ ...draftFrom(trip), title: '다른 이름' }, trip)).toBe(true);
  });

  it('sees a changed date and planning level', () => {
    expect(isDirty({ ...draftFrom(trip), endDate: '2026-10-08' }, trip)).toBe(true);
    expect(isDirty({ ...draftFrom(trip), planningLevel: 'NOTHING' }, trip)).toBe(true);
  });

  it('does not call an untouched draft dirty', () => {
    // A false positive nags on every cancel and trains the user to dismiss the
    // prompt, which is how a real unsaved edit gets discarded.
    expect(isDirty({ ...draftFrom(trip) }, trip)).toBe(false);
  });
});

describe('draftError enforces the contract limits', () => {
  it('rejects an empty or whitespace title', () => {
    expect(draftError({ ...draftFrom(trip), title: '' })).toBe('title-empty');
    expect(draftError({ ...draftFrom(trip), title: '   ' })).toBe('title-empty');
  });

  it('rejects a title past the contract maximum', () => {
    const long = 'a'.repeat(MAX_TITLE_LENGTH + 1);
    expect(draftError({ ...draftFrom(trip), title: long })).toBe('title-too-long');
    expect(
      draftError({ ...draftFrom(trip), title: 'a'.repeat(MAX_TITLE_LENGTH) }),
    ).toBeNull();
  });

  it('rejects an end before the start', () => {
    expect(
      draftError({ ...draftFrom(trip), startDate: '2026-10-07', endDate: '2026-10-04' }),
    ).toBe('range-reversed');
  });

  it('rejects a range past thirty days', () => {
    expect(
      draftError({ ...draftFrom(trip), startDate: '2026-10-01', endDate: '2026-11-05' }),
    ).toBe('range-too-long');
    // Exactly 30 days is allowed.
    expect(
      draftError({ ...draftFrom(trip), startDate: '2026-10-01', endDate: '2026-10-30' }),
    ).toBeNull();
  });

  it('does not try to predict the server’s shrink rejection', () => {
    // The contract refuses a shrink only while an item or lock lies outside the
    // new range — server state this client cannot see. Guessing would either
    // block a legal edit or promise one the server refuses, so the shrink is
    // allowed through and the 422 is surfaced instead.
    const shrunk = { ...draftFrom(trip), endDate: '2026-10-04' };
    expect(draftError(shrunk)).toBeNull();
  });
});

describe('toPatch sends only what changed', () => {
  it('carries one field when one field changed', () => {
    expect(toPatch({ ...draftFrom(trip), title: '새 이름' }, trip)).toEqual({
      title: '새 이름',
    });
  });

  it('is null when nothing changed, because an empty patch is a 400', () => {
    // UpdateTripRequest is minProperties:1.
    expect(toPatch(draftFrom(trip), trip)).toBeNull();
  });

  it('does not resend fields the user never touched', () => {
    // Resending everything would overwrite a concurrent edit to a field this
    // user did not change, which is the whole reason this is a PATCH.
    const patch = toPatch({ ...draftFrom(trip), endDate: '2026-10-08' }, trip);
    expect(patch).toEqual({ endDate: '2026-10-08' });
    expect(patch).not.toHaveProperty('title');
    expect(patch).not.toHaveProperty('planningLevel');
  });

  it('trims the title it sends', () => {
    expect(toPatch({ ...draftFrom(trip), title: '  새 이름  ' }, trip)).toEqual({
      title: '새 이름',
    });
  });

  it('treats a title that differs only by surrounding space as unchanged', () => {
    expect(toPatch({ ...draftFrom(trip), title: `  ${trip.title}  ` }, trip)).toBeNull();
  });
});

describe('fieldToInput maps server field paths to inputs', () => {
  it('maps the plain names', () => {
    expect(fieldToInput('title')).toBe('title');
    expect(fieldToInput('startDate')).toBe('startDate');
  });

  it('maps a dotted or slashed server path to its leaf', () => {
    expect(fieldToInput('trip.startDate')).toBe('startDate');
    expect(fieldToInput('/endDate')).toBe('endDate');
  });

  it('returns null for a field this form does not render', () => {
    // Still shown to the user, just not attached to the wrong input.
    expect(fieldToInput('days[0].items[2].startTime')).toBeNull();
    expect(fieldToInput('')).toBeNull();
  });
});
