import { expect, test } from '@playwright/test';
import { postFixtures } from '@nullnull/contracts';

test('A-07 keyboard opens a saved post and removes it from the profile list', async ({
  page,
}) => {
  const writes: string[] = [];
  page.on('request', (request) => {
    if (['PUT', 'POST', 'PATCH', 'DELETE'].includes(request.method())) {
      writes.push(`${request.method()} ${new URL(request.url()).pathname}`);
    }
  });
  await page.goto(`/posts/${postFixtures.detail.id}`);
  const save = page.getByRole('button', { name: 'Save this post' });
  await expect(save).toBeVisible();
  await save.focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('button', { name: 'Remove from saved' })).toBeVisible();

  await page.getByRole('button', { name: 'Back' }).click();
  await page.getByRole('button', { name: 'Me', exact: true }).click();
  const entry = page.getByRole('link', { name: 'Saved posts' });
  await expect(entry).toBeVisible();
  await entry.focus();
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/\/profile\/saved-posts$/);

  const detail = page.getByRole('link', { name: '가을 서울 산책 코스' });
  await expect(detail).toBeVisible();
  await detail.focus();
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(new RegExp(`/posts/${postFixtures.detail.id}$`));
  await page.getByRole('button', { name: 'Back' }).click();
  await expect(page).toHaveURL(/\/profile\/saved-posts$/);

  const remove = page.getByRole('button', { name: 'Remove 가을 서울 산책 코스' });
  await remove.focus();
  await page.keyboard.press('Enter');
  await expect(page.getByText('No saved posts yet.')).toBeVisible();
  expect(writes.filter((write) => write.includes('/trips'))).toEqual([]);
  expect(
    writes.filter((write) => write.endsWith(`/posts/${postFixtures.detail.id}/saved`)),
  ).toEqual([
    `PUT /api/v1/posts/${postFixtures.detail.id}/saved`,
    `DELETE /api/v1/posts/${postFixtures.detail.id}/saved`,
  ]);
  const widths = await page.evaluate(() => ({
    content: document.documentElement.scrollWidth,
    viewport: document.documentElement.clientWidth,
  }));
  expect(widths.content).toBeLessThanOrEqual(widths.viewport);
});
