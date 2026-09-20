import { describe, expect, it } from 'vitest';
import { formatTripPeriod } from '../trip-period.js';

describe('formatTripPeriod', () => {
  it('formats compact Korean and English ranges with one separator', () => {
    expect(formatTripPeriod('2026-10-04', '2026-10-07', 'ko-KR', 'short')).toBe(
      '10. 4. – 10. 7.',
    );
    expect(formatTripPeriod('2026-10-04', '2026-10-07', 'en-US', 'short')).toBe(
      '10/4 – 10/7',
    );
  });

  it('uses locale month names for the prominent style', () => {
    expect(formatTripPeriod('2026-10-04', '2026-10-07', 'ko-KR', 'long')).toBe(
      '10월 4일 – 10월 7일',
    );
    expect(formatTripPeriod('2026-10-04', '2026-10-07', 'en-US', 'long')).toBe(
      'October 4 – October 7',
    );
  });

  it('does not repeat a one-day trip', () => {
    expect(formatTripPeriod('2026-10-04', '2026-10-04', 'en-US', 'short')).toBe('10/4');
  });
});
