// Browser worker for `npm run dev`. Never imported at module scope by app code:
// main.tsx loads it through a dynamic import behind an import.meta.env.DEV
// guard so Rollup drops it from the production bundle entirely.
//
// This matters beyond bundle size. scripts/integration-test.sh runs Playwright
// against the built app and a real API. If MSW ever started in that build, the
// e2e suite would exercise mocks and the docker-integration gate would pass
// while proving nothing.
import { setupWorker } from 'msw/browser';
import { handlers } from './handlers.js';

export const worker = setupWorker(...handlers);
