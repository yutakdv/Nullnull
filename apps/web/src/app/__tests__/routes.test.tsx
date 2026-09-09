import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import { I18nProvider } from '../../i18n/I18nProvider.js';
import { createQueryClient } from '../../shared/api/index.js';
import { routes } from '../routes.js';

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

describe('P0 route table', () => {
  // Routes whose screens have not been built yet still resolve to a labelled
  // placeholder rather than a blank page. Rows leave this list as their slice
  // lands: /, /language and /intro in FE-101, /profile in FE-105,
  // /about-data in FE-404.
  it.each([
    ['/feed', 'feed'],
    ['/live', 'live'],
  ])('resolves %s to its placeholder', async (path, routeId) => {
    renderAt(path);
    expect(await screen.findByTestId('placeholder-route')).toHaveTextContent(routeId);
  });

  it('resolves deep-linked detail routes', async () => {
    renderAt('/posts/018f3f8e-9b67-7a21-8d31-31d315b93911');
    expect(await screen.findByTestId('placeholder-route')).toHaveTextContent(
      'post-detail',
    );
  });

  it.each([
    ['/', 'splash-heading'],
    ['/language', 'language-heading'],
    ['/intro', 'intro-heading'],
    ['/profile', 'profile-heading'],
    ['/about-data', 'data-guide-heading'],
  ])('resolves %s to its built screen', async (path, headingId) => {
    renderAt(path);
    expect(await screen.findByRole('heading', { level: 1 })).toHaveAttribute(
      'id',
      headingId,
    );
  });

  it('renders an explicit not-found screen instead of blanking', async () => {
    renderAt('/does-not-exist');
    expect(await screen.findByTestId('placeholder-route')).toHaveTextContent('not-found');
    expect(screen.getByRole('link')).toHaveAttribute('href', '/');
  });
});
