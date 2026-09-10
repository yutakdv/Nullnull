// Every operation a screen calls needs a default msw handler.
//
// Without one the request is unhandled: each test can stand up its own handler
// and pass, while the running app gets nothing back and shows its failure
// state. That is what happened to createTrip in FE-102 — the suite was green
// and the wizard's final step was broken in the browser.
//
// This walks the API module for the paths it calls and checks each is served,
// so a new call cannot be added without its mock.
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { handlers } from '../msw/handlers.js';

const source = readFileSync('src/shared/api/session.ts', 'utf8');

/** Paths the API module calls, as (method, path) pairs. */
function calledOperations(): { method: string; path: string }[] {
  const calls: { method: string; path: string }[] = [];
  // `\s*` between the method and the path: prettier wraps a long call so the
  // path lands on the next line, and a same-line-only regex quietly stops
  // seeing those operations. That is how /deletion-requests and every FE-303
  // call were being counted as "not called" and so never checked for a mock.
  const pattern = /\.(GET|POST|PATCH|PUT|DELETE)\(\s*'([^']+)'/g;
  let match = pattern.exec(source);
  while (match) {
    calls.push({ method: match[1] ?? '', path: match[2] ?? '' });
    match = pattern.exec(source);
  }
  return calls;
}

describe('default handlers cover what the screens call', () => {
  const called = calledOperations();

  it('finds the calls it means to check', () => {
    // A refactor that moves these elsewhere would make the test vacuous.
    expect(called.length).toBeGreaterThan(10);
  });

  /**
   * Reduces a path to its shape so the two spellings of a parameter compare
   * equal: the client uses OpenAPI's `{tripId}` and msw uses `:tripId`, and the
   * names need not agree. Without this every parameterized path silently failed
   * to match — `endsWith` compared `{deletionRequestId}` against `:id` — so the
   * guard was passing without checking anything for exactly the handlers most
   * likely to be missing.
   */
  function shape(path: string): string {
    return path.replace(/\{[^}]+\}/g, '*').replace(/:[^/]+/g, '*');
  }

  it.each(calledOperations())(
    '$method $path has a default handler',
    ({ method, path }) => {
      const served = handlers.some((handler) => {
        const info = (handler as { info?: { method?: string; path?: string } }).info;
        return (
          info?.method?.toUpperCase() === method &&
          shape(String(info.path ?? '')).endsWith(shape(path))
        );
      });
      expect(
        served,
        `${method} ${path} has no default handler, so the app gets an unhandled request`,
      ).toBe(true);
    },
  );
});
