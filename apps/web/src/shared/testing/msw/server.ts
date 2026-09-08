// Node request interception for vitest. Imported only from test setup.
import { setupServer } from 'msw/node';
import { handlers } from './handlers.js';

export const server = setupServer(...handlers);
