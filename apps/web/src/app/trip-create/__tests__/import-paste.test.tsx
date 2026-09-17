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
import { http } from 'msw';
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
