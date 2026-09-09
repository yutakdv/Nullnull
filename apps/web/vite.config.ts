import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { defineConfig, type Plugin } from 'vite';
import react from '@vitejs/plugin-react';

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
        target: process.env.API_INTERNAL_BASE_URL ?? 'http://localhost:8080',
        changeOrigin: false,
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
  },
});
