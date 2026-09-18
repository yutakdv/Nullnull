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
import { routes } from '../routes.js';
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
