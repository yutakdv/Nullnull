import type { components } from '@nullnull/api-client';

export type CrowdMetric = components['schemas']['CrowdMetric'];
export type CrowdSeries = components['schemas']['CrowdSeries'];

const KST_OFFSET = '+09:00';

/** The inclusive API window for calendar dates in Korea. */
export function crowdWindow(startDate: string, endDate: string) {
  const from = new Date(`${startDate}T00:00:00${KST_OFFSET}`).toISOString();
  const nextDay = new Date(`${endDate}T00:00:00${KST_OFFSET}`);
  nextDay.setUTCDate(nextDay.getUTCDate() + 1);
  const lastSecond = new Date(nextDay.getTime() - 1000).toISOString();
  return {
    from,
    // The contract is inclusive at PostgreSQL microsecond precision. JS only
    // stores milliseconds, so spell the final 999 microseconds explicitly.
    to: lastSecond.replace('.000Z', '.999999Z'),
  };
}

/** A target instant's calendar date in Korea, independent of device timezone. */
export function crowdTargetDate(point: CrowdMetric): string | null {
  const target = point.provenance.targetAt;
  if (!target) return null;
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(new Date(target));
  const value = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((part) => part.type === type)?.value;
  const year = value('year');
  const month = value('month');
  const day = value('day');
  return year && month && day ? `${year}-${month}-${day}` : null;
}

/** The exact server point for one KST day; no interpolation or averaging. */
export function crowdPointForDate(
  series: CrowdSeries | null | undefined,
  date: string,
): CrowdMetric | null {
  return series?.points.find((point) => crowdTargetDate(point) === date) ?? null;
}

/**
 * The product-approved card representative: the first maximum returned point.
 * Selecting a point preserves its provenance; calculating an average would not.
 */
export function busiestCrowdPoint(
  series: CrowdSeries | null | undefined,
): CrowdMetric | null {
  let selected: CrowdMetric | null = null;
  for (const point of series?.points ?? []) {
    if (typeof point.value !== 'number' || !Number.isFinite(point.value)) continue;
    if (selected === null || point.value > (selected.value ?? Number.NEGATIVE_INFINITY)) {
      selected = point;
    }
  }
  return selected;
}
