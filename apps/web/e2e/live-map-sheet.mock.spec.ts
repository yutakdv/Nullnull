import { expect, test } from '@playwright/test';

test.describe('FE-401 Live map sheet', () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('nullnull.locale', 'ko-KR');
    });
    await page.goto('/live');
  });

  test('keeps source state visible while the sheet moves down and back up', async ({
    page,
  }) => {
    const sourceState = page.getByTestId('live-persistent-state');
    const handle = page.getByTestId('live-sheet-drag-handle');
    await expect(sourceState).toBeVisible();
    await expect(handle).toHaveAttribute('aria-expanded', 'true');

    await page.getByTestId('live-sheet-content').evaluate((element) => {
      element.scrollTop = element.scrollHeight;
    });
    await expect(sourceState).toBeVisible();

    const box = await handle.boundingBox();
    expect(box).not.toBeNull();
    if (!box) return;
    const x = box.x + box.width / 2;
    const y = box.y + box.height / 2;

    await page.mouse.move(x, y);
    await page.mouse.down();
    await page.mouse.move(x, y + 130, { steps: 8 });
    await page.mouse.up();
    await expect(handle).toHaveAttribute('aria-expanded', 'false');
    await expect(sourceState).toBeVisible();

    const collapsedBox = await handle.boundingBox();
    expect(collapsedBox).not.toBeNull();
    if (!collapsedBox) return;
    const collapsedX = collapsedBox.x + collapsedBox.width / 2;
    const collapsedY = collapsedBox.y + collapsedBox.height / 2;
    await page.mouse.move(collapsedX, collapsedY);
    await page.mouse.down();
    await page.mouse.move(collapsedX, collapsedY - 130, { steps: 8 });
    await page.mouse.up();
    await expect(handle).toHaveAttribute('aria-expanded', 'true');
  });

  test('keeps REPLAY explicitly not-live at the 200% zoom-equivalent viewport', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 180, height: 800 });
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
          areas: Array<{ crowd: null | { state: string } }>;
        };
        return new Response(
          JSON.stringify({
            ...body,
            mode: 'REPLAY',
            areas: body.areas.map((area) => ({
              ...area,
              crowd: area.crowd ? { ...area.crowd, state: 'REPLAY' } : null,
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
    await page.getByTestId('live-sheet-content').evaluate((element) => {
      element.scrollTop = element.scrollHeight;
    });
    await expect(sourceState).toBeVisible();
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth),
    ).toBeLessThanOrEqual(
      await page.evaluate(() => document.documentElement.clientWidth),
    );
  });

  test('offers keyboard controls and keeps collapsed content out of focus order', async ({
    page,
  }) => {
    const handle = page.getByTestId('live-sheet-drag-handle');
    await handle.focus();
    await page.keyboard.press('ArrowDown');
    await expect(handle).toHaveAttribute('aria-expanded', 'false');
    await expect(page.getByTestId('live-sheet-content')).toHaveAttribute('inert', '');

    await page.keyboard.press('ArrowUp');
    await expect(handle).toHaveAttribute('aria-expanded', 'true');
  });
});
