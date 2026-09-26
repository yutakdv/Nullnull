import { readFileSync } from 'node:fs';
import { expect, test, type Locator, type Page } from '@playwright/test';
import { messages } from '../src/i18n/messages.js';
import { overflow } from './overflow.js';

// FE-103-T10 in a browser (#54 §4): a search continues past its first page by
// keyboard alone, and the page it brings is where focus lands. The unit test
// (must-visit.test.tsx) measures the same move in happy-dom, which neither
// lays out nor scrolls; this is where the new result is actually focused in a
// scrolling panel at 360px.
//
// The Live search box because it needs nothing but the Live list around it.
// Every call is served with page.route, as in live-replay-matrix.spec.ts, and
// for its reason: page.route cannot see a request the MSW worker answers, so
// the worker is blocked. That also makes the file run the same against the
// mock dev server and in the composed gate, where the seeded catalog is far
// short of a second page and the Live screens answer the FEATURE_LIVE_DATA-off
// 403. The approved `places` example is a single page, so its three places
// are split into two here; only the CursorPage envelope is written, and the
// cursor is opaque by contract, so any string stands in.
test.use({ serviceWorkers: 'block' });

const fixture = (path: string) =>
  readFileSync(
    new URL(`../../../packages/contracts/fixtures/${path}`, import.meta.url),
    'utf8',
  );

interface Place {
  id: string;
  name: string;
}
const PLACES = (JSON.parse(fixture('places/search-page.json')) as { items: Place[] })
  .items;
const [FIRST, SECOND, THIRD] = PLACES;
if (!FIRST || !SECOND || !THIRD) {
  throw new Error('the places example no longer holds the three places split here');
}
const CURSOR = 'cursor-page-2';
const EN = messages['en-US'];

/** Serves the Live list and a two-page search; returns the search bodies and stray calls. */
async function serve(page: Page) {
  const searches: unknown[] = [];
  const unexpected: string[] = [];
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const { pathname } = new URL(request.url());
    const method = request.method();
    const json = (body: unknown) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: typeof body === 'string' ? body : JSON.stringify(body),
      });
    if (method === 'POST' && pathname === '/api/v1/session/csrf')
      return json(fixture('session/csrf-token.json'));
    // The approved owner examples carry ko-KR and would override the locale.
    if (method === 'GET' && pathname === '/api/v1/me') return route.abort();
    if (method === 'POST' && pathname === '/api/v1/live/areas')
      return json(fixture('live/area-result-live.json'));
    if (method === 'GET' && /^\/api\/v1\/live\/areas\/[^/]+\/places$/.test(pathname))
      return json(fixture('live/area-places.json'));
    if (method === 'POST' && pathname === '/api/v1/places/search') {
      const body = request.postDataJSON() as { cursor?: string | null };
      searches.push(body);
      return json(
        body.cursor === CURSOR
          ? { items: [THIRD], page: { nextCursor: null, hasMore: false } }
          : { items: [FIRST, SECOND], page: { nextCursor: CURSOR, hasMore: true } },
      );
    }
    unexpected.push(`${method} ${pathname}`);
    return route.abort();
  });
  await page.route('https://dapi.kakao.com/**', (route) => route.abort());
  await page.addInitScript(() => {
    localStorage.setItem('nullnull.locale', 'en-US');
  });
  return { searches, unexpected };
}

/** Presses Tab until `target` has focus; fails rather than clicking. */
async function tabTo(page: Page, target: Locator, limit = 60): Promise<void> {
  await expect(target).toBeVisible();
  for (let press = 0; press < limit; press += 1) {
    if (await target.evaluate((element) => element === document.activeElement)) return;
    await page.keyboard.press('Tab');
  }
  throw new Error(`not reached by Tab within ${String(limit)} presses`);
}

test('FE-103-T10 a search continued by keyboard lands on the first new result', async ({
  page,
}) => {
  const { searches, unexpected } = await serve(page);
  await page.goto('/live');

  await tabTo(page, page.getByRole('searchbox'));
  await page.keyboard.type('서울');
  const more = page.getByRole('button', { name: EN['placeSearch.more'] });
  await tabTo(page, more);
  await page.keyboard.press('Enter');

  // The result the page added holds focus: its card, which the next Tab
  // leaves for that result's own link.
  const added = page.getByRole('listitem').filter({
    has: page.getByRole('link', {
      name: EN['live.searchOpen'].replace('{name}', THIRD.name),
    }),
  });
  await expect(added).toBeFocused();
  await expect(added).toBeInViewport();
  await page.keyboard.press('Tab');
  await expect(
    page.getByRole('link', { name: EN['live.searchOpen'].replace('{name}', THIRD.name) }),
  ).toBeFocused();

  // The continuation asked with the cursor beside the same words and locale,
  // and it is gone once the server said there is no more. The box searches as
  // it is typed, so the words' own requests are the last two.
  expect(searches.slice(-2)).toEqual([
    { query: '서울', locale: 'en-US' },
    { query: '서울', locale: 'en-US', cursor: CURSOR },
  ]);
  await expect(more).toHaveCount(0);

  // 360px (the config's viewport): the continuation adds nothing wider.
  const measured = await overflow(page);
  expect(measured.spilling).toEqual([]);
  expect(measured.documentWidth).toBeLessThanOrEqual(measured.viewportWidth);
  expect(unexpected).toEqual([]);
});
