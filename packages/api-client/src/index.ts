// Generated-client consumption boundary.
//
// Every HTTP call in apps/web goes through this module. Response shapes come
// from src/generated/openapi.ts, which is produced from docs/api/openapi.yaml
// by `npm run api:generate`. Never hand-write a response type or widen one to
// `any` — regenerate instead (.claude/rules/frontend.md).
//
// Cross-cutting request concerns live here so screens cannot forget them:
//   - session cookie is always sent (credentials: 'include')
//   - CSRF token is attached to every mutating request
//   - If-Match / Idempotency-Key are passed through per call
// Owner identity is never sent by the client; the server derives it from the
// authenticated session (CLAUDE.md invariant 11).

import createClient, { type Middleware } from 'openapi-fetch';
import type { paths } from './generated/openapi.js';

export type { paths, components, operations } from './generated/openapi.js';

const MUTATING_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

export interface ApiClientOptions {
  /** Same-origin by default; the dev server proxies to the API. */
  baseUrl?: string;
  /**
   * Returns the current CSRF token, or null before bootstrap completes.
   * Kept as a callback so the token can rotate without rebuilding the client.
   */
  getCsrfToken?: () => string | null;
}

/** Header name agreed with the API; see docs/api/README.md. */
export const CSRF_HEADER = 'X-CSRF-Token';

function csrfMiddleware(getToken: () => string | null): Middleware {
  return {
    onRequest({ request }) {
      if (!MUTATING_METHODS.has(request.method.toUpperCase())) {
        return undefined;
      }
      const token = getToken();
      if (token) {
        request.headers.set(CSRF_HEADER, token);
      }
      return request;
    },
  };
}

export function createApiClient(options: ApiClientOptions = {}) {
  const client = createClient<paths>({
    baseUrl: options.baseUrl ?? '/',
    // The session cookie is HttpOnly; the browser must attach it itself.
    credentials: 'include',
  });

  if (options.getCsrfToken) {
    client.use(csrfMiddleware(options.getCsrfToken));
  }

  return client;
}

export type ApiClient = ReturnType<typeof createApiClient>;
