import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider, createBrowserRouter } from 'react-router';
import { I18nProvider } from '../i18n/I18nProvider.js';
import { routes } from './routes.js';

// Defaults are tuned when the first query lands (FE-101). Mutations never
// auto-retry: each command decides its own Idempotency-Key handling.
const queryClient = new QueryClient({
  defaultOptions: { mutations: { retry: 0 } },
});
const router = createBrowserRouter(routes);

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <I18nProvider>
        <RouterProvider router={router} />
      </I18nProvider>
    </QueryClientProvider>
  );
}
