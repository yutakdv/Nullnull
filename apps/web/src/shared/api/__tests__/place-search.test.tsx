// @vitest-environment happy-dom
//
// FE-103-T4: a place search carries the UI's locale.
//
// PlaceSearchRequest has a `locale` (default ko-KR) and the search sent only
// `{ query }`, so an English UI got Korean place names from every search box —
// Live, add place, the wizard's manual step and must-visit, post authoring —
// while place detail and summaries came back in the owner's locale. The two
// halves of one screen disagreed (#60, #54).
//
// FE-103-T5..T7: a search continues past its first page (#54 §4). The
// contract pages searchPlaces by an opaque cursor (`PlaceSearchRequest.cursor`,
// `PlaceSearchPage.page`), and the hook asked for page one only, so a match
// past the 20th could not be reached from any search box.
import { QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { placeFixtures } from '@nullnull/contracts';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it } from 'vitest';
import type { SupportedLocale } from '../../../i18n/locales.js';
import { createQueryClient, usePlaceSearch } from '../index.js';
import { API_BASE } from '../../testing/msw/handlers.js';
import { server } from '../../testing/msw/server.js';

let bodies: unknown[] = [];

beforeEach(() => {
  bodies = [];
  server.use(
    http.post(`${API_BASE}/places/search`, async ({ request }) => {
      bodies.push(await request.json());
      return HttpResponse.json(placeFixtures.searchPage);
    }),
  );
});

function wrapper() {
  const client = createQueryClient();
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
}

describe('FE-103-T4 a place search carries the UI locale', () => {
  it('sends the locale with the query', async () => {
    const { result } = renderHook(() => usePlaceSearch('경복궁', 'en-US'), {
      wrapper: wrapper(),
    });
    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });
    expect(bodies).toEqual([{ query: '경복궁', locale: 'en-US' }]);
  });

  it('asks again when only the locale changes', async () => {
    // The same words in another language are another answer. Were the locale
    // not part of the cache key, switching language would keep showing the
    // first language's names from cache and send nothing.
    const { result, rerender } = renderHook(
      ({ locale }: { locale: SupportedLocale }) => usePlaceSearch('경복궁', locale),
      { initialProps: { locale: 'ko-KR' as SupportedLocale }, wrapper: wrapper() },
    );
    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });
    rerender({ locale: 'en-US' });
    await waitFor(() => {
      expect(bodies).toHaveLength(2);
    });
    expect(bodies).toEqual([
      { query: '경복궁', locale: 'ko-KR' },
      { query: '경복궁', locale: 'en-US' },
    ]);
  });
});

// Two pages built from the approved fixture's places. The page envelope is the
// contract's CursorPage; the cursor value is opaque, so any string stands in.
const [placeA, placeB] = placeFixtures.searchPage.items;

function servePages(first: { nextCursor: string | null; hasMore: boolean }) {
  server.use(
    http.post(`${API_BASE}/places/search`, async ({ request }) => {
      const body = (await request.json()) as { cursor?: string | null };
      bodies.push(body);
      if (body.cursor === 'cursor-2') {
        return HttpResponse.json({
          items: [placeB],
          page: { nextCursor: null, hasMore: false },
        });
      }
      return HttpResponse.json({ items: [placeA], page: first });
    }),
  );
}

/** Searches, then asks for the page after the first. */
async function continueOnce() {
  servePages({ nextCursor: 'cursor-2', hasMore: true });
  const { result } = renderHook(() => usePlaceSearch('서울', 'en-US'), {
    wrapper: wrapper(),
  });
  await waitFor(() => {
    expect(result.current.isSuccess).toBe(true);
  });
  expect(result.current.hasNextPage).toBe(true);
  await act(async () => {
    await result.current.fetchNextPage();
  });
  return result;
}

describe('FE-103-T5 a continuation sends the cursor its last page returned', () => {
  it('FE-103-T5 repeats the query and locale beside that cursor', async () => {
    await continueOnce();
    // The cursor is bound to the query and the locale that minted it
    // (BA-022-T2, BA-022-T11), so the continuation repeats both rather than
    // sending the cursor alone.
    expect(bodies).toEqual([
      { query: '서울', locale: 'en-US' },
      { query: '서울', locale: 'en-US', cursor: 'cursor-2' },
    ]);
  });
});

describe('FE-103-T6 a continuation adds to the results rather than replacing them', () => {
  it('FE-103-T6 lists the next page after the first', async () => {
    const result = await continueOnce();
    await waitFor(() => {
      expect(result.current.data?.items.map((place) => place.id)).toEqual([
        placeA?.id,
        placeB?.id,
      ]);
    });
    expect(result.current.hasNextPage).toBe(false);
  });
});

describe('FE-103-T7 no next page is asked for once the server says there is none', () => {
  it('FE-103-T7 reads hasMore, not the presence of a cursor', async () => {
    // hasMore is the contract's own flag. A cursor that arrives with it false
    // is not an invitation to ask again.
    servePages({ nextCursor: 'cursor-2', hasMore: false });
    const { result } = renderHook(() => usePlaceSearch('서울', 'en-US'), {
      wrapper: wrapper(),
    });
    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });
    expect(result.current.hasNextPage).toBe(false);
    expect(bodies).toHaveLength(1);
  });
});
