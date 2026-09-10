import '@testing-library/jest-dom/vitest';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { resetMockState } from './src/shared/testing/msw/handlers.js';
import { server } from './src/shared/testing/msw/server.js';

// `error` so an unhandled request fails the test instead of reaching the
// network: a test that silently hits a real host is not a test.
beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' });
});
afterEach(() => {
  server.resetHandlers();
  // Handlers that model state (FE-106's trip version) would otherwise carry a
  // previous test's mutation into the next one.
  resetMockState();
});
afterAll(() => {
  server.close();
});
