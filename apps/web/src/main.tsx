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
  });
