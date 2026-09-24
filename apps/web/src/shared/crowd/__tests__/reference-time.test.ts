import { describe, expect, it } from 'vitest';
import { formatReferenceTime } from '../reference-time.js';

describe('formatReferenceTime', () => {
  it('FE-403-T1 reads a UTC instant as the Seoul date and time', () => {
    // 05:00 UTC is 14:00 in Seoul; the raw instant said "05:00" to every reader.
    expect(formatReferenceTime('2026-09-20T05:00:00Z', 'en-US')).toBe('9/20, 2:00 PM');
    expect(formatReferenceTime('2026-09-20T05:00:00Z', 'ko-KR')).toMatch(
      /^9\. 20\. 오후 2:00$/,
    );
  });

  it('keeps the Seoul calendar day across the UTC midnight', () => {
    // 20:30 UTC on the 19th is already the 20th in Seoul.
    expect(formatReferenceTime('2026-09-19T20:30:00Z', 'en-US')).toBe('9/20, 5:30 AM');
  });
});
