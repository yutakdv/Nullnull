export type TripPeriodStyle = 'short' | 'long';

function calendarDate(value: string): Date {
  const [year, month, day] = value.split('-').map(Number);
  return new Date(year ?? 0, (month ?? 1) - 1, day ?? 1);
}

/** Formats contract calendar dates without shifting them through UTC. */
export function formatTripPeriod(
  startDate: string,
  endDate: string,
  locale: string,
  style: TripPeriodStyle = 'short',
): string {
  const format = new Intl.DateTimeFormat(locale, {
    month: style === 'long' ? 'long' : 'numeric',
    day: 'numeric',
  });
  const start = format.format(calendarDate(startDate));
  if (startDate === endDate) return start;
  return `${start} – ${format.format(calendarDate(endDate))}`;
}
