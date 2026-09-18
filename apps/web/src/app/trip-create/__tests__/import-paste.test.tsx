// @vitest-environment happy-dom
//
// FE-104 paste import (FR-TRC-08, Figma S02-4C-A `401:1221`).
//
// The assertion this file exists for is the privacy one. Invariant 10 says the
// pasted itinerary is never stored, logged or sent to analytics, and the
// contract says the response must not echo it back — so what is checked here is
// that the raw text leaves as a request body and appears NOWHERE else.
//
// The rest is the draft state machine: a correction carries the draft's own
// ETag, `dismissed` withdraws a line nobody can resolve (#223), and confirm is
// refused until the draft is READY.
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse, delay } from 'msw';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { messages } from '../../../i18n/messages.js';
import { createQueryClient } from '../../../shared/api/index.js';
import { API_BASE, problemResponse } from '../../../shared/testing/msw/handlers.js';
import { server } from '../../../shared/testing/msw/server.js';
import { routes } from '../../routes.js';

const copy = messages['en-US'];

const PASTE = '10/4 Gyeongbokgung 10am\n10/5 Myeongdong\nsomewhere in a hanok village';

/**
 * A draft that parsed cleanly and found nothing — the empty state of FE-104-T2.
 *
 * Built here rather than imported: the handlers' own `buildImportDraft` is
 * module-private and deliberately shaped to carry the two unresolved tokens the
 * correction tests need, so it is the opposite of this. Exporting it just to
 * spread-and-blank it would widen the mock surface to express an absence.
 *
 * READY because nothing is unresolved, which is what makes this a state and not
 * an error: the server read the paste, answered, and the answer was "no plan in
 * here". A prose paste is the real way someone reaches it.
 */
const EMPTY_READ = {
  id: '018f4c30-2b55-7f22-ad13-6e8f4a2b3c02',
  version: 1,
  status: 'READY',
  title: null,
  dates: { startDate: null, endDate: null },
  items: [],
  unresolved: [],
  expiresAt: '2026-09-16T04:00:00Z',
};

/** Every request the app made, with body and headers, for the privacy sweep. */
let seen: { method: string; url: string; body: string; headers: string }[] = [];

/**
 * Every response body the app received.
 *
 * Collected separately because the outgoing side cannot see a server that
 * echoes the paste back, and "the response must not echo rawText" is the
 * contract's own words.
 */
let responses: string[] = [];

beforeEach(() => {
  seen = [];
  responses = [];
  server.events.on('request:start', ({ request }) => {
    const clone = request.clone();
    void clone.text().then((body) => {
      seen.push({
        method: request.method,
        url: request.url,
        body,
        headers: JSON.stringify([...request.headers.entries()]),
      });
    });
  });
  server.events.on('response:mocked', ({ response }) => {
    void response
      .clone()
      .text()
      .then((body) => responses.push(body));
  });
});

afterEach(() => {
  server.events.removeAllListeners();
});

function renderImport() {
  const router = createMemoryRouter(routes, { initialEntries: ['/start/import'] });
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <I18nProvider>
        <RouterProvider router={router} />
      </I18nProvider>
    </QueryClientProvider>,
  );
}

/** Pastes and parses, leaving the screen on the review state. */
async function paste(user: ReturnType<typeof userEvent.setup>, text = PASTE) {
  const box = await screen.findByLabelText(copy['import.label']);
  await user.click(box);
  await user.paste(text);
  await user.click(screen.getByRole('button', { name: copy['import.parse'] }));
  return screen.findByText(
    new RegExp(copy['import.review.items'].replace('{count}', '')),
  );
}

// FE-104-T1 is "붙여넣기 원문을 저장·로그·analytics에 남기지 않는다", and these
// three cases are what prove it. The ID is in the name because that is the
// string the aggregator reads; without it the clause was proven and invisible.
//
// Added after reading the bodies rather than copying the ID across: the first
// case checks the raw text against every outgoing URL, header and non-parse
// body, then against the response and the rendered DOM, and records the
// mutation that showed the outgoing-only version was insufficient.
describe('FE-104-T1 the paste is read without being kept', () => {
  it('sends the pasted text as a request body and nothing else', async () => {
    const user = userEvent.setup();
    renderImport();
    await paste(user);

    await waitFor(() => {
      expect(seen.some((r) => r.url.includes('/trip-imports/parse'))).toBe(true);
    });
    const parse = seen.find((r) => r.url.includes('/trip-imports/parse'));
    // It IS in the parse body — that is the whole operation.
    expect(parse?.body).toContain('Gyeongbokgung');

    // And nowhere else: not in any URL, not in any header, and not in the body
    // of any other request. A query string or an analytics event carrying the
    // itinerary is exactly what invariant 10 forbids.
    for (const request of seen) {
      expect(request.url).not.toContain('Gyeongbokgung');
      expect(request.headers).not.toContain('Gyeongbokgung');
      if (!request.url.includes('/trip-imports/parse')) {
        expect(request.body).not.toContain('Gyeongbokgung');
      }
    }

    // And the server does not hand it back. The contract says the response
    // must not echo rawText, and checking only the OUTGOING side cannot see a
    // server that breaks that — proved by mutation: making the handler put
    // rawText in the draft title left every assertion above green.
    expect(responses.join('')).not.toContain('Gyeongbokgung');
    expect(document.body.textContent).not.toContain('Gyeongbokgung');
  });

  it('clears the box once the draft comes back', async () => {
    const user = userEvent.setup();
    renderImport();
    await paste(user);

    // The review state replaces the textarea entirely, so the pasted words are
    // no longer held anywhere in the page.
    await waitFor(() => {
      expect(screen.queryByLabelText(copy['import.label'])).not.toBeInTheDocument();
    });
    expect(document.body.textContent).not.toContain('Gyeongbokgung');
  });

  it('does not parse an empty box', async () => {
    const user = userEvent.setup();
    renderImport();
    await screen.findByLabelText(copy['import.label']);
    expect(screen.getByRole('button', { name: copy['import.parse'] })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: copy['import.parse'] }));
    expect(seen.some((r) => r.url.includes('/trip-imports/parse'))).toBe(false);
  });
});

describe('what the parser could not place is corrected by the person', () => {
  it('sends the draft version as If-Match when resolving a line', async () => {
    const user = userEvent.setup();
    renderImport();
    await paste(user);

    const pick = await screen.findByRole('button', {
      name: new RegExp(copy['import.token.pick'].replace('{name}', '')),
    });
    await user.click(pick);

    await waitFor(() => {
      expect(seen.some((r) => r.method === 'PATCH')).toBe(true);
    });
    const patch = seen.find((r) => r.method === 'PATCH');
    // The quoted draft version, which is what the contract defines the import
    // draft's ETag to be. Asserted as the exact string: `"1"` and `1` are not
    // the same header.
    expect(patch?.headers).toContain('"\\"1\\""');
    expect(patch?.body).toContain('placeId');
  });

  it('withdraws a line nobody can resolve, and says so', async () => {
    // #223: a free-memo line yields an EMPTY label and no suggestions. Without
    // `dismissed` that draft could never reach READY — the dead end FCR-019
    // recorded.
    const user = userEvent.setup();
    renderImport();
    await paste(user);

    const unreadable = await screen.findByText(copy['import.token.noLabel']);
    const row = unreadable.closest('li') as HTMLElement;
    await user.click(
      within(row).getByRole('button', { name: copy['import.token.dismiss'] }),
    );

    await waitFor(() => {
      expect(seen.some((r) => r.method === 'PATCH')).toBe(true);
    });
    expect(seen.find((r) => r.method === 'PATCH')?.body).toContain('"dismissed":true');
    // Announced at screen level, where it outlives the row it removed.
    expect(
      await screen.findByText(copy['import.token.dismissed'].replace('{line}', '7')),
    ).toBeInTheDocument();
  });

  it('reports a conflict instead of overwriting a draft that moved on', async () => {
    const user = userEvent.setup();
    renderImport();
    await paste(user);
    server.use(
      http.patch(`${API_BASE}/trip-imports/:draftId`, () =>
        problemResponse('IMPORT_DRAFT_CHANGED'),
      ),
    );

    const pick = await screen.findByRole('button', {
      name: new RegExp(copy['import.token.pick'].replace('{name}', '')),
    });
    await user.click(pick);

    expect(await screen.findByRole('alert')).toHaveTextContent(copy['import.changed']);
  });
});

describe('a draft becomes a trip only once it is settled', () => {
  it('refuses to confirm while a line still needs a look', async () => {
    const user = userEvent.setup();
    renderImport();
    await paste(user);

    expect(
      await screen.findByRole('button', { name: copy['import.confirm'] }),
    ).toBeDisabled();
    expect(screen.getByText(copy['import.confirmBlocked'])).toBeInTheDocument();
    expect(seen.some((r) => r.url.includes('/confirm'))).toBe(false);
  });

  it('confirms with both guards once nothing is unresolved', async () => {
    const user = userEvent.setup();
    renderImport();
    await paste(user);

    // Settle everything: resolve the suggested line, drop the unreadable one,
    // and give the dateless item a date by dropping it.
    await user.click(
      await screen.findByRole('button', {
        name: new RegExp(copy['import.token.pick'].replace('{name}', '')),
      }),
    );
    const unreadable = await screen.findByText(copy['import.token.noLabel']);
    await user.click(
      within(unreadable.closest('li') as HTMLElement).getByRole('button', {
        name: copy['import.token.dismiss'],
      }),
    );
    const noDate = await screen.findByText(copy['import.item.noDate']);
    await user.click(
      within(noDate.closest('li') as HTMLElement).getByRole('button', {
        name: new RegExp(copy['import.item.dismiss'].replace('{name}', '')),
      }),
    );

    const confirm = await screen.findByRole('button', {
      name: copy['import.confirm'],
    });
    await waitFor(() => {
      expect(confirm).toBeEnabled();
    });
    await user.click(confirm);

    await waitFor(() => {
      expect(seen.some((r) => r.url.includes('/confirm'))).toBe(true);
    });
    const posted = seen.find((r) => r.url.includes('/confirm'));
    // Invariant 6: If-Match so a draft edited elsewhere cannot be confirmed
    // from a stale view, Idempotency-Key so a retry does not create a second
    // trip. Both, or the atomic create is not actually guarded.
    //
    // The VALUES are asserted, not just the header names: a header sent empty
    // would satisfy a name-only check while guarding nothing. If-Match is the
    // quoted draft version, which by here is 4 — the draft advanced once per
    // correction, so this also says the corrections reached the server rather
    // than only the screen.
    expect(posted?.headers).toContain('["If-Match","\\"4\\""]');
    expect(posted?.headers).toMatch(
      /\["Idempotency-Key","[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"\]/i,
    );
  });
});

// FE-104-T2 is "기본/loading/empty/error/offline/stale 상태를 각각 렌더한다".
//
// The clause was the one FE-104 acceptance with NO test carrying its ID: T1 is
// the privacy sweep above and T3 is proven by `e2e/screens.ts`, which names
// `/start/import` so responsive.spec measures it at 360px and 200% zoom. The
// state matrix was the gap, and a card cannot reach integration-ready while an
// acceptance ID has no testcase to point `provenBy` at.
//
// The six names in the clause are a checklist for a QUERY screen, and this is
// not one: `useParseTripImport` is a mutation and the draft is deliberately
// kept out of the query cache (invariant 10 — a cache entry is a copy of the
// itinerary that outlives the request). So there is no background refetch here
// and no cached-then-revalidated read. Writing a test per literal word would
// mean inventing two states the screen cannot enter, and a test that asserts a
// state nothing produces is the "발화할 수 없는 단언" AGENTS.md rule 7② lists.
//
// What that clause means on a mutation screen, mapped one to one:
//
//   default  → the empty paste box, before anything is sent
//   loading  → parse in flight, and confirm in flight (two different waits)
//   empty    → a draft that parsed but yielded nothing to review
//   error    → parse failed, and confirm failed, WITHOUT claiming a trip exists
//   offline  → the transport itself fails rather than the server answering
//   stale    → the draft expired underneath the person (410, not retryable)
//
// `changed` (a draft edited in another tab) is the sibling of `stale` and is
// already proven above, so it is not repeated here.
describe('FE-104-T2 the screen renders each of its states', () => {
  it('starts on an empty box that cannot be sent', async () => {
    renderImport();
    expect(await screen.findByLabelText(copy['import.label'])).toHaveValue('');
    // The privacy line is said BEFORE the paste, not after: it is the reason
    // someone is willing to paste at all.
    expect(screen.getByText(copy['import.privacy'])).toBeInTheDocument();
    expect(screen.getByRole('button', { name: copy['import.parse'] })).toBeDisabled();
  });

  it('reports that it is reading while the parse is in flight', async () => {
    server.use(
      http.post(`${API_BASE}/trip-imports/parse`, async () => {
        await delay(50);
        return HttpResponse.json(EMPTY_READ, {
          headers: { ETag: '"1"', 'Cache-Control': 'no-store' },
        });
      }),
    );
    const user = userEvent.setup();
    renderImport();
    const box = await screen.findByLabelText(copy['import.label']);
    await user.click(box);
    await user.paste(PASTE);
    await user.click(screen.getByRole('button', { name: copy['import.parse'] }));

    // Named its own state rather than only disabled: a button that goes quiet
    // reads as a dead press, and this one can take a while on a long paste.
    const reading = await screen.findByRole('button', { name: copy['import.parsing'] });
    expect(reading).toBeDisabled();
  });

  it('says a draft that read nothing is empty rather than showing a bare list', async () => {
    // A paste of prose parses fine and yields no items and no tokens. Without
    // its own state the review screen renders "읽은 일정 0개" over two empty
    // lists, which reads as a broken screen rather than as "we read nothing".
    server.use(
      http.post(`${API_BASE}/trip-imports/parse`, () =>
        HttpResponse.json(EMPTY_READ, {
          headers: { ETag: '"1"', 'Cache-Control': 'no-store' },
        }),
      ),
    );
    const user = userEvent.setup();
    renderImport();
    const box = await screen.findByLabelText(copy['import.label']);
    await user.click(box);
    await user.paste('just some prose with no plan in it');
    await user.click(screen.getByRole('button', { name: copy['import.parse'] }));

    expect(await screen.findByText(copy['import.empty'])).toBeInTheDocument();
    // And it does not offer to make a trip out of nothing.
    expect(screen.queryByRole('button', { name: copy['import.confirm'] })).toBeNull();
  });

  it('keeps the paste in the box when the parse fails', async () => {
    server.use(
      http.post(`${API_BASE}/trip-imports/parse`, () =>
        problemResponse('VALIDATION_FAILED'),
      ),
    );
    const user = userEvent.setup();
    renderImport();
    const box = await screen.findByLabelText(copy['import.label']);
    await user.click(box);
    await user.paste(PASTE);
    await user.click(screen.getByRole('button', { name: copy['import.parse'] }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      copy['import.parseFailed'],
    );
    // The text survives so the retry is one press, not a re-paste. Clearing on
    // failure would throw away something the app promises never to store, so
    // the person could not get it back.
    expect(screen.getByLabelText(copy['import.label'])).toHaveValue(PASTE);
    expect(screen.getByRole('button', { name: copy['import.parse'] })).toBeEnabled();
  });

  it('reports a transport failure the same way, without a server answer', async () => {
    // Offline is not a Problem response — there is no response at all. The
    // screen has to reach the same state from a thrown fetch as from a 4xx,
    // or a plane-mode paste shows nothing and looks like a dead button.
    server.use(http.post(`${API_BASE}/trip-imports/parse`, () => HttpResponse.error()));
    const user = userEvent.setup();
    renderImport();
    const box = await screen.findByLabelText(copy['import.label']);
    await user.click(box);
    await user.paste(PASTE);
    await user.click(screen.getByRole('button', { name: copy['import.parse'] }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      copy['import.parseFailed'],
    );
    expect(screen.getByLabelText(copy['import.label'])).toHaveValue(PASTE);
  });

  it('offers the repaste the expiry message asks for', async () => {
    // The stale case. A draft has a lifetime and the paste is gone from the
    // client by now (`setRaw('')`, invariant 10), so the recovery is to paste
    // again rather than to retry the same draft — problem-policy calls it
    // `repaste`.
    //
    // This test used to assert only the alert, and its own comment called this
    // "the one error the screen cannot offer a retry for". That framing was
    // wrong and the screen matched it: the copy said 다시 붙여넣어야 해요 while
    // the only control left was a confirm button that re-sent the same expired
    // ETag. The traveller could either watch it fail or leave through the
    // NavBar, losing the dates and interests they had entered.
    const user = userEvent.setup();
    renderImport();
    await paste(user);
    server.use(
      http.patch(`${API_BASE}/trip-imports/:draftId`, () =>
        problemResponse('IMPORT_DRAFT_EXPIRED'),
      ),
    );

    await user.click(
      await screen.findByRole('button', {
        name: new RegExp(copy['import.token.pick'].replace('{name}', '')),
      }),
    );

    expect(await screen.findByRole('alert')).toHaveTextContent(copy['import.expired']);

    // The way out, and it has to LAND somewhere: pressing it returns the
    // textarea, which is the only place a new paste can be typed.
    await user.click(screen.getByRole('button', { name: copy['import.retry'] }));
    expect(await screen.findByLabelText(copy['import.label'])).toBeInTheDocument();
  });

  it('does not leave the confirm live on an expired draft', async () => {
    // The same ETag is refused every time, so a live 여행으로 만들기 offers a
    // button whose only outcome is the error already on screen.
    const user = userEvent.setup();
    renderImport();
    await paste(user);
    server.use(
      http.patch(`${API_BASE}/trip-imports/:draftId`, () =>
        problemResponse('IMPORT_DRAFT_EXPIRED'),
      ),
    );

    await user.click(
      await screen.findByRole('button', {
        name: new RegExp(copy['import.token.pick'].replace('{name}', '')),
      }),
    );
    await screen.findByRole('alert');

    expect(screen.queryByRole('button', { name: copy['import.confirm'] })).toBeNull();
  });

  it('does not claim a trip exists when the confirm fails', async () => {
    const user = userEvent.setup();
    renderImport();
    await paste(user);

    // Settle the draft so confirm is actually reachable, then fail it.
    await user.click(
      await screen.findByRole('button', {
        name: new RegExp(copy['import.token.pick'].replace('{name}', '')),
      }),
    );
    const unreadable = await screen.findByText(copy['import.token.noLabel']);
    await user.click(
      within(unreadable.closest('li') as HTMLElement).getByRole('button', {
        name: copy['import.token.dismiss'],
      }),
    );
    const noDate = await screen.findByText(copy['import.item.noDate']);
    await user.click(
      within(noDate.closest('li') as HTMLElement).getByRole('button', {
        name: new RegExp(copy['import.item.dismiss'].replace('{name}', '')),
      }),
    );

    server.use(
      http.post(`${API_BASE}/trip-imports/:draftId/confirm`, () =>
        problemResponse('INTERNAL_ERROR'),
      ),
    );
    const confirm = await screen.findByRole('button', { name: copy['import.confirm'] });
    await waitFor(() => {
      expect(confirm).toBeEnabled();
    });
    await user.click(confirm);

    expect(await screen.findByRole('alert')).toHaveTextContent(
      copy['import.confirmFailed'],
    );
    // Still on the review screen, not navigated to a trip that was never made.
    expect(
      screen.getByRole('button', { name: copy['import.confirm'] }),
    ).toBeInTheDocument();
  });
});
