// @vitest-environment happy-dom
//
// FE-305 slice 1: the four item mutations, tested at the wire.
//
// These assert the things the contract makes asymmetric between operations,
// because every one of them typechecks clean when written wrong:
//
//   - updateTripItem takes application/merge-patch+json; openapi-fetch sends
//     application/json regardless of the typed media key, so the server would
//     answer 415.
//   - updateTripItem and removeTripItem declare NO Idempotency-Key, while add,
//     reorder and replace require one. "Consistency" in either direction is a
//     header the contract did not ask for, or a missing one it did.
//   - removeTripItem requires a `disposition` query parameter with no default.
//     Choosing it here would decide for the user whether their saved place
//     survives.
import { QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { http } from 'msw';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { tripFixtures } from '@nullnull/contracts';
import {
  createQueryClient,
  useRemoveTripItem,
  useReorderTripItems,
  useReplaceTripItem,
  useUpdateTripItem,
} from '../index.js';
import { API_BASE, problemResponse } from '../../testing/msw/handlers.js';
import { server } from '../../testing/msw/server.js';

const trip = tripFixtures.detailScheduled;
const ETAG = `"${String(trip.version)}"`;
const firstItem = trip.days[0]?.items[0];
const secondItem = trip.days[0]?.items[1];

interface Sent {
  method: string;
  url: string;
  ifMatch: string | null;
  idempotency: string | null;
  contentType: string | null;
  body: unknown;
}

let sent: Sent[] = [];

beforeEach(() => {
  sent = [];
  server.events.on('request:start', ({ request }) => {
    if (request.method === 'GET') return;
    const clone = request.clone();
    const base = {
      method: request.method,
      url: request.url,
      ifMatch: request.headers.get('If-Match'),
      idempotency: request.headers.get('Idempotency-Key'),
      contentType: request.headers.get('content-type'),
    };
    void clone
      .text()
      .then((text) => {
        sent.push({ ...base, body: text ? (JSON.parse(text) as unknown) : null });
      })
      .catch(() => {
        sent.push({ ...base, body: null });
      });
  });
});

afterEach(() => {
  server.events.removeAllListeners();
});

function wrapper({ children }: { children: ReactNode }) {
  return (
    <QueryClientProvider client={createQueryClient()}>{children}</QueryClientProvider>
  );
}

describe('updateTripItem sends merge-patch and no idempotency key', () => {
  it('sets the merge-patch content type the contract declares', async () => {
    const { result } = renderHook(() => useUpdateTripItem(trip.id), { wrapper });
    result.current.mutate({
      itemId: firstItem?.id ?? '',
      patch: { durationMinutes: 90 },
      etag: ETAG,
    });

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });
    const patch = sent.find((r) => r.method === 'PATCH');
    // application/json here is a 415 the types cannot catch.
    expect(patch?.contentType).toContain('application/merge-patch+json');
    expect(patch?.ifMatch).toBe(ETAG);
  });

  it('sends no Idempotency-Key, because this operation declares none', async () => {
    const { result } = renderHook(() => useUpdateTripItem(trip.id), { wrapper });
    result.current.mutate({
      itemId: firstItem?.id ?? '',
      patch: { note: '메모' },
      etag: ETAG,
    });

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });
    expect(sent.find((r) => r.method === 'PATCH')?.idempotency).toBeNull();
  });

  it('refuses an empty patch rather than letting the server reject it', async () => {
    const { result } = renderHook(() => useUpdateTripItem(trip.id), { wrapper });
    result.current.mutate({ itemId: firstItem?.id ?? '', patch: {}, etag: ETAG });

    await waitFor(() => {
      expect(result.current.isError).toBe(true);
    });
    // minProperties: 1 — an empty body is a round trip that could never succeed.
    expect(sent.filter((r) => r.method === 'PATCH')).toHaveLength(0);
  });

  it('refuses to write without an ETag rather than sending a blind patch', async () => {
    const { result } = renderHook(() => useUpdateTripItem(trip.id), { wrapper });
    result.current.mutate({
      itemId: firstItem?.id ?? '',
      patch: { note: 'x' },
      etag: null,
    });

    await waitFor(() => {
      expect(result.current.isError).toBe(true);
    });
    expect(sent).toHaveLength(0);
  });
});

describe('reorderTripItems moves everything in one request', () => {
  it('carries If-Match, the caller Idempotency-Key, and the whole ordering', async () => {
    const { result } = renderHook(() => useReorderTripItems(trip.id), { wrapper });
    const order = [
      { itemId: secondItem?.id ?? '', date: '2026-10-04', position: 0 },
      { itemId: firstItem?.id ?? '', date: '2026-10-04', position: 1 },
    ];
    result.current.mutate({ order, etag: ETAG, idempotencyKey: 'key-reorder-0001' });

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });
    const post = sent.find((r) => r.url.endsWith('/items/reorder'));
    expect(post?.ifMatch).toBe(ETAG);
    // Minted by the caller so a retry of one user action reuses it.
    expect(post?.idempotency).toBe('key-reorder-0001');
    expect(post?.body).toEqual({ items: order });
  });

  it('applies the swap as one unit', async () => {
    const { result } = renderHook(() => useReorderTripItems(trip.id), { wrapper });
    result.current.mutate({
      order: [
        { itemId: secondItem?.id ?? '', date: '2026-10-04', position: 0 },
        { itemId: firstItem?.id ?? '', date: '2026-10-04', position: 1 },
      ],
      etag: ETAG,
      idempotencyKey: 'key-reorder-0002',
    });

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });
    const day = result.current.data?.result.trip.days[0];
    expect(day?.items.map((i) => i.id)).toEqual([secondItem?.id, firstItem?.id]);
  });

  it('moves an item to another day in the same request', async () => {
    const { result } = renderHook(() => useReorderTripItems(trip.id), { wrapper });
    result.current.mutate({
      // 10-06 is empty in the fixture.
      order: [{ itemId: firstItem?.id ?? '', date: '2026-10-06', position: 0 }],
      etag: ETAG,
      idempotencyKey: 'key-move-0001',
    });

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });
    const days = result.current.data?.result.trip.days ?? [];
    expect(days[0]?.items.some((i) => i.id === firstItem?.id)).toBe(false);
    expect(days[2]?.items.some((i) => i.id === firstItem?.id)).toBe(true);
  });
});

describe('replaceTripItem keeps the schedule unless told otherwise', () => {
  it('omits preserveDateTime rather than sending the default', async () => {
    const { result } = renderHook(() => useReplaceTripItem(trip.id), { wrapper });
    result.current.mutate({
      itemId: firstItem?.id ?? '',
      replacement: { replacementPlaceId: '018f4b20-1a44-7e11-9c02-5d7e3f1a2b02' },
      etag: ETAG,
      idempotencyKey: 'key-replace-0001',
    });

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });
    const post = sent.find((r) => r.url.includes('/replace'));
    expect(post?.idempotency).toBe('key-replace-0001');
    // Sending `true` explicitly is harmless; sending `false` as a form default
    // would move the user's schedule without them asking.
    expect(post?.body).not.toHaveProperty('preserveDateTime', false);
  });
});

describe('removeTripItem forces the caller to choose a disposition', () => {
  it('sends RESTORE_CANDIDATE as a query parameter', async () => {
    const { result } = renderHook(() => useRemoveTripItem(trip.id), { wrapper });
    result.current.mutate({
      itemId: firstItem?.id ?? '',
      disposition: 'RESTORE_CANDIDATE',
      etag: ETAG,
    });

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });
    const del = sent.find((r) => r.method === 'DELETE');
    expect(del?.url).toContain('disposition=RESTORE_CANDIDATE');
    expect(del?.ifMatch).toBe(ETAG);
  });

  it('sends REMOVE when the user asked to drop it entirely', async () => {
    const { result } = renderHook(() => useRemoveTripItem(trip.id), { wrapper });
    result.current.mutate({
      itemId: firstItem?.id ?? '',
      disposition: 'REMOVE',
      etag: ETAG,
    });

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });
    expect(sent.find((r) => r.method === 'DELETE')?.url).toContain('disposition=REMOVE');
  });

  it('sends no Idempotency-Key, because this operation declares none', async () => {
    const { result } = renderHook(() => useRemoveTripItem(trip.id), { wrapper });
    result.current.mutate({
      itemId: firstItem?.id ?? '',
      disposition: 'REMOVE',
      etag: ETAG,
    });

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });
    expect(sent.find((r) => r.method === 'DELETE')?.idempotency).toBeNull();
  });
});

describe('every item mutation surfaces a version conflict', () => {
  it('updateTripItem reports TRIP_CHANGED rather than retrying', async () => {
    server.use(
      http.patch(`${API_BASE}/trips/:tripId/items/:itemId`, () =>
        problemResponse('TRIP_CHANGED'),
      ),
    );
    const { result } = renderHook(() => useUpdateTripItem(trip.id), { wrapper });
    result.current.mutate({
      itemId: firstItem?.id ?? '',
      patch: { note: 'x' },
      etag: '"99"',
    });

    await waitFor(() => {
      expect(result.current.isError).toBe(true);
    });
    // One attempt: an auto-retried conflict would overwrite whatever changed.
    expect(sent.filter((r) => r.method === 'PATCH')).toHaveLength(1);
  });

  it('removeTripItem reports TRIP_CHANGED rather than retrying', async () => {
    server.use(
      http.delete(`${API_BASE}/trips/:tripId/items/:itemId`, () =>
        problemResponse('TRIP_CHANGED'),
      ),
    );
    const { result } = renderHook(() => useRemoveTripItem(trip.id), { wrapper });
    result.current.mutate({
      itemId: firstItem?.id ?? '',
      disposition: 'REMOVE',
      etag: '"99"',
    });

    await waitFor(() => {
      expect(result.current.isError).toBe(true);
    });
    expect(sent.filter((r) => r.method === 'DELETE')).toHaveLength(1);
  });
});
