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
import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseQueryResult,
} from '@tanstack/react-query';
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
        // The contract declares application/merge-patch+json and BA-011 enforces
        // it with `consumes`; openapi-fetch would otherwise send
        // application/json and every save would come back 415.
        headers: { 'Content-Type': 'application/merge-patch+json' },
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

type CreateTripRequest = components['schemas']['CreateTripRequest'];
type TripDetail = components['schemas']['TripDetail'];

/**
 * Creates a trip from the wizard draft.
 *
 * Carries an Idempotency-Key because the contract declares one and invariant 6
 * requires it for retryable commands: a repeated submit — a double tap, a retry
 * after a timeout that actually succeeded — must not create a second trip. The
 * key is generated per attempt and reused for that attempt only.
 *
 * MOCK DATA today; replaced when BA-030 lands.
 */
export function useCreateTrip() {
  return useMutation<TripDetail, Problem | Error, CreateTripRequest>({
    mutationFn: async (request) => {
      const { data, error, response } = await getApiClient().POST('/trips', {
        body: request,
        params: { header: { 'Idempotency-Key': crypto.randomUUID() } },
      });
      if (!data) fail(error, response);
      return data;
    },
  });
}

type DeletionReceipt = components['schemas']['DeletionReceipt'];
type DeletionRequestStatus = components['schemas']['DeletionRequestStatus'];

// The deletion status token lives here and nowhere else.
//
// The contract is explicit: "store in memory only and never log it". It is a
// bearer token for a resource whose session has already been revoked, so
// persisting it would outlive the session it replaced and leave a credential on
// the device for something the user asked to erase.
let deletionStatusToken: string | null = null;

export function currentDeletionToken(): string | null {
  return deletionStatusToken;
}

/** Drops the token, e.g. when the user leaves the receipt screen. */
export function forgetDeletionToken(): void {
  deletionStatusToken = null;
}

/**
 * Requests deletion of the session and everything it owns.
 *
 * Returns 202: the job is queued, not finished. The session and its CSRF tokens
 * are revoked immediately, so every later call on this device is unauthenticated
 * — which is why the status route uses its own token rather than the cookie.
 *
 * Carries an Idempotency-Key: for 24 hours the same key replays the same
 * receipt instead of queuing a second deletion (invariant 6).
 */
export function useRequestDeletion() {
  return useMutation<DeletionReceipt, Problem | Error, void>({
    mutationFn: async () => {
      const { data, error, response } = await getApiClient().DELETE('/session', {
        params: { header: { 'Idempotency-Key': crypto.randomUUID() } },
      });
      if (!data) fail(error, response);
      deletionStatusToken = data.statusToken;
      return data;
    },
  });
}

/**
 * Polls the deletion job.
 *
 * Authenticated by the receipt token, not the cookie — the cookie is already
 * revoked. Disabled until a receipt exists, so it never fires unauthenticated.
 */
export function useDeletionStatus(
  requestId: string | null,
): UseQueryResult<DeletionRequestStatus, Problem | Error> {
  return useQuery({
    queryKey: ['deletion', requestId],
    enabled: requestId !== null && deletionStatusToken !== null,
    // Terminal states stop polling; the screen decides by reading status.
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      return status === 'COMPLETED' || status === 'FAILED' ? false : 3000;
    },
    queryFn: async () => {
      const token = deletionStatusToken;
      if (!token) throw new Error('No deletion receipt token');
      const { data, error, response } = await getApiClient().GET(
        '/deletion-requests/{deletionRequestId}',
        {
          params: { path: { deletionRequestId: requestId ?? '' } },
          // A security scheme, not a parameter, so the generated types do not
          // carry it; openapi-fetch takes it as a request header instead.
          headers: { 'X-Deletion-Status-Token': token },
        },
      );
      if (!data) fail(error, response);
      return data;
    },
  });
}

type ReplaceInterestsRequest = components['schemas']['ReplaceInterestsRequest'];

/**
 * A trip plus the ETag it was read at.
 *
 * The ETag is carried beside the body rather than derived from `version`
 * because If-Match is about *this representation*: the server owns the
 * validator's format, and a client that rebuilds it from a field has quietly
 * decided the two can never disagree. When they do, the rebuilt one silently
 * overwrites a concurrent edit — exactly what invariant 6 exists to stop.
 */
export interface TripWithETag {
  trip: TripDetail;
  etag: string | null;
}

export function tripQueryKey(tripId: string) {
  return ['trip', tripId] as const;
}

/** Reads one trip, keeping the ETag needed to mutate it. */
export function useTrip(
  tripId: string | null,
): UseQueryResult<TripWithETag, Problem | Error> {
  return useQuery({
    queryKey: tripQueryKey(tripId ?? ''),
    enabled: tripId !== null,
    queryFn: async () => {
      const { data, error, response } = await getApiClient().GET('/trips/{tripId}', {
        params: { path: { tripId: tripId ?? '' } },
      });
      if (!data) fail(error, response);
      return { trip: data, etag: response.headers.get('ETag') };
    },
  });
}

/**
 * Replaces the whole interest set for a trip (FR-PRO-05).
 *
 * A replace, not a merge: the contract's PUT takes the complete set, so an
 * empty array is a real value meaning "no interests" rather than a no-op.
 *
 * If-Match is required by the contract and not optional here either. Without a
 * known ETag this refuses to send rather than falling back to an unconditional
 * write — a blind PUT is how one device's edit erases another's.
 */
export function useReplaceTripInterests(tripId: string | null) {
  const queryClient = useQueryClient();
  return useMutation<
    TripWithETag,
    Problem | Error,
    { interests: ReplaceInterestsRequest['interests']; etag: string | null }
  >({
    mutationFn: async ({ interests, etag }) => {
      if (tripId === null) throw new Error('No trip selected');
      if (etag === null) {
        throw new Error('Cannot replace interests without the trip ETag');
      }
      const { data, error, response } = await getApiClient().PUT(
        '/trips/{tripId}/interests',
        {
          params: { path: { tripId }, header: { 'If-Match': etag } },
          body: { interests },
        },
      );
      if (!data) fail(error, response);
      return { trip: data, etag: response.headers.get('ETag') };
    },
    onSuccess: (result) => {
      // Seed the cache with the response the server just returned, so the new
      // ETag is in hand for the next edit without a refetch round-trip.
      if (tripId !== null) queryClient.setQueryData(tripQueryKey(tripId), result);
    },
  });
}

type UpdateTripRequest = components['schemas']['UpdateTripRequest'];

/**
 * Patches trip metadata (FR-TRP-05).
 *
 * merge-patch, like PATCH /me: the contract declares
 * application/merge-patch+json and the server enforces it with `consumes`, so
 * openapi-fetch's application/json default would come back 415.
 *
 * If-Match is required by the contract and required here. Without a known ETag
 * this refuses rather than writing unconditionally — a blind PATCH is how one
 * device's edit erases another's.
 */
export function useUpdateTrip(tripId: string | null) {
  const queryClient = useQueryClient();
  return useMutation<
    TripWithETag,
    Problem | Error,
    { patch: UpdateTripRequest; etag: string | null }
  >({
    mutationFn: async ({ patch, etag }) => {
      if (tripId === null) throw new Error('No trip selected');
      if (etag === null) throw new Error('Cannot update a trip without its ETag');
      const { data, error, response } = await getApiClient().PATCH('/trips/{tripId}', {
        body: patch,
        params: { path: { tripId }, header: { 'If-Match': etag } },
        headers: { 'Content-Type': 'application/merge-patch+json' },
      });
      if (!data) fail(error, response);
      return { trip: data, etag: response.headers.get('ETag') };
    },
    onSuccess: (result) => {
      if (tripId !== null) queryClient.setQueryData(tripQueryKey(tripId), result);
    },
  });
}

type CandidatePage = components['schemas']['CandidatePage'];
type CandidateMatchResult = components['schemas']['CandidateMatchResult'];
type AddTripItemRequest = components['schemas']['AddTripItemRequest'];
type TripMutationResult = components['schemas']['TripMutationResult'];

export function candidatesQueryKey(tripId: string) {
  return ['trip', tripId, 'candidates'] as const;
}

/** Candidates saved against a trip (FR-CAN-05). */
export function useTripCandidates(
  tripId: string | null,
): UseQueryResult<CandidatePage, Problem | Error> {
  return useQuery({
    queryKey: candidatesQueryKey(tripId ?? ''),
    enabled: tripId !== null,
    queryFn: async () => {
      const { data, error, response } = await getApiClient().GET(
        '/trips/{tripId}/candidates',
        { params: { path: { tripId: tripId ?? '' } } },
      );
      if (!data) fail(error, response);
      return data;
    },
  });
}

/**
 * Eligible dates for scheduling one candidate (FR-CAN-07).
 *
 * CHECKING is an in-progress answer, so it is polled; the other four states are
 * final and stop the polling. Without that a `CHECKING` panel would sit there
 * forever showing a skeleton the server had already resolved.
 */
export function useCandidateMatches(
  tripId: string | null,
  candidateId: string | null,
): UseQueryResult<CandidateMatchResult, Problem | Error> {
  return useQuery({
    queryKey: ['trip', tripId ?? '', 'candidates', candidateId ?? '', 'matches'],
    enabled: tripId !== null && candidateId !== null,
    refetchInterval: (query) => (query.state.data?.state === 'CHECKING' ? 2000 : false),
    queryFn: async () => {
      const { data, error, response } = await getApiClient().GET(
        '/trips/{tripId}/candidates/{candidateId}/matches',
        {
          params: {
            path: { tripId: tripId ?? '', candidateId: candidateId ?? '' },
          },
        },
      );
      if (!data) fail(error, response);
      return data;
    },
  });
}

/**
 * Schedules a candidate as a trip item (FR-ITM-02).
 *
 * One request, not two. The contract's 201 is "item added and candidate marked
 * scheduled", which is the atomic transition invariant 5 requires: a client
 * that added the item and then updated the candidate could leave a scheduled
 * item beside an ACTIVE candidate if the second call failed.
 *
 * Carries If-Match because it changes the schedule, and an Idempotency-Key
 * because a repeated submit must not add the place twice (invariant 6).
 */
export interface TripMutationWithETag {
  result: TripMutationResult;
  etag: string | null;
}

export function useAddTripItem(tripId: string | null) {
  const queryClient = useQueryClient();
  return useMutation<
    TripMutationWithETag,
    Problem | Error,
    { item: AddTripItemRequest; etag: string | null }
  >({
    mutationFn: async ({ item, etag }) => {
      if (tripId === null) throw new Error('No trip selected');
      if (etag === null) throw new Error('Cannot add an item without the trip ETag');
      const { data, error, response } = await getApiClient().POST(
        '/trips/{tripId}/items',
        {
          body: item,
          params: {
            path: { tripId },
            header: {
              'If-Match': etag,
              'Idempotency-Key': crypto.randomUUID(),
            },
          },
        },
      );
      if (!data) fail(error, response);
      // The ETag rides beside the body rather than inside it: TripMutationResult
      // is additionalProperties:false, so folding a header into it would be a
      // shape the contract does not describe.
      return { result: data, etag: response.headers.get('ETag') };
    },
    onSuccess: ({ result, etag }) => {
      if (tripId === null) return;
      // The response carries the whole trip, so the schedule updates without a
      // refetch. The candidate list does need one: its statuses changed server
      // -side as part of the same transaction.
      queryClient.setQueryData(tripQueryKey(tripId), { trip: result.trip, etag });
      void queryClient.invalidateQueries({ queryKey: candidatesQueryKey(tripId) });
    },
  });
}

/**
 * Dismisses a candidate (FR-CAN-06).
 *
 * No If-Match: the contract does not ask for one, because dismissing a
 * candidate does not touch the schedule — a candidate is not an item
 * (invariant 1), and the operation's own summary says so.
 */
export function useRemoveTripCandidate(tripId: string | null) {
  const queryClient = useQueryClient();
  return useMutation<void, Problem | Error, { candidateId: string }>({
    mutationFn: async ({ candidateId }) => {
      if (tripId === null) throw new Error('No trip selected');
      const { error, response } = await getApiClient().DELETE(
        '/trips/{tripId}/candidates/{candidateId}',
        { params: { path: { tripId, candidateId } } },
      );
      if (!response.ok) fail(error, response);
    },
    onSuccess: () => {
      if (tripId !== null) {
        void queryClient.invalidateQueries({ queryKey: candidatesQueryKey(tripId) });
      }
    },
  });
}

type ConstraintType = components['schemas']['ConstraintType'];

/**
 * Releases one item lock (FR-CON-02, FR-CON-05).
 *
 * One constraint per request, which is the contract's own shape:
 * DELETE /constraints/{constraintType}. Invariant 7 says the four locks are
 * independent and none is released automatically, so there is deliberately no
 * "clear all" here — a caller wanting two gone asks twice, and the user
 * confirms each.
 *
 * If-Match because it changes the schedule and bumps the trip version.
 */
export function useRemoveItemConstraint(tripId: string | null) {
  const queryClient = useQueryClient();
  return useMutation<
    TripMutationWithETag,
    Problem | Error,
    { itemId: string; constraintType: ConstraintType; etag: string | null }
  >({
    mutationFn: async ({ itemId, constraintType, etag }) => {
      if (tripId === null) throw new Error('No trip selected');
      if (etag === null) throw new Error('Cannot change a lock without the trip ETag');
      const { data, error, response } = await getApiClient().DELETE(
        '/trips/{tripId}/items/{itemId}/constraints/{constraintType}',
        {
          params: {
            path: { tripId, itemId, constraintType },
            header: { 'If-Match': etag },
          },
        },
      );
      if (!data) fail(error, response);
      return { result: data, etag: response.headers.get('ETag') };
    },
    onSuccess: ({ result, etag }) => {
      if (tripId === null) return;
      queryClient.setQueryData(tripQueryKey(tripId), { trip: result.trip, etag });
    },
  });
}
