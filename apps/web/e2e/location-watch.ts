import type { Page } from '@playwright/test';

// What location-off.spec.ts watches on every screen, shared with the Live
// matrix (live-replay-matrix.spec.ts), which is where the Live list and detail
// are drawn in the gate: the gate's own Live routes show only the 403 of
// FEATURE_LIVE_DATA off. Moved here rather than copied for the reason
// overflow.ts gives - two callers must agree on what counts as a coordinate.
// The three shapes and their comments moved unchanged.

/** Reads like a latitude/longitude pair — `37.5665,126.9780` — in a URL or a body. */
export const COORDINATE = /[-+]?\d{1,3}\.\d{4,}\s*,\s*[-+]?\d{1,3}\.\d{4,}/;

/** A JSON object key that names a coordinate: `{"lat": 37.5665}`. Bodies only. */
export const COORDINATE_FIELD = /"(lat|lng|latitude|longitude|coords|geo)"\s*:/i;

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
export const COORDINATE_PARAM =
  /(?<![a-z0-9_])(lat|lon|lng|latitude|longitude|coord|coords|geo|position)\s*=\s*[-+]?\d{1,3}\.\d{4,}/i;

/**
 * Installs the three watches location-off.spec.ts keeps on a screen - the
 * geolocation API wrapped so a call throws AND is recorded, JS dialogs, and the
 * wire - before the page loads. `report()` returns what each saw.
 *
 * What the dialog watch sees is `alert`/`confirm`/`prompt`/`beforeunload`: an
 * app-made "use your location?" pre-prompt, for instance. It does NOT see the
 * browser's own permission prompt, and it was documented as if it did. Measured
 * with Playwright 1.56.0's headless Chromium on /about-data: an unwrapped
 * `getCurrentPosition` with no permission granted came back `code=1 User denied
 * Geolocation` and no `dialog` event fired, while a `confirm()` in the same page
 * did fire one. The browser prompt
 * is closed off by the wrapper instead - the Geolocation API is the only thing
 * that raises it, and a call is recorded before the browser could ask - and the
 * Permissions API is watched by location-off.spec.ts's own last test.
 *
 * Calls are recorded outside the page as well as in it. A record kept only in
 * `window` belongs to one document, and a full navigation replaces it: measured,
 * a `getCurrentPosition` on /about-data read as [] after `page.goto('/feed')`.
 * The walks that use this navigate - location-off.spec.ts falls back to a fresh
 * `goto` when Back does not return, and in the gate a trip screen is reached
 * through the splash first - so a call made before one read as no call at all.
 *
 * `asked` is null when the wrapper is not on the document being reported: an
 * uninstalled wrapper records nothing, and reading that as "no calls" is the
 * vacuous pass this exists to prevent. Callers assert it is not null.
 */
export async function watchLocation(page: Page) {
  const asked: string[] = [];
  const dialogs: string[] = [];
  const leaked: string[] = [];
  // Exposed before the init script is added and before any navigation, so every
  // document (and every frame) the wrapper lands in can reach it.
  await page.exposeFunction('__nnGeolocationCalled', (name: string) => {
    asked.push(name);
  });
  await page.addInitScript(() => {
    const view = window as unknown as {
      __geo: string[];
      __nnGeolocationCalled: (name: string) => Promise<void>;
    };
    const record = (name: string) => {
      view.__geo.push(name);
      void view.__nnGeolocationCalled(name);
      throw new Error(`geolocation.${name} must not be called`);
    };
    view.__geo = [];
    Object.defineProperty(navigator, 'geolocation', {
      configurable: true,
      value: {
        getCurrentPosition: () => record('getCurrentPosition'),
        watchPosition: () => record('watchPosition'),
        clearWatch: () => undefined,
      },
    });
  });
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
  return {
    async report() {
      const inPage = await page.evaluate(() => {
        const geo = (window as unknown as { __geo?: unknown }).__geo;
        return Array.isArray(geo) ? (geo as string[]) : null;
      });
      if (inPage === null) return { asked: null, dialogs, leaked };
      // The page's own list backs up the outside one for a call made just
      // before this read: `__geo` took it synchronously, while its message to
      // `asked` may still be on the way.
      return { asked: asked.length > 0 ? [...asked] : inPage, dialogs, leaked };
    },
  };
}
