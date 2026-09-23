/**
 * A provider reference time as the traveller reads it: month, day and time in
 * Seoul, where every crowd source in this service observes. The API carries a
 * UTC instant, and printing that verbatim put "05:00Z" on a reading taken at
 * 14:00 in Seoul. Month and day stay because a REPLAY reading can be days old.
 */
export function formatReferenceTime(instant: string, locale: string): string {
  return new Intl.DateTimeFormat(locale, {
    month: 'numeric',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
    timeZone: 'Asia/Seoul',
  }).format(new Date(instant));
}
