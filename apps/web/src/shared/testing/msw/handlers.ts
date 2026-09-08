// MSW handlers backed by @nullnull/contracts fixtures.
//
// Every response body here is ajv-validated against docs/api/openapi.yaml by
// packages/contracts (see its README). Handlers must not build bodies inline —
// an inline object is a hand-written model with nothing checking it.
import { problemFixtures, sessionFixtures } from '@nullnull/contracts';
import { http, HttpResponse } from 'msw';
import type { ProblemCode } from '../../api/index.js';

/** The dev proxy and the deployed app both serve the API under /api/v1. */
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
];
