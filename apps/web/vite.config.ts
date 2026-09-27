import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { defineConfig, type Plugin } from 'vite';
import react from '@vitejs/plugin-react';

const configuredApiTarget = process.env.API_INTERNAL_BASE_URL;
const apiTarget = configuredApiTarget ?? 'http://localhost:8080';

// The MSW worker script is served from mocks/ rather than public/ so it never
// reaches dist. A worker sitting in the runtime image would let a build serve
// mocks, and scripts/integration-test.sh runs Playwright against that image
// with a real api container — the e2e suite would pass without touching the
// API. apply: 'serve' keeps this plugin out of the build entirely.
function mswWorkerPlugin(): Plugin {
  const workerPath = fileURLToPath(
    new URL('./mocks/mockServiceWorker.js', import.meta.url),
  );
  return {
    name: 'nullnull-msw-worker',
    apply: 'serve',
    configureServer(server) {
      server.middlewares.use('/mockServiceWorker.js', (_request, response) => {
        response.setHeader('Content-Type', 'text/javascript');
        response.end(readFileSync(workerPath, 'utf8'));
      });
    },
  };
}

// Only VITE_* values reach the browser bundle and all of them are public.
// Secrets, raw itinerary text and precise location never go here
// (.claude/rules/frontend.md).
export default defineConfig({
  plugins: [react(), mswWorkerPlugin()],
  server: {
    port: 5173,
    // Same-origin proxy so the session cookie and CSRF token work in dev
    // without relaxing CORS.
    proxy: {
      '/api': {
        target: apiTarget,
        // Local API requests keep the browser-facing origin. A remote deploy
        // (CloudFront, for example) must receive its own Host header or it can
        // reject the proxy request before the API sees it. All other request
        // headers, including a user-supplied deployment gate header, pass
        // through without being stored in frontend configuration.
        changeOrigin: configuredApiTarget !== undefined,
      },
    },
  },
  preview: { port: 4173 },
  test: {
    include: ['src/**/*.test.{ts,tsx}'],
    // jsdom for the suite. Files that exercise a react-router loader override
    // this with `// @vitest-environment happy-dom`: Vitest's jsdom environment
    // installs jsdom's AbortController/AbortSignal but leaves Request as Node's
    // undici, which brand-checks the signal against its own realm, so building
    // the Request a loader is called with throws (#67).
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./vitest.setup.ts'],
    css: false,
    // JUnit alongside the readable one in CI, as playwright.config.ts does for the browser suite.
    // check_test_reports.py reads JUnit and nothing else, so an acceptance clause proven here was
    // invisible to the gate. The path ends in unit/ because the reader looks for
    // <dir>/<suite>/*.xml, and compose.integration.yml binds vitest-report out of the container.
    reporters: process.env.CI ? ['default', 'junit'] : ['default'],
    outputFile: process.env.CI ? { junit: 'vitest-report/unit/results.xml' } : undefined,
  },
});
