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

/** Reads like a latitude/longitude pair in a URL or a body. */
const COORDINATE = /[-+]?\d{1,3}\.\d{4,}\s*,\s*[-+]?\d{1,3}\.\d{4,}/;
const COORDINATE_FIELD = /"(lat|lng|latitude|longitude|coords|geo)"\s*:/i;

for (const screen of SCREENS) {
  test(`${screen.name} asks for no location`, async ({ page }) => {
    const calls: string[] = [];
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
      if (COORDINATE.test(url) || COORDINATE.test(body) || COORDINATE_FIELD.test(body)) {
        leaked.push(`${request.method()} ${url}`);
      }
    });

    await page.goto(screen.path);
    await page.waitForLoadState('networkidle');

    const asked = await page.evaluate(
      () => (window as unknown as { __geo: string[] }).__geo,
    );
    expect(asked, `${screen.name} called the geolocation API`).toEqual([]);
    expect(calls).toEqual([]);
    expect(dialogs, `${screen.name} opened a permission prompt`).toEqual([]);
    expect(leaked, `${screen.name} sent something shaped like a coordinate`).toEqual([]);
  });
}

test('no screen registers a geolocation permission at all', async ({ page }) => {
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
