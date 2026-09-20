import { expect, test } from '@playwright/test';

// Deterministic visual assertions for the MSW catalogue.
//
// The composed API intentionally serves a different number and shape of
// curated posts on every fresh integration database. Exact card height,
// pagination count, and optimization fixture geometry are UI regressions, not
// server-contract assertions, so Playwright collects this file only in the
// local mock suite (see playwright.config.ts).
test.describe('deterministic feed and optimization visuals', () => {
  test('S03 feed cards keep the deployment portrait proportions', async ({ page }) => {
    await page.setViewportSize({ width: 393, height: 852 });
    await page.goto('/feed');

    const firstCard = page.locator('article').first();
    await expect(firstCard).toBeVisible();
    const bounds = await firstCard.boundingBox();
    const imageBounds = await firstCard.locator('img').boundingBox();
    expect(bounds?.width).toBe(172.5);
    expect(bounds?.height).toBeGreaterThan(340);
    expect(imageBounds?.width).toBe(172.5);
    expect(imageBounds?.height).toBe(230);
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

  test('S09 keeps optimization inside the Figma bottom-sheet frame', async ({ page }) => {
    await page.setViewportSize({ width: 393, height: 852 });
    await page.addInitScript(() => {
      localStorage.setItem('nullnull.locale', 'ko-KR');
    });
    await page.goto('/trip/018f4a10-2c31-7d42-9a55-6b1f0c3e8a01/optimize');

    const sheet = page.getByRole('dialog', { name: '무엇을 최적화할까요?' });
    await expect(sheet).toBeVisible();
    const bounds = await sheet.boundingBox();
    expect(bounds?.x).toBe(0);
    expect(bounds?.y).toBe(386);
    expect(bounds?.height).toBe(466);
  });
});
