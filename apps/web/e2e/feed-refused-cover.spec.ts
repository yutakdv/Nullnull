import { readFileSync } from 'node:fs';
import { expect, test, type Page } from '@playwright/test';

// FE-603-T13: a feed card whose cover URL isSafeUrl refuses (FE-603-T12) still
// shows the control that opens the post, at a size a finger can hit.
//
// The cover <button> is the card's only way into the post - the title is a
// plain <h3> - and its height came only from `.cover img`. With the image
// refused, the button collapsed to 0px: still in the DOM, still focusable,
// invisible and untappable. The render test beside T12 cannot see that,
// because jsdom has no layout; only a browser measures it.
//
// Every call is served with page.route and service workers are blocked, as in
// core-locale.spec.ts, so the same card reaches the page against the mock dev
// server and in the composed gate. The feed has no example with an http cover
// (every shipped URL is https), so the first card of the approved feed page
// has its cover rewritten to http - the one field under test.
test.use({ serviceWorkers: 'block' });

const fixture = (path: string) =>
  readFileSync(
    new URL(`../../../packages/contracts/fixtures/${path}`, import.meta.url),
    'utf8',
  );

interface FeedPage {
  items: { post: { title: string; coverUrl: string } }[];
}

const PAGE = JSON.parse(fixture('feed/page.json')) as FeedPage;
const REFUSED = PAGE.items[0]!.post;
const REFUSED_COVER = REFUSED.coverUrl.replace(/^https:/, 'http:');
PAGE.items[0]!.post = { ...REFUSED, coverUrl: REFUSED_COVER };

/** Serves the feed's calls; returns the calls it does not serve. */
async function serve(page: Page): Promise<string[]> {
  const unexpected: string[] = [];
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const { pathname, searchParams } = new URL(request.url());
    const method = request.method();
    const json = (body: string) =>
      route.fulfill({ status: 200, contentType: 'application/json', body });
    if (method === 'POST' && pathname === '/api/v1/session/csrf')
      return json(fixture('session/csrf-token.json'));
    if (method === 'GET' && pathname === '/api/v1/me')
      return json(fixture('session/owner-profile-onboarded.json'));
    if (method === 'GET' && pathname === '/api/v1/trips')
      return json(fixture('trips/trip-page.json'));
    if (method === 'GET' && pathname === '/api/v1/feed')
      return json(
        searchParams.has('cursor') ? fixture('feed/page-2.json') : JSON.stringify(PAGE),
      );
    unexpected.push(`${method} ${pathname}`);
    return route.abort();
  });
  // The other covers name a host that does not exist; nothing should wait on it.
  await page.route('https://cdn.example.test/**', (route) => route.abort());
  return unexpected;
}

test('FE-603-T13 a refused feed cover still leaves a visible 44px control that opens the post', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const unexpected = await serve(page);
  await page.goto('/feed');

  const card = page.locator('article', { hasText: REFUSED.title });
  await expect(card).toHaveCount(1);
  // The refusal happened: no image was drawn from the http URL. Without this
  // the measurement below could be of an ordinary card with its picture.
  await expect(card.locator('img')).toHaveCount(0);

  const cover = card.getByRole('button', { name: REFUSED.title, exact: true });
  await expect(cover).toBeVisible();
  const box = await cover.boundingBox();
  expect(box?.height ?? 0).toBeGreaterThanOrEqual(44);
  expect(box?.width ?? 0).toBeGreaterThanOrEqual(44);

  expect(unexpected).toEqual([]);

  // And it is still the way in. Only the navigation is asserted: the post's
  // own read is not served, because the post screen is not what this measures.
  await cover.click();
  await expect(page).toHaveURL(/\/posts\/018f5b00-0000-7000-8000-000000000001$/);
});
