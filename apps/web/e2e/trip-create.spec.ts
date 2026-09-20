import { expect, test, type Page } from '@playwright/test';
import { overflow } from './overflow.js';

// The trip-creation wizard in a real browser (FE-103, FR-TRC-05).
//
// Nothing in this suite touched /start before. The wizard is six steps now —
// dates, interests, planning level, the input-method branch, manual entry
// (S02-4C-C `438:3199`) and confirm (S02-5C `438:3259`) — and
// .claude/rules/frontend.md asks for a keyboard and focus check on exactly this
// kind of flow: "라우팅, 검색, sheet/dialog, 여행 생성, 후보 저장, 일정 교체,
// 최적화 흐름을 바꾸면 Playwright E2E와 키보드 접근성 검사를 함께 추가한다".
//
// What these prove that the component tests cannot. happy-dom reports layout as
// zeros, so every assertion about a real box — the 44px touch target, the 360px
// reflow, a focus ring that is actually painted — is only observable here. The
// component tests already cover what the request carries; this file does not
// restate that.
//
// The wizard renders at /start without going through the splash screen, unlike
// /trip and /feed: steps 1-5 are a local draft and the only server calls are
// searchPlaces and the final createTrip, so there is no bootstrap to wait on.
// Confirmed in a browser rather than assumed.
//
// The copy asserted here is ENGLISH because a fresh browser profile has no
// stored language and the app falls back to en-US. Reading Korean out of a
// hand-driven browser was misleading: that profile had already chosen 한국어,
// and the first run of this file failed every test on the very first heading.
// shell.spec.ts drives the language screen; these tests take the default.

/** The narrowest supported width, which is also the config's viewport. */
const NARROW = 360;

const MANUAL_TITLE = "What's already planned?";
const CONFIRM_TITLE = "Here's what you entered";

/** The per-day "+ 장소 추가" control, which names the day it adds to. */
const ADD_TO_DAY = /^Add a place to /;
/** A search result's add button, which names the place. */
const ADD_RESULT = / to this day$/;
/** The must-visit toggle on the confirm step. */
const PICK = /^Mark .* must visit$/;

/**
 * Presses the primary CTA of whichever step is showing.
 *
 * The manual step and the confirm step BOTH label it "Start with this plan" —
 * they are the same button in the frame, and the confirm step is simply where
 * it finally submits. A plain getByRole would be strict-mode ambiguous the
 * moment both were mounted, so this scopes to the visible one.
 */
async function advance(page: Page) {
  await page.getByRole('button', { name: 'Start with this plan' }).click();
}

/**
 * Picks a date range on step 1 and continues.
 *
 * The 20th and 23rd rather than the 1st and 4th: a range that starts mid-month
 * cannot be satisfied by an off-by-one that happens to land on day 1.
 */
async function pickRange(page: Page) {
  await page.getByRole('button', { name: '20', exact: true }).click();
  await page.getByRole('button', { name: '23', exact: true }).click();
  // The CTA names the range once both ends are chosen, so it is the button that
  // proves step 1 accepted them.
  await page.getByRole('button', { name: /–/ }).click();
}

/** Steps 2 and 3, answering 거의 다 세우고 왔어요 to reach the manual branch. */
async function reachInputMethod(page: Page) {
  await page.getByRole('button', { name: 'Next' }).click();
  await page.getByRole('button', { name: /Almost all of it/ }).click();
  await page.getByRole('button', { name: 'Next' }).click();
}

/**
 * Adds the first search result to the first day of the manual step.
 *
 * `ADD_RESULT` excludes the day buttons deliberately. "Add {place} to this day"
 * and "Add a place to {day}" both begin with "Add ", and the first run of this
 * file matched the DAY buttons instead of the results — `/^Add \S/` does not
 * separate them either, because the "a" of "a place" is a non-space. The test
 * then pressed a different day's button, added nothing, and created a trip with
 * no stops; every later assertion failed somewhere else entirely. The app's
 * English label gained "to this day" in the same change, because a screen
 * reader hears the same collision.
 */
async function addFirstPlace(page: Page) {
  await page.getByRole('button', { name: ADD_TO_DAY }).first().click();
  await page.getByLabel('Search by place name').fill('서울');
  await page.getByRole('button', { name: ADD_RESULT }).first().click();
}

async function openWizard(page: Page) {
  await page.goto('/start');
  await expect(page.getByRole('heading', { name: 'Add your trip dates' })).toBeVisible();
}

test('STEP 1 uses the same top-right app-bar position as the later steps', async ({
  page,
}) => {
  await openWizard(page);

  const back = await page.getByRole('button', { name: 'Previous step' }).boundingBox();
  const marker = await page.getByText('STEP 1', { exact: true }).boundingBox();
  const viewportWidth = page.viewportSize()?.width ?? 0;

  expect(marker).not.toBeNull();
  expect(back).not.toBeNull();
  expect((marker?.y ?? 0) + (marker?.height ?? 0) / 2).toBeCloseTo(
    (back?.y ?? 0) + (back?.height ?? 0) / 2,
    0,
  );
  expect(viewportWidth - ((marker?.x ?? 0) + (marker?.width ?? 0))).toBe(16);
});

test('the wizard action stays fixed to the viewport bottom while content scrolls', async ({
  page,
}) => {
  await openWizard(page);
  const bar = page.locator('[data-fixed="true"]');
  await expect(bar).toBeVisible();

  const viewportHeight = page.viewportSize()?.height ?? 0;
  const before = await bar.boundingBox();
  expect(before).not.toBeNull();
  expect((before?.y ?? 0) + (before?.height ?? 0)).toBeCloseTo(viewportHeight, 0);

  // Force enough content to scroll, then measure the same fixed bar again.
  // A normal-flow or sticky CTA moves with this scroll; a fixed one does not.
  await page.addStyleTag({ content: '#main > section { min-height: 1400px; }' });
  await page.locator('#main').evaluate((node) => {
    node.scrollTop = node.scrollHeight;
  });
  const after = await bar.boundingBox();
  expect(after).not.toBeNull();
  expect(after?.y).toBeCloseTo(before?.y ?? 0, 0);
  expect((after?.y ?? 0) + (after?.height ?? 0)).toBeCloseTo(viewportHeight, 0);
});

test.describe('FE-103 the manual branch is operable by keyboard', () => {
  test('reaches manual entry and back out without a pointer', async ({ page }) => {
    // Every step of the branch has to be answerable from the keyboard, because
    // choosing IS the action on the method screen — there is no CTA to fall
    // back to if the two options cannot be focused.
    await openWizard(page);
    await pickRange(page);
    await reachInputMethod(page);

    const manual = page.getByRole('button', { name: /Enter it yourself/ });
    await manual.focus();
    await expect(manual).toBeFocused();
    await page.keyboard.press('Enter');

    await expect(page.getByRole('heading', { name: MANUAL_TITLE })).toBeVisible();

    // And back. The wizard's promise at every step is that moving back keeps
    // what was entered, so the way back has to exist here too — this step used
    // to be a blank screen with no exit at all.
    const back = page.getByRole('button', { name: 'Previous step' });
    await back.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('button', { name: /Enter it yourself/ })).toBeVisible();
  });

  test('adds a place by keyboard and names each control for its day', async ({
    page,
  }) => {
    await openWizard(page);
    await pickRange(page);
    await reachInputMethod(page);
    await page.getByRole('button', { name: /Enter it yourself/ }).click();

    // One add-a-place button per day, and each names its own day. They all read
    // "+ 장소 추가" on screen, so without the accessible name a screen-reader
    // user hears four identical buttons and cannot tell which day they add to.
    const adds = page.getByRole('button', { name: ADD_TO_DAY });
    await expect(adds).toHaveCount(4);

    const first = adds.first();
    await first.focus();
    await expect(first).toBeFocused();
    await page.keyboard.press('Enter');

    const search = page.getByLabel('Search by place name');
    await expect(search).toBeVisible();
    await search.fill('서울');

    // The result's button carries the place for the same reason: every row's
    // visible label is 담기.
    const pick = page.getByRole('button', { name: ADD_RESULT }).first();
    await expect(pick).toBeVisible();
    const picked = (await pick.getAttribute('aria-label')) ?? '';
    await pick.click();

    // The stop is on the day now, with a remove control that also names it.
    const place = picked.replace(/^Add /, '').replace(/ to this day$/, '');
    await expect(page.getByRole('button', { name: `Remove ${place}` })).toBeVisible();
  });

  test('the daypart select is a real control, and changing it sends no time', async ({
    page,
  }) => {
    // 오전/오후 groups and orders a stop; it never becomes a startTime
    // (wizard.ts seedItemsOf). What this adds over the unit test is that the
    // control is operable at all — a <select> that cannot be reached by
    // keyboard would strip the only way to say which half of the day.
    await openWizard(page);
    await pickRange(page);
    await reachInputMethod(page);
    await page.getByRole('button', { name: /Enter it yourself/ }).click();
    await addFirstPlace(page);

    const daypart = page.getByRole('combobox').first();
    await expect(daypart).toBeVisible();
    await daypart.focus();
    await expect(daypart).toBeFocused();
    await daypart.selectOption('AFTERNOON');
    await expect(daypart).toHaveValue('AFTERNOON');
  });
});

test.describe('FE-103 the confirm step is operable by keyboard', () => {
  async function reachConfirm(page: Page) {
    await openWizard(page);
    await pickRange(page);
    await reachInputMethod(page);
    await page.getByRole('button', { name: /Enter it yourself/ }).click();
    await addFirstPlace(page);
    await advance(page);
    await expect(page.getByRole('heading', { name: CONFIRM_TITLE })).toBeVisible();
  }

  test('the pick toggle is reachable and reports its own state', async ({ page }) => {
    // The control FIGMA_HANDOFF:154 left out of its description. It must
    // announce pressed/unpressed rather than relying on the pin glyph, which
    // is shape-and-colour only information.
    await reachConfirm(page);

    const pick = page.getByRole('button', { name: PICK }).first();
    await expect(pick).toHaveAttribute('aria-pressed', 'false');

    await pick.focus();
    await expect(pick).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(pick).toHaveAttribute('aria-pressed', 'true');

    // And it toggles back: a lock the user cannot release is not a toggle.
    await page.keyboard.press('Enter');
    await expect(pick).toHaveAttribute('aria-pressed', 'false');
  });

  test('a picked stop is marked by more than colour', async ({ page }) => {
    // The frame redraws a picked card in the protect colour. Colour alone
    // fails anyone who cannot see it, so the pressed state and the swapped pin
    // have to carry it too — this asserts the accessible half is present while
    // the paint changes.
    await reachConfirm(page);
    const pick = page.getByRole('button', { name: PICK }).first();

    const before = await pick.evaluate((el) => getComputedStyle(el).color);
    await pick.click();
    const after = await pick.evaluate((el) => getComputedStyle(el).color);

    expect(after, 'the pick control should repaint when pressed').not.toBe(before);
    await expect(pick).toHaveAttribute('aria-pressed', 'true');
  });

  test('#105 loads crowd only when a stop approaches the viewport', async ({ page }) => {
    const requests: string[] = [];
    page.on('request', (request) => {
      if (request.url().includes('/crowd-forecast')) requests.push(request.url());
    });

    await openWizard(page);
    // A stable off-screen position before the card is mounted. The production
    // observer watches the card itself with a 160px preload margin; 1000px is
    // deliberately beyond that boundary on the 800px test viewport.
    await page.addStyleTag({ content: '[data-crowd-stop]{margin-top:1000px}' });
    await pickRange(page);
    await reachInputMethod(page);
    await page.getByRole('button', { name: /Enter it yourself/ }).click();
    await addFirstPlace(page);
    await advance(page);
    await expect(page.getByRole('heading', { name: CONFIRM_TITLE })).toBeVisible();

    const card = page.locator('[data-crowd-stop]').first();
    await expect(card).not.toBeInViewport();
    expect(requests).toHaveLength(0);

    await card.scrollIntoViewIfNeeded();
    await expect(card).toBeInViewport();
    await expect.poll(() => requests.length).toBe(1);
  });

  test('going back to fix the itinerary keeps the picks', async ({ page }) => {
    // 다시 고칠래요 is not a cancel. Asserted in a browser as well as in the
    // component test because this is the step where a user would lose work.
    await reachConfirm(page);
    await page.getByRole('button', { name: PICK }).first().click();

    await page.getByRole('button', { name: 'Let me fix it' }).click();
    await expect(page.getByRole('heading', { name: MANUAL_TITLE })).toBeVisible();

    await advance(page);
    await expect(page.getByRole('button', { name: PICK }).first()).toHaveAttribute(
      'aria-pressed',
      'true',
    );
  });

  test('does not borrow another date point or its source', async ({ page }) => {
    // The default single-place fixture has October points while this draft is
    // in September. An exact-date card must stay without a figure/credit; it
    // must not borrow the first or maximum point from another date.
    await reachConfirm(page);
    await page.locator('[data-crowd-stop]').first().scrollIntoViewIfNeeded();
    await expect(page.getByText('Loading crowd forecast')).not.toBeVisible();
    const body = (await page.locator('body').textContent()) ?? '';
    expect(body).not.toMatch(/Relative concentration/);
    expect(body).not.toMatch(/Level \d/);
    expect(body).not.toMatch(/한국관광공사/);
  });
});

test.describe('FE-103 the new steps hold up at 360px', () => {
  test('neither step scrolls sideways', async ({ page }) => {
    // 360px is the narrowest supported width. A stop card holds a name, a
    // daypart control and a remove button on one row, which is the kind of row
    // that overflows first.
    await openWizard(page);
    await pickRange(page);
    await reachInputMethod(page);
    await page.getByRole('button', { name: /Enter it yourself/ }).click();
    await addFirstPlace(page);

    // The SHARED probe, not documentElement.scrollWidth. That metric cannot
    // fail on this app and the first version of this test used it: AppShell's
    // <main> is a scroll container, so CSS computes its overflow-x to `auto`
    // and a card forced to 600px scrolls INSIDE it — main.scrollWidth went to
    // 652 while documentElement stayed at 360 and the assertion passed. Proven
    // by mutation rather than reasoned about. overflow.ts measures element
    // boxes against the viewport for exactly this reason, and says so.
    const manual = await overflow(page);
    expect(manual.spilling, `manual entry spills: ${manual.widest.join(', ')}`).toEqual(
      [],
    );
    expect(manual.clipped, 'manual entry clips its own text').toEqual([]);

    await advance(page);
    await expect(page.getByRole('heading', { name: CONFIRM_TITLE })).toBeVisible();

    const confirm = await overflow(page);
    expect(confirm.spilling, `confirm spills: ${confirm.widest.join(', ')}`).toEqual([]);
    expect(confirm.clipped, 'confirm clips its own text').toEqual([]);
    expect(page.viewportSize()?.width).toBe(NARROW);
  });

  test('every control on the confirm step meets the 44px touch target', async ({
    page,
  }) => {
    // The frame draws the pick control at 30px, which is below the minimum a
    // finger can hit — the CSS pads it out and this is what checks that the
    // padding survived. Measured from the rendered box, which only a browser
    // has.
    await openWizard(page);
    await pickRange(page);
    await reachInputMethod(page);
    await page.getByRole('button', { name: /Enter it yourself/ }).click();
    await addFirstPlace(page);
    await advance(page);
    await expect(page.getByRole('heading', { name: CONFIRM_TITLE })).toBeVisible();

    const small = await page.evaluate(() =>
      Array.from(document.querySelectorAll('button'))
        .filter((el) => {
          const box = el.getBoundingClientRect();
          return box.width > 0 && (box.width < 44 || box.height < 44);
        })
        .map((el) => el.getAttribute('aria-label') ?? el.textContent?.trim() ?? '?'),
    );
    expect(small, 'these controls are smaller than 44px').toEqual([]);
  });
});
