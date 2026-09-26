// Test-only: searchPlaces answered as two cursor pages (#54, FE-103-T5..T14).
//
// The approved `places` fixture is a single page (`hasMore: false`), so no
// screen could show what happens after it. The pages here are the fixture's
// own places split in two; only the CursorPage envelope is written here, and
// it is typed as the contract's PlaceSearchPage so tsc holds it to the schema.
// The cursor is opaque by contract, so any string stands in for it.
import { http, HttpResponse } from 'msw';
import type { components } from '@nullnull/api-client';
import { placeFixtures } from '@nullnull/contracts';
import type { ProblemCode } from '../../api/index.js';
import { API_BASE, problemResponse } from './handlers.js';
import { server } from './server.js';

type PlaceSearchPage = components['schemas']['PlaceSearchPage'];
type PlaceSearchRequest = components['schemas']['PlaceSearchRequest'];

export const NEXT_CURSOR = 'cursor-page-2';

const [first, second, third] = placeFixtures.searchPage.items;
if (!first || !second || !third) {
  // Fails loudly rather than serving short pages: every assertion below would
  // then be about fewer places than it names.
  throw new Error(
    'the places fixture no longer holds the three places these pages split',
  );
}

/** Page one ends at the second place; page two holds the third. */
export const searchPages = {
  first: [first, second],
  next: [third],
} as const;

export interface ServedSearch {
  /** Every request body, in the order the server received them. */
  bodies: PlaceSearchRequest[];
  /** Resolves the held second page, when `hold` was asked for. */
  release: () => void;
}

export function servePlaceSearchPages(
  options: {
    /** How many requests for page two fail before one succeeds. */
    failNext?: number;
    /**
     * What those failures answer: this Problem, or with none named a network
     * error, which has no status and no code.
     */
    failWith?: ProblemCode;
    /** Keep page two pending until `release()`, to observe the loading state. */
    hold?: boolean;
  } = {},
): ServedSearch {
  const bodies: PlaceSearchRequest[] = [];
  let failures = options.failNext ?? 0;
  let release: () => void = () => undefined;
  const held = options.hold
    ? new Promise<void>((resolve) => {
        release = resolve;
      })
    : Promise.resolve();

  server.use(
    http.post(`${API_BASE}/places/search`, async ({ request }) => {
      const body = (await request.json()) as PlaceSearchRequest;
      bodies.push(body);
      if (body.cursor !== NEXT_CURSOR) {
        const page: PlaceSearchPage = {
          items: [...searchPages.first],
          page: { nextCursor: NEXT_CURSOR, hasMore: true },
        };
        return HttpResponse.json(page);
      }
      await held;
      if (failures > 0) {
        failures -= 1;
        return options.failWith === undefined
          ? HttpResponse.error()
          : problemResponse(options.failWith);
      }
      const page: PlaceSearchPage = {
        items: [...searchPages.next],
        page: { nextCursor: null, hasMore: false },
      };
      return HttpResponse.json(page);
    }),
  );
  return {
    bodies,
    release: () => {
      release();
    },
  };
}
