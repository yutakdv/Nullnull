import { QueryClientProvider } from '@tanstack/react-query';
import { Component, type ErrorInfo, type ReactNode } from 'react';
import { RouterProvider, createBrowserRouter } from 'react-router';
import { I18nProvider } from '../i18n/I18nProvider.js';
import { createQueryClient } from '../shared/api/index.js';
import { routes } from './routes.js';

// Retry behaviour comes from the Problem policy table (shared/api). Mutations
// never auto-retry: each command decides its own Idempotency-Key handling.
const queryClient = createQueryClient();
const router = createBrowserRouter(routes);

// Last resort only. Route-level failures are handled by RouteErrorBoundary,
// which keeps the shell alive; this catches a crash in the providers
// themselves, where there is no router left to render into. It cannot use
// useI18n — the provider it guards may be the thing that failed — so its copy
// is intentionally minimal and locale-independent.
class RootErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    if (import.meta.env.DEV) {
      console.error('Root boundary caught an error', error, info.componentStack);
    }
  }

  render() {
    if (this.state.failed) {
      return (
        <main id="main">
          <h1>널널 · Nullnull</h1>
          <p>앱을 시작하지 못했어요. 새로고침해주세요.</p>
          <p lang="en">The app could not start. Please reload.</p>
        </main>
      );
    }
    return this.props.children;
  }
}

export function App() {
  return (
    <RootErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <I18nProvider>
          <RouterProvider router={router} />
        </I18nProvider>
      </QueryClientProvider>
    </RootErrorBoundary>
  );
}
