import '@testing-library/jest-dom/vitest';
import { afterAll, afterEach, beforeAll, beforeEach, vi } from 'vitest';
import { resetMockState } from './src/shared/testing/msw/handlers.js';
import { server } from './src/shared/testing/msw/server.js';

// `error` so an unhandled request fails the test instead of reaching the
// network: a test that silently hits a real host is not a test.
beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' });
});
beforeEach(() => {
  // Local browser credentials are public-at-runtime but must never leak into
  // unit-test output. Tests that exercise the SDK opt in with a fake key.
  vi.stubEnv('VITE_KAKAO_MAP_APP_KEY', '');
});
afterEach(() => {
  vi.unstubAllEnvs();
  server.resetHandlers();
  // Handlers that model state (FE-106's trip version) would otherwise carry a
  // previous test's mutation into the next one.
  resetMockState();
});
afterAll(() => {
  server.close();
});
