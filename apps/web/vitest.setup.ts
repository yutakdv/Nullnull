import '@testing-library/jest-dom/vitest';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { server } from './src/shared/testing/msw/server.js';

// `error` so an unhandled request fails the test instead of reaching the
// network: a test that silently hits a real host is not a test.
beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' });
});
afterEach(() => {
  server.resetHandlers();
});
afterAll(() => {
  server.close();
});
