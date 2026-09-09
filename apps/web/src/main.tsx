import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './app/App.js';
import './styles.css';

const container = document.getElementById('root');
if (!container) {
  throw new Error('Root container is missing from index.html');
}

// MSW is dev-only and loaded dynamically so Rollup drops it from the build.
// import.meta.env.DEV is statically false in production, which lets the whole
// branch — and msw with it — be tree-shaken away. scripts/integration-test.sh
// runs Playwright against the built app and a real API; mocks reaching that
// build would make the docker-integration gate pass without proving anything.
async function startMocking() {
  if (!import.meta.env.DEV || import.meta.env.VITE_API_MOCKING !== 'on') return;
  const { worker } = await import('./shared/testing/msw/browser.js');
  await worker.start({ onUnhandledRequest: 'bypass' });
}

// Offline shell (FE-004). Production only: in dev the MSW worker owns this
// scope, and a second worker caching the shell would serve stale bundles while
// the app is being edited. The worker itself never caches API responses — see
// public/sw.js for why that boundary matters.
function registerOfflineShell() {
  if (import.meta.env.DEV || !('serviceWorker' in navigator)) return;
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch((error: unknown) => {
      // An unavailable worker costs offline support, not the app.
      console.error('Offline shell failed to register', error);
    });
  });
}

// The app renders whether or not mocking starts. A failed worker registration
// is a dev-tooling problem, not a reason to leave the page blank.
void startMocking()
  .catch((error: unknown) => {
    console.error('MSW failed to start; continuing without mocks', error);
  })
  .finally(() => {
    createRoot(container).render(
      <StrictMode>
        <App />
      </StrictMode>,
    );
    registerOfflineShell();
  });
