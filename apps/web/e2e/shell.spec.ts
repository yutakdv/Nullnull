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
    await expect(page.getByRole('heading', { level: 1 })).toHaveAttribute(
      'id',
      'live-heading',
    );
  });

  test('shows an explicit not-found screen with a way back', async ({ page }) => {
    await page.goto('/no-such-page');
    // The 404 shows its localised heading, not a debug token. This asserted the
    // literal "not-found" — a dev placeholder's leftover — so the test held the
    // string in place instead of catching it.
    await expect(page.getByRole('heading', { level: 1 })).toHaveAttribute(
      'id',
      'not-found-heading',
    );
    await expect(page.getByText('not-found', { exact: true })).toHaveCount(0);
    await page.getByRole('link').click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveAttribute(
      'id',
      /^(splash|language)-heading$/,
    );
  });

  test('keyboard focus reaches interactive content', async ({ page }) => {
    await page.goto('/no-such-page');
    // Wait for the link to be there before pressing anything. Tab is sent to
    // whatever the page is at that instant, so pressing it during hydration
    // moves focus inside a document that React then replaces, and the
    // assertion sees an element that is attached but not yet focusable. The
    // sibling tests above all await an assertion before they act; this one
    // did not, and it only passed while the unmocked app had no data to wait
    // for.
    await expect(page.getByRole('link')).toBeVisible();
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

  test('profile offers sign-in as a link, and it still sends nothing', async ({
    page,
  }) => {
    // This used to assert the opposite — that the row was inert text and
    // neither a button nor a link. The owner moved sign-in into P0 (#264,
    // #265), so the clause is inverted rather than deleted.
    //
    // The unit test in profile.test.tsx was inverted in the same commit that
    // added the link; this one was not, and the gate caught it. Inverting an
    // assertion in one layer and not the other is how a promise quietly stops
    // being checked where it matters most.
    const writes: string[] = [];
    page.on('request', (request) => {
      if (request.method() !== 'GET') writes.push(request.url());
    });

    await page.goto('/profile');
    await expect(page.getByRole('heading', { level: 1 })).toHaveAttribute(
      'id',
      'profile-heading',
    );

    const link = page.getByRole('link', { name: /로그인|Sign in/ });
    await expect(link).toHaveCount(1);
    await expect(link).toHaveAttribute('href', '/sign-in');

    // The screen it opens has no contract behind it yet (#264), so following
    // the link must not produce an auth request. That is the promise the
    // sign-in screen carries, checked here in a real browser.
    await link.click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveAttribute(
      'id',
      'signin-heading',
    );
    expect(writes.filter((url) => /login|auth|sign-?in|account/i.test(url))).toEqual([]);
  });
});
