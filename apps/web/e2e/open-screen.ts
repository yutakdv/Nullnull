import { readFileSync } from 'node:fs';
import { expect, type Locator, type Page } from '@playwright/test';
import { messages, type MessageKey } from '../src/i18n/messages.js';
import type { Screen, Shows } from './screens.js';
import { createSeededTrip, startSession } from './seeded-trip.js';

// Opens a SCREENS entry and proves it reached the screen its name claims.
//
// Every walk over SCREENS goes through here — responsive.spec.ts and
// location-off.spec.ts — so the two agree on which state of a screen they
// measure. Before this, both navigated to the entry's path and measured
// whatever came back, and in the composed gate that was "We can't find that
// trip" for every /trip/… entry (screens.ts, GateReach). A not-found screen
// passes every check those suites make, which is why nothing went red.
//
// One entry is opened elsewhere in one suite: responsive.spec.ts measures the
// splash on a held bootstrap (its openHeldSplash), because the splash has no
// `shows` to wait on and redirects on its own. location-off.spec.ts opens the
// splash here, unheld: it watches the whole visit, bootstrap and redirect
// included, rather than one frame of it.

/**
 * Whether this run is the composed gate — the decision playwright.config.ts
 * makes, repeated rather than approximated: PLAYWRIGHT_MOCK_BASE_URL wins,
 * because it names an already-running MOCK server even when a base URL is set.
 */
export const composedStack =
  !process.env.PLAYWRIGHT_MOCK_BASE_URL &&
  Boolean(process.env.PLAYWRIGHT_BASE_URL ?? process.env.WEB_BASE_URL);

const fixture = (path: string) =>
  readFileSync(
    new URL(`../../../packages/contracts/fixtures/${path}`, import.meta.url),
    'utf8',
  );

/**
 * The message in either locale, as an anchored pattern. A `{…}` placeholder
 * matches any value, so `Saved places {count}` matches "Saved places 0" and not
 * the bare "Saved places" the not-found state keeps.
 */
function named(key: MessageKey): RegExp {
  const alternatives = (['ko-KR', 'en-US'] as const).map((locale) => {
    const text = (messages[locale] as Record<string, string>)[key];
    if (text === undefined) throw new Error(`${locale} has no message ${key}`);
    return text
      .split(/\{[^}]+\}/)
      .map((part) => part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
      .join('.+');
  });
  return new RegExp(`^(?:${alternatives.join('|')})$`);
}

function shown(page: Page, shows: Shows): Locator {
  const name = named(shows.key);
  if (shows.role === 'text') return page.getByText(name);
  if (shows.role === 'heading') return page.getByRole('heading', { level: 1, name });
  return page.getByRole(shows.role, { name });
}

/**
 * Serves the approved READY run for a `/trip/{trip}/optimizations/{run}` path.
 *
 * The same overrides the MSW handler makes, and only those: the run takes the
 * path's id and trip id, and the trip's version as its input version, so it is
 * not stale against the trip it is shown with. The trip is the approved
 * scheduled example — the example the run was written against — so its id has
 * to be the path's; anything else fails here rather than as a stale preview.
 *
 * Needs `serviceWorkers: 'block'`: page.route does not see a request a service
 * worker handles, and the production build registers one (public/sw.js).
 */
async function serveApprovedRun(page: Page, path: string): Promise<void> {
  const [, tripId, runId] = /^\/trip\/([^/]+)\/optimizations\/([^/]+)$/.exec(path) ?? [];
  if (!tripId || !runId) {
    throw new Error(
      `approved-run needs a /trip/{id}/optimizations/{id} path, got ${path}`,
    );
  }
  const trip = JSON.parse(fixture('trips/trip-detail-scheduled.json')) as {
    id: string;
    version: number;
  };
  if (trip.id !== tripId) {
    throw new Error(`approved-run serves trip ${trip.id}, but ${path} names ${tripId}`);
  }
  const run = {
    ...(JSON.parse(fixture('optimizations/run-ready.json')) as Record<string, unknown>),
    id: runId,
    tripId,
    inputTripVersion: trip.version,
  };
  await page.route(`**/api/v1/trips/${tripId}`, (route) =>
    route.request().method() === 'GET'
      ? route.fulfill({ json: trip, headers: { ETag: `"${String(trip.version)}"` } })
      : route.fallback(),
  );
  await page.route(`**/api/v1/optimizations/${runId}`, (route) =>
    route.request().method() === 'GET' ? route.fulfill({ json: run }) : route.fallback(),
  );
}

/** The path this run can reach the entry's screen at (screens.ts, GateReach). */
async function reachablePath(page: Page, screen: Screen): Promise<string> {
  if (!screen.path.startsWith('/trip/')) return screen.path;
  // Checked in the mock run too, where the fixture id resolves anyway: a new
  // trip route added without these would pass here and measure a 404 in the
  // gate, which is exactly how the entries above went unnoticed.
  if (!screen.gate || !screen.shows) {
    throw new Error(
      `${screen.name}: a /trip/… entry must say how the gate reaches it (gate) and ` +
        'what only the reached screen draws (shows) - see screens.ts',
    );
  }
  if (!composedStack) return screen.path;
  if (screen.gate === 'approved-run') {
    // A real session first, so the calls this screen makes to the real API
    // (the owner, the CSRF token) are answered as a person's would be.
    await startSession(page);
    await serveApprovedRun(page, screen.path);
    return screen.path;
  }
  const own = await createSeededTrip(page);
  return screen.path.replace(/^\/trip\/[^/]+/, own);
}

/**
 * Opens the entry, waits for the network to settle, and — when the entry says
 * what only its screen draws — waits for that too. Returns the path it opened,
 * which in the gate is not the entry's own for an `own-trip` screen.
 *
 * The wait is 10s because the mock run reaches READY on its third read, a
 * Retry-After second apart; networkidle alone lands on QUEUED or RUNNING.
 */
export async function openScreen(page: Page, screen: Screen): Promise<string> {
  const path = await reachablePath(page, screen);
  await page.goto(path);
  await page.waitForLoadState('networkidle');
  if (screen.shows) {
    await expect(
      shown(page, screen.shows).first(),
      `${screen.name} did not reach its own screen (${screen.shows.role} ` +
        `"${screen.shows.key}"); a check that measures what came back instead ` +
        'would measure a loading, error or not-found state and pass',
    ).toBeVisible({ timeout: 10_000 });
  }
  return path;
}
