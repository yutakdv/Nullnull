import { expect, test } from '@playwright/test';

const TRIP_ID = '018f4a10-2c31-7d42-9a55-6b1f0c3e8a01';
const SETUP_PATH = `/trip/${TRIP_ID}/optimize`;

test.describe('optimization setup sheet keyboard flow', () => {
  test('keeps browser focus inside the modal and closes with Escape', async ({
    page,
  }) => {
    await page.goto(SETUP_PATH);
    await page.waitForLoadState('networkidle');

    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    const initialFocus = await dialog.evaluate((element) => {
      const active = document.activeElement as HTMLElement | null;
      return {
        inside: element.contains(active),
        tag: active?.tagName ?? 'none',
        name: active?.getAttribute('aria-label') ?? active?.textContent?.trim() ?? '',
      };
    });
    expect(initialFocus.inside, JSON.stringify(initialFocus)).toBe(true);

    const background = page.locator('[inert]');
    await expect(background).toHaveAttribute('aria-hidden', 'true');
    expect(await background.evaluate((element) => (element as HTMLElement).inert)).toBe(
      true,
    );

    // `inert` must work in the browser, not only be present as markup: even a
    // direct focus attempt may not move focus into the dimmed trip.
    expect(
      await background.evaluate((element) => {
        const control = element.querySelector<HTMLElement>(
          'a[href], button, input, select, textarea, [tabindex]',
        );
        control?.focus();
        return element.contains(document.activeElement);
      }),
    ).toBe(false);
    expect(
      await dialog.evaluate((element) => element.contains(document.activeElement)),
    ).toBe(true);

    const controls = dialog.locator(
      'button:not(:disabled), a[href], input:not(:disabled)',
    );
    const first = controls.first();
    const last = controls.last();

    await dialog.focus();
    await page.keyboard.press('Tab');
    await expect(first).toBeFocused();
    await last.focus();
    await page.keyboard.press('Tab');
    await expect(first).toBeFocused();
    await page.keyboard.press('Shift+Tab');
    await expect(last).toBeFocused();

    await page.keyboard.press('Escape');
    await expect(page).toHaveURL(new RegExp(`/trip/${TRIP_ID}$`));
    await expect(dialog).toHaveCount(0);
  });

  test('offers a named close button inside the sheet', async ({ page }) => {
    await page.goto(SETUP_PATH);
    await page.waitForLoadState('networkidle');

    const dialog = page.getByRole('dialog');
    const close = dialog.getByRole('button', { name: /뒤로|Back/ });
    await expect(close).toBeVisible();
    await close.click();

    await expect(page).toHaveURL(new RegExp(`/trip/${TRIP_ID}$`));
    await expect(dialog).toHaveCount(0);
  });

  test('dismisses the sheet when its grabber is dragged down', async ({ page }) => {
    await page.goto(SETUP_PATH);
    await page.waitForLoadState('networkidle');

    const dialog = page.getByRole('dialog');
    const handle = page.getByTestId('optimize-sheet-drag-handle');
    await expect(handle).toBeVisible();
    const box = await handle.boundingBox();
    expect(box).not.toBeNull();
    if (box === null) return;

    const x = box.x + box.width / 2;
    const y = box.y + box.height / 2;
    await page.mouse.move(x, y);
    await page.mouse.down();
    await page.mouse.move(x, y + 150, { steps: 8 });
    await page.mouse.up();

    await expect(page).toHaveURL(new RegExp(`/trip/${TRIP_ID}$`));
    await expect(dialog).toHaveCount(0);
  });
});
