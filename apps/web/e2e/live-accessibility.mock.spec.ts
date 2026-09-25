import { expect, test, type Page } from '@playwright/test';

const LIVE_PLACE_PATH = '/live/places/018f4b20-1a44-7e11-9c02-5d7e3f1a2b01';

function useLocale(page: Page, locale: string) {
  return page.addInitScript((nextLocale) => {
    localStorage.setItem('nullnull.locale', nextLocale);
  }, locale);
}

test.describe('FE-402-T3 Live detail accessibility', () => {
  test('restores focus to the Korean candidate action after saving at 360px', async ({
    page,
  }) => {
    await useLocale(page, 'ko-KR');
    await page.setViewportSize({ width: 360, height: 800 });
    await page.goto(LIVE_PLACE_PATH);

    await expect(page.getByRole('heading', { level: 1, name: '경복궁' })).toBeVisible();
    await expect(page.getByRole('button', { name: '라이브로 돌아가기' })).toBeVisible();

    const save = page.getByRole('button', { name: '대표 여행에 담기' });
    await expect(save).toHaveAccessibleName('대표 여행에 담기');
    await expect(
      page.getByText('후보 장소로만 담아요. 여행 일정은 그대로예요'),
    ).toBeVisible();

    await save.focus();
    await expect(save).toBeFocused();
    await page.keyboard.press('Enter');

    await expect(
      page.getByText('대표 여행에 담았어요. 일정은 그대로예요.'),
    ).toBeVisible();
    await expect(save).toBeEnabled();
    await expect(save).toBeFocused();
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth),
    ).toBeLessThanOrEqual(
      await page.evaluate(() => document.documentElement.clientWidth),
    );
  });

  test('reflows the English action and long status at the 200% zoom-equivalent width', async ({
    page,
  }) => {
    await useLocale(page, 'en-US');
    await page.setViewportSize({ width: 180, height: 800 });
    await page.goto(LIVE_PLACE_PATH);

    await expect(page.getByRole('heading', { level: 1, name: '경복궁' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Back to Live' })).toHaveAccessibleName(
      'Back to Live',
    );

    const save = page.getByRole('button', { name: 'Save to representative trip' });
    await expect(save).toHaveAccessibleName('Save to representative trip');
    await expect(
      page.getByText('Saves this as a candidate. Your itinerary stays unchanged.'),
    ).toBeVisible();
    await save.click();
    await expect(
      page.getByText('Saved to your representative trip. Your itinerary is unchanged.'),
    ).toBeVisible();

    const layout = await page.evaluate(() => ({
      clientWidth: document.documentElement.clientWidth,
      scrollWidth: document.documentElement.scrollWidth,
    }));
    expect(layout.scrollWidth).toBeLessThanOrEqual(layout.clientWidth);
  });

  test('keeps the detail action reduced-motion safe', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await useLocale(page, 'en-US');
    await page.goto(LIVE_PLACE_PATH);

    const action = page.getByRole('button', { name: 'Save to representative trip' });
    await expect(action).toBeVisible();
    const duration = await action.evaluate(
      (element) => getComputedStyle(element).transitionDuration,
    );
    expect(duration).toMatch(/^(?:0s|1e-05s|0\.00001s)$/);
  });
});

test.describe('FE-403-T3 Live reduced motion', () => {
  test('keeps the map fallback and destination list usable when reduce is requested', async ({
    page,
  }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await useLocale(page, 'en-US');
    await page.route('https://dapi.kakao.com/**', (route) => route.abort());
    await page.goto('/live');

    const list = page.getByRole('region', { name: 'Live destination list' });
    await expect(list).toBeVisible();
    await expect(page.getByRole('region', { name: 'Live map' })).toBeVisible();
    await expect(page.getByText('Could not load the map')).toBeVisible();
    await expect(
      page.getByText('Check the latest observation and its source state'),
    ).toBeVisible();

    const motion = await list.evaluate((element) => {
      const style = getComputedStyle(element);
      return {
        animationName: style.animationName,
        transitionDuration: style.transitionDuration,
      };
    });
    expect(motion.animationName).toBe('none');
    expect(motion.transitionDuration).toMatch(/^(?:0s|1e-05s|0\.00001s)$/);
  });
});

for (const locale of ['ko-KR', 'en-US'] as const) {
  test(`FE-402 FE-403 Seoul scale and offline recovery in ${locale}`, async ({
    page,
    context,
  }) => {
    const ko = locale === 'ko-KR';
    await useLocale(page, locale);
    await page.goto(LIVE_PLACE_PATH);
    const bar = page.getByRole('img', {
      name: ko ? /서울 혼잡도 5단계 중/ : /Seoul crowd level .* of 5/,
    });
    await expect(bar).toBeVisible();
    await expect(bar.locator(':scope > span')).toHaveCount(5);
    await expect(bar.locator(':scope > span[data-unpublished]')).toHaveCount(1);
    await page
      .getByRole('button', { name: ko ? '라이브로 돌아가기' : 'Back to Live' })
      .click();
    const list = page.getByRole('list', {
      name: ko ? '지금 권역 혼잡' : 'Crowding by area now',
    });
    await expect(list).toBeVisible();
    await context.setOffline(true);
    const offline = page.getByText(
      ko
        ? '인터넷 연결이 끊겼어요. 이전에 불러온 정보는 현재와 다를 수 있어요.'
        : 'You are offline. Previously loaded readings may be out of date.',
    );
    await expect(offline).toBeVisible();
    await expect(list).toBeVisible();
    await context.setOffline(false);
    await expect(offline).not.toBeVisible();
  });
}
