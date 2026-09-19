// routes.tsx is the source of truth for what screens exist. e2e/screens.ts is a
// hand-written list, and it is the only thing that puts a screen in front of
// responsive.spec.ts (360px, 200% zoom, the 44px touch floor) and
// location-off.spec.ts (no geolocation call). Nothing checked the two agreed.
//
// That gap is not hypothetical and it is not new. screens.ts records being
// caught twice: once holding 8 of 17 paths, and once missing the paste screen
// alone — "the screen with the app's largest text input, a review list and two
// blocking CTAs had never been measured at 360px". Both times the list was
// wrong for a while and every gate stayed green, because a shorter loop is not
// a failure. It just measures less.
//
// Measured again while adding /sign-in (#265): with the route in routes.tsx
// and the line missing from screens.ts, `tsc --noEmit` exited 0 and all 1,059
// unit tests passed. The drift is invisible to every check the repo had.
//
// This test lives in src/ because vitest only collects `src/**/*.test.{ts,tsx}`
// (vite.config.ts:45). Importing screens.ts from here has a second effect worth
// stating: e2e/ is outside the tsconfig program (#266), so this import is
// currently the only thing type-checking that file.
import { describe, expect, it } from 'vitest';
import type { RouteObject } from 'react-router';
import { postFixtures, tripFixtures } from '@nullnull/contracts';
import { routes } from '../routes.js';
import { MOCK_RUN_ID } from '../../shared/testing/msw/handlers.js';
import { SCREENS } from '../../../e2e/screens.js';

// Paths the walk deliberately does not visit. Each needs a reason, because an
// exemption with no reason is how a screen stops being measured quietly.
const NOT_WALKED = new Map<string, string>([
  // The catch-all. Not a screen; NotFoundScreen is reached by any unknown path
  // and has no canonical URL to put in a list.
  ['*', 'catch-all, not a screen'],
]);

/** Every path routes.tsx defines, normalised to what a browser would visit. */
function declaredPaths(table: RouteObject[], parent = ''): string[] {
  return table.flatMap((route) => {
    // The two layout routes share path '/' and contribute nothing themselves.
    const own = route.path ?? '';
    const full = own.startsWith('/') ? own : `${parent}/${own}`.replace(/\/+/g, '/');
    const here = route.path === undefined ? [] : [full];
    return [...here, ...declaredPaths(route.children ?? [], full === '/' ? '' : full)];
  });
}

/** `/trip/:tripId/candidates` and `/trip/<uuid>/candidates` compare equal. */
function shape(path: string): string {
  return path
    .split('/')
    .map((segment) =>
      segment.startsWith(':') ||
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(segment)
        ? ':param'
        : segment,
    )
    .join('/');
}

describe('the E2E screen list matches the route table', () => {
  // Not vacuous: an empty routes.tsx or a broken parser would otherwise make
  // every assertion below pass by iterating nothing.
  it('reads a plausible number of routes from both sides', () => {
    expect(declaredPaths(routes).length).toBeGreaterThan(10);
    expect(SCREENS.length).toBeGreaterThan(10);
  });

  it('walks every route that routes.tsx declares', () => {
    const walked = new Set(SCREENS.map((screen) => shape(screen.path)));
    const missing = declaredPaths(routes)
      .filter((path) => (path !== '' && path !== '/') || path === '/')
      .filter((path) => !NOT_WALKED.has(path.replace(/^\//, '')))
      .map(shape)
      .filter((path) => !walked.has(path));

    expect(
      [...new Set(missing)],
      'routes.tsx declares these paths and e2e/screens.ts does not walk them. ' +
        'A screen absent from SCREENS is never measured at 360px, at 200% zoom, ' +
        'against the 44px touch floor, or for a geolocation call — and nothing ' +
        'goes red, because the loop just gets shorter.',
    ).toEqual([]);
  });

  it('does not walk a path the router cannot serve', () => {
    // The mirror image: a stale entry sends Playwright to the not-found screen,
    // which reflows fine at 360px and calls no geolocation. It passes, and the
    // screen it was named for goes unmeasured.
    const declared = new Set(declaredPaths(routes).map(shape));
    const orphaned = SCREENS.map((screen) => shape(screen.path)).filter(
      (path) => !declared.has(path),
    );

    expect(orphaned, 'e2e/screens.ts walks paths routes.tsx does not declare').toEqual(
      [],
    );
  });
});

// The describe above compares SHAPES, and that is why it cannot see this.
//
// `shape()` rewrites any uuid segment to ':param', so a fabricated id and a
// real one are the same string to it: '/posts/018f4c30-…' and
// '/posts/018f5b00-…' both become '/posts/:param'. It answers "does the router
// declare this path", which is a different question from "does this id resolve
// to data". Both entries below were wrong for a while and it stayed green.
//
// Its third test already describes this failure in prose — "a stale entry sends
// Playwright to the not-found screen, which reflows fine at 360px … It passes,
// and the screen it was named for goes unmeasured". That sentence was true and
// nothing measured it. This block does.
//
// Measured (#195): /posts/018f4c30-… rendered "That post does not exist" and
// an optimizations id rendered "No such optimization", while their names in
// SCREENS claimed the post screen and the run screen. Every gate was green:
// a not-found screen reflows at 360px, survives 200% zoom, meets the 44px
// floor and calls no geolocation. It just is not the screen being claimed.
const KNOWN_IDS = new Map<string, string>([
  [tripFixtures.detailScheduled.id, 'tripFixtures.detailScheduled.id'],
  [postFixtures.detail.id, 'postFixtures.detail.id'],
  [MOCK_RUN_ID, 'MOCK_RUN_ID (msw handlers)'],
]);

const UUID = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi;

/** Every uuid in a path, in order. */
function idsIn(path: string): string[] {
  return path.match(UUID) ?? [];
}

describe('the E2E screen list reaches the screens it names', () => {
  // The entries this block is about. Kept as a value so the guard below and the
  // assertions read the same set rather than two greps that can drift apart.
  const withIds = SCREENS.filter((screen) => idsIn(screen.path).length > 0);

  // Not vacuous, and this one is load-bearing rather than decorative: every
  // assertion below iterates `withIds`, so if the regex stops matching or the
  // paths stop carrying ids, all of them pass over an empty set and report
  // nothing. That is the shape this repo has been caught by three times — the
  // `ajv test --invalid` glob matching 0 files, the egress probe judged by exit
  // code, `actual_call=blocked` counted as a pass. An empty set is the failure.
  it('finds entries that carry ids at all', () => {
    expect(
      withIds.length,
      'no SCREENS entry carries a uuid, so every assertion in this block ' +
        'would pass over an empty set and prove nothing',
    ).toBeGreaterThan(0);
    expect(KNOWN_IDS.size).toBeGreaterThan(0);
  });

  it('resolves every id on a rendered entry to a fixture', () => {
    const unresolved = withIds
      .filter((screen) => (screen.expect ?? 'rendered') === 'rendered')
      .flatMap((screen) =>
        idsIn(screen.path)
          .filter((id) => !KNOWN_IDS.has(id.toLowerCase()))
          .map((id) => `${screen.name}: ${id}`),
      );

    expect(
      unresolved,
      'these SCREENS entries carry ids no fixture or msw handler answers, so ' +
        'the walk measures a not-found screen while the entry name claims a ' +
        'real one. Either point the path at a fixture id, or, if the miss is ' +
        "deliberate, say so with expect: 'missing'.",
    ).toEqual([]);
  });

  it("keeps an entry marked 'missing' actually missing", () => {
    // The mirror, and the reason `expect` is a field rather than a naming
    // convention. 'optimization run (missing)' exists to measure the not-found
    // screen's own reflow, which the fix above would otherwise have spent. If
    // someone later points it at a real id, the error state stops being
    // measured and the name starts lying — silently, because a rendered screen
    // passes every assertion the suites make.
    //
    // ONE unresolvable id is enough, not all of them. This path carries two:
    // the trip id, which resolves and must — the run is nested under a real
    // trip — and the run id, which is invented on purpose. Requiring every id
    // to miss would fail on the trip id and push the next person to break the
    // trip too, which would measure a different not-found screen than the one
    // this entry names.
    const resolved = withIds
      .filter((screen) => screen.expect === 'missing')
      .filter((screen) =>
        idsIn(screen.path).every((id) => KNOWN_IDS.has(id.toLowerCase())),
      )
      .map(
        (screen) =>
          `${screen.name}: every id resolves (${idsIn(screen.path).join(', ')})`,
      );

    expect(
      resolved,
      "an entry marked expect: 'missing' carries an id that DOES resolve. It " +
        'now renders a real screen, so the not-found state it was added to ' +
        'measure is no longer measured by anything.',
    ).toEqual([]);
  });
});
