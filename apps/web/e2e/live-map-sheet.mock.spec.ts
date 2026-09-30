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
    const sheetContent = page.getByTestId('live-sheet-content');
    const scrollable = await sheetContent.evaluate(
      (element) => element.scrollHeight > element.clientHeight,
    );
    expect(scrollable).toBe(true);
    await sheetContent.evaluate((element) => {
      element.scrollTop = element.scrollHeight;
    });
    await expect(sourceState).toBeInViewport();
  });

  test('FE-401-T3 floats search over the map and restores the draggable list', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 360, height: 800 });
    const map = page.getByRole('region', { name: '카카오 지도' });
    const search = page.getByRole('searchbox', { name: 'Live 장소 검색' });
    const sourceState = page.getByTestId('live-persistent-state');
    const handle = page.getByTestId('live-sheet-drag-handle');
    const sheet = page.getByRole('region', { name: '라이브 여행지 목록' });
    await expect(sourceState).toBeVisible();
    const mapBounds = await map.boundingBox();
    const searchBounds = await search.boundingBox();
    const stateBounds = await sourceState.boundingBox();
    const sheetBounds = await sheet.boundingBox();
    const titleBounds = await page.getByRole('heading', { name: '라이브' }).boundingBox();
    expect(mapBounds).not.toBeNull();
    expect(searchBounds).not.toBeNull();
    expect(stateBounds).not.toBeNull();
    expect(sheetBounds).not.toBeNull();
    expect(titleBounds?.width).toBe(1);
    expect(searchBounds!.y).toBeGreaterThanOrEqual(mapBounds!.y);
    expect(searchBounds!.y).toBeLessThan(mapBounds!.y + mapBounds!.height);
    expect(searchBounds!.y + searchBounds!.height).toBeLessThan(stateBounds!.y);
    expect(stateBounds!.y + stateBounds!.height).toBeLessThan(sheetBounds!.y);
    expect(sheetBounds!.y).toBeGreaterThan(400);
    const viewTabBounds = await page
      .getByRole('tab', { name: '현재 여행지' })
      .boundingBox();
    const handleBounds = await handle.boundingBox();
    expect(handleBounds?.height).toBeGreaterThanOrEqual(44);
    expect(Math.round(viewTabBounds!.y - sheetBounds!.y)).toBe(44);
    const tabTextInset = await page
      .getByRole('tab', { name: '현재 여행지' })
      .evaluate((element) => {
        const text = document.createRange();
        text.selectNodeContents(element);
        return Math.round(
          text.getBoundingClientRect().y - element.getBoundingClientRect().y,
        );
      });
    expect(tabTextInset).toBeLessThanOrEqual(4);
    const searchRadius = await search.evaluate((element) =>
      Number.parseFloat(getComputedStyle(element.parentElement!).borderTopLeftRadius),
    );
    expect(searchRadius).toBeGreaterThanOrEqual(searchBounds!.height / 2);
    const iconInset = await search.evaluate((element) => {
      const field = element.parentElement!;
      return Math.round(
        field.querySelector('svg')!.getBoundingClientRect().left -
          field.getBoundingClientRect().left,
      );
    });
    expect(iconInset).toBe(16);
    await expect(handle).toHaveAttribute('aria-expanded', 'true');
    await handle.focus();
    await page.keyboard.press('ArrowDown');
    await expect(handle).toHaveAttribute('aria-expanded', 'false');
    await expect(sheet).toHaveAttribute('data-snap', 'collapsed');
    await expect(page.getByTestId('live-sheet-content')).toHaveAttribute('inert', '');
    const tabBar = page.getByRole('navigation', { name: '주요 메뉴' });
    await expect
      .poll(async () => {
        const collapsedBounds = await sheet.boundingBox();
        const tabsBounds = await tabBar.locator('..').boundingBox();
        return Math.round(tabsBounds!.y - collapsedBounds!.y);
      })
      .toBe(48);
    await page.keyboard.press('ArrowUp');
    await expect(handle).toHaveAttribute('aria-expanded', 'true');
    await expect
      .poll(async () => Math.round((await sheet.boundingBox())!.y))
      .toBe(Math.round(sheetBounds!.y));
    const bounds = await handle.boundingBox();
    expect(bounds).not.toBeNull();
    await page.mouse.move(bounds!.x + bounds!.width / 2, bounds!.y + 16);
    await page.mouse.down();
    await page.mouse.move(bounds!.x + bounds!.width / 2, bounds!.y + 110, {
      steps: 5,
    });
    await page.mouse.up();
    await expect(handle).toHaveAttribute('aria-expanded', 'false');
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
