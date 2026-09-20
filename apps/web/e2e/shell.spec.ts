import { expect, test } from '@playwright/test';
import { createRepresentativeTrip, createSeededTrip } from './seeded-trip.js';

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

  test('the not-found state uses the service gutter and recovery target', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 393, height: 852 });
    await page.goto('/no-such-page');

    const heading = page.getByRole('heading', { level: 1 });
    const recovery = page.getByRole('link');
    const headingBounds = await heading.boundingBox();
    const recoveryBounds = await recovery.boundingBox();
    expect(headingBounds?.x).toBe(16);
    expect(recoveryBounds?.height).toBeGreaterThanOrEqual(44);
  });

  test('A-2 keeps the Figma 16px content gutter without shell padding', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 393, height: 852 });
    await page.goto('/language');

    const heading = page.getByRole('heading', { name: /Choose your language/ });
    await expect(heading).toBeVisible();
    await expect(heading).toHaveCSS('margin-left', '0px');

    const bounds = await heading.boundingBox();
    expect(bounds?.x).toBe(16);
  });

  test('uses Pretendard as the UI typeface', async ({ page }) => {
    await page.goto('/language');
    await expect(page.locator('body')).toHaveCSS('font-family', /Pretendard/);
  });

  test('caps and centers form content on a wide viewport', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await page.goto('/start');

    const main = page.locator('#main');
    await expect(main).toHaveAttribute('data-content-width', 'form');
    const bounds = await main.boundingBox();
    expect(bounds?.width).toBe(560);
    expect(bounds?.x).toBe(360);
  });

  test('A-2 places its bottom CTA at the Figma inset', async ({ page }) => {
    await page.setViewportSize({ width: 393, height: 852 });
    await page.goto('/language');

    const next = page.locator('section').getByRole('button').last();
    await expect(next).toBeVisible();
    const bounds = await next.boundingBox();
    expect(bounds?.y).toBe(771);
    expect(bounds?.height).toBe(52);
  });

  test('A-1 centers the Figma brand block without a visible loading line', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 393, height: 852 });
    await page.goto('/');

    const heading = page.getByRole('heading', { name: 'Nullnull' });
    const tagline = page.locator('#splash-heading + p');
    await expect(heading).toBeVisible();
    await expect(tagline).toBeVisible();

    const headingBounds = await heading.boundingBox();
    const taglineBounds = await tagline.boundingBox();
    expect(headingBounds?.y).toBe(377);
    expect(taglineBounds?.y).toBe(433);
  });

  test('A-3 moves the CTA up when its Figma secondary copy is present', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 393, height: 852 });
    await page.goto('/intro');

    const start = page.locator('section').getByRole('button').last();
    await expect(start).toBeVisible();
    const bounds = await start.boundingBox();
    expect(bounds?.y).toBe(756);
    expect(bounds?.height).toBe(52);
  });

  test('P0 tab destinations keep the deployment-sized floating tab bar', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 393, height: 852 });
    await page.goto('/feed');

    const tabs = page.getByRole('navigation', { name: /주요 메뉴|Main menu/ });
    await expect(tabs).toBeVisible();
    const bounds = await tabs.boundingBox();
    expect(bounds?.x).toBe(16);
    expect(bounds?.y).toBe(771);
    expect(bounds?.width).toBe(361);
    expect(bounds?.height).toBe(73);
  });

  test('BA-011 chooses and opens a representative trip by keyboard', async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('nullnull.locale', 'ko-KR');
    });
    await createSeededTrip(page);
    await page.goto('/trips/select');

    await expect(page.getByRole('heading', { name: '내 여행' })).toBeVisible();
    const firstTrip = page.getByRole('list').getByRole('button').first();
    await firstTrip.focus();
    await expect(firstTrip).toBeFocused();
    await page.keyboard.press('Enter');

    await expect(page).toHaveURL(/\/trip\/[0-9a-f-]+$/i);
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  });

  test('S03 balances the feed title inset above and below', async ({ page }) => {
    await page.setViewportSize({ width: 393, height: 852 });
    await page.goto('/feed');

    const screen = page.locator('section[aria-labelledby="feed-heading"]');
    const title = page.locator('#feed-heading');
    const header = title.locator('..');
    const next = header.locator('xpath=following-sibling::*[1]');

    const screenBounds = await screen.boundingBox();
    const headerBounds = await header.boundingBox();
    const nextBounds = await next.boundingBox();
    await expect(title).toHaveCSS('font-size', '24px');
    expect((headerBounds?.y ?? 0) - (screenBounds?.y ?? 0)).toBe(12);
    expect(
      (nextBounds?.y ?? 0) - ((headerBounds?.y ?? 0) + (headerBounds?.height ?? 0)),
    ).toBe(12);
  });

  test('S03 floats the search notice without moving the feed', async ({ page }) => {
    await page.setViewportSize({ width: 393, height: 852 });
    await createRepresentativeTrip(page);
    await page.goto('/feed');

    const banner = page.getByTestId('active-trip-banner');
    await expect(banner).toBeVisible();
    const before = await banner.boundingBox();

    await page.getByRole('button', { name: /검색|Search/ }).click();

    const notice = page.getByRole('status');
    await expect(notice).toBeVisible();
    const after = await banner.boundingBox();
    const noticeBounds = await notice.boundingBox();
    const searchBounds = await page
      .getByRole('button', { name: /검색|Search/ })
      .boundingBox();
    expect(after?.y).toBe(before?.y);
    expect(
      (searchBounds?.x ?? 0) - ((noticeBounds?.x ?? 0) + (noticeBounds?.width ?? 0)),
    ).toBe(8);
    expect((noticeBounds?.y ?? 0) + (noticeBounds?.height ?? 0) / 2).toBe(
      (searchBounds?.y ?? 0) + (searchBounds?.height ?? 0) / 2,
    );
    await page.waitForTimeout(1_100);
    await expect(notice).toHaveCount(0);
  });

  test('S03 reaches the home logo and representative-trip choices by keyboard', async ({
    page,
  }) => {
    await createRepresentativeTrip(page, 2);
    await page.goto('/feed');

    const logo = page.getByRole('link', { name: /홈 피드|Home feed/ });
    await logo.focus();
    await expect(logo).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/\/feed$/);

    const trip = page.getByRole('button', { name: /대표 여행|Representative trip/ });
    await trip.focus();
    await page.keyboard.press('Enter');
    await expect(trip).toHaveAttribute('aria-expanded', 'true');

    await page.keyboard.press('Tab');
    await expect(
      page
        .getByRole('list', { name: /대표 여행|Representative trip/ })
        .getByRole('button')
        .first(),
    ).toBeFocused();

    await page.keyboard.press('Escape');
    await expect(trip).toBeFocused();
    await expect(trip).toHaveAttribute('aria-expanded', 'false');
  });

  test('S03 keeps the closed trip filter blue and highlights gray choices on hover', async ({
    page,
  }) => {
    await createRepresentativeTrip(page, 2);
    await page.goto('/feed');

    const trip = page.getByRole('button', { name: /대표 여행|Representative trip/ });
    await expect(trip).toHaveCSS('background-color', 'rgb(234, 242, 255)');
    await trip.hover();
    await expect(trip).toHaveCSS('background-color', 'rgb(234, 242, 255)');

    await trip.click();
    const choice = page
      .getByRole('list', { name: /대표 여행|Representative trip/ })
      .getByRole('button')
      .last();
    await expect(choice).toHaveCSS('background-color', 'rgb(247, 248, 249)');
    await choice.hover();
    await expect(choice).toHaveCSS('background-color', 'rgb(234, 242, 255)');
  });

  test('S07 trip hero keeps the Figma 12px top inset', async ({ page }) => {
    await page.setViewportSize({ width: 393, height: 852 });
    await page.addInitScript(() => {
      localStorage.setItem('nullnull.locale', 'ko-KR');
    });
    const tripPath = await createSeededTrip(page);
    await page.goto(tripPath);

    const heading = page.getByRole('heading', { level: 1 });
    await expect(heading).toBeVisible();
    const hero = heading.locator('xpath=ancestor::header');
    const bounds = await hero.boundingBox();
    expect(bounds?.x).toBe(16);
    expect(bounds?.y).toBe(12);
    expect(bounds?.height).toBe(178);
  });

  test('S14 uses the approved profile type and icon scale', async ({ page }) => {
    await page.setViewportSize({ width: 393, height: 852 });
    await page.addInitScript(() => {
      localStorage.setItem('nullnull.locale', 'ko-KR');
    });
    await createSeededTrip(page);
    await page.goto('/profile');

    await expect(page.locator('#profile-heading')).toHaveCSS('font-size', '24px');

    const avatar = page.getByTestId('profile-avatar');
    await expect(avatar).toHaveCSS('width', '40px');
    await expect(avatar).toHaveCSS('height', '40px');
    await expect(avatar.locator('svg')).toHaveAttribute('width', '24');

    await expect(page.getByRole('link', { name: '로그인' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '로그인' })).toHaveCount(0);

    await expect(page.getByText('TEST', { exact: true })).toHaveCSS('font-size', '18px');
    await expect(page.getByText('이 계정은 test계정입니다')).toHaveCSS(
      'font-size',
      '15px',
    );
    await expect(page.locator('#profile-trips-heading')).toHaveCSS('font-size', '17px');
    await expect(page.locator('#interests-heading')).toHaveCSS('font-size', '17px');
    await expect(page.locator('#deletion-heading')).toHaveCSS('font-size', '17px');

    const trips = page.getByRole('region', { name: '내 여행 목록' });
    const firstTrip = trips.getByRole('link').first();
    await expect(firstTrip.locator('span').nth(1)).toHaveCSS('font-size', '16px');
    await expect(firstTrip.locator('svg')).toHaveCount(0);
  });

  test('S14 aligns profile card headings to the 16px spacing grid', async ({ page }) => {
    await page.setViewportSize({ width: 393, height: 852 });
    await page.addInitScript(() => {
      localStorage.setItem('nullnull.locale', 'ko-KR');
    });
    await createSeededTrip(page);
    await page.goto('/profile');

    const trips = page.getByRole('region', { name: '내 여행 목록' });
    const history = page.getByRole('region', { name: 'AI 최적화 이력' });
    const tripBounds = await trips.boundingBox();
    const tripHeadingBounds = await page.locator('#profile-trips-heading').boundingBox();
    const historyBounds = await history.boundingBox();
    const historyHeadingBounds = await page
      .locator('#profile-history-heading')
      .boundingBox();

    expect((tripHeadingBounds?.x ?? 0) - (tripBounds?.x ?? 0)).toBe(16);
    expect((tripHeadingBounds?.y ?? 0) - (tripBounds?.y ?? 0)).toBe(16);
    expect((historyHeadingBounds?.x ?? 0) - (historyBounds?.x ?? 0)).toBe(16);
    expect((historyHeadingBounds?.y ?? 0) - (historyBounds?.y ?? 0)).toBe(16);
    expect(
      (historyBounds?.y ?? 0) - ((tripBounds?.y ?? 0) + (tripBounds?.height ?? 0)),
    ).toBe(12);

    const tripCount = page
      .locator('#profile-trips-heading')
      .locator('..')
      .locator('span');
    const firstDelete = trips.getByRole('button', { name: /삭제/ }).first();
    await expect(tripCount).toBeVisible();
    await expect(firstDelete).toBeVisible();
    const countBounds = await tripCount.boundingBox();
    const deleteBounds = await firstDelete.boundingBox();
    const countCenter = (countBounds?.x ?? 0) + (countBounds?.width ?? 0) / 2;
    const deleteCenter = (deleteBounds?.x ?? 0) + (deleteBounds?.width ?? 0) / 2;
    expect(Math.abs(countCenter - deleteCenter)).toBeLessThanOrEqual(0.5);
  });
});

// These run against the built app and a real API container, with no mocks. The
// unit suite covers behaviour; what only this environment can prove is that the
// screens survive a server that answers for real (FE-101, FE-105).
test.describe('onboarding and profile in a real browser', () => {
  test('FE-101-T1 language screen selects Korean and English, never JA or ZH (FCR-001 trace)', async ({
    page,
  }) => {
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

  test('FE-105-T1 profile test-account label is inert and sends nothing', async ({
    page,
  }) => {
    // THIRD spelling. It began as inert text, became a link when login went
    // into P0 (#264, #265), and is back because the owner reverted login to P1
    // on 2026-09-19 — `owners.account_id` is unique, so several judges on the
    // one official test account would share an owner and see each other's
    // edits. An anonymous session gives each browser its own.
    //
    // The lesson this test recorded the last time still applies, now to the
    // revert: profile.test.tsx was inverted in the same commit that added the
    // link and THIS file was not, so the gate caught it. Both layers move
    // together or the promise stops being checked where it matters most.
    const requests: string[] = [];
    page.on('request', (request) => {
      requests.push(`${request.method()} ${request.url()}`);
    });

    await page.goto('/profile');
    await expect(page.getByRole('heading', { level: 1 })).toHaveAttribute(
      'id',
      'profile-heading',
    );

    // Present but not a control. The test-account label explains the session
    // state without promising a sign-in flow that P0 does not provide.
    const account = page.getByText(/test계정|test account/i).first();
    await expect(account).toBeVisible();
    await expect(page.getByRole('link', { name: /로그인|Sign in/ })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /로그인|Sign in/ })).toHaveCount(0);

    await page.waitForLoadState('networkidle');
    const requestCount = requests.length;
    await account.click();
    await account.dispatchEvent('keydown', { key: 'Enter' });
    await expect(page).toHaveURL(/\/profile$/);
    await page.waitForTimeout(50);
    expect(requests).toHaveLength(requestCount);

    // No request at all, which is stronger than checking one guessed auth path.
  });
});
