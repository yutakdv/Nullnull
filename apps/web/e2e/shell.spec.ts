import { expect, test } from '@playwright/test';

// FE-001 covers the shell only: the app boots, routes resolve and keyboard
// focus reaches the main region. Feature journeys arrive with their slices.
test.describe('app shell', () => {
  test('boots and resolves the entry route', async ({ page }) => {
    await page.goto('/');
    // FE-101 replaced the splash placeholder with the real screen. It renders
    // the brand immediately and resolves the session behind it, so the entry
    // route is never blank whatever the API answers.
    await expect(page.getByRole('heading', { level: 1 })).toHaveAttribute(
      'id',
      /^(splash|language)-heading$/,
    );
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
    await expect(page.getByRole('heading', { level: 1 })).toHaveAttribute(
      'id',
      /^(splash|language)-heading$/,
    );
  });

  test('keyboard focus reaches interactive content', async ({ page }) => {
    await page.goto('/no-such-page');
    await page.keyboard.press('Tab');
    await expect(page.getByRole('link')).toBeFocused();
  });
});

// These run against the built app and a real API container, with no mocks. The
// unit suite covers behaviour; what only this environment can prove is that the
// screens survive a server that answers for real (FE-101, FE-105).
test.describe('onboarding and profile in a real browser', () => {
  test('language screen selects Korean and English, never JA or ZH', async ({ page }) => {
    await page.goto('/language');
    const japanese = page.getByRole('button', { name: /日本語/ });
    await expect(japanese).toBeDisabled();
    await expect(page.getByRole('button', { name: /中文/ })).toBeDisabled();
    await page.getByRole('button', { name: /English/ }).click();
    await expect(page.locator('html')).toHaveAttribute('lang', 'en-US');
  });

  test('a chosen language survives a reload', async ({ page }) => {
    await page.goto('/language');
    await page.getByRole('button', { name: /한국어/ }).click();
    await expect(page.locator('html')).toHaveAttribute('lang', 'ko-KR');
    await page.reload();
    await expect(page.locator('html')).toHaveAttribute('lang', 'ko-KR');
  });

  test('profile shows the guest state without offering sign-in', async ({ page }) => {
    await page.goto('/profile');
    await expect(page.getByRole('heading', { level: 1 })).toHaveAttribute(
      'id',
      'profile-heading',
    );
    // P0 has no accounts: the row is inert text, not a control.
    await expect(page.getByRole('button', { name: /로그인|Sign in/ })).toHaveCount(0);
    await expect(page.getByRole('link', { name: /로그인|Sign in/ })).toHaveCount(0);
  });
});
