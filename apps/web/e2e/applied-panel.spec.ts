import { expect, test } from '@playwright/test';
import { createSeededTrip } from './seeded-trip.js';

// The trip-detail undo block was retired by the FE/BE product decision. The
// underlying optimization history and revert contracts remain covered by
// their unit/contract suites; this browser check guards the route boundary so
// the old block and its eager history request do not quietly return.
test.describe('the retired trip-detail undo block stays removed', () => {
  test('loads the itinerary without mounting or requesting optimization history', async ({
    page,
  }) => {
    const tripPath = await createSeededTrip(page);
    const optimizationRequests: string[] = [];
    page.on('request', (request) => {
      const pathname = new URL(request.url()).pathname;
      if (pathname.startsWith('/api/v1/') && pathname.includes('/optimizations')) {
        optimizationRequests.push(request.url());
      }
    });

    await page.goto(tripPath);
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await page.waitForLoadState('networkidle');

    await expect(page.getByRole('region', { name: /undone|되돌/i })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /undo|되돌리기/i })).toHaveCount(0);
    expect(optimizationRequests).toEqual([]);

    const width = await page.evaluate(() => ({
      client: document.documentElement.clientWidth,
      scroll: document.documentElement.scrollWidth,
    }));
    expect(width.scroll).toBeLessThanOrEqual(width.client);
  });
});
