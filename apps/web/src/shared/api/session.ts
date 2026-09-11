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
  useInfiniteQuery,
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

/**
 * Fetches a fresh tab-local CSRF token for the session the cookie already names.
 *
 * This is the recovery for a refresh or a second tab, and it is deliberately
 * NOT a re-bootstrap. POST /demo/sessions reuses a live session, but when the
 * session has expired it creates a NEW anonymous owner
 * (SessionSafetyIT.expiration asserts the owner id differs), which would strand
 * everything the user had. POST /session/csrf needs only the cookie and mints a
 * token without touching the session, so it recovers or it fails honestly.
 *
 * Tokens are independent per tab and the server keeps five before evicting the
 * least recently used, so this must not be called speculatively — see
 * `reissueCsrfToken` for the single-flight wrapper the app uses.
 */
async function requestCsrfToken(): Promise<string> {
  const { data, error, response } = await getApiClient().POST('/session/csrf', {});
  if (!data) fail(error, response);
  csrfToken = data.csrfToken;
  return data.csrfToken;
}

/** In-flight reissue, so concurrent failures share one request. */
let csrfInFlight: Promise<string> | null = null;

/**
 * Reissues the tab's CSRF token, at most one request at a time.
 *
 * Several mutations can fail with CSRF_INVALID at once. Without this they would
 * each ask for a token, and the server evicts the least recently used once a
 * sixth unexpired token exists — so a burst could evict the very token it just
 * handed out, and the other tabs' tokens with it.
 */
export function reissueCsrfToken(): Promise<string> {
  csrfInFlight ??= requestCsrfToken().finally(() => {
    csrfInFlight = null;
  });
  return csrfInFlight;
}

/** Test seam: drops the token so a test can observe the recovery. */
export function clearCsrfTokenForTest(): void {
  csrfToken = null;
}

export const csrfQueryKey = ['session', 'csrf'] as const;

/**
 * Makes sure this tab holds a CSRF token, without minting a session.
 *
 * Why it exists: only the splash screen bootstraps, so a refresh or a deep
 * link onto any other route left `currentCsrfToken()` null and every mutation
 * would have been rejected. Verified by loading /feed directly — the token was
 * null before this.
 *
 * It asks the server only when the token is actually missing, and only for a
 * session the cookie already names. A 401 here means the session is gone, and
 * it stays an error rather than bootstrapping a replacement: a fresh bootstrap
 * on an expired session creates a DIFFERENT anonymous owner
 * (SessionSafetyIT.expiration), silently stranding the user's trips.
 */
export function useCsrfToken(): UseQueryResult<string, Problem | Error> {
  return useQuery({
    queryKey: csrfQueryKey,
    queryFn: () => reissueCsrfToken(),
    enabled: currentCsrfToken() === null,
    staleTime: Infinity,
    // The contract's recovery for a failed reissue is the user's, not a loop.
    retry: false,
  });
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

type CreateOptimizationRequest = components['schemas']['CreateOptimizationRequest'];

/**
 * Queues a preview-only optimization run (FE-501, FR-OPT-01).
 *
 * "Preview-only" is the contract's own word: the run freezes an input revision
 * and reports asynchronously, and a failure "never mutates the trip". Nothing
 * here writes to the trip cache for the same reason — an itinerary changes on
 * APPLY and nowhere else (invariants 3 and 4).
 *
 * The Idempotency-Key is minted by the CALLER, not in here. A key created
 * inside mutationFn would be a fresh one on every attempt, so a retry would
 * queue a second run rather than replaying the first — which is the exact
 * failure invariant 6 exists to prevent.
 *
 * If-Match carries the trip's ETag and the body repeats the version as
 * `inputTripVersion`: the header guards the request, the field records what
 * the run was computed against.
 *
 * The return type is inferred rather than annotated. Writing
 * `useMutation<OptimizationRun, …>` fails to compile with "two different types
 * with this name exist": OptimizationRun nests the OptimizationChange union,
 * and naming it explicitly produces an identity the client's own return type
 * does not match. Letting it infer keeps one identity and the same safety.
 */
export function useCreateOptimization(tripId: string | null) {
  return useMutation({
    mutationFn: async ({
      request,
      etag,
      idempotencyKey,
    }: {
      request: CreateOptimizationRequest;
      etag: string | null;
      idempotencyKey: string;
    }) => {
      if (tripId === null) throw new Error('No trip selected');
      if (etag === null) throw new Error('Cannot optimize without the trip ETag');
      const { data, error, response } = await getApiClient().POST(
        '/trips/{tripId}/optimizations',
        {
          params: {
            path: { tripId },
            header: { 'If-Match': etag, 'Idempotency-Key': idempotencyKey },
          },
          body: request,
        },
      );
      if (!data) fail(error, response);
      return data;
    },
  });
}

type PostDetail = components['schemas']['PostDetail'];
type SavedPostState = components['schemas']['SavedPostState'];

export function postQueryKey(postId: string) {
  return ['post', postId] as const;
}

/**
 * One post and the places it links to (FE-202, FR-PST-01).
 *
 * MOCK DATA today; replaced when BA-032 lands.
 */
export function usePost(
  postId: string | null,
): UseQueryResult<PostDetail, Problem | Error> {
  return useQuery({
    queryKey: postQueryKey(postId ?? ''),
    enabled: postId !== null,
    queryFn: async () => {
      const { data, error, response } = await getApiClient().GET('/posts/{postId}', {
        params: { path: { postId: postId ?? '' } },
      });
      if (!data) fail(error, response);
      return data;
    },
  });
}

/**
 * Bookmarks a post (FR-PST-02).
 *
 * A SavedPost and nothing else. It creates no TripCandidate, no TripItem, and
 * touches no trip — the resource is /posts/{postId}/saved, with no trip in the
 * path and no ETag, so there is no trip for it to change (invariant 1).
 *
 * No Idempotency-Key: the contract puts idempotency in the resource instead,
 * answering 201 when the save is new and 200 when it already existed, and
 * saying which through `duplicate`. A repeat save is a success, not an error.
 */
export function useSavePost(postId: string) {
  const queryClient = useQueryClient();
  return useMutation<SavedPostState, Problem | Error, void>({
    mutationFn: async () => {
      const { data, error, response } = await getApiClient().PUT(
        '/posts/{postId}/saved',
        { params: { path: { postId } } },
      );
      if (!data) fail(error, response);
      return data;
    },
    // A GET for this post may already be in flight — TanStack refetches on
    // window focus by default — and it would resolve with the pre-save body
    // and overwrite the flag we are about to set, silently flipping the
    // button back. Cancelling first is what keeps the toggle honest.
    onMutate: async () => {
      await queryClient.cancelQueries({ queryKey: postQueryKey(postId) });
    },
    onSuccess: (state) => {
      // Only the post's own saved flag moves. Nothing here writes to a trip
      // cache, which is what keeps the three resources apart.
      queryClient.setQueryData(postQueryKey(postId), (current: PostDetail | undefined) =>
        current === undefined ? current : { ...current, saved: state.saved },
      );
      void queryClient.invalidateQueries({ queryKey: ['feed'] });
    },
  });
}

/** Removes the bookmark. 204 whether or not it was there, so this is safe to repeat. */
export function useUnsavePost(postId: string) {
  const queryClient = useQueryClient();
  return useMutation<void, Problem | Error, void>({
    mutationFn: async () => {
      const { error, response } = await getApiClient().DELETE('/posts/{postId}/saved', {
        params: { path: { postId } },
      });
      if (response.status !== 204) fail(error, response);
    },
    onMutate: async () => {
      await queryClient.cancelQueries({ queryKey: postQueryKey(postId) });
    },
    onSuccess: () => {
      queryClient.setQueryData(postQueryKey(postId), (current: PostDetail | undefined) =>
        current === undefined ? current : { ...current, saved: false },
      );
      void queryClient.invalidateQueries({ queryKey: ['feed'] });
    },
  });
}

type FeedPage = components['schemas']['FeedPage'];

/**
 * The personalized feed, one cursor page at a time.
 *
 * useInfiniteQuery rather than useQuery because the contract paginates by an
 * opaque cursor the server mints: the next page is only reachable through the
 * `nextCursor` the previous one returned, so pages have to accumulate in one
 * cache entry rather than replace each other.
 *
 * `tripId` is part of the key. The contract says it "adds candidate state for
 * the selected trip without changing ranking semantics", and the cursor is
 * bound to that selection — reusing a cursor issued for another trip is
 * CURSOR_INVALID, so a different trip has to start its own page one.
 *
 * MOCK DATA today; replaced when BA-032 lands.
 */
export function useFeed(tripId: string | null = null, enabled = true) {
  return useInfiniteQuery<FeedPage, Problem | Error>({
    queryKey: ['feed', tripId],
    // The caller waits until the trip selection is settled. Querying before
    // then sends one request under the wrong key and a second under the right
    // one, and the cursor from the first is bound to a selection the user
    // never had.
    enabled,
    initialPageParam: null as string | null,
    queryFn: async ({ pageParam }) => {
      const cursor = pageParam as string | null;
      const { data, error, response } = await getApiClient().GET('/feed', {
        params: {
          query: {
            ...(cursor === null ? {} : { cursor }),
            ...(tripId === null ? {} : { tripId }),
          },
        },
      });
      if (!data) fail(error, response);
      return data;
    },
    // hasMore is the contract's own flag. Reading only nextCursor would ask
    // for another page whenever the server sent a cursor with hasMore false.
    getNextPageParam: (last) =>
      last.page.hasMore ? (last.page.nextCursor ?? null) : null,
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
 * after a timeout that actually succeeded — must not create a second trip.
 *
 * The key comes from the CALLER, for the same reason it does in
 * useCreateOptimization above. Minting it in here produced a fresh one on
 * every attempt, so the retry after a lost response looked to the server like
 * an unrelated command with an identical body and created a second trip —
 * exactly what the key exists to prevent. The wizard holds one key across
 * retries of the same draft and rotates it when the draft changes.
 *
 * MOCK DATA today; replaced when BA-030 lands.
 */
export function useCreateTrip() {
  return useMutation<
    TripDetail,
    Problem | Error,
    { request: CreateTripRequest; idempotencyKey: string }
  >({
    mutationFn: async ({ request, idempotencyKey }) => {
      const { data, error, response } = await getApiClient().POST('/trips', {
        body: request,
        params: { header: { 'Idempotency-Key': idempotencyKey } },
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
 * because a repeated submit must not add the place twice (invariant 6). The
 * key comes from the CALLER for the reason useCreateOptimization spells out:
 * one minted in here is new on every attempt, so the retry after a lost
 * response reads as a fresh command and the place lands on the day twice.
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
    { item: AddTripItemRequest; etag: string | null; idempotencyKey: string }
  >({
    mutationFn: async ({ item, etag, idempotencyKey }) => {
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
              'Idempotency-Key': idempotencyKey,
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

type SetConstraintInput = components['schemas']['SetConstraintInput'];

/**
 * Sets one item lock (FE-307, FR-CON-01/FR-CON-03).
 *
 * One request per lock, because the contract gives each its own endpoint:
 * PUT /constraints/{constraintType}, with the body's `type` required to equal
 * the path's. There is no way to set two at once and nothing here tries —
 * that is invariant 7's independence expressed as a route, not as a promise.
 *
 * Mirrors useRemoveItemConstraint: If-Match is required, and the result
 * carries the whole trip plus a new ETag, so the cache takes both.
 */
export function useSetItemConstraint(tripId: string | null) {
  const queryClient = useQueryClient();
  return useMutation<
    TripMutationWithETag,
    Problem | Error,
    { itemId: string; constraint: SetConstraintInput; etag: string | null }
  >({
    mutationFn: async ({ itemId, constraint, etag }) => {
      if (tripId === null) throw new Error('No trip selected');
      if (etag === null) throw new Error('Cannot change a lock without the trip ETag');
      const { data, error, response } = await getApiClient().PUT(
        '/trips/{tripId}/items/{itemId}/constraints/{constraintType}',
        {
          params: {
            // The path decides which lock this is; the body repeats it because
            // the contract makes `type` the union's discriminator.
            path: { tripId, itemId, constraintType: constraint.type },
            header: { 'If-Match': etag },
          },
          body: constraint,
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

type UpdateTripItemRequest = components['schemas']['UpdateTripItemRequest'];
type ReorderTripItemsRequest = components['schemas']['ReorderTripItemsRequest'];
type ReplaceTripItemRequest = components['schemas']['ReplaceTripItemRequest'];
type RemoveDisposition = 'RESTORE_CANDIDATE' | 'REMOVE';

/**
 * Applies a trip mutation result to the cache.
 *
 * Only the removal path touches the candidate list, and only when it restores
 * one: `trip.candidates` is a page rather than the whole set (TripScreen reads
 * `candidateCount` for the total), so the list has to be refetched rather than
 * read out of this response.
 */
function useApplyTripMutation(tripId: string | null) {
  const queryClient = useQueryClient();
  return (result: TripMutationResult, etag: string | null, touchedCandidates = false) => {
    if (tripId === null) return;
    queryClient.setQueryData(tripQueryKey(tripId), { trip: result.trip, etag });
    if (touchedCandidates) {
      void queryClient.invalidateQueries({ queryKey: candidatesQueryKey(tripId) });
    }
  };
}

/**
 * Edits one scheduled item's date, position, time, duration or note
 * (FR-ITM-03, FR-ITM-04).
 *
 * merge-patch, not JSON: the contract declares application/merge-patch+json
 * and openapi-fetch sends application/json regardless of the typed media key,
 * so the header is set explicitly or the server answers 415.
 *
 * No Idempotency-Key. The contract declares none on this operation — unlike
 * add, reorder and replace — and sending one "for consistency" is a header the
 * server did not ask for.
 *
 * `date` and `position` are not nullable in the schema; only startTime,
 * durationMinutes and note clear with null. An empty patch is minProperties:1
 * and would be rejected, so the caller must send at least one field.
 */
export function useUpdateTripItem(tripId: string | null) {
  const apply = useApplyTripMutation(tripId);
  return useMutation<
    TripMutationWithETag,
    Problem | Error,
    { itemId: string; patch: UpdateTripItemRequest; etag: string | null }
  >({
    mutationFn: async ({ itemId, patch, etag }) => {
      if (tripId === null) throw new Error('No trip selected');
      if (etag === null) throw new Error('Cannot edit an item without the trip ETag');
      if (Object.keys(patch).length === 0) {
        throw new Error('An empty item patch is rejected by the contract');
      }
      const { data, error, response } = await getApiClient().PATCH(
        '/trips/{tripId}/items/{itemId}',
        {
          body: patch,
          params: { path: { tripId, itemId }, header: { 'If-Match': etag } },
          headers: { 'Content-Type': 'application/merge-patch+json' },
        },
      );
      if (!data) fail(error, response);
      return { result: data, etag: response.headers.get('ETag') };
    },
    onSuccess: ({ result, etag }) => {
      apply(result, etag);
    },
  });
}

/**
 * Moves and reorders items in one atomic request (FR-ITM-03, FR-ITM-05).
 *
 * The contract has no separate "move" operation: this one carries the complete
 * ordering for every day it touches and the server applies it as a unit. A
 * cross-day move sent as a sequence of single-item edits would collide with the
 * (trip, date, position) uniqueness partway through and leave the trip in a
 * state no user asked for.
 *
 * The key is minted by the caller, not here, so a retry of the same user action
 * reuses it rather than counting as a fresh command.
 */
export function useReorderTripItems(tripId: string | null) {
  const apply = useApplyTripMutation(tripId);
  return useMutation<
    TripMutationWithETag,
    Problem | Error,
    {
      order: ReorderTripItemsRequest['items'];
      etag: string | null;
      idempotencyKey: string;
    }
  >({
    mutationFn: async ({ order, etag, idempotencyKey }) => {
      if (tripId === null) throw new Error('No trip selected');
      if (etag === null) throw new Error('Cannot reorder without the trip ETag');
      const { data, error, response } = await getApiClient().POST(
        '/trips/{tripId}/items/reorder',
        {
          body: { items: order },
          params: {
            path: { tripId },
            header: { 'If-Match': etag, 'Idempotency-Key': idempotencyKey },
          },
        },
      );
      if (!data) fail(error, response);
      return { result: data, etag: response.headers.get('ETag') };
    },
    onSuccess: ({ result, etag }) => {
      apply(result, etag);
    },
  });
}

/**
 * Swaps an item's place after the user has compared the two (FR-ITM-08).
 *
 * `preserveDateTime` is omitted rather than sent as `true`: the contract's
 * default already is true, and sending `false` moves the item's schedule, which
 * has to be an explicit user choice rather than a serialized form default.
 *
 * `relationId` links the replacement back to the listRelatedPlaces row that
 * suggested it, so the server can check the evidence the user actually saw.
 */
export function useReplaceTripItem(tripId: string | null) {
  const apply = useApplyTripMutation(tripId);
  return useMutation<
    TripMutationWithETag,
    Problem | Error,
    {
      itemId: string;
      replacement: ReplaceTripItemRequest;
      etag: string | null;
      idempotencyKey: string;
    }
  >({
    mutationFn: async ({ itemId, replacement, etag, idempotencyKey }) => {
      if (tripId === null) throw new Error('No trip selected');
      if (etag === null) throw new Error('Cannot replace an item without the trip ETag');
      const { data, error, response } = await getApiClient().POST(
        '/trips/{tripId}/items/{itemId}/replace',
        {
          body: replacement,
          params: {
            path: { tripId, itemId },
            header: { 'If-Match': etag, 'Idempotency-Key': idempotencyKey },
          },
        },
      );
      if (!data) fail(error, response);
      return { result: data, etag: response.headers.get('ETag') };
    },
    onSuccess: ({ result, etag }) => {
      apply(result, etag);
    },
  });
}

/**
 * Removes an item, either restoring its candidate or dropping it (FR-ITM-06).
 *
 * `disposition` is a required query parameter, not a body field and not
 * optional: the contract offers RESTORE_CANDIDATE and REMOVE and forces the
 * caller to say which. Defaulting it here would decide on the user's behalf
 * whether their saved place survives.
 *
 * Restoring a candidate is the one item mutation that changes the candidate
 * list, so it is the only one that invalidates it.
 */
export function useRemoveTripItem(tripId: string | null) {
  const apply = useApplyTripMutation(tripId);
  return useMutation<
    TripMutationWithETag,
    Problem | Error,
    { itemId: string; disposition: RemoveDisposition; etag: string | null }
  >({
    mutationFn: async ({ itemId, disposition, etag }) => {
      if (tripId === null) throw new Error('No trip selected');
      if (etag === null) throw new Error('Cannot remove an item without the trip ETag');
      const { data, error, response } = await getApiClient().DELETE(
        '/trips/{tripId}/items/{itemId}',
        {
          params: {
            path: { tripId, itemId },
            query: { disposition },
            header: { 'If-Match': etag },
          },
        },
      );
      if (!data) fail(error, response);
      return { result: data, etag: response.headers.get('ETag') };
    },
    onSuccess: ({ result, etag }, { disposition }) => {
      apply(result, etag, disposition === 'RESTORE_CANDIDATE');
    },
  });
}

type AddCandidateRequest = components['schemas']['AddCandidateRequest'];
type CandidateSaveResult = components['schemas']['CandidateSaveResult'];

/**
 * Saves a place as a candidate — no date, no schedule change (FR-CAN-02).
 *
 * This is the other half of the place-add choice: a chosen day goes to
 * addTripItem and creates a TripItem, while "미정" comes here and creates a
 * TripCandidate. Invariants 1 and 2 are that distinction, so the two are
 * separate hooks rather than one that branches on whether a date is set.
 *
 * The response carries `tripScheduleChanged`, which the contract sets false for
 * this operation: saving a candidate never touches the itinerary. Only the
 * candidate list is invalidated here.
 *
 * Carries an Idempotency-Key because a repeated submit must not save the place
 * twice; the server answers 200 with `duplicate: true` when it already exists.
 */
export function useAddTripCandidate(tripId: string | null) {
  const queryClient = useQueryClient();
  return useMutation<
    CandidateSaveResult,
    Problem | Error,
    { request: AddCandidateRequest; idempotencyKey: string }
  >({
    mutationFn: async ({ request, idempotencyKey }) => {
      if (tripId === null) throw new Error('No trip selected');
      const { data, error, response } = await getApiClient().POST(
        '/trips/{tripId}/candidates',
        {
          body: request,
          params: {
            path: { tripId },
            header: { 'Idempotency-Key': idempotencyKey },
          },
        },
      );
      if (!data) fail(error, response);
      return data;
    },
    onSuccess: () => {
      if (tripId === null) return;
      // The itinerary is untouched by construction, so only the candidate list
      // is refetched.
      void queryClient.invalidateQueries({ queryKey: candidatesQueryKey(tripId) });
    },
  });
}

type RelatedPlaceResult = components['schemas']['RelatedPlaceResult'];

/**
 * Places that could stand in for this one (FR-ITM-08).
 *
 * CHECKING is an in-progress answer, so it is polled; the other four states are
 * final and stop it. Without that a CHECKING panel would sit showing a
 * skeleton the server had already resolved.
 *
 * Read fresh rather than cached long: each row carries a provenance whose
 * `comparisonEligible` is computed per request, and a provider incident can
 * flip it between two reads of the same row. A stale copy would let the screen
 * show a comparison the server no longer permits.
 */
export function useRelatedPlaces(
  placeId: string | null,
): UseQueryResult<RelatedPlaceResult, Problem | Error> {
  return useQuery({
    queryKey: ['places', placeId ?? '', 'related'],
    enabled: placeId !== null,
    gcTime: 0,
    staleTime: 0,
    refetchInterval: (query) => (query.state.data?.state === 'CHECKING' ? 2000 : false),
    queryFn: async () => {
      const { data, error, response } = await getApiClient().GET(
        '/places/{placeId}/related',
        { params: { path: { placeId: placeId ?? '' } } },
      );
      if (!data) fail(error, response);
      return data;
    },
  });
}
