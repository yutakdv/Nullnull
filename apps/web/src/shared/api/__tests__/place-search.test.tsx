// @vitest-environment happy-dom
//
// FE-103-T4: a place search carries the UI's locale.
//
// PlaceSearchRequest has a `locale` (default ko-KR) and the search sent only
// `{ query }`, so an English UI got Korean place names from every search box —
// Live, add place, the wizard's manual step and must-visit, post authoring —
// while place detail and summaries came back in the owner's locale. The two
// halves of one screen disagreed (#60, #54).
import { QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
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
