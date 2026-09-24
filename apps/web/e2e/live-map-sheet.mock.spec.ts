import { expect, test } from '@playwright/test';

test.describe('FE-401 Live map and list', () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('nullnull.locale', 'ko-KR');
    });
    await page.route('https://dapi.kakao.com/**', (route) => route.abort());
    await page.goto('/live');
  });

  test('FE-401-T1 keeps source state and the list usable when the map SDK fails', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 360, height: 400 });
    const sourceState = page.getByTestId('live-persistent-state');
    await expect(sourceState).toBeVisible();
    await expect(page.getByRole('region', { name: '카카오 지도' })).toBeVisible();
    await expect(page.getByText('지도를 불러오지 못했어요')).toBeVisible();
    await expect(page.getByRole('region', { name: '라이브 여행지 목록' })).toBeVisible();
    await expect(page.getByTestId('live-sheet-drag-handle')).toHaveCount(0);
    const main = page.getByRole('main');
    const scrollable = await main.evaluate(
      (element) => element.scrollHeight > element.clientHeight,
    );
    expect(scrollable).toBe(true);
    await main.evaluate((element) => {
      element.scrollTop = element.scrollHeight;
    });
    await expect(sourceState).toBeInViewport();
  });

  test('FE-403-T1 FE-403-T3 keeps REPLAY explicitly not-live and keyboard-operable at the 200% zoom-equivalent viewport', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 180, height: 400 });
    await page.addInitScript(() => {
      localStorage.setItem('nullnull.locale', 'en-US');
      const nativeFetch = window.fetch.bind(window);
      window.fetch = async (...args) => {
        const response = await nativeFetch(...args);
        const requestUrl =
          typeof args[0] === 'string'
            ? args[0]
            : args[0] instanceof URL
              ? args[0].href
              : args[0].url;
        if (!requestUrl.includes('/live/areas') || !response.ok) return response;
        const body = (await response.clone().json()) as {
          mode: string;
          areas: Array<{
            crowd: {
              state: string;
              provenance: { sourceState: string };
            };
          }>;
        };
        return new Response(
          JSON.stringify({
            ...body,
            mode: 'REPLAY',
            areas: body.areas.map((area) => ({
              ...area,
              crowd: {
                ...area.crowd,
                state: 'REPLAY',
                provenance: { ...area.crowd.provenance, sourceState: 'REPLAY' },
              },
            })),
          }),
          { headers: response.headers, status: response.status },
        );
      };
    });
    await page.goto('/live');

    const sourceState = page.getByTestId('live-persistent-state');
    await expect(sourceState).toContainText(/replay/i);
    await expect(sourceState).toContainText(/not live/i);
    // The observation in Seoul time (05:00Z is 14:00 KST), never the raw instant.
    // Observed and generated fall in the same minute here, so which of the two
    // the badge names is pinned by FE-403-T1 in live.test.tsx, whose approved
    // replay fixture keeps them minutes apart.
    await expect(sourceState).toContainText('Observed 9/20, 2:00 PM');
    await expect(sourceState).not.toContainText('2026-09-20T05:00:00Z');
    await expect(sourceState).not.toContainText('2026-09-20T05:00:07Z');
    const area = page.getByRole('button', { name: /광화문·덕수궁/ });
    await area.focus();
    await expect(area).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(area).toHaveAttribute('aria-expanded', 'true');
    const main = page.getByRole('main');
    const scrollable = await main.evaluate(
      (element) => element.scrollHeight > element.clientHeight,
    );
    expect(scrollable).toBe(true);
    await main.evaluate((element) => {
      element.scrollTop = element.scrollHeight;
    });
    await expect(sourceState).toBeVisible();
    await expect(sourceState).toBeInViewport();
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth),
    ).toBeLessThanOrEqual(
      await page.evaluate(() => document.documentElement.clientWidth),
    );
  });

  test('FE-403-T3 expands and collapses an area with the keyboard', async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 800 });
    const area = page.getByRole('button', { name: /광화문·덕수궁/ });
    await area.focus();
    await expect(area).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(area).toHaveAttribute('aria-expanded', 'true');
    await expect(page.getByRole('link', { name: /경복궁 Live 정보 보기/ })).toBeVisible();
    await page.keyboard.press('Enter');
    await expect(area).toHaveAttribute('aria-expanded', 'false');
  });

  test('FE-401-T3 selects actual place coordinates and opens its map marker with the keyboard', async ({
    page,
  }) => {
    await page.addInitScript(() => {
      // The SDK is the external boundary; our component still creates the map and markers.
      Object.assign(window, {
        kakao: {
          maps: {
            LatLng: class {
              constructor(
                public latitude: number,
                public longitude: number,
              ) {}
            },
            Map: class {
              constructor(
                public container: HTMLElement,
                options: { center: unknown },
              ) {
                container.dataset.center = JSON.stringify(options.center);
              }
            },
            CustomOverlay: class {
              constructor(private options: { content: HTMLElement; position: unknown }) {}
              setMap(map: { container: HTMLElement } | null) {
                if (map) map.container.append(this.options.content);
                else this.options.content.remove();
              }
            },
            load: (callback: () => void) => callback(),
          },
        },
      });
    });
    await page.goto('/live');
    const map = page.getByRole('region', { name: '카카오 지도' });
    await expect(map.getByRole('button')).toHaveCount(0);
    await page.getByRole('searchbox', { name: 'Live 장소 검색' }).fill('경복궁');
    const select = page.getByRole('button', { name: '경복궁 지도에서 보기' });
    await select.focus();
    await page.keyboard.press('Enter');
    await expect(select).toHaveAttribute('aria-pressed', 'true');
    const marker = map.getByRole('button', { name: '경복궁', exact: true });
    await expect(marker).toBeVisible();
    await expect(map.locator('[data-center]')).toHaveAttribute(
      'data-center',
      JSON.stringify({ latitude: 37.579617, longitude: 126.977041 }),
    );
    await marker.focus();
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/\/live\/places\/018f4b20-1a44-7e11-9c02-5d7e3f1a2b01$/);
    await expect(page.getByRole('heading', { level: 1, name: '경복궁' })).toBeVisible();
  });

  test('FE-401-T3 opens a named search result link with the keyboard', async ({
    page,
  }) => {
    const search = page.getByRole('searchbox', { name: 'Live 장소 검색' });
    await search.fill('경복궁');
    const searchResult = page.getByRole('link', { name: '경복궁 Live 정보 보기' });
    await expect(searchResult).not.toContainText('›');
    await expect(searchResult).toHaveCSS('font-weight', '500');
    await searchResult.focus();
    await expect(searchResult).toBeFocused();
    await page.keyboard.press('Enter');

    await expect(page).toHaveURL(/\/live\/places\/018f4b20-1a44-7e11-9c02-5d7e3f1a2b01$/);
    await expect(page.getByRole('heading', { level: 1, name: '경복궁' })).toBeVisible();
    await expect(page.getByRole('navigation', { name: '주요 메뉴' })).toHaveCount(0);

    const appBarTitle = page.getByText('장소 혼잡 정보', { exact: true });
    await expect(appBarTitle).toHaveCSS('font-size', '20px');

    const fixedAction = page.locator('[data-fixed="true"]');
    await expect(fixedAction).toContainText('대표 여행에 담기');
    await expect(fixedAction).toContainText(
      '후보 장소로만 담아요. 여행 일정은 그대로예요',
    );
    await expect(fixedAction).toHaveCSS('position', 'fixed');
  });
});
