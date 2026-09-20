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
// reads out of the JUnit report. That wiring has landed (#233):
// playwright.config.ts writes JUnit in CI, compose.integration.yml exports the
// directory, and integration-test.sh passes --e2e-junit-dir with the
// aggregation step moved after E2E so the report exists when it is read.
// So an id in a title here is collected, and a title that names a clause it
// does not prove now claims that clause in the card ledger.
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
  await page.getByRole('button', { name: 'Edit itinerary' }).click();
  await expect(page).toHaveURL(/\/edit$/);
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

  test('BA-040-T4 the move sheet traps focus', async ({ page }) => {
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

    // The TRAP, which the assertion above does not reach. "Focus is inside"
    // is true of a non-modal dialog too: MoveDaySheet focuses a control of its
    // own on open, so replacing `showModal()` with `show()` left every
    // assertion here green while the sheet stopped being modal at all
    // (measured: `dialog.matches(':modal')` went false, radius 0). The title
    // says "traps", and trapping is about what happens when focus tries to
    // LEAVE.
    //
    // Escape attempt rather than a Tab walk. A Tab walk cannot tell the two
    // apart here — measured: on the real, modal sheet the fourth Tab already
    // reports focus outside the <dialog> (the browser passes through the
    // document before cycling back), so "some Tab lands outside" is true of
    // the healthy sheet as well and an assertion built on it fails on correct
    // code. Focusing an outside control is what modality actually forbids:
    // elements outside the top layer are inert, so the call is a no-op and
    // focus stays put. Measured both ways — modal: STAYED-IN, non-modal:
    // ESCAPED-TO the itinerary behind the sheet.
    const escaped = await page.evaluate(() => {
      const open = Array.from(document.querySelectorAll('dialog')).find((d) => d.open);
      const outside = Array.from(document.querySelectorAll<HTMLElement>('button')).find(
        (button) => !open?.contains(button) && !(button as HTMLButtonElement).disabled,
      );
      if (!outside) return 'no control outside the sheet to try';
      outside.focus();
      return (open?.contains(document.activeElement) ?? false)
        ? null
        : `focus escaped to "${(document.activeElement?.textContent ?? '').trim().slice(0, 24)}"`;
    });
    expect(escaped, 'the sheet should hold focus against a control behind it').toBeNull();

    await page.keyboard.press('Escape');
    await expect(sheet).toBeHidden();

    // HALF OF THIS TITLE IS MEASURED, half is not, on purpose. "traps focus"
    // is now the escape attempt above; "returns it to the trigger" stays
    // unasserted for the reason below, and whether the title should drop that
    // clause is BE's call, asked on #233. Left deliberately rather than
    // overlooked.
    //
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

  // NOT BA-070-T5. That clause is "the ring stays visible across repeated
  // Tab presses", and this presses once. Worse, `boxShadow !== 'none'` passes
  // on a decorative shadow the control carries at rest, so this cannot fail
  // for the reason the clause cares about. The id now sits on
  // responsive.spec.ts's "puts focus on something visible", which presses
  // eight times and compares each stop against its OWN resting style.
  // Kept because a first stop with no indicator at all is still worth failing.
  test('the feed gives its first stop a visible focus ring', async ({ page }) => {
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

    // The rows have to be on screen before the walk starts. `openTrip` waits
    // for networkidle, which is when the REQUEST settled, not when React has
    // rendered what came back — measured: at that moment not one button on the
    // page had an aria-label yet, so the walk below ran past a screen with no
    // move controls in it and reported the trigger unreachable. The sibling
    // describe waits for the heading in its beforeEach for this reason; this
    // one has no beforeEach, so the wait is stated here.
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await expect(
      page.getByRole('button', { name: `Move ${FIRST_ITEM} to another day` }),
    ).toBeVisible();

    // Opened by keyboard, not `trigger.click()`. The clause is that a sheet a
    // KEYBOARD user opened answers Escape, and a click opens it from a state no
    // keyboard user is ever in — the pointer path leaves focus wherever it was,
    // so the sheet that Escape then closes is not the one under test. Same
    // reason the reachability test above walks with Tab (#233).
    //
    // Bound 40, not the 60 the tests above use: measured, this trigger is the
    // 14th stop on this screen, and those 60s were sized for their own screens.
    const label = `Move ${FIRST_ITEM} to another day`;
    let reached = false;
    const walked: string[] = [];
    for (let i = 0; i < 40 && !reached; i += 1) {
      await page.keyboard.press('Tab');
      const here = await page.evaluate(() => {
        const el = document.activeElement as HTMLElement | null;
        return (el?.getAttribute('aria-label') ?? el?.textContent ?? '')
          .trim()
          .slice(0, 24);
      });
      walked.push(here);
      reached = here === label;
    }
    // Names where the walk went, not just that it failed: "unreachable" alone
    // cannot tell a missing control from a walk that started on a screen which
    // had not rendered yet, and this test hit the second case once already.
    expect(
      reached,
      `the move trigger should be reachable by Tab; walked ${walked.join(' → ')}`,
    ).toBe(true);
    await page.keyboard.press('Enter');
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

  test('BA-070-T5 a trip can be created from start to itinerary by keyboard alone', async ({
    page,
  }) => {
    // The clause BE settled #233 on: not "each step announces itself" but the
    // walk-through finishing. The two tests above prove one transition each and
    // both stop at step 2 — a wizard that moves correctly from 1 to 2 and then
    // strands a keyboard user on step 3 satisfies them and fails the judged
    // demo, which is the run this card exists for.
    //
    // Every step waits on the thing that step produces rather than on a
    // duration: a `waitForTimeout` here would pass whenever the machine was
    // fast and fail whenever it was loaded, and #272 spent a day on exactly
    // that shape.
    await openWithSession(page, '/start');

    // Step 1 → 2. Dates, then the CTA that names the range back.
    await pickRangeByKeyboard(page);
    const heading = page.getByRole('heading', { level: 1 });
    await expect(heading).toBeFocused();

    // Tab to a button whose accessible name starts with `want`, then Enter.
    // Matched on the button's OWN name: an earlier version compared the
    // focused element's textContent, which on this screen matched the <main>
    // wrapper (its text contains every child's), so Enter fired on a container
    // and the walk sat on step 3 forever.
    const pressButton = async (want: string) => {
      for (let i = 0; i < 80; i += 1) {
        await page.keyboard.press('Tab');
        const name = await page.evaluate(() => {
          const el = document.activeElement as HTMLElement | null;
          if (!el || el.tagName !== 'BUTTON') return '';
          return (el.getAttribute('aria-label') ?? el.textContent ?? '').trim();
        });
        if (name.startsWith(want)) {
          await page.keyboard.press('Enter');
          return true;
        }
      }
      return false;
    };

    // Step 2 → 3. Interests are optional, so Next alone carries the step.
    expect(await pressButton('Next'), 'step 2 should offer Next to a keyboard').toBe(
      true,
    );
    await expect(heading).toHaveText(/planned already/i);

    // Step 3 → creation. "Nothing yet" is the branch that needs no further
    // input, so it is the shortest honest path through the wizard; picking it
    // does not advance on its own, Next does.
    expect(
      await pressButton('Nothing yet'),
      'step 3 should offer its planning levels to a keyboard',
    ).toBe(true);
    expect(
      await pressButton('Next'),
      'step 3 should offer Next once a level is picked',
    ).toBe(true);

    // Arrived: the wizard handed off to a trip of its own making.
    await page.waitForURL(/\/trip\/[^/]+$/, { timeout: 15_000 });
    // The itinerary itself, not the loading state that precedes it — the route
    // renders "Loading your itinerary" under the same <h1> first, so asserting
    // on the URL alone would call a spinner a finished trip.
    await expect(heading).not.toHaveText(/loading/i, { timeout: 15_000 });
    // A control the itinerary only renders once it has one: the day filters.
    // "Add a place" was the first choice and it is a <Link>, not a button — a
    // reminder that the role belongs to the element, not to how the thing reads.
    await expect(page.getByRole('button', { name: 'Day 1' })).toBeVisible();
  });
});
