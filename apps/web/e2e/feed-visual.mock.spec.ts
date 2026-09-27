import { readFileSync } from 'node:fs';
import { expect, test, type Page } from '@playwright/test';

const fixture = (path: string) =>
  readFileSync(
    new URL(`../../../packages/contracts/fixtures/${path}`, import.meta.url),
    'utf8',
  );

/** The approved forecast reading one feed card is given (crowd/series-forecast.json). */
const CROWD = (
  JSON.parse(fixture('crowd/series-forecast.json')) as {
    points: { provenance: { officialUrl: string } }[];
  }
).points[0];

/**
 * Serves the feed as MSW does - the same owner, trip list and feed page - except
 * that the SECOND card carries CROWD. Returns the calls it did not serve, for
 * the test to assert empty. The second card, so that measuring the first card
 * (what this test did before) measures a card without it.
 */
async function serveFeedWithCrowd(page: Page): Promise<string[]> {
  const unexpected: string[] = [];
  const tripPage = JSON.parse(fixture('trips/trip-page.json')) as {
    items: { id: string }[];
  };
  const owner = {
    ...(JSON.parse(fixture('session/owner-profile-anonymous.json')) as object),
    activeTripId: tripPage.items[0]?.id ?? null,
  };
  const feed = JSON.parse(fixture('feed/page.json')) as { items: object[] };
  feed.items = feed.items.map((card, index) =>
    index === 1 ? { ...card, crowd: CROWD } : card,
  );
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const { pathname } = new URL(request.url());
    const method = request.method();
    const json = (body: unknown) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(body),
      });
    if (method === 'POST' && pathname === '/api/v1/session/csrf')
      return json(JSON.parse(fixture('session/csrf-token.json')));
    if (method === 'GET' && pathname === '/api/v1/me') return json(owner);
    if (method === 'GET' && pathname === '/api/v1/trips') return json(tripPage);
    if (method === 'GET' && pathname === '/api/v1/feed') return json(feed);
    unexpected.push(`${method} ${pathname}`);
    return route.abort();
  });
  return unexpected;
}

// Deterministic visual assertions for the MSW catalogue.
//
// The composed API intentionally serves a different number and shape of
// curated posts on every fresh integration database. Exact card height,
// pagination count, and optimization fixture geometry are UI regressions, not
// server-contract assertions, so Playwright collects this file only in the
// local mock suite (see playwright.config.ts).
test.describe('deterministic feed and optimization visuals', () => {
  // The height floor is the deployment portrait proportion the card was built
  // to (93de5679): a card whose crowd reading draws its forecast credit.
  // Since #390 the feed examples carry what the server sends, and every card's
  // `crowd` is null, so no card the catalogue serves has that credit. Measured
  // on the second card at 393px: without it 325.39 (4 text lines, 1 credit
  // link), with it 355.39 (6 lines, 2 links) - the forecast's credit wraps to
  // two lines at 172.5px. The floor is not moved: the card it describes is made
  // here, from the approved forecast example, and measured by what it holds.
  test.describe(() => {
    // page.route cannot see what the MSW worker answers
    // (live-replay-matrix.spec.ts), so the worker is blocked for this test.
    test.use({ serviceWorkers: 'block' });

    test('S03 feed cards keep the deployment portrait proportions', async ({ page }) => {
      await page.setViewportSize({ width: 393, height: 852 });
      const unexpected = await serveFeedWithCrowd(page);
      await page.goto('/feed');

      // The card that draws the forecast's credit - by the link to its source.
      const card = page.locator('article').filter({
        has: page.locator(`a[href="${CROWD?.provenance.officialUrl ?? ''}"]`),
      });
      await expect(card).toBeVisible();
      const bounds = await card.boundingBox();
      const imageBounds = await card.locator('img').boundingBox();
      expect(bounds?.width).toBe(172.5);
      expect(bounds?.height).toBeGreaterThan(340);
      expect(imageBounds?.width).toBe(172.5);
      expect(imageBounds?.height).toBe(230);
      expect(unexpected, 'calls this file does not serve').toEqual([]);
    });
  });

  test('S03 continues the feed on scroll without a load-more button', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 393, height: 852 });
    await page.goto('/feed');

    const cards = page.locator('article');
    await expect(cards.first()).toBeVisible();
    await expect(page.getByRole('button', { name: /더 보기|Show more/ })).toHaveCount(0);

    await page.locator('main').hover();
    await page.mouse.wheel(0, 1_000);
    await expect(cards).toHaveCount(6);
  });

  test('S09 keeps optimization pinned to the viewport without whole-sheet scrolling', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 393, height: 852 });
    await page.addInitScript(() => {
      localStorage.setItem('nullnull.locale', 'ko-KR');
    });
    await page.goto('/trip/018f4a10-2c31-7d42-9a55-6b1f0c3e8a01/optimize');

    const sheet = page.getByRole('dialog', { name: '무엇을 최적화할까요?' });
    await expect(sheet).toBeVisible();
    const bounds = await sheet.boundingBox();
    expect(bounds?.x).toBe(0);
    expect(Math.round((bounds?.y ?? 0) + (bounds?.height ?? 0))).toBe(852);
    expect(bounds?.height).toBeLessThan(852);
    const scroll = await sheet.evaluate((node) => ({
      clientHeight: node.clientHeight,
      scrollHeight: node.scrollHeight,
    }));
    expect(scroll.scrollHeight).toBe(scroll.clientHeight);
  });
});
