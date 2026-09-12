import type { components } from '@nullnull/api-client';

// Derivations for S07-1 `410:1738` (FR-TRP-01, FE-301).
//
// Pure so the counts can be tested without a render. FE-301's acceptance is
// that the screen "does not produce a wrong total", and a total computed inline
// in JSX is one nobody can test directly.

type TripDetail = components['schemas']['TripDetail'];
type TripDay = TripDetail['days'][number];

/** Milliseconds in a day. Dates in the contract are plain `date`, not instants. */
const DAY_MS = 86_400_000;

/**
 * Parses a contract `date` as UTC midnight.
 *
 * `new Date('2026-10-04')` is already UTC, but `new Date(2026, 9, 4)` is local;
 * mixing the two is how a day count comes out one short across a timezone. Both
 * ends of every subtraction here go through this function.
 */
function parseDate(iso: string): number {
  return Date.parse(`${iso}T00:00:00Z`);
}

/** Nights and days as the header says them: 3박 4일. */
export function tripLength(
  startDate: string,
  endDate: string,
): {
  nights: number;
  days: number;
} {
  const span = Math.round((parseDate(endDate) - parseDate(startDate)) / DAY_MS);
  // A same-day trip is 0박 1일, and an end before the start is not a negative
  // trip — it is bad data, which is clamped rather than displayed as one.
  const nights = Math.max(0, span);
  return { nights, days: nights + 1 };
}

/**
 * Days until the trip starts, or null once it has begun.
 *
 * `today` is passed in rather than read from the clock so the value is testable
 * and so the caller decides which timezone "today" means — the trip's, not the
 * device's, since a trip in Asia/Seoul does not become D-0 because the user's
 * phone is in another zone.
 */
export function daysUntil(startDate: string, today: string): number | null {
  const diff = Math.round((parseDate(startDate) - parseDate(today)) / DAY_MS);
  return diff > 0 ? diff : null;
}

/** Today as a plain date in the given IANA timezone. */
export function todayIn(timezone: string, now: Date = new Date()): string {
  // `en-CA` renders as YYYY-MM-DD, which is the contract's `date` format.
  return new Intl.DateTimeFormat('en-CA', { timeZone: timezone }).format(now);
}

/**
 * How many scheduled items the trip holds.
 *
 * Summed from the days rather than taken from any single field: the contract
 * has no item total, so a number typed into the header would be a second,
 * unverified source of truth.
 */
export function itemCount(days: readonly TripDay[]): number {
  return days.reduce((total, day) => total + day.items.length, 0);
}

/** True when the trip has days but nothing scheduled in any of them. */
export function isEmptySchedule(days: readonly TripDay[]): boolean {
  return itemCount(days) === 0;
}

/**
 * Items in the order the screen shows them.
 *
 * Sorted by `position`, which the contract defines as the ordering field.
 * Relying on array order instead would make the screen agree with the server
 * only by luck, and disagree silently the first time a response came back in a
 * different order.
 */
export function orderedItems(day: TripDay): TripDay['items'] {
  return [...day.items].sort((a, b) => a.position - b.position);
}

/** The day a chip selects, or every day for the `전체` chip. */
export function visibleDays(
  days: readonly TripDay[],
  selected: string | null,
): readonly TripDay[] {
  if (selected === null) return days;
  return days.filter((day) => day.date === selected);
}

/**
 * A contract `time` as the row shows it: "09:30".
 *
 * The contract's format is a wall clock with seconds and no offset
 * ("09:30:00") since #145 removed the UTC offset requirement. Rendering it raw
 * puts the seconds on screen, which is what happens if this is treated as
 * display-ready text.
 *
 * Returns null for anything unparseable rather than a guess: a malformed time
 * shown as "00:00" is a wrong schedule, while an absent one is visibly absent.
 */
export function formatTime(
  value: string | null | undefined,
  locale: string,
): string | null {
  if (!value) return null;
  const match = /^(\d{2}):(\d{2})/.exec(value);
  if (!match) return null;
  const [, hh, mm] = match;
  // Formatted through Intl on a fixed date so 09:30 reads as "오전 9:30" or
  // "9:30 AM" per locale, rather than being concatenated by hand.
  const at = new Date(`2000-01-01T${hh ?? '00'}:${mm ?? '00'}:00Z`);
  return new Intl.DateTimeFormat(locale, {
    hour: 'numeric',
    minute: '2-digit',
    timeZone: 'UTC',
  }).format(at);
}

/**
 * A contract `date` (YYYY-MM-DD) as short, locale-aware text.
 *
 * Parsed as UTC and formatted in UTC: a plain date has no timezone, and
 * letting the runtime apply the local one shifts "2026-10-04" to the 3rd for
 * anyone west of Greenwich.
 */
export function formatDate(date: string, locale: string): string {
  const at = new Date(`${date}T00:00:00Z`);
  if (Number.isNaN(at.getTime())) return date;
  return new Intl.DateTimeFormat(locale, {
    month: 'numeric',
    day: 'numeric',
    timeZone: 'UTC',
  }).format(at);
}
