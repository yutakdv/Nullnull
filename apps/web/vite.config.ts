import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Only VITE_* values reach the browser bundle and all of them are public.
// Secrets, raw itinerary text and precise location never go here
// (.claude/rules/frontend.md).
export default defineConfig({
  plugins: [react()],
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
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./vitest.setup.ts'],
    css: false,
  },
});
