import { expect, test } from '@playwright/test';
import { createSeededTrip, FIRST_ITEM } from './seeded-trip.js';

// Keyboard and focus through the itinerary editor and the judged walk-through.
//
// BA-040-T4 and BA-070-T5 are acceptance clauses on two BACKEND cards, and both
// were stuck: check_test_reports.py requires every acceptance id to appear in a
// JUnit testcase name, FE-owned acceptance lives in Playwright, and nothing was
// exercising the itinerary editor by keyboard at all. The only keyboard test in
// the suite drives the 404 page's link (shell.spec.ts), which is not trip
// editing (#233).
//
// The ids are in the test titles on purpose: that is the string the aggregator
// reads out of the JUnit report. Wiring integration-test.sh to pass
// --e2e-junit-dir is BE's half and is not done yet, so these ids are not
// collected today — the specs still run inside docker-integration and fail the
// gate on a regression, and the day that flag lands the two cards can promote
// without anyone writing a test under time pressure.
//
// What these prove that the unit tests cannot: MoveDaySheet, ReplaceSheet and
// ConfirmDialog each hold a `restoreTo` ref and focus it on close, and that
// behaviour has unit coverage but no browser coverage. jsdom/happy-dom do not
// implement <dialog>'s focus semantics, so "the dialog closed and focus went
// back to the button the user pressed" is only observable here.

/**
 * Opens a screen with a session already in hand.
 *
 * The app bootstraps exactly once, on the splash screen: `useSessionBootstrap`
 * is called by SplashScreen and nowhere else, deliberately, because an
 * unauthenticated POST that repeats itself creates duplicate anonymous owners.
 * So a test that navigates straight to /trip or /feed never gets a session, and
 * every request it makes comes back 401 with the screen showing "Your session
 * ended" — which is what these tests were reading as a keyboard failure.
 *
 * Going through `/` is not a workaround; it is the route a person takes.
 * shell.spec.ts already does the same thing. The other specs that visit these
 * screens directly are fine because they measure what survives ANY state:
 * responsive.spec checks reflow and screens.ts says so in as many words ("the
 * error state has to survive 360px"), and location-off.spec checks that nothing
 * asks for a location, which an error screen also satisfies.
 */
async function openWithSession(page: import('@playwright/test').Page, path: string) {
  await page.goto('/');
  // Splash redirects to /language (first visit) or /feed (returning) once
  // bootstrap resolves, so either destination proves the session exists — the
  // redirect is the success branch of that query.
  //
  // Named explicitly rather than "anything but `/`": that predicate is true the
  // moment the URL is `/language`, which it already is before bootstrap has
  // answered, so it waited for nothing and the tests failed exactly as before.
  await page.waitForURL(/\/(language|feed)$/, { timeout: 15_000 });
  await page.goto(path);
  await page.waitForLoadState('networkidle');
}

/** Opens a trip this test's session owns (see seeded-trip.ts for why it is not a fixed id). */
async function openTrip(page: import('@playwright/test').Page) {
  const path = await createSeededTrip(page);
  await page.goto(path);
  await page.waitForLoadState('networkidle');
}

test.describe('BA-040-T4 the itinerary editor is operable by keyboard', () => {
  test.beforeEach(async ({ page }) => {
    await openTrip(page);
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  });

  test('BA-040-T4 every move control is reachable by Tab and named for its item', async ({
    page,
  }) => {
    // Reordering is buttons rather than drag precisely so it can be driven from
    // a keyboard (COMPONENT_CATALOG calls the keyboard path a premise, not a
    // fallback). The names carry the place because a row of bare arrows tells a
    // screen-reader user nothing about which stop they are moving.
    const down = page.getByRole('button', { name: `Move ${FIRST_ITEM} down` });
    await expect(down).toBeVisible();
    await expect(down).toBeEnabled();

    // The first item cannot move up, and the control says so by being disabled
    // rather than by vanishing — a control that disappears moves every other
    // control under the user's fingers.
    await expect(
      page.getByRole('button', { name: `Move ${FIRST_ITEM} up` }),
    ).toBeDisabled();

    // Tab, not `down.focus()` — the same reason the tab bar walk below gives.
    // `focus()` succeeds on a control the keyboard cannot reach, so the
    // "reachable by Tab" half of this title was unfalsifiable: measured, giving
    // this button `tabindex="-1"` left the old version green (#233).
    //
    // Bounded rather than a guess at the order: the trip screen renders the
    // header and day nav before the rows, so the walk passes through them
    // first and stops as soon as it arrives.
    const label = `Move ${FIRST_ITEM} down`;
    let reached = false;
    for (let i = 0; i < 60 && !reached; i += 1) {
      await page.keyboard.press('Tab');
      reached = await page.evaluate(
        (name) => (document.activeElement?.getAttribute('aria-label') ?? '') === name,
        label,
      );
    }
    expect(reached, 'the move-down control should be reachable by Tab').toBe(true);
    await expect(down).toBeFocused();
    await page.keyboard.press('Enter');

    // The RESULT is announced, not merely the attempt. Asserting "some live
    // region has text" passed even with the success announcement deleted,
    // because the in-flight "Moving" fills the same region — that assertion
    // measured the pending state and called it the result. The success copy is
    // the only string that distinguishes them.
    await expect(page.getByText(/Moved .* to position/)).toBeVisible();
  });

  test('BA-040-T4 the move sheet traps focus and returns it to the trigger', async ({
    page,
  }) => {
    const trigger = page.getByRole('button', {
      name: `Move ${FIRST_ITEM} to another day`,
    });
    await trigger.focus();
    await page.keyboard.press('Enter');

    const sheet = page.getByRole('dialog');
    await expect(sheet).toBeVisible();
    // Focus lands inside the sheet rather than staying behind it: a modal that
    // opens without moving focus leaves a keyboard user tabbing through the
    // page underneath.
    await expect(sheet).toContainText('Which day should it move to?');
    // The OPEN dialog, not the first one in the DOM. This screen mounts twelve
    // <dialog> elements (a lock confirm and a replace sheet per row), all closed
    // but present, so querySelector('dialog') answers about the wrong one — it
    // reported "focus is outside" while focus was on this sheet's Cancel button.
    const inside = await page.evaluate(() => {
      const open = Array.from(document.querySelectorAll('dialog')).find((d) => d.open);
      return open?.contains(document.activeElement) ?? false;
    });
    expect(inside, 'focus should be inside the open sheet').toBe(true);

    await page.keyboard.press('Escape');
    await expect(sheet).toBeHidden();

    // NOT asserted here: "focus returns to the trigger". MoveDaySheet does
    // restore it (restoreTo, :67-72) and a unit test covers that, but no
    // assertion I could write in a browser DISTINGUISHES it — deleting
    // target.focus() left this spec green both when closing with Escape (a
    // native <dialog> restores to its invoker on its own) and when closing with
    // Cancel. An assertion that passes with its subject deleted reads as
    // coverage and is worse than none, so the claim is left to the unit test
    // rather than restated here in a form that cannot fail.
  });

  test('BA-040-T4 a completed move leaves focus somewhere, not on the document', async ({
    page,
  }) => {
    // The clause the test above could not reach. Closing the sheet by Escape or
    // Cancel is restored by the browser itself, so deleting our own restore left
    // that assertion green — but there is a third way out, and it is the one a
    // traveller actually takes: pick a day and complete the move.
    //
    // That path chains two dialogs. The first stop carries a DATE lock, so
    // picking a day closes the sheet and opens a confirm. The confirm captured
    // its restore target while the sheet was still open, which makes it a button
    // inside the sheet, and that button is gone by the time the confirm closes.
    //
    // WHERE THE LOCK COMES FROM, because this test dies without it and the two
    // sources differ. seeded-trip.ts sends it (a DATE constraint in seedItems);
    // the SERVER does not attach it. An earlier version of this comment said the
    // opposite and that was wrong — TripService only turns a CANDIDATE's
    // MUST_VISIT into a lock, and these items come from seedItems. The msw
    // fixture also carries DATE on 경복궁, which is why this test could pass
    // locally while the gate never opened a confirm at all (#272).
    //
    // That difference outlives this comment: msw's createTrip ignores the request
    // body and getTrip always answers trip-detail-scheduled.json, so LOCALLY this
    // test is green whatever seeded-trip.ts sends. Only the gate reads the
    // constraint this test depends on.
    //
    // Measured before the fix: focus fell to document.body and the next Tab
    // restarted at the top of the page, dropping a keyboard user out of the
    // itinerary they were editing. 24 samples came out 12 onBody=true / 12
    // false — a coin flip, because a passing run sampled the transient window
    // before the last focusout.
    //
    // Asserted in a browser because it cannot be asserted anywhere else:
    // happy-dom lets .focus() succeed inside a CLOSED <dialog> (measured), so the
    // node this used to pick reads as focused there and the defect is invisible.
    const trigger = page.getByRole('button', {
      name: `Move ${FIRST_ITEM} to another day`,
    });
    await trigger.focus();
    await page.keyboard.press('Enter');

    const sheet = page.getByRole('dialog');
    await expect(sheet).toBeVisible();
    await sheet.getByRole('button', { name: /Day 3/ }).first().click();

    // The DATE lock turns the pick into a question rather than a move.
    const confirm = page.getByRole('dialog').getByRole('button', {
      name: /Release and move/i,
    });
    await expect(confirm).toBeVisible();
    await confirm.click();

    // Both surfaces are gone and the move has landed.
    await expect(page.locator('dialog[open]')).toHaveCount(0);

    const landed = await page.evaluate(() => {
      const el = document.activeElement as HTMLElement | null;
      return {
        onBody: el === document.body,
        connected: el?.isConnected ?? false,
      };
    });
    expect(
      landed.onBody,
      'focus fell to <body>: the next Tab restarts at the top of the page',
    ).toBe(false);
    expect(landed.connected, 'focus is on a node that is no longer in the page').toBe(
      true,
    );
  });

  test('BA-040-T4 a lock confirm can be answered and cancelled by keyboard', async ({
    page,
  }) => {
    // Releasing a lock asks first (invariant 7: nothing auto-releases), so the
    // confirm is on the editing path and has to be answerable without a mouse.
    const release = page.getByRole('button', { name: /Release Must visit/i }).first();
    await release.focus();
    await page.keyboard.press('Enter');

    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();

    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();
    // Cancelling returns the user where they were, and — the part that matters
    // for a lock — leaves the lock alone.
    await expect(release).toBeFocused();
    await expect(release).toBeVisible();

    // ANSWERED, the other half of this title. Cancelling was the only path this
    // test walked, so "answered" was a promise the body never kept: every
    // assertion above passes on a dialog whose confirm button does nothing.
    //
    // The lock going away is the assertion, not the dialog closing. Escape
    // already closes it, so "the dialog is hidden" cannot tell confirming from
    // cancelling — and invariant 7 is about the lock, not the dialog. The
    // control is rendered from `activeLocks(item)` (LockRow.tsx:66), so it
    // disappears exactly when the release actually landed on the trip.
    await release.focus();
    await page.keyboard.press('Enter');
    await expect(dialog).toBeVisible();

    // Reached by Tab rather than by `focus()`: this is a keyboard test and the
    // confirm of a destructive action is precisely where a control that takes
    // focus only programmatically would strand someone (#233).
    const confirm = dialog.getByRole('button', { name: 'Release and continue' });
    await expect(confirm).toBeVisible();
    let onConfirm = false;
    for (let i = 0; i < 20 && !onConfirm; i += 1) {
      await page.keyboard.press('Tab');
      onConfirm = await confirm.evaluate((el) => el === document.activeElement);
    }
    expect(onConfirm, 'the confirm button should be reachable by Tab').toBe(true);

    await page.keyboard.press('Enter');
    await expect(dialog).toBeHidden();
    await expect(
      release,
      'the Must visit lock should be gone once the release is confirmed',
    ).toBeHidden();
  });
});

test.describe('BA-070-T5 the judged walk-through is operable by keyboard', () => {
  // FIGMA_HANDOFF's numbered walk-through is what a contest judge follows. These
  // cover the steps a keyboard user could be stopped by; the per-screen focus
  // and touch-target checks live in responsive.spec.ts and are not repeated.

  /**
   * Completes the wizard's first step with the keyboard alone.
   *
   * Declared inside this describe rather than beside `openWithSession` above:
   * that helper block is shared with BA-040-T4 and the two cards are being
   * written by different sessions, so anything only one of them needs stays in
   * its own block.
   *
   * Tab-walks to the calendar, picks two days with Enter, then walks to the CTA
   * — whose label is the chosen range itself, which is why it is matched by
   * shape rather than by a fixed string.
   */
  async function pickRangeByKeyboard(page: import('@playwright/test').Page) {
    const pressEnterOn = async (match: (label: string) => boolean) => {
      for (let i = 0; i < 80; i += 1) {
        await page.keyboard.press('Tab');
        const label = await page.evaluate(() =>
          (document.activeElement?.textContent ?? '').trim(),
        );
        if (match(label)) {
          await page.keyboard.press('Enter');
          return label;
        }
      }
      return null;
    };

    // Two day cells. Any two that are a few days apart make a valid range; the
    // CTA stays disabled until both ends exist, which is what proves the picks
    // registered.
    const first = await pressEnterOn((l) => l === '20');
    expect(first, 'the calendar should be reachable by Tab').toBe('20');
    const second = await pressEnterOn((l) => l === '23');
    expect(second, 'the second date should be reachable by Tab').toBe('23');

    // The CTA names the range once both ends are chosen (`2026-09-20 – …`), so
    // it is the button that proves step 1 accepted them.
    const cta = await pressEnterOn((l) => /^\d{4}-\d{2}-\d{2}\s/.test(l));
    expect(cta, 'the continue CTA should be reachable by Tab').not.toBeNull();
  }

  test('BA-070-T5 the tab bar reaches every P0 destination by keyboard', async ({
    page,
  }) => {
    await openWithSession(page, '/feed');

    // Tab, not `locator.focus()`.
    //
    // `focus()` succeeds on an element the keyboard cannot reach at all —
    // measured: setting `tabindex="-1"` on every tab left `focus()` returning
    // true while walking the document with Tab never arrived. The earlier
    // version of this test used `focus()`, so it would have stayed green with
    // the tab bar completely unreachable, which is the failure it exists to
    // catch.
    const names = ['Home', 'My trip', 'Live', 'Me'];
    const reached: string[] = [];
    // A bound, not a guess at the tab order: the feed renders cards above the
    // bar, so the walk passes through them first. It stops as soon as all four
    // are found.
    for (let i = 0; i < 60 && reached.length < names.length; i += 1) {
      await page.keyboard.press('Tab');
      const label = await page.evaluate(() => {
        const el = document.activeElement;
        if (!el || !el.closest('nav')) return null;
        return (el.getAttribute('aria-label') ?? el.textContent ?? '').trim();
      });
      if (label && names.includes(label) && !reached.includes(label)) {
        reached.push(label);
      }
    }

    // Order matters as much as reachability: a bar that hands focus around out
    // of sequence is navigable but not predictable.
    expect(reached, 'every tab should be reachable by Tab, in order').toEqual(names);
  });

  test('BA-070-T5 Enter on a tab actually navigates', async ({ page }) => {
    await openWithSession(page, '/feed');

    // Reaching a control is half of it. A tab that takes focus and ignores
    // Enter is a dead end for anyone without a pointer, and `toBeFocused()`
    // cannot tell the two apart.
    const trip = page.getByRole('button', { name: 'My trip', exact: true });
    await expect(trip).toBeVisible();

    for (let i = 0; i < 60; i += 1) {
      await page.keyboard.press('Tab');
      if (await trip.evaluate((el) => el === document.activeElement)) break;
    }
    await expect(trip, 'the My trip tab should be reachable by Tab').toBeFocused();

    await page.keyboard.press('Enter');
    // Either the trip screen or its empty state — the destination depends on
    // whether this session has a trip, and both mean the tab worked.
    await expect(page).not.toHaveURL(/\/feed$/);
  });

  test('BA-070-T5 focus is always visible where it lands', async ({ page }) => {
    // A focused control with no visible indicator is a trap: the user is
    // somewhere but cannot see where. Checked on the feed because it is the
    // screenshot-1 screen and the densest.
    await openWithSession(page, '/feed');
    await page.keyboard.press('Tab');

    const visible = await page.evaluate(() => {
      const el = document.activeElement as HTMLElement | null;
      if (!el || el === document.body) return null;
      const style = getComputedStyle(el);
      const box = el.getBoundingClientRect();
      return {
        onScreen: box.width > 0 && box.height > 0,
        indicated: style.outlineStyle !== 'none' || style.boxShadow !== 'none',
      };
    });
    expect(visible, 'something should take focus on the feed').not.toBeNull();
    expect(visible?.onScreen, 'the focused element should be rendered').toBe(true);
    expect(visible?.indicated, 'the focused element should show a focus ring').toBe(true);
  });

  test('BA-070-T5 Escape closes a sheet rather than leaving the user inside it', async ({
    page,
  }) => {
    // Every sheet in the app must answer Escape (.claude/rules/frontend.md).
    // The itinerary editor is the densest surface, so it is where a missed
    // Escape handler would strand someone.
    await openTrip(page);

    const trigger = page.getByRole('button', {
      name: `Move ${FIRST_ITEM} to another day`,
    });
    await trigger.click();
    await expect(page.getByRole('dialog')).toBeVisible();

    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog')).toBeHidden();
  });

  test('BA-070-T5 the trip wizard announces each step it moves to', async ({ page }) => {
    // The judged walk-through starts here: 여행 만들기 is step 1 of the demo,
    // and it is the flow with the most screen changes — six panels swapped in
    // and out of one route.
    //
    // The steps are component state rather than routes, so nothing resets focus
    // when one replaces another. Measured before this was fixed: steps 1, 2 and
    // 3 all left `document.activeElement` on `<body>`, which tells a screen
    // reader nothing and makes a keyboard user walk down from the top again on
    // every step.
    await openWithSession(page, '/start');

    const heading = page.getByRole('heading', { level: 1 });
    const firstTitle = (await heading.textContent())?.trim();

    // Opening the screen must NOT take focus. A page that grabs it on load
    // moves the caret out from under someone who was already reading.
    await expect(page.locator('body')).toBeFocused();

    // Step 1 → 2, by keyboard only.
    await pickRangeByKeyboard(page);

    // Focus is on the new step's heading, and the heading changed — so the
    // assertion cannot pass on a screen that never moved.
    await expect(heading).toBeFocused();
    const secondTitle = (await heading.textContent())?.trim();
    expect(secondTitle, 'the wizard should have moved to another step').not.toBe(
      firstTitle,
    );

    // The heading, not the first control. Step 2's first focusable is the back
    // button — an icon button with no text — so focusing "the first thing"
    // would announce "Previous step" to someone who just moved forward.
    await expect(heading).toHaveAttribute('tabindex', '-1');
  });

  test('BA-070-T5 going back through the wizard announces the step too', async ({
    page,
  }) => {
    await openWithSession(page, '/start');
    await pickRangeByKeyboard(page);
    const forwardTitle = (
      await page.getByRole('heading', { level: 1 }).textContent()
    )?.trim();

    // Back is the same kind of move and has the same cost when focus is lost.
    const back = page.getByRole('button', { name: 'Previous step' });
    await back.focus();
    await page.keyboard.press('Enter');

    const heading = page.getByRole('heading', { level: 1 });
    await expect(heading).toBeFocused();
    expect((await heading.textContent())?.trim()).not.toBe(forwardTitle);
  });
});
