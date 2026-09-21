import { expect, test } from '@playwright/test';
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
// screen is clean and says nothing about the other seven. A permission prompt
// appearing anywhere on the judged walk-through is a compliance failure, so
// the guard has to be as wide as the app.
//
// Three things are watched on every screen, because they fail differently:
//   - the geolocation API, wrapped so a call throws AND is recorded
//   - the browser permission dialog, which would mean something reached the
//     API through a path the wrapper did not cover
//   - the wire, because a coordinate can be collected without navigator (a
//     map SDK, an IP lookup) and sending one is the thing the rule protects
//     against, whatever produced it
//
// On the wire, three shapes count as a coordinate, because a position reaches a
// server in more than one form: a comma-joined pair (`37.5665,126.9780`), a
// JSON field (`{"lat": …}`), and a named parameter (`?lat=…`, `&lng=…`) in
// either a URL or a form-encoded body. The last of these was added after the
// first two were found to miss `?lat=37.5665&lng=126.9780` entirely — see
// COORDINATE_PARAM below for what it excludes and why the precision floor sits
// where it does.

/** Reads like a latitude/longitude pair — `37.5665,126.9780` — in a URL or a body. */
const COORDINATE = /[-+]?\d{1,3}\.\d{4,}\s*,\s*[-+]?\d{1,3}\.\d{4,}/;

/** A JSON object key that names a coordinate: `{"lat": 37.5665}`. Bodies only. */
const COORDINATE_FIELD = /"(lat|lng|latitude|longitude|coords|geo)"\s*:/i;

/**
 * A coordinate carried as a named parameter: `?lat=37.5665`, `&lng=126.9780`,
 * or the same shape in a form-encoded body.
 *
 * The two patterns above could not see this. `COORDINATE` needs the pair joined
 * by a comma, and a URL splits them across `&`; `COORDINATE_FIELD` needs JSON
 * quoting, and it was only ever applied to bodies. So
 * `GET /api/v1/places/nearby?lat=37.5665&lng=126.9780` — the most ordinary way
 * to send a position — passed all three checks.
 *
 * What keeps this from firing on innocent traffic:
 *
 *   - The key must be followed IMMEDIATELY by `=`, so `latest=1` cannot match:
 *     after `lat` comes `e`, not `=`. Same for `catalog=`, `later=`.
 *   - The lookbehind requires the key to start at a boundary, so a longer word
 *     ending in one of these cannot match: `translate=37.5665`, `plat=37.5665`,
 *     `flag=` are all excluded.
 *   - Every query parameter the contract actually defines was checked against
 *     it: at, cursor, disposition, from, limit, source, status, to, tripId.
 *     None matches, and none of them is a coordinate — the contract defines no
 *     coordinate parameter at all, so a match here is a violation rather than a
 *     tolerated case.
 *
 * The `\d{4,}` floor is deliberately kept from `COORDINATE`, and it is about
 * PRECISION rather than formatting. At Seoul's latitude one decimal place is
 * worth roughly: 2 places ±1.1km (a district), 3 places ±111m (a block),
 * 4 places ±11m (a building). Four is where a coordinate stops describing an
 * area and starts locating a person, which is what invariant 10 and CMP-LOC-002
 * protect against. A coarse `region=37.5` is not the leak this guards.
 */
const COORDINATE_PARAM =
  /(?<![a-z0-9_])(lat|lon|lng|latitude|longitude|coord|coords|geo|position)\s*=\s*[-+]?\d{1,3}\.\d{4,}/i;

for (const screen of SCREENS) {
  test(`FE-603-T1 BA-073-T5 ${screen.name} asks for no location`, async ({ page }) => {
    const dialogs: string[] = [];
    const leaked: string[] = [];

    // Installed before any app code runs, so a call during module evaluation
    // is caught too.
    await page.addInitScript(() => {
      const record = (name: string) => {
        (window as unknown as { __geo: string[] }).__geo.push(name);
        throw new Error(`geolocation.${name} must not be called`);
      };
      (window as unknown as { __geo: string[] }).__geo = [];
      Object.defineProperty(navigator, 'geolocation', {
        configurable: true,
        value: {
          getCurrentPosition: () => record('getCurrentPosition'),
          watchPosition: () => record('watchPosition'),
          clearWatch: () => undefined,
        },
      });
    });

    // A prompt would mean the API was reached some other way.
    page.on('dialog', (dialog) => {
      dialogs.push(dialog.type());
      void dialog.dismiss();
    });

    page.on('request', (request) => {
      const url = request.url();
      if (!url.includes('/api/')) return;
      const body = request.postData() ?? '';
      // COORDINATE_PARAM is applied to the body as well as the URL: a
      // form-encoded POST carries `lat=37.5665` in exactly the same shape, and
      // checking only the URL would let the same value through by changing verb.
      if (
        COORDINATE.test(url) ||
        COORDINATE.test(body) ||
        COORDINATE_FIELD.test(body) ||
        COORDINATE_PARAM.test(url) ||
        COORDINATE_PARAM.test(body)
      ) {
        leaked.push(`${request.method()} ${url}`);
      }
    });

    await page.goto(screen.path);
    await page.waitForLoadState('networkidle');

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
      if (!page.url().includes(screen.path.split('?')[0] ?? screen.path)) {
        await page.goBack().catch(() => undefined);
        await page.waitForLoadState('networkidle').catch(() => undefined);
        if (!page.url().includes(screen.path.split('?')[0] ?? screen.path)) {
          await page.goto(screen.path).catch(() => undefined);
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

    const asked = await page.evaluate(
      () => (window as unknown as { __geo: string[] }).__geo ?? [],
    );
    expect(asked, `${screen.name} called the geolocation API`).toEqual([]);
    expect(dialogs, `${screen.name} opened a permission prompt`).toEqual([]);
    expect(leaked, `${screen.name} sent something shaped like a coordinate`).toEqual([]);
  });
}

test('FE-603-T1 BA-073-T5 no screen registers a geolocation permission at all', async ({
  page,
}) => {
  // The capability is OFF, so even querying it is a signal the feature is
  // half-wired. Checked once rather than per screen: the Permissions API is
  // global, and a query anywhere would show up here.
  const queried: string[] = [];
  await page.addInitScript(() => {
    (window as unknown as { __perm: string[] }).__perm = [];
    const original = navigator.permissions?.query?.bind(navigator.permissions);
    if (!original) return;
    navigator.permissions.query = ((descriptor: { name: string }) => {
      (window as unknown as { __perm: string[] }).__perm.push(descriptor.name);
      return original(descriptor as never);
    }) as typeof navigator.permissions.query;
  });

  await page.goto('/feed');
  await page.waitForLoadState('networkidle');
  const names = await page.evaluate(
    () => (window as unknown as { __perm: string[] }).__perm,
  );
  queried.push(...names.filter((name) => name === 'geolocation'));
  expect(queried).toEqual([]);
});
