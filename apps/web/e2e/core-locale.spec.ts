import { readFileSync } from 'node:fs';
import { expect, test, type Page } from '@playwright/test';
import { messages } from '../src/i18n/messages.js';

// BA-092-T15: the core flow's screens show in Korean and in English. The other
// checks that set a locale measure something else - FE-101-T1 that the picker
// sets `lang`, FE-601-T2 that English copy fits - and pass a screen whose
// heading is a Korean literal. Here each screen's heading and its main controls
// are compared, by accessible name, with the value `messages` holds for the
// locale under test, so a string that does not come from the locale's table
// fails in the language it does not belong to.
//
// Every call is served from the approved examples with page.route, as in
// live-replay-matrix.spec.ts: the gate runs with Live and optimization off, so
// only this way do the same screens reach the page in the mock run and in the
// gate.
test.use({ serviceWorkers: 'block' });

const fixture = (path: string) =>
  readFileSync(
    new URL(`../../../packages/contracts/fixtures/${path}`, import.meta.url),
    'utf8',
  );

const RUN = JSON.parse(fixture('optimizations/run-ready.json')) as {
  id: string;
  tripId: string;
};
const TRIP = fixture('trips/trip-detail-scheduled.json');
const TRIP_VERSION = (JSON.parse(TRIP) as { version: number }).version;
const DETAIL = fixture('live/place-detail-live.json');
const PLACE_ID = (JSON.parse(DETAIL) as { place: { id: string } }).place.id;

/** Serves the core screens' calls; returns the calls it does not serve. */
async function serve(page: Page, locale: keyof typeof messages): Promise<string[]> {
  const unexpected: string[] = [];
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const { pathname } = new URL(request.url());
    const method = request.method();
    const json = (body: string, headers: Record<string, string> = {}) =>
      route.fulfill({ status: 200, contentType: 'application/json', headers, body });
    if (method === 'POST' && pathname === '/api/v1/session/csrf')
      return json(fixture('session/csrf-token.json'));
    // The approved owner examples carry ko-KR and would override the locale.
    if (method === 'GET' && pathname === '/api/v1/me') return route.abort();
    if (method === 'POST' && pathname === '/api/v1/trip-drafts/preview')
      return json(fixture('trips/draft-preview-ready.json'));
    if (method === 'GET' && pathname === `/api/v1/trips/${RUN.tripId}`)
      return json(TRIP, { ETag: `"${String(TRIP_VERSION)}"` });
    if (method === 'GET' && pathname === `/api/v1/optimizations/${RUN.id}`)
      return json(fixture('optimizations/run-ready.json'));
    if (method === 'POST' && pathname === '/api/v1/live/areas')
      return json(fixture('live/area-result-live.json'));
    if (method === 'GET' && pathname === `/api/v1/live/places/${PLACE_ID}`)
      return json(DETAIL);
    unexpected.push(`${method} ${pathname}`);
    return route.abort();
  });
  await page.addInitScript((value) => {
    localStorage.setItem('nullnull.locale', value);
  }, locale);
  return unexpected;
}

for (const locale of ['ko-KR', 'en-US'] as const) {
  const M = messages[locale];

  test(`BA-092-T15 trip creation reads ${locale} through to the plan it offers`, async ({
    page,
  }) => {
    const unexpected = await serve(page, locale);
    await page.goto('/start');
    const heading = page.getByRole('heading', { level: 1 });

    await expect(heading).toHaveText(M['wizard.dates.title']);
    await page.locator('button', { hasText: /^20$/ }).first().click();
    await page.locator('button', { hasText: /^23$/ }).first().click();
    await page.getByRole('button', { name: /^\d{4}-\d{2}-\d{2}\s/ }).click();

    await expect(heading).toHaveText(
      `${M['wizard.interests.title1']}${M['wizard.interests.title2']}`,
    );
    await page.getByRole('button', { name: M['wizard.next'], exact: true }).click();

    await expect(heading).toHaveText(
      `${M['wizard.planning.title1']}${M['wizard.planning.title2']}`,
    );
    await page
      .getByRole('button', { name: new RegExp(`^${M['wizard.planning.NOTHING.title']}`) })
      .click();
    await page.getByRole('button', { name: M['wizard.next'], exact: true }).click();

    // The plan the wizard offers, and the control that accepts it.
    await expect(heading).toHaveText(M['draftPreview.title']);
    await expect(
      page.getByRole('button', { name: M['draftPreview.start'], exact: true }),
    ).toBeVisible();
    expect(unexpected, 'calls this file does not serve').toEqual([]);
  });

  test(`BA-092-T15 the trip and its optimization read ${locale}`, async ({ page }) => {
    const unexpected = await serve(page, locale);
    await page.goto(`/trip/${RUN.tripId}`);
    await expect(
      page.getByRole('link', { name: M['trip.optimize'], exact: true }),
    ).toBeVisible();
    await expect(
      page.getByRole('button', { name: M['trip.editStart'], exact: true }),
    ).toBeVisible();

    await page.goto(`/trip/${RUN.tripId}/optimizations/${RUN.id}`);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(
      M['run.title.ready'],
    );
    await expect(
      page.getByRole('button', { name: M['decision.apply'], exact: true }),
    ).toBeVisible();
    await expect(
      page.getByRole('button', { name: M['decision.keep'], exact: true }),
    ).toBeVisible();
    expect(unexpected, 'calls this file does not serve').toEqual([]);
  });

  test(`BA-092-T15 Live list and place detail read ${locale}`, async ({ page }) => {
    const unexpected = await serve(page, locale);
    await page.goto('/live');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(M['live.title']);

    await page.goto(`/live/places/${PLACE_ID}`);
    await expect(
      page.getByRole('button', { name: M['live.detail.back'], exact: true }),
    ).toBeVisible();
    await expect(
      page.getByRole('heading', { name: M['live.detail.crowd'], exact: true }),
    ).toBeVisible();
    await expect(
      page.getByRole('heading', { name: M['live.detail.related'], exact: true }),
    ).toBeVisible();
    await expect(page.getByText(M['live.detail.title'], { exact: true })).toBeVisible();
    expect(unexpected, 'calls this file does not serve').toEqual([]);
  });
}
