import { expect, test } from '@playwright/test';

test('A-08 feed search keeps its keyboard notice without a duplicate header label at 360px', async ({
  page,
}) => {
  await page.goto('/feed');
  await expect(page.locator('#feed-search-availability')).toHaveCount(0);
  await expect(
    page.getByText(/Search is coming soon|피드 검색 기능을 준비 중이에요/),
  ).toHaveCount(0);

  const search = page.getByRole('button', { name: /search|검색/i });
  await expect(search).not.toHaveAttribute(
    'aria-describedby',
    /feed-search-availability/,
  );
  await search.focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('status')).toHaveText(/coming soon|준비 중/);

  const widths = await page.evaluate(() => ({
    content: document.documentElement.scrollWidth,
    viewport: document.documentElement.clientWidth,
  }));
  expect(widths.content).toBeLessThanOrEqual(widths.viewport);
});
