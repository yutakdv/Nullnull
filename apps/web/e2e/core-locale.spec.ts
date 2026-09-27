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
  proposals: { summary: string; dataProvenance: Record<string, unknown>[] }[];
};

// The one sentence on the run screen the server writes, per locale.
//
// The approved READY example is written for a Korean reader - its proposal's
// summary is the server's ko sentence - and there is no en-US READY example to
// serve instead (packages/contracts/fixtures belongs to the contract). Served
// as it was, the en-US run showed that Korean sentence and passed, because every
// check here read the app's own copy and none read the server's. The server
// writes it in the owner's language (OptimizeItemHandler picks ko or en, and
// apps/ai explain/templates.py renders it), so an en-US reader gets the EN
// template.
//
// The en-US value below is a TEST OVERRIDE standing in for that EN sentence, not
// what the server would send for this example. It fills the EN template with the
// example's own facts, but 'Insadong' is this file's choice. The server names the
// place with the catalog's locale fallback (JdbcCatalogPlaceQuery `find`: the
// exact locale, then the language, then ko, then the canonical name), and the
// approved trip example calls it 인사동 (trips/trip-detail-scheduled.json). Where
// the catalog has no English name for it, the real EN sentence reads
// "Moving 인사동 from ...". The override is all English on purpose, so that the
// Korean walker below measures the app's copy and not that fallback. The source
// credit stays as the registry wrote it, which is what the server passes in.
const CREDIT = String(RUN.proposals[0]?.dataProvenance[0]?.attribution);
const SUMMARY = {
  'ko-KR': RUN.proposals[0]?.summary ?? '',
  'en-US':
    'Moving Insadong from Oct 4 13:00 to Oct 7 13:00 lowers relative concentration ' +
    `from 88 to 41 (47 points). ${CREDIT}`,
} as const;

/**
 * Text the server wrote about its sources, which the screen quotes as written:
 * every string on the run's provenance records. The only Korean an en-US run
 * screen may show.
 */
const SOURCE_TEXT = [
  ...new Set(
    RUN.proposals.flatMap((proposal) =>
      proposal.dataProvenance.flatMap((record) =>
        Object.values(record).filter(
          (value): value is string => typeof value === 'string',
        ),
      ),
    ),
  ),
].sort((a, b) => b.length - a.length);

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
      return json(
        JSON.stringify({
          ...RUN,
          proposals: RUN.proposals.map((proposal) => ({
            ...proposal,
            summary: SUMMARY[locale],
          })),
        }),
      );
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
    // The served summary, verbatim, in the language of the screen around it: the
    // approved example's sentence in ko-KR, this file's override in en-US (SUMMARY).
    await expect(page.getByText(SUMMARY[locale], { exact: true })).toBeVisible();
    if (locale === 'en-US') {
      // And no other Korean: every text node and accessible label on the
      // screen, with the source credits quoted as written taken out. The
      // checks above read the controls this file names; this reads the rest,
      // so Korean anywhere - the app's own copy, or a ko summary shown where the
      // en one was served - fails here. It does not judge the server's real EN
      // sentence, which can carry a Korean place name (see SUMMARY).
      const korean = await page.evaluate((quoted) => {
        const found: string[] = [];
        const check = (text: string) => {
          const rest = quoted.reduce(
            (left, credit) => left.split(credit).join(' '),
            text,
          );
          if (/[ㄱ-ㆎ가-힣]/.test(rest)) found.push(text.trim());
        };
        const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
        for (let node = walker.nextNode(); node; node = walker.nextNode()) {
          check(node.textContent ?? '');
        }
        for (const element of document.querySelectorAll('[aria-label]')) {
          check(element.getAttribute('aria-label') ?? '');
        }
        return found;
      }, SOURCE_TEXT);
      expect(korean, 'Korean on the en-US run screen besides the source credits').toEqual(
        [],
      );
    }
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
