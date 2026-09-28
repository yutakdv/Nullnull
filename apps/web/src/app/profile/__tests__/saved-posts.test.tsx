// @vitest-environment happy-dom
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse, delay } from 'msw';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { postFixtures } from '@nullnull/contracts';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { createQueryClient } from '../../../shared/api/index.js';
import { API_BASE } from '../../../shared/testing/msw/handlers.js';
import { server } from '../../../shared/testing/msw/server.js';
import { routes } from '../../routes.js';

const first = {
  post: {
    id: postFixtures.detail.id,
    title: 'Saved first',
    excerpt: null,
    coverUrl: postFixtures.detail.coverUrl,
    publishedAt: postFixtures.detail.publishedAt,
  },
  savedAt: '2026-09-11T05:40:00Z',
};
const second = {
  post: {
    ...first.post,
    id: '018f5b00-0000-7000-8000-000000000002',
    title: 'Saved second',
  },
  savedAt: '2026-09-10T05:40:00Z',
};
let seen: Array<{ method: string; path: string }> = [];

beforeEach(() => {
  seen = [];
  server.events.on('request:start', ({ request }) => {
    seen.push({ method: request.method, path: new URL(request.url).pathname });
  });
});
afterEach(() => server.events.removeAllListeners());

function mount(path = '/profile') {
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

describe('A-07 saved posts', () => {
  it('opens the saved list from profile and shows its empty state', async () => {
    server.use(
      http.get(`${API_BASE}/me/saved-posts`, () =>
        HttpResponse.json({ items: [], page: { nextCursor: null, hasMore: false } }),
      ),
    );
    const user = userEvent.setup();
    const router = mount();
    await user.click(await screen.findByRole('link', { name: 'Saved posts' }));
    expect(router.state.location.pathname).toBe('/profile/saved-posts');
    expect(await screen.findByText('No saved posts yet.')).toBeInTheDocument();
  });

  it('loads another cursor page and opens a saved post detail', async () => {
    server.use(
      http.get(`${API_BASE}/me/saved-posts`, ({ request }) => {
        const cursor = new URL(request.url).searchParams.get('cursor');
        return HttpResponse.json(
          cursor === 'after-1'
            ? { items: [second], page: { nextCursor: null, hasMore: false } }
            : { items: [first], page: { nextCursor: 'after-1', hasMore: true } },
        );
      }),
      http.get(`${API_BASE}/posts/:postId`, () =>
        HttpResponse.json({ ...postFixtures.detail, saved: true }),
      ),
    );
    const user = userEvent.setup();
    const router = mount('/profile/saved-posts');
    expect(await screen.findByRole('link', { name: 'Saved first' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Load more' }));
    expect(await screen.findByRole('link', { name: 'Saved second' })).toBeInTheDocument();
    await user.click(screen.getByRole('link', { name: 'Saved first' }));
    expect(router.state.location.pathname).toBe(`/posts/${first.post.id}`);
    expect(
      await screen.findByRole('button', { name: 'Remove from saved' }),
    ).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Back' }));
    expect(router.state.location.pathname).toBe('/profile/saved-posts');
  });

  it('removes a saved post by keyboard without changing a trip', async () => {
    let saved = true;
    server.use(
      http.get(`${API_BASE}/me/saved-posts`, () =>
        HttpResponse.json({
          items: saved ? [first] : [],
          page: { nextCursor: null, hasMore: false },
        }),
      ),
      http.delete(`${API_BASE}/posts/:postId/saved`, () => {
        saved = false;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = userEvent.setup();
    mount('/profile/saved-posts');
    const remove = await screen.findByRole('button', { name: 'Remove Saved first' });
    remove.focus();
    expect(remove).toHaveFocus();
    await user.keyboard('{Enter}');
    expect(await screen.findByText('No saved posts yet.')).toBeInTheDocument();
    expect(seen.filter((request) => request.method === 'DELETE')).toEqual([
      { method: 'DELETE', path: `/api/v1/posts/${first.post.id}/saved` },
    ]);
    expect(seen.some((request) => request.path.includes('/trips'))).toBe(false);
  });

  it('announces removal while the request is pending and prevents a second action', async () => {
    server.use(
      http.get(`${API_BASE}/me/saved-posts`, () =>
        HttpResponse.json({ items: [first], page: { nextCursor: null, hasMore: false } }),
      ),
      http.delete(`${API_BASE}/posts/:postId/saved`, async () => {
        await delay(80);
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = userEvent.setup();
    mount('/profile/saved-posts');
    await user.click(await screen.findByRole('button', { name: 'Remove Saved first' }));
    expect(screen.getByRole('button', { name: 'Removing Saved first' })).toBeDisabled();
  });
});
