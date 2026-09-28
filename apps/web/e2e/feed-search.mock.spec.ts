import { expect, test } from '@playwright/test';

test('A-08 feed search announces its availability before keyboard activation at 360px', async ({
  page,
}) => {
  await page.goto('/feed');
  const availability = page.locator('#feed-search-availability');
  await expect(availability).toBeVisible();
  await expect(availability).toHaveText(/coming soon|준비 중/);

  const search = page.getByRole('button', { name: /search|검색/i });
  await expect(search).toHaveAttribute('aria-describedby', 'feed-search-availability');
  await search.focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('status')).toHaveText(/coming soon|준비 중/);

  const widths = await page.evaluate(() => ({
    content: document.documentElement.scrollWidth,
    viewport: document.documentElement.clientWidth,
  }));
  expect(widths.content).toBeLessThanOrEqual(widths.viewport);
});
