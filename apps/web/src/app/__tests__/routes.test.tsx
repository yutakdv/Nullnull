import { render, screen } from '@testing-library/react';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import { I18nProvider } from '../../i18n/I18nProvider.js';
import { routes } from '../routes.js';

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  return render(
    <I18nProvider>
      <RouterProvider router={router} />
    </I18nProvider>,
  );
}

describe('P0 route table', () => {
  it.each([
    ['/', 'splash'],
    ['/language', 'language'],
    ['/feed', 'feed'],
    ['/live', 'live'],
    ['/profile', 'profile'],
    ['/about-data', 'about-data'],
  ])('resolves %s', async (path, routeId) => {
    renderAt(path);
    expect(await screen.findByTestId('placeholder-route')).toHaveTextContent(routeId);
  });

  it('resolves deep-linked detail routes', async () => {
    renderAt('/posts/018f3f8e-9b67-7a21-8d31-31d315b93911');
    expect(await screen.findByTestId('placeholder-route')).toHaveTextContent(
      'post-detail',
    );
  });

  it('renders an explicit not-found screen instead of blanking', async () => {
    renderAt('/does-not-exist');
    expect(await screen.findByTestId('placeholder-route')).toHaveTextContent('not-found');
    expect(screen.getByRole('link')).toHaveAttribute('href', '/');
  });
});
