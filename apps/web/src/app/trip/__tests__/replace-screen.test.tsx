// @vitest-environment happy-dom
//
// FE-305 slice 4 acceptance (FR-ITM-07, FR-ITM-08), S07-11 `479:3497` /
// `414:2347`.
//
// The screen that shows two places side by side, so invariant 8 is the subject:
// a number appears only when both provenances permit the comparison, and "not
// comparable" never renders as equivalent. The wire is asserted because a sheet
// that believes it preserved the schedule while sending preserveDateTime:false
// would pass an inspection test and move the stop.
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { relatedFixtures, tripFixtures } from '@nullnull/contracts';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { messages } from '../../../i18n/messages.js';
import { createQueryClient } from '../../../shared/api/index.js';
import { API_BASE, problemResponse } from '../../../shared/testing/msw/handlers.js';
import { server } from '../../../shared/testing/msw/server.js';
import { routes } from '../../routes.js';

const copy = messages['en-US'];
const trip = tripFixtures.detailScheduled;
const firstAlternative = relatedFixtures.page.items[0];

interface Sent {
  url: string;
  ifMatch: string | null;
  idempotency: string | null;
  body: Record<string, unknown> | null;
}

let sent: Sent[] = [];

beforeEach(() => {
  sent = [];
  server.events.on('request:start', ({ request }) => {
    if (!request.url.includes('/replace')) return;
    const clone = request.clone();
    const base = {
      url: request.url,
      ifMatch: request.headers.get('If-Match'),
      idempotency: request.headers.get('Idempotency-Key'),
    };
    void clone.json().then(
      (body) => {
        sent.push({ ...base, body: body as Record<string, unknown> });
      },
      () => {
        sent.push({ ...base, body: null });
      },
    );
  });
});

afterEach(() => {
  server.events.removeAllListeners();
});

function renderTrip() {
  const router = createMemoryRouter(routes, { initialEntries: [`/trip/${trip.id}`] });
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <I18nProvider>
        <RouterProvider router={router} />
      </I18nProvider>
    </QueryClientProvider>,
  );
}

async function openReplace(name: string) {
  const user = userEvent.setup();
  renderTrip();
  const heading = await screen.findByRole('heading', { level: 3, name });
  const card = heading.closest('article');
  if (!card) throw new Error('card not found');
  await user.click(
    within(card).getByRole('button', {
      name: copy['replace.open'].replace('{name}', name),
    }),
  );
  return {
    user,
    sheet: await screen.findByRole('dialog', { name: copy['replace.title'] }),
  };
}

describe('FE-305-T2 the sheet shows both sides with their own sources', () => {
  it('names the stop being replaced and offers the alternatives', async () => {
    const { sheet } = await openReplace('경복궁');
    expect(within(sheet).getByText('경복궁')).toBeInTheDocument();
    for (const option of relatedFixtures.page.items) {
      expect(await within(sheet).findByText(option.place.name)).toBeInTheDocument();
    }
  });

  it("shows the server's own reason for each alternative", async () => {
    const { sheet } = await openReplace('경복궁');
    // relationReason is a required contract field, unlike the reason text on a
    // TripCandidate — so it is shown as written rather than invented.
    expect(
      await within(sheet).findByText(firstAlternative?.relationReason ?? ''),
    ).toBeInTheDocument();
  });

  it('keeps the order the server returned', async () => {
    const { sheet } = await openReplace('경복궁');
    await within(sheet).findByText(firstAlternative?.place.name ?? '');
    const names = within(sheet)
      .getAllByRole('button', { pressed: false })
      .map((b) => b.textContent ?? '');
    // Ranking by crowd would be the same numeric comparison by another name.
    expect(names[0]).toContain(relatedFixtures.page.items[0]?.place.name ?? '');
  });
});

describe('FE-305-T1 invariant 8 governs the comparison', () => {
  it('states that the crowd levels cannot be compared, rather than implying equality', async () => {
    const { sheet } = await openReplace('경복궁');
    // Every fixture row has crowd null, so no pair is comparable.
    expect(
      (await within(sheet).findAllByText(copy['replace.compare.noData'])).length,
    ).toBeGreaterThan(0);
  });

  it('renders no delta or ranking language', async () => {
    const { sheet } = await openReplace('경복궁');
    await within(sheet).findByText(firstAlternative?.place.name ?? '');
    const text = sheet.textContent ?? '';
    // No "less crowded", no arrow, no percentage.
    expect(text).not.toMatch(/덜 붐|더 붐|less crowded|[−↓↑]\s*\d/);
    expect(text).not.toMatch(/\d+\s*%/);
  });
});

describe('FE-305-T1 the sheet names what the swap does to the locks', () => {
  it('says MUST_VISIT is released and the date lock stays', async () => {
    const { sheet } = await openReplace('경복궁');
    // 경복궁 carries MUST_VISIT (pins the place) and DATE (pins the schedule).
    await within(sheet).findByText(firstAlternative?.place.name ?? '');
    expect(sheet).toHaveTextContent(copy['trip.lock.MUST_VISIT']);
    expect(sheet).toHaveTextContent(copy['trip.lock.DATE']);
  });

  it('says nothing is released for a stop with no place lock', async () => {
    const { sheet } = await openReplace('인사동');
    // 인사동 carries TIME only, which pins the clock rather than the place.
    await within(sheet).findByText(firstAlternative?.place.name ?? '');
    expect(sheet).toHaveTextContent(
      copy['replace.keeps'].replace('{locks}', copy['trip.lock.TIME']),
    );
  });
});

describe('FE-305-T1 the replace request preserves the schedule', () => {
  it('sends the chosen place with If-Match and an Idempotency-Key', async () => {
    const { user, sheet } = await openReplace('경복궁');
    await user.click(
      await within(sheet).findByRole('button', {
        name: new RegExp(firstAlternative?.place.name ?? ''),
      }),
    );
    await user.click(
      within(sheet).getByRole('button', { name: copy['replace.confirm'] }),
    );

    await waitFor(() => {
      expect(sent).toHaveLength(1);
    });
    expect(sent[0]?.ifMatch).toBe(`"${String(trip.version)}"`);
    expect(sent[0]?.idempotency).toBeTruthy();
    expect(sent[0]?.body).toMatchObject({
      replacementPlaceId: firstAlternative?.place.id,
    });
  });

  it('omits preserveDateTime rather than sending false', async () => {
    const { user, sheet } = await openReplace('경복궁');
    await user.click(
      await within(sheet).findByRole('button', {
        name: new RegExp(firstAlternative?.place.name ?? ''),
      }),
    );
    await user.click(
      within(sheet).getByRole('button', { name: copy['replace.confirm'] }),
    );

    await waitFor(() => {
      expect(sent).toHaveLength(1);
    });
    // The contract defaults it true; false moves the stop's schedule and has to
    // be an explicit choice, never a form default.
    expect(sent[0]?.body).not.toHaveProperty('preserveDateTime', false);
  });

  it('will not confirm until an alternative is chosen', async () => {
    const { sheet } = await openReplace('경복궁');
    await within(sheet).findByText(firstAlternative?.place.name ?? '');
    expect(
      within(sheet).getByRole('button', { name: copy['replace.confirm'] }),
    ).toBeDisabled();
  });

  it('reports a conflict without claiming the swap happened', async () => {
    server.use(
      http.post(`${API_BASE}/trips/:tripId/items/:itemId/replace`, () =>
        problemResponse('TRIP_CHANGED'),
      ),
    );
    const { user, sheet } = await openReplace('경복궁');
    await user.click(
      await within(sheet).findByRole('button', {
        name: new RegExp(firstAlternative?.place.name ?? ''),
      }),
    );
    await user.click(
      within(sheet).getByRole('button', { name: copy['replace.confirm'] }),
    );
    expect(await screen.findByText(copy['trip.conflict'])).toBeInTheDocument();
  });
});

describe('FE-305-T2 the five relation states each say their own thing', () => {
  it('does not present CHECKING as "nothing to swap in"', async () => {
    server.use(
      http.get(`${API_BASE}/places/:placeId/related`, () =>
        HttpResponse.json(relatedFixtures.checking),
      ),
    );
    const { sheet } = await openReplace('경복궁');
    expect(
      await within(sheet).findByText(copy['replace.state.CHECKING']),
    ).toBeInTheDocument();
    expect(within(sheet).queryByText(copy['replace.state.NONE'])).not.toBeInTheDocument();
  });

  it('says NONE only when the server decided there is nothing', async () => {
    server.use(
      http.get(`${API_BASE}/places/:placeId/related`, () =>
        HttpResponse.json(relatedFixtures.none),
      ),
    );
    const { sheet } = await openReplace('경복궁');
    expect(
      await within(sheet).findByText(copy['replace.state.NONE']),
    ).toBeInTheDocument();
    expect(
      within(sheet).queryByRole('button', { name: copy['replace.confirm'] }),
    ).not.toBeInTheDocument();
  });

  it('reports a failed lookup instead of an empty sheet', async () => {
    server.use(
      http.get(`${API_BASE}/places/:placeId/related`, () => HttpResponse.error()),
    );
    const { sheet } = await openReplace('경복궁');
    expect(await within(sheet).findByText(copy['replace.error'])).toBeInTheDocument();
  });
});

describe('FE-305-T3 the sheet behaves like a dialog', () => {
  it('focuses the safe control and closes on Escape', async () => {
    const { user, sheet } = await openReplace('경복궁');
    await waitFor(() => {
      expect(
        within(sheet).getByRole('button', { name: copy['replace.cancel'] }),
      ).toHaveFocus();
    });
    await user.keyboard('{Escape}');
    await waitFor(() => {
      expect(
        screen.queryByRole('dialog', { name: copy['replace.title'] }),
      ).not.toBeInTheDocument();
    });
    expect(sent).toEqual([]);
  });

  it('marks the chosen alternative with aria-pressed, not colour alone', async () => {
    const { user, sheet } = await openReplace('경복궁');
    const option = await within(sheet).findByRole('button', {
      name: new RegExp(firstAlternative?.place.name ?? ''),
    });
    expect(option).toHaveAttribute('aria-pressed', 'false');
    await user.click(option);
    expect(option).toHaveAttribute('aria-pressed', 'true');
  });
});
