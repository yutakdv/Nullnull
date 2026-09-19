import { expect, test } from '@playwright/test';
import { createSeededTrip } from './seeded-trip.js';

// FE-505-T3's keyboard half: can the traveller reach the undo control with the
// keyboard alone, and is it visible, named and ringed when they get there.
//
// WHY A SEPARATE FILE rather than a case in responsive.spec.ts. That spec walks
// SCREENS, and the panel is one section of one screen — sixteen of those
// seventeen routes have no panel at all, so a clause asserted inside that loop
// would pass on absence fifteen times over. What it measures here is the panel,
// so the panel has to be the subject.
//
// WHY NOT "PRESS TAB N TIMES". The clause is reachability, not ordinal
// position, and the two come apart the moment anything above the panel gains or
// loses a control. Measured on this screen: the revert button is the 5th
// focusable of 76, behind "Edit itinerary" — a number that describes today's
// header, not a contract. responsive.spec.ts caps its walk at eight presses for
// its own reasons and the panel sits past that, which is why this clause could
// never have been proven there (the walk reads document.activeElement, and a
// <section> with no tabindex never enters it).
//
// keyboard-flow.spec.ts:176 and ConfirmDialog.tsx:306 record where the "press
// Tab and count" shape broke before: in a modal the browser sends focus through
// the document between stops, so even correct code fails an assertion written
// that way. The panel is not a modal, but the lesson is the same — count
// presses and you measure the layout, not the control.
//
// So reachability is asked directly: the button is focused, and one Tab is
// taken from the control immediately before it. One step depends on the
// neighbour, not on the length of everything above.
test.describe('FE-505-T3 the undo control is reachable by keyboard', () => {
  test('the revert button takes focus, shows a ring, and follows its neighbour in the tab order', async ({
    page,
  }) => {
    // A trip of this session's own. The panel only draws once the trip has a
    // decided optimization run behind it, which the seeded trip's history
    // provides; before that gate opened this spec could not have been written
    // without asserting into an empty screen.
    const tripPath = await createSeededTrip(page);
    await page.goto(tripPath);
    await page.waitForLoadState('networkidle');

    // GUARD 1 — the panel is on the screen at all.
    //
    // First, and failing loudly, because everything below is a statement about
    // the panel: if the run gate closes again, or the history stops carrying a
    // decided run, every assertion after this becomes a claim about a section
    // that is not there. That is the shape this repo has been caught by three
    // times, most recently in the gate failure this file's sibling had to fix.
    //
    // `getByRole('region')` rather than a `[role="region"]` selector: the role
    // is IMPLICIT on a <section> that has an accessible name, so the attribute
    // is absent from the DOM and a CSS query for it returns 0 whether the panel
    // is present or not. Measured — 0 with the panel rendered.
    const panel = page.getByRole('region', { name: /undone|되돌/i });
    await expect(
      panel,
      'the applied panel is not on the trip screen, so there is no undo to reach',
    ).toBeVisible();

    // GUARD 2 — the control this clause is about exists.
    //
    // Separate from guard 1 because they fail for different reasons: a panel
    // that drew in its REVERTED state has no button at all (AppliedPanel.tsx
    // renders none for `reverted`), and that is a different repair from the
    // panel being absent.
    const revert = panel.getByRole('button');
    await expect(
      revert,
      'the panel drew no control, so there is no undo to reach',
    ).toBeVisible();

    // (a) The button can hold focus.
    //
    // This is what `tabindex="-1"`, `inert`, `display:none` and an aria-hidden
    // ancestor all break, and none of them are visible in the markup of the
    // button itself. ConfirmDialog.tsx:292 lists the same set as the causes of
    // a focus() that silently does nothing.
    await revert.focus();
    await expect(revert, 'the undo control cannot hold focus').toBeFocused();

    // (b) Focused, it is on screen and has an accessible name. A control that
    // takes focus off-viewport is reachable and still unusable.
    const box = await revert.boundingBox();
    expect(box, 'the focused undo control has no box').not.toBeNull();
    expect(box?.width ?? 0).toBeGreaterThan(0);
    expect(box?.height ?? 0).toBeGreaterThan(0);
    const viewport = page.viewportSize();
    expect(
      box?.x ?? -1,
      'the focused undo control sits outside the viewport',
    ).toBeGreaterThanOrEqual(0);
    expect((box?.x ?? 0) + (box?.width ?? 0)).toBeLessThanOrEqual(
      (viewport?.width ?? 0) + 1,
    );
    await expect(revert).not.toHaveAccessibleName('');

    // (c) Focus is VISIBLE, compared against the button's own resting style.
    //
    // Not "does it have an outline or a shadow" — the panel's controls carry
    // decoration at rest, and accepting any of it is how an earlier version of
    // the equivalent check in responsive.spec.ts passed with the focus ring
    // deleted (measured there, all 16 screens green). What counts is a style
    // that CHANGES when focus arrives.
    const ring = await revert.evaluate((el) => {
      const read = () => {
        const s = getComputedStyle(el as HTMLElement);
        return `${s.outlineStyle}|${s.outlineWidth}|${s.outlineColor}|${s.boxShadow}`;
      };
      const focused = read();
      (el as HTMLElement).blur();
      const resting = read();
      (el as HTMLElement).focus();
      return { focused, resting };
    });
    expect(
      ring.focused,
      'the undo control looks the same focused as unfocused: no visible focus indicator',
    ).not.toBe(ring.resting);

    // (d) It is in the TAB ORDER, not merely focusable programmatically.
    //
    // `.focus()` proves the element accepts focus; it says nothing about
    // whether a keyboard user can ever arrive. One Tab from the control
    // immediately before it answers that, and one step is deliberate: a count
    // from the top of the page would encode how many controls the header has
    // today and break the next time one is added.
    const focusables = await page.evaluate(() => {
      const nodes = [
        ...document.querySelectorAll<HTMLElement>(
          'a[href], button:not([disabled]), input, select, textarea, [tabindex]:not([tabindex="-1"])',
        ),
      ];
      const index = nodes.findIndex((n) => n.closest('section[data-state]') !== null);
      return {
        index,
        previousText: index > 0 ? (nodes[index - 1]?.textContent ?? '').trim() : '',
      };
    });
    expect(
      focusables.index,
      'the undo control is not among the focusable elements of the page',
    ).toBeGreaterThan(0);

    const previous = page.getByRole('button', { name: focusables.previousText }).first();
    await previous.focus();
    await page.keyboard.press('Tab');
    await expect(
      revert,
      'Tab from the control before it does not reach the undo: it is out of the tab order',
    ).toBeFocused();
  });
});
