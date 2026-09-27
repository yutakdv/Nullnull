import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import { I18nProvider } from '../../i18n/I18nProvider.js';
import { messages } from '../../i18n/messages.js';
import { createQueryClient } from '../../shared/api/index.js';
import { routes } from '../routes.js';

const copy = messages['en-US'];

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <I18nProvider>
        <RouterProvider router={router} />
      </I18nProvider>
    </QueryClientProvider>,
  );
}

describe('FE-001-T1 P0 route table', () => {
  // Every P0 route now resolves to a real screen. The last placeholder row
  // was /live, which became its own 준비 중 screen rather than the debug
  // output of PlaceholderScreen — a persistent tab that printed the literal
  // string "live" read as a broken build. The others left as their slices
  // landed: /, /language and /intro in FE-101, /profile in FE-105,
  // /about-data in FE-404, the optimization run in FE-502.
  //
  // PlaceholderScreen is deleted (no route used it), and with it the only
  // element that carried `data-testid="placeholder-route"`. An assertion that
  // this test id is absent could no longer fail, so the heading below is the
  // whole check.
  it('resolves /live to the live screen, not a debug placeholder', async () => {
    renderAt('/live');
    expect(
      await screen.findByRole('heading', { level: 1, name: /라이브|Live/ }),
    ).toBeInTheDocument();
  });

  it('resolves a deep-linked post to its screen, not a blank page', async () => {
    // A post detail is a real screen since FE-202. An id the mock does not
    // serve still resolves to the screen, which says the post is missing —
    // that is FR-PST-01's deep-link/404 requirement.
    renderAt('/posts/018f3f8e-9b67-7a21-8d31-31d315b93911');
    expect(await screen.findByRole('heading', { level: 1 })).toHaveAttribute(
      'id',
      'post-heading',
    );
  });

  it.each([
    ['/', 'splash-heading'],
    ['/language', 'language-heading'],
    ['/intro', 'intro-heading'],
    ['/profile', 'profile-heading'],
    ['/about-data', 'data-guide-heading'],
    ['/feed', 'feed-heading'],
  ])('resolves %s to its built screen', async (path, headingId) => {
    renderAt(path);
    expect(await screen.findByRole('heading', { level: 1 })).toHaveAttribute(
      'id',
      headingId,
    );
  });

  it('renders an explicit not-found screen instead of blanking', async () => {
    renderAt('/does-not-exist');
    // The localised heading, not a debug token. This used to assert the literal
    // string "not-found", which a dev-only placeholder had left in the screen —
    // so the test required the very thing that should not ship.
    expect(await screen.findByRole('heading', { level: 1 })).toHaveAttribute(
      'id',
      'not-found-heading',
    );
    expect(screen.getByText(copy['app.notFound.title'])).toBeInTheDocument();
    expect(screen.queryByText('not-found')).toBeNull();
    expect(screen.getByRole('link')).toHaveAttribute('href', '/');
  });
});
