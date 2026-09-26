import { expect, test } from '@playwright/test';
import { watchLocation } from './location-watch.js';
import { composedStack, openScreen } from './open-screen.js';
import { SCREENS } from './screens.js';

// CMP-LOC-002: the submission build asks for no location, anywhere.
//
// The rule is absolute rather than conditional — P0 collects no precise
// location and the contest profile keeps the capability OFF (CLAUDE.md
// invariant 10, and the P0 decisions section). So this does not check that a
// prompt is handled well; it checks that the API is never reached at all.
//
// Why an E2E rather than the unit test that already exists: profile.test.tsx
// covers one screen by replacing navigator.geolocation, which proves that
// screen is clean and says nothing about any other screen in SCREENS (no count
// here: the list grows, and a number written beside it went stale). A
// permission prompt appearing anywhere on the judged walk-through is a
// compliance failure, so the guard has to be as wide as the app.
//
// Three things are watched on every screen, because they fail differently:
//   - the geolocation API, wrapped so a call throws AND is recorded
//   - JS dialogs (alert/confirm/prompt), which is where an app-made "use your
//     location?" pre-prompt would appear. NOT the browser's own permission
//     prompt: this comment used to say it caught that, and Playwright reports
//     no such event (measured - location-watch.ts has the numbers). The
//     wrapper is what closes that path, since only the Geolocation API raises
//     the prompt and the wrapper records the call first.
//   - the wire, because a coordinate can be collected without navigator (a
//     map SDK, an IP lookup) and sending one is the thing the rule protects
//     against, whatever produced it
//
// The three live in location-watch.ts, shared with live-replay-matrix.spec.ts.
// This file kept its own copy after they moved there, and the copy was the one
// without the guard that the wrapper was installed at all.
//
// On the wire, three shapes count as a coordinate - one per pattern in
// location-watch.ts, COORDINATE, COORDINATE_FIELD and COORDINATE_PARAM - because
// a position reaches a server in more than one form: a comma-joined pair
// (`37.5665,126.9780`), a JSON field (`{"lat": …}`), and a named parameter
// (`?lat=…`, `&lng=…`) in either a URL or a form-encoded body. The last of
// these was added after the first two were found to miss
// `?lat=37.5665&lng=126.9780` entirely — see COORDINATE_PARAM in
// location-watch.ts for what it excludes and why the precision floor sits
// where it does.

// Service workers are blocked in the gate only, for openScreen's page.route
// (responsive.spec.ts has the same line and the reason).
test.use({ serviceWorkers: composedStack ? 'block' : 'allow' });

for (const screen of SCREENS) {
  test(`FE-603-T1 BA-073-T5 BA-092-T18 ${screen.name} asks for no location`, async ({
    page,
  }) => {
    // Installed before any app code runs, so a call during module evaluation
    // is caught too.
    const watch = await watchLocation(page);

    // The path this run can reach the screen at: in the gate a trip screen
    // opens on a trip the test created, not on the fixture id (screens.ts).
    const path = await openScreen(page, screen);
    const here = path.split('?')[0] ?? path;

    // Then USE the screen. Loading it only proves nothing asks for location
    // during module evaluation, and the rule is about the whole visit: a call
    // reached through a click handler, a sheet opening, or a mutation's
    // onSuccess would have been invisible here, and so would a coordinate sent
    // in the body of a POST that a tap triggered.
    //
    // Every enabled control is pressed rather than a chosen few, because the
    // point is to be as wide as the app — the same reason this file exists
    // instead of a per-screen unit test. Presses are best-effort: a control
    // that navigates away, detaches, or opens a modal that swallows the next
    // click is not a failure of THIS rule, so each one is attempted and skipped
    // if it no longer exists.
    const controls = page.locator(
      'button:not([disabled]), [role="button"]:not([aria-disabled="true"]), a[href^="/"]',
    );
    const total = await controls.count();
    for (let index = 0; index < Math.min(total, 12); index += 1) {
      const control = controls.nth(index);
      try {
        if (!(await control.isVisible())) continue;
        await control.click({ timeout: 1500, noWaitAfter: true });
        await page.waitForTimeout(120);
      } catch {
        // Detached, covered, or it navigated. Either way nothing to press.
      }
      // A press that navigates away does not end the walk — it would end it on
      // the FIRST control of most screens (a feed card is a link to a post), so
      // everything below it would go untouched. Come back and carry on, which
      // is also what a user does.
      if (!page.url().includes(here)) {
        await page.goBack().catch(() => undefined);
        await page.waitForLoadState('networkidle').catch(() => undefined);
        if (!page.url().includes(here)) {
          await page.goto(path).catch(() => undefined);
          await page.waitForLoadState('networkidle').catch(() => undefined);
        }
      }
    }

    // Typing reaches code that no click does — a search box fires a query per
    // keystroke, and that is the shape most likely to carry a coordinate.
    const box = page.locator('input[type="search"], input[type="text"]').first();
    if (await box.count()) {
      try {
        await box.fill('서울', { timeout: 1500 });
        await page.waitForTimeout(400);
      } catch {
        // Not editable on this screen.
      }
    }
    await page.waitForLoadState('networkidle').catch(() => undefined);

    const { asked, dialogs, leaked } = await watch.report();
    // Before the "no calls" below can mean anything, the wrapper has to have
    // been there. This read `__geo ?? []`, so a wrapper that never installed
    // came back as an empty list and every screen passed having watched
    // nothing; live-replay-matrix.spec.ts already guarded this.
    expect(
      asked,
      `${screen.name}: the geolocation wrapper was not installed`,
    ).not.toBeNull();
    expect(asked, `${screen.name} called the geolocation API`).toEqual([]);
    expect(dialogs, `${screen.name} opened a JS dialog`).toEqual([]);
    expect(leaked, `${screen.name} sent something shaped like a coordinate`).toEqual([]);
  });
}

test('FE-603-T1 BA-073-T5 the feed, as it loads, queries no geolocation permission', async ({
  page,
}) => {
  // The capability is OFF, so even querying it is a signal the feature is
  // half-wired. What this covers is the /feed screen as it loads, and nothing
  // else: it opens that one screen and presses nothing, and the per-screen walk
  // above does not watch this API. The title says so. It used to read "no
  // screen registers a geolocation permission at all", and an earlier comment
  // said a query anywhere would show up here; a query made on another screen,
  // or behind a control, would not. The two ids stay because this still
  // witnesses part of their clause: one screen, and a query rather than a
  // prompt (only the Geolocation API raises the prompt, and the walk's wrapper
  // closes that on every SCREENS entry). The gap is every other screen and
  // every control - nothing watches the Permissions API there.
  await page.addInitScript(() => {
    const view = window as unknown as { __perm?: string[]; __permWrapper?: unknown };
    const original = navigator.permissions?.query?.bind(navigator.permissions);
    // No list without the wrapper. This used to set `__perm = []` first and
    // then return when the API was missing, so an unwatched page read as "no
    // query" and passed - the vacuous pass that watchLocation's `asked: null`
    // prevents on the geolocation side.
    if (!original) return;
    view.__perm = [];
    const wrapper = ((descriptor: { name: string }) => {
      view.__perm?.push(descriptor.name);
      return original(descriptor as never);
    }) as typeof navigator.permissions.query;
    navigator.permissions.query = wrapper;
    view.__permWrapper = wrapper;
  });

  await page.goto('/feed');
  await page.waitForLoadState('networkidle');
  const watched = await page.evaluate(() => {
    const view = window as unknown as { __perm?: unknown; __permWrapper?: unknown };
    return {
      names: Array.isArray(view.__perm) ? (view.__perm as string[]) : null,
      // Still ours when read: a query made through a function that replaced
      // the wrapper would not reach the list.
      installed:
        view.__permWrapper !== undefined &&
        navigator.permissions?.query === view.__permWrapper,
    };
  });
  expect(
    watched.names,
    'navigator.permissions.query is missing, so nothing was watched',
  ).not.toBeNull();
  expect(watched.installed, 'the Permissions API wrapper was not installed').toBe(true);
  expect(watched.names?.filter((name) => name === 'geolocation')).toEqual([]);
});
