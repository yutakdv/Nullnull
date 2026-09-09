// MSW handlers backed by @nullnull/contracts fixtures.
//
// Every response body here is ajv-validated against docs/api/openapi.yaml by
// packages/contracts (see its README). Handlers must not build bodies inline —
// an inline object is a hand-written model with nothing checking it.
import {
  optimizationFixtures,
  problemFixtures,
  sessionFixtures,
  tripFixtures,
} from '@nullnull/contracts';
import { http, HttpResponse } from 'msw';
import type { ProblemCode } from '../../api/index.js';

/**
 * The dev proxy and the deployed app both serve the API under /api/v1.
 *
 * Relative on purpose: MSW resolves a relative handler path against the
 * document origin at match time, which is the same origin the client resolves
 * its own base URL against (shared/api/session.ts).
 */
export const API_BASE = '/api/v1';

const PROBLEM_CONTENT_TYPE = 'application/problem+json';

/** A Problem response with the fixture's own status and the RFC 9457 type. */
export function problemResponse(code: ProblemCode, headers: Record<string, string> = {}) {
  const fixture = problemFixtures[code];
  return HttpResponse.json(fixture, {
    status: fixture.status,
    headers: {
      'Content-Type': PROBLEM_CONTENT_TYPE,
      'X-Request-ID': fixture.requestId,
      ...headers,
    },
  });
}

/**
 * Default happy-path handlers: only the session bootstrap trio, which is all
 * FE-003 needs. Screen slices add their own as their fixtures arrive from BE.
 */
export const handlers = [
  http.post(`${API_BASE}/demo/sessions`, () =>
    HttpResponse.json(sessionFixtures.bootstrap, { status: 201 }),
  ),
  http.post(`${API_BASE}/session/csrf`, () =>
    HttpResponse.json(sessionFixtures.csrfToken),
  ),
  http.get(`${API_BASE}/me`, () => HttpResponse.json(sessionFixtures.owner)),
  // Merge-patch: echo the fixture with the patch applied, so a screen sees the
  // field it just wrote. The body is still fixture-shaped, not hand-built.
  http.patch(`${API_BASE}/me`, async ({ request }) => {
    const patch = (await request.json()) as Partial<typeof sessionFixtures.owner>;
    return HttpResponse.json({ ...sessionFixtures.owner, ...patch });
  }),

  // MOCK DATA (FE-105) — these two operations have no approved example, so the
  // fixtures behind them are schema-valid guesses rather than real responses
  // (packages/contracts/src/index.ts). Delete these two handlers once BA-030
  // and BA-053 serve the real thing; the screens already call the real client.
  http.get(`${API_BASE}/trips`, () => HttpResponse.json(tripFixtures.page)),
  http.get(`${API_BASE}/optimizations`, () =>
    HttpResponse.json(optimizationFixtures.historyPage),
  ),
];
