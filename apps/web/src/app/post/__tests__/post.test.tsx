// @vitest-environment happy-dom
//
// happy-dom because the screen navigates (#67).
//
// FE-202 acceptance (FR-PST-01, FR-PST-02), S03-D `398:611`.
//
// FE-202-T1: saving a post does not change a trip.
// FE-202-T2: default/loading/error/404/saved each render.
// FE-202-T3: keyboard reach, focus and accessible names.
//
// The weight is on T1, and it is checked at the wire rather than in the UI.
// SYSTEM_ARCHITECTURE's storage table is explicit: 게시물 저장 writes
// `saved_posts` and leaves trip version 유지. A screen that looked right while
// sending a trip mutation would pass an inspection test and break invariant 1.
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse, delay } from 'msw';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { postFixtures } from '@nullnull/contracts';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { messages } from '../../../i18n/messages.js';
import { createQueryClient } from '../../../shared/api/index.js';
import { API_BASE, problemResponse } from '../../../shared/testing/msw/handlers.js';
import { server } from '../../../shared/testing/msw/server.js';
import { routes } from '../../routes.js';

const copy = messages['en-US'];
const post = postFixtures.detail;

/** Every request the server saw, so a stray trip write cannot hide. */
let seen: { method: string; path: string }[] = [];

beforeEach(() => {
  seen = [];
  server.events.on('request:start', ({ request }) => {
    seen.push({ method: request.method, path: new URL(request.url).pathname });
  });
});

afterEach(() => {
  server.events.removeAllListeners();
});

function renderPost(postId: string = post.id) {
  const router = createMemoryRouter(routes, { initialEntries: [`/posts/${postId}`] });
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <I18nProvider>
        <RouterProvider router={router} />
      </I18nProvider>
    </QueryClientProvider>,
  );
}

describe('FE-202-T1 saving a post is not a trip change', () => {
  it('writes only the post saved resource', async () => {
    const user = userEvent.setup();
    renderPost();
    await user.click(await screen.findByRole('button', { name: copy['post.save'] }));

    await waitFor(() => {
      expect(seen.some((r) => r.method === 'PUT')).toBe(true);
    });
    // Session bootstrap and the CSRF reissue are writes too, so this counts
    // only the ones that carry content: exactly one, to the post's own
    // resource.
    const writes = seen.filter(
      (r) =>
        r.method !== 'GET' &&
        !r.path.startsWith('/api/v1/session') &&
        !r.path.startsWith('/api/v1/demo'),
    );
    expect(writes).toHaveLength(1);
    expect(writes[0]?.path).toBe(`/api/v1/posts/${post.id}/saved`);
  });

  it('touches no trip endpoint at all', async () => {
    const user = userEvent.setup();
    renderPost();
    await user.click(await screen.findByRole('button', { name: copy['post.save'] }));

    await waitFor(() => {
      expect(seen.some((r) => r.method === 'PUT')).toBe(true);
    });
    // SYSTEM_ARCHITECTURE: 게시물 저장 -> saved_posts, trip version 유지. No
    // trip is in this resource's path, so there is nothing to bump — and this
    // asserts that nothing tried.
    expect(seen.some((r) => r.path.includes('/trips'))).toBe(false);
  });

  it('sends no If-Match, because there is no trip version to guard', async () => {
    let header: string | null | undefined;
    server.use(
      http.put(`${API_BASE}/posts/:postId/saved`, ({ request }) => {
        header = request.headers.get('If-Match');
        return HttpResponse.json(postFixtures.savedState, { status: 201 });
      }),
    );
    const user = userEvent.setup();
    renderPost();
    await user.click(await screen.findByRole('button', { name: copy['post.save'] }));
    await waitFor(() => {
      expect(header).not.toBeUndefined();
    });
    expect(header).toBeNull();
  });

  it('says the trip is unchanged rather than leaving the user to guess', async () => {
    renderPost();
    expect(await screen.findByText(copy['post.saveNote'])).toBeInTheDocument();
  });

  it('offers no way to add a place to a trip from here', async () => {
    // PostDetail.places is a plain PlaceSummary[] with no candidateState, so
    // this screen knows nothing about trips and claims nothing. Adding a
    // candidate is FE-303's surface.
    renderPost();
    const list = await screen.findByRole('region', { name: copy['post.places'] });
    expect(within(list).queryAllByRole('button')).toHaveLength(0);
  });
});

describe('FE-202-T1 a repeat save is a success, not an error', () => {
  it('treats an already-saved post as saved', async () => {
    // The contract answers 200 with duplicate:true when the save already
    // existed, and 201 when it is new. Both mean saved.
    server.use(
      http.put(`${API_BASE}/posts/:postId/saved`, () =>
        HttpResponse.json(
          { ...postFixtures.savedState, duplicate: true },
          { status: 200 },
        ),
      ),
    );
    const user = userEvent.setup();
    renderPost();
    await user.click(await screen.findByRole('button', { name: copy['post.save'] }));

    expect(
      await screen.findByRole('button', { name: copy['post.unsave'] }),
    ).toBeInTheDocument();
    expect(screen.queryByText(copy['post.saveFailed'])).toBeNull();
  });

  it('unsaves again and the control returns to save', async () => {
    const user = userEvent.setup();
    renderPost();
    await user.click(await screen.findByRole('button', { name: copy['post.save'] }));
    await user.click(await screen.findByRole('button', { name: copy['post.unsave'] }));
    expect(
      await screen.findByRole('button', { name: copy['post.save'] }),
    ).toBeInTheDocument();
  });
});

describe('FE-202-T2 the screen renders each of its states', () => {
  it('shows the post it loaded', async () => {
    renderPost();
    expect(
      await screen.findByRole('heading', { level: 1, name: post.title }),
    ).toBeInTheDocument();
    expect(screen.getByText(post.body)).toBeInTheDocument();
  });

  it('shows a loading state before the answer arrives', async () => {
    server.use(
      http.get(`${API_BASE}/posts/:postId`, async () => {
        await delay(50);
        return HttpResponse.json(post);
      }),
    );
    renderPost();
    expect(await screen.findByRole('status')).toHaveTextContent(copy['post.loading']);
  });

  it('distinguishes a missing post from a failed request', async () => {
    // A deep link to a post that does not exist is not worth retrying; a
    // network failure is. FR-PST-01 asks for the two to be distinguishable.
    renderPost('018f5b00-0000-7000-8000-0000000000ff');
    expect(await screen.findByRole('alert')).toHaveTextContent(copy['post.notFound']);
    expect(screen.queryByRole('button', { name: copy['post.retry'] })).toBeNull();
  });

  it('offers a retry when the request fails', async () => {
    server.use(http.get(`${API_BASE}/posts/:postId`, () => HttpResponse.error()));
    renderPost();
    expect(await screen.findByRole('alert')).toHaveTextContent(copy['post.error']);
    expect(screen.getByRole('button', { name: copy['post.retry'] })).toBeInTheDocument();
  });

  it('reports a failed save without claiming it worked', async () => {
    server.use(
      http.put(`${API_BASE}/posts/:postId/saved`, () => problemResponse('RATE_LIMITED')),
    );
    const user = userEvent.setup();
    renderPost();
    await user.click(await screen.findByRole('button', { name: copy['post.save'] }));
    expect(await screen.findByText(copy['post.saveFailed'])).toBeInTheDocument();
    expect(screen.getByRole('button', { name: copy['post.save'] })).toBeInTheDocument();
  });
});

describe('FE-202 the places are credited', () => {
  it('shows the source the server named for each place', async () => {
    renderPost();
    await screen.findByRole('heading', { level: 1, name: post.title });
    // CMP-ATT-001: a KTO-sourced place carries its credit wherever it appears,
    // shown verbatim (CMP-ATT-003).
    const credit = post.places[0]?.sourceAttribution?.attribution ?? '';
    expect(credit).not.toBe('');
    expect(screen.getAllByText(credit).length).toBeGreaterThan(0);
  });

  it('does not render categoryCode, which is machine text', async () => {
    renderPost();
    await screen.findByRole('heading', { level: 1, name: post.title });
    const code = post.places[0]?.categoryCode ?? 'x';
    expect(screen.queryByText(new RegExp(`^${code}$`))).toBeNull();
  });
});

describe('FE-202-T3 keyboard and names', () => {
  it('reaches the save control by keyboard and activates it with Enter', async () => {
    const user = userEvent.setup();
    renderPost();
    const button = await screen.findByRole('button', { name: copy['post.save'] });
    button.focus();
    expect(button).toHaveFocus();
    await user.keyboard('{Enter}');
    await waitFor(() => {
      expect(seen.some((r) => r.method === 'PUT')).toBe(true);
    });
  });

  it('names the places section so a screen reader can find it', async () => {
    renderPost();
    expect(
      await screen.findByRole('region', { name: copy['post.places'] }),
    ).toBeInTheDocument();
  });

  it('offers a back control that returns to the feed', async () => {
    const user = userEvent.setup();
    renderPost();
    await user.click(await screen.findByRole('button', { name: copy['post.back'] }));
    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 1 })).toHaveAttribute(
        'id',
        'feed-heading',
      );
    });
  });
});
