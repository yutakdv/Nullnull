// Anonymous session bootstrap (FR-SES-01, FR-SES-02, FR-ONB-01).
//
// P0 has no sign-up: the server issues an anonymous owner and a session cookie,
// and every later request rides that cookie. The client never invents or sends
// an owner id — the server derives it from the authenticated session
// (CLAUDE.md invariant 11), so nothing here writes one.
//
// The CSRF token is held in memory only. It is not a secret to persist: a token
// in localStorage outlives the session it belongs to and would be attached to
// requests the server has already stopped honouring.
import { useMutation, useQuery, type UseQueryResult } from '@tanstack/react-query';
import { createApiClient, type components } from '@nullnull/api-client';
import { toProblem, type Problem } from './problem.js';

type SessionBootstrap = components['schemas']['SessionBootstrap'];
type OwnerProfile = components['schemas']['OwnerProfile'];

let csrfToken: string | null = null;

/** The token the client middleware attaches to mutating requests. */
export function currentCsrfToken(): string | null {
  return csrfToken;
}

// Same-origin in every environment: the dev server proxies /api and the
// deployed app is served beside the API. Resolved against the document origin
// because openapi-fetch hands the URL to `fetch`, which needs an absolute one.
//
// Built on first use rather than at import time so the origin is read after the
// document exists, which is what makes this module importable from tests.
let client: ReturnType<typeof createApiClient> | null = null;

export function apiBaseUrl(): string {
  return new URL('/api/v1', location.origin).toString();
}

export function getApiClient(): ReturnType<typeof createApiClient> {
  client ??= createApiClient({
    baseUrl: apiBaseUrl(),
    getCsrfToken: currentCsrfToken,
  });
  return client;
}

/**
 * Throws the server's Problem when there is one, so the boundary and the retry
 * policy both see a typed failure instead of a bare Error.
 */
function fail(error: unknown, response: Response): never {
  const problem = toProblem(error);
  if (problem) throw problem;
  throw new Error(`Request failed with status ${String(response.status)}`);
}

async function bootstrapSession(): Promise<SessionBootstrap> {
  const { data, error, response } = await getApiClient().POST('/demo/sessions', {});
  if (!data) fail(error, response);
  csrfToken = data.csrfToken;
  return data;
}

export const sessionQueryKey = ['session', 'bootstrap'] as const;

/**
 * Bootstraps once per app load. The splash screen renders this state; the retry
 * is the user's, never automatic — an unauthenticated POST that repeats itself
 * is how duplicate anonymous owners get created.
 */
export function useSessionBootstrap(): UseQueryResult<SessionBootstrap, Problem | Error> {
  return useQuery({
    queryKey: sessionQueryKey,
    queryFn: bootstrapSession,
    staleTime: Infinity,
    retry: false,
  });
}

type UpdatePreferencesRequest = components['schemas']['UpdatePreferencesRequest'];

/**
 * Merge-patches the owner profile. The body carries only the fields being
 * changed: UpdatePreferencesRequest is `additionalProperties: false`, and
 * BA-003 turns unknown fields into 400 INVALID_REQUEST, so nothing extra may
 * ride along.
 *
 * A failure is non-blocking for the caller — the local draft has already
 * applied — but it is surfaced rather than swallowed so the screen can say the
 * choice did not reach the server.
 */
export function useUpdatePreferences() {
  return useMutation<OwnerProfile, Problem | Error, UpdatePreferencesRequest>({
    mutationFn: async (patch) => {
      const { data, error, response } = await getApiClient().PATCH('/me', {
        body: patch,
      });
      if (!data) fail(error, response);
      return data;
    },
  });
}

type TripPage = components['schemas']['TripPage'];
type OptimizationHistoryPage = components['schemas']['OptimizationHistoryPage'];

/**
 * The owner's trips, newest state first.
 *
 * Served by MOCK DATA today: listTrips has no approved example, so the fixture
 * behind the msw handler is a schema-valid guess (packages/contracts). The call
 * itself is real, so when BA-030 lands only the handler and fixture go away.
 */
export function useTrips(): UseQueryResult<TripPage, Problem | Error> {
  return useQuery({
    queryKey: ['trips'],
    queryFn: async () => {
      const { data, error, response } = await getApiClient().GET('/trips', {});
      if (!data) fail(error, response);
      return data;
    },
  });
}

/**
 * Optimization history for the profile screen.
 *
 * The contract limits this to status, timestamps, target trip and run link —
 * it never returns itinerary content, and nothing here stores any (CLAUDE.md
 * P0 decision on 최적화 이력).
 *
 * Also MOCK DATA today; replaced when BA-053 lands.
 */
export function useOptimizationHistory(): UseQueryResult<
  OptimizationHistoryPage,
  Problem | Error
> {
  return useQuery({
    queryKey: ['optimizations', 'history'],
    queryFn: async () => {
      const { data, error, response } = await getApiClient().GET('/optimizations', {});
      if (!data) fail(error, response);
      return data;
    },
  });
}

type PlaceSearchPage = components['schemas']['PlaceSearchPage'];
type PlaceSearchRequest = components['schemas']['PlaceSearchRequest'];

/**
 * Canonical place search.
 *
 * A read-only POST by contract: the query is free-form text, and putting it in
 * a URL would leak it into CDN, proxy and browser history logs. The response is
 * `no-store` for the same reason, so this is not cached across sessions either.
 *
 * MOCK DATA today; replaced when BA-022 lands.
 */
export function usePlaceSearch(
  query: string,
): UseQueryResult<PlaceSearchPage, Problem | Error> {
  const trimmed = query.trim();
  return useQuery({
    // The query text is part of the cache key but never leaves the client in a
    // URL; the request carries it in the body.
    queryKey: ['places', 'search', trimmed],
    enabled: trimmed.length > 0,
    queryFn: async () => {
      const body: PlaceSearchRequest = { query: trimmed };
      const { data, error, response } = await getApiClient().POST('/places/search', {
        body,
      });
      if (!data) fail(error, response);
      return data;
    },
  });
}
