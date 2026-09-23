import { expect, test } from '@playwright/test';

for (const locale of ['ko-KR', 'en-US'] as const) {
  test(`FE-303-T2 FE-303-T3 NOT_ACTIVE safely refreshes a stale candidate in ${locale}`, async ({
    page,
  }) => {
    const korean = locale === 'ko-KR';
    const status = korean ? 'DISMISSED' : 'SCHEDULED';
    const mutations: string[] = [];
    page.on('request', (request) => {
      if (
        request.method() === 'POST' &&
        new URL(request.url()).pathname.endsWith('/items')
      ) {
        mutations.push(request.url());
      }
    });
    await page.setViewportSize({ width: korean ? 180 : 360, height: 800 });
    await page.addInitScript(
      ({ locale, status }) => {
        localStorage.setItem('nullnull.locale', locale);
        let listReads = 0;
        let releaseMatch: (() => void) | undefined;
        const becameInactive = new Promise<void>((resolve) => {
          releaseMatch = resolve;
        });
        window.addEventListener('candidate-became-inactive', () => releaseMatch?.());
        const nativeFetch = window.fetch.bind(window);
        window.fetch = async (...args) => {
          const input = args[0];
          const url =
            typeof input === 'string'
              ? input
              : input instanceof URL
                ? input.href
                : input.url;
          const pathname = new URL(url, location.origin).pathname;
          const response = await nativeFetch(...args);
          if (/\/candidates$/.test(pathname) && response.ok) {
            const body = (await response.clone().json()) as {
              items: Array<{ status: string }>;
            };
            listReads += 1;
            return new Response(
              JSON.stringify({
                ...body,
                items: [
                  { ...body.items[0], status: listReads === 1 ? 'ACTIVE' : status },
                ],
              }),
              { status: response.status, headers: response.headers },
            );
          }
          if (/\/candidates\/[^/]+\/matches$/.test(pathname)) {
            await becameInactive;
            return new Response(
              JSON.stringify({
                candidateId: pathname.split('/').at(-2),
                state: 'NOT_ACTIVE',
                slots: [],
              }),
              { headers: { 'Content-Type': 'application/json' } },
            );
          }
          return response;
        };
      },
      { locale, status },
    );
    await page.goto('/trip/018f4a10-2c31-7d42-9a55-6b1f0c3e8a01/candidates');
    const card = page
      .getByRole('article')
      .filter({ has: page.getByRole('heading', { name: '연희동 카페거리' }) });
    await card
      .getByRole('button', { name: korean ? '일정에 추가' : 'Add to a day', exact: true })
      .click();
    const sheet = page.getByRole('dialog', {
      name: korean ? '어느 날에 추가할까요?' : 'Which day should it go on?',
    });
    await expect(sheet).toBeVisible();
    await page.evaluate(() =>
      window.dispatchEvent(new Event('candidate-became-inactive')),
    );
    await expect(
      sheet.getByRole('status').filter({
        hasText: korean
          ? '이미 일정에 담겼거나 후보에서 제거된 장소예요'
          : 'This place has already been scheduled or removed',
      }),
    ).toBeVisible();
    await expect(sheet.getByRole('list')).toHaveCount(0);
    await expect(
      sheet.getByText(korean ? '고를 수 있는 날짜가 없어요' : 'No dates to choose from', {
        exact: true,
      }),
    ).toHaveCount(0);
    await page.keyboard.press('Escape');
    const refresh = card.getByRole('button', {
      name: korean ? '목록 새로고침' : 'Refresh saved places',
    });
    await expect(refresh).toBeFocused();
    await page.keyboard.press('Enter');
    if (korean) {
      await expect(page.getByRole('heading', { name: '연희동 카페거리' })).toHaveCount(0);
      await expect(page.getByRole('heading', { level: 1 })).toBeFocused();
    } else {
      await expect(card.getByText('Already on the itinerary')).toBeVisible();
      await expect(
        card.getByRole('button', { name: 'Remove 연희동 카페거리 from saved' }),
      ).toBeFocused();
    }
    expect(mutations).toEqual([]);
    const widths = await page.evaluate(() => ({
      content: document.documentElement.scrollWidth,
      viewport: document.documentElement.clientWidth,
    }));
    expect(widths.content).toBeLessThanOrEqual(widths.viewport);
  });
}
