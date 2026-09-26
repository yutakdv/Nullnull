// @vitest-environment happy-dom
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { tripFixtures, postFixtures, placeFixtures } from '@nullnull/contracts';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { messages } from '../../../i18n/messages.js';
import { createQueryClient } from '../../../shared/api/index.js';
import { API_BASE } from '../../../shared/testing/msw/handlers.js';
import { server } from '../../../shared/testing/msw/server.js';
import {
  NEXT_CURSOR,
  searchPages,
  servePlaceSearchPages,
} from '../../../shared/testing/msw/place-search-pages.js';
import { routes } from '../../routes.js';
import { imageChecksum, uploadPostImage } from '../authoring.js';

vi.mock('../authoring.js', async (load) => ({
  ...(await load<typeof import('../authoring.js')>()),
  imageChecksum: vi.fn(async () => 'a'.repeat(64)),
  uploadPostImage: vi.fn(async () => undefined),
}));
const copy = messages['en-US'];
const ticket = {
  uploadId: '018f5b00-0000-7000-8000-000000000099',
  url: 'https://storage.example.test/upload',
  method: 'PUT',
  headers: { 'Content-Type': 'image/png' },
  expiresAt: '2099-01-01T00:00:00Z',
};
function mount(path = '/posts/new') {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  render(
    <QueryClientProvider client={createQueryClient()}>
      <I18nProvider>
        <RouterProvider router={router} />
      </I18nProvider>
    </QueryClientProvider>,
  );
  return router;
}
beforeEach(() => {
  vi.mocked(imageChecksum).mockClear();
  vi.mocked(uploadPostImage).mockReset().mockResolvedValue(undefined);
  server.use(
    http.post(`${API_BASE}/posts/images/uploads`, () =>
      HttpResponse.json(ticket, { status: 201 }),
    ),
  );
});

describe('FE-603-T5 a chosen place keeps its credit', () => {
  it('credits the checklist row it is chosen from', async () => {
    // The row and the chip both read `place`; the static scan sees one group.
    const place = placeFixtures.searchPage.items[0];
    const credit = place?.sourceAttribution;
    if (!place || !credit) throw new Error('the search fixture lost its credited place');
    const user = userEvent.setup();
    mount();
    await user.type(await screen.findByLabelText(copy['author.search']), place.name);
    const row = (await screen.findByRole('checkbox', { name: place.name })).closest(
      'li',
    ) as HTMLElement;
    expect(within(row).getByRole('link', { name: credit.attribution })).toHaveAttribute(
      'href',
      credit.officialUrl ?? '',
    );
  });

  it('credits the chip that stands for the place', async () => {
    // The search checklist credited the place; the chip it became did not. The
    // chip is what stays on screen as "this post is about 경복궁", so it
    // carries the credit itself, next to the button rather than inside it.
    const place = placeFixtures.searchPage.items[0];
    const credit = place?.sourceAttribution;
    if (!place || !credit) throw new Error('the search fixture lost its credited place');
    const user = userEvent.setup();
    mount();
    await user.type(await screen.findByLabelText(copy['author.search']), place.name);
    await user.click(await screen.findByRole('checkbox', { name: place.name }));

    const chip = await screen.findByRole('button', { name: `${place.name} ×` });
    expect(within(chip).queryByRole('link')).toBeNull();
    const item = chip.closest('li') as HTMLElement;
    expect(within(item).getByRole('link', { name: credit.attribution })).toHaveAttribute(
      'href',
      credit.officialUrl ?? '',
    );
  });
});

describe('#54 the place checklist continues past the first page', () => {
  it('offers a place from the next page to link', async () => {
    // The continuation itself is proven by FE-103-T5..T21 (place-search.test.tsx,
    // must-visit.test.tsx); this is the wiring of this screen's own checklist.
    const served = servePlaceSearchPages();
    const [next] = searchPages.next;
    const user = userEvent.setup();
    mount();
    await user.type(await screen.findByLabelText(copy['author.search']), '서울');
    await user.click(
      await screen.findByRole('button', { name: copy['placeSearch.more'] }),
    );
    expect(await screen.findByRole('checkbox', { name: next.name })).toBeInTheDocument();
    expect(served.bodies.at(-1)?.cursor).toBe(NEXT_CURSOR);
  });
});

describe('FE-103-T19 a failed next page is not a failed search here', () => {
  it('FE-103-T8 FE-103-T19 keeps the checklist and adds no search failure', async () => {
    // The screen's own alert is for a search that failed outright; a failed
    // page two is the continuation's to report.
    servePlaceSearchPages({ failNext: 1 });
    const [a, b] = searchPages.first;
    const user = userEvent.setup();
    mount();
    await user.type(await screen.findByLabelText(copy['author.search']), '서울');
    await user.click(
      await screen.findByRole('button', { name: copy['placeSearch.more'] }),
    );
    await screen.findByRole('button', { name: copy['placeSearch.retryMore'] });

    for (const place of [a, b]) {
      expect(screen.getByRole('checkbox', { name: place.name })).toBeInTheDocument();
    }
    expect(screen.queryByText(copy['author.searchFailed'])).toBeNull();
    expect(screen.getByRole('alert')).toHaveTextContent(copy['placeSearch.moreFailed']);
  });
});

describe('#312 authoring and trip prerequisite', () => {
  it('keeps the feed visible but hides authoring when there is no itinerary', async () => {
    server.use(
      http.get(`${API_BASE}/trips`, () => HttpResponse.json(tripFixtures.pageEmpty)),
    );
    mount('/feed');
    await screen.findByRole('heading', { name: copy['feed.title'] });
    await screen.findByText(copy['feed.noTripTitle']);
    expect(
      screen.queryByRole('link', { name: copy['author.entry'] }),
    ).not.toBeInTheDocument();
  });
  it('shows the authoring entry for an existing itinerary', async () => {
    mount('/feed');
    expect(
      await screen.findByRole('link', { name: copy['author.entry'] }),
    ).toHaveAttribute('href', '/posts/new');
  });
  it('guards direct access with a create-trip link, without upload inputs', async () => {
    server.use(
      http.get(`${API_BASE}/trips`, () => HttpResponse.json(tripFixtures.pageEmpty)),
    );
    mount();
    expect(
      await screen.findByRole('link', { name: copy['author.createTrip'] }),
    ).toHaveAttribute('href', '/start');
    expect(screen.queryByLabelText(copy['author.choose'])).not.toBeInTheDocument();
  });
  it('does not treat a failed trip lookup as an empty itinerary', async () => {
    server.use(
      http.get(`${API_BASE}/trips`, () => new HttpResponse(null, { status: 503 })),
    );
    mount();
    expect(await screen.findByText(copy['author.failedTrips'])).toBeInTheDocument();
    expect(
      screen.queryByRole('link', { name: copy['author.createTrip'] }),
    ).not.toBeInTheDocument();
  });
  it('retries an ambiguous publish with the identical key/body and navigates to the returned post', async () => {
    const requests: { key: string | null; body: unknown }[] = [];
    server.use(
      http.post(`${API_BASE}/posts`, async ({ request }) => {
        requests.push({
          key: request.headers.get('Idempotency-Key'),
          body: await request.json(),
        });
        if (requests.length === 1) return HttpResponse.error();
        return HttpResponse.json({ postId: postFixtures.detail.id }, { status: 201 });
      }),
    );
    const user = userEvent.setup();
    const router = mount();
    await user.upload(
      await screen.findByLabelText(copy['author.choose']),
      new File(['image'], 'cover.png', { type: 'image/png' }),
    );
    await screen.findByText(copy['author.uploaded']);
    await user.type(screen.getByLabelText(copy['author.title']), 'A calm walk');
    await user.type(screen.getByLabelText(copy['author.caption']), 'A quiet afternoon');
    expect(screen.getByRole('button', { name: copy['author.publish'] })).toBeDisabled();
    await user.type(screen.getByLabelText(copy['author.search']), '경복궁');
    await user.click(await screen.findByRole('checkbox', { name: '경복궁' }));
    await user.click(screen.getByRole('button', { name: copy['author.publish'] }));
    await screen.findByText(copy['author.publishFailed']);
    expect(screen.getByLabelText(copy['author.title'])).toBeDisabled();
    await user.click(screen.getByRole('button', { name: copy['author.retryPublish'] }));
    await waitFor(() =>
      expect(router.state.location.pathname).toBe(`/posts/${postFixtures.detail.id}`),
    );
    expect(requests).toHaveLength(2);
    expect(requests[0]).toEqual(requests[1]);
    expect(requests[0]?.body).toMatchObject({
      title: 'A calm walk',
      body: 'A quiet afternoon',
      placeIds: [placeFixtures.searchPage.items[0]!.id],
      uploadId: ticket.uploadId,
    });
    expect(uploadPostImage).toHaveBeenCalledTimes(1);
  });
  it('does not publish after an upload failure and preserves the selected file for retry', async () => {
    vi.mocked(uploadPostImage).mockRejectedValueOnce(new Error('network'));
    const user = userEvent.setup();
    mount();
    await user.upload(
      await screen.findByLabelText(copy['author.choose']),
      new File(['image'], 'cover.png', { type: 'image/png' }),
    );
    await screen.findByText(copy['author.uploadFailed']);
    expect(screen.getByRole('button', { name: copy['author.publish'] })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: copy['author.retryUpload'] }));
    await screen.findByText(copy['author.uploaded']);
    expect(uploadPostImage).toHaveBeenCalledTimes(2);
  });
});
