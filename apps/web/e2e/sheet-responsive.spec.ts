import { expect, test } from '@playwright/test';
import { overflow } from './overflow.js';
import { createSeededTrip } from './seeded-trip.js';

// FE-203-T3 / FE-601: the bottom sheets survive 360px and 200% zoom.
//
// responsive.spec.ts sweeps fourteen SCREENS, and a sheet is on none of them:
// every sheet in this app is a <dialog> that exists only after an interaction,
// so a spec that navigates and measures never renders one. That left the three
// densest surfaces in the product — a day list, a place list and a trip list,
// each inside a panel capped at 90dvh — with no layout coverage at the two
// widths the accessibility rule names.
//
// The gap became load-bearing when the three sheets started sharing their
// geometry (shared/ui/styles/sheet.module.css). One `composes` line now
// decides the padding, the radius and the max-height of all three, so a
// mistake there is a mistake everywhere, and nothing in the suite could see
// it: vitest runs with `css: false`, which means no unit test computes sheet
// geometry at all.
//
// 200% zoom is modelled as a 180px viewport, the same way responsive.spec.ts
// models it and for the same reason: doubling the text size halves the space.

/** Where a sheet lives: the test's own trip, or the feed. */
type SheetPath = 'own-trip' | '/feed';

/**
 * Opens a screen with a session and a trip of this test's own.
 *
 * The move-day sheet needs a trip whose items it can move, and a fixed fixture id is nobody's
 * trip against the real API (seeded-trip.ts has why). The feed gets the same trip so the picker
 * has one to list rather than an empty state.
 */
async function openWithSession(page: import('@playwright/test').Page, path: SheetPath) {
  const trip = await createSeededTrip(page);
  await page.goto(path === 'own-trip' ? trip : path);
  await page.waitForLoadState('networkidle');
  if (path === 'own-trip') {
    await page.getByRole('button', { name: 'Edit itinerary' }).click();
  }
}

/** Every sheet reachable without writing anything. */
const SHEETS = [
  {
    name: 'move day sheet',
    path: 'own-trip',
    open: /Move .* to another day/,
    shows: 'Which day should it move to?',
  },
  {
    // The id rides on the NAME rather than on the describe title, because the
    // title is a template over both sheets and the move-day sheet is not
    // FE-203's surface. Carried here, only the picker's two testcases spell
    // the id, which is the string check_test_reports.py reads.
    //
    // It covers the 360px and 200% zoom halves of FE-203-T3 and no more — the
    // clause's reduced-motion and focus-return halves are FE-203-T4, and
    // nothing in this file measures either (grep: 0 hits for reducedMotion and
    // for any focus-restore assertion).
    name: 'FE-203-T3 trip picker',
    path: '/feed',
    // The `+` on a feed card files the place into a trip; the picker asks
    // which one (FR-CAN-01). It only CHOOSES, so opening it writes nothing.
    //
    // The name is the button's label, which the app renders in the selected
    // locale — e2e runs in English, as keyboard-flow.spec.ts's `Move ...`
    // selectors already rely on. `tripAdd.idle` is that string.
    open: /Add to my trip/,
    shows: undefined,
  },
] as const;

for (const sheet of SHEETS) {
  test.describe(`${sheet.name} at 360px, the narrowest designed width`, () => {
    test(`${sheet.name} fits`, async ({ page }) => {
      await openWithSession(page, sheet.path);

      const trigger = page.getByRole('button', { name: sheet.open }).first();
      await expect(trigger, `${sheet.name} needs a trigger to open it`).toBeVisible();
      await trigger.click();

      const dialog = page.getByRole('dialog');
      await expect(dialog, `${sheet.name} should open`).toBeVisible();
      if (sheet.shows) await expect(dialog).toContainText(sheet.shows);

      const result = await overflow(page);
      expect(result.spilling, `${sheet.name} has content past the viewport`).toEqual([]);
      expect(result.clipped, `${sheet.name} clips text`).toEqual([]);

      // The panel must not grow past the screen: its list scrolls instead.
      //
      // Measured on the PANEL, which is the <dialog>'s child, not the dialog.
      // The dialog itself is the full-viewport backdrop — it measured exactly
      // 800 of 800 — so `dialog.height <= viewport` is true however the sheet
      // is styled. Written that way first, and the proof it was worthless is
      // that deleting `max-height: 90dvh` from the shared module left all four
      // tests green. The panel is 428px against a 720px cap, and removing the
      // cap is what this now catches.
      const panel = await dialog.evaluate((node) => {
        const child = node.firstElementChild as HTMLElement | null;
        return {
          height: child?.getBoundingClientRect().height ?? -1,
          viewport: window.innerHeight,
        };
      });
      expect(panel.height, 'the sheet panel should be found').toBeGreaterThan(0);
      expect(
        panel.height,
        'the sheet panel should cap its height and scroll its own list',
      ).toBeLessThanOrEqual(panel.viewport);
    });
  });

  test.describe(`${sheet.name} at 200% zoom, where the viewport halves`, () => {
    test.use({ viewport: { width: 180, height: 500 } });

    test(`${sheet.name} still fits`, async ({ page }) => {
      await openWithSession(page, sheet.path);

      const trigger = page.getByRole('button', { name: sheet.open }).first();
      await expect(trigger).toBeVisible();
      await trigger.click();

      const dialog = page.getByRole('dialog');
      await expect(dialog).toBeVisible();

      const result = await overflow(page);
      expect(result.spilling, `${sheet.name} spills at 200% zoom`).toEqual([]);
      // `clipped` is NOT asserted here. At 180px a long place name legitimately
      // wraps to many lines, and the check that catches a cut-off line also
      // catches a line that merely ran out of room to be one line. The 360px
      // case above is where clipping is a defect; here the question is only
      // whether the sheet still fits the axis the user cannot scroll.
      expect(
        result.documentWidth,
        `${sheet.name} forces horizontal page scrolling at 200% zoom`,
      ).toBeLessThanOrEqual(page.viewportSize()?.width ?? 0);
    });
  });
}
