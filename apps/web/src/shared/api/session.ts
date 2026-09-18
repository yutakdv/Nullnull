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
import { isProblem, toProblem, type Problem } from './problem.js';

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
  const queryClient = useQueryClient();
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
    onSuccess: (owner) => {
      // The bootstrap entry holds the owner profile, and for `activeTripId`
      // that cache IS the source of truth — AppShell reads it to decide where
      // the 내 여행 tab goes. Leaving it stale means the tab keeps sending the
      // traveller to the fallback until the next full load.
      //
      // Written from the RESPONSE rather than from the patch: a merge patch
      // says what changed, and the server answers with the whole profile after
      // applying it. Merging the request instead would copy a value the server
      // may have rejected or normalised.
      //
      // setQueryData, not invalidateQueries: bootstrapping again would POST
      // /demo/sessions, and `useSessionBootstrap` exists precisely to keep that
      // to one call per load (a repeat mints a second anonymous owner).
      queryClient.setQueryData<SessionBootstrap>(sessionQueryKey, (current) =>
        current ? { ...current, owner } : current,
      );
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

type OptimizationStatus = components['schemas']['OptimizationStatus'];

/** The two statuses the server is still working on. Everything else is settled. */
const RUNNING_STATUSES: OptimizationStatus[] = ['QUEUED', 'RUNNING'];

export function isRunning(status: OptimizationStatus): boolean {
  return RUNNING_STATUSES.includes(status);
}

/** Seconds the server asked us to wait, clamped to something sane. */
function retryAfterMs(header: string | null): number {
  const seconds = Number(header);
  if (!Number.isFinite(seconds) || seconds <= 0) return 2000;
  return Math.min(Math.max(seconds, 1), 30) * 1000;
}

export const optimizationQueryKey = (runId: string) => ['optimizations', runId];

/**
 * One optimization run, polled while the server is still computing it (FE-502,
 * FR-OPT-03).
 *
 * Polling stops the moment the run reaches a terminal status. QUEUED and
 * RUNNING are the only two the server is still working on; READY, APPLIED,
 * KEPT, REVERTED, FAILED and EXPIRED are settled, and continuing to ask would
 * be a request per interval forever on a screen the user may leave open.
 *
 * The interval comes from the response's own Retry-After, which the contract
 * sends "for QUEUED/RUNNING responses", rather than from a number invented
 * here. It is clamped because the value is server-controlled and a zero would
 * spin.
 *
 * A 410 PREVIEW_EXPIRED is not retried: the contract is explicit that an
 * undecided preview expires and that this "does not apply to an already
 * recorded decision", so there is nothing to wait for. It surfaces as a
 * Problem the screen renders as its own state.
 *
 * Nothing here writes to the trip cache. A run is a preview until the user
 * applies it, and reading one must not move an itinerary (invariants 3 and 4).
 *
 * MOCK DATA today; replaced when BA-050 lands.
 *
 * The return type is inferred rather than annotated, for the same reason
 * useCreateOptimization gives below: naming OptimizationRun in the signature
 * fails to compile with "two different types with this name exist", because
 * the run nests the OptimizationChange union.
 */
export function useOptimization(runId: string | null) {
  return useQuery({
    queryKey: optimizationQueryKey(runId ?? ''),
    enabled: runId !== null,
    queryFn: async () => {
      if (runId === null) throw new Error('No run selected');
      const { data, error, response } = await getApiClient().GET(
        '/optimizations/{runId}',
        { params: { path: { runId } } },
      );
      if (!data) fail(error, response);
      return { run: data, retryAfter: response.headers.get('Retry-After') };
    },
    select: (result) => result.run,
    refetchInterval: (query) => {
      const result = query.state.data;
      if (!result || !isRunning(result.run.status)) return false;
      return retryAfterMs(result.retryAfter);
    },
    // A run that is still queued is not an error and not stale data; the
    // screen shows it as working. Refetching it on focus is what the poll
    // already does.
    refetchOnWindowFocus: false,
    retry: (count, error) => {
      // An expired preview is terminal. Asking again cannot un-expire it.
      if (isProblem(error) && error.code === 'PREVIEW_EXPIRED') return false;
      if (isProblem(error) && error.code === 'NOT_FOUND') return false;
      return count < 2;
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

/**
 * Deletes a trip and everything it owns (FR-TRP-04).
 *
 * 204 with no body, so success is read from the status rather than from data —
 * `if (!data) fail(...)`, the shape every other trip hook uses, would treat a
 * successful delete as a failure. useUnsavePost is the precedent.
 *
 * The ETag comes from `TripSummary.version`, not from a prior getTrip. The
 * contract defines the header as the quoted trip version (`"7"`,
 * `^"[1-9][0-9]*"$`), so the list row already holds everything If-Match needs
 * and the profile can delete without fetching each trip first. If that
 * derivation ever stops holding, this sends a stale validator and the server
 * answers 409 — it fails closed, which is the point of invariant 6.
 *
 * The Idempotency-Key is minted by the CALLER for the same reason it is on
 * reorder: a retry of the same user action must reuse the key, and a key minted
 * in here would be fresh on every attempt, turning one destructive command into
 * two.
 *
 * On success the trip's own cache entry is REMOVED rather than invalidated.
 * Invalidating asks for it again, and the next fetch is a 404 for a resource
 * the user deliberately destroyed.
 */
export function useDeleteTrip() {
  const queryClient = useQueryClient();
  return useMutation<
    void,
    Problem | Error,
    { tripId: string; etag: string; idempotencyKey: string }
  >({
    mutationFn: async ({ tripId, etag, idempotencyKey }) => {
      const { error, response } = await getApiClient().DELETE('/trips/{tripId}', {
        params: {
          path: { tripId },
          header: { 'If-Match': etag, 'Idempotency-Key': idempotencyKey },
        },
      });
      if (response.status !== 204) fail(error, response);
    },
    onSuccess: (_result, { tripId }) => {
      queryClient.removeQueries({ queryKey: tripQueryKey(tripId) });
      queryClient.removeQueries({ queryKey: candidatesQueryKey(tripId) });
      void queryClient.invalidateQueries({ queryKey: ['trips'] });
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
 * `trip.candidates` is a page rather than the whole set (TripScreen reads
 * `candidateCount` for the total), so a mutation that changes the candidate
 * list has to invalidate it rather than read the new one out of this response.
 *
 * `touchedCandidates` is REQUIRED, with no default. It used to default to
 * false, which made "this mutation does not affect candidates" the silent
 * answer for any caller that did not think about it — and three of the four
 * callers had not. The answer differs per operation and only the call site
 * knows it, so the type now asks every one of them. Defaulting it again would
 * reintroduce the same class of bug the next time an operation starts touching
 * candidates.
 */
function useApplyTripMutation(tripId: string | null) {
  const queryClient = useQueryClient();
  return (
    result: TripMutationResult,
    etag: string | null,
    touchedCandidates: boolean,
  ) => {
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
      // A field edit on an existing item. The place does not change, so no
      // candidate is created or consumed.
      apply(result, etag, false);
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
      // Reorder moves items between days and positions; candidates are not
      // items and none is created or consumed.
      apply(result, etag, false);
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
      // The outgoing place comes back as an ACTIVE candidate — the contract
      // says so on this operation ("the same disposition removeTripItem names
      // RESTORE_CANDIDATE") and takes no parameter to opt out, so a replace
      // always changes the candidate list.
      apply(result, etag, true);
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
 * Two item mutations change the candidate list, not one: this operation when
 * the disposition is RESTORE_CANDIDATE, and replaceTripItem always — the
 * contract says the outgoing place "comes back to the trip as an ACTIVE
 * candidate" and gives no parameter to opt out (#165 Q2). This comment used to
 * claim removal was the only one, which is the reasoning that left replace
 * showing a stale list.
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
/**
 * Saves a place as a candidate of a trip (FR-CAN-01).
 *
 * The trip can be given per call, not only at hook level. That is not a
 * convenience: a screen where the user PICKS the trip binds this hook while
 * the choice is still unmade, so a hook-level id is whatever was selected at
 * render time. It worked only because the state update happened to re-render
 * before the async mutationFn dereferenced the rebuilt closure — a race with
 * a benign outcome today and no test able to see it, because the argument the
 * caller passed was inert (#FR-CAN-01 audit).
 *
 * Passing `tripId` in the variables makes the caller's choice the thing that
 * is actually sent, so a test that picks the second trip fails when the code
 * sends the first.
 */
export function useAddTripCandidate(tripId: string | null) {
  const queryClient = useQueryClient();
  return useMutation<
    CandidateSaveResult,
    Problem | Error,
    { request: AddCandidateRequest; idempotencyKey: string; tripId?: string }
  >({
    mutationFn: async ({ request, idempotencyKey, tripId: target }) => {
      // The call's own trip wins; the hook-level one is the default for
      // screens whose trip comes from the route and cannot change mid-flight.
      const id = target ?? tripId;
      if (id === null) throw new Error('No trip selected');
      const { data, error, response } = await getApiClient().POST(
        '/trips/{tripId}/candidates',
        {
          body: request,
          params: {
            path: { tripId: id },
            header: { 'Idempotency-Key': idempotencyKey },
          },
        },
      );
      if (!data) fail(error, response);
      return data;
    },
    onSuccess: (_result, variables) => {
      // Invalidate the list of the trip that was actually written to. Using
      // the hook-level id here would refresh the wrong trip's candidates
      // whenever the caller saved into a different one.
      const id = variables.tripId ?? tripId;
      if (id === null) return;
      // The itinerary is untouched by construction, so only the candidate list
      // is refetched.
      void queryClient.invalidateQueries({ queryKey: candidatesQueryKey(id) });
    },
  });
}

type ImportDraft = components['schemas']['ImportDraft'];
type ParseImportRequest = components['schemas']['ParseImportRequest'];
type RemapImportRequest = components['schemas']['RemapImportRequest'];
type ConfirmImportRequest = components['schemas']['ConfirmImportRequest'];

/** A draft and the ETag its next mutation has to send back. */
export interface ImportDraftWithETag {
  draft: ImportDraft;
  etag: string | null;
}

/**
 * Turns pasted itinerary text into a structured draft (FR-TRC-08, FE-104).
 *
 * The raw text is a request body and nothing else. It is never put in a query
 * string, a cache key, a log line or an analytics event, and the contract says
 * the response must not echo it back — invariant 10 is the reason this
 * operation exists in this shape at all. That is also why the draft is NOT
 * written into the query cache here: a cache entry is a copy that outlives the
 * request, and the only thing the screen needs is the value this returns.
 *
 * Carries an Idempotency-Key minted by the caller, so a retry after a lost
 * response re-reads the same parse instead of starting a second one.
 */
export function useParseTripImport() {
  return useMutation<
    ImportDraftWithETag,
    Problem | Error,
    { request: ParseImportRequest; idempotencyKey: string }
  >({
    mutationFn: async ({ request, idempotencyKey }) => {
      const { data, error, response } = await getApiClient().POST('/trip-imports/parse', {
        body: request,
        params: { header: { 'Idempotency-Key': idempotencyKey } },
      });
      if (!data) fail(error, response);
      return { draft: data, etag: response.headers.get('ETag') };
    },
  });
}

/**
 * Corrects what the parser could not place (FR-TRC-08).
 *
 * `updates` is a partial patch per clientKey where an absent field means
 * "leave alone", so this sends only what the user actually changed. The one
 * field that is not a value correction is `dismissed`: it withdraws a token or
 * an item instead of resolving it (#223), which is how a line the parser could
 * not place stops blocking READY. Without it a draft containing a free-memo
 * line could never reach confirm — the dead end FCR-019 recorded.
 *
 * If-Match is required and required here: the draft version advances on every
 * remap, and a blind PATCH would overwrite a correction made in another tab.
 * A 410 means the draft expired, which is not retryable — the paste is gone
 * and the user has to start again (problem-policy: `repaste`).
 */
export function useRemapTripImport(draftId: string | null) {
  return useMutation<
    ImportDraftWithETag,
    Problem | Error,
    { updates: RemapImportRequest['updates']; etag: string | null }
  >({
    mutationFn: async ({ updates, etag }) => {
      if (draftId === null) throw new Error('No import draft');
      if (etag === null) throw new Error('Cannot correct a draft without its ETag');
      const { data, error, response } = await getApiClient().PATCH(
        '/trip-imports/{draftId}',
        {
          body: { updates },
          params: { path: { draftId }, header: { 'If-Match': etag } },
        },
      );
      if (!data) fail(error, response);
      return { draft: data, etag: response.headers.get('ETag') };
    },
  });
}

/**
 * Turns a reviewed draft into a real trip (FR-TRC-09).
 *
 * One atomic transaction on the server (invariant 5): either the trip and all
 * of its mapped items exist, or none of them do. The client's part is to send
 * both guards the contract asks for — If-Match so a draft edited elsewhere
 * cannot be confirmed from a stale view, and an Idempotency-Key so a retry
 * after a lost response does not create a second trip.
 *
 * The trip list is invalidated rather than written: this returns the new trip,
 * but `listTrips` is a separate cursor-paged resource and guessing where the
 * new row belongs in it is how a list starts disagreeing with the server.
 */
export function useConfirmTripImport(draftId: string | null) {
  const queryClient = useQueryClient();
  return useMutation<
    TripWithETag,
    Problem | Error,
    { request: ConfirmImportRequest; etag: string | null; idempotencyKey: string }
  >({
    mutationFn: async ({ request, etag, idempotencyKey }) => {
      if (draftId === null) throw new Error('No import draft');
      if (etag === null) throw new Error('Cannot confirm a draft without its ETag');
      const { data, error, response } = await getApiClient().POST(
        '/trip-imports/{draftId}/confirm',
        {
          body: request,
          params: {
            path: { draftId },
            header: { 'If-Match': etag, 'Idempotency-Key': idempotencyKey },
          },
        },
      );
      if (!data) fail(error, response);
      return { trip: data, etag: response.headers.get('ETag') };
    },
    onSuccess: (result) => {
      queryClient.setQueryData(tripQueryKey(result.trip.id), result);
      void queryClient.invalidateQueries({ queryKey: ['trips'] });
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
