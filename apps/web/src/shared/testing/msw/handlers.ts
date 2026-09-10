// MSW handlers backed by @nullnull/contracts fixtures.
//
// Every response body here is ajv-validated against docs/api/openapi.yaml by
// packages/contracts (see its README). Handlers must not build bodies inline —
// an inline object is a hand-written model with nothing checking it.
import {
  optimizationFixtures,
  placeFixtures,
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
 * FE-106's trip state. A replace increments the version, so the next If-Match
 * has to use the ETag the server just returned.
 */
let tripState: (typeof tripFixtures)['detailWithInterests'] | null = null;

function currentTrip() {
  // The scheduled fixture is the default: it carries the interests FE-106
  // edits *and* the days/items FE-301 renders, so one trip serves both screens
  // and they cannot disagree about the same id.
  tripState ??= tripFixtures.detailScheduled;
  return tripState;
}

/** Drops mutations between tests, so ordering cannot leak state. */
export function resetMockState(): void {
  tripState = null;
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
  // MOCK DATA (FE-102). Without this the wizard's final submit is an unhandled
  // request: the tests each stood up their own handler and passed, while the
  // running app answered 500 and showed its failure state. Delete with BA-030.
  http.post(`${API_BASE}/trips`, () =>
    HttpResponse.json(tripFixtures.detailCreated, {
      status: 201,
      headers: {
        // Strong, not W/"1": components.headers.ETag is `^"[1-9][0-9]*"$` and
        // If-Match repeats that pattern, so a weak validator is one the real
        // server would reject. Mirrors the body's own `version`.
        ETag: `"${String(tripFixtures.detailCreated.version)}"`,
        Location: `/trips/${tripFixtures.detailCreated.id}`,
      },
    }),
  ),
  // MOCK DATA (FE-105). Deletion is 202 with a receipt, then a status the
  // screen polls with the receipt token. Delete with BA-012.
  http.delete(`${API_BASE}/session`, () =>
    HttpResponse.json(sessionFixtures.deletionReceipt, {
      status: 202,
      headers: { Location: sessionFixtures.deletionReceipt.statusUrl },
    }),
  ),
  http.get(`${API_BASE}/deletion-requests/:id`, () =>
    HttpResponse.json(sessionFixtures.deletionStatus, {
      headers: { 'Cache-Control': 'private, no-store' },
    }),
  ),
  // MOCK DATA (FE-103). searchPlaces is a read-only POST so the query never
  // reaches a URL log; the handler matches that shape. Delete with BA-022.
  http.post(`${API_BASE}/places/search`, () =>
    HttpResponse.json(placeFixtures.searchPage, {
      headers: { 'Cache-Control': 'private, no-store' },
    }),
  ),

  // MOCK DATA (FE-106). getTrip and replaceTripInterests have no approved
  // example, so these are schema-valid guesses. Delete with BA-031.
  //
  // Stateful on purpose: the interest card reads an ETag, sends it back as
  // If-Match and expects a new one. A handler that returned a fixed version
  // would let a stale-ETag bug pass, because every save would look fresh.
  http.get(`${API_BASE}/trips/:tripId`, () => {
    const trip = currentTrip();
    return HttpResponse.json(trip, { headers: { ETag: `"${String(trip.version)}"` } });
  }),
  http.put(`${API_BASE}/trips/:tripId/interests`, async ({ request }) => {
    const trip = currentTrip();
    // The contract requires If-Match; a mismatch is the 409 the screen recovers
    // from. Modelled here so the conflict path is exercised for real rather
    // than only by a test that forces it.
    if (request.headers.get('If-Match') !== `"${String(trip.version)}"`) {
      return problemResponse('TRIP_CHANGED');
    }
    const body = (await request.json()) as {
      interests: (typeof trip)['interests'];
    };
    tripState = { ...trip, interests: body.interests, version: trip.version + 1 };
    return HttpResponse.json(tripState, {
      headers: { ETag: `"${String(tripState.version)}"` },
    });
  }),
];
