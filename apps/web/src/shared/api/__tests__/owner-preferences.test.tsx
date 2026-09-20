// @vitest-environment happy-dom
//
// `useUpdatePreferences` and the cache entry it has to keep honest.
//
// The bootstrap entry holds the owner profile, and for `activeTripId` that
// cache IS the source of truth: AppShell reads it to decide where the 내 여행
// tab goes, and `useSessionBootstrap` asks the server once per load. So a PATCH
// that returned a new profile and left the cache alone would report success
// while the tab kept using the old value until the next reload.
//
// Written because deleting the `onSuccess` broke NOTHING: 283 tests passed with
// the cache write removed. A wiring that no test can miss is a wiring the next
// refactor deletes.
import { QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { sessionFixtures } from '@nullnull/contracts';
import type { components } from '@nullnull/api-client';
import { createQueryClient, sessionQueryKey, useUpdatePreferences } from '../index.js';
import { API_BASE } from '../../testing/msw/handlers.js';
import { server } from '../../testing/msw/server.js';

type SessionBootstrap = components['schemas']['SessionBootstrap'];

const TRIP_ID = '018f4a10-2c31-7d42-9a55-6b1f0c3e8a01';

/** A client with the bootstrap entry already populated, as a live tab has. */
function bootstrapped() {
  const client = createQueryClient();
  client.setQueryData<SessionBootstrap>(sessionQueryKey, sessionFixtures.bootstrap);
  return client;
}

function wrapper(client: ReturnType<typeof createQueryClient>) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

/** What the cache currently says the active trip is. */
function cachedActiveTrip(client: ReturnType<typeof createQueryClient>) {
  return client.getQueryData<SessionBootstrap>(sessionQueryKey)?.owner.activeTripId;
}

beforeEach(() => {
  server.use(
    // Echoes the merge patch over the fixture, which is what the real server
    // answers with: the WHOLE profile after applying the change.
    http.patch(`${API_BASE}/me`, async ({ request }) => {
      const patch = (await request.json()) as Record<string, unknown>;
      return HttpResponse.json({ ...sessionFixtures.owner, ...patch });
    }),
  );
});
afterEach(() => {
  server.resetHandlers();
});

describe('a preference write keeps the session cache current (BA-011)', () => {
  it('writes the returned owner into the bootstrap entry', async () => {
    const client = bootstrapped();
    expect(cachedActiveTrip(client)).toBeNull();

    const { result } = renderHook(() => useUpdatePreferences(), {
      wrapper: wrapper(client),
    });
    result.current.mutate({ activeTripId: TRIP_ID });

    await waitFor(() => {
      expect(cachedActiveTrip(client)).toBe(TRIP_ID);
    });
  });

  it('takes the value from the response, not from the patch it sent', async () => {
    // A merge patch says what changed; the server answers with the profile it
    // actually stored. Copying the request instead would cache a value the
    // server may have normalised or refused — and this hook's own contract
    // (`TRIP_NOT_FOUND` on a trip the owner does not have) makes that a real
    // difference rather than a hypothetical one.
    server.use(
      http.patch(`${API_BASE}/me`, () =>
        HttpResponse.json({ ...sessionFixtures.owner, activeTripId: null }),
      ),
    );
    const client = bootstrapped();

    const { result } = renderHook(() => useUpdatePreferences(), {
      wrapper: wrapper(client),
    });
    result.current.mutate({ activeTripId: TRIP_ID });

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });
    expect(cachedActiveTrip(client)).toBeNull();
  });

  it('leaves the rest of the bootstrap entry alone', async () => {
    // Only the owner changes. Replacing the whole entry would drop the CSRF
    // token and its expiry, which the shell reads from the same object.
    const client = bootstrapped();

    const { result } = renderHook(() => useUpdatePreferences(), {
      wrapper: wrapper(client),
    });
    result.current.mutate({ activeTripId: TRIP_ID });

    await waitFor(() => {
      expect(cachedActiveTrip(client)).toBe(TRIP_ID);
    });
    const entry = client.getQueryData<SessionBootstrap>(sessionQueryKey);
    expect(entry?.csrfToken).toBe(sessionFixtures.bootstrap.csrfToken);
    expect(entry?.expiresAt).toBe(sessionFixtures.bootstrap.expiresAt);
  });

  it('does not create the entry when this tab never bootstrapped', async () => {
    // A cold cache means no session has answered here. Writing one would
    // fabricate a bootstrap the tab never received, and `useSessionBootstrap`
    // would then skip the call it exists to make.
    const client = createQueryClient();

    const { result } = renderHook(() => useUpdatePreferences(), {
      wrapper: wrapper(client),
    });
    result.current.mutate({ activeTripId: TRIP_ID });

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });
    expect(client.getQueryData(sessionQueryKey)).toBeUndefined();
  });

  it('keeps the current-owner cache in sync after a cold-tab preference change', async () => {
    // Refreshed tabs recover the owner through GET /me rather than POSTing a
    // new demo session. If PATCH updates only the bootstrap cache, the feed and
    // My Trip tab snap back to the old representative on their next render.
    const client = createQueryClient();
    client.setQueryData(['owner', 'current'], {
      ...sessionFixtures.owner,
      activeTripId: null,
    });

    const { result } = renderHook(() => useUpdatePreferences(), {
      wrapper: wrapper(client),
    });
    result.current.mutate({ activeTripId: TRIP_ID });

    await waitFor(() => {
      expect(
        client.getQueryData<typeof sessionFixtures.owner>(['owner', 'current'])
          ?.activeTripId,
      ).toBe(TRIP_ID);
    });
  });
});
