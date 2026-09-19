import { describe, expect, it } from 'vitest';
import { crowdFixtures } from '@nullnull/contracts';
import {
  busiestCrowdPoint,
  crowdPointForDate,
  crowdTargetDate,
  crowdWindow,
} from '../forecast.js';

describe('crowd forecast policy', () => {
  it('builds the inclusive KST window required by the contract', () => {
    expect(crowdWindow('2026-10-04', '2026-10-07')).toEqual({
      from: '2026-10-03T15:00:00.000Z',
      to: '2026-10-07T14:59:59.999999Z',
    });
  });

  it('maps target instants to KST dates instead of the device timezone', () => {
    const first = crowdFixtures.seriesForecast.points[0];
    expect(first).toBeDefined();
    expect(crowdTargetDate(first!)).toBe('2026-10-05');
    expect(crowdPointForDate(crowdFixtures.seriesForecast, '2026-10-05')).toBe(first);
  });

  it('selects the first maximum point without changing its provenance', () => {
    const selected = busiestCrowdPoint(crowdFixtures.seriesForecast);
    expect(selected?.value).toBe(72.5);
    expect(selected?.provenance.targetAt).toBe('2026-10-05T15:00:00Z');
    expect(selected).toBe(crowdFixtures.seriesForecast.points[1]);
  });

  it('does not invent a reading when no numeric point exists', () => {
    expect(busiestCrowdPoint(crowdFixtures.seriesUnavailable)).toBeNull();
    expect(
      busiestCrowdPoint({
        ...crowdFixtures.seriesForecast,
        points: crowdFixtures.seriesForecast.points.map((point) => ({
          ...point,
          value: null,
        })),
      }),
    ).toBeNull();
  });
});
