// FE-305 replace rules (FR-ITM-07, FR-ITM-08). Invariants 7 and 8.
//
// This is the screen that puts two places side by side, so invariant 8 is the
// subject rather than a constraint: a number appears only when the server says
// the pair is comparable, and "not comparable" is never rendered as equal.
import { describe, expect, it } from 'vitest';
import type { components } from '@nullnull/api-client';
import { tripFixtures } from '@nullnull/contracts';
import {
  alternatives,
  comparable,
  comparisonBlock,
  hasAlternatives,
  isReplaceBlocked,
  replaceLockEffect,
} from '../replace.js';

type RelatedPlace = components['schemas']['RelatedPlace'];
type RelatedPlaceResult = components['schemas']['RelatedPlaceResult'];
type CrowdMetric = components['schemas']['CrowdMetric'];
type TripItem = components['schemas']['TripDetail']['days'][number]['items'][number];

const days = tripFixtures.detailScheduled.days;

function item(dayIndex: number, itemIndex: number): TripItem {
  const found = days[dayIndex]?.items[itemIndex];
  if (!found) throw new Error('fixture shape changed');
  return found;
}

/** A provenance stub carrying only what these rules read. */
function crowd(opts: {
  value: number;
  eligible: boolean;
  axis?: string;
  reason?: string | null;
}): CrowdMetric {
  return {
    state: 'FORECAST',
    value: opts.value,
    unit: 'relative-index',
    ordinalLevel: null,
    label: '관광지 집중률 예측',
    provenance: {
      comparisonEligible: opts.eligible,
      comparisonAxis: opts.axis ?? 'TEMPORAL',
      comparisonReasonCode: opts.reason ?? null,
    },
  } as unknown as CrowdMetric;
}

function related(overrides: Partial<RelatedPlace> = {}): RelatedPlace {
  return {
    place: {
      id: '018f4b20-1a44-7e11-9c02-5d7e3f1a2b02',
      name: '명동',
      categoryCode: 'STREET',
      regionCode: 'KR-11-140',
    },
    relation: 'SIMILAR',
    relationReason: '같은 카페 분위기',
    crowd: null,
    provenance: {} as RelatedPlace['provenance'],
    ...overrides,
  };
}

function result(
  state: RelatedPlaceResult['state'],
  items: RelatedPlace[] = [],
): RelatedPlaceResult {
  return { sourcePlaceId: item(0, 0).place.id, state, items };
}

describe('the five relation states are not interchangeable', () => {
  it('treats EXACT and SIMILAR with items as a decision', () => {
    expect(hasAlternatives(result('EXACT', [related()]))).toBe(true);
    expect(hasAlternatives(result('SIMILAR', [related()]))).toBe(true);
  });

  it('does not present CHECKING or UNKNOWN as "no alternatives"', () => {
    // Both arrive with an empty items array, so branching on length would
    // answer a question the server has not answered.
    expect(hasAlternatives(result('CHECKING'))).toBe(false);
    expect(hasAlternatives(result('UNKNOWN'))).toBe(false);
    expect(alternatives(result('CHECKING'))).toEqual([]);
  });

  it('offers nothing from a decided state that carries nothing', () => {
    expect(hasAlternatives(result('NONE'))).toBe(false);
  });

  it('keeps the server order rather than ranking by crowd', () => {
    // Sorting by crowd is itself a numeric comparison across places whose
    // provenance may not permit one.
    const busy = related({ crowd: crowd({ value: 90, eligible: true }) });
    const calm = related({
      place: { ...related().place, id: 'other', name: '서울숲' },
      crowd: crowd({ value: 10, eligible: true }),
    });
    expect(
      alternatives(result('SIMILAR', [busy, calm])).map((r) => r.place.name),
    ).toEqual(['명동', '서울숲']);
  });
});

describe('a comparison needs both sides to be eligible', () => {
  const withCrowd = {
    ...item(0, 0),
    crowd: crowd({ value: 80, eligible: true }),
  } as TripItem;

  it('compares when both sides say so on the same axis', () => {
    expect(
      comparable(withCrowd, related({ crowd: crowd({ value: 20, eligible: true }) })),
    ).toBe(true);
  });

  it('refuses when either side is not eligible', () => {
    expect(
      comparable(withCrowd, related({ crowd: crowd({ value: 20, eligible: false }) })),
    ).toBe(false);
    const ineligibleHere = { ...withCrowd, crowd: crowd({ value: 80, eligible: false }) };
    expect(
      comparable(
        ineligibleHere,
        related({ crowd: crowd({ value: 20, eligible: true }) }),
      ),
    ).toBe(false);
  });

  it('refuses across different comparison axes', () => {
    // Two numbers measured differently are not a comparison.
    expect(
      comparable(
        withCrowd,
        related({ crowd: crowd({ value: 20, eligible: true, axis: 'SPATIAL' }) }),
      ),
    ).toBe(false);
  });

  it('treats missing crowd as not comparable, never as equal', () => {
    expect(comparable(withCrowd, related({ crowd: null }))).toBe(false);
    expect(
      comparable(item(0, 0), related({ crowd: crowd({ value: 1, eligible: true }) })),
    ).toBe(false);
  });

  it('always gives a reason when it refuses', () => {
    // Silence would let the user assume the two are equivalent.
    expect(comparisonBlock(withCrowd, related({ crowd: null }))).toBe('no-data');
    expect(
      comparisonBlock(
        withCrowd,
        related({ crowd: crowd({ value: 5, eligible: false, reason: 'STALE_INPUT' }) }),
      ),
    ).toBe('STALE_INPUT');
    expect(
      comparisonBlock(
        withCrowd,
        related({ crowd: crowd({ value: 5, eligible: false, reason: null }) }),
      ),
    ).toBe('not-eligible');
  });

  it('gives no reason when the comparison is allowed', () => {
    expect(
      comparisonBlock(
        withCrowd,
        related({ crowd: crowd({ value: 20, eligible: true }) }),
      ),
    ).toBeNull();
  });
});

describe('replacing a place releases only the lock that pins the place', () => {
  it('releases MUST_VISIT and keeps the schedule locks', () => {
    // 경복궁 carries MUST_VISIT and DATE.
    expect(replaceLockEffect(item(0, 0))).toEqual({
      released: ['MUST_VISIT'],
      kept: ['DATE'],
    });
  });

  it('releases nothing when the place was not pinned', () => {
    // 인사동 carries TIME only, which pins the clock rather than the place.
    expect(replaceLockEffect(item(0, 1))).toEqual({ released: [], kept: ['TIME'] });
  });

  it('reports an item with no locks as releasing and keeping nothing', () => {
    expect(replaceLockEffect(item(1, 0))).toEqual({ released: [], kept: [] });
  });
});

describe('a reservation blocks replacement outright', () => {
  it('refuses an item pinned by a booking', () => {
    const reserved = {
      ...item(1, 0),
      constraints: [
        {
          type: 'RESERVATION' as const,
          locked: true as const,
          source: 'IMPORT' as const,
          date: '2026-10-05',
          startTime: '19:00:00',
          endTime: null,
        },
      ],
    } as TripItem;
    // The booking lives elsewhere; swapping the place here would leave the two
    // disagreeing.
    expect(isReplaceBlocked(reserved)).toBe(true);
    expect(isReplaceBlocked(item(0, 0))).toBe(false);
  });
});
