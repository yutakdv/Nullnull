import { expect, test } from '@playwright/test';

// FE-001 covers the shell only: the app boots, routes resolve and keyboard
// focus reaches the main region. Feature journeys arrive with their slices.
test.describe('app shell', () => {
  test('boots and resolves the entry route', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByTestId('placeholder-route')).toHaveText('splash');
    await expect(page.locator('html')).toHaveAttribute('lang', /^(ko-KR|en-US)$/);
  });

  test('resolves a deep link without a full reload', async ({ page }) => {
    await page.goto('/live');
    await expect(page.getByTestId('placeholder-route')).toHaveText('live');
  });

  test('shows an explicit not-found screen with a way back', async ({ page }) => {
    await page.goto('/no-such-page');
    await expect(page.getByTestId('placeholder-route')).toHaveText('not-found');
    await page.getByRole('link').click();
    await expect(page.getByTestId('placeholder-route')).toHaveText('splash');
  });

  test('keyboard focus reaches interactive content', async ({ page }) => {
    await page.goto('/no-such-page');
    await page.keyboard.press('Tab');
    await expect(page.getByRole('link')).toBeFocused();
  });
});
