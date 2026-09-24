import { expect, test, type Page } from '@playwright/test';

// BA-073-T4 (#53) against the real API: a place the API credits to a provider
// shows that credit on screen, or the release gate fails. FE-603-T4
// (attribution-coverage.test.ts) proves, from the code, that every place a
// screen names has a PlaceAttribution in the same file or a stated exemption;
// this proves the drawn page carries the credit the response sent, verbatim
// (CMP-ATT-003), for every credited place on it.
//
// The integration seed gives exactly one place a KorService2 credit: 명동, with
// no coordinates, so searchPlaces and the draft pool never return it and no
// other spec draws it. This test is the only one that names it, through
// createTrip. The *.integration.spec suffix keeps it off the MSW run, whose
// fixtures credit other places (playwright.config.ts).
//
// No trace or screenshot: the recorded requests would carry the session cookie.
test.use({ trace: 'off', screenshot: 'off', video: 'off' });

const MYEONGDONG = '018f4b20-1a44-7e11-9c02-5d7e3f1a2b02';
const GYEONGBOKGUNG = '018f4b20-1a44-7e11-9c02-5d7e3f1a2b01';

interface TripPlace {
  id: string;
  name: string;
  sourceAttribution: { attribution: string; officialUrl?: string | null } | null;
}

interface TripDetail {
  days: { items: { place: TripPlace }[] }[];
}

/** Creates a trip from inside the page, where the session and its Origin live (seeded-trip.ts). */
async function createTrip(page: Page, placeIds: string[]): Promise<string> {
  await page.goto('/');
  await page.waitForURL(/\/(language|feed)$/, { timeout: 15_000 });
  const start = new Date();
  start.setUTCDate(start.getUTCDate() + 14);
  const startDate = start.toISOString().slice(0, 10);

  const result = await page.evaluate(
    async ({ startDate, placeIds }) => {
      const csrf = await fetch('/api/v1/session/csrf', {
        method: 'POST',
        credentials: 'same-origin',
      });
      if (!csrf.ok) return { status: csrf.status, body: await csrf.text() };
      const { csrfToken } = (await csrf.json()) as { csrfToken: string };
      const created = await fetch('/api/v1/trips', {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
          'Content-Type': 'application/json',
          'X-CSRF-Token': csrfToken,
          'Idempotency-Key': crypto.randomUUID(),
        },
        body: JSON.stringify({
          startDate,
          endDate: startDate,
          timezone: 'Asia/Seoul',
          planningLevel: 'MUST_VISIT_ONLY',
          interests: [],
          seedItems: placeIds.map((placeId, position) => ({
            placeId,
            date: startDate,
            position,
          })),
        }),
      });
      if (created.status !== 201)
        return { status: created.status, body: await created.text() };
      const { id } = (await created.json()) as { id: string };
      return { status: 201, id };
    },
    { startDate, placeIds },
  );
  if (result.status !== 201 || !('id' in result)) {
    throw new Error(
      `createTrip answered ${String(result.status)}: ${'body' in result ? result.body : ''}`,
    );
  }
  return `/trip/${result.id}`;
}

test('BA-073-T4 every place the trip response credits shows that credit on screen', async ({
  page,
}) => {
  const tripPath = await createTrip(page, [MYEONGDONG, GYEONGBOKGUNG]);
  const detail = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      /^\/api\/v1\/trips\/[0-9a-f-]{36}$/.test(new URL(response.url()).pathname) &&
      response.status() === 200,
  );
  await page.goto(tripPath);
  const trip = (await (await detail).json()) as TripDetail;

  const credited = trip.days
    .flatMap((day) => day.items)
    .map((item) => item.place)
    .filter((place) => place.sourceAttribution !== null);
  // Every credited place shown with its credit is also true of a page that
  // credits none, so the seeded place has to be among them.
  expect(credited.map((place) => place.id)).toContain(MYEONGDONG);

  for (const place of credited) {
    // An empty credit would match anything below.
    const credit = place.sourceAttribution?.attribution ?? '';
    const officialUrl = place.sourceAttribution?.officialUrl ?? null;
    expect(credit, place.name).not.toBe('');
    const card = page
      .getByRole('article')
      .filter({ has: page.getByRole('heading', { name: place.name, exact: true }) });
    await expect(card, place.name).toHaveCount(1);
    // The server's wording exactly and visibly, as the link to its officialUrl when the
    // response carries one (DataAttribution). A containment check on the card's text also
    // passed a hidden credit and one with anything appended.
    const shown = officialUrl
      ? card.getByRole('link', { name: credit, exact: true })
      : card.getByText(credit, { exact: true });
    await expect(shown, place.name).toBeVisible();
    if (officialUrl) await expect(shown, place.name).toHaveAttribute('href', officialUrl);
  }
});
